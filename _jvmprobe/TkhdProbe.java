import com.houg.tv.playback.CencMp4Parser;
import java.nio.file.*;

/**
 * 验证 CencMp4Parser.displayGeometry 的 tkhd 偏移计算。
 * 用真实样本文件（含旋转 90° 的竖屏视频更佳）断言：宽高与旋转角。
 * 无样本文件时输出解析结果供人工核对。
 */
public class TkhdProbe {
    public static void main(String[] args) throws Exception {
        String[] candidates = {
            "D:/hongguo/HougTime/_sample_clear_ref.mp4",
            "D:/hongguo/HougTime/_sample_clear_java.mp4",
            "D:/hongguo/HougTime/_sample_enc.mp4"
        };
        boolean found = false;
        for (String f : candidates) {
            Path p = Paths.get(f);
            if (!Files.exists(p)) continue;
            byte[] buf = Files.readAllBytes(p);
            java.util.List<CencMp4Parser.Box> top = CencMp4Parser.parse(buf, 0, buf.length);
            for (CencMp4Parser.Box trak : CencMp4Parser.find(top, "trak")) {
                CencMp4Parser.DisplayGeometry g = CencMp4Parser.displayGeometry(buf, trak);
                System.out.println(f + " trak: w=" + g.width + " h=" + g.height
                        + " rot=" + g.rotation);
                found = true;
            }
        }
        if (!found) System.out.println("NO SAMPLE FILE");
    }
}
