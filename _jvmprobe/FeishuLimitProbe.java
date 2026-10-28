import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 实测飞书自定义机器人 interactive 卡片的正文长度上限。
 * 找出「能稳定接受」的最大字符数，用于校准 LogUploader.CHUNK_CHARS。
 */
public class FeishuLimitProbe {

    static final String URL =
            "https://open.feishu.cn/open-apis/bot/v2/hook/7a8c2ca0-be89-418e-9407-0548ca423792";
    static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static void main(String[] args) throws Exception {
        // 纯中文（最坏情况：3 字节/字符）
        int[] sizes = {15000, 20000, 25000, 30000};
        System.out.println("=== 纯中文正文长度上限探测（中文最多，3 字节/字） ===");
        for (int n : sizes) {
            String body = repeat("测", n);
            String resp = send(body);
            boolean ok = resp.contains("\"code\":0") || resp.contains("\"StatusCode\":0");
            System.out.println("chars=" + n + " bytes~" + (n * 3) + " -> " + (ok ? "OK" : "REJECT") + "  " + brief(resp));
            if (!ok) break;
        }

        // 混合内容（更接近真实日志：ASCII + 中文）
        System.out.println("\n=== 混合内容（日志样式）长度探测 ===");
        for (int n : new int[]{20000, 24000, 28000, 30000, 32000, 40000, 50000}) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (sb.length() < n) {
                sb.append("16:11:0").append(i % 10).append(".000 [D] NetClient: 搜索关键词=闪婚 返回 22 条\n");
                i++;
            }
            String body = sb.substring(0, n);
            String resp = send(body + "\n[日志]");
            boolean ok = resp.contains("\"code\":0") || resp.contains("\"StatusCode\":0");
            int bytes = body.getBytes("UTF-8").length;
            System.out.println("chars=" + n + " bytes=" + bytes + " -> " + (ok ? "OK" : "REJECT") + "  " + brief(resp));
        }
    }

    static String send(String content) throws Exception {
        String payload = "{\"msg_type\":\"interactive\",\"card\":{\"header\":{\"title\":{\"tag\":\"plain_text\","
                + "\"content\":\"[日志] 长度探测\"},\"template\":\"blue\"},\"elements\":[{\"tag\":\"markdown\","
                + "\"content\":\"" + escape(content) + "\"}]}}";
        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder().url(URL)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(JSON, payload)).build();
        Response resp = client.newCall(req).execute();
        try {
            return resp.code() + " " + (resp.body() == null ? "" : resp.body().string());
        } finally {
            resp.close();
        }
    }

    static String brief(String s) { return s.length() > 150 ? s.substring(0, 150) : s; }

    static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    static String escape(String s) {
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
}
