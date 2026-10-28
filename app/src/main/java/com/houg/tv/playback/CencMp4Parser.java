package com.houg.tv.playback;

import java.util.ArrayList;
import java.util.List;

/**
 * 红果 CENC mp4 的 box 解析与样本表展开（绝对偏移）。
 * 与 Python 金标准 _decrypt_ref.py 的 parse / track_info 逐字节对齐。
 */
public final class CencMp4Parser {

    /** 需要递归解析子 box 的容器类型（与 _decrypt_ref.py 的 CONTAINERS 完全一致）。 */
    private static final String[] CONTAINERS = {
        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex",
        "moof", "traf", "mfra", "udta", "wave", "sinf", "schi", "stsd"
    };

    private CencMp4Parser() {}

    /* ---------------- 数据模型 ---------------- */

    public static final class Box {
        public final String type;
        public final int start;            // 绝对偏移（box 起始）
        public final int hdr;              // 头长度：8 或 16（size==1 时）
        public final int end;              // start + size
        public final List<Box> children = new ArrayList<>();

        Box(String type, int start, int hdr, int end) {
            this.type = type;
            this.start = start;
            this.hdr = hdr;
            this.end = end;
        }

        public int size() {
            return end - start;
        }
    }

    public static final class Sample {
        public final int offset;           // 样本在文件中的绝对偏移
        public final int size;

        Sample(int offset, int size) {
            this.offset = offset;
            this.size = size;
        }
    }

    public static final class SampleEntry {
        public final String fourcc;
        public final int start;
        public final int size;

        SampleEntry(String fourcc, int start, int size) {
            this.fourcc = fourcc;
            this.start = start;
            this.size = size;
        }
    }

    /** 单轨的加密信息与展开后的样本表。 */
    public static final class Track {
        public final List<SampleEntry> entries = new ArrayList<>();
        public final List<Sample> samples = new ArrayList<>();
        public int ivBase;                 // saio 指向的 IV 数组绝对偏移
        public int ivSize;                 // saiz default_sample_info_size（8）
        public int sencCount;              // senc sample_count
        public int sampleCount;            // stsz sample_count
        public Box stbl;
    }

    /** tkhd 里的显示几何：宽高（16.16 定点数）与旋转角。 */
    public static final class DisplayGeometry {
        public int width = 0;
        public int height = 0;
        public int rotation = 0;      // 0/90/180/270
    }

    /**
     * 解析 trak 的 tkhd box：width/height 是 16.16 定点数，旋转存放在
     * 3x3 矩阵（[0]=A,[1]=B,[3]=C,[4]=D；竖屏 90° 旋转时 A=D=0, B=1, C=-1）。
     * 用于竖屏短剧的画幅适配（旋转 90° 的视频横向铺满导致人被拉胖）。
     */
    public static DisplayGeometry displayGeometry(byte[] buf, Box trak) {
        DisplayGeometry g = new DisplayGeometry();
        try {
            List<Box> tboxes = parse(buf, trak.start + trak.hdr, trak.end);
            for (Box b : tboxes) {
                if (!"tkhd".equals(b.type)) continue;
                int p = b.start + b.hdr;
                int ver = buf[p] & 0xFF;
                p += 4;                            // version + flags
                if (ver == 1) p += 16;             // v1: creation/modification 各 8 字节
                else p += 8;                       // v0: 各 4 字节
                p += 4;                            // track_ID
                p += 4;                            // reserved
                p += (ver == 1) ? 8 : 4;           // duration
                p += 8;                            // reserved[2]
                p += 8;                            // layer + alternate_group + volume + reserved
                // 3x3 矩阵：9 个 16.16 定点数（36 字节），[0]=a [1]=b [3]=c [4]=d
                float a = readFixed1616(buf, p);
                float bMat = readFixed1616(buf, p + 4);
                float c = readFixed1616(buf, p + 8);
                float d = readFixed1616(buf, p + 16);
                p += 36;
                g.width = Math.round(readFixed1616(buf, p));
                g.height = Math.round(readFixed1616(buf, p + 4));
                // 旋转判定（与标准一致）：
                // 0°: a=1,d=1  90°: b=1,c=-1  180°: a=-1,d=-1  270°: b=-1,c=1
                if (a == 1f && d == 1f) g.rotation = 0;
                else if (a == -1f && d == -1f) g.rotation = 180;
                else if (bMat == 1f && c == -1f) g.rotation = 90;
                else if (bMat == -1f && c == 1f) g.rotation = 270;
                return g;
            }
        } catch (Exception ignored) {
            // 解析失败时返回默认（width=0），调用方回退 ExoPlayer 自身回调
        }
        return g;
    }

