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
import java.util.UUID
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
 * 安全：随机端口、监听局域网（手机扫码访问必需，无法只绑本机）；
 * 草稿只存内存不落盘。
 *
 * 会话生命期由 [LanInputSession] 管理：本类**不再**随二维码弹窗/单次提交而停止，
 * 而是靠 [idleTimeoutMs] 空闲过期自动关闭（见 [isExpired]）。
 *
 * 续传能力（均由本类提供协议面）：
 *  - `GET /state?sid=` 取回手机端未提交的组合串、光标与剩余时长；
 *  - `POST /draft?sid=` 增量保存组合串（页面刷新/关掉重开后继续写）；
 *  - `GET /` 页面自带 sid，标题区不再需要输网址，刷新即认回同一会话；
 *  - `/submit` 在电视端已离开搜索页时明确回 503，而不是静默丢弃。
 *
 * 关键点（历史故障）：
 * 1. 手机浏览器打开页面时通常会先建 1~3 条「预连接」socket 却迟迟不发请求，
 *    若串行处理且每条阻塞数秒，真实请求就会被排队到超时 → 手机端一直转圈/黑屏。
 *    因此 accept 后立刻丢给线程池处理，每个连接单独短超时，空连接直接丢弃。
 * 2. 提交同时支持 JS `fetch` 的 GET 与原生表单 POST（JS 被拦时也能用）。
 * 3. 本机地址按网卡名打分优选，避免发到手机连不上的 p2p0/tun0 等虚拟网卡。
 */
