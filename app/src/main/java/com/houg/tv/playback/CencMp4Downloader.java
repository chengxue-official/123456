package com.houg.tv.playback;

import com.houg.tv.sign.XGorgon;

import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 下载HG加密 mp4（带 App UA 与固定 Referer），失败按 urls 列表依次重试。
 */
public final class CencMp4Downloader {

    private static final String REFERER = "https://novel.snssdk.com/";

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

    /** 依次尝试多个地址，全部失败抛出最后一次异常。 */
    public byte[] downloadWithRetry(List<String> urls) throws Exception {
        Exception last = null;
        for (String url : urls) {
            try {
                return download(url);
            } catch (Exception e) {
                last = e;
            }
        }
        throw last != null ? last : new Exception("HG媒体下载失败");
    }
}
