import android.content.Context;
import com.houg.tv.sign.DeviceRegister;
import com.houg.tv.sign.SignedRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probe7：验证官方搜索接口 /reading/bookapi/search/page/v/ 的全库覆盖能力。
 *  1) 搜「你疯了别惹他」等关键词，列出全部结果（book_id + 高亮 title）
 *  2) 检查万倍第三季/第四季是否出现
 *  3) 对命中的 id 打官方详情接口（/novel/player/video_detail/v1/）验证可播性 + 集数
 */
public class Probe7 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build();
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "search_verify.txt";
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outPath, false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        String[] queries = {"你疯了别惹他", "别惹他，他老婆比禁忌还凶万倍", "秦爷夫人又孕吐了"};
        for (String q : queries) {
            search(q);
        }
        OUT.close();
        System.out.println("DONE");
    }

    static void search(String q) throws Exception {
        log("===== QUERY: " + q + " =====");
        String path = "/reading/bookapi/search/page/v/?query="
                + java.net.URLEncoder.encode(q, "UTF-8")
                + "&count=50&offset=0&aid=8662&search_type=short_play";
        String text = signedGet(path);
        if (text.isEmpty()) {
            log("EMPTY RESPONSE");
            return;
        }
        JSONObject root = new JSONObject(text);
        log("code=" + root.optInt("code", -1) + " message=" + root.optString("message")
                + " len=" + text.length());
        JSONArray data = root.optJSONArray("data");
        if (data == null) {
            log("NO data[]");
            return;
        }
        int shown = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject cell = data.optJSONObject(i);
            if (cell == null) continue;
            String id = firstText(cell, "book_id", "search_result_id");
            JSONObject hl = cell.optJSONObject("search_high_light");
            String title = "";
            if (hl != null) {
                JSONObject t = hl.optJSONObject("title");
                if (t != null) title = t.optString("text", "");
            }
            if (title.isEmpty()) title = cell.optString("cell_name", "");
            if (title.isEmpty()) continue;
            shown++;
            log(String.format("[%02d] %s  %s", i, id, title));
            // 万倍系命中 → 立即打详情验证
            if (title.contains("万倍") || title.contains("别惹他")) {
                verifyPlayable(id, title);
            }
        }
        log("TOTAL shown=" + shown + " / data.len=" + data.length());
    }

    static void verifyPlayable(String seriesId, String title) {
        try {
            JSONObject body = new JSONObject();
            body.put("series_id", seriesId);
            body.put("aid", 8662);
            String payload = body.toString();
            SignedRequest req = new SignedRequest("/novel/player/video_detail/v1/",
                    payload.getBytes(StandardCharsets.UTF_8), false);
            Request.Builder rb = new Request.Builder().url(req.url);
            for (java.util.Map.Entry<String, String> e : req.headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
            rb.post(okhttp3.RequestBody.create(
                    okhttp3.MediaType.parse("application/json; charset=utf-8"),
                    payload.getBytes(StandardCharsets.UTF_8)));
            try (Response resp = CLIENT.newCall(rb.build()).execute()) {
                String text = resp.body() == null ? "" : resp.body().string();
                if (text.isEmpty()) {
                    log("    DETAIL " + seriesId + " -> HTTP " + resp.code() + " EMPTY BODY");
                    return;
                }
                JSONObject root = new JSONObject(text);
                JSONObject data = root.optJSONObject("data");
                JSONObject vd = data == null ? null : data.optJSONObject("video_data");
                JSONArray vids = vd == null ? null : vd.optJSONArray("video_list");
                String name = vd == null ? "" : firstText(vd, "series_title", "series_name");
                int cnt = vids == null ? 0 : vids.length();
                log("    DETAIL " + seriesId + " -> HTTP " + resp.code()
                        + " code=" + root.optInt("code", -1)
                        + " name=" + name + " episodes=" + cnt);
            }
        } catch (Exception e) {
            log("    DETAIL " + seriesId + " FAIL " + e);
        }
    }

    static String signedGet(String pathAndQuery) throws Exception {
        SignedRequest req = new SignedRequest(pathAndQuery, new byte[0], false);
        Request.Builder rb = new Request.Builder().url(req.url);
        for (java.util.Map.Entry<String, String> e : req.headers.entrySet()) {
            rb.header(e.getKey(), e.getValue());
        }
        try (Response resp = CLIENT.newCall(rb.build()).execute()) {
            return resp.body() == null ? "" : resp.body().string();
        }
    }

    static String firstText(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
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
