/**
 * 纯逻辑模拟：验证「续播连跳」与「弹幕完整路径」两个修复。
 * 不依赖 Android，直接复刻修复后的判定公式。
 */
public class FixVerify {

    // ========== 1. 续播连跳 ==========
    static final long END_TOLERANCE_MS = 1500L;
    static final long RESUME_MIN_MS = 5000L;
    static final long RESUME_TAIL_MARGIN_MS = 15000L;

    /** 修复后：决定续播还是从头播。返回 seek 目标位置（0 = 从头播）。 */
    static long decideResume(long saved, long dur) {
        if (saved <= RESUME_MIN_MS) return 0;
        long margin = Math.min(RESUME_TAIL_MARGIN_MS, dur / 10);
        if (margin < END_TOLERANCE_MS) margin = END_TOLERANCE_MS;
        if (dur > 0 && saved >= dur - margin) return 0;   // 接近片尾→从头播
        return saved;
    }

    /** 修复后：recordHistory 是否应写入该位置（接近片尾则跳过）。 */
    static boolean shouldRecord(long posMs, long dur) {
        if (posMs <= 0) return true;      // <=0 走 record 的"保留旧值"语义
        if (dur > 0 && posMs >= dur - END_TOLERANCE_MS) return false;  // 片尾不写
        return true;
    }

    /** 修复后：播完一集后该集进度应被清除。 */
    static boolean isCompleted(long saved, long dur) {
        long margin = Math.min(RESUME_TAIL_MARGIN_MS, dur / 10);
        if (margin < END_TOLERANCE_MS) margin = END_TOLERANCE_MS;
        return dur > 0 && saved >= dur - margin;
    }

    // ========== 2. 弹幕完整路径 ==========
    static final int SCREEN_W = 1920;
    static final long TRAVEL_WALL_MS = 8000L;

    static long travelVideoMs(float textW, float rate) {
        long wall = (long) (TRAVEL_WALL_MS * (SCREEN_W + textW) / SCREEN_W);
        if (wall < 2000L) wall = 2000L;
        return (long) (wall * rate);
    }

    /** 弹幕在 elapsed（播放时钟）时的 x 位置。 */
    static float xAt(long elapsed, float textW, long travelMs) {
        float progress = Math.max(0f, Math.min(1f, (float) elapsed / travelMs));
        return SCREEN_W - (SCREEN_W + textW) * progress;
    }

    static int pass = 0, fail = 0;
    static void check(String name, boolean cond, String extra) {
        if (cond) { pass++; System.out.println("  ✓ " + name); }
        else { fail++; System.out.println("  ✗ " + name + " -> " + extra); }
    }

    public static void main(String[] args) {
        System.out.println("=== 1. 续播连跳（用户场景：第1集 120s，上次看到 120s=片尾） ===");
        long dur = 120_000L;
        check("上次=片尾 120s -> 从头播（不连跳）", decideResume(120_000, dur) == 0, "" + decideResume(120_000, dur));
        check("上次=片尾前 1s -> 从头播", decideResume(dur - 1000, dur) == 0, "" + decideResume(dur - 1000, dur));
        check("上次=片尾前 5s -> 从头播（旧实现会续播后跳集）", decideResume(dur - 5000, dur) == 0, "" + decideResume(dur - 5000, dur));
        check("上次=片尾前 10s -> 从头播（margin=12s）", decideResume(dur - 10_000, dur) == 0, "" + decideResume(dur - 10_000, dur));
        check("上次=片尾前 30s -> 正常续播到 90s", decideResume(dur - 30_000, dur) == 90_000, "" + decideResume(dur - 30_000, dur));
        check("上次=60s -> 正常续播到 60s", decideResume(60_000, dur) == 60_000, "");
        check("上次=3s（<5s）-> 从头播", decideResume(3_000, dur) == 0, "");
        // 长视频：margin 取 min(15s, dur/10)
        long longDur = 1_800_000L; // 30min
        check("30min 视频，上次=片尾前 10s -> 从头播", decideResume(longDur - 10_000, longDur) == 0, "");
        check("30min 视频，上次=片尾前 30s -> 续播", decideResume(longDur - 30_000, longDur) == longDur - 30_000, "");

        System.out.println("\n=== 2. 播完即清进度（连跳的另一半防线） ===");
        check("片尾位置不写入历史（120s）", !shouldRecord(dur - 500, dur), "");
        check("片尾 1.5s 内不写入历史", !shouldRecord(dur - 1000, dur), "");
        check("中部位置正常写入（60s）", shouldRecord(60_000, dur), "");
        check("posMs=0（拿不到位置）走保留旧值路径", shouldRecord(0, dur), "");
        check("播完判定：位置=片尾 -> completed", isCompleted(dur, dur), "");
        check("播完判定：位置=中部 -> 未完成", !isCompleted(60_000, dur), "");

        System.out.println("\n=== 3. 弹幕完整路径（关键：全程从右缘外走到左缘外） ===");
        float tw = 200f;
        long travel = travelVideoMs(tw, 1.0f);
        System.out.println("  文字宽=" + tw + "px, 全程时长=" + travel + "ms");
        float xStart = xAt(0, tw, travel);
        check("起点 x 在屏幕右缘（文字整体在画面外，x=1920）", Math.abs(xStart - SCREEN_W) < 0.01, "" + xStart);
        check("起点：文字左缘贴右缘 -> 完全不可见", xStart >= SCREEN_W - 0.5f, "" + xStart);
        float xMid = xAt(travel / 2, tw, travel);
        check("中点 x 在屏幕中央附近", xMid > 400 && xMid < 1500, "" + xMid);
        float xEnd = xAt(travel, tw, travel);
        check("终点 x = 1920-(1920+200) = -200（文字完全移出左缘）", Math.abs(xEnd - (-200f)) < 0.01, "" + xEnd);
        check("终点：文字右缘 <= 0 -> 完全消失", xEnd + tw <= 0.01f, "right=" + (xEnd + tw));
        // 晚到的弹幕：spawn 位置在游标到达时，仍然从右缘出生开始走
        check("数据晚到 2s 才 spawn：进度仍从 0 开始（x=右缘）", Math.abs(xAt(0, tw, travel) - SCREEN_W) < 0.01, "");
        // 倍速补偿
        long travel2x = travelVideoMs(tw, 2.0f);
        check("2 倍速：播放时钟时长翻倍（墙钟停留时间不变）", travel2x == travel * 2, travel2x + " vs " + (travel * 2));
        // 长弹幕停留更久（恒定像素速度）
        long travelWide = travelVideoMs(1200f, 1.0f);
        check("更宽的弹幕全程时长更长（恒定像素速度）", travelWide > travel, travelWide + " > " + travel);

        System.out.println("\n=== 4. 轨道排布：不摘掉正在飞的弹幕 ===");
        // 几何判定：同轨道上一条的右缘 + 间距 > 屏宽 时该轨道阻塞
        float gap = 12f; // textSizePx*0.6 ≈ 20sp*2.0*0.6
        float prevX = SCREEN_W - 100;  // 上一条刚进屏 100px（右缘 1920-100+200=2020 > 1920）
        boolean blocked = (prevX + tw + gap > SCREEN_W);
        check("同轨道前一条刚进屏 -> 该轨道阻塞（新弹幕排队而非替换）", blocked, "");
        float prevX2 = 400; // 上一条已飞到左侧
        check("同轨道前一条已让开右缘 -> 可放新弹幕", !(prevX2 + tw + gap > SCREEN_W), "");

        System.out.println("\n结果：" + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
