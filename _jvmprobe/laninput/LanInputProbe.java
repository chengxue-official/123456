import com.houg.tv.net.LanInputReceiver;
import com.houg.tv.net.LanInputServer;
import com.houg.tv.net.LanInputSession;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LanInputServer 的 JVM 协议级验证：
 * 把生产源码原封不动编译后在 JVM 上启动，用 HttpURLConnection / 裸 Socket 模拟手机端请求。
 *
 * 覆盖：启动/端口、页面可达、/submit 回调触发与中文解码、URL 编码边界、POST 表单、
 * favicon 404、浏览器空闲预连接不阻塞真实请求（历史黑屏/转圈根因）、并发、停止后拒连；
 * 以及「会话保持与恢复」协议面：sid 校验、/state 取回草稿与光标、/draft 增量保存、
 * 电视端离开后 503、空闲 TTL 自动关闭、LanInputSession 端口复用/绑定/快照。
 */
public class LanInputProbe {

    static int passed = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  | " + detail));
        } else {
            failures.add(name + "  | " + detail);
            System.out.println("FAIL  " + name + "  | " + detail);
        }
    }

    /** 用 GET 打服务，返回 [状态码, 响应体]。400+ 也返回响应体而不是抛异常。 */
    static String[] get(String path) throws Exception {
        URL url = new URL("http://127.0.0.1:" + PORT + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("GET");
        return read(conn);
    }

    /** 表单 POST（和手机原生表单提交一致：Accept: text/html）。 */
    static String[] postForm(String path, String kw) throws Exception {
        return postBody(path, "kw=" + URLEncoder.encode(kw, "UTF-8"));
    }

    /** 任意 body 的 POST（/draft 需同时带 kw 与 cursor）。 */
    static String[] postBody(String path, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + PORT + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        return read(conn);
    }

    static String[] read(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream raw = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder body = new StringBuilder();
        if (raw != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) body.append(line).append('\n');
            reader.close();
        }
        conn.disconnect();
        return new String[]{String.valueOf(code), body.toString()};
    }

    static int PORT;

    public static void main(String[] args) throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        LanInputServer server = new LanInputServer(kw -> {
            received.add(kw);
            return kotlin.Unit.INSTANCE;
        });

        // ---- 1. 启动与端口 ----
        int port = server.start();
        check("启动返回有效端口", port > 0, "port=" + port);
        check("port 属性一致", server.getPort() == port, "get=" + server.getPort());
        check("重复 start 幂等", server.start() == port, "二次 start 返回同端口");

        String addr = server.localAddress();
        check("本机地址探测", addr != null && !addr.isEmpty(), "addr=" + addr);

        PORT = port;
        Thread.sleep(200);

        // ---- 2. 浏览器空闲预连接不阻塞页面（黑屏/转圈根因回归）----
        // 手机浏览器会先建 2~3 条 socket 却不发请求；旧实现串行 + 5s 超时会把真实请求卡死。
        List<Socket> idle = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Socket s = new Socket();
            s.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), 2000);
            idle.add(s);
        }
        long t0 = System.currentTimeMillis();
        String[] page = get("/");
        long pageMs = System.currentTimeMillis() - t0;
        check("3 条空闲预连接下首页仍 200", page[0].equals("200"), "code=" + page[0]);
        check("首页首字节不被预连接拖死（<1500ms）", pageMs < 1500, "耗时=" + pageMs + "ms");
        for (Socket s : idle) {
            try { s.close(); } catch (Exception ignored) { }
        }
        check("页面含输入框与 POST 表单",
                page[1].contains("id=\"kw\"") && page[1].contains("name=\"kw\"")
                        && page[1].contains("method=\"post\"") && page[1].contains("/submit"),
                "含 kw 输入框+表单=" + page[1].contains("method=\"post\""));
        check("页面标题正确", page[1].contains("红果TV"), "");

        // ---- 3. GET /submit 回调（含中文与特殊字符）----
        submit("都市", "纯中文");
        submit("庆余年", "剧名·中文");
        submit("a b+c", "空格加号（往返应保持不变）");
        submit("%E5%BA%86%E4%BD%99%E5%B9%B4", "百分号字面量（往返应保持不变）");
        submit("", "空关键词（应不触发回调）");
        // 直接构造原始 query 验证 `+` 号解码为空格（URLEncoder 会把空格编成 +）
        String[] plus = get("/submit?kw=" + "红果+TV");
        check("submit 加号即空格 返回 200/ok", plus[0].equals("200"), "code=" + plus[0]);

        // ---- 4. POST 表单提交（无 JS 路径）----
        String[] posted = postForm("/submit", "凡人修仙传");
        check("POST 表单提交 返回 200", posted[0].equals("200"), "code=" + posted[0]);
        check("POST 表单返回落地页", posted[1].contains("已发送到电视"), posted[1].trim());

        // ---- 5. favicon 不再返回整页 HTML ----
        String[] icon = get("/favicon.ico");
        check("favicon 返回 404", icon[0].equals("404"), "code=" + icon[0]);
        check("favicon 不返回页面", !icon[1].contains("<html"), "");

        Thread.sleep(400);
        check("回调触发 6 次（空词不计）", received.size() == 6, "received=" + received);
        if (received.size() >= 6) {
            check("中文正确解码", "都市".equals(received.get(0)) && "庆余年".equals(received.get(1)),
                    received.toString());
            check("空格+加号 往返不变", "a b+c".equals(received.get(2)), received.get(2));
            check("百分号字面量 往返不变", "%E5%BA%86%E4%BD%99%E5%B9%B4".equals(received.get(3)), received.get(3));
            check("query 中 + 解码为空格", "红果 TV".equals(received.get(4)), received.get(4));
            check("POST 中文正确解码", "凡人修仙传".equals(received.get(5)), received.get(5));
        }

        // ---- 6. 并发 5 个请求 ----
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    get("/submit?kw=" + URLEncoder.encode("并发" + idx, "UTF-8"));
                } catch (Exception e) {
                    System.out.println("并发请求失败: " + e);
                }
            });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        Thread.sleep(400);
        check("并发 5 请求全部到达", received.size() == 6 + 5, "received=" + received.size());

        // ---- 7. 会话保持与恢复：sid 校验 / 草稿续传 / 电视离开 503 ----
        String sid = server.getSessionId();
        check("sid 长度 8", sid != null && sid.length() == 8, "sid=" + sid);
        check("页面下发 sid", page[1].contains("sid=" + sid), "");

        String[] st0 = get("/state");
        check("/state 无 sid 返回 403", st0[0].equals("403"), "code=" + st0[0]);
        String[] stBad = get("/state?sid=deadbeef");
        check("/state 错 sid 返回 403", stBad[0].equals("403"), "code=" + stBad[0]);

        String[] st1 = get("/state?sid=" + sid);
        check("/state 正确 sid 返回 200", st1[0].equals("200"), "code=" + st1[0]);
        check("/state 含空草稿与光标",
                st1[1].contains("\"sid\":\"" + sid + "\"") && st1[1].contains("\"draft\":\"\"")
                        && st1[1].contains("\"cursor\":0"),
                st1[1].trim());

        // 手机端页面刷新后：凭 sid 保存草稿 → 重开页面取回续写
        String[] d1 = postBody("/draft?sid=" + sid,
                "kw=" + URLEncoder.encode("  庆余", "UTF-8") + "&cursor=2");
        check("/draft 保存返回 200", d1[0].equals("200"), "code=" + d1[0]);
        String[] st2 = get("/state?sid=" + sid);
        check("草稿可续传且前后空格原样保留（不 trim）",
                st2[1].contains("\"draft\":\"  庆余\"") && st2[1].contains("\"cursor\":2"),
                st2[1].trim());
        String[] page2 = get("/?sid=" + sid);
        check("刷新页面仍认回同一会话", page2[0].equals("200") && page2[1].contains("sid=" + sid),
                "code=" + page2[0]);

        // 提交后草稿清空（未提交组合串已消费）
        String[] s1 = postBody("/submit?sid=" + sid,
                "kw=" + URLEncoder.encode("草稿清空验证", "UTF-8"));
        check("带 sid 提交 200", s1[0].equals("200"), "code=" + s1[0]);
        String[] st3 = get("/state?sid=" + sid);
        check("提交后草稿清空", st3[1].contains("\"draft\":\"\"") && st3[1].contains("\"cursor\":0"),
                st3[1].trim());

        // 错 sid 的提交被拒
        String[] badSubmit = get("/submit?kw=abc&sid=wrongsid");
        check("错 sid 提交 403", badSubmit[0].equals("403"), "code=" + badSubmit[0]);

        // 电视端已离开搜索页（无接收方）→ 503 明确告知
        server.setReceiverAvailable(() -> false);
        String[] away = postForm("/submit", "没人接");
        check("电视离开后提交 503", away[0].equals("503") && away[1].contains("已离开搜索页"),
                "code=" + away[0]);
        String[] awayText = get("/submit?kw=xyz");
        check("非 HTML 请求 503 返回 tv-away", awayText[0].equals("503") && awayText[1].contains("tv-away"),
                "code=" + awayText[0]);
        server.setReceiverAvailable(null);

        // ---- 8. 空闲 TTL 自动关闭 ----
        LanInputServer shortLived = new LanInputServer(null);
        shortLived.setIdleTimeoutMs(600);
        int sp = shortLived.start();
        check("短 TTL 服务可启动", sp > 0, "port=" + sp);
        PORT = sp;
        Thread.sleep(1800);
        boolean expiredRefused = false;
        try {
            get("/");
        } catch (Exception e) {
            expiredRefused = true;
        }
        check("空闲超时后自动关闭（拒连）", expiredRefused, "TTL=600ms，1.8s 后应已自停");
        check("isExpired 判定为真", shortLived.isExpired(System.currentTimeMillis()), "");
        check("过期后 port 归零", shortLived.getPort() == 0, "port=" + shortLived.getPort());
        shortLived.stop();

        // ---- 9. LanInputSession：进程级会话（端口复用/绑定/快照/停用）----
        LanInputSession session = LanInputSession.INSTANCE;
        check("TTL 常量 10 分钟", LanInputSession.TTL_MS == 600000L, "TTL_MS=" + LanInputSession.TTL_MS);
        int sp1 = session.ensureStarted();
        check("会话首次启动返回端口", sp1 > 0, "port=" + sp1);
        check("再次 ensureStarted 复用端口", session.ensureStarted() == sp1, "");
        check("isAlive 为真", session.isAlive(), "");
        check("port() 一致", session.port() == sp1, "port=" + session.port());
        String u = session.url();
        check("会话 URL 带端口与 sid", u != null && u.contains(":" + sp1) && u.contains("?sid="),
                "url=" + u);
        check("未绑定接收方 canReceive=false", !session.canReceive(), "");

        PORT = sp1;
        Rec rec = new Rec();
        session.bind(rec);
        check("bind 后 canReceive=true", session.canReceive(), "");
        String[] rs = postForm("/submit", "会话续传");
        check("绑定后提交 200", rs[0].equals("200"), "code=" + rs[0]);
        Thread.sleep(200);
        check("会话回调送达接收方", rec.keywords.size() == 1 && "会话续传".equals(rec.keywords.get(0)),
                rec.keywords.toString());

        session.unbind(rec);
        check("unbind 后 canReceive=false", !session.canReceive(), "");
        String[] rs2 = postForm("/submit", "无人接收");
        check("unbind 后提交 503", rs2[0].equals("503"), "code=" + rs2[0]);

        session.saveSnapshot("庆余年", 2);
        LanInputSession.Snapshot snap = session.snapshotOrNull();
        check("快照文本与光标可恢复",
                snap != null && "庆余年".equals(snap.getText()) && snap.getSelection() == 2,
                String.valueOf(snap));

        session.stop();
        check("stop 后 isAlive=false", !session.isAlive(), "");
        check("stop 后 port()==0", session.port() == 0, "");
        check("stop 后 url()==null", session.url() == null, "");

        // ---- 10. 停止后拒连 ----
        PORT = port; // 切回主服务端口（前两节临时换过端口）
        server.stop();
        Thread.sleep(200);
        boolean refused = false;
        try {
            get("/");
        } catch (Exception e) {
            refused = true;
        }
        check("停止后拒绝连接", refused, "stop 后再次请求应抛异常");

        System.out.println();
        System.out.println("==== 结果: " + passed + " 通过, " + failures.size() + " 失败 ====");
        for (String f : failures) {
            System.out.println("  FAILED: " + f);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    static void submit(String kw, String label) throws Exception {
        String q = kw.isEmpty() ? "" : ("?kw=" + URLEncoder.encode(kw, "UTF-8"));
        String[] r = get("/submit" + q);
        boolean ok = kw.isEmpty() ? r[0].equals("400") : (r[0].equals("200") && r[1].contains("ok"));
        check("submit " + label, ok, "code=" + r[0] + " body=" + r[1].trim());
    }

    /** LanInputSession 的接收方桩：记录送达的关键词。 */
    static class Rec implements LanInputReceiver {
        final List<String> keywords = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onLanKeyword(String keyword) {
            keywords.add(keyword);
        }
    }
}
