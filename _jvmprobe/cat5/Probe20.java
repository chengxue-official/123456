import android.content.Context;
import com.houg.tv.net.NetClient;
import com.houg.tv.sign.DeviceRegister;
import okhttp3.OkHttpClient;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Probe20：搞清「万倍 S3/S4 到底是没有资源，还是只是搜不到」
 * 1) detail(S3 id) —— 资源在不在（能不能拿到标题+集数）
 * 2) 建议接口按裸基础名 —— 服务端「搜索名单」里到底有哪些季
 * 3) 服务端搜索接口按裸基础名/全名 —— 换一条路能不能搜到 S3/S4
 * 4) 本地分类全量（前 60 页 short_play）—— App 本地片名索引里有没有 S3/S4
 */
public class Probe20 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;
    static final String BASE = "你疯了别惹他，他老婆比禁忌还凶万倍";

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);
        NetClient nc = new NetClient(CLIENT);

        log("===== 1) 资源在不在：detail =====");
        detail(nc, "7686109178997263384", "S3（本剧）");
        // S4 的 id 从服务端搜索/本地分类里找，找不到就跳过
        log("===== 2) 建议接口（服务端搜索名单） =====");
        suggest(nc, BASE);
        suggest(nc, BASE + "第三季");
        suggest(nc, BASE + "第四季");
        log("===== 3) 服务端搜索接口 =====");
        officialSearch(nc, BASE);
        officialSearch(nc, BASE + "第三季");
        officialSearch(nc, BASE + "第四季");
        log("===== 4) 本地分类全量（前 60 页）里有没有 =====");
        catalog(nc);

        OUT.close();
        System.out.println("DONE");
    }

    static void detail(NetClient nc, String id, String tag) {
        try {
            NetClient.SeriesDetail sd = nc.detail(id);
            log("  " + tag + " id=" + id + " → 标题=" + sd.title + "，集数="
                    + (sd.episodes == null ? -1 : sd.episodes.size()));
        } catch (Exception e) {
            log("  " + tag + " id=" + id + " → 失败: " + e.getMessage());
        }
    }

    /** 反射调私有 fetchSuggestDramas */
    static void suggest(NetClient nc, String kw) {
        try {
            Method m = NetClient.class.getDeclaredMethod("fetchSuggestDramas", String.class, int.class);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<NetClient.Drama> list = (List<NetClient.Drama>) m.invoke(nc, kw, 20);
            StringBuilder sb = new StringBuilder("  建议 query=" + kw + " → " + list.size() + " 条");
            for (NetClient.Drama d : list) sb.append("\n      ").append(d.id).append("  ").append(d.title);
            log(sb.toString());
        } catch (Exception e) {
            log("  建议 query=" + kw + " 失败: " + e.getMessage());
        }
    }

    /** 反射调私有 fetchOfficialSearchDramas(keyword, deadlineMs, live, sink)：直接看返回条数与标题 */
    static void officialSearch(NetClient nc, String kw) {
        long t0 = System.currentTimeMillis();
        try {
            List<NetClient.Drama> list = nc.search(kw);
            StringBuilder sb = new StringBuilder("  服务端搜索 query=" + kw + " → " + list.size()
                    + " 条 (" + (System.currentTimeMillis() - t0) + "ms)");
            int n = 0;
            for (NetClient.Drama d : list) {
                if (d.title != null && d.title.contains("万倍")) {
                    sb.append("\n      ").append(d.id).append("  ").append(d.title);
                    n++;
                    if (n >= 10) break;
                }
            }
            if (n == 0) sb.append("\n      （结果里没有一条含「万倍」）");
            log(sb.toString());
        } catch (Exception e) {
            log("  服务端搜索 query=" + kw + " 失败: " + e.getMessage());
        }
    }

    static void catalog(NetClient nc) {
        try {
            Method m = NetClient.class.getDeclaredMethod("catalogAll", String.class, String.class, int.class);
            @SuppressWarnings("unchecked")
            List<NetClient.Drama> list = (List<NetClient.Drama>) m.invoke(nc, "short_play", "default", 60);
            int hit = 0;
            for (NetClient.Drama d : list) {
                if (d.title != null && d.title.contains("万倍")) {
                    log("      分类命中 " + d.id + "  " + d.title);
                    hit++;
                }
            }
            log("  分类共 " + list.size() + " 条，含「万倍」" + hit + " 条");
            if (hit == 0) log("  （前 60 页没找到，可能排在更后面）");
        } catch (Exception e) {
            log("  分类拉取失败: " + e.getMessage());
        }
    }

    static void log(String s) {
        try {
            OUT.write(s);
            OUT.newLine();
            OUT.flush();
        } catch (Exception ignored) {
        }
    }
}
