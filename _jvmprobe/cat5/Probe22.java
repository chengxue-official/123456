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
 * Probe22: 搜索流程的「多季补全」是否也漏季
 * （completeSeasons 仍走建议接口 + 预算 10 + 连续 2 次空手即停）
 */
public class Probe22 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);
        NetClient nc = new NetClient(CLIENT);

        log("===== search() 结果里的系列季数 =====");
        search(nc, "你疯了别惹他，他老婆比禁忌还凶万倍", "万倍");
        search(nc, "千万别惹他，他老婆比禁忌还凶亿倍", "亿倍");
        search(nc, "秦爷夫人又孕吐了", "秦爷夫人");

        OUT.close();
        System.out.println("DONE");
    }

    static void search(NetClient nc, String kw, String seriesMark) {
        long t0 = System.currentTimeMillis();
        try {
            List<NetClient.Drama> list = nc.search(kw);
            StringBuilder sb = new StringBuilder("  q=" + kw + " → 共 " + list.size()
                    + " 条（" + (System.currentTimeMillis() - t0) + "ms），系列条目：");
            int n = 0;
            for (NetClient.Drama d : list) {
                if (d.title == null || !d.title.contains(seriesMark)) continue;
                n++;
                sb.append("\n      ").append(d.id).append("  ").append(d.title);
            }
            sb.append("\n    系列命中 ").append(n).append(" 条");
            log(sb.toString());
        } catch (Exception e) {
            log("  q=" + kw + " 失败: " + e);
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
