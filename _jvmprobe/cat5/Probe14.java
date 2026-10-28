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
 * Probe14：复刻改后 fetchOfficialSearchDramas 逻辑（无 search_type + 空页即停 + 预算自检
 * + live 增量落盘），用与 App 相同的 7000ms 预算、MAX_PAGES=8、PAGE_MIN_MS=2500
 * 实测条数与耗时；若某次耗时超过 7000ms，说明会发生「超时但有已取结果」的情况。
 */
public class Probe14 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;

    static final int MAX_PAGES = 8;
    static final long PAGE_MIN_MS = 2500L;
    static final long BUDGET_MS = 7000L;

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        for (String q : new String[]{"你疯了别惹他", "婚书为饵", "秦爷夫人又孕吐了", "斗破苍穹"}) {
            long deadline = System.currentTimeMillis() + BUDGET_MS;
            long t0 = System.currentTimeMillis();
            int n;
            try {
                n = fetch(q, deadline);
            } catch (Exception e) {
                log("===== " + q + " 失败: " + e.getMessage());
                continue;
            }
            long cost = System.currentTimeMillis() - t0;
            log("===== QUERY=" + q + " 结果=" + n + " 条  本腿耗时=" + cost + "ms"
                    + (cost > BUDGET_MS ? "  [超预算→靠 live 保住 " + n + " 条]" : "  [预算内]"));
        }
        OUT.close();
        System.out.println("DONE");
    }

    /** @return 去重后的结果条数 */
    static int fetch(String keyword, long deadlineMs) throws Exception {
        Set<String> seen = new HashSet<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            if (System.currentTimeMillis() + PAGE_MIN_MS > deadlineMs) {
                log("  预算不足，停止翻页（已取 " + seen.size() + " 条）");
                break;
            }
            long t = System.currentTimeMillis();
            JSONObject root = appGet("/reading/bookapi/search/tab/v/?query=" + enc(keyword)
                    + "&count=50&offset=" + offset);
            long cost = System.currentTimeMillis() - t;
            JSONArray tabs = root.optJSONArray("search_tabs");
            if (tabs == null) break;

            int added = 0;
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
                        if (title.isEmpty() || title.equals(id)) continue;
                        added++;
                        if (seen.size() <= 60) {
                            log("  [" + added + "] " + id + "  " + title + "  cnt=" + str(src, "episode_cnt")
                                    + "  3D=" + (String.valueOf(src.opt("category_schema")).contains("3D") ? "Y" : "N"));
                        }
                    }
                }
                if (next < 0 && tab.optBoolean("has_more", false)) {
                    next = tab.optInt("next_offset", -1);
                }
            }
            log("  page=" + page + " offset=" + offset + " 新增=" + added
                    + " 累计=" + seen.size() + " 耗时=" + cost + "ms next=" + next);
            if (added == 0) break;
            if (next <= offset) break;
            offset = next;
        }
        return seen.size();
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
    }
}