    /** 16.16 定点数 → float。 */
    private static float readFixed1616(byte[] buf, int p) {
        int v = readU32(buf, p);
        return v / 65536f;
    }

    /* ---------------- box 解析 ---------------- */

    public static boolean isContainer(String type) {
        for (String c : CONTAINERS) {
            if (c.equals(type)) return true;
        }
        return false;
    }

    /** 解析 [start, end) 区间内的 box，返回该层 box 列表（绝对偏移）。 */
    public static List<Box> parse(byte[] buf, int start, int end) {
        List<Box> out = new ArrayList<>();
        int i = start;
        while (i + 8 <= end) {
            long size = readU32(buf, i) & 0xFFFFFFFFL;
            String type = fourcc(buf, i + 4);
            int hdr = 8;
            if (size == 1) {
                size = readU64(buf, i + 8);
                hdr = 16;
            } else if (size == 0) {
                size = end - i;
            }
            int boxEnd = i + (int) size;
            Box box = new Box(type, i, hdr, boxEnd);
            if (isContainer(type)) {
                box.children.addAll(parse(buf, i + hdr, boxEnd));
            }
            out.add(box);
            i = boxEnd;
        }
        return out;
    }

    public static List<Box> find(List<Box> boxes, String type) {
        List<Box> out = new ArrayList<>();
        for (Box b : boxes) {
            if (b.type.equals(type)) out.add(b);
        }
        return out;
    }

    public static List<Box> findRecursive(List<Box> boxes, String type) {
        List<Box> out = new ArrayList<>();
        for (Box b : boxes) {
            if (b.type.equals(type)) out.add(b);
            out.addAll(findRecursive(b.children, type));
        }
        return out;
    }

    /* ---------------- 单轨样本表展开 ---------------- */

    /**
     * 读取某条轨 stsd 里第一个 sample entry 的 fourcc（hvc1 / hev1 / encv / avc1 …）。
     *
     * <p>与 {@link #trackInfo} 不同：不解析 senc/saio/saiz，因此对「已去 CENC 的
     * 明文 mp4」同样可用（缓存命中路径只有明文文件，没有 senc）。读不到返回空串。
     */
    public static String videoSampleCodec(byte[] buf, Box trak) {
        try {
            List<Box> tboxes = parse(buf, trak.start + trak.hdr, trak.end);
            List<Box> stsds = findRecursive(tboxes, "stsd");
            if (stsds.isEmpty()) return "";
            Box stsd = stsds.get(0);
            int p = stsd.start + stsd.hdr + 4; // FullBox version+flags
            if (p + 4 > buf.length) return "";
            int entryCount = readU32(buf, p);
            p += 4;
            if (entryCount <= 0 || p + 8 > buf.length) return "";
            return fourcc(buf, p + 4);
        } catch (Exception e) {
            return "";
        }
    }

