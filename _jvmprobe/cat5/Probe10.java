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

public class Probe10 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;
    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);
        String q = java.net.URLEncoder.encode("你疯了别惹他", "UTF-8");
        SignedRequest req = new SignedRequest("/reading/bookapi/search/tab/v/?query=" + q
                + "&count=50&offset=0&aid=8662&search_type=short_play", new byte[0], false);
        Request.Builder rb = new Request.Builder().url(req.url);
        for (java.util.Map.Entry<String, String> e : req.headers.entrySet()) rb.header(e.getKey(), e.getValue());
        String text;
        try (Response resp = CLIENT.newCall(rb.build()).execute()) {
            text = resp.body() == null ? "" : resp.body().string();
        }
        JSONObject root = new JSONObject(text);
        log("top: " + keys(root));
        JSONObject data = root;
        log("root keys rechecked");
        JSONArray tabs = data == null ? null : data.optJSONArray("search_tabs");
        if (tabs != null) {
            log("tabs.len=" + tabs.length());
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject t = tabs.optJSONObject(i);
                if (t == null) continue;
                log("tab[" + i + "] keys=" + keys(t));
                JSONArray items = t.optJSONArray("data");
                log("  tab[" + i + "] type=" + t.opt("type") + " title=" + t.opt("tab_title") + " data.len=" + (items == null ? -1 : items.length()));
                if (items != null) {
                    for (int j = 0; j < items.length() && j < 3; j++) {
                        JSONObject it = items.optJSONObject(j);
                        if (it == null) continue;
                        log("  item[" + j + "] keys=" + keys(it));
                        Iterator<String> it2 = it.keys();
                        while (it2.hasNext()) {
                            String k = it2.next();
                            Object v = it.opt(k);
                            if (v instanceof JSONObject) log("    " + k + " keys=" + keys((JSONObject) v));
                        }
                    }
                }
                for (String k : new String[]{"tab_title", "title", "type", "cell_type"}) {
                    if (t.has(k)) log("  " + k + "=" + t.opt(k));
                }
            }
        }
        OUT.close();
        System.out.println("DONE");
    }
    static String keys(JSONObject o) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = o.keys();
        while (it.hasNext()) sb.append(it.next()).append(",");
        return sb.toString();
    }
    static synchronized void log(String s) {
        try { OUT.write(s); OUT.newLine(); OUT.flush(); } catch (Exception ignored) {}
        System.out.println(s);
    }
}