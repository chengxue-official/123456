import android.content.Context;
import com.houg.tv.sign.DeviceRegister;
import com.houg.tv.sign.SignedRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Probe9：把 suggest / tab 两个官方搜索接口的原始返回落到文件，逐字分析。
 */
public class Probe9 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build();

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : ".";
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        String[] qs = {
                "你疯了别惹他",
                "他老婆比禁忌还凶万倍",
                "你疯了别惹他，他老婆比禁忌还凶万倍第三季",
                "秦爷夫人又孕吐了"
        };
        int i = 0;
        for (String q : qs) {
            i++;
            String qEnc = java.net.URLEncoder.encode(q, "UTF-8");
            dump(dir + "\\suggest_" + i + ".json",
                    "/reading/bookapi/search/suggest/v/?query=" + qEnc
                            + "&count=50&offset=0&aid=8662&search_type=short_play", q);
            dump(dir + "\\tab_" + i + ".json",
                    "/reading/bookapi/search/tab/v/?query=" + qEnc
                            + "&count=50&offset=0&aid=8662&search_type=short_play", q);
        }
        System.out.println("DONE");
    }

    static void dump(String file, String pathAndQuery, String q) {
        try {
            SignedRequest req = new SignedRequest(pathAndQuery, new byte[0], false);
            Request.Builder rb = new Request.Builder().url(req.url);
            for (java.util.Map.Entry<String, String> e : req.headers.entrySet()) {
                rb.header(e.getKey(), e.getValue());
            }
            String text;
            int code;
            try (Response resp = CLIENT.newCall(rb.build()).execute()) {
                code = resp.code();
                text = resp.body() == null ? "" : resp.body().string();
            }
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, false), StandardCharsets.UTF_8))) {
                w.write("// PATH " + pathAndQuery);
                w.newLine();
                w.write("// HTTP " + code + " len=" + text.length() + " q=" + q);
                w.newLine();
                w.write(text);
            }
            System.out.println(file + " HTTP " + code + " len=" + text.length());
        } catch (Exception e) {
            System.out.println(file + " FAIL " + e);
        }
    }
}