class LanInputServer(
    /** 收到关键词时的回调（已 trim，非空）；进程级会话下由 LanInputSession 注入 */
    var onKeyword: ((String) -> Unit)? = null,
) {

    /** 是否有人能接关键词（电视端搜索页是否绑定了接收方）；无接收方时 /submit 回 503 */
    var receiverAvailable: (() -> Boolean)? = null

    /** 空闲过期时长（毫秒）；0 = 永不过期 */
    @Volatile var idleTimeoutMs: Long = 0L

    /** 会话 id：随二维码 URL 下发，手机端刷新/重开页面后凭它认回同一会话 */
    val sessionId: String = UUID.randomUUID().toString().replace("-", "").take(8)

    /** 手机端未提交的组合串（不 trim，保留原样） */
    @Volatile var draft: String = ""
        private set

    /** 手机端未提交组合串的光标位置 */
    @Volatile var draftCursor: Int = 0
        private set

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null
    private var pool: ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())

    /** 最近一次「有效会话活动」时间戳 */
    @Volatile private var lastActiveAt = System.currentTimeMillis()

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
            // soTimeout 让 accept 周期醒来，stop()/空闲过期都能立刻生效
            val socket = ServerSocket(0).apply { soTimeout = 500 }
            serverSocket = socket
            lastActiveAt = System.currentTimeMillis()
            pool = Executors.newCachedThreadPool { r ->
                Thread(r, "lan-input-worker").apply { isDaemon = true }
            }
            thread = Thread({ serveLoop(socket) }, "lan-input").apply {
                isDaemon = true
                start()
            }
            AppLog.d("LanInputServer", "局域网输入已启动，端口=" + socket.localPort + " sid=" + sessionId)
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

    /** 会话是否已空闲过期（now 可注入，便于验证）。 */
    fun isExpired(now: Long): Boolean = idleTimeoutMs > 0 && now - lastActiveAt > idleTimeoutMs

    /**
     * 刷新会话活动时间。
     *
     * 仅当确有接收方在等输入时才算活动：否则电视端离开搜索页后，手机页面每 15s
     * 的探活轮询会把会话无限续命，留下一个永不关闭的局域网服务。
     */
    private fun touch() {
        if (receiverAvailable?.invoke() != false) lastActiveAt = System.currentTimeMillis()
    }

    private fun serveLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketTimeoutException) {
                // 只是超时：顺带检查空闲过期（会话保持的最后一道关）
                if (isExpired(System.currentTimeMillis())) {
                    AppLog.d("LanInputServer", "会话空闲超时，自动关闭")
                    stop()
                    break
                }
                continue
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

            touch()

            // 手机端凭 sid 认回同一会话：取回未提交的组合串、光标与剩余时长
            if (path == "/state") {
                if (sidOf(query) != sessionId) {
                    reply(out, 403, "text/plain; charset=utf-8", "forbidden")
                    return
                }
                val remain = if (idleTimeoutMs <= 0) -1L
                else (idleTimeoutMs - (System.currentTimeMillis() - lastActiveAt)).coerceAtLeast(0L)
                reply(
                    out, 200, "application/json; charset=utf-8",
                    "{\"sid\":\"" + sessionId + "\",\"draft\":\"" + jsonEscape(draft) +
                        "\",\"cursor\":" + draftCursor + ",\"ttlMs\":" + remain + "}"
                )
                return
            }

            // 未提交组合串的增量同步（页面防抖 400ms 一次 + pagehide 时 sendBeacon 兜底）
            if (path == "/draft") {
                if (sidOf(query) != sessionId) {
                    reply(out, 403, "text/plain; charset=utf-8", "forbidden")
                    return
                }
                val body = if (method == "POST") readBody(input, headers["content-length"]) else query
                val text = paramRaw(body, "kw")
                draft = text
                draftCursor = (paramRaw(body, "cursor").toIntOrNull() ?: text.length)
                    .coerceIn(0, text.length)
                reply(out, 200, "text/plain; charset=utf-8", "ok")
                return
            }

            if (path == "/submit") {
                // sid 允许为空：兼容无 JS 的原生表单/旧链接；带了就必须对得上
                val given = sidOf(query)
                if (given.isNotEmpty() && given != sessionId) {
                    reply(out, 403, "text/plain; charset=utf-8", "forbidden")
                    return
                }
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
                if (onKeyword == null || receiverAvailable?.invoke() == false) {
                    // 电视端已离开搜索页：明确告知，用户知道回电视上重进搜索页即可
                    if (wantsHtml(headers)) {
                        reply(out, 503, "text/html; charset=utf-8", errorPage("电视已离开搜索页，请先在电视上回到搜索页"))
                    } else {
                        reply(out, 503, "text/plain; charset=utf-8", "tv-away")
                    }
                    return
                }
                main.post { onKeyword?.invoke(kw) }
                draft = "" // 已提交，清空未提交组合串
                draftCursor = 0
                if (wantsHtml(headers)) {
                    reply(out, 200, "text/html; charset=utf-8", doneHtml())
                } else {
                    reply(out, 200, "text/plain; charset=utf-8", "ok")
                }
                return
            }

            if (method != "GET" && method != "HEAD") {
                reply(out, 405, "text/plain; charset=utf-8", "method not allowed")
                return
            }
            reply(out, 200, "text/html; charset=utf-8", pageHtml())
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

    /** 读取整个请求体（/draft 需要同时取 kw 与 cursor，不能读两次流）。 */
    private fun readBody(input: InputStream, lengthHeader: String?): String {
        val length = lengthHeader?.toIntOrNull() ?: return ""
        if (length !in 1..65536) return ""
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val n = input.read(body, offset, body.size - offset)
            if (n < 0) break
            offset += n
        }
        return String(body, 0, offset, Charsets.UTF_8)
    }

    private fun readFormField(input: InputStream, lengthHeader: String?, key: String): String =
        param(readBody(input, lengthHeader), key)

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

    /** 取参数原值（不 trim）——草稿是未提交的组合串，前后空格必须原样保留。 */
    private fun paramRaw(query: String, key: String): String {
        if (query.isEmpty()) return ""
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == key) {
                return try {
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                } catch (e: Throwable) {
                    ""
                }
            }
        }
        return ""
    }

    private fun param(query: String, key: String): String = paramRaw(query, key).trim()

    private fun sidOf(query: String): String = paramRaw(query, "sid")

    /** 最小 JSON 字符串转义（草稿可能含引号/换行/控制字符）。 */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when {
                c == '\\' -> sb.append("\\\\")
                c == '"' -> sb.append("\\\"")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
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
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun errorPage(message: String): String = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>红果TV 远程输入</title>
<style>body{margin:0;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#141414;color:#eee;padding:22px}h1{font-size:19px}p{color:#9a9a9a;font-size:14px}a{color:#e53935}</style>
</head><body><h1>$message</h1><p><a href="/">返回</a></p></body></html>"""

    /**
     * 手机端页面：输入框 + 提交按钮。
     *
     * 续传逻辑（全在客户端 JS 里，服务端只提供 `/state` 与 `/draft`）：
     *  - 打开/刷新即按 sid 取回草稿与光标并回填；
     *  - 输入防抖 400ms 增量同步（组合输入中不打断，compositionend 再同步）；
     *  - pagehide 用 sendBeacon 兜底，关页面也不丢；
     *  - 每 15s 探活，会话过期/断开时立刻提示而不是等提交才失败。
     */
    private fun pageHtml(): String = """<!DOCTYPE html>
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
<p>在下面输入要搜索的剧名，点「发送到电视」。电视上会立即开始搜索；关掉本页 10 分钟内重开仍可续写。</p>
<form method="post" action="/submit?sid=$sessionId" onsubmit="return send(event)">
<input id="kw" name="kw" type="search" placeholder="输入剧名…" autocomplete="off" autofocus>
<button type="submit">发送到电视</button>
</form>
<div id="tip"></div>
<script>
 var SID='$sessionId', el=document.getElementById('kw'), tip=document.getElementById('tip');
 var timer=null, composing=false;
 function setTip(t){tip.textContent=t||'';}
 function restore(){
   fetch('/state?sid='+SID,{cache:'no-store'}).then(function(r){
     if(!r.ok)throw new Error('gone');
     return r.json();
   }).then(function(d){
     if(d.draft && !el.value){
       el.value=d.draft;
       try{el.setSelectionRange(d.cursor,d.cursor);}catch(e){}
       setTip('已恢复上次未提交的输入');
     }
     if(typeof d.ttlMs==='number'&&d.ttlMs>=0&&d.ttlMs<60000){setTip('会话即将到期，请尽快发送');}
   }).catch(function(){setTip('会话已过期，请回到电视上重新扫码');});
 }
 function sync(){
   clearTimeout(timer);
   timer=setTimeout(function(){
     fetch('/draft?sid='+SID,{method:'POST',keepalive:true,
       headers:{'Content-Type':'application/x-www-form-urlencoded'},
       body:'kw='+encodeURIComponent(el.value)+'&cursor='+el.selectionStart}).catch(function(){});
   },400);
 }
 function send(e){
   var v=(el.value||'').trim();
   if(!v){setTip('请先输入剧名');return false;}
   if(e)e.preventDefault();
   fetch('/submit?sid='+SID,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
     body:'kw='+encodeURIComponent(v)}).then(function(r){
       if(r.status===503){setTip('电视已离开搜索页，请回到电视上的搜索页后重试');return;}
       if(!r.ok)throw new Error('bad');
       setTip('已发送：'+v);
       el.value='';
       el.focus();
     }).catch(function(){
       setTip('发送失败，请确认与电视在同一 Wi-Fi');
     });
   return false;
 }
 el.addEventListener('input',function(e){
   if(e.isComposing||composing)return;
   sync();
 });
 el.addEventListener('compositionstart',function(){composing=true;});
 el.addEventListener('compositionend',function(){composing=false;sync();});
 el.addEventListener('keydown',function(e){
   if(e.key==='Enter'){e.preventDefault();send();}
 });
 window.addEventListener('pagehide',function(){
   try{
     var body='kw='+encodeURIComponent(el.value)+'&cursor='+el.selectionStart;
     if(navigator.sendBeacon){navigator.sendBeacon('/draft?sid='+SID,body);}
     else{fetch('/draft?sid='+SID,{method:'POST',keepalive:true,
       headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body});}
   }catch(e){}
 });
 setInterval(function(){
   fetch('/state?sid='+SID,{cache:'no-store'}).then(function(r){
     if(!r.ok)setTip('会话已过期，请回到电视上重新扫码');
   }).catch(function(){setTip('与电视的连接已断开，请重新扫码');});
 },15000);
 restore();
</script></body></html>"""

    /** 原生表单 POST（无 JS）提交后的落地页；「继续输入」回到同一会话。 */
    private fun doneHtml(): String = """<!DOCTYPE html>
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
