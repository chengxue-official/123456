import com.houg.tv.util.Pinyin;

/**
 * 拼音首字母映射回归（生产源码 Pinyin.kt 原文编译，不改逻辑）。
 *
 * 覆盖三块：
 *  1. GB2312 区位码「字母边界」字，尤其是 X 的上界修复（旧实现 D189..D1B8 丢成 \0）；
 *  2. 多音字任一读音命中（「重生」按 CS / ZS 都能搜到）；
 *  3. 标点/数字的保留与跳过规则。
 */
public final class PinyinProbe {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        // 常用字 → 期望首字母（覆盖 A~Z 各段，含边界字）
        expect("啊", 'A');
        expect("八", 'B');
        expect("才", 'C');
        expect("大", 'D');
        expect("额", 'E');
        expect("发", 'F');
        expect("个", 'G');
        expect("好", 'H');
        expect("就", 'J');
        expect("看", 'K');
        expect("了", 'L');
        expect("们", 'M');
        expect("你", 'N');
        expect("哦", 'O');
        expect("怕", 'P');
        expect("去", 'Q');
        expect("人", 'R');
        expect("是", 'S');
        expect("他", 'T');
        expect("我", 'W');
        expect("西", 'X');
        expect("有", 'Y');
        expect("在", 'Z');

        // X 段尾部（修复前会得到 \0）
        for (String c : new String[]{"迅", "寻", "勋", "巡", "训", "询", "熏", "循", "汛", "殉"}) {
            expect(c, 'X');
        }
        // X→Y→Z 边界
        expect("压", 'Y');
        expect("呀", 'Y');
        expect("匝", 'Z');

        // 整串（主读音；唢不在 GB2312 区，GBK 扩展字当前不映射 → 丢字母）
        expectLetters("唢呐修仙", "NXX");
        expectLetters("重生之都市仙尊", "ZSZDSXZ");
        // 字母/数字保留，标点跳过
        expectLetters("A计划2", "AJH2");
        expectLetters("《寻她》", "XT");

        // 多音字：任一读音都算前缀命中
        mp("重生之都市仙尊", "CS", true);   // chong sheng
        mp("重生之都市仙尊", "ZS", true);   // zhong sheng
        mp("重生之都市仙尊", "CSZDSXZ", true);
        mp("重生之都市仙尊", "ZSZDSXZ", true);
        mp("重生之都市仙尊", "XX", false);
        mp("长夜难明", "CY", true);          // chang
        mp("长夜难明", "ZY", true);          // zhang
        mp("行者无疆", "XZ", true);          // xing
        mp("行者无疆", "HZ", true);          // hang
        mp("解语花", "JY", true);
        mp("解语花", "XY", true);
        // 标点跳过
        mp("《寻她》", "XT", true);
        mp("A计划2", "AJH2", true);
        // 长于剧名首字母数的查询不应命中
        mp("寻她", "XTH", false);

        System.out.println();
        System.out.println("结果：" + passed + " 项通过 / " + failed + " 项失败");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void expect(String ch, char want) {
        char got = Pinyin.INSTANCE.firstLetter(ch.charAt(0));
        boolean ok = got == want;
        report(ok, "firstLetter(" + ch + ") = " + show(got) + (ok ? "" : "，期望 " + want));
    }

    private static void expectLetters(String input, String want) {
        String got = Pinyin.INSTANCE.firstLetters(input);
        boolean ok = want.equals(got);
        report(ok, "firstLetters(" + input + ") = " + got + (ok ? "" : "，期望 " + want));
    }

    private static void mp(String title, String query, boolean want) {
        boolean got = Pinyin.INSTANCE.matchesPrefix(title, query);
        report(got == want, "matchesPrefix(" + title + ", " + query + ") = " + got
                + (got == want ? "" : "，期望 " + want));
    }

    private static String show(char c) {
        return c == '\u0000' ? "\\0" : String.valueOf(c);
    }

    private static void report(boolean ok, String msg) {
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("[FAIL] " + msg);
        }
    }
}
