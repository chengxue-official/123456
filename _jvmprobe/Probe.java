import com.houg.tv.sign.SignedRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JVM 验证：okhttp 栈下 video_model / 弹幕接口是否可用（无 Android 依赖，手工 JSON） */
public class Probe {
    static final String VID = "7693557924731309081";
    static final String SERIES = "7693501483337141273";

    public static void main(String[] args) throws Exception {
        OkHttpClient client = new OkHttpClient();

        // 1. video_model
        String vmBody = "{\"video_id\":\"" + VID + "\",\"content_type\":1,"
                + "\"biz_param\":{\"need_all_video_definition\":true,\"video_platform\":3}}";
        String vm = post(client, "/novel/player/video_model/v1/", vmBody);
        System.out.println("video_model head: " + vm.substring(0, Math.min(200, vm.length())));
        Matcher md = Pattern.compile("\"video_duration\":(\\d+)").matcher(vm);
        long dur = md.find() ? Long.parseLong(md.group(1)) : 120;
        System.out.println("video_duration(s)=" + dur);

        // 2. 弹幕（普通签名——验证是否 110001）
        String dmBody = "{\"comment_source\":601,\"server_channel\":1000,\"group_id\":\"" + VID + "\","
                + "\"group_type\":30,\"comment_type\":20,\"sort\":1,\"count\":90,\"cursor\":\"\","
                + "\"aid\":8662,\"compliance_status\":0,"
                + "\"business_param\":{\"book_id\":\"" + SERIES + "\",\"start_offset_time\":0,"
                + "\"playlet_item_duration\":" + (dur * 1000) + ","
                + "\"need_danmaku_guide_type\":[1,3,4,2]}}";
        String dm = post(client, "/novel/commentapi/comment/list/" + VID + "/v1/", dmBody);
        System.out.println("danmaku(普通签名) head: " + dm.substring(0, Math.min(300, dm.length())));
    }

    static String post(OkHttpClient client, String path, String jsonBody) throws Exception {
        byte[] body = jsonBody.getBytes("UTF-8");
        SignedRequest req = new SignedRequest(path, body);
        Request.Builder rb = new Request.Builder().url(req.url);
        for (Map.Entry<String, String> e : req.headers.entrySet()) rb.header(e.getKey(), e.getValue());
        rb.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body));
        try (Response resp = client.newCall(rb.build()).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            System.out.println("POST " + path + " -> " + resp.code());
            return text;
        }
    }
}
