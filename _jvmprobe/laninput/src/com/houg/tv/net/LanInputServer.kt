package com.houg.tv.net

import android.os.Handler
import android.os.Looper
import com.houg.tv.util.AppLog
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网跨屏输入服务（TV 端）。
 *
 * 电视/盒子上用遥控器打字极其痛苦，系统输入法在很多机型上根本唤不出来。
 * 这里在 TV 上起一个极简 HTTP 服务，手机连同一 Wi-Fi 打开提示的网址，
 * 在网页输入框里打字 → 提交后回传给 TV 直接搜索。
 *
 * 只用 `ServerSocket` 手写最小 HTTP，不引入任何依赖（老设备/minSdk 21 友好）。
 * 安全：仅监听本机、随机端口；页面与接口都不落盘、不做持久化，服务随搜索页销毁而关闭。
 *
 * 关键点（历史故障）：
 * 1. 手机浏览器打开页面时通常会先建 1~3 条「预连接」socket 却迟迟不发请求，
 *    若串行处理且每条阻塞数秒，真实请求就会被排队到超时 → 手机端一直转圈/黑屏。
 *    因此 accept 后立刻丢给线程池处理，每个连接单独短超时，空连接直接丢弃。
 * 2. 提交同时支持 JS `fetch` 的 GET 与原生表单 POST（JS 被拦时也能用）。
 * 3. 本机地址按网卡名打分优选，避免发到手机连不上的 p2p0/tun0 等虚拟网卡。
 */
