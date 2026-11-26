package com.houg.tv.net

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 鎺ュ彛閰嶇疆灞傦紙娴嬭瘯鐗堬紝鐙珛浜庝富閾撅級銆? *
 * 涓夌骇鍔犺浇锛歛ssets 鍐呭缓 -> filesDir 缂撳瓨 -> 鍐峰惎鍔ㄥ紓姝ユ媺杩滅锛堝婧愬苟鍙戯紝鍏堝埌鍏堝緱锛? * 鐩殑锛氭妸"鎺ュ彛璺緞/鍩熷悕缁?浠庝唬鐮侀噷鎸埌 endpoints.json锛屾帴鍙ｇ偢浜嗘敼閰嶇疆鍗冲彲锛屼笉閲嶈 APK銆? *
 * 涓庝富绾跨幇鏈夎兘鍔涚殑鍏崇郴锛? *  - HostPool / TncHostSource锛氬煙鍚嶅彲鐢ㄦ€э紙宸叉湁锛? *  - 鏈被锛氭帴鍙ｅ绾︼紙璺緞 + 鏂规硶 + 褰掑睘鍩熷悕缁勶級鈥斺€?鐩墠缂虹殑涓€灞? */
object EndpointConfig {

    private const val TAG = "EpConfig"
    private const val ASSET = "endpoints.json"
    private const val CACHE_NAME = "endpoints_remote.json"

    private var loaded = false
    private var version: Int = 0
    private var minClientVersion: Int = 0
    private var hostsByGroup: MutableMap<String, List<String>> = mutableMapOf()
    private var endpoints: MutableMap<String, Spec> = mutableMapOf()
    private var webPaths: MutableMap<String, String> = mutableMapOf()

    /** 褰撳墠閰嶇疆鏉ユ簮锛坅ssets / cache / remote锛夛紝鐢ㄤ簬鏃ュ織涓庤嚜妫€鏄剧ず */
    @Volatile
    private var sourceTag: String = "assets"

    /**
     * 杩滅閰嶇疆鍦板潃锛堝苟鍙戞媺锛屽厛鍙栧埌鍚堟硶鐗堟湰鐨勮儨鍑猴級銆?     * 涓绘簮锛欵dgeOne Pages锛堜笌 update.json 鍚屽煙锛屽凡鍦ㄧ敤锛?     * 澶囨簮锛歫sDelivr锛圙itHub CDN锛夈€丟itee raw銆丒dgeOne 涓嶅甫 www
     * 浠ュ悗鍔?鎹㈡簮鍙敼杩欎釜鍒楄〃锛堥渶闅忕増鍙戝竷锛夈€?     */
    private val remoteUrls = listOf(
        "https://www.hongguo.kdns.fr/endpoints.json",
        "https://cdn.jsdelivr.net/gh/chengxue-official/hongguoA@main/public/endpoints.json",
        "https://gitee.com/chengxue/hongguoA/raw/main/public/endpoints.json",
        "https://hongguo.kdns.fr/endpoints.json"
    )

    data class Spec(val key: String, val hostGroup: String, val path: String, val method: String)

    data class ProbeResult(
        val key: String,
        val url: String,
        val httpCode: Int,
        val costMs: Long,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null && httpCode in 200..399
        override fun toString(): String =
            "$key -> $httpCode (${costMs}ms)${if (error != null) " ERR=$error" else ""}  $url"
    }

    // ---------------- 鍔犺浇 ----------------

