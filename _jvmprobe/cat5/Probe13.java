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

/**
 * Probe13：确认官方搜索返回单元格里有没有 category/类型字段能区分漫剧/AI 剧。
 */
public class Probe13 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        for (String q : new String[]{"斗破苍穹", "漫剧", "一人之下"}) {
            log("===== QUERY=" + q);
            try {
                JSONObject root = appGet("/reading/bookapi/search/tab/v/?query=" + enc(q)
                        + "&count=50&offset=0&search_type=short_play");
                JSONArray tabs = root.optJSONArray("search_tabs");
                if (tabs == null) { log("no search_tabs"); continue; }
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
                        String id = str(src, "series_id_str").isEmpty() ? str(src, "series_id") : str(src, "series_id_str");
                        log("  cell_name=" + str(cell, "cell_name")
                                + " title=" + firstNonEmpty(str(src, "series_title"), str(src, "title"))
                                + " id=" + id);
                        // 打出 video_detail 全部 key，找类型相关字段
                        if (detail != null) {
                            StringBuilder keys = new StringBuilder();
                            java.util.Iterator<String> it = detail.keys();
                            while (it.hasNext()) keys.append(it.next()).append(" ");
                            log("    detail_keys=" + keys.toString().trim());
                            Object cat = detail.opt("category_schema");
                            if (cat != null) log("    category_schema=" + cat.toString());
                            Object tags = detail.opt("tag_list");
                            if (tags != null) log("    tag_list=" + tags.toString());
                        }
                    }
                }
            } catch (Exception e) {
                log("  失败: " + e.getMessage());
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
