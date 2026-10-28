import com.houg.tv.playback.CencMp4Remuxer;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立验证 harness：读 _sample_enc.mp4，用 CencMp4Remuxer.remux 去 CENC，
 * 校验 md5、残留字节、以及 stsd fourcc。不依赖任何 Android 库。
 */
public class RemuxVerify {

    static final String[] RESIDUAL = {"encv", "enca", "sinf", "senc", "saio", "saiz", "tenc", "sgpd", "sbgp"};

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RemuxVerify <enc.mp4> <out.mp4>");
            System.exit(2);
        }
        byte[] enc = Files.readAllBytes(Paths.get(args[0]));
        byte[] key = hex("c075bd047abf9050204865bd472f81e8");

        long t0 = System.currentTimeMillis();
        byte[] out = CencMp4Remuxer.remux(enc, key);
        long ms = System.currentTimeMillis() - t0;

        Files.write(Paths.get(args[1]), out);
        String outMd5 = md5(out);
        System.out.println("INPUT_SIZE=" + enc.length);
        System.out.println("INPUT_MD5=" + md5(enc));
        System.out.println("OUTPUT_SIZE=" + out.length);
        System.out.println("OUTPUT_MD5=" + outMd5);
        System.out.println("ELAPSED_MS=" + ms);

        String expect = "a02a37b8ca8cdba2ca5f6c2c6e1ddd48";
        System.out.println("MD5_MATCH=" + (expect.equals(outMd5) ? "PASS" : "FAIL"));

        for (String r : RESIDUAL) {
            int c = countOccurrences(out, r.getBytes("US-ASCII"));
            System.out.println("RESIDUAL_" + r + "=" + c + (c == 0 ? " (OK)" : " (LEFTOVER!)"));
        }

        System.out.println("STSD_FOURCC=" + stsdFourcc(out));

        // 简单 box 解析完整性：顶层 box 解析到最后字节
        System.out.println("BOX_PARSE_OK=" + parseTopLevelOk(out));
    }

    static int countOccurrences(byte[] buf, byte[] needle) {
        int c = 0;
        outer:
        for (int i = 0; i + needle.length <= buf.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (buf[i + j] != needle[j]) continue outer;
            }
            c++;
        }
        return c;
    }

    static String md5(byte[] b) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /* ---- 独立 box 解析器（不复用工程实现，交叉验证） ---- */

    static final String[] CONTAINERS = {
        "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex",
        "moof", "traf", "mfra", "udta", "wave", "sinf", "schi", "stsd"
    };

    static int u32(byte[] b, int p) {
        return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
    }

    static long u64(byte[] b, int p) {
        return ((long) u32(b, p) << 32) | (u32(b, p + 4) & 0xFFFFFFFFL);
    }

    static String fourcc(byte[] b, int p) {
        return "" + (char) (b[p] & 0xFF) + (char) (b[p + 1] & 0xFF)
                + (char) (b[p + 2] & 0xFF) + (char) (b[p + 3] & 0xFF);
    }

    static boolean isContainer(String t) {
        for (String c : CONTAINERS) if (c.equals(t)) return true;
        return false;
    }

    static class Box {
        String type; int start; int hdr; int end; List<Box> children = new ArrayList<>();
    }

    static List<Box> parse(byte[] b, int start, int end) {
        List<Box> out = new ArrayList<>();
        int i = start;
        while (i + 8 <= end) {
            long size = u32(b, i) & 0xFFFFFFFFL;
            String type = fourcc(b, i + 4);
            int hdr = 8;
            if (size == 1) { size = u64(b, i + 8); hdr = 16; }
            else if (size == 0) { size = end - i; }
            Box box = new Box();
            box.type = type; box.start = i; box.hdr = hdr; box.end = i + (int) size;
            if (isContainer(type)) box.children.addAll(parse(b, i + hdr, box.end));
            out.add(box);
            i = box.end;
        }
        return out;
    }

    static List<Box> findRec(List<Box> boxes, String type) {
        List<Box> out = new ArrayList<>();
        for (Box b : boxes) {
            if (b.type.equals(type)) out.add(b);
            out.addAll(findRec(b.children, type));
        }
        return out;
    }

    /** 输出 stsd 里每个 sample entry 的 fourcc，逗号分隔。 */
    static String stsdFourcc(byte[] buf) {
        List<Box> top = parse(buf, 0, buf.length);
        List<Box> stsdList = findRec(top, "stsd");
        if (stsdList.isEmpty()) return "NO_STSD";
        ByteArrayOutputStream sb = new ByteArrayOutputStream();
        try {
            for (Box stsd : stsdList) {
                int p = stsd.start + stsd.hdr + 4; // fullbox
                int count = u32(buf, p); p += 4;
                for (int e = 0; e < count; e++) {
                    int esize = u32(buf, p);
                    String ft = fourcc(buf, p + 4);
                    sb.write((ft + ";").getBytes("US-ASCII"));
                    p += esize;
                }
            }
        } catch (Exception ex) {
            return "PARSE_ERROR:" + ex;
        }
        return sb.toString();
    }

    /** 顶层 box 逐个解析，确保能完整覆盖到文件末尾（无悬挂字节）。 */
    static String parseTopLevelOk(byte[] buf) {
        try {
            List<Box> top = parse(buf, 0, buf.length);
            if (top.isEmpty()) return "FAIL_EMPTY";
            Box last = top.get(top.size() - 1);
            boolean ok = last.end == buf.length;
            StringBuilder sb = new StringBuilder(ok ? "PASS" : "FAIL_END=" + last.end + "/" + buf.length);
            sb.append(" topboxes=");
            for (Box b : top) sb.append(b.type).append("@").append(b.start).append("+").append(b.end - b.start).append(" ");
            return sb.toString();
        } catch (Exception e) {
            return "FAIL_EXC:" + e;
        }
    }
}
