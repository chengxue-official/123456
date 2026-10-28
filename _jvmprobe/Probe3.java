import com.houg.tv.sign.SignedRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.Map;

/** 端到端验证：用 app 真实 SignedRequest(comment=true) + okhttp 调弹幕接口 */
public class Probe3 {
    public static void main(String[] args) throws Exception {
        OkHttpClient client = new OkHttpClient();
        String vid = "7693557924731309081";
        String dmBody = "{\"comment_source\":601,\"server_channel\":1000,\"group_id\":\"" + vid + "\","
                + "\"group_type\":30,\"comment_type\":20,\"sort\":1,\"count\":90,\"cursor\":\"\","
                + "\"aid\":8662,\"compliance_status\":0,"
                + "\"business_param\":{\"book_id\":\"7693501483337141273\",\"start_offset_time\":0,"
                + "\"playlet_item_duration\":120000,\"need_danmaku_guide_type\":[1,3,4,2]}}";

        byte[] body = dmBody.getBytes("UTF-8");
        SignedRequest req = new SignedRequest("/novel/commentapi/comment/list/" + vid + "/v1/", body, true);
        Request.Builder rb = new Request.Builder().url(req.url);
        for (Map.Entry<String, String> e : req.headers.entrySet()) rb.header(e.getKey(), e.getValue());
        rb.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body));
        try (Response resp = client.newCall(rb.build()).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            System.out.println("code=" + resp.code());
            System.out.println("body head: " + text.substring(0, Math.min(500, text.length())));
        }
    }
}
