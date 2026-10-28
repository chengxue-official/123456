package com.houg.tv.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.houg.tv.net.HttpClientProvider
import com.houg.tv.util.AppLog
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * 热更新：检查 + 下载 + 唤起安装。
 *
 * 架构（2026-10 迁移后）：本地代码 → Git 仓库 → EdgeOne Pages 自动部署 → 电视直接读托管域名。
 * 电视端只接触托管域名，不直连 GitHub（raw.githubusercontent.com 国内不可达）。
 * 原 Cloudflare Worker 链路因账号被封已作废（详见 HOTUPDATE-PLAN.md）。
 *
 * 清单 update.json（v2，向后兼容旧字段）：
 * ```json
 * {
 *   "versionCode": 118,
 *   "versionName": "1.0.18",
 *   "apk": "hongguo-1.0.18.apk",
 *   "notes": "修复xxx",
 *   "sha256": "（非空则强校验）",
 *   "mirrors": ["https://其它源/update.json"],
 *   "logApi": "https://托管域名/api/log"
 * }
 * ```
 *
 * **清单自举**：成功读到任何一份清单后，把原文落盘；下次检查的源列表 = 硬编码源
 * ∪ 清单里的 mirrors ∪ 上次命中的源。以后换托管只改清单，不必再发 APK。
 */
object UpdateChecker {

    /**
     * 旧托管域名（KataBump 免费二级域名，NS 曾指向 Cloudflare）。
     *
     * 2026-10 CF 账号被封后 Worker 已消失（GET /update.json 返回 403），但 DNS 解析仍正常。
     * 保留在源列表里有两个作用：
     * 1) 若把该域名的 NS 改指新托管（EdgeOne），已装客户端无需重装即可恢复更新；
     * 2) 作为备用源：403 是快速失败，不拖慢整体检查。
     */
    const val HOST = "https://hongguo.kdns.fr"

    /**
     * 新主源（EdgeOne Pages 自定义域名）。
     *
     * 为什么是 `www.` 子域名而不是根域 `hongguo.kdns.fr`：
     * EdgeOne 只提供 CNAME 接入，而 KataBump 明确禁止在 zone apex（@）放 CNAME
     * （与 SOA/NS 冲突，RFC 1034），所以根域无法指向 EdgeOne，只能用子域名。
     *
     * 注意：**不带结尾斜杠**；EdgeOne 项目默认域名（*.edgeone.site / *.pages 系列）
     * 在国内不可匿名访问（合规限制），必须走这个已备案豁免的自定义域名 + 免费证书。
     * 填好后即具备「清单自举」能力：以后换托管只改 update.json 的 mirrors，不用再发 APK。
     */
    const val NEW_HOST = "https://www.hongguo.kdns.fr"

    /**
     * 硬编码源列表（按顺序尝试，任一成功即采用其返回）。
     *
     * 实际使用的源 = 本列表 ∪ 上次成功清单里的 mirrors ∪ 上次命中的源（见 [sources]）。
     * 即使将来硬编码源全部失效，只要本地还存着一份旧清单，就能从 mirrors 自举到新源。
     *
     * 历史教训：`*.workers.dev` 在国内遭**系统性 DNS 污染**（被投毒到 Facebook / Dropbox
     * 的 IP 段），任何国内网络都连不通，永远不要再加回源列表。
     *
     * 顺序：`www.` 子域优先——它的解析实测稳定，而部分电视（如 VIDAA）上裸域
     * `hongguo.kdns.fr` 会 DNS 解析失败，若排在前面会白等一轮超时才切到可用域。
     */
    private val HOSTS = listOf(NEW_HOST, HOST)

    /** 清单与自举源的持久化（成功读到清单后落盘，供下次检查与日志地址使用）。 */
    private const val PREFS = "update_checker"
    private const val PREF_MANIFEST = "manifest"
    private const val PREF_LAST_HOST = "last_good_host"

