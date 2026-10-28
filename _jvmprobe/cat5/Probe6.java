import android.content.Context;
import com.houg.tv.sign.DeviceRegister;
import com.houg.tv.sign.SignedRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Probe6：继续摸底红果官方「全库搜索」接口路径。
 * 参照番茄系命名习惯批量探测；命中标准 = HTTP 200 且 body 含 series_id/series_name/book_data 等。
 */
public class Probe6 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();
    static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "search_probe.txt";
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outPath, false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        String q = "你疯了别惹他";
        String[] paths = {
                "/reading/bookapi/search/page/v/",
                "/reading/bookapi/search/sug/v/",
                "/reading/bookapi/search/v/",
                "/reading/bookapi/search/book/v/",
                "/reading/distribution/search/page/v/",
                "/reading/distribution/search/word/v/",
                "/reading/distribution/search/item/v/",
                "/reading/distribution/search/list/v/",
                "/reading/distribution/search/result/v/",
                "/reading/distribution/search/tab/v/",
                "/reading/search/v/",
                "/novel/search/v/",
                "/reading/distribution/search/sug/",
                "/reading/bookapi/search/sug/",
                "/reading/distribution/search/page/",
        };
        for (String path : paths) {
            // POST 形态
            try {
                JSONObject b = new JSONObject();
                b.put("query", q);
                b.put("count", 20);
                b.put("offset", 0);
                b.put("aid", 8662);
                b.put("req_type", "only_content");
                b.put("search_type", "short_play");
                probe(path, b.toString().getBytes(StandardCharsets.UTF_8), true);
            } catch (Exception e) {
                log("POST " + path + " FAIL " + e);
            }
            // GET 形态
            try {
                String p = path + "?query=" + java.net.URLEncoder.encode(q, "UTF-8")
                        + "&count=20&offset=0&search_type=short_play";
                probe(p, new byte[0], false);
            } catch (Exception e) {
                log("GET " + path + " FAIL " + e);
            }
        }
        OUT.close();
        System.out.println("DONE");
    }

    static void probe(String path, byte[] body, boolean post) {
        try {
            SignedRequest req = new SignedRequest(path, body, false);
            Request.Builder rb = new Request.Builder().url(req.url);
            for (java.util.Map.Entry<String, String> e : req.headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
            if (post) rb.post(RequestBody.create(JSON_TYPE, body));
            try (Response resp = CLIENT.newCall(rb.build()).execute()) {
                String text = resp.body() == null ? "" : resp.body().string();
                boolean interesting = resp.code() == 200 && text.length() > 40
                        && (text.contains("series") || text.contains("book") || text.contains("sug"));
                String head = text.length() > 350 ? text.substring(0, 350) : text;
                log((interesting ? "*** " : "") + (post ? "POST " : "GET  ") + path
                        + " -> HTTP " + resp.code() + " len=" + text.length()
                        + " body=" + head.replace("\n", " "));
            }
        } catch (Exception e) {
            log((post ? "POST " : "GET  ") + path + " FAIL " + e);
        }
    }

    static synchronized void log(String s) {
        try {
            OUT.write(s);
            OUT.newLine();
            OUT.flush();
        } catch (Exception ignored) {
        }
        System.out.println(s);
    }
}
