import com.houg.tv.sign.HongguoCommentSign;
import com.houg.tv.sign.SignedRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** JVM 验证评论（弹幕）专用签名 */
public class Probe2 {
    static final String VID = "7693557924731309081";
    static final String SERIES = "7693501483337141273";

    public static void main(String[] args) throws Exception {
        OkHttpClient client = new OkHttpClient();
        String dmBody = "{\"comment_source\":601,\"server_channel\":1000,\"group_id\":\"" + VID + "\","
                + "\"group_type\":30,\"comment_type\":20,\"sort\":1,\"count\":90,\"cursor\":\"\","
                + "\"aid\":8662,\"compliance_status\":0,"
                + "\"business_param\":{\"book_id\":\"" + SERIES + "\",\"start_offset_time\":0,"
                + "\"playlet_item_duration\":120000,"
                + "\"need_danmaku_guide_type\":[1,3,4,2]}}";
        String dm = postComment(client, "/novel/commentapi/comment/list/" + VID + "/v1/", dmBody);
        System.out.println("danmaku(评论签名) head: " + dm.substring(0, Math.min(600, dm.length())));
    }

    /** 复刻 Go：评论请求走专用签名 + Comment-Source/Server-Channel 头，X-SS-STUB 置空 */
    static String postComment(OkHttpClient client, String path, String jsonBody) throws Exception {
        byte[] body = jsonBody.getBytes("UTF-8");

        // query 与 SignedRequest 一致（含 _rticket）
        SignedRequest base = new SignedRequest(path, body);
        String query = base.url.encodedQuery();
        long now = System.currentTimeMillis();
        // _rticket 需与签名时刻一致：重造 query，把 _rticket 换成 now
        String fixedQuery = query.replaceAll("_rticket=\\d+", "_rticket=" + now);

        HongguoCommentSign.Result sign = HongguoCommentSign.sign(fixedQuery, now);

        okhttp3.HttpUrl url = base.url.newBuilder()
                .setQueryParameter("_rticket", String.valueOf(now))
                .build();

        Request.Builder rb = new Request.Builder().url(url);
        rb.header("User-Agent", com.houg.tv.sign.XGorgon.USER_AGENT);
        rb.header("Accept", "application/json");
        rb.header("X-XS-From-Web", "0");
        rb.header("Sdk-Version", "2");
        rb.header("Content-Type", "application/json; charset=utf-8");
        rb.header("Comment-Source", "601");
        rb.header("Server-Channel", "1000");
        rb.header("X-Gorgon", sign.xGorgon);
        rb.header("X-Khronos", sign.xKhronos);
        rb.header("X-SS-Req-Ticket", sign.xReqTicket);
        rb.header("X-SS-STUB", "");
        rb.header("X-Argus", sign.xArgus);
        rb.header("X-Ladon", sign.xLadon);
        rb.post(RequestBody.create(MediaType.parse("application/json; charset=utf-8"), body));
        try (Response resp = client.newCall(rb.build()).execute()) {
            String text = resp.body() == null ? "" : resp.body().string();
            System.out.println("POST " + path + " -> " + resp.code());
            return text;
        }
    }
}
