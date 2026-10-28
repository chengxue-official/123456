package com.houg.tv.sign;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 红果 App 接口域名池（容灾）。
 *
 * <p>字节系 fqnovel.com 接口存在多个等价域名，X-Gorgon 签名只绑定 path+query+body，
 * <b>不绑定 host</b>，因此任一域名返回的签名对全部域名通用（2026-10-11 实测确认）。
 * 当主域名不可用（DNS 失败 / 连接失败 / HTTP 非 200）时，自动降级到下一个域名。
 *
 * <p>实测结论（JVM + okhttp，弹幕接口 + 分类接口双测 code=0）：
 * <ul>
 *   <li>可用：sinfonlineb（主）、sinfonlinea、sinfonlinec、lf、lq、hl</li>
 *   <li>不可用（NXDOMAIN）：sinfonline1、sinfonline2、sinfonlinea1、sinfonlineb1、sinfonline</li>
 * </ul>
 *
 * <p>线程安全：{@link #currentIndex} 为 AtomicInteger，失败时轮转到下一域名。
 */
public final class HostPool {

    private HostPool() {}

    /** 已验证可用的域名，第一个为主域名（保持与原 BASE_URL 一致）。 */
    private static final List<String> HOSTS = Collections.unmodifiableList(Arrays.asList(
            "https://api5-normal-sinfonlineb.fqnovel.com",
            "https://api5-normal-sinfonlinea.fqnovel.com",
            "https://api5-normal-sinfonlinec.fqnovel.com",
            "https://api5-normal-lf.fqnovel.com",
            "https://api5-normal-lq.fqnovel.com",
            "https://api5-normal-hl.fqnovel.com"
    ));

    /** 当前首选域名下标；失败时前移，成功时归位。 */
    private static final AtomicInteger currentIndex = new AtomicInteger(0);

    public static List<String> hosts() {
        return HOSTS;
    }

    public static int size() {
        return HOSTS.size();
    }

    /** 当前首选域名（带 https:// 前缀）。 */
    public static String current() {
        return HOSTS.get(currentIndex.get() % HOSTS.size());
    }

    /** 当前首选域名下标。 */
    public static int currentIndex() {
        return currentIndex.get() % HOSTS.size();
    }

    /** 按池顺序取第 i 个域名作为尝试起点（i 从 0 递增，越界自动回绕）。 */
    public static String at(int i) {
        int n = HOSTS.size();
        return HOSTS.get(((i % n) + n) % n);
    }

    /** 某域名请求成功：把它提升为首选，后续请求优先复用它。 */
    public static void reportSuccess(String host) {
        int idx = HOSTS.indexOf(host);
        if (idx >= 0) currentIndex.set(idx);
    }

    /**
     * 某域名请求失败：把游标推到该域名的下一个。
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
        int idx = HOSTS.indexOf(host);
        if (idx < 0) return;
        int next = (idx + 1) % HOSTS.size();
        while (true) {
            int cur = currentIndex.get();
            if (cur == next) return;
            if (currentIndex.compareAndSet(cur, next)) return;
        }
    }

    /** 当前首选域名的简称（仅主机名，用于日志）。 */
    public static String currentHostOnly() {
        String c = current();
        return c.startsWith("https://") ? c.substring("https://".length()) : c;
    }
}
