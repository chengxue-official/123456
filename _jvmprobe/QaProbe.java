import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * QA 测试桩：把 NetClient 里搜索相关的纯算法函数复制出来，在 JVM 上跑边界用例。
 * 与生产代码同步维护（改动时同步这里）。
 */
public class QaProbe {

    /* ==== 与 NetClient 一致的常量与正则 ==== */
    static final int SEARCH_SEASON_MAX = 200;
    static final Pattern SEASON_MARK = Pattern.compile(
            "第\\s*([0-9０-９零〇一二两兩三四五六七八九十百]+)\\s*([季部])\\s*$");

    static int failed = 0;

    public static void main(String[] args) {
        testParseSeason();
        testChineseOrArabic();
        testChineseNum();
        testNormTitle();
        testSortTotalOrder();
        testMatchRank();
        System.out.println(failed == 0 ? "ALL PASS" : ("FAILED: " + failed));
        if (failed > 0) System.exit(1);
    }

    /* ---------- parseSeason ---------- */

    static String[] parseSeason(String title) {
        String t = title == null ? "" : title.trim();
        Matcher m = SEASON_MARK.matcher(t);
        if (!m.find()) return new String[]{t, "0", ""};
        String base = t.substring(0, m.start());
        while (base.length() > 0) {
            char c = base.charAt(base.length() - 1);
            if (Character.isWhitespace(c) || c == '-' || c == '：' || c == ':' || c == '《'
                    || c == '·' || c == '_') {
                base = base.substring(0, base.length() - 1);
            } else break;
        }
        int n = chineseOrArabic(m.group(1));
        String unit = m.group(2);
        if (base.isEmpty() || n < 1 || n > SEARCH_SEASON_MAX) {
            return new String[]{t, "0", ""};
        }
        return new String[]{base, String.valueOf(n), unit};
    }

    static int sign(int v) { return v < 0 ? -1 : (v > 0 ? 1 : 0); }

    static void eq(String name, Object actual, Object expected) {
        boolean ok = actual == null ? expected == null : actual.equals(expected);
        if (!ok) {
            failed++;
            System.out.println("FAIL " + name + ": actual=" + actual + " expected=" + expected);
        }
    }

    static String[] ss(String base, int season, String unit) {
        return new String[]{base, String.valueOf(season), unit};
    }

    static void testParseSeason() {
        // 基本中文数字（含此前 indexOf 偏移 bug 的回归用例）
        eq("s1", Arrays.asList(parseSeason("闪婚甜果第一季")), Arrays.asList(ss("闪婚甜果", 1, "季")));
        eq("s2", Arrays.asList(parseSeason("闪婚盛先生第二季")), Arrays.asList(ss("闪婚盛先生", 2, "季")));
        eq("s3", Arrays.asList(parseSeason("离婚后傅总后悔了科研大佬她不伺候了第三季")),
                Arrays.asList(ss("离婚后傅总后悔了科研大佬她不伺候了", 3, "季")));
        // 阿拉伯数字
        eq("s4", Arrays.asList(parseSeason("东北雀神风云第2部")), Arrays.asList(ss("东北雀神风云", 2, "部")));
        // 空格分隔
        eq("s5", Arrays.asList(parseSeason("闪婚 第2季")), Arrays.asList(ss("闪婚", 2, "季")));
        // 连字符/冒号结尾的基础名
        eq("s6", Arrays.asList(parseSeason("科研大佬她不伺候了-第一季")),
                Arrays.asList(ss("科研大佬她不伺候了", 1, "季")));
        eq("s7", Arrays.asList(parseSeason("甜桃烈酒：第三季")),
                Arrays.asList(ss("甜桃烈酒", 3, "季")));
        // 无季标记
        eq("s8", Arrays.asList(parseSeason("总裁是我前女友")), Arrays.asList(ss("总裁是我前女友", 0, "")));
        // 基础名为空 → 不算季
        eq("s9", Arrays.asList(parseSeason("第一季")), Arrays.asList(ss("第一季", 0, "")));
        // 超大季数 → 不算季
        eq("s10", Arrays.asList(parseSeason("剧名第999季")), Arrays.asList(ss("剧名第999季", 0, "")));
        // 中文大数
        eq("s11", 201, 201);
        eq("s11b", Arrays.asList(parseSeason("剧名第一百零一季")), Arrays.asList(ss("剧名", 101, "季")));
        eq("s11c", Arrays.asList(parseSeason("剧名第二十部")), Arrays.asList(ss("剧名", 20, "部")));
        // 全角数字
        eq("s12", Arrays.asList(parseSeason("剧名第２季")), Arrays.asList(ss("剧名", 2, "季")));
        // "两"
        eq("s13", Arrays.asList(parseSeason("剧名第两季")), Arrays.asList(ss("剧名", 2, "季")));
        // null/空
        eq("s14", Arrays.asList(parseSeason(null)), Arrays.asList(ss("", 0, "")));
        eq("s15", Arrays.asList(parseSeason("")), Arrays.asList(ss("", 0, "")));
        // 中间含季字但不在尾部（如「四季酒店的故事」）
        eq("s16", Arrays.asList(parseSeason("四季酒店的故事")), Arrays.asList(ss("四季酒店的故事", 0, "")));
        // 季在中间：第N季后还有内容 → 正则要求在词尾，不匹配
        eq("s17", Arrays.asList(parseSeason("第二季海报拍摄花絮")), Arrays.asList(ss("第二季海报拍摄花絮", 0, "")));
    }

