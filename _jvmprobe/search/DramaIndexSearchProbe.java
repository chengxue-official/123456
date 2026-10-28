import com.houg.tv.repo.DramaIndex;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * DramaIndex.search JVM 回归（生产源码 DramaIndex.kt / Pinyin.kt 原文编译，不改逻辑）。
 *
 * 覆盖：
 *  1. 索引未注入 → 返回空列表（不抛异常）；
 *  2. 前缀命中排在包含命中之前（两段式排序）；
 *  3. 多音字任一读音前缀命中（重生：CS / ZS）；
 *  4. 标点跳过后仍能对齐（《寻她》）；
 *  5. 中文关键词走标题 startsWith/contains 两段排序；
 *  6. 小写查询自动转大写等效；
 *  7. 空查询 → 空；长于剧名首字母数的查询 → 空；
 *  8. limit 截断（前缀优先保留）。
 */
public final class DramaIndexSearchProbe {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // ---- 1) 未注入索引：search 必须返回空且不抛 ----
        try {
            List<DramaIndex.Entry> r0 = DramaIndex.INSTANCE.search("LJ", 50);
            report(r0.isEmpty(), "no-index returns empty", "size=" + r0.size());
        } catch (Throwable t) {
            report(false, "no-index returns empty", "threw " + t);
        }

        // ---- 注入索引（模拟磁盘/重建后的内存索引） ----
        List<DramaIndex.Entry> entries = Arrays.asList(
                entry("1001", "绿箭侠", "LJX"),            // LJ 前缀命中
                entry("1002", "白龙剑", "BLJ"),            // LJ 包含命中（非前缀）
                entry("1003", "重生之都市仙尊", "ZSZDSXZ"), // 多音字：CS/ZS 前缀
                entry("1004", "长夜难明", "CYNM"),          // 多音字：CY/ZY 前缀
                entry("1005", "寻她", "XT"),               // X 前缀；中文“寻”前缀
                entry("1006", "《寻她》", "XT"),            // 标点跳过后 X 前缀；中文“寻”包含
                entry("1007", "西游", "XY"),               // X 前缀（limit 用）
                entry("1008", "行者无疆", "XZWJ"),          // X 前缀（limit 用）
                entry("1009", "小草", "XC"),               // X 前缀（limit 用）
                entry("1010", "少女武林", "SNWL")           // 与查询无关（不应出现）
        );
        setEntries(entries);

        // ---- 2) 前缀优先于包含（LJ） ----
        List<DramaIndex.Entry> r = DramaIndex.INSTANCE.search("LJ", 50);
        report(r.size() == 2
                        && "1001".equals(r.get(0).getId())
                        && "1002".equals(r.get(1).getId()),
                "prefix before contains (LJ)", ids(r));

        // ---- 3) 多音字任一读音都算前缀命中 ----
        r = DramaIndex.INSTANCE.search("CS", 50);
        report(r.size() >= 1 && "1003".equals(r.get(0).getId()),
                "polyphone CS prefix hit (重生)", ids(r));
        r = DramaIndex.INSTANCE.search("ZS", 50);
        report(r.size() >= 1 && "1003".equals(r.get(0).getId()),
                "polyphone ZS prefix hit (重生)", ids(r));

        // ---- 4) 标点跳过后仍能对齐 ----
        r = DramaIndex.INSTANCE.search("XT", 50);
        boolean hasPunct = false;
        for (DramaIndex.Entry e : r) {
            if ("1006".equals(e.getId())) hasPunct = true;
        }
        report(hasPunct, "punctuated title matches XT", ids(r));

        // ---- 5) 中文关键词两段排序：startsWith 在 contains 前 ----
        r = DramaIndex.INSTANCE.search("寻", 50);
        report(r.size() == 2
                        && "1005".equals(r.get(0).getId())
                        && "1006".equals(r.get(1).getId()),
                "chinese prefix before contains (寻)", ids(r));

        // ---- 6) 小写查询等效大写 ----
        List<DramaIndex.Entry> up = DramaIndex.INSTANCE.search("LJ", 50);
        List<DramaIndex.Entry> low = DramaIndex.INSTANCE.search("lj", 50);
        report(ids(up).equals(ids(low)), "lowercase query equals uppercase",
                ids(up) + " vs " + ids(low));

        // ---- 7) 空查询 / 超长查询 ----
        report(DramaIndex.INSTANCE.search("   ", 50).isEmpty(), "blank query -> empty", "");
        report(DramaIndex.INSTANCE.search("XTH", 50).isEmpty(),
                "query longer than initials -> empty", ids(DramaIndex.INSTANCE.search("XTH", 50)));

        // ---- 8) limit 截断：前缀命中优先保留 ----
        r = DramaIndex.INSTANCE.search("X", 2);
        // X 前缀命中：1005 寻她 / 1006《寻她》 / 1007 西游 / 1008 行者无疆 / 1009 小草
        report(r.size() == 2
                        && "1005".equals(r.get(0).getId())
                        && "1006".equals(r.get(1).getId()),
                "limit=2 keeps first prefix hits", ids(r));
        r = DramaIndex.INSTANCE.search("X", 50);
        // 前缀 5 条（1005,1006,1007,1008,1009）+ 包含兜底 2 条（LJX / ZSZDSXZ 含 X）。
        // 关键断言：包含命中不得排到前缀命中之前。
        report(r.size() == 7
                        && "1005".equals(r.get(0).getId())
                        && "1009".equals(r.get(4).getId())
                        && "1001".equals(r.get(5).getId())
                        && "1003".equals(r.get(6).getId()),
                "query X: 5 prefix then 2 contains", ids(r));

        System.out.println();
        System.out.println("RESULT: " + passed + " passed / " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static DramaIndex.Entry entry(String id, String title, String initials) {
        return new DramaIndex.Entry(id, title, "", initials);
    }

    private static void setEntries(List<DramaIndex.Entry> list) throws Exception {
        Field f = DramaIndex.class.getDeclaredField("entries");
        f.setAccessible(true);
        f.set(null, new ArrayList<>(list));
    }

    private static String ids(List<DramaIndex.Entry> list) {
        StringBuilder sb = new StringBuilder("[");
        for (DramaIndex.Entry e : list) {
            if (sb.length() > 1) sb.append(',');
            sb.append(e.getId()).append(':').append(e.getTitle());
        }
        return sb.append(']').toString();
    }

    private static void report(boolean ok, String name, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name + (detail.isEmpty() ? "" : "  | " + detail));
        } else {
            failed++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  | " + detail));
        }
    }
}