    /** 每个域名的尝试次数：Cloudflare 边缘偶发 TCP/握手被重置，重试一次即可恢复。 */
    private const val ATTEMPTS_PER_HOST = 3

    /** 重试前的等待，避免连续撞上同一段网络抖动。 */
    private const val RETRY_DELAY_MS = 400L

    private const val TAG = "UpdateChecker"

    private fun updateUrl(host: String): String {
        val h = host.trimEnd('/')
        // 源可能是完整清单 URL（mirrors 里的 xxx/update.json），此时原样使用
        return if (h.endsWith(".json")) h else h + "/update.json"
    }

    /** 远端版本信息。 */
    data class Remote(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val notes: String,
        /** APK 的 SHA-256（十六进制）。空 = 老清单，只做长度校验。 */
        val sha256: String = "",
        /** 清单镜像（完整 update.json URL），下次检查时并入源列表（自举）。 */
        val mirrors: List<String> = emptyList(),
        /** 日志上传地址（随清单热切换）。空 = 调用方沿用内置默认。 */
        val logApi: String = ""
    )

    /** 检查结果：hasUpdate=有新版本；message=给用户的提示。 */
    data class CheckResult(val hasUpdate: Boolean, val remote: Remote?, val message: String)

    /**
     * 同步检查更新（网络操作，调用方需切 IO 线程）。
     * 依次尝试 [sources]，每个源试 [ATTEMPTS_PER_HOST] 次（边缘偶发抖动重试即恢复），
     * 任一成功即采用，并把清单原文落盘（供 mirrors 自举与 logApi 热切换）；全部失败才报错。
     *
     * ⚠️ 错误信息必须**聚合**各次尝试的真实原因，绝不能只留最后一条——
     * 旧版 lastErr 被后一个域名覆盖，报错永远显示兜底域名的超时，
     * 用户看到「连不上 workers.dev」实则主域名早已失败，排查方向被带偏。
     * @param context 仅用于持久化清单（读取自举源），可传 Application Context
     * @param currentVersionCode 本机安装的 versionCode
     */
    fun check(context: Context, currentVersionCode: Int): CheckResult {
        val errors = ArrayList<String>()
        for (host in sources(context)) {
            if (host.isBlank() || !host.startsWith("http")) continue
            for (attempt in 1..ATTEMPTS_PER_HOST) {
                try {
                    val req = Request.Builder().url(updateUrl(host))
                        .header("Cache-Control", "no-cache") // 确保拿到最新 update.json 而非缓存
                        .build()
                    val resp = HttpClientProvider.get().newCall(req).execute()
                    try {
                        if (!resp.isSuccessful) {
                            errors.add("$host HTTP ${resp.code()}")
                            break // 该域名持续非 2xx，换下一个域名
                        }
                        val text = resp.body()?.string() ?: ""
                        val remote = parse(text, host)
                            ?: return CheckResult(false, null, "update.json 格式错误")
                        // 落盘清单原文：mirrors 下次并入源列表，logApi 供日志上传热切换
                        rememberManifest(context, text, host)
                        AppLog.d(TAG, "检查更新成功 host=$host 第${attempt}次 远端=${remote.versionName}")
                        return if (remote.versionCode > currentVersionCode) {
                            CheckResult(true, remote, "发现新版本 v${remote.versionName}")
                        } else {
                            CheckResult(false, remote, "已是最新版本 v${remote.versionName}")
                        }
                    } finally {
                        resp.close()
                    }
                } catch (e: Exception) {
                    // 网络被重置/SSL 中断/DNS 失败等：聚合真实原因后重试
                    val why = e.message ?: e.javaClass.simpleName
                    errors.add("$host 第${attempt}次 $why")
                    AppLog.d(TAG, "检查更新失败 host=$host 第${attempt}次: $why")
                    if (attempt < ATTEMPTS_PER_HOST) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
            }
        }
        val msg = "检查失败：" + errors.joinToString("；")
        AppLog.d(TAG, msg)
        return CheckResult(false, null, msg)
    }

    private fun parse(text: String, host: String): Remote? = try {
        val o = JSONObject(text)
        val apk = o.optString("apk", "")
        if (apk.isEmpty()) null
        else Remote(
            versionCode = o.optInt("versionCode", 0),
            versionName = o.optString("versionName", "?"),
            // apk 用"命中成功的那个 host"拼绝对地址，避免主域名取到清单却从备用域名下载
            apkUrl = if (apk.startsWith("http")) apk else host.trimEnd('/') + "/" + apk.trimStart('/'),
            notes = o.optString("notes", ""),
            sha256 = o.optString("sha256", "").trim(),
            mirrors = parseMirrors(o.optJSONArray("mirrors")),
            logApi = o.optString("logApi", "").trim()
        )
    } catch (e: Exception) {
        null
    }

    /** 读 mirrors 数组：只收 http(s) 绝对地址，去重并限 8 条，防脏清单把检查拖成龟速。 */
    private fun parseMirrors(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val u = arr.optString(i, "").trim()
            if (u.startsWith("http") && !out.contains(u)) out.add(u)
            if (out.size >= 8) break
        }
        return out
    }