    /* ---------- chineseOrArabic ---------- */

    static int chineseOrArabic(String label) {
        if (label == null || label.isEmpty()) return 0;
        try {
            return Integer.parseInt(label);
        } catch (Exception ignored) {
        }
        int digit = 0, number = 0, previous = 1000;
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            int d = digitOf(c);
            if (d >= 0) {
                digit = digit * 10 + d;
                if (digit > SEARCH_SEASON_MAX) return 0;
                continue;
            }
            int unit;
            if (c == '百') unit = 100;
            else if (c == '十') unit = 10;
            else return 0;
            if (unit >= previous || digit > 9) return 0;
            number += Math.max(digit, 1) * unit;
            if (number > SEARCH_SEASON_MAX) return 0;
            digit = 0;
            previous = unit;
        }
        number += digit;
        return number > SEARCH_SEASON_MAX ? 0 : number;
    }

    static int digitOf(char c) {
        switch (c) {
            case '零': case '〇': return 0;
            case '一': return 1;
            case '二': case '两': case '兩': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: break;
        }
        if (c >= '０' && c <= '９') return c - '０';
        return -1;
    }

    static void testChineseOrArabic() {
        eq("n1", chineseOrArabic("1"), 1);
        eq("n2", chineseOrArabic("12"), 12);
        eq("n3", chineseOrArabic("一"), 1);
        eq("n4", chineseOrArabic("二"), 2);
        eq("n5", chineseOrArabic("十"), 10);
        eq("n6", chineseOrArabic("十四"), 14);
        eq("n7", chineseOrArabic("二十"), 20);
        eq("n8", chineseOrArabic("二十一"), 21);
        eq("n9", chineseOrArabic("一百零一"), 101);
        eq("n10", chineseOrArabic("一百"), 100);
        eq("n11", chineseOrArabic("零"), 0);
        eq("n12", chineseOrArabic("〇"), 0);
        eq("n13", chineseOrArabic("两"), 2);
        eq("n14", chineseOrArabic("２"), 2);       // 全角
        eq("n15", chineseOrArabic("abc"), 0);
        eq("n16", chineseOrArabic(""), 0);
        eq("n17", chineseOrArabic(null), 0);
        eq("n18", chineseOrArabic("二百"), 200);
        eq("n19", chineseOrArabic("二百零一"), 0);   // 201 > SEARCH_SEASON_MAX(200) → 无效
        eq("n19b", chineseOrArabic("一百零一"), 101);
        // 超限 → 0（被上层拦截）
        eq("n20", chineseOrArabic("999"), 999);    // Integer.parseInt 成功，返回原值
        eq("n21", chineseOrArabic("一千"), 0);      // 千不在映射表 → 0
    }

    /* ---------- chineseNum ---------- */

    static String chineseNum(int n) {
        if (n < 1 || n > SEARCH_SEASON_MAX) return String.valueOf(n);
        final char[] d = "零一二三四五六七八九".toCharArray();
        if (n < 10) return String.valueOf(d[n]);
        if (n < 20) return "十" + (n % 10 == 0 ? "" : String.valueOf(d[n % 10]));
        if (n < 100) {
            return String.valueOf(d[n / 10]) + "十" + (n % 10 == 0 ? "" : String.valueOf(d[n % 10]));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(d[n / 100]).append('百');
        int rest = n % 100;
        if (rest == 0) return sb.toString();
        if (rest < 10) sb.append('零');
        else if (rest < 20) sb.append('一');
        sb.append(chineseNum(rest));
        return sb.toString();
    }

    static void testChineseNum() {
        eq("c1", chineseNum(1), "一");
        eq("c2", chineseNum(9), "九");
        eq("c3", chineseNum(10), "十");
        eq("c4", chineseNum(11), "十一");
        eq("c5", chineseNum(20), "二十");
        eq("c6", chineseNum(21), "二十一");
        eq("c7", chineseNum(100), "一百");
        eq("c8", chineseNum(101), "一百零一");
        eq("c9", chineseNum(110), "一百一十");
        eq("c10", chineseNum(200), "二百");
        // 往返一致（parseSeason ∘ chineseNum = id，1..200）
        for (int n = 1; n <= 200; n++) {
            eq("roundtrip " + n, chineseOrArabic(chineseNum(n)), n);
        }
    }

    /* ---------- normTitle ---------- */

    static String normTitle(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            if (c == 0x3000) c = ' ';
            if (Character.isWhitespace(c) || c == '《' || c == '》' || c == '·' || c == '_'
                    || c == '-' || c == '：' || c == ':' || c == '，' || c == ',' || c == '！'
                    || c == '!' || c == '？' || c == '?' || c == '"' || c == '\'' || c == '“'
                    || c == '”' || c == '‘' || c == '’') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    static void testNormTitle() {
        eq("t1", normTitle("Flash Marriage"), "flashmarriage");
        eq("t2", normTitle("闪婚：甜桃烈酒"), "闪婚甜桃烈酒");
        eq("t3", normTitle("闪婚－甜桃烈酒"), "闪婚甜桃烈酒");   // 全角连字符
        eq("t4", normTitle("ＡＢＣ"), "abc");                    // 全角字母
        eq("t5", normTitle("　闪婚　"), "闪婚");                  // 全角空格
        eq("t6", normTitle(null), "");
        eq("t7", normTitle(""), "");
        eq("t8", normTitle("ABC"), normTitle("abc"));
    }

    /* ---------- 排序全序性（TimSort 压力测试） ---------- */

    static class D {
        final String title;
        final String id;
        D(String title, String id) { this.title = title; this.id = id; }
    }

    static List<D> sortResult(List<D> list, String keyword) {
        final String q = normTitle(keyword);
        Collections.sort(list, new Comparator<D>() {
            @Override public int compare(D a, D b) {
                int ra = matchRank(a.title, q), rb = matchRank(b.title, q);
                if (ra != rb) return ra < rb ? -1 : 1;
                String[] pa = parseSeason(a.title), pb = parseSeason(b.title);
                int baseCmp = normTitle(pa[0]).compareTo(normTitle(pb[0]));
                if (baseCmp != 0) return baseCmp;
                int unitCmp = pa[2].compareTo(pb[2]);
                if (unitCmp != 0) return unitCmp;
                int sa = Integer.parseInt(pa[1]), sb = Integer.parseInt(pb[1]);
                if (sa != sb) return sa < sb ? -1 : 1;
                return a.id.compareTo(b.id);
            }
        });
        return list;
    }

    static int matchRank(String title, String q) {
        String t = normTitle(title);
        if (q.isEmpty()) return 4;
        if (t.equals(q)) return 0;
        if (t.startsWith(q)) return 1;
        if (t.contains(q)) return 2;
        String[] ps = parseSeason(title);
        if (ps != null && !normTitle(ps[0]).isEmpty() && normTitle(ps[0]).contains(q)) return 3;
        return 4;
    }

    static void testSortTotalOrder() {
        // 1) 词典序键 + id 兜底 → 冒泡验证全序性（自反/反对称/传递）
        List<D> items = new ArrayList<>();
        String[] titles = {
                "离婚后傅总后悔了科研大佬她不伺候了", "离婚后傅总后悔了科研大佬她不伺候了第二季",
                "离婚后傅总后悔了科研大佬她不伺候了第三季", "离婚后我才知她是神明", "离婚后我才知她是神明第二季",
                "离婚后我成了全能歌王", "闪婚甜果第一季", "闪婚盛先生", "闪婚盛先生第二季",
                "闪婚后，傅先生马甲藏不住了", "总裁是我前女友", "总裁归来：从天桥开始",
                "剧名第2部", "剧名第10部", "剧名第十部", "剧名第11部",
        };
        for (int i = 0; i < titles.length; i++) items.add(new D(titles[i], String.valueOf(9000 - i * 7)));
        for (int i = 0; i < titles.length; i++) items.add(new D(titles[i], "10" + i));

        Comparator<D> probe = (x, y) -> {
            String q = normTitle("离婚");
            int ra = matchRank(x.title, q), rb = matchRank(y.title, q);
            if (ra != rb) return ra < rb ? -1 : 1;
            String[] pa = parseSeason(x.title), pb = parseSeason(y.title);
            int c = normTitle(pa[0]).compareTo(normTitle(pb[0]));
            if (c != 0) return c;
            c = pa[2].compareTo(pb[2]);
            if (c != 0) return c;
            int sa = Integer.parseInt(pa[1]), sb = Integer.parseInt(pb[1]);
            if (sa != sb) return sa < sb ? -1 : 1;
            return x.id.compareTo(y.id);
        };
        // 传递性验证：对每个三元组 a<b 且 b<c => a<c
        int n = items.size();
        int[][] cmp = new int[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                cmp[i][j] = probe.compare(items.get(i), items.get(j));
        for (int i = 0; i < n; i++) {
            eq("reflexive " + i, cmp[i][i] == 0 ? 0 : 1, 0);
            for (int j = 0; j < n; j++) {
                eq("antisym " + i + "," + j,
                        sign(cmp[i][j]) == -sign(cmp[j][i]) ? 0 : 1, 0);
                for (int k = 0; k < n; k++) {
                    if (cmp[i][j] < 0 && cmp[j][k] < 0 && cmp[i][k] >= 0) {
                        failed++;
                        System.out.println("FAIL transitivity " + i + ">" + j + ">" + k);
                    }
                }
            }
        }

        // 2) 大列表 TimSort 压力（200 条随机 id，带多季系列）
        List<D> big = new ArrayList<>();
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 200; i++) {
            int series = rnd.nextInt(10);
            int season = rnd.nextInt(5);
            String t = "系列" + series + (season == 0 ? "" : "第" + chineseNum(season) + "季");
            big.add(new D(t, String.valueOf(rnd.nextLong())));
        }
        try {
            sortResult(big, "系列");
            // 验证同系列按季升序
            for (int i = 1; i < big.size(); i++) {
                D prev = big.get(i - 1), cur = big.get(i);
                String[] pp = parseSeason(prev.title), cp = parseSeason(cur.title);
                if (normTitle(pp[0]).equals(normTitle(cp[0])) && pp[2].equals(cp[2])) {
                    int a = Integer.parseInt(pp[1]), b = Integer.parseInt(cp[1]);
                    if (a > b) {
                        failed++;
                        System.out.println("FAIL season order: " + prev.title + " before " + cur.title);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            failed++;
            System.out.println("FAIL timsort: " + e);
        }
    }

    static void testMatchRank() {
        String q = normTitle("闪婚");
        eq("r1", matchRank("闪婚", q), 0);
        eq("r2", matchRank("闪婚甜果第一季", q), 1);
        eq("r3", matchRank("甜闪婚糖", q), 2);
        eq("r4", matchRank("闪婚盛先生第二季", q), 1);
        eq("r5", matchRank("离婚", q), 4);
        String q2 = normTitle("离婚后傅总后悔了");
        // 标题以 q2 开头 → rank 1（前缀），优先于「包含」
        eq("r6", matchRank("离婚后傅总后悔了科研大佬她不伺候了第二季", q2), 1);
        String q3 = normTitle("科研大佬");
        // 基础名是标题前缀，含基础名 ⇒ 必然含于标题，所以 rank 3（仅基础名匹配）
        // 在实际数据里不可达；这里记录真实行为（rank 2）以免自欺。
        eq("r7", matchRank("离婚后傅总后悔了科研大佬她不伺候了第二季", q3), 2);
    }
}
