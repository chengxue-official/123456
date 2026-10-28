import com.houg.tv.sign.SignedRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 诊断：抓取完整弹幕响应体，确认 next_query_danmaku_list_time / offset_time 单位、status、danmaku_count。 */
public class Probe4 {
    public static void main(String[] args) throws Exception {
        OkHttpClient client = new OkHttpClient();
        String vid = args.length > 0 ? args[0] : "7693557924731309081";
        String series = args.length > 1 ? args[1] : "7693501483337141273";
        long start = args.length > 2 ? Long.parseLong(args[2]) : 0L;

        String dmBody = "{\"comment_source\":601,\"server_channel\":1000,\"group_id\":\"" + vid + "\","
                + "\"group_type\":30,\"comment_type\":20,\"sort\":1,\"count\":90,\"cursor\":\"\","
                + "\"aid\":8662,\"compliance_status\":0,"
                + "\"business_param\":{\"book_id\":\"" + series + "\",\"start_offset_time\":" + start + ","
                + "\"playlet_item_duration\":120000,\"need_danmaku_guide_type\":[1,3,4,2]}}";

        byte[] body = dmBody.getBytes("UTF-8");
        SignedRequest req = new SignedRequest("/novel/commentapi/comment/list/" + vid + "/v1/", body, true);
        Request.Builder rb = new Request.Builder().url(req.url);
        for (Map.Entry<String, String> e : req.headers.entrySet()) rb.header(e.getKey(), e.getValue());
        rb.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body));
        try (Response resp = client.newCall(rb.build()).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            System.out.println("code=" + resp.code());
            System.out.println("=====FULL BODY=====");
            System.out.println(text);
            System.out.println("=====KEY FIELDS=====");
            System.out.println("danmaku_count        = " + extract(text, "\"danmaku_count\"\\s*:\\s*([0-9]+)"));
            System.out.println("total_danmaku_count  = " + extract(text, "\"total_danmaku_count\"\\s*:\\s*([0-9]+)"));
            System.out.println("next_query_danmaku   = " + extract(text, "\"next_query_danmaku_list_time\"\\s*:\\s*([0-9]+)"));
            System.out.println("offset_time(count)   = " + count(text, "\"offset_time\"\\s*:\\s*([0-9]+)"));
            System.out.println("status values        = " + listAll(text, "\"status\"\\s*:\\s*([0-9]+)"));
            System.out.println("offset_time values   = " + listAll(text, "\"offset_time\"\\s*:\\s*([0-9]+)"));
            System.out.println("offset_time unit hint: if all < 2000 -> seconds; if values near window boundaries -> ms");
        }
    }

    static String extract(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : "<absent>";
    }

    static int count(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    static String listAll(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        StringBuilder sb = new StringBuilder();
        int c = 0;
        while (m.find() && c < 40) { if (c > 0) sb.append(","); sb.append(m.group(1)); c++; }
        return sb.toString();
    }
}
