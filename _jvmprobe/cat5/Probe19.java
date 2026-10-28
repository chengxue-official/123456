import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Probe19: DramaIndex.search 边界补测（反射塞入小样本索引，不联网）
 * 覆盖：空串 / 纯空白 / 单字命中 / 单字不命中 / 纯数字 / 超长串 /
 *       重复条目（同名不同 id）/ 全角书名号 / 短查询对长标题 / 空标题条目。
 */
public class Probe19 {
    static BufferedWriter OUT;

    public static void main(String[] args) throws Exception {
        OUT = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(args[0], false), StandardCharsets.UTF_8));

        Class<?> idx = Class.forName("com.houg.tv.repo.DramaIndex");
        Object inst = idx.getField("INSTANCE").get(null);
        Class<?> entryCls = Class.forName("com.houg.tv.repo.DramaIndex$Entry");
        Constructor<?> ctor = entryCls.getConstructor(
                String.class, String.class, String.class, String.class);

        String[][] samples = {
                {"1", "总裁的替身新娘", "", "ZC"},
                {"2", "战神归来", "", "ZSGL"},
                {"3", "千金归来", "", "QJGL"},
                {"4", "重生之豪门千金", "", "CS"},
                {"5", "闪婚老公是首富", "", "SH"},
                {"6", "秦爷夫人又孕吐了第二季", "", "QY"},
                {"7", "战神归来", "", "ZSGL"},   // 重复：同名不同 id
                {"8", "", "", "X"},              // 空标题条目
        };
        List<Object> list = new ArrayList<>();
        for (String[] s : samples) list.add(ctor.newInstance(s[0], s[1], s[2], s[3]));
        Field f = idx.getDeclaredField("entries");
        f.setAccessible(true);
        f.set(inst, list);

        Method search = idx.getMethod("search", String.class, int.class);
        String[] queries = {
                "",                              // 空串
                "   ",                           // 纯空白
                "战",                            // 单字，精确命中 2 条（含重复）
                "剑",                            // 单字，精确不中 → 长度<2 不模糊
                "12345",                         // 纯数字（拉丁分支，无模糊兜底）
                "123456789012345678901234567890",// 30 字超长
                "战神归莱",                      // 4 字错 1 字
                "归莱",                          // 2 字短查询 vs 4 字标题（长度差剪枝）
                "《战神归来》",                  // 全角书名号 → 归一化后应命中
                "秦爷夫人又孕吐了第2季",         // 中文数字/阿拉伯数字混写
                "总裁的替身新娘",                // 精确整名
                "SH",                            // 首字母
        };
        for (String q : queries) {
            long t0 = System.currentTimeMillis();
            try {
                @SuppressWarnings("unchecked")
                List<Object> hits = (List<Object>) search.invoke(inst, q, 50);
                StringBuilder sb = new StringBuilder("q=\"" + q + "\" → " + hits.size() + " 条 ("
                        + (System.currentTimeMillis() - t0) + "ms)");
                for (Object h : hits) {
                    sb.append(" | ").append(entryCls.getMethod("getId").invoke(h))
                      .append(":").append(entryCls.getMethod("getTitle").invoke(h));
                }
                log(sb.toString());
            } catch (Throwable e) {
                log("q=\"" + q + "\" 抛异常: " + e);
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
