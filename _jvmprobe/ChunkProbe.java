import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分片逻辑验证：与 LogUploader.splitChunks / hardSplitByBytes / escapeJson 保持同步。
 * 重点：分片不丢内容（拼回原文）、单片字节不超限、超长行按字硬切、分片数封顶、UTF-8 不切断多字节字符。
 */
public class ChunkProbe {

    static final int CHUNK_BYTES = 48 * 1024;
    static final int MAX_CHUNKS = 20;

    public static void main(String[] args) {
        int pass = 0, fail = 0;

        // T1 短日志不分片
        String shortLog = "===== 设备信息 =====\nMi Box\n\n===== 日志 =====\nabc\n";
        List<String> c1 = splitChunks(shortLog);
        if (c1.size() == 1 && c1.get(0).equals(shortLog)) { pass++; p("T1 短日志不分片"); }
        else { fail++; f("T1 短日志不分片", "size=" + c1.size()); }

        // T2 多行日志分片后拼回必须与原文字节一致
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("16:11:00.000 [D] NetClient: 搜索关键词=闪婚 返回 22 条\n");
        String longLog = sb.toString();
        List<String> c2 = splitChunks(longLog);
        if (join(c2).equals(longLog)) { pass++; p("T2 分片无损拼接（" + c2.size() + " 片, " + bytes(longLog) + " 字节）"); }
        else { fail++; f("T2 分片无损拼接", "原 " + longLog.length() + " 拼回 " + join(c2).length()); }

        // T3 每片字节不超限
        int maxB = 0;
        for (String c : c2) maxB = Math.max(maxB, bytes(c));
        if (maxB <= CHUNK_BYTES) { pass++; p("T3 每片字节 <= " + CHUNK_BYTES + "（实测最大 " + maxB + "）"); }
        else { fail++; f("T3 每片字节 <= " + CHUNK_BYTES, "最大 " + maxB); }

        // T4 超长单行硬切，且（在分片数上限内）无损
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 30000; i++) huge.append("中");
        String hugeLog = huge.toString(); // 90000 字节 → 2 片
        List<String> c4 = splitChunks(hugeLog);
        boolean t4ok = join(c4).equals(hugeLog) && c4.size() == 2;
        if (t4ok) { pass++; p("T4 超长单行硬切无损（" + c4.size() + " 片, " + bytes(hugeLog) + " 字节）"); }
        else { fail++; f("T4 超长单行硬切", c4.size() + " 片, 拼回=" + join(c4).equals(hugeLog)); }

        // T4b 硬切不得切断多字节字符（拼回必须正确，无替换符）
        boolean noBroken = !join(c4).contains("\uFFFD") && join(c4).equals(hugeLog);
        if (noBroken) { pass++; p("T4b 硬切未切断多字节字符"); }
        else { fail++; f("T4b 硬切未切断多字节字符", "含替换符或内容不符"); }

        // T4c 每条分片自身也必须是合法 UTF-8（字节边界对齐）
        boolean eachValid = true;
        for (String c : c4) {
            if (!new String(c.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8).equals(c)) { eachValid = false; break; }
        }
        if (eachValid) { pass++; p("T4c 每条分片 UTF-8 边界对齐"); }
        else { fail++; f("T4c 每条分片 UTF-8 边界对齐", "有分片边界错位"); }

        // T5 分片数封顶
        StringBuilder flood = new StringBuilder();
        for (int i = 0; i < 400000; i++) flood.append("x\n"); // 800000 字节 → 17 片（48KB/片）… 用更大数据触发封顶
        for (int i = 0; i < 2000000; i++) flood.append("x\n");
        List<String> c5 = splitChunks(flood.toString());
        if (c5.size() == MAX_CHUNKS) { pass++; p("T5 分片数封顶 " + MAX_CHUNKS + "（数据 " + bytes(flood.toString()) / 1024 + " KB）"); }
        else { fail++; f("T5 分片数封顶", "size=" + c5.size()); }

        // T6 截断提示
        if (c5.get(c5.size() - 1).contains("已截断")) { pass++; p("T6 截断提示存在"); }
        else { fail++; f("T6 截断提示存在", "末片无提示"); }

