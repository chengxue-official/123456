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
 * Probe15: replicate the streaming official-search leg (12s budget, 8 pages,
 * stop on empty page / low budget) and print the arrival timeline of each batch
 * so we can see when the first results would hit the screen and whether the
 * longer budget actually yields more items.
 */
public class Probe15 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;

    static final int MAX_PAGES = 8;
    static final long PAGE_MIN_MS = 2500L;
    static final long SYNC_BUDGET_MS = 7000L;
    static final long STREAM_BUDGET_MS = 12000L;

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        String[] queries = {"你疯了别惹他", "婚书为饵", "斗破苍穹", "开局签到荒古圣体"};
        for (String q : queries) {
            log("===== QUERY=" + q);
            int a = run(q, SYNC_BUDGET_MS, "sync-7s");
            Thread.sleep(400);
            int b = run(q, STREAM_BUDGET_MS, "stream-12s");
            log("  >>> " + q + "  7s=" + a + " 条  12s=" + b + " 条  增益=" + (b - a));
        }
        OUT.close();
        System.out.println("DONE");
    }

    /** @return deduped item count */
    static int run(String keyword, long budgetMs, String tag) throws Exception {
        long start = System.currentTimeMillis();
        long deadline = start + budgetMs;
        Set<String> seen = new HashSet<>();
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            if (System.currentTimeMillis() + PAGE_MIN_MS > deadline) {
                log("  [" + tag + "] 预算不足停翻页，已取 " + seen.size()
                        + " 条 @+" + (System.currentTimeMillis() - start) + "ms");
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
                    }
                }
                if (next < 0 && tab.optBoolean("has_more", false)) {
                    next = tab.optInt("next_offset", -1);
                }
            }
            // this is where sink.onBatch() would fire -> screen gets the batch
            log("  [" + tag + "] 批次 page=" + page + " offset=" + offset + " 本批=" + added
                    + " 累计=" + seen.size() + " 上屏时刻=+" + (System.currentTimeMillis() - start)
                    + "ms 单页耗时=" + cost + "ms next=" + next);
            if (added == 0) break;
            if (next <= offset) break;
            offset = next;
        }
        log("  [" + tag + "] 本腿结束 共 " + seen.size() + " 条 耗时="
                + (System.currentTimeMillis() - start) + "ms");
        return seen.size();
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
        throw new Exception("appGet failed: " + pathAndQuery);
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