class LanInputServer(
    /** 收到关键词时的回调（已 trim，非空） */
    private val onKeyword: (String) -> Unit,
) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private var pool: ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())

    /** 本机局域网 IPv4，按网卡可信度优选；失败返回 null。 */
    fun localAddress(): String? {
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        try {
            for (nic in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp || nic.isLoopback) continue
                val name = (nic.name ?: "").lowercase(Locale.ROOT)
                if (isVirtualNic(name)) continue
                for (addr in Collections.list(nic.inetAddresses)) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("169.254.")) continue // 无 DHCP 的自动地址，连不通
                    val score = nicScore(name)
                    if (score > bestScore) {
                        bestScore = score
                        best = host
                    }
                }
            }
        } catch (e: Throwable) {
            AppLog.e("LanInputServer", "取本机地址失败: " + (e.message ?: ""), e)
        }
        return best
    }

    /** 虚拟/点对点网卡，手机大概率连不上，直接排除。 */
    private fun isVirtualNic(name: String): Boolean {
        val prefixes = arrayOf("p2p", "tun", "tap", "ppp", "rmnet", "dummy", "usb", "sit", "ip6", "ifb", "virbr", "docker", "veth", "br-", "vbox", "vmnet")
        for (p in prefixes) if (name.startsWith(p)) return true
        return false
    }

    /** Wi-Fi > 有线 > 其它（蜂窝/未知）。 */
    private fun nicScore(name: String): Int = when {
        name.startsWith("wlan") || name.startsWith("wl") || name.contains("wifi") -> 100
        name.startsWith("eth") || name.startsWith("en") -> 80
        name.startsWith("ap") -> 60
        else -> 20
    }

    /** 启动服务，返回监听端口；失败返回 0。 */
    fun start(): Int {
        if (!running.compareAndSet(false, true)) return port
        return try {
            // soTimeout 让 accept 周期醒来，stop() 能立刻退出
            val socket = ServerSocket(0).apply { soTimeout = 500 }
            serverSocket = socket
            pool = Executors.newCachedThreadPool { r ->
                Thread(r, "lan-input-worker").apply { isDaemon = true }
            }
            thread = Thread({ serveLoop(socket) }, "lan-input").apply {
                isDaemon = true
                start()
            }
            AppLog.d("LanInputServer", "局域网输入已启动，端口=" + socket.localPort)
            socket.localPort
        } catch (e: Throwable) {
            running.set(false)
            AppLog.e("LanInputServer", "启动失败: " + (e.message ?: ""), e)
            0
        }
    }

    val port: Int get() = serverSocket?.localPort ?: 0

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        thread = null
        try { pool?.shutdownNow() } catch (_: Throwable) {}
        pool = null
        AppLog.d("LanInputServer", "局域网输入已停止")
    }

    private fun serveLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketTimeoutException) {
                continue // 只是超时，继续等连接
            } catch (e: Throwable) {
                break // close() 时 accept 抛异常，正常退出
            }
            val exec = pool
            if (exec == null || !running.get()) {
                closeQuietly(client)
                break
            }
            try {
                exec.execute { handleClient(client) }
            } catch (e: Throwable) {
                closeQuietly(client)
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 2000
            client.tcpNoDelay = true
            val input = client.getInputStream().buffered()

            val requestLine = readLine(input, 1024) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.ROOT)
            val rawPath = parts[1]

            val headers = HashMap<String, String>()
            var headerBytes = 0
            while (true) {
                val line = readLine(input, 4096) ?: break
                headerBytes += line.length + 2
                if (headerBytes > 8192) return
                if (line.isEmpty()) break
                val at = line.indexOf(':')
                if (at <= 0) continue
                headers[line.substring(0, at).trim().lowercase(Locale.ROOT)] =
                    line.substring(at + 1).trim()
            }

            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")
            val out = BufferedOutputStream(client.getOutputStream())

            if (path == "/favicon.ico") {
                reply(out, 404, "text/plain; charset=utf-8", "not found")
                return
            }

            if (path == "/submit") {
                val kw = when (method) {
                    "POST" -> readFormField(input, headers["content-length"], "kw")
                    "GET" -> param(query, "kw")
                    else -> ""
                }
                if (kw.isEmpty()) {
                    if (wantsHtml(headers)) {
                        reply(out, 400, "text/html; charset=utf-8", errorPage("请先输入剧名"))
                    } else {
                        reply(out, 400, "text/plain; charset=utf-8", "empty")
                    }
                    return
                }
                main.post { onKeyword(kw) }
                if (wantsHtml(headers)) {
                    reply(out, 200, "text/html; charset=utf-8", DONE_HTML)
                } else {
                    reply(out, 200, "text/plain; charset=utf-8", "ok")
                }
                return
            }

            if (method != "GET" && method != "HEAD") {
                reply(out, 405, "text/plain; charset=utf-8", "method not allowed")
                return
            }
            reply(out, 200, "text/html; charset=utf-8", PAGE_HTML)
        } catch (e: SocketTimeoutException) {
            // 浏览器预连接/半开连接：不发请求就挂着，直接丢弃，绝不占用后续请求
        } catch (e: Throwable) {
            AppLog.d("LanInputServer", "请求处理失败: " + (e.message ?: ""))
        } finally {
            closeQuietly(client)
        }
    }

    /** 原生表单 POST 会带 `Accept: text/html`；fetch 默认不带，借此区分要回页面还是纯文本。 */
    private fun wantsHtml(headers: Map<String, String>): Boolean =
        headers["accept"]?.contains("text/html", ignoreCase = true) == true

    private fun readFormField(input: InputStream, lengthHeader: String?, key: String): String {
        val length = lengthHeader?.toIntOrNull() ?: return ""
        if (length !in 1..8192) return ""
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val n = input.read(body, offset, body.size - offset)
            if (n < 0) break
            offset += n
        }
        return param(String(body, 0, offset, Charsets.UTF_8), key)
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val buf = ByteArrayOutputStream()
        while (buf.size() < max) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == 10) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
        }
        return buf.toString("UTF-8")
    }

    private fun param(query: String, key: String): String {
        if (query.isEmpty()) return ""
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == key) {
                return try {
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8").trim()
                } catch (e: Throwable) {
                    ""
                }
            }
        }
        return ""
    }

    private fun closeQuietly(socket: Socket) {
        try { socket.close() } catch (_: Throwable) {}
    }

    private fun reply(out: OutputStream, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = StringBuilder()
            .append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
            .append("Content-Type: ").append(contentType).append("\r\n")
            .append("Content-Length: ").append(bytes.size).append("\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("Referrer-Policy: no-referrer\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append("Connection: close\r\n")
            .append("\r\n")
            .toString()
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        else -> "OK"
    }

    private fun errorPage(message: String): String = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>红果TV 远程输入</title>
<style>body{margin:0;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#141414;color:#eee;padding:22px}h1{font-size:19px}p{color:#9a9a9a;font-size:14px}a{color:#e53935}</style>
</head><body><h1>$message</h1><p><a href="/">返回重新输入</a></p></body></html>"""

    private companion object {
        /** 手机端页面：一个输入框 + 提交按钮，提交后回显并清空，可反复输入。 */
        const val PAGE_HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>红果TV 远程输入</title>
<style>
 body{margin:0;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
      background:#141414;color:#eee;padding:22px;box-sizing:border-box}
 h1{font-size:19px;margin:0 0 6px}
 p{color:#9a9a9a;font-size:13px;margin:0 0 20px;line-height:1.5}
 input{width:100%;box-sizing:border-box;font-size:19px;padding:15px 14px;border-radius:10px;
       border:1px solid #3a3a3a;background:#222;color:#fff;outline:none}
 button{width:100%;box-sizing:border-box;margin-top:14px;font-size:17px;padding:15px;
        border:0;border-radius:10px;background:#e53935;color:#fff;font-weight:600}
 button:active{background:#c62828}
 #tip{margin-top:16px;font-size:14px;color:#5ac85a;min-height:20px;text-align:center}
</style></head><body>
<h1>红果TV 远程输入</h1>
<p>在下面输入要搜索的剧名，点「发送到电视」。电视上会立即开始搜索。</p>
<form method="post" action="/submit" onsubmit="return send(event)">
<input id="kw" name="kw" type="search" placeholder="输入剧名…" autocomplete="off" autofocus>
<button type="submit">发送到电视</button>
</form>
<div id="tip"></div>
<script>
 function send(e){
   var el=document.getElementById('kw');
   var v=(el.value||'').trim();
   var tip=document.getElementById('tip');
   if(!v){tip.textContent='请先输入剧名';return false;}
   if(e)e.preventDefault();
   fetch('/submit',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
     body:'kw='+encodeURIComponent(v)}).then(function(r){
       if(!r.ok)throw new Error('bad');
       tip.textContent='已发送：'+v;
       el.value='';
       el.focus();
     }).catch(function(){
       tip.textContent='发送失败，请确认与电视在同一 Wi-Fi';
     });
   return false;
 }
 document.getElementById('kw').addEventListener('keydown',function(e){
   if(e.key==='Enter'){e.preventDefault();send();}
 });
</script></body></html>"""

        /** 原生表单 POST（无 JS）提交后的落地页。 */
        const val DONE_HTML = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>红果TV 远程输入</title>
<style>body{margin:0;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;
 background:#141414;color:#eee;padding:22px;box-sizing:border-box}
 h1{font-size:19px;margin:0 0 6px}p{color:#9a9a9a;font-size:14px;line-height:1.6}
 a{color:#e53935;font-size:16px}</style></head><body>
<h1>已发送到电视</h1><p>电视上会立即开始搜索，请回到电视继续。</p>
<p><a href="/">继续输入</a></p>
</body></html>"""
    }
}
