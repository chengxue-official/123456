package com.houg.tv.playback;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * 去 CENC 重封装：解密 mdat + 重写 moov（encv→hvc1、enca→mp4a、删 sinf/senc/saio/saiz/sgpd/sbgp），
 * 用 free box 补齐使 mdat 文件偏移不变。与 Python 金标准 _decrypt_ref.py 逐字节对齐。
 */
public final class CencMp4Remuxer {

    private static final String[] DROPPED_STBL = {"senc", "saio", "saiz", "sgpd", "sbgp"};

    private CencMp4Remuxer() {}

    /** 输入加密 mp4 字节，输出明文（去 CENC）mp4 字节。不修改入参。 */
    public static byte[] remux(byte[] encryptedMp4, byte[] key) throws Exception {
        byte[] buf = encryptedMp4.clone();

        List<CencMp4Parser.Box> top = CencMp4Parser.parse(buf, 0, buf.length);
        CencMp4Parser.Box moov = first(CencMp4Parser.find(top, "moov"), "moov");
        CencMp4Parser.Box mdat = first(CencMp4Parser.find(top, "mdat"), "mdat");

        // 逐 trak 解密样本（在副本上原地写回）
        List<CencMp4Parser.Box> moovChildren =
                CencMp4Parser.parse(buf, moov.start + moov.hdr, moov.end);
        for (CencMp4Parser.Box trak : CencMp4Parser.find(moovChildren, "trak")) {
            CencMp4Parser.Track info = CencMp4Parser.trackInfo(buf, trak);
            for (int i = 0; i < info.samples.size(); i++) {
                CencMp4Parser.Sample s = info.samples.get(i);
                byte[] iv8 = Arrays.copyOfRange(buf,
                        info.ivBase + i * info.ivSize,
                        info.ivBase + i * info.ivSize + info.ivSize);
                byte[] pt = CencSampleDecryptor.decrypt(
                        Arrays.copyOfRange(buf, s.offset, s.offset + s.size), iv8, key);
                System.arraycopy(pt, 0, buf, s.offset, s.size);
            }
        }

        // 重写 moov
        byte[] newMoov = rebuildContainer(buf, moov);

        // 组装：ftyp + 新 moov + free 补齐 + mdat（保持 mdat 偏移不变）
        int mdatOffset = mdat.start;
        int gap = mdatOffset - (moov.start + newMoov.length);
        byte[] free = gap >= 8 ? buildBox("free", new byte[gap - 8]) : new byte[0];

        byte[] out = new byte[moov.start + newMoov.length + free.length + (mdat.end - mdat.start)];
        System.arraycopy(buf, 0, out, 0, moov.start);
        System.arraycopy(newMoov, 0, out, moov.start, newMoov.length);
        System.arraycopy(free, 0, out, moov.start + newMoov.length, free.length);
        System.arraycopy(buf, mdat.start, out,
                moov.start + newMoov.length + free.length, mdat.end - mdat.start);
        return out;
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
            String newFourcc = mapFourcc(etyp);
            int childOff = p + sampleEntryChildrenOffset(etyp);
            int childEnd = p + esize;

            List<CencMp4Parser.Box> children = CencMp4Parser.parse(buf, childOff, childEnd);
            ByteArrayOutputStream childBytes = new ByteArrayOutputStream();
            for (CencMp4Parser.Box c : children) {
                if ("sinf".equals(c.type)) continue;
                childBytes.write(buf, c.start, c.end - c.start);
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

    private static String mapFourcc(String etyp) {
        if ("encv".equals(etyp) || "bvc1".equals(etyp) || "hev1".equals(etyp)) return "hvc1";
        if ("enca".equals(etyp)) return "mp4a";
        return etyp;
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
