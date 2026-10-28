import android.content.Context;
import com.houg.tv.net.NetClient;
import com.houg.tv.sign.DeviceRegister;
import okhttp3.OkHttpClient;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Probe17: 复核本轮三项改动
 * 1) findSiblingSeasons 召回（含「无后缀=第1季」这类真实样本）
 * 2) findRelated 召回
 * 3) DramaIndex 模糊搜索（反射塞入小样本索引，跑错字查询）
 */
public class Probe17 {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build();
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));
        DeviceRegister.attach(new Context());
        DeviceRegister.ensureRegistered(CLIENT);

        NetClient nc = new NetClient(CLIENT);

        log("===== 1) findSiblingSeasons =====");
        sibling(nc, "秦爷夫人又孕吐了", "7630830352579185689");
        sibling(nc, "秦爷夫人又孕吐了第二季", "7695006178228587544");
        sibling(nc, "你疯了别惹他，他老婆比禁忌还凶万倍第三季", "7686109178997263384");
        sibling(nc, "千万别惹他，他老婆比禁忌还凶亿倍第四季", "");

        log("===== 2) findRelated =====");
        related(nc, "季总别虐了，舒小姐嫁人了", "7360622728073710654");
        related(nc, "秦爷夫人又孕吐了第二季", "7695006178228587544");
        related(nc, "你疯了别惹他，他老婆比禁忌还凶万倍第三季", "7686109178997263384");

        log("===== 3) DramaIndex 模糊搜索 =====");
        fuzzy();

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

    static void related(NetClient nc, String title, String id) {
        long t0 = System.currentTimeMillis();
        try {
            List<NetClient.Drama> list = nc.findRelated(title, id);
            StringBuilder sb = new StringBuilder("  " + title + " → " + list.size() + " 条 ("
                    + (System.currentTimeMillis() - t0) + "ms)");
            for (NetClient.Drama d : list) sb.append("\n      ").append(d.id).append("  ").append(d.title);
            log(sb.toString());
        } catch (Exception e) {
            log("  " + title + " 失败: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    static void fuzzy() throws Exception {
        Class<?> idx = Class.forName("com.houg.tv.repo.DramaIndex");
        Object inst = idx.getField("INSTANCE").get(null);
        Class<?> entryCls = Class.forName("com.houg.tv.repo.DramaIndex$Entry");
        Constructor<?> ctor = entryCls.getConstructor(
                String.class, String.class, String.class, String.class);
        String[][] samples = {
                {"1", "总裁的替身新娘", "", ""},
                {"2", "战神归来", "", ""},
                {"3", "千金归来", "", ""},
                {"4", "重生之豪门千金", "", ""},
                {"5", "闪婚老公是首富", "", ""},
                {"6", "秦爷夫人又孕吐了第二季", "", ""},
        };
        List<Object> list = new ArrayList<>();
        for (String[] s : samples) list.add(ctor.newInstance(s[0], s[1], s[2], s[3]));
        Field f = idx.getDeclaredField("entries");
        f.setAccessible(true);
        f.set(inst, list);
        Method search = idx.getMethod("search", String.class, int.class);
        String[] queries = {
                "总裁的替身新狼",     // 错 1 字
                "战神归莱",           // 错 1 字
                "千金规来",           // 错 2 字
                "赛总滴替身新娘",     // 错 2 字
                "秦爷夫人又孕吐了第2季", // 中文数字→阿拉伯，精确应不中，看模糊
                "完全不相干的东西啊", // 应 0 条
                "总裁",               // 精确命中，不走模糊
        };
        for (String q : queries) {
            List<Object> hits = (List<Object>) search.invoke(inst, q, 50);
            StringBuilder sb = new StringBuilder("  q=" + q + " → " + hits.size() + " 条");
            for (Object h : hits) sb.append(" | ").append(entryCls.getMethod("getTitle").invoke(h));
            log(sb.toString());
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
