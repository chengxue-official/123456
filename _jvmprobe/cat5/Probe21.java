import android.content.Context;
import com.houg.tv.net.NetClient;
import com.houg.tv.sign.DeviceRegister;
import okhttp3.OkHttpClient;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Probe21: 复核「准确版」findSiblingSeasons（建议接口 + 官方全库搜索逐季并行补全）
 *
 * 关键验收：
 *   万倍第三季 → 必须召回 S4（7692425329742203928），这是建议接口漏掉的季。
 */
public class Probe21 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        NetClient nc = new NetClient(CLIENT);

        log("===== findSiblingSeasons（准确版）=====");
        sibling(nc, "你疯了别惹他，他老婆比禁忌还凶万倍第三季", "7686109178997263384");
        sibling(nc, "千万别惹他，他老婆比禁忌还凶亿倍第四季", "");
        sibling(nc, "秦爷夫人又孕吐了第二季", "7695006178228587544");
        sibling(nc, "秦爷夫人又孕吐了", "7630830352579185689");
        sibling(nc, "季总别虐了，舒小姐嫁人了", "7360622728073710654");

        OUT.close();
        System.out.println("DONE");
    }

    static void sibling(NetClient nc, String title, String selfId) {
        long t0 = System.currentTimeMillis();
        try {
            List<NetClient.Drama> list = nc.findSiblingSeasons(title, selfId);
            StringBuilder sb = new StringBuilder("  " + title + " → " + list.size() + " 条 ("
                    + (System.currentTimeMillis() - t0) + "ms)");
            for (NetClient.Drama d : list) sb.append("\n      ").append(d.id).append("  ").append(d.title);
            log(sb.toString());
        } catch (Exception e) {
            log("  " + title + " 失败: " + e);
        }
    }

    static void log(String s) {
        try {
            OUT.write(s);
            OUT.newLine();
            OUT.flush();
        } catch (Exception ignored) {
        }
        System.out.println(s);
    }
}
