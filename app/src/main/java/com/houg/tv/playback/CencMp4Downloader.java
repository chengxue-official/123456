package com.houg.tv.playback;

import com.houg.tv.sign.XGorgon;

import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 下载HG加密 mp4（带 App UA 与固定 Referer）。
 *
 * 线上 1.1.3 MiTV 日志：媒体 CDN 偶发限流（HTTP 429）与连接重置
 * （HTTP/2 stream was reset: INTERNAL_ERROR），限流窗口以分钟计。
 * 旧实现每个地址只打一次就放弃——用户立刻看到「视频下载或解密失败」，
 * 手动重试又撞上同一限流窗口，只能杀进程等几分钟才好。
 * 因此在「换地址快速重试」之外，对 429/5xx/网络瞬断增加带退避的整轮重试。
 */
public final class CencMp4Downloader {

    private static final String REFERER = "https://novel.snssdk.com/";

    /** 退避轮之间的等待：首次 + 3 轮退避，总等待约 14s。 */
    private static final long[] BACKOFF_WAITS_MS = {2000L, 4000L, 8000L};

    private final OkHttpClient client;

    public CencMp4Downloader(OkHttpClient client) {
        this.client = client;
    }

    public byte[] download(String url) throws Exception {
        Request.Builder rb = new Request.Builder().url(url)
                .header("User-Agent", XGorgon.USER_AGENT)
                .header("Referer", REFERER)
                .header("Accept", "*/*");
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) {
                throw new Exception("HG媒体下载 HTTP " + resp.code());
            }
            if (resp.body() == null) {
                throw new Exception("HG媒体下载无响应体");
            }
            return resp.body().bytes();
        }
    }

    /**
     * 依次尝试多个地址（地址之间不等待，换节点可能立刻恢复）；
     * 全部失败且属可重试错误（429/5xx/网络瞬断）时按退避表整轮重试。
     * 全部轮次失败时优先抛出「可重试」那个异常——同一变体的多个备用地址里，
     * 若 429 被后面的 404 覆盖，上层就拿不到限流信号，既不退避也不给冷却。
     */
    public byte[] downloadWithRetry(List<String> urls) throws Exception {
        return downloadWithRetry(urls, 0L);
    }

    /**
     * 带总时间预算的版本（R-01）：budgetMs &gt; 0 时，退避轮若会把总耗时
     * 推过预算就直接放弃，起播不再转圈几分钟。预算只用于起播（用户在等）；
     * 预加载在后台跑，传 0 不限。预算耗尽抛首轮记录的可重试异常（429 信号不丢）。
     */
    public byte[] downloadWithRetry(List<String> urls, long budgetMs) throws Exception {
        // 空地址列表直接失败：否则白等 3 轮退避（约 14s）才抛「下载失败」，
        // 起播转圈被无意义拉长（QA BUG-002）。
        if (urls == null || urls.isEmpty()) {
            throw new Exception("HG媒体下载无可用地址");
        }
        final long deadline = budgetMs > 0 ? System.currentTimeMillis() + budgetMs : 0L;
        Exception last = null;
        Exception retryableErr = null;
        for (String url : urls) {
            try {
                return download(url);
            } catch (Exception e) {
                last = e;
                if (retryableErr == null && retryable(e)) retryableErr = e;
            }
        }
        if (retryableErr == null) {
            throw last;
        }
        for (long waitMs : BACKOFF_WAITS_MS) {
            // 起播预算（R-01）：这一轮退避若会把总耗时推过预算，直接放弃
            // 剩余轮次，宁可早报错让用户点重试，也不让转圈拖到几分钟。
            if (deadline > 0 && System.currentTimeMillis() + waitMs > deadline) {
                break;
            }
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ie) {
                throw retryableErr;
            }
            for (String url : urls) {
                try {
                    return download(url);
                } catch (Exception e) {
                    // 退避轮次失败不再覆盖 retryableErr，最终统一抛首轮的可重试异常
                }
            }
        }
        // retryableErr 必非空（能走到这里说明首轮出现过可重试错误）。
        // 不抛 last：首轮里若混有 404/403，last 已被覆盖，抛它会丢掉限流信号（QA BUG-006）。
        throw retryableErr;
    }

    /** 429 限流与 5xx 是瞬态可重试；403/404 等永久错误不退避。其余按网络瞬断处理。 */
    private static boolean retryable(Exception e) {
        String m = e == null ? null : e.getMessage();
        if (m == null || !m.startsWith("HG媒体下载 HTTP ")) return true;
        String code = m.substring("HG媒体下载 HTTP ".length());
        return "429".equals(code) || code.startsWith("5");
    }
}