    @Synchronized
    fun load(ctx: Context, force: Boolean = false) {
        if (loaded && !force) return
        val app = ctx.applicationContext
        try {
            val base = app.assets.open(ASSET).use { input: InputStream -> input.bufferedReader().readText() }
            parse(base)
            sourceTag = "assets"
        } catch (t: Throwable) {
            Log.e(TAG, "assets load failed: ${t.message}")
        }
        val cache = File(app.filesDir, CACHE_NAME)
        if (cache.exists()) {
            try {
                val text = cache.readText()
                val v = JSONObject(text).optInt("version", 0)
                if (v > version) {
                    parse(text)
                    sourceTag = "cache"
                    Log.i(TAG, "cache config v$v applied")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cache config invalid, ignored: ${t.message}")
            }
        }
        loaded = true
        Log.i(TAG, "config ready v$version from $sourceTag: ${endpoints.size} endpoints, ${hostsByGroup.size} groups, min_client=$minClientVersion")
    }

    fun parse(text: String) {
        val root = JSONObject(text)
        version = root.optInt("version", 0)
        minClientVersion = root.optInt("min_client", 0)
        hostsByGroup = mutableMapOf()
        root.optJSONObject("hosts")?.let { h ->
            for (g in h.keys()) {
                val arr = h.optJSONArray(g) ?: continue
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                hostsByGroup[g] = list
            }
        }
        endpoints = mutableMapOf()
        root.optJSONObject("endpoints")?.let { e ->
            for (k in e.keys()) {
                val o = e.optJSONObject(k) ?: continue
                endpoints[k] = Spec(
                    key = k,
                    hostGroup = o.optString("host", "api_primary"),
                    path = o.optString("path", ""),
                    method = o.optString("method", "GET")
                )
            }
        }
        webPaths = mutableMapOf()
        root.optJSONObject("web_paths")?.let { w ->
            for (k in w.keys()) webPaths[k] = w.optString(k, "")
        }
    }

    fun version(): Int = version
    fun minClient(): Int = minClientVersion
    fun source(): String = sourceTag

    /** 瀹㈡埛绔増鏈槸鍚︿綆浜庨厤缃姹傜殑鏈€浣庣増鏈紙闇€瑕佹彁绀哄崌绾э級 */
    fun clientTooOld(currentVersionCode: Int): Boolean =
        minClientVersion > 0 && currentVersionCode < minClientVersion

    // ---------------- 杩滅鐑洿鏂?----------------

    /**
     * 鍐峰惎鍔ㄥ紓姝ユ媺閰嶇疆锛氬婧愬苟鍙戯紝鍚?4 绉掕秴鏃讹紱
     * 鍙帴鍙?version > 鏈湴 鐨勯厤缃紱鎴愬姛鍚庡啓 filesDir 缂撳瓨锛屼笅娆″喎鍚洿鎺ョ敓鏁堛€?     */
    fun refreshRemoteAsync(ctx: Context) {
        val app = ctx.applicationContext
        thread(name = "ep-remote-refresh") {
            val lock = Object()
            var done = false
            var best: String? = null
            var bestV = version
            val workers = remoteUrls.map { url ->
                thread(name = "ep-fetch") {
                    try {
                        val text = httpGet(url, 8000)
                        val v = JSONObject(text).optInt("version", -1)
                        if (v > bestV) {
                            synchronized(lock) {
                                if (v > bestV) {
                                    best = text
                                    bestV = v
                                    done = true
                                }
                            }
                        } else {
                            Log.i(TAG, "remote v$v <= local v$version, skip ($url)")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "fetch failed ($url): ${t.javaClass.simpleName} ${t.message}")
                    }
                }
            }
            workers.forEach { runCatching { it.join(6000) } }
            val text = best
            if (text != null) {
                try {
                    parse(text)
                    sourceTag = "remote"
                    File(app.filesDir, CACHE_NAME).writeText(text)
                    Log.i(TAG, "remote config applied v$bestV (min_client=$minClientVersion)")
                } catch (t: Throwable) {
                    Log.e(TAG, "apply remote failed: ${t.message}")
                }
            } else {
                Log.i(TAG, "no newer remote config, keep $sourceTag v$version")
            }
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "HougTime/${com.houg.tv.BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    // ---------------- 鏌ヨ ----------------

    fun hosts(group: String): List<String> = hostsByGroup[group] ?: emptyList()
    fun spec(key: String): Spec? = endpoints[key]
    fun keys(): List<String> = endpoints.keys.sorted()

    /** 鍙栨煇鎺ュ彛鐨勫€欓€?URL锛堟寜鍩熷悕缁勫唴椤哄簭锛屼緵涓婂眰鍋氬け璐ヨ疆鎹級 */
    fun candidates(key: String, https: Boolean = true): List<String> {
        val s = endpoints[key] ?: return emptyList()
        val scheme = if (https) "https://" else "http://"
        return hosts(s.hostGroup).map { scheme + it + s.path }
    }

    /** web SSR 鍩哄潃锛堟帴鍙ｅ叏鐐告椂鐨勫厹搴曪級 */
    fun webBases(): List<String> = hosts("web_ssr").map { "https://$it" }

    fun webPath(key: String, vararg args: Pair<String, String>): String? {
        var p = webPaths[key] ?: return null
        for ((k, v) in args) p = p.replace("{$k}", v)
        return p
    }

    // ---------------- 杞崲 ----------------

    private val cursor = HashMap<String, Int>()

    private fun nextIndex(groupOrKey: String, size: Int): Int {
        if (size <= 0) return 0
        return (cursor[groupOrKey] ?: 0).coerceAtLeast(0) % size
    }

    fun advance(groupOrKey: String, size: Int) {
        if (size <= 0) return
        cursor[groupOrKey] = ((cursor[groupOrKey] ?: 0) + 1) % size
    }

    fun webBase(): String {
        val list = webBases()
        return if (list.isEmpty()) "https://hongguoduanju.com" else list[nextIndex("web_ssr", list.size)]
    }

    fun apiUrl(key: String, fallback: String): String {
        val cands = candidates(key)
        if (cands.isEmpty()) return fallback
        return cands[nextIndex(key, cands.size)]
    }

    fun reportFailure(key: String) {
        advance(key, candidates(key).size)
    }

    // ---------------- 鎺㈤拡 ----------------

    /**
     * 鎺㈤拡锛氬鍗曚釜鎺ュ彛鍙戜竴娆¤交閲忚姹傦紝鍙叧蹇?绔偣鏄惁杩樻椿鐫€"銆?     * 404/410 瑙嗕负璺緞琚垏锛涘叾瀹冿紙鍚弬鏁伴敊鐨?4xx锛夎鏄庣鐐硅繕鍦ㄣ€?     */
    fun probe(key: String, timeoutMs: Int = 8000): ProbeResult {
        val cands = candidates(key)
        if (cands.isEmpty()) return ProbeResult(key, "", -1, 0, "no candidate host")
        val spec = endpoints[key]
        var last: ProbeResult? = null
        for (url in cands) {
            val start = System.currentTimeMillis()
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (spec?.method == "POST") "POST" else "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) tv")
                    if (spec?.method == "POST") {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        outputStream.use { it.write("{}".toByteArray()) }
                    }
                }
                val code = conn.responseCode
                val r = ProbeResult(key, url, code, System.currentTimeMillis() - start)
                if (code != 404 && code != 410) return r
                last = r
            } catch (t: Throwable) {
                last = ProbeResult(
                    key, url, -1, System.currentTimeMillis() - start,
                    t.javaClass.simpleName + ":" + t.message
                )
            } finally {
                try { conn?.disconnect() } catch (_: Throwable) {}
            }
        }
        return last ?: ProbeResult(key, "", -1, 0, "unknown")
    }
}
