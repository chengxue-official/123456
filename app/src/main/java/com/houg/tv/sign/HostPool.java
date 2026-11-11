package com.houg.tv.sign;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import com.houg.tv.util.AppLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HG App 接口域名池（容灾）。
 *
 * <p>字节系 fqn\u006fvel.com 接口存在多个等价域名，X-Gorgon 签名只绑定 path+query+body，
 * <b>不绑定 host</b>，因此任一域名返回的签名对全部域名通用（2026-10-11 实测确认）。
 * 当主域名不可用（DNS 失败 / 连接失败 / HTTP 非 200 / 200 空 body）时，自动降级到下一个域名。
 *
 * <p>实测结论（JVM + okhttp，弹幕接口 + 分类接口双测 code=0）：
 * <ul>
 *   <li>可用：sinf\u006fnlineb（主）、sinfonlinea、sinfonlinec、lf、lq、hl</li>
 *   <li>不可用（NXDOMAIN）：sinfonline1、sinfonline2、sinfonlinea1、sinf\u006fnlineb1、sinfonline</li>
 * </ul>
 *
 * <p><b>动态域名（对齐 HGTV 1.3.6.2 的 TNC）</b>：{@link TncHostSource} 从
 * {@code is.snssdk.com/service/settings/v3/} 拉到服务端下发的 API 主机，经
 * {@link #setDynamicHosts(List)} 合并进来并落盘；服务端换域名/加节点无需发版。
 *
 * <p><b>坏域名记忆（对齐 HGTV 的 host 黑名单）</b>：同一域名连续失败
 * [BAD_FAIL_THRESHOLD] 次就标记为坏域名并落盘，[BAD_TTL_MS] 内不再参与轮询；
 * 过期自动恢复（服务端节点会自愈）。全部域名都被标记时视为已恢复，清空标记重来。
 *
 * <p>线程安全：{@link #currentIndex} 为 AtomicInteger，失败时轮转到下一域名。
 */
public final class HostPool {

    private HostPool() {}

    /** 内置已验证可用的域名（服务端动态下发失败时的兜底）。 */
    private static final List<String> STATIC_HOSTS = Collections.unmodifiableList(Arrays.asList(
            "https://api5-normal-sinf\u006fnlineb.fqn\u006fvel.com",
            "https://api5-normal-sinfonlinea.fqn\u006fvel.com",
            "https://api5-normal-sinfonlinec.fqn\u006fvel.com",
            "https://api5-normal-lf.fqn\u006fvel.com",
            "https://api5-normal-lq.fqn\u006fvel.com",
            "https://api5-normal-hl.fqn\u006fvel.com"
    ));

    /** 当前生效的域名表 = 动态下发（若有）+ 内置兜底。volatile：整体替换，读无需加锁。 */
    private static volatile List<String> HOSTS = STATIC_HOSTS;

    /** 当前首选域名下标；失败时前移，成功时归位。 */
    private static final AtomicInteger currentIndex = new AtomicInteger(0);

    /** 连续失败多少次标记为坏域名。 */
    private static final int BAD_FAIL_THRESHOLD = 2;

    /** 坏域名标记有效期：过期后重新参与轮询。 */
    private static final long BAD_TTL_MS = 30L * 60 * 1000;

    /** 动态域名个数上限（防止 settings 里疑似主机把我们带偏）。 */
    private static final int DYNAMIC_MAX = 8;

    /** host → {连续失败次数, 最近失败时间}。 */
    private static final Map<String, long[]> FAILS = new ConcurrentHashMap<>();

    private static final Object LOCK = new Object();

    private static Context appContext;

    private static final String PREFS_NAME = "h\u006fnggu\u006f_host_pool";

    /** App 启动时注入上下文（读/写坏域名记忆与动态域名），缺失时退化为纯内存。 */
    public static void attach(Context context) {
        if (context == null) return;
        appContext = context.getApplicationContext();
        SharedPreferences p = prefs();
        if (p == null) return;
        synchronized (LOCK) {
            FAILS.clear();
            for (String h : HOSTS) {
                int n = p.getInt(key(h, "n"), 0);
                long t = p.getLong(key(h, "t"), 0L);
                if (n > 0 && t > 0L) FAILS.put(h, new long[]{n, t});
            }
            String raw = p.getString("dynamic-hosts", "");
            if (!raw.isEmpty()) {
                try {
                    JSONArray arr = new JSONArray(raw);
                    List<String> extra = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String h = arr.optString(i, "");
                        if (validHost(h)) extra.add(h);
                    }
                    if (!extra.isEmpty()) rebuild(extra);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static List<String> hosts() {
        return HOSTS;
    }

    public static int size() {
        return HOSTS.size();
    }

    /** 当前首选域名（带 https:// 前缀）。 */
    public static String current() {
        List<String> h = HOSTS;
        return h.get(Math.abs(currentIndex.get()) % h.size());
    }

    /** 当前首选域名下标。 */
    public static int currentIndex() {
        List<String> h = HOSTS;
        return Math.abs(currentIndex.get()) % h.size();
    }

    /**
     * 按池顺序取第 i 个可用域名作为尝试起点（越界自动回绕）。
     *
     * <p>会跳过「坏域名记忆」里仍在有效期内的域名；全部被标记时清空记忆重来，
     * 避免服务端整体恢复后我们把所有候选都跳过。
     */
    public static String at(int i) {
        List<String> hosts = HOSTS;
        int n = hosts.size();
        int start = ((i % n) + n) % n;
        for (int k = 0; k < n; k++) {
            String h = hosts.get((start + k) % n);
            if (!isBad(h)) return h;
        }
        synchronized (LOCK) {
            FAILS.clear();
            persistAll();
        }
        return hosts.get(start);
    }

    /**
     * 用服务端 TNC 下发的域名刷新池子（静态域名始终保留在末尾兜底）。
     *
     * <p>只接受 {@code https://api{5,3}-normal-*.fqnovel.com} / {@code api5-sinfnormal*}
     * 形态的主机；每次刷新整体替换动态部分，并落盘供冷启动复用。
     */
    public static void setDynamicHosts(List<String> dynamic) {
        List<String> valid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (dynamic != null) {
            for (String h : dynamic) {
                if (h == null) continue;
                String v = h.trim();
                if (!v.startsWith("https://")) v = "https://" + v;
                v = v.replaceAll("/+$", "");
                if (!validHost(v) || !seen.add(v)) continue;
                valid.add(v);
                if (valid.size() >= DYNAMIC_MAX) break;
            }
        }
        synchronized (LOCK) {
            rebuild(valid);
            SharedPreferences p = prefs();
            if (p != null) {
                p.edit().putString("dynamic-hosts", new JSONArray(valid).toString()).apply();
            }
        }
        AppLog.INSTANCE.d("HostPool", "域名池已更新：动态 " + valid.size()
                + " 个 + 内置 " + STATIC_HOSTS.size() + " 个，共 " + HOSTS.size() + " 个");
    }

    /** 某域名请求成功：清掉失败记忆，并把它提升为首选，后续请求优先复用它。 */
    public static void reportSuccess(String host) {
        int idx = HOSTS.indexOf(host);
        if (idx >= 0) currentIndex.set(idx);
        if (FAILS.remove(host) != null) persist(host, 0, 0L);
    }

    /**
     * 某域名请求失败：累计失败次数（达到阈值即标记坏域名）并推进游标。
     *
     * <p><b>必须无条件推进</b>（旧实现只在「失败域名恰好是当前首选」时才推）：
     * 一次请求会依次尝试多个候选域名，第 2、3 个候选失败时游标纹丝不动，
     * 下一次请求又从同一个起点开始，于是反复撞在同一批坏域名上——用户看到
     * 「同一个视频点几次都提示接口无数据，过一会儿或换一集又能播」。
     *
     * <p>用 CAS 循环而非直接 set：失败是并发上报的，避免覆盖掉另一个线程刚
     * 上报的成功结果（成功总是把游标钉在可用域名上，理应优先保留）。
     */
    public static void reportFailure(String host) {
        List<String> hosts = HOSTS;
        int idx = hosts.indexOf(host);
        if (idx < 0) return;
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            long[] v = FAILS.get(host);
            long count = v == null ? 1L : v[0] + 1L;
            FAILS.put(host, new long[]{count, now});
            persist(host, count, now);
        }
        int next = (idx + 1) % hosts.size();
        while (true) {
            int cur = currentIndex.get();
            if (cur == next) return;
            if (currentIndex.compareAndSet(cur, next)) return;
        }
    }

    /** 当前有效坏域名个数（日志用）。 */
    public static int badHostCount() {
        int n = 0;
        for (String h : HOSTS) if (isBad(h)) n++;
        return n;
    }

    /** 当前首选域名的简称（仅主机名，用于日志）。 */
    public static String currentHostOnly() {
        String c = current();
        return c.startsWith("https://") ? c.substring("https://".length()) : c;
    }

    /** 校验服务端下发的主机形态，防止被脏配置带去奇怪的域名。 */
    private static boolean validHost(String h) {
        if (h == null || !h.startsWith("https://")) return false;
        String host = h.substring("https://".length());
        if (!host.endsWith(".fqnovel.com")) return false;
        return host.startsWith("api5-normal-") || host.startsWith("api3-normal-")
                || host.startsWith("api5-sinfnormal");
    }

    /** 重建生效域名表：动态在前（服务端指定优先），静态在后兜底。 */
    private static void rebuild(List<String> dynamic) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (dynamic != null) merged.addAll(dynamic);
        merged.addAll(STATIC_HOSTS);
        HOSTS = Collections.unmodifiableList(new ArrayList<>(merged));
    }

    private static boolean isBad(String host) {
        long[] v = FAILS.get(host);
        if (v == null) return false;
        if (System.currentTimeMillis() - v[1] > BAD_TTL_MS) {
            synchronized (LOCK) {
                FAILS.remove(host);
                persist(host, 0, 0L);
            }
            return false;
        }
        return v[0] >= BAD_FAIL_THRESHOLD;
    }

    private static void persistAll() {
        SharedPreferences p = prefs();
        if (p == null) return;
        SharedPreferences.Editor e = p.edit();
        for (String h : HOSTS) {
            long[] v = FAILS.get(h);
            e.putInt(key(h, "n"), v == null ? 0 : (int) v[0]);
            e.putLong(key(h, "t"), v == null ? 0L : v[1]);
        }
        e.apply();
    }

    private static void persist(String host, long count, long at) {
        SharedPreferences p = prefs();
        if (p == null) return;
        p.edit()
                .putInt(key(host, "n"), (int) count)
                .putLong(key(host, "t"), at)
                .apply();
    }

    private static SharedPreferences prefs() {
        Context ctx = appContext;
        if (ctx == null) return null;
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 用主机名的 hashCode 做 key，避免 URL 里的特殊字符进 prefs key。 */
    private static String key(String host, String suffix) {
        return "h" + Math.abs(host.hashCode()) + "-" + suffix;
    }
}