        // T7 escapeJson
        String esc = escapeJson("a\"b\\c\nd\te\u0001f中文");
        if (esc.equals("a\\\"b\\\\c\\nd\\te\\u0001f中文")) { pass++; p("T7 escapeJson 转义正确"); }
        else { fail++; f("T7 escapeJson 转义正确", "得到 " + esc); }

        // T8 分片标题带 (i/N)
        String t8 = title("Mi Box 4", "10-12 16:40:00", 2, 5);
        if (t8.equals("[日志] HougTime 上报 · Mi Box 4 · 10-12 16:40:00 (2/5)")) { pass++; p("T8 分片标题带 (i/N)"); }
        else { fail++; f("T8 分片标题带 (i/N)", t8); }

        // T9 单片标题不带序号
        String t9 = title("Mi Box 4", "10-12 16:40:00", 1, 1);
        if (t9.equals("[日志] HougTime 上报 · Mi Box 4 · 10-12 16:40:00")) { pass++; p("T9 单片标题不带序号"); }
        else { fail++; f("T9 单片标题不带序号", t9); }

        // T10 真实规模：6 万字符级日志应只需极少片
        StringBuilder real = new StringBuilder();
        for (int i = 0; i < 1500; i++) real.append("16:11:0").append(i % 10).append(".000 [D] NetClient: search keyword=闪婚 建议命中=16\n");
        List<String> c10 = splitChunks(real.toString());
        if (c10.size() <= 4 && join(c10).equals(real.toString())) {
            pass++; p("T10 真实日志 " + bytes(real.toString()) / 1024 + " KB -> " + c10.size() + " 片且无损");
        } else { fail++; f("T10 真实日志分片", c10.size() + " 片, 无损=" + join(c10).equals(real.toString())); }

        System.out.println("\n=== PASS=" + pass + " FAIL=" + fail + " ===");
        if (fail > 0) System.exit(1);
    }

    // ===== 与 LogUploader.kt 同步 =====
    static List<String> splitChunks(String report) {
        if (bytes(report) <= CHUNK_BYTES) return Collections.singletonList(report);
        List<String> chunks = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int sbBytes = 0;
        int i = 0;
        while (i < report.length()) {
            int nl = report.indexOf('\n', i);
            int end = nl < 0 ? report.length() : nl + 1;
            String line = report.substring(i, end);
            i = end;
            int lineBytes = bytes(line);
            if (lineBytes > CHUNK_BYTES) {
                if (sb.length() > 0) { chunks.add(sb.toString()); sb.setLength(0); sbBytes = 0; }
                chunks.addAll(hardSplitByBytes(line));
                continue;
            }
            if (sbBytes + lineBytes > CHUNK_BYTES) { chunks.add(sb.toString()); sb.setLength(0); sbBytes = 0; }
            sb.append(line);
            sbBytes += lineBytes;
        }
        if (sb.length() > 0) chunks.add(sb.toString());
        if (chunks.size() > MAX_CHUNKS) {
            List<String> kept = new ArrayList<>(chunks.subList(0, MAX_CHUNKS));
            kept.set(MAX_CHUNKS - 1, kept.get(MAX_CHUNKS - 1)
                    + "\n……（日志过长，已截断至前 " + MAX_CHUNKS + " 片，共 " + chunks.size() + " 片）……");
            return kept;
        }
        return chunks;
    }

    static List<String> hardSplitByBytes(String text) {
        List<String> out = new ArrayList<>();
        int start = 0, bytes = 0, i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int w = utf8Width(cp);
            if (bytes + w > CHUNK_BYTES) { out.add(text.substring(start, i)); start = i; bytes = 0; }
            bytes += w;
            i += Character.charCount(cp);
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }

    static int utf8Width(int cp) {
        if (cp < 0x80) return 1;
        if (cp < 0x800) return 2;
        if (cp < 0x10000) return 3;
        return 4;
    }

    static String title(String model, String time, int index, int total) {
        String seq = total > 1 ? " (" + index + "/" + total + ")" : "";
        return "[日志] HougTime 上报 · " + model + " · " + time + seq;
    }

    static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static int bytes(String s) { return s.getBytes(StandardCharsets.UTF_8).length; }
    static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p);
        return sb.toString();
    }
    static void p(String m) { System.out.println("[PASS] " + m); }
    static void f(String m, String d) { System.out.println("[FAIL] " + m + " -> " + d); }
}
