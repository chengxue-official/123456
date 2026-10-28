package com.houg.tv.net

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object HttpClientProvider {
    @Volatile
    private var client: OkHttpClient? = null

    /**
     * IPv4 优先的 DNS 解析。
     *
     * 背景：电视盒子所在家庭网络普遍「IPv6 半残」——路由器通告了 v6 地址、
     * DNS 也返回 AAAA，但实际没有可用路由。OkHttp 3.12 没有 Happy Eyeballs
     * （不会并发回退），拿到地址列表后**按顺序逐个试**，AAAA 排前面时第一条
     * 连接就会干等到 connectTimeout 才换下一条，表现为「偶发卡死/超时」。
     * 实测（8+8 次）hongguo.kdns.fr 的 v4/v6 成功率相当、都存在边缘抖动，
     * 故 v4 优先在两类环境下都不吃亏；v4 记录缺失时仍返回全部结果，保底可用。
     */
    private val Ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = Dns.SYSTEM.lookup(hostname)
            if (all.size <= 1) return all
            val v4 = ArrayList<InetAddress>(all.size)
            for (a in all) if (a is Inet4Address) v4.add(a)
            return if (v4.isNotEmpty()) v4 else all
        }
    }

    fun get(): OkHttpClient {
        val existing = client
        if (existing != null) return existing
        synchronized(this) {
            val again = client
            if (again != null) return again
            val built = OkHttpClient.Builder()
                .dns(Ipv4FirstDns)
                // 连接超时 8s：连 Cloudflare/红果 CDN 超过这个时长基本就是不通了。
                // 配合更新检查的 3 次重试，最坏耗时约 3×(8+0.4)≈25s，比原来
                // 单次 15s 更"敢重试"——实测主域名单次成功率仅约 6/10，
                // 重试 3 次可把成功率推到 95% 以上。
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            client = built
            return built
        }
    }
}
