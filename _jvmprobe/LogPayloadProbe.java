import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * JVM 端到端验证：按 LogUploader.buildPayload 的逻辑构造 payload，通过真实 webhook 发送，
 * 确认 App 上线后用户能正常收到消息卡片。与 LogUploader.kt 同步维护。
 */
public class LogPayloadProbe {

    static final String URL =
            "https://open.feishu.cn/open-apis/bot/v2/hook/7a8c2ca0-be89-418e-9407-0548ca423792";
    static final String KEYWORD = "日志";
    static final int MAX_BODY = 6000;

    public static void main(String[] args) throws Exception {
        String report = "===== 设备信息 =====\n"
                + "设备型号: Mi Box 4\n"
                + "品牌厂商: XIAOMI/小米\n"
                + "系统版本: Android 9 (API 28)\n"
                + "屏幕分辨率: 1920x1080 px (960x540 dp, density=2.0)\n"
                + "CPU ABI: arm64-v8a\n"
                + "总内存: 2048 MB\n\n"
                + "===== 日志 =====\n"
                + "16:11:02.123 [D] DeviceInfo: 启动完成。\n"
                + "16:11:05.456 [D] NetClient: search keyword=闪婚\n"
                + "16:11:06.789 [D] NetClient: search 建议接口命中=16\n"
                + "16:11:07.012 [D] NetClient: search 返回 22 条（去重后）\n"
                + "16:11:08.345 [E] NetClient: search 建议接口失败（忽略） | timeout\n\n"
                + "===== 接口信息 =====\n"
                + "Base URL: https://api5-normal-sinfonlineb.fqnovel.com\n"
                + "device_id: 1234567890\n"
                + "iid: 9876543210\n"
                + "X-Gorgon: 8404a0b2000000000000000000000000";

        String body = buildPayload(report, "Mi Box 4");
        System.out.println("--- payload length=" + body.length() + " ---");
        System.out.println(body.substring(0, Math.min(300, body.length())));
        System.out.println("--- sending ---");

        String resp = post(body);
        System.out.println("HTTP response: " + resp);
        if (!resp.contains("\"code\":0") && !resp.contains("\"StatusCode\":0")) {
            throw new IllegalStateException("飞书拒绝: " + resp);
        }
        System.out.println("OK #1 — 正常日志 payload 已送达");

        // 超长截断分支
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            big.append("16:11:00.000 [D] Test line ").append(i).append(" abcdefghijk\n");
        }
        String truncated = buildPayload(
                "===== 设备信息 =====\nX\n\n===== 日志 =====\n" + big
                        + "\n\n===== 接口信息 =====\nBase URL: x", "Mi Box 4");
        System.out.println("截断后 payload length=" + truncated.length());
        if (truncated.contains("Test line 400")) throw new IllegalStateException("截断失败：仍含中段");
        if (!truncated.contains("Base URL: x")) throw new IllegalStateException("截断后丢失尾部");
        System.out.println("OK #2 — 超长截断分支通过（保留头尾，中部截断）");

        // 特殊字符转义分支（引号/反斜杠/控制字符）
        String nasty = "===== 日志 =====\n"
                + "quote=\" backslash=\\ tab=\t ctrl=\u0001\n"
                + "===== 接口信息 =====\nBase URL: x";
        String escaped = buildPayload(nasty, "TV\"Box\\1");
        String r3 = post(escaped);
        System.out.println("HTTP response: " + r3);
        if (!r3.contains("\"code\":0") && !r3.contains("\"StatusCode\":0")) {
            throw new IllegalStateException("转义分支被飞书拒绝（JSON 非法）: " + r3);
        }
        System.out.println("OK #3 — 特殊字符转义通过（JSON 合法，飞书接受）");

        System.out.println("\nALL PASS");
    }

    static String post(String body) throws Exception {
        MediaType json = MediaType.parse("application/json; charset=utf-8");
        OkHttpClient client = new OkHttpClient();
        Request req = new Request.Builder().url(URL)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(json, body))
                .build();
        Response resp = client.newCall(req).execute();
        try {
            String text = resp.body() == null ? "" : resp.body().string();
            return "HTTP " + resp.code() + " " + text;
        } finally {
            resp.close();
        }
    }

    /** 与 LogUploader.kt 完全一致。 */
    static String buildPayload(String report, String model) {
        String body = report;
        if (body.length() > MAX_BODY) {
            String head = body.substring(0, 1200);
            String tail = body.substring(body.length() - 800);
            body = head + "\n\n……（中间日志过长已截断）……\n\n" + tail;
        }
        String safeModel = model.replace("\"", "'").replace("\n", " ");
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        String title = "[" + KEYWORD + "] HougTime 上报 · " + safeModel + " · " + time;

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"msg_type\":\"interactive\",");
        sb.append("\"card\":{");
        sb.append("\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"");
        sb.append(escapeJson(title));
        sb.append("\"},\"template\":\"blue\"},");
        sb.append("\"elements\":[{\"tag\":\"markdown\",\"content\":\"");
        sb.append(escapeJson(body));
        sb.append("\"}]}}");
        return sb.toString();
    }

    /** 与 LogUploader.kt 完全一致。 */
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
}
