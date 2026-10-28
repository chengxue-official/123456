import android.content.Context;
import com.houg.tv.sign.DeviceRegister;
import com.houg.tv.sign.SignedRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 决定性探针：向红果官方签名接口直查——
 *  1) 设备注册（拿 device_id/iid/Cookie）
 *  2) landpage 分类接口（/reading/distribution/category/landpage/v/）三个分类翻页，
 *     扫描 万倍/别惹他/亿倍/禁忌/他老婆 是否在官方池、有无 series_id
 *  3) 探测候选官方搜索接口是否存在可用响应
 * 结果写入 result.txt（UTF-8）。
 */
public class Probe5 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build();
    static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    static final Pattern HIT = Pattern.compile("万倍|别惹他|亿倍|禁忌|他老婆");
    static BufferedWriter OUT;
    static final Set<String> SEEN = new HashSet<>();

    public static void main(String[] args) throws Exception {
        String outPath = args.length > 0 ? args[0] : "result.txt";
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(outPath, false), StandardCharsets.UTF_8));

        DeviceRegister.attach(new Context());
        try {
            DeviceRegister.Result r = DeviceRegister.ensureRegistered(CLIENT);
            log("REG_OK device_id=" + r.deviceId + " iid=" + r.installId
                    + " cookie=" + r.cookieHeader);
        } catch (Exception e) {
            log("REG_FAIL " + e);
        }

        scanGenre("short_play", "default", 120);
        scanGenre("comic_series", "comic_series", 60);
        scanGenre("ai_series", "ai_series", 60);

        probeSearchPaths();
        OUT.close();
        System.out.println("DONE -> " + new File(outPath).getAbsolutePath());
    }

    static void scanGenre(String genre, String scene, int maxPages) throws Exception {
        log("===== GENRE " + genre + " / " + scene + " =====");
        int offset = 0;
        String session = "";
        int pages = 0;
        int total = 0;
        int emptyRetries = 0;
        while (pages < maxPages) {
            JSONObject selectItems = new JSONObject();
            selectItems.put("genre", new JSONArray().put(genre));
            selectItems.put("sort", new JSONArray().put("online_time"));
            selectItems.put("gender", new JSONArray());
            selectItems.put("category_dim_theme", new JSONArray());
            selectItems.put("category_dim_role", new JSONArray());
            selectItems.put("category_dim_epoch", new JSONArray());
            selectItems.put("online_time", new JSONArray());
            selectItems.put("creation_status", new JSONArray());

            JSONObject body = new JSONObject();
            body.put("req_scene", scene);
            body.put("offset", offset);
            body.put("limit", 18);
            body.put("req_type", "only_content");
            body.put("need_selector_panel", false);
            body.put("client_req_type", pages == 0 ? 3 : 2);
            body.put("session_id", session);
            body.put("filter_ids", "");
            body.put("select_items", selectItems);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            SignedRequest req = new SignedRequest(
                    "/reading/distribution/category/landpage/v/", payload, false);
            Request.Builder rb = new Request.Builder().url(req.url);
            for (java.util.Map.Entry<String, String> e : req.headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
            rb.post(RequestBody.create(JSON_TYPE, payload));

            String text;
            try (Response resp = CLIENT.newCall(rb.build()).execute()) {
                text = resp.body() == null ? "" : resp.body().string();
                if (resp.code() != 200) {
                    log("PAGE " + (pages + 1) + " HTTP " + resp.code() + " STOP");
                    break;
                }
            }
            if (text.isEmpty()) {
                emptyRetries++;
                if (emptyRetries > 2) {
                    log("PAGE " + (pages + 1) + " EMPTY x3 STOP");
                    break;
                }
                Thread.sleep(400);
                continue;
            }
            emptyRetries = 0;
            JSONObject root = new JSONObject(text);
            int biz = root.optInt("code", 0);
            if (biz != 0) {
                log("PAGE " + (pages + 1) + " bizCode=" + biz + " msg=" + root.optString("message") + " STOP");
                break;
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                log("PAGE " + (pages + 1) + " NO data STOP");
                break;
            }
            JSONArray rows = data.optJSONArray("video_data");
            int n = rows == null ? 0 : rows.length();
            pages++;
            total += n;
            for (int i = 0; i < n; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                JSONObject vd = row.optJSONObject("video_data");
                if (vd == null || vd.length() == 0) vd = row;
                String id = firstText(vd, "series_id_str", "series_id");
                String name = firstText(vd, "series_name", "series_title", "name", "title");
                String cnt = firstText(vd, "episode_cnt");
                if (id.isEmpty() || SEEN.contains(id)) continue;
                SEEN.add(id);
                Matcher m = HIT.matcher(name);
                if (name.length() > 0 && m.find()) {
                    log("HIT p" + pages + " off" + (offset + i) + "  " + id + "  " + name
                            + "  cnt=" + cnt);
                }
            }
            boolean hasMore = data.optBoolean("has_more", false);
            int nextOffset = data.optInt("next_offset", offset + n);
            String nextSession = data.optString("session_id", session);
            if (pages == 1) {
                log("PAGE1 n=" + n + " has_more=" + hasMore + " next_offset=" + nextOffset
                        + " session=" + (nextSession.isEmpty() ? "<empty>" : nextSession));
                // 打样 3 条确认数据真实
                int shown = 0;
                for (int i = 0; i < n && shown < 3; i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    JSONObject vd = row.optJSONObject("video_data");
                    if (vd == null || vd.length() == 0) vd = row;
                    String nm = firstText(vd, "series_name", "series_title", "name", "title");
                    String id = firstText(vd, "series_id_str", "series_id");
                    if (!nm.isEmpty()) { log("SAMPLE " + id + "  " + nm); shown++; }
                }
            }
            if (!hasMore) {
                log("DONE pages=" + pages + " total=" + total + " (has_more=false)");
                break;
            }
            offset = nextOffset;
            session = nextSession;
            Thread.sleep(250);
        }
        log("SCANNED " + genre + " pages=" + pages + " unique=" + SEEN.size());
    }

    static void probeSearchPaths() {
        log("===== SEARCH PATH PROBES =====");
        String[][] paths = {
                {"/reading/distribution/search/sug/v/", "POST"},
                {"/reading/distribution/search/v/", "POST"},
                {"/reading/distribution/search/book/v/", "POST"},
                {"/novel/search/sug/v/", "POST"},
                {"/novel/search/item/sug/v/", "POST"},
                {"/reading/search/page/", "GET"},
                {"/reading/search/sug/", "GET"},
                {"/reading/book/search/", "GET"},
                {"/novel/search/page/", "GET"},
        };
        String q = "你疯了别惹他";
        for (String[] p : paths) {
            try {
                String path = p[0];
                String method = p[1];
                String bodyStr = "{\"query\":\"" + q + "\",\"count\":20,\"offset\":0,"
                        + "\"aid\":8662,\"req_type\":\"only_content\"}";
                byte[] payload = method.equals("POST")
                        ? bodyStr.getBytes(StandardCharsets.UTF_8) : new byte[0];
                if (method.equals("GET")) {
                    path = path + "?query=" + java.net.URLEncoder.encode(q, "UTF-8") + "&count=20&offset=0";
                }
                SignedRequest req = new SignedRequest(path, payload, false);
                Request.Builder rb = new Request.Builder().url(req.url);
                for (java.util.Map.Entry<String, String> e : req.headers.entrySet()) {
                    rb.header(e.getKey(), e.getValue());
                }
                if (method.equals("POST")) {
                    rb.post(RequestBody.create(JSON_TYPE, payload));
                }
                try (Response resp = CLIENT.newCall(rb.build()).execute()) {
                    String text = resp.body() == null ? "" : resp.body().string();
                    String head = text.length() > 400 ? text.substring(0, 400) : text;
                    log(method + " " + p[0] + " -> HTTP " + resp.code()
                            + " len=" + text.length() + " body=" + head.replace("\n", " "));
                }
            } catch (Exception e) {
                log(p[1] + " " + p[0] + " FAIL " + e);
            }
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