    /* ==================== 清单持久化与源自举 ==================== */

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 记住最近一次读到的清单与命中源（mirrors 自举、logApi 热切换都依赖它）。 */
    private fun rememberManifest(context: Context, text: String, host: String) {
        try {
            prefs(context).edit()
                .putString(PREF_MANIFEST, text)
                .putString(PREF_LAST_HOST, host.trim())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun savedManifest(context: Context): JSONObject? = try {
        val s = prefs(context).getString(PREF_MANIFEST, null)
        if (s.isNullOrBlank()) null else JSONObject(s)
    } catch (_: Exception) {
        null
    }

    /** 清单下发的日志上传地址；空 = 未配置，调用方回退内置默认 [com.houg.tv.util.LogUploader.LOG_API]。 */
    fun savedLogApi(context: Context): String =
        savedManifest(context)?.optString("logApi", "")?.trim().orEmpty()

    /**
     * 本次检查的源列表（去重、保序）：
     * 硬编码 HOSTS → 上次清单里的 mirrors → 上次命中的源。
     */
    private fun sources(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        for (h in HOSTS) {
            val v = h.trim()
            if (v.startsWith("http")) out.add(v.trimEnd('/'))
        }
        val saved = savedManifest(context)
        if (saved != null) {
            for (m in parseMirrors(saved.optJSONArray("mirrors"))) out.add(m.trimEnd('/'))
        }
        val last = try {
            prefs(context).getString(PREF_LAST_HOST, "").orEmpty()
        } catch (_: Exception) {
            ""
        }.trim()
        if (last.startsWith("http")) out.add(last.trimEnd('/'))
        return out.toList()
    }

    /** 从源地址取 scheme://host[:port]，用于拼同名 APK 的下载候选。 */
    private fun originOf(url: String): String = try {
        val u = java.net.URI(url)
        val port = if (u.port > 0) ":${u.port}" else ""
        val origin = "${u.scheme}://${u.host}$port"
        if (origin.length > "${u.scheme}://".length + 2) origin else url.trimEnd('/')
    } catch (_: Exception) {
        url.trimEnd('/')
    }

    /** 流式计算文件 SHA-256 并与清单比对（忽略大小写）。 */
    private fun sha256Matches(file: File, expected: String): Boolean {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val hex = md.digest().joinToString("") { String.format(java.util.Locale.US, "%02x", it) }
            hex.equals(expected.trim(), ignoreCase = true)
        } catch (e: Exception) {
            AppLog.d(TAG, "sha256 计算失败: ${e.message}")
            false
        }
    }

    /**
     * 同步下载 APK 到应用缓存目录（网络操作，调用方需切 IO 线程）。
     * 候选地址 = 清单命中的 URL + 各源（含 mirrors 自举源）拼出的同名文件，逐个尝试，
     * 抗"检查通了但下载域名被 reset"的情况。清单带 sha256 时逐个校验，损坏即换源。
     * @return 下载好的文件；失败返回 null 并写 [error]。
     */
    fun download(context: Context, remote: Remote, error: StringBuilder): File? {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        // 先写临时文件，完成后改名，避免中断留下半截文件被误装
        val tmp = File(dir, "update.apk.part")
        val target = File(dir, "hongguo-${remote.versionName}.apk")
        val name = remote.apkUrl.substringAfterLast('/')
        val candidates = LinkedHashSet<String>().apply {
            add(remote.apkUrl)
            for (h in sources(context)) add(originOf(h).trimEnd('/') + "/" + name)
        }
        // 每个候选地址试 ATTEMPTS_PER_HOST 次（APK 十几 MB，边缘抖动一次就会断流），
        // 各地址的真实原因**累积**写入 error，不要每轮清空——否则用户只看到最后一次失败。
        error.setLength(0)
        for (url in candidates) {
            for (attempt in 1..ATTEMPTS_PER_HOST) {
                val one = StringBuilder()
                if (downloadOne(url, tmp, target, one)) {
                    // 清单给了 sha256 就强校验：不一致说明文件损坏或被替换，换下一个源
                    if (remote.sha256.isNotEmpty() && !sha256Matches(target, remote.sha256)) {
                        val why = "sha256 校验失败（文件可能损坏或被替换）"
                        AppLog.d(TAG, "下载校验失败 $url 第${attempt}次: $why")
                        if (error.length > 0) error.append('\n')
                        error.append("$url 第${attempt}次：").append(why)
                        target.delete()
                        break
                    }
                    AppLog.d(TAG, "下载成功 $url 第${attempt}次")
                    return target
                }
                AppLog.d(TAG, "下载失败 $url 第${attempt}次: $one")
                if (error.length > 0) error.append('\n')
                error.append("$url 第${attempt}次：").append(one)
                if (attempt < ATTEMPTS_PER_HOST) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        if (error.length == 0) error.append("下载失败")
        return null
    }

    /** 下载单个 URL 到 tmp 并改名为 target。成功返回 true，否则写 [error] 返回 false。 */
    private fun downloadOne(url: String, tmp: File, target: File, error: StringBuilder): Boolean {
        return try {
            tmp.delete()
            target.delete()
            val req = Request.Builder().url(url).build()
            val resp = HttpClientProvider.get().newCall(req).execute()
            try {
                if (!resp.isSuccessful) {
                    error.append("下载失败：HTTP ${resp.code()}")
                    return false
                }
                val body = resp.body() ?: run {
                    error.append("下载失败：响应为空")
                    return false
                }
                val total = body.contentLength()
                var written = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            written += n
                        }
                        output.flush()
                    }
                }
                if (total > 0 && written < total) {
                    error.append("下载不完整：$written/$total")
                    tmp.delete()
                    return false
                }
                if (!tmp.renameTo(target)) {
                    // rename 失败（跨设备等）：直接复制
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                true
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            error.append("下载异常：" + (e.message ?: e.javaClass.simpleName))
            false
        }
    }

    /**
     * 唤起系统安装器安装已下载的 APK。
     * @return true 表示成功唤起安装器（不代表用户点了安装）；false 表示无法唤起。
     *
     * 注意：这里**不**检查 canRequestPackageInstalls()——小米等 ROM 上该 API 常年
     * 返回 false（即使「允许安装未知应用」已开），据此拦截会让用户永远装不上。
     * 直接唤起系统安装器，授权页由安装器自行弹出；失败再由调用方给分厂商引导。
     */
    fun install(context: Context, apk: File): Boolean {
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", apk
            )
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                context, "安装包路径异常：${e.message ?: ""}", android.widget.Toast.LENGTH_LONG
            ).show()
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // 部分盒子无系统安装器，或 ROM 直接拦截；调用方据此给引导
            android.widget.Toast.makeText(
                context, "无法启动安装器，请手动安装：$apk", android.widget.Toast.LENGTH_LONG
            ).show()
            false
        }
    }
}
