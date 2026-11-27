package com.houg.tv.playback;

import com.houg.tv.sign.XGorgon;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 流式下载HG加密 mp4 到文件（带 App UA 与固定 Referer）。
 *
 * <p>旧实现用 `resp.body().bytes()` 把整集（~28MB）读进 Java 堆，再交给重封装再分配
 * 一份等大的输出数组，峰值 ≈ 2×文件大小，老电视/32 位进程直接 OOM
 * （线上 `Failed to allocate a 27994450 byte allocation`）。现改为 64KB 分块直写磁盘，
 * 内存占用与文件大小无关。
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

    /** 落盘缓冲：与文件大小无关的常量内存。 */
    private static final int COPY_BUFFER = 64 * 1024;

    private final OkHttpClient client;

    public CencMp4Downloader(OkHttpClient client) {
        this.client = client;
    }

    /**
     * 依次尝试多个地址（地址之间不等待，换节点可能立刻恢复）；
     * 全部失败且属可重试错误（429/5xx/网络瞬断）时按退避表整轮重试。
     * 全部轮次失败时优先抛出「可重试」那个异常——同一变体的多个备用地址里，
     * 若 429 被后面的 404 覆盖，上层就拿不到限流信号，既不退避也不给冷却。
     *
     * @param out      下载目标文件（内容会被覆盖）
     * @param budgetMs &gt; 0 时为总时间预算（起播用），预算内做不完就早报错
     */
    public void downloadToFileWithRetry(List<String> urls, File out, long budgetMs) throws Exception {
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
                downloadToFile(url, out);
                return;
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
                    downloadToFile(url, out);
                    return;
                } catch (Exception e) {
                    // 退避轮次失败不再覆盖 retryableErr，最终统一抛首轮的可重试异常
                }
            }
        }
        // retryableErr 必非空（能走到这里说明首轮出现过可重试错误）。
        // 不抛 last：首轮里若混有 404/403，last 已被覆盖，抛它会丢掉限流信号（QA BUG-006）。
        throw retryableErr;
    }

    /** 分段并发下载的最小体积：小文件并发反而更慢。 */
    private static final long PARALLEL_MIN_BYTES = 6L * 1024 * 1024;
    /** 并发连接数：CDN 单连接常被限速 2~4MB/s，多路并发显著提速。 */
    private static final int PARALLEL_THREADS = 4;

    /**
     * 分段并发下载（HTTP Range）。
     *
     * 起播慢的大头是整集下载：线上实测单连接约 3MB/s，一集 40MB 要 10s+，
     * 再加 CENC 解密重封装，起播 13~19 秒。CDN 支持 Range，多连接并发能明显提速。
     *
     * 安全保证：任一段失败、或服务端不支持 Range（回 200 而非 206），立即放弃并发、
     * 删掉半成品并回到单连接逻辑，不会把「下载失败」的风险带进来。
     */
    private boolean tryParallelDownload(String url, File out) {
        long total = -1L;
        try {
            Request probe = new Request.Builder().url(url)
                    .header("User-Agent", XGorgon.USER_AGENT)
                    .header("Referer", REFERER)
                    .header("Range", "bytes=0-0")
                    .build();
            try (Response r = client.newCall(probe).execute()) {
                if (r.code() != 206) return false;
                String cr = r.header("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash > 0) total = Long.parseLong(cr.substring(slash + 1).trim());
                }
            }
        } catch (Exception e) {
            return false;
        }
        if (total < PARALLEL_MIN_BYTES) return false;

        final long len = total;
        final int threads = PARALLEL_THREADS;
        final long seg = (len + threads - 1) / threads;
        final java.util.concurrent.atomic.AtomicReference<Exception> err =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(threads);
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(out, "rw");
            raf.setLength(len);
            final java.io.RandomAccessFile file = raf;
            for (int i = 0; i < threads; i++) {
                final long start = i * seg;
                final long end = Math.min(len - 1, start + seg - 1);
                new Thread(() -> {
                    try {
                        if (err.get() != null) return;
                        Request req = new Request.Builder().url(url)
                                .header("User-Agent", XGorgon.USER_AGENT)
                                .header("Referer", REFERER)
                                .header("Range", "bytes=" + start + "-" + end)
                                .build();
                        try (Response resp = client.newCall(req).execute()) {
                            if (resp.code() != 206 || resp.body() == null) {
                                throw new Exception("分段下载 HTTP " + resp.code());
                            }
                            try (InputStream in = resp.body().byteStream()) {
                                byte[] buf = new byte[COPY_BUFFER];
                                long pos = start;
                                int n;
                                while ((n = in.read(buf)) != -1) {
                                    synchronized (file) {
                                        file.seek(pos);
                                        file.write(buf, 0, n);
                                    }
                                    pos += n;
                                }
                                if (pos != end + 1) throw new Exception("分段长度不符");
                            }
                        }
                    } catch (Exception e) {
                        err.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                }, "hg-dl-" + i).start();
            }
            latch.await();
        } catch (Exception e) {
            err.compareAndSet(null, e);
        } finally {
            try {
                if (raf != null) raf.close();
            } catch (Exception ignore) {
            }
        }
        if (err.get() != null) {
            try {
                out.delete();
            } catch (Exception ignore) {
            }
            return false;
        }
        return out.length() == len;
    }

    /** 下载单个地址到文件：64KB 分块落盘，不整集进内存。 */
    private void downloadToFile(String url, File out) throws Exception {
        // 大文件先试分段并发（支持 Range 才有意义），失败自动回退单连接
        if (tryParallelDownload(url, out)) {
            return;
        }
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
            long total = resp.body().contentLength();
            long written = 0L;
            try (InputStream in = resp.body().byteStream();
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[COPY_BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    os.write(buf, 0, n);
                    written += n;
                }
                os.flush();
            }
            // 服务端声明了长度却没读满：按失败处理，避免把半截文件当成功去解密/播放
            if (total > 0 && written < total) {
                throw new Exception("HG媒体下载不完整 " + written + "/" + total);
            }
        }
    }

    /** 429 限流与 5xx 是瞬态可重试；403/404 等永久错误不退避。其余按网络瞬断处理。 */
    private static boolean retryable(Exception e) {
        String m = e == null ? null : e.getMessage();
        if (m == null || !m.startsWith("HG媒体下载 HTTP ")) return true;
        String code = m.substring("HG媒体下载 HTTP ".length());
        return "429".equals(code) || code.startsWith("5");
    }
}
