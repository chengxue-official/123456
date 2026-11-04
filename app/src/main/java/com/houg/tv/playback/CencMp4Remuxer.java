package com.houg.tv.playback;

import com.houg.tv.util.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 去 CENC 重封装：解密 mdat + 重写 moov（encv→hvc1/av01、enca→mp4a、删 sinf/senc/saio/saiz/sgpd/sbgp），
 * 用 free box 补齐使 mdat 文件偏移不变。与 Python 金标准 _decrypt_ref.py 逐字节对齐。
 *
 * <p>2026-10 修复（线上 OOM：`Failed to allocate a 27994450 byte allocation`）：
 * 旧实现把整集文件读进 byte[]、再按整集大小分配输出数组，峰值内存 ≈ 2×文件大小，
 * 1.8GB 老电视/32 位进程直接 OOM。现改为<b>流式重封装</b>：内存里只保留
 * `[0, mdat)` 的头部（ftyp+moov，通常 ~200KB），mdat 区按样本逐块读、解密、写出，
 * 峰值内存 = 最大单样本（几百 KB）+ 64KB 拷贝缓冲。
 */
public final class CencMp4Remuxer {

    private static final String[] DROPPED_STBL = {"senc", "saio", "saiz", "sgpd", "sbgp"};

    private static final String TAG = "CencMp4Remuxer";

    /**
     * AV1 兜底配置盒（{@code av1C}）：marker+version=1、seq_profile=0、seq_level_idx_0=8、
     * 8bit 4:2:0、无 configOBUs。片源只把 frma 改成 av01、没给 av1C 时补上这一份，
     * 否则 MediaExtractor/ExoPlayer 认不出视频轨，只剩声音。
     */
    private static final byte[] FALLBACK_AV1C = {(byte) 0x81, 0x08, 0x0C, 0x00};

    /** 头部（ftyp+moov 及之间的 box）上限：正常约 200KB，超限说明结构异常，交给上层兜底。 */
    private static final int MAX_HEAD_BYTES = 16 * 1024 * 1024;

    /** 单样本上限：正常视频帧几百 KB；异常文件不整块申请内存。 */
    private static final int MAX_SAMPLE_BYTES = 8 * 1024 * 1024;

    /** 原样拷贝的块大小。 */
    private static final int COPY_CHUNK = 64 * 1024;

    private CencMp4Remuxer() {}

