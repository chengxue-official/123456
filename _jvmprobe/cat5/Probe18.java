import android.content.Context;
import com.houg.tv.net.NetClient;
import com.houg.tv.sign.DeviceRegister;
import okhttp3.OkHttpClient;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Probe18: 对比「官方搜索」与「建议接口」对同系列各季的召回。
 * 目的：确认 findSiblingSeasons 应该走官方搜索（全季）而不是建议接口变体探测。
 */
public class Probe18 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        NetClient nc = new NetClient(CLIENT);
        Method official = NetClient.class.getDeclaredMethod("fetchOfficialSearchDramas",
                String.class, long.class, List.class, NetClient.SearchSink.class);
        official.setAccessible(true);
        Method suggest = NetClient.class.getDeclaredMethod("fetchSuggestDramas", String.class, int.class);
        suggest.setAccessible(true);

        String[] queries = {
                "秦爷夫人又孕吐了",
                "你疯了别惹他，他老婆比禁忌还凶万倍",
                "千万别惹他，他老婆比禁忌还凶亿倍",
        };
        for (String q : queries) {
            log("===== " + q + " =====");
            long t0 = System.currentTimeMillis();
            try {
                @SuppressWarnings("unchecked")
                List<NetClient.Drama> list = (List<NetClient.Drama>) official.invoke(
                        nc, q, System.currentTimeMillis() + 4000, new ArrayList<NetClient.Drama>(), null);
                StringBuilder sb = new StringBuilder("  官方搜索 " + list.size() + " 条 ("
                        + (System.currentTimeMillis() - t0) + "ms)");
                for (NetClient.Drama d : list) sb.append("\n      ").append(d.id).append("  ").append(d.title);
                log(sb.toString());
            } catch (Exception e) {
                log("  官方搜索失败: " + e);
            }
            t0 = System.currentTimeMillis();
            try {
                @SuppressWarnings("unchecked")
                List<NetClient.Drama> list = (List<NetClient.Drama>) suggest.invoke(nc, q, 50);
                StringBuilder sb = new StringBuilder("  建议接口 " + list.size() + " 条 ("
                        + (System.currentTimeMillis() - t0) + "ms)");
                for (NetClient.Drama d : list) sb.append("\n      ").append(d.id).append("  ").append(d.title);
                log(sb.toString());
            } catch (Exception e) {
                log("  建议接口失败: " + e);
            }
        }

        OUT.close();
        System.out.println("DONE");
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
