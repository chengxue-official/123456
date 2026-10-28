package com.houg.tv.sign;

import java.util.HashMap;
import java.util.Map;

import okhttp3.HttpUrl;

/**
 * HG App 接口请求装配：固定 query 参数 + device_id/iid + _rticket + X-Argus 签名族。
 * 身份只认 {@link DeviceRegister} 注册下发的这一对（持久化；未注册时进程内随机兜底，
 * 注册成功后自动覆盖）。Cookie 也来自注册下发的 Set-Cookie。
 * 取流接口（video_model/v1）另有 {@link PlaybackSigner} 专用签名。
 */
public final class SignedRequest {

    /** 主域名；实际使用时优先走 {@link HostPool}（多域名容灾）。 */
    public static final String BASE_URL = "https://api5-normal-sinf\u006fnlineb.fqn\u006fvel.com";

    public final HttpUrl url;
    public final Map<String, String> headers;
    public final byte[] body;
    /** 本次请求使用的域名（带 https:// 前缀），供调用方做成功/失败上报。 */
    public final String baseUrl;

    public SignedRequest(String path, byte[] body) {
        this(path, body, false, null);
    }

    /**
     * @param comment 评论（弹幕）接口走专用签名：X-SS-STUB 置空 + X-Argus/X-Ladon +
     *                Comment-Source/Server-Channel 头（对齐 Go signHgCommentRequest）。
     */
    public SignedRequest(String path, byte[] body, boolean comment) {
        this(path, body, comment, null);
    }

    /**
     * @param baseUrl 指定接口域名（如 https://api5-normal-lf.fqn\u006fvel.com）；传 null 用 HostPool 当前首选。
     */
    public SignedRequest(String path, byte[] body, boolean comment, String baseUrl) {
        this.body = body == null ? new byte[0] : body;
        String base = (baseUrl == null || baseUrl.isEmpty()) ? HostPool.current() : baseUrl;
        this.baseUrl = base;
        long now = System.currentTimeMillis();

        HttpUrl.Builder b = HttpUrl.parse(base + path).newBuilder();
        b.addQueryParameter("aid", "8662");
        b.addQueryParameter("app_name", "novelread");
        b.addQueryParameter("version_code", "73932");
        b.addQueryParameter("version_name", "7.3.9.32");
        b.addQueryParameter("manifest_version_code", "73932");
        b.addQueryParameter("update_version_code", "73932");
        b.addQueryParameter("channel", "update_64");
        b.addQueryParameter("device_platform", "android");
        b.addQueryParameter("os", "android");
        b.addQueryParameter("ssmix", "a");
        b.addQueryParameter("device_type", "25053RT47C");
        b.addQueryParameter("device_brand", "Redmi");
        b.addQueryParameter("language", "zh");
        b.addQueryParameter("os_api", "36");
        b.addQueryParameter("os_version", "16");
        b.addQueryParameter("resolution", "1280*2772");
        b.addQueryParameter("dpi", "520");
        b.addQueryParameter("ac", "wifi");
        b.addQueryParameter("device_id", deviceId());
        b.addQueryParameter("iid", iid());
        b.addQueryParameter("_rticket", String.valueOf(now));

        HttpUrl built = b.build();
        String rawQuery = built.encodedQuery();

        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", XGorgon.USER_AGENT);
        h.put("Accept", "application/json");
        h.put("X-XS-From-Web", "0");
        h.put("Sdk-Version", "2");
        h.put("Content-Type", "application/json; charset=utf-8");
        // 注册下发的 Cookie（store-region / install_id 等），后续业务请求必须带上
        String cookie = DeviceRegister.cookieHeader();
        if (!cookie.isEmpty()) {
            h.put("Cookie", cookie);
        }

        if (comment) {
            HgCommentSign.Result sign = HgCommentSign.sign(rawQuery, now);
            h.put("Comment-Source", "601");
            h.put("Server-Channel", "1000");
            h.put("X-Khronos", sign.xKhronos);
            h.put("X-SS-STUB", "");
            h.put("X-Gorgon", sign.xGorgon);
            h.put("X-SS-Req-Ticket", sign.xReqTicket);
            h.put("X-Argus", sign.xArgus);
            h.put("X-Ladon", sign.xLadon);
        } else {
            // 内容接口（栏目/详情）实测门槛是 X-Argus：纯 Gorgon 一律 200 空 body，
            // 混用 Gorgon+Argus 也是空 body，只有「纯 Argus 族」或「纯评论签名」有数据。
            // 这里与评论接口同源生成 Argus/Ladon，但不带 Comment-Source/Server-Channel、不带 Gorgon/STUB。
            HgCommentSign.Result sign = HgCommentSign.sign(rawQuery, now);
            h.put("X-Khronos", sign.xKhronos);
            h.put("X-SS-Req-Ticket", sign.xReqTicket);
            h.put("X-Argus", sign.xArgus);
            h.put("X-Ladon", sign.xLadon);
        }

        this.url = built;
        this.headers = h;
    }

    public static String deviceId() { return DeviceRegister.deviceId(); }
    public static String iid() { return DeviceRegister.installId(); }
}
