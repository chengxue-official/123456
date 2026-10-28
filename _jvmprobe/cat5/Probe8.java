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
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * Probe8：定位「搜剧名 → 该剧全部季」的官方接口。
 *  A) dump /reading/bookapi/search/page/v/ 的完整结构（顶层键、cell 键、嵌套 video_data 键）
 *  B) 该接口分页 offset 是否有效（offset=0/6/12）
 *  C) 建议/补全接口族路径探测（prefix 匹配最容易直接命中完整剧名）
 *  D) search_type 参数矩阵
 */
public class Probe8 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build();
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "probe8.txt";
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outPath, false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        String target = "他老婆比禁忌还凶万倍";

        log("################ A. STRUCTURE DUMP ################");
        String base = "/reading/bookapi/search/page/v/?query="
                + java.net.URLEncoder.encode(target, "UTF-8")
                + "&count=50&offset=0&aid=8662&search_type=short_play";
        String text = signedGet(base);
        log("len=" + text.length());
        if (!text.isEmpty()) {
            JSONObject root = new JSONObject(text);
            log("top keys: " + keys(root));
            log("code=" + root.optInt("code", -1) + " msg=" + root.optString("message"));
            JSONArray data = root.optJSONArray("data");
            if (data != null) {
                log("data.len=" + data.length());
                for (int i = 0; i < data.length() && i < 2; i++) {
                    JSONObject c = data.optJSONObject(i);
                    if (c == null) continue;
                    log("data[" + i + "] keys: " + keys(c));
                    Iterator<String> it = c.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        Object v = c.opt(k);
                        if (v instanceof JSONObject) {
                            log("   " + k + " (obj) keys: " + keys((JSONObject) v));
                        } else if (v instanceof JSONArray) {
                            JSONArray arr = (JSONArray) v;
                            log("   " + k + " (arr) len=" + arr.length());
                            if (arr.length() > 0 && arr.opt(0) instanceof JSONObject) {
                                log("      [0] keys: " + keys(arr.optJSONObject(0)));
                            }
                        } else {
                            String s = String.valueOf(v);
                            if (s.length() > 120) s = s.substring(0, 120) + "...";
                            log("   " + k + " = " + s);
                        }
                    }
                }
            }
            // 顶层是否有 has_more / 分页字段
            for (String k : new String[]{"has_more", "offset", "next_offset", "total", "cursor", "search_id"}) {
                if (root.has(k)) log("top." + k + " = " + root.opt(k));
            }
        }

        log("");
        log("################ B. PAGINATION ################");
        for (int off : new int[]{0, 6, 12, 30}) {
            String p = "/reading/bookapi/search/page/v/?query="
                    + java.net.URLEncoder.encode("别惹他", "UTF-8")
                    + "&count=50&offset=" + off + "&aid=8662&search_type=short_play";
            String t = signedGet(p);
            String sig = "";
            if (!t.isEmpty()) {
                try {
                    JSONObject r = new JSONObject(t);
                    JSONArray d = r.optJSONArray("data");
                    StringBuilder sb = new StringBuilder();
                    if (d != null) {
                        for (int i = 0; i < d.length(); i++) {
                            JSONObject c = d.optJSONObject(i);
                            if (c == null) continue;
                            sb.append("|").append(highlightTitle(c));
                        }
                    }
                    sig = "code=" + r.optInt("code", -1) + " n=" + (d == null ? -1 : d.length()) + " " + sb;
                } catch (Exception e) {
                    sig = "PARSE_FAIL";
                }
            } else sig = "EMPTY";
            log("offset=" + off + " -> " + sig);
        }

        log("");
        log("################ C. SUGGEST FAMILY ################");
        String[] suggestPaths = {
                "/reading/bookapi/search/suggest/v/",
                "/reading/bookapi/suggest/v/",
                "/reading/bookapi/search/suggestion/v/",
                "/reading/bookapi/search/suggest_word/v/",
                "/reading/bookapi/search/hint/v/",
                "/reading/bookapi/search/complete/v/",
                "/reading/bookapi/search/page/v/",
                "/reading/bookapi/search/aggregation/v/",
                "/reading/bookapi/search/result/v/",
                "/reading/bookapi/search/playlet/v/",
                "/reading/bookapi/search/video/v/",
                "/reading/playlet/search/v/",
                "/reading/distribution/playlet/search/v/",
                "/reading/bookapi/search/tab/v/"
        };
        String[] queries = {"你疯了别惹他", "他老婆比禁忌还凶万倍", "你疯了别惹他，他老婆比禁忌还凶万倍第三季"};
        for (String sp : suggestPaths) {
            for (String q : queries) {
                String p = sp + "?query=" + java.net.URLEncoder.encode(q, "UTF-8")
                        + "&count=50&offset=0&aid=8662&search_type=short_play";
                probe(p, sp, q);
            }
        }

        log("");
        log("################ D. search_type MATRIX ################");
        String[] types = {"short_play", "playlet", "video", "all", "", "drama", "series", "shortplay", "short_play_video"};
        for (String ty : types) {
            String p = "/reading/bookapi/search/page/v/?query="
                    + java.net.URLEncoder.encode(target, "UTF-8")
                    + "&count=50&offset=0&aid=8662";
            if (!ty.isEmpty()) p += "&search_type=" + ty;
            String t = signedGet(p);
            String sig;
            if (t.isEmpty()) sig = "EMPTY";
            else {
                try {
                    JSONObject r = new JSONObject(t);
                    JSONArray d = r.optJSONArray("data");
                    StringBuilder sb = new StringBuilder();
                    if (d != null) for (int i = 0; i < d.length() && i < 8; i++) {
                        JSONObject c = d.optJSONObject(i);
                        if (c != null) sb.append("|").append(highlightTitle(c));
                    }
                    sig = "code=" + r.optInt("code", -1) + " n=" + (d == null ? -1 : d.length()) + " " + sb;
                } catch (Exception e) {
                    sig = "PARSE_FAIL";
                }
            }
            log("search_type='" + ty + "' -> " + sig);
        }

        OUT.close();
        System.out.println("DONE");
    }

    static void probe(String pathAndQuery, String label, String q) {
        try {
            String t = signedGet(pathAndQuery);
            if (t.isEmpty()) {
                log("[MISS] " + label + " q=" + q + " EMPTY");
                return;
            }
            JSONObject r = new JSONObject(t);
            int code = r.optInt("code", -1);
            String hit = "";
            JSONArray data = r.optJSONArray("data");
            if (data == null) {
                // suggest 常见结构：data 是对象 / 或根下 suggest_list
                JSONObject d = r.optJSONObject("data");
                if (d != null) {
                    JSONArray sl = d.optJSONArray("suggest_list");
                    if (sl != null) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < sl.length() && i < 8; i++) {
                            sb.append("|").append(String.valueOf(sl.opt(i)));
                        }
                        hit = "suggest_list n=" + sl.length() + " " + sb;
                    } else hit = "data(obj) keys=" + keys(d);
                }
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < data.length() && i < 8; i++) {
                    JSONObject c = data.optJSONObject(i);
                    if (c != null) sb.append("|").append(highlightTitle(c));
                }
                hit = "data(arr) n=" + data.length() + " " + sb;
            }
            log("[HIT] " + label + " q=" + q + " len=" + t.length() + " code=" + code + " " + hit);
        } catch (Exception e) {
            log("[ERR] " + label + " q=" + q + " " + e);
        }
    }

    static String highlightTitle(JSONObject cell) {
        JSONObject hl = cell.optJSONObject("search_high_light");
        if (hl != null) {
            JSONObject t = hl.optJSONObject("title");
            if (t != null) {
                String s = t.optString("text", "");
                if (!s.isEmpty()) return s;
            }
        }
        String s = cell.optString("cell_name", "");
        if (s.isEmpty()) s = cell.optString("title", "");
        return s;
    }

    static String keys(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = o.keys();
        while (it.hasNext()) sb.append(it.next()).append(",");
        return sb.toString();
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
