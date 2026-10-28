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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Probe11：按 NetClient.fetchOfficialSearchDramas 新逻辑做端到端模拟
 * （签名 GET -> search_tabs -> video_data[0] -> video_detail -> Drama 字段）。
 */
public class Probe11 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        for (String q : new String[]{"你疯了别惹他", "秦爷夫人又孕吐了", "婚书为饵"}) {
            log("===== QUERY: " + q + " =====");
            Set<String> seen = new HashSet<>();
            int offset = 0;
            for (int page = 0; page < 2; page++) {
                JSONObject root = appGet("/reading/bookapi/search/tab/v/?query="
                        + enc(q) + "&count=50&offset=" + offset + "&search_type=short_play");
                JSONArray tabs = root.optJSONArray("search_tabs");
                if (tabs == null) { log("no search_tabs"); break; }
                int next = -1;
                for (int i = 0; i < tabs.length(); i++) {
                    JSONObject tab = tabs.optJSONObject(i);
                    if (tab == null) continue;
                    JSONArray cells = tab.optJSONArray("data");
                    if (cells != null) {
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
                            if (id.isEmpty() || !isNumericId(id) || !seen.add(id)) continue;
                            String title = firstNonEmpty(str(src, "series_title"), str(src, "title"));
                            String count = firstNonEmpty(str(src, "episode_cnt"));
                            String remark = firstNonEmpty(str(src, "episode_right_text"));
                            log("  [" + seen.size() + "] " + id + "  " + title
                                    + "  cnt=" + count + "  remark=" + remark
                                    + "  cover=" + (str(src, "series_cover").isEmpty() ? "无" : "有"));
                        }
                    }
                    if (next < 0 && tab.optBoolean("has_more", false)) {
                        next = tab.optInt("next_offset", -1);
                    }
                }
                if (next <= offset) break;
                offset = next;
            }
        }
        OUT.close();
        System.out.println("DONE");
    }

    static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A");
        } catch (Exception e) { return s; }
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
        for (int r = 0; r <= 2; r++) {
            SignedRequest req = new SignedRequest(pathAndQuery, new byte[0], false);
            Request.Builder rb = new Request.Builder().url(req.url);
            for (java.util.Map.Entry<String, String> e : req.headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
            try (Response resp = CLIENT.newCall(rb.build()).execute()) {
                String text = resp.body() == null ? "" : resp.body().string();
                if (text.isEmpty()) continue;
                JSONObject json = new JSONObject(text);
                if (json.optInt("code", -1) != 0) continue;
                return json;
            }
        }
        throw new Exception("appGet 失败: " + pathAndQuery);
    }

    static synchronized void log(String s) {
        try { OUT.write(s); OUT.newLine(); OUT.flush(); } catch (Exception ignored) {}
        System.out.println(s);
    }
}
