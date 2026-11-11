package com.houg.tv.sign;

import android.content.Context;
import android.content.SharedPreferences;

import com.houg.tv.net.HttpClientProvider;
import com.houg.tv.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * TNC 动态域名源（对齐 HGTV 1.3.6.2 的 TNC）。
 *
 * <p>HGTV 从 {@code https://is.snssdk.com/service/settings/v3/} 拉应用设置，
 * 其中 {@code data} 里可能带：
 * <ul>
 *   <li>{@code ttnet_http_dns_addr}：主机名 → IP 列表（HTTP DNS）；</li>
 *   <li>{@code tnc_config.local_host_filter}：服务端指定的 API 主机白名单；</li>
 *   <li>{@code send_tnc_host_arrays}：TNC 自身使用的主机（.zijieapi.com）；</li>
 *   <li>{@code tnc_update_interval}：刷新间隔（秒，5 分钟~24 小时）。</li>
 * </ul>
 * 把这些主机与内置域名池合并后，服务端换域名/加节点无需发版；页面里出现的
 * {@code https://api5-normal-*.fqnovel.com} 也会被兜底收集。
 *
 * <p>拉取失败完全静默（沿用上次落盘的域名 + 内置域名），不影响播放。
 */
public final class TncHostSource {

    private static final String SETTINGS_URL = "https://is.snssdk.com/service/settings/v3/?";

    private static final String PREFS_NAME = "h\u006fnggu\u006f_tnc";

    private static final long DEFAULT_INTERVAL_MS = 60L * 60 * 1000;

    private static final long MIN_INTERVAL_MS = 5L * 60 * 1000;

    private static final long MAX_INTERVAL_MS = 24L * 60 * 60 * 1000;

    /** 从 settings 响应里兜底收集的 API 主机。 */
    private static final Pattern HOST_URL = Pattern.compile(
            "https://(api(?:5|3)-normal-[a-z0-9-]+\\.fqnovel\\.com)");

    private static final AtomicBoolean FETCHING = new AtomicBoolean(false);

    private static Context appContext;

    private TncHostSource() {}

    /** App 启动时注入上下文（读写拉取时间与间隔）。 */
    public static void attach(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    /**
     * 冷启动调用：距上次成功拉取不足「服务端下发的间隔」就直接返回，否则后台拉一次。
     * 不阻塞启动线程。
     */
    public static void refreshIfStaleAsync() {
        SharedPreferences p = prefs();
        long lastAt = p == null ? 0L : p.getLong("fetched-at", 0L);
        long interval = p == null ? DEFAULT_INTERVAL_MS : p.getLong("interval-ms", DEFAULT_INTERVAL_MS);
        if (lastAt > 0L && System.currentTimeMillis() - lastAt < interval) return;
        refreshNowAsync();
    }

    /** 立刻后台拉一次（失败静默）。 */
    public static void refreshNowAsync() {
        if (!FETCHING.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    fetchAndApply();
                } finally {
                    FETCHING.set(false);
                }
            }
        }, "hg-tnc-refresh");
        t.setDaemon(true);
        t.start();
    }

    private static void fetchAndApply() {
        try {
            OkHttpClient client = HttpClientProvider.INSTANCE.get();
            Request req = new Request.Builder()
                    .url(SETTINGS_URL + buildQuery())
                    .header("User-Agent", PlaybackSigner.APP_UA)
                    .header("Accept", "application/json")
                    .build();
            String text;
            try (Response resp = client.newCall(req).execute()) {
                if (resp.code() != 200 || resp.body() == null) {
                    AppLog.INSTANCE.d("TncHostSource", "settings 拉取失败 HTTP " + resp.code());
                    return;
                }
                text = resp.body().string();
            }
            JSONObject root = new JSONObject(text);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                AppLog.INSTANCE.d("TncHostSource", "settings 响应缺少 data");
                return;
            }
            long intervalSec = data.optLong("tnc_update_interval", 0L);
            long interval = intervalSec <= 0L ? DEFAULT_INTERVAL_MS : clamp(intervalSec * 1000L);

            LinkedHashSet<String> hosts = new LinkedHashSet<>();
            JSONObject dns = data.optJSONObject("ttnet_http_dns_addr");
            if (dns != null) {
                Iterator<String> it = dns.keys();
                while (it.hasNext()) hosts.add(it.next());
            }
            JSONObject tnc = data.optJSONObject("tnc_config");
            if (tnc != null) collectHosts(tnc.opt("local_host_filter"), hosts);
            collectHosts(data.opt("send_tnc_host_arrays"), hosts);
            // 兜底：settings 里出现的任何 API 主机（例如各类业务的默认域名）
            Matcher m = HOST_URL.matcher(data.toString());
            while (m.find() && hosts.size() < 32) hosts.add(m.group(1));

            List<String> dynamic = new ArrayList<>(hosts);
            HostPool.setDynamicHosts(dynamic);

            SharedPreferences p = prefs();
            if (p != null) {
                p.edit()
                        .putLong("fetched-at", System.currentTimeMillis())
                        .putLong("interval-ms", interval)
                        .apply();
            }
            AppLog.INSTANCE.d("TncHostSource", "settings 拉取成功：候选域名 " + dynamic.size()
                    + " 个，下次刷新间隔 " + interval / 60000 + " 分钟");
        } catch (Throwable e) {
            AppLog.INSTANCE.d("TncHostSource", "settings 拉取异常（忽略）：" + e.getMessage());
        }
    }

    private static void collectHosts(Object value, LinkedHashSet<String> out) {
        if (value == null) return;
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) out.add(s);
            }
        } else if (value instanceof String) {
            // 兼容 "a,b,c" 形态
            for (String s : ((String) value).split(",")) {
                if (!s.trim().isEmpty()) out.add(s.trim());
            }
        } else if (value instanceof JSONObject) {
            Iterator<String> it = ((JSONObject) value).keys();
            while (it.hasNext()) out.add(it.next());
        }
    }

    private static String buildQuery() {
        StringBuilder sb = new StringBuilder();
        sb.append("app=1&settings_time=0");
        sb.append("&_rticket=").append(System.currentTimeMillis());
        sb.append("&aid=8662&app_name=novelread&version_code=73932&version_name=7.3.9.32");
        sb.append("&device_platform=android&os=android&channel=update_64&language=zh");
        sb.append("&ac=wifi&ssmix=a");
        String dev = DeviceRegister.registered() ? DeviceRegister.deviceId() : "";
        String iid = DeviceRegister.registered() ? DeviceRegister.installId() : "";
        if (!dev.isEmpty()) sb.append("&device_id=").append(dev);
        if (!iid.isEmpty()) sb.append("&iid=").append(iid);
        return sb.toString();
    }

    private static long clamp(long v) {
        if (v < MIN_INTERVAL_MS) return MIN_INTERVAL_MS;
        if (v > MAX_INTERVAL_MS) return MAX_INTERVAL_MS;
        return v;
    }

    private static SharedPreferences prefs() {
        Context ctx = appContext;
        if (ctx == null) return null;
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
