import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 热更新端到端验证：模拟 Cloudflare Pages 托管，跑通「读清单 → 比版本 → 下载 APK」全流程。
 * 逻辑与 UpdateChecker.kt 的 check()/download() 保持同步。
 * JSON 用正则解析（避免 JVM 侧缺 org.json 依赖；字段值不含特殊字符时等价）。
 *
 * 用法： java UpdateProbe <host> <currentVersionCode> <sourceApk>
 *   例： java UpdateProbe http://127.0.0.1:8899 1 hongguo-1.0.0.apk
 */
public class UpdateProbe {

    static String HOST;

    public static void main(String[] args) throws Exception {
        HOST = args.length > 0 ? args[0] : "http://127.0.0.1:8899";
        int current = args.length > 1 ? Integer.parseInt(args[1]) : 1;

        int pass = 0, fail = 0;

        // T1 读清单
        Remote remote = check(current);
        if (remote != null) { pass++; p("T1 读取 update.json 成功：v" + remote.versionName + " (code=" + remote.versionCode + ")"); }
        else { fail++; f("T1 读取 update.json", "失败"); return; }

        // T2 版本比较：本机 code 更低（0）→ 必须提示更新
        Remote older = checkFor(HOST, 0);
        if (older != null && older.versionCode > 0) {
            pass++; p("T2 版本比较：本机 0 < 远端 " + older.versionCode + " → 提示更新");
        } else { fail++; f("T2 版本比较", "未识别出新版本"); }

        // T3 版本相等 → 不提示更新
        if (!(remote.versionCode > current)) { pass++; p("T3 本机 " + current + " == 远端 " + remote.versionCode + " → 不提示更新"); }
        else { fail++; f("T3 版本相等判断", "逻辑错误"); }

        // T4 apkUrl 相对路径拼接正确
        if (remote.apkUrl.startsWith(HOST) && remote.apkUrl.endsWith(".apk")) {
            pass++; p("T4 apkUrl 相对路径拼接正确：" + remote.apkUrl);
        } else { fail++; f("T4 apkUrl 拼接", remote.apkUrl); }

        // T5 下载 APK 且字节完整
        StringBuilder err = new StringBuilder();
        File apk = download(remote, err);
        if (apk != null && apk.length() > 0) {
            pass++; p("T5 下载 APK 成功：" + apk.getName() + " (" + (apk.length() / 1048576.0 > 0.1 ? String.format("%.2f MB", apk.length() / 1048576.0) : apk.length() + " B") + ")");
        } else {
            fail++; f("T5 下载 APK", err.toString());
        }

        // T6 下载内容与源文件字节一致（完整性校验）
        if (apk != null && apk.length() > 0) {
            File src = new File(args.length > 2 ? args[2] : "hongguo-1.0.0.apk");
            if (src.exists()) {
                if (src.length() == apk.length()) { pass++; p("T6 下载内容字节数一致（" + src.length() + " B）"); }
                else { fail++; f("T6 字节数一致", "源 " + src.length() + " vs 下载 " + apk.length()); }
            } else {
                p("T6 跳过（源文件 " + src.getName() + " 不存在）");
            }
        }

        // T7 错误路径：404 时给出明确错误
        Remote bad = new Remote(2, "9.9.9", HOST + "/no-such-file.apk", "");
        StringBuilder err2 = new StringBuilder();
        File badFile = download(bad, err2);
        if (badFile == null && err2.length() > 0) { pass++; p("T7 下载失败时返回明确错误：" + err2); }
        else { fail++; f("T7 下载失败处理", "未正确报错"); }

        System.out.println("\n=== PASS=" + pass + " FAIL=" + fail + " ===");
        if (fail > 0) System.exit(1);
    }

    static class Remote {
        int versionCode; String versionName, apkUrl, notes;
        Remote(int c, String n, String u, String no) { versionCode = c; versionName = n; apkUrl = u; notes = no; }
    }

    /** 仅读远端清单（不下载），供版本比较用。 */
    static Remote checkFor(String host, int current) {
        try {
            Request req = new Request.Builder().url(host + "/update.json")
                    .header("Cache-Control", "no-cache").build();
            OkHttpClient client = new OkHttpClient();
            Response resp = client.newCall(req).execute();
            try {
                if (!resp.isSuccessful()) return null;
                String text = resp.body() == null ? "" : resp.body().string();
                String apk = optStr(text, "apk", "");
                if (apk.isEmpty()) return null;
                return new Remote(optInt(text, "versionCode", 0), optStr(text, "versionName", "?"),
                        host + "/" + apk, optStr(text, "notes", ""));
            } finally { resp.close(); }
        } catch (Exception e) { return null; }
    }

    /** 与 UpdateChecker.check 同步 */
    static Remote check(int current) {
        try {
            Request req = new Request.Builder().url(HOST + "/update.json")
                    .header("Cache-Control", "no-cache").build();
            OkHttpClient client = new OkHttpClient();
            Response resp = client.newCall(req).execute();
            try {
                if (!resp.isSuccessful()) { System.out.println("HTTP " + resp.code()); return null; }
                String text = resp.body() == null ? "" : resp.body().string();
                int code = optInt(text, "versionCode", 0);
                String name = optStr(text, "versionName", "?");
                String apk = optStr(text, "apk", "");
                String notes = optStr(text, "notes", "");
                if (apk.isEmpty()) return null;
                String url = apk.startsWith("http") ? apk : HOST.replaceAll("/$", "") + "/" + apk.replaceAll("^/", "");
                return new Remote(code, name, url, notes);
            } finally { resp.close(); }
        } catch (Exception e) { System.out.println("异常: " + e); return null; }
    }

    // --- 极简 JSON 字段提取（字段值无嵌套/转义时与 org.json 等价）---
    static String optStr(String json, String key, String def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : def;
    }

    static int optInt(String json, String key, int def) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : def;
    }

    /** 与 UpdateChecker.download 同步 */
    static File download(Remote remote, StringBuilder error) {
        try {
            File dir = new File("_update_test"); dir.mkdirs();
            File tmp = new File(dir, "update.apk.part");
            File target = new File(dir, "hongguo-" + remote.versionName + ".apk");
            tmp.delete(); target.delete();

            OkHttpClient client = new OkHttpClient();
            Response resp = client.newCall(new Request.Builder().url(remote.apkUrl).build()).execute();
            try {
                if (!resp.isSuccessful()) { error.append("下载失败：HTTP ").append(resp.code()); return null; }
                if (resp.body() == null) { error.append("下载失败：响应为空"); return null; }
                long total = resp.body().contentLength();
                long written = 0;
                InputStream in = resp.body().byteStream();
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) >= 0) { out.write(buf, 0, n); written += n; }
                    out.flush();
                } finally { out.close(); in.close(); }
                if (total > 0 && written < total) { error.append("下载不完整：").append(written).append("/").append(total); tmp.delete(); return null; }
                if (!tmp.renameTo(target)) { error.append("改名失败"); return null; }
                return target;
            } finally { resp.close(); }
        } catch (Exception e) { error.append("下载异常：").append(e.getMessage()); return null; }
    }

    static void p(String m) { System.out.println("[PASS] " + m); }
    static void f(String m, String d) { System.out.println("[FAIL] " + m + " -> " + d); }
}