    public static Track trackInfo(byte[] buf, Box trak) {
        Track t = new Track();
        List<Box> tboxes = parse(buf, trak.start + trak.hdr, trak.end);
        Box stbl = findRecursive(tboxes, "stbl").get(0);
        t.stbl = stbl;
        List<Box> stblBoxes = parse(buf, stbl.start + stbl.hdr, stbl.end);

        // stsd
        Box stsd = find(stblBoxes, "stsd").get(0);
        int p = stsd.start + stsd.hdr;
        p += 4; // FullBox version+flags
        int entryCount = readU32(buf, p);
        p += 4;
        for (int e = 0; e < entryCount; e++) {
            int esize = readU32(buf, p);
            String etyp = fourcc(buf, p + 4);
            t.entries.add(new SampleEntry(etyp, p, esize));
            p += esize;
        }

        // senc
        Box senc = find(stblBoxes, "senc").get(0);
        int sp = senc.start + senc.hdr + 4; // 跳过 FullBox
        t.sencCount = readU32(buf, sp);

        // saio（flags & 1 有 aux_info_type+param 则多 8 字节）
        Box saio = find(stblBoxes, "saio").get(0);
        int ap = saio.start + saio.hdr;
        int aver = buf[ap] & 0xFF;
        int aflags = ((buf[ap + 1] & 0xFF) << 16) | ((buf[ap + 2] & 0xFF) << 8) | (buf[ap + 3] & 0xFF);
        ap += 4;
        if ((aflags & 0x1) != 0) ap += 8;
        ap += 4; // entry_count
        t.ivBase = (aver == 1) ? (int) readU64(buf, ap) : readU32(buf, ap);

        // saiz
        Box saiz = find(stblBoxes, "saiz").get(0);
        int zp = saiz.start + saiz.hdr;
        int zflags = ((buf[zp + 1] & 0xFF) << 16) | ((buf[zp + 2] & 0xFF) << 8) | (buf[zp + 3] & 0xFF);
        zp += 4;
        if ((zflags & 0x1) != 0) zp += 8;
        t.ivSize = buf[zp] & 0xFF;
        zp += 1;
        zp += 4; // sample_count（与 stsz 一致）

        // stsz
        Box stsz = find(stblBoxes, "stsz").get(0);
        int szp = stsz.start + stsz.hdr + 4;
        int defaultSize = readU32(buf, szp);
        szp += 4;
        t.sampleCount = readU32(buf, szp);
        szp += 4;
        int[] sizes = new int[t.sampleCount];
        if (defaultSize == 0) {
            for (int i = 0; i < t.sampleCount; i++) sizes[i] = readU32(buf, szp + 4 * i);
        } else {
            for (int i = 0; i < t.sampleCount; i++) sizes[i] = defaultSize;
        }

        // stco / co64
        List<Box> stcoList = find(stblBoxes, "stco");
        int[] chunkOffsets;
        if (!stcoList.isEmpty()) {
            Box stco = stcoList.get(0);
            int cp = stco.start + stco.hdr + 4;
            int ccount = readU32(buf, cp);
            cp += 4;
            chunkOffsets = new int[ccount];
            for (int i = 0; i < ccount; i++) chunkOffsets[i] = readU32(buf, cp + 4 * i);
        } else {
            Box co64 = find(stblBoxes, "co64").get(0);
            int cp = co64.start + co64.hdr + 4;
            int ccount = readU32(buf, cp);
            cp += 4;
            chunkOffsets = new int[ccount];
            for (int i = 0; i < ccount; i++) chunkOffsets[i] = (int) readU64(buf, cp + 8 * i);
        }

        // stsc
        Box stsc = find(stblBoxes, "stsc").get(0);
        int scp = stsc.start + stsc.hdr + 4;
        int scCount = readU32(buf, scp);
        scp += 4;
        int[] firstChunk = new int[scCount];
        int[] spc = new int[scCount];
        for (int i = 0; i < scCount; i++) {
            firstChunk[i] = readU32(buf, scp);
            spc[i] = readU32(buf, scp + 4);
            scp += 12;
        }

        // 逐 chunk 展开样本（与 Python 逐行一致）
        int sampleIdx = 0;
        for (int ci = 0; ci < chunkOffsets.length; ci++) {
            int samplesPerChunk = 1;
            for (int e = 0; e < scCount; e++) {
                if (firstChunk[e] <= ci + 1) samplesPerChunk = spc[e];
                else break;
            }
            int offset = chunkOffsets[ci];
            for (int k = 0; k < samplesPerChunk; k++) {
                if (sampleIdx >= sizes.length) break;
                t.samples.add(new Sample(offset, sizes[sampleIdx]));
                offset += sizes[sampleIdx];
                sampleIdx++;
            }
        }
        return t;
    }

    /* ---------------- 字节工具 ---------------- */

    static int readU32(byte[] buf, int p) {
        return ((buf[p] & 0xFF) << 24) | ((buf[p + 1] & 0xFF) << 16)
                | ((buf[p + 2] & 0xFF) << 8) | (buf[p + 3] & 0xFF);
    }

    static long readU64(byte[] buf, int p) {
        return ((long) readU32(buf, p) << 32) | (readU32(buf, p + 4) & 0xFFFFFFFFL);
    }

    static void writeU32(byte[] out, int p, int v) {
        out[p] = (byte) (v >>> 24);
        out[p + 1] = (byte) (v >>> 16);
        out[p + 2] = (byte) (v >>> 8);
        out[p + 3] = (byte) v;
    }

    /** 读取 4 字节 fourcc（ASCII，逐字节转 char，避免 Charset 差异）。 */
    static String fourcc(byte[] buf, int p) {
        return "" + (char) (buf[p] & 0xFF) + (char) (buf[p + 1] & 0xFF)
                + (char) (buf[p + 2] & 0xFF) + (char) (buf[p + 3] & 0xFF);
    }

    static byte[] fourccBytes(String s) {
        return new byte[]{(byte) s.charAt(0), (byte) s.charAt(1),
                (byte) s.charAt(2), (byte) s.charAt(3)};
    }
}
