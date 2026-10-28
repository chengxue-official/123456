import android.content.Context;
import com.houg.tv.sign.DeviceRegister;
import com.houg.tv.sign.SignedRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probe16: 相关推荐数据源探测。
 * 1) 搜索拿几个真实 series_id；
 * 2) 抓网页详情页 _ROUTER_DATA，dump detail_page 的全部顶层 key + 递归找
 *    recommend/related/like/guess/similar/same 等候选字段；
 * 3) 同步 dump App 版详情接口的 data.* key 清单。
 * 目的：确认「相关推荐」有没有现成接口，没有就走同标签/同分类兜底。
 */
public class Probe16 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;
    static final String WEB = "https://hongguoduanju.com";
    static final Pattern INTEREST = Pattern.compile(
            "(?i)recommend|related|like|guess|similar|same|also|watch_next|ref");

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        Set<String> ids = new LinkedHashSet<>();
        String[] queries = {"总裁", "战神", "千金"};
        for (String q : queries) {
            fillIds(q, ids);
            if (ids.size() >= 3) break;
        }
        log("候选 series_id: " + ids);
        for (String id : ids) probe(id);
        OUT.close();
        System.out.println("DONE");
    }

    static void fillIds(String keyword, Set<String> ids) {
        try {
            JSONObject root = appGet("/reading/bookapi/search/tab/v/?query=" + enc(keyword)
                    + "&count=20&offset=0");
            JSONArray tabs = root.optJSONArray("search_tabs");
            if (tabs == null) return;
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject tab = tabs.optJSONObject(i);
                if (tab == null) continue;
                JSONArray cells = tab.optJSONArray("data");
                if (cells == null) continue;
                for (int j = 0; j < cells.length(); j++) {
                    JSONObject cell = cells.optJSONObject(j);
                    if (cell == null) continue;
                    JSONArray vdArr = cell.optJSONArray("video_data");
                    if (vdArr == null || vdArr.length() == 0) continue;
                    JSONObject vd0 = vdArr.optJSONObject(0);
                    if (vd0 == null) continue;
                    JSONObject detail = vd0.optJSONObject("video_detail");
                    JSONObject src = (detail != null && detail.length() > 0) ? detail : vd0;
                    String id = firstNonEmpty(str(src, "series_id_str"), str(src, "series_id"));
                    if (id.isEmpty() || !isNumericId(id)) continue;
                    ids.add(id);
                }
            }
        } catch (Exception e) {
            log("fillIds " + keyword + " 失败: " + e);
        }
    }

    static void probe(String id) {
        log("========== series_id=" + id + " ==========");
        try {
            String html = webGet(WEB + "/detail?series_id=" + enc(id));
            log("[web] html " + html.length() + " 字符");
            String json = extractRouterData(html);
            if (json == null) {
                log("[web] 未找到 _ROUTER_DATA");
                return;
            }
            Object rootV = new JSONTokener(json).nextValue();
            if (!(rootV instanceof JSONObject)) {
                log("[web] _ROUTER_DATA 非 JSONObject");
                return;
            }
            JSONObject root = (JSONObject) rootV;
            JSONObject loader = root.optJSONObject("loaderData");
            if (loader == null) {
                log("[web] loaderData 不存在");
                return;
            }
            JSONArray loaderKeys = loader.names();
            log("[web] loaderData keys: " + keysOf(loader));
            if (loaderKeys != null) {
                for (int i = 0; i < loaderKeys.length(); i++) {
                    String key = loaderKeys.optString(i);
                    JSONObject page = loader.optJSONObject(key);
                    if (page == null) continue;
                    log("[web] page=" + key + " 顶层 keys: " + keysOf(page));
                    walk(page, "loaderData." + key, 0);
                    // 把整页 JSON 落盘，方便人工翻
                    try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                            new FileOutputStream("probe16_" + id + ".json", false),
                            StandardCharsets.UTF_8))) {
                        w.write(page.toString());
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log("[web] 探测失败: " + e);
        }
        try {
            JSONObject body = new JSONObject();
            body.put("series_id", id);
            JSONObject resp = appPost("/novel/player/video_detail/v1/", body.toString());
            JSONObject data = resp.optJSONObject("data");
            if (data == null) data = new JSONObject();
            JSONObject vd = data.optJSONObject("video_data");
            log("[app] data keys: " + keysOf(data));
            if (vd != null) {
                log("[app] video_data keys: " + keysOf(vd));
                walk(vd, "data.video_data", 0);
            }
        } catch (Exception e) {
            log("[app] 探测失败: " + e);
        }
    }

    /** 递归找名字含关键字的字段（深度限 5，只打路径 + 值类型/规模）。 */
    static void walk(Object node, String path, int depth) {
        if (depth > 5 || node == null) return;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONArray names = o.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String k = names.optString(i);
                Object v = o.opt(k);
                if (k == null || v == null) continue;
                Matcher m = INTEREST.matcher(k);
                if (m.find()) {
                    String shape;
                    if (v instanceof JSONArray) {
                        shape = "JSONArray[" + ((JSONArray) v).length() + "]";
                    } else if (v instanceof JSONObject) {
                        shape = "JSONObject{" + ((JSONObject) v).length() + "}";
                    } else {
                        String s = String.valueOf(v);
                        shape = "\"" + (s.length() > 60 ? s.substring(0, 60) + "..." : s) + "\"";
                    }
                    log("  [候选] " + path + "." + k + " = " + shape);
                }
                if (v instanceof JSONObject) walk(v, path + "." + k, depth + 1);
                else if (v instanceof JSONArray) walk(v, path + "." + k, depth + 1);
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length() && i < 3; i++) {
                walk(arr.opt(i), path + "[" + i + "]", depth + 1);
            }
        }
    }

    static String keysOf(JSONObject o) {
        JSONArray names = o.names();
        if (names == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < names.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.optString(i));
        }
        return sb.append("]").toString();
    }

    static String extractRouterData(String html) {
        Matcher m = Pattern.compile("(?s)(?:window\\.)?_ROUTER_DATA\\s*=\\s*").matcher(html);
        if (!m.find()) return null;
        int brace = html.indexOf('{', m.end());
        if (brace < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = brace; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return html.substring(brace, i + 1);
                }
            }
        }
        return null;
    }

    static String webGet(String url) throws Exception {
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept-Language", "zh-CN,zh;q=0.9");
        try (Response resp = CLIENT.newCall(rb.build()).execute()) {
            return resp.body() == null ? "" : resp.body().string();
        }
    }

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A");
        } catch (Exception e) {
            return s;
        }
    }

    static String str(JSONObject o, String k) {
        Object v = o == null ? null : o.opt(k);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    static String firstNonEmpty(String... xs) {
        for (String x : xs) if (x != null && !x.isEmpty()) return x;
        return "";
    }

    static boolean isNumericId(String id) {
        if (id == null || id.isEmpty() || id.length() > 24) return false;
        for (int i = 0; i < id.length(); i++) if (!Character.isDigit(id.charAt(i))) return false;
        return true;
    }

    static JSONObject appGet(String pathAndQuery) throws Exception {
        return appReq(pathAndQuery, null);
    }

    static JSONObject appPost(String path, String body) throws Exception {
        return appReq(path, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    static JSONObject appReq(String path, byte[] body) throws Exception {
        for (int r = 0; r <= 2; r++) {
            SignedRequest req = new SignedRequest(path, body == null ? new byte[0] : body,
                    body != null);
            Request.Builder rb = new Request.Builder().url(req.url);
            for (java.util.Map.Entry<String, String> e : req.headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
            if (body != null) rb.post(RequestBody.create(
                    okhttp3.MediaType.parse("application/json; charset=utf-8"), body));
            try (Response resp = CLIENT.newCall(rb.build()).execute()) {
                String text = resp.body() == null ? "" : resp.body().string();
                if (text.isEmpty()) continue;
                JSONObject json = new JSONObject(text);
                if (json.optInt("code", -1) != 0) continue;
                return json;
            }
        }
        throw new Exception("appReq failed: " + path);
    }

    static synchronized void log(String s) {
        try {
            OUT.write(s);
            OUT.newLine();
            OUT.flush();
        } catch (Exception ignored) {
        }
    }
}