    /**
     * 加密 mp4 文件 → 明文 mp4 文件（流式，峰值内存 MB 级）。不修改入参文件。
     *
     * <p>布局与旧 byte[] 版本完全一致：`ftyp… + 新 moov + free 补齐 + mdat（样本解密）`，
     * mdat 的文件偏移保持不变（stco/co64 里存的是绝对偏移，不能移动）。
     */
    public static void remuxToFile(File encrypted, File clear, byte[] key) throws Exception {
        if (key == null || key.length != 16) throw new Exception("CENC 密钥长度异常");
        try (RandomAccessFile raf = new RandomAccessFile(encrypted, "r")) {
            long length = raf.length();

            // ---------- 1. 顺序扫描顶层 box 头，定位 moov / mdat ----------
            long moovStart = -1;
            long mdatStart = -1;
            long mdatEnd = -1;
            long p = 0;
            while (p + 8 <= length) {
                byte[] hdr = new byte[16];
                raf.seek(p);
                raf.readFully(hdr, 0, (int) Math.min(16, length - p));
                long size = CencMp4Parser.readU32(hdr, 0) & 0xFFFFFFFFL;
                String type = CencMp4Parser.fourcc(hdr, 4);
                int h = 8;
                if (size == 1) {
                    if (length - p < 16) throw new Exception("mp4 box 头被截断: " + type);
                    size = CencMp4Parser.readU64(hdr, 8);
                    h = 16;
                } else if (size == 0) {
                    size = length - p;
                }
                if (size < h || p + size > length) throw new Exception("mp4 box 长度异常: " + type);
                if ("moov".equals(type)) {
                    moovStart = p;
                } else if ("mdat".equals(type)) {
                    mdatStart = p;
                    mdatEnd = p + size;
                }
                p += size;
            }
            if (moovStart < 0 || mdatStart < 0) throw new Exception("缺少 moov 或 mdat box");
            if (moovStart > mdatStart) throw new Exception("moov 不在 mdat 之前，暂不支持");
            if (mdatStart > MAX_HEAD_BYTES) {
                throw new Exception("mp4 头部过大: " + mdatStart + " 字节");
            }

            // ---------- 2. 只把头部读进内存（解析与重写 moov 都在这块 buf 内完成） ----------
            byte[] head = new byte[(int) mdatStart];
            raf.seek(0);
            raf.readFully(head);

            List<CencMp4Parser.Box> top = CencMp4Parser.parse(head, 0, head.length);
            CencMp4Parser.Box moov = first(CencMp4Parser.find(top, "moov"), "moov");

            // 逐 trak 展开样本（含各自 IV 偏移），再按文件偏移排序 → 可顺序流式处理
            List<EncSample> samples = new ArrayList<>();
            List<CencMp4Parser.Box> moovChildren =
                    CencMp4Parser.parse(head, moov.start + moov.hdr, moov.end);
            for (CencMp4Parser.Box trak : CencMp4Parser.find(moovChildren, "trak")) {
                CencMp4Parser.Track info = CencMp4Parser.trackInfo(head, trak);
                for (int i = 0; i < info.samples.size(); i++) {
                    CencMp4Parser.Sample s = info.samples.get(i);
                    int ivOffset = info.ivBase + i * info.ivSize;
                    if (s.offset < mdatStart || (long) s.offset + s.size > mdatEnd) {
                        throw new Exception("样本超出 mdat 范围");
                    }
                    if (ivOffset < 0 || (long) ivOffset + info.ivSize > head.length) {
                        throw new Exception("样本 IV 越界");
                    }
                    samples.add(new EncSample(s.offset, s.size, ivOffset, info.ivSize));
                }
            }
            // ⚠️ 不用 samples.sort(Comparator.comparingInt(...))：
            // 1) List.sort / Comparator.comparingInt 都是 API 24+，本项目未开
            //    coreLibraryDesugaring，API 21~23 会直接 NoSuchMethodError；
            // 2) lambda 会生成 $$ExternalSyntheticLambda0 类，老设备内存紧张时类加载失败，
            //    线上（9R59 G910M / Android 5.1.1）报错只剩这个类名：
            //    `起播失败 vid=... | ...CencMp4Remuxer$$ExternalSyntheticLambda0`。
            Collections.sort(samples, new Comparator<EncSample>() {
                @Override
                public int compare(EncSample a, EncSample b) {
                    return a.offset < b.offset ? -1 : (a.offset == b.offset ? 0 : 1);
                }
            });

            // ---------- 3. 重写 moov，并按「原样拷贝 / 逐样本解密」流式写出 ----------
            byte[] newMoov = rebuildContainer(head, moov);
            long gap = mdatStart - (moov.start + (long) newMoov.length);
            // BUG-117：mdat 区按原偏移逐样本拷贝（stco/co64 存的是绝对偏移），头部总长
            // 必须保持不变，空隙用 free box 补齐。free box 自身至少 8 字节，空隙不足 8
            // 时补不进去，mdat 会整体前移，产物必然损坏 —— 这里明确报错交给上层重试别的
            // 变体（正常 HG 片源删掉 senc/saio/saiz/sinf 后空隙都在几百字节以上，走不到）。
            if (gap < 8) {
                throw new Exception("重封装头部空隙不足(" + gap + " 字节)，无法保持 mdat 偏移");
            }
            byte[] free = buildBox("free", new byte[(int) gap - 8]);

            try (FileOutputStream fos = new FileOutputStream(clear)) {
                fos.write(head, 0, moov.start);
                fos.write(newMoov);
                fos.write(free);

                byte[] raw = new byte[COPY_CHUNK];
                long pos = mdatStart;
                for (EncSample s : samples) {
                    if (s.offset < pos) throw new Exception("样本偏移重叠");
                    copyRange(raf, pos, s.offset - pos, fos, raw);
                    if (s.size > MAX_SAMPLE_BYTES) throw new Exception("样本过大: " + s.size);
                    byte[] cipher = new byte[s.size];
                    raf.seek(s.offset);
                    raf.readFully(cipher);
                    byte[] iv = Arrays.copyOfRange(head, s.ivOffset, s.ivOffset + s.ivSize);
                    byte[] plain = CencSampleDecryptor.decrypt(cipher, iv, key);
                    fos.write(plain, 0, s.size);
                    pos = s.offset + (long) s.size;
                }
                copyRange(raf, pos, mdatEnd - pos, fos, raw);
                fos.flush();
            }
        }
    }

    /** 文件区间 [from, from+len) 原样拷到输出流（mdat 里非样本区、box 头等）。 */
    private static void copyRange(RandomAccessFile raf, long from, long len,
                                  FileOutputStream fos, byte[] buf) throws Exception {
        if (len <= 0) return;
        raf.seek(from);
        long left = len;
        while (left > 0) {
            int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new Exception("源文件被截断");
            fos.write(buf, 0, n);
            left -= n;
        }
    }

    /** 一个待解密样本：文件偏移、长度、IV 偏移与 IV 长度。 */
    private static final class EncSample {
        final int offset;
        final int size;
        final int ivOffset;
        final int ivSize;

        EncSample(int offset, int size, int ivOffset, int ivSize) {
            this.offset = offset;
            this.size = size;
            this.ivOffset = ivOffset;
            this.ivSize = ivSize;
        }
    }

    /* ---------------- 重写 ---------------- */

    private static byte[] rebuildContainer(byte[] buf, CencMp4Parser.Box box) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (CencMp4Parser.Box c : box.children) {
            if ("stbl".equals(c.type)) {
                byte[] rebuilt = rebuildStbl(buf, c);
                out.write(rebuilt, 0, rebuilt.length);
            } else if (CencMp4Parser.isContainer(c.type)) {
                byte[] rebuilt = rebuildContainer(buf, c);
                out.write(rebuilt, 0, rebuilt.length);
            } else {
                out.write(buf, c.start, c.end - c.start);
            }
        }
        return buildBox(box.type, out.toByteArray());
    }

    private static byte[] rebuildStbl(byte[] buf, CencMp4Parser.Box stbl) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (CencMp4Parser.Box c : stbl.children) {
            if (isDropped(c.type)) continue;
            if ("stsd".equals(c.type)) {
                byte[] rebuilt = rebuildStsd(buf, c);
                out.write(rebuilt, 0, rebuilt.length);
            } else {
                out.write(buf, c.start, c.end - c.start);
            }
        }
        return buildBox("stbl", out.toByteArray());
    }

    private static byte[] rebuildStsd(byte[] buf, CencMp4Parser.Box stsd) {
        int p = stsd.start + stsd.hdr;
        byte[] fullbox = Arrays.copyOfRange(buf, p, p + 4); // version+flags
        int entryCount = CencMp4Parser.readU32(buf, p + 4);
        p += 8;

        ByteArrayOutputStream entries = new ByteArrayOutputStream();
        for (int e = 0; e < entryCount; e++) {
            int esize = CencMp4Parser.readU32(buf, p);
            String etyp = CencMp4Parser.fourcc(buf, p + 4);
            int childOff = p + sampleEntryChildrenOffset(etyp);
            int childEnd = p + esize;

            List<CencMp4Parser.Box> children = CencMp4Parser.parse(buf, childOff, childEnd);
            String newFourcc = mapFourcc(buf, etyp, children);
            ByteArrayOutputStream childBytes = new ByteArrayOutputStream();
            boolean hasAv1c = false;
            byte[] bv2cPayload = null;
            for (CencMp4Parser.Box c : children) {
                if ("sinf".equals(c.type)) continue;
                // 字节自定义的 AV1 配置盒（bytevc2 的 bv2C），ExoPlayer 不认它的四字符码；
                // 结构与 AV1CodecConfigurationRecord 相同，取 payload 改写成标准 av1C。
                if ("bv2C".equals(c.type)) {
                    bv2cPayload = Arrays.copyOfRange(buf, c.start + c.hdr, c.end);
                    continue;
                }
                // 容器头与码流编码不符时（frma 写 hvc1、实际是 AV1），残留的 hvcC/avcC
                // 只会让解析器找错配置盒，直接丢弃。
                if ("av01".equals(newFourcc)
                        && ("hvcC".equals(c.type) || "avcC".equals(c.type))) continue;
                if ("av1C".equals(c.type)) hasAv1c = true;
                childBytes.write(buf, c.start, c.end - c.start);
            }
            if ("av01".equals(newFourcc) && !hasAv1c) {
                byte[] payload = bv2cPayload != null ? bv2cPayload : FALLBACK_AV1C;
                if (bv2cPayload != null) {
                    // 线上「AV1 有声黑屏」定案用：bv2C 的内容是否符合标准 av1C 记录
                    // （首字节应为 0x81 = marker 1 + version 1）。
                    AppLog.INSTANCE.d(TAG, "bv2C payload len=" + bv2cPayload.length
                            + " head=" + hexPreview(bv2cPayload));
                }
                byte[] av1c = buildBox("av1C", payload);
                childBytes.write(av1c, 0, av1c.length);
            }
            if ("encv".equals(etyp) || "av01".equals(etyp)) {
                // 线上 AV1 集「无视频轨」定案用：原始子盒里到底有什么配置盒
                AppLog.INSTANCE.d(TAG, "stsd " + etyp + "→" + newFourcc + " 子盒="
                        + boxTypes(children) + (hasAv1c ? " 含av1C"
                        : (bv2cPayload != null ? " bv2C→av1C" : " 补av1C")));
            }

            // fixed 跳过原 entry 的 8 字节 size+fourcc 头，只保留 sample_entry_base + visual/audio 固定字段。
            byte[] fixed = Arrays.copyOfRange(buf, p + 8, childOff);
            byte[] body = concat(fixed, childBytes.toByteArray());

            byte[] sizeBytes = new byte[4];
            CencMp4Parser.writeU32(sizeBytes, 0, body.length + 8);
            entries.write(sizeBytes, 0, 4);
            entries.write(CencMp4Parser.fourccBytes(newFourcc), 0, 4);
            entries.write(body, 0, body.length);
            p += esize;
        }

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(fullbox, 0, fullbox.length);
        byte[] countBytes = new byte[4];
        CencMp4Parser.writeU32(countBytes, 0, entryCount);
        payload.write(countBytes, 0, 4);
        payload.write(entries.toByteArray(), 0, entries.size());
        return buildBox("stsd", payload.toByteArray());
    }

    private static String boxTypes(List<CencMp4Parser.Box> children) {
        StringBuilder sb = new StringBuilder();
        for (CencMp4Parser.Box c : children) {
            if (sb.length() > 0) sb.append(',');
            sb.append(c.type);
        }
        return sb.toString();
    }

    /**
     * 加密 sample entry → 明文四字符码。
     *
     * ⚠️ `encv` 不能一律当 HEVC：AV1（bytevc2）片源的 entry 同样是 `encv`，
     * 但原始格式在 `sinf/frma` 里写着 `av01`。旧实现无脑改成 `hvc1`，会产出
     * 「挂 hvc1 头、喂 AV1 码流」的畸形文件，MediaExtractor 找不到可用视频轨，
     * 线上表现为「该集没有可解码的视频轨（只有声音）」（2026-11-03 TCL 日志）。
     */
    private static String mapFourcc(byte[] buf, String etyp, List<CencMp4Parser.Box> children) {
        if ("encv".equals(etyp)) {
            // 以配置盒为准，不信 frma：2.0.15 线上日志 2bidew6y2b（TCL MT5879，19:19）
            // 出现 frma=hvc1、子盒却是 bv2C（AV1 配置）的片源，按 frma 改成 hvc1 后
            // ExoPlayer 找不到 hvcC，报「没有可解码的视频轨（只有声音）」。
            if (hasChild(children, "bv2C") || hasChild(children, "av1C")) return "av01";
            if (hasChild(children, "hvcC")) return "hvc1";
            if (hasChild(children, "avcC")) return "avc1";
            String original = originalFormat(buf, children);
            if ("av01".equals(original)) return "av01";
            if ("avc1".equals(original) || "avc3".equals(original)) return "avc1";
            // hvc1/hev1/bvc1 或老片源没写 frma 时按历史行为兜底为 hvc1
            return "hvc1";
        }
        if ("bvc1".equals(etyp) || "hev1".equals(etyp)) return "hvc1";
        if ("enca".equals(etyp)) return "mp4a";
        return etyp;
    }

    /** 前 8 字节的十六进制预览，用于线上定案配置盒内容。 */
    private static String hexPreview(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(8, data.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Integer.toHexString(data[i] & 0xff));
        }
        if (data.length > n) sb.append(" ...");
        return sb.toString();
    }

    private static boolean hasChild(List<CencMp4Parser.Box> children, String type) {
        for (CencMp4Parser.Box c : children) {
            if (type.equals(c.type)) return true;
        }
        return false;
    }

    /** 从 `sinf/frma` 读原始编码格式；缺失或解析异常返回 null。 */
    private static String originalFormat(byte[] buf, List<CencMp4Parser.Box> children) {
        for (CencMp4Parser.Box c : children) {
            if (!"sinf".equals(c.type)) continue;
            try {
                List<CencMp4Parser.Box> inner =
                        CencMp4Parser.parse(buf, c.start + c.hdr, c.end);
                for (CencMp4Parser.Box b : inner) {
                    if ("frma".equals(b.type)) {
                        return CencMp4Parser.fourcc(buf, b.start + b.hdr);
                    }
                }
            } catch (Exception ignored) {
                // 结构异常时按无 frma 处理，走兜底
            }
        }
        return null;
    }

    /** sample entry 子 box 起始偏移：视频 = 8+8+70=86；音频 = 8+8+20=36。 */
    private static int sampleEntryChildrenOffset(String fourcc) {
        if ("encv".equals(fourcc) || "avc1".equals(fourcc) || "hvc1".equals(fourcc)
                || "hev1".equals(fourcc) || "bvc1".equals(fourcc)) {
            return 8 + 8 + 70;
        }
        return 8 + 8 + 20;
    }

    private static boolean isDropped(String type) {
        for (String d : DROPPED_STBL) {
            if (d.equals(type)) return true;
        }
        return false;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] buildBox(String type, byte[] payload) {
        byte[] out = new byte[payload.length + 8];
        CencMp4Parser.writeU32(out, 0, payload.length + 8);
        System.arraycopy(CencMp4Parser.fourccBytes(type), 0, out, 4, 4);
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    private static CencMp4Parser.Box first(List<CencMp4Parser.Box> boxes, String type)
            throws Exception {
        if (boxes.isEmpty()) throw new Exception("缺少 " + type + " box");
        return boxes.get(0);
    }
}
