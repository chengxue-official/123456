package com.houg.tv.util

import android.content.Context
import com.houg.tv.net.HttpClientProvider
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志远程上报：把全量日志 + 设备信息打包上报到开发者。
 *
 * 用户诉求演变：电视上分享/传文件太麻烦 → 最初用飞书机器人 webhook 直接灌卡片；
 * 但 webhook 不支持文件消息，长日志会被切成多张长卡片，接收端要手动滚动复制。
 *
 * 现行方案（文件化）：
 * 1. 日志先上传到自有服务器（[LOG_API]，与更新服务器同域，国内可达），
 *    拿到一次性 token 与「在线查看 / 下载 .txt」链接；
 * 2. 再发一张**短卡片**到飞书群（标题 + 摘要 + 两个链接），
 *    接收端在手机上点一下即可拿文件，无需滚动复制；
 * 3. 上传或发卡片失败 → 降级回旧模式：分片灌卡片（见 [splitChunks]），保证日志不丢。
 *
 * 协议：
 * - 上传：POST JSON 到 [LOG_API]（自有 Worker，application/json）；
 * - 降级：飞书自定义机器人 incoming webhook（POST JSON，application/json）。
 */
object LogUploader {

    /**
     * 日志上传地址的**内置默认**（仅在清单还没下发 logApi 时使用）。
     *
     * 实际地址优先取 update.json 里的 `logApi` 字段（[com.houg.tv.update.UpdateChecker.savedLogApi]），
     * 可在服务端热切换；这里只是首次启动/清单未读到时的兜底。
     */
    const val LOG_API = "https://www.hongguo.kdns.fr/api/log"

    /** 飞书群机器人 webhook（用户建好机器人后填这里）。空 = 未配置，点击时提示。 */
    const val LOG_WEBHOOK_URL =
        "https://open.feishu.cn/open-apis/bot/v2/hook/7a8c2ca0-be89-418e-9407-0548ca423792"

    /** 机器人安全设置「自定义关键词」——务必保证该词出现在消息中，否则被飞书拦截。 */
    private const val KEYWORD = "日志"

    /**
     * 上传限流窗口：同一设备 15 分钟内最多上传一次。
     *
     * 双保险：App 端本地节流做即时拦截（省一次网络往返），服务端（Worker）按
     * deviceKey 独立限流做权威兜底（防改包/清数据绕过）。两边窗口必须一致。
     */
    private const val RATE_LIMIT_MS = 15 * 60 * 1000L

    private const val PREFS = "log_uploader"
    private const val PREF_LAST_UPLOAD = "last_upload_ms"
    private const val PREF_INSTALL_ID = "install_id"

    /**
     * 单张卡片正文字节上限。飞书实测上限为整卡 100KB，报错原文
     * `card content size is over limit, the max size is 100kb`；
     * 取 48KB 留足转义膨胀（`"`→`\"`）与 card 结构开销的余量。
     */
    private const val CHUNK_BYTES = 48 * 1024

    /** 分片数量上限，防止日志异常膨胀时刷屏（20 片 ≈ 960KB）。 */
    private const val MAX_CHUNKS = 20

    /**
     * 整报上传（文件模式）的字符上限。EdgeOne 请求 body 硬上限 1MB，
     * 按 UTF-8 中文 3 字节/字符的最坏情况取 30 万字符（≈900KB），保证不触顶。
     */
    private const val MAX_UPLOAD_CHARS = 300_000

    /** 分片之间间隔（ms），规避机器人 5 次/秒的限流。 */
    private const val SEND_INTERVAL_MS = 220L

    private const val TAG = "LogUploader"

    private val JSON = MediaType.parse("application/json; charset=utf-8")

    /**
     * 上报结果。
     * @param ok 是否成功送达
     * @param detail 成功时为摘要，失败时为原因
     * @param rateLimited 失败是否由「15 分钟内重复上传」引起（调用方据此换个友好提示，
     *                    不必显示"上传失败"字样）
     */
    data class Result(val ok: Boolean, val detail: String, val rateLimited: Boolean = false)

    /**
     * 同步上报全量日志（网络耗时操作，调用方需先切 IO 线程）。
     *
     * 流程：先尝试「文件模式」（上传拿链接 + 发短卡片）；任一环节失败则降级
     * 为「分片模式」（把日志正文直接灌进多张飞书卡片），确保日志始终能送达。
     *
     * 限流：15 分钟内只允许上传一次。本地拦截命中直接返回，不发任何请求；
     * 服务端返回 429 时同样直接返回（**不降级**——否则限流形同虚设，
     * 仍会往飞书群里灌卡片）。
     *
     * @return [Result.ok]=true 表示已送达；失败时 [Result.detail] 含具体原因。
     */
    fun upload(context: Context): Result {
        val url = LOG_WEBHOOK_URL.trim()
        if (url.isEmpty()) {
            return Result(false, "未配置 LOG_WEBHOOK_URL（见 LogUploader 顶部）")
        }
        if (!url.startsWith("https://open.feishu.cn/open-apis/bot/v2/hook/")) {
            return Result(false, "URL 需为飞书机器人地址（https://open.feishu.cn/open-apis/bot/v2/hook/...）")
        }

        // ① 本地节流：15 分钟内已上传过 → 直接拒绝，不消耗网络与飞书配额
        val localRemain = localRateRemainMs(context)
        if (localRemain > 0) {
            return Result(false, "上传过于频繁，请约 ${ceilMinutes(localRemain)} 分钟后再试", rateLimited = true)
        }

        val fullReport = buildReport(context)
        // 超过平台 body 上限时先截断（保留头部：设备信息在前，尾部多为重复堆栈）
        val report = if (fullReport.length > MAX_UPLOAD_CHARS) {
            fullReport.take(MAX_UPLOAD_CHARS) +
                "\n……（日志过长，已截断，原始共 ${fullReport.length} 字符）……"
        } else fullReport
        val model = android.os.Build.MODEL.replace("\"", "'").replace("\n", " ")
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

        // ② 文件模式（首选）：上传拿链接 → 发一张短卡片
        // 上传地址优先用清单热下发的 logApi，没读到清单才回退内置默认
        val api = com.houg.tv.update.UpdateChecker.savedLogApi(context).ifEmpty { LOG_API }
        val result = uploadToServer(api, report, model, time, installId(context))
        val uploaded = result.uploaded
        if (uploaded != null) {
            val payload = buildLinkPayload(uploaded, report, model, time)
            val error = post(url, payload)
            if (error == null) {
                markUploaded(context)
                return Result(true, "文件模式 · ${report.length} 字符 / ${uploaded.sizeKb}KB")
            }
            // 卡片发送失败：链接已生成，仍在降级结果里告知（用户可能想重试）
            AppLog.e(TAG, "文件模式发卡片失败，降级分片：$error", null)
        } else if (result.rateLimited) {
            // 服务端限流：不降级，避免绕过限流继续灌卡片
            markUploaded(context)
            return Result(false, "上传过于频繁，请约 ${ceilMinutes(result.retryAfterSec * 1000L)} 分钟后再试", rateLimited = true)
        } else {
            AppLog.e(TAG, "日志上传服务器失败，降级分片模式", null)
        }

        // ③ 分片模式（降级）：正文直接进卡片
        val chunked = uploadChunked(url, report, model, time)
        if (chunked.ok) markUploaded(context)
        return chunked
    }

    /** 服务端上传结果：三态（成功 / 被限流 / 其它失败）。 */
    private class ServerResult(
        val uploaded: Uploaded?,
        val rateLimited: Boolean = false,
        val retryAfterSec: Long = 0L
    )

    /**
     * 上传日志到自有服务器。
     * @param api 上传地址（清单下发的 logApi，或内置默认 [LOG_API]）
     * @return 成功时 [ServerResult.uploaded] 非空；被限流时 [ServerResult.rateLimited]=true。
     */
    private fun uploadToServer(
        api: String,
        report: String,
        model: String,
        time: String,
        deviceKey: String
    ): ServerResult {
        return try {
            val sb = StringBuilder(report.length + 256)
            sb.append('{')
            sb.append("\"deviceKey\":\"").append(escapeJson(deviceKey)).append("\",")
            sb.append("\"device\":\"").append(escapeJson(model)).append("\",")
            sb.append("\"time\":\"").append(escapeJson(time)).append("\",")
            sb.append("\"title\":\"").append(escapeJson("[$KEYWORD] HougTime 上报 · $model · $time")).append("\",")
            sb.append("\"content\":\"").append(escapeJson(report)).append("\"}")
            val req = Request.Builder().url(api)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(JSON, sb.toString()))
                .build()
            val resp: Response = HttpClientProvider.get().newCall(req).execute()
            try {
                val text = if (resp.body() != null) resp.body()!!.string() else ""
                if (resp.code() == 429) {
                    val sec = try { JSONObject(text).optLong("retryAfter", RATE_LIMIT_MS / 1000) } catch (_: Exception) { RATE_LIMIT_MS / 1000 }
                    return ServerResult(null, rateLimited = true, retryAfterSec = sec)
                }
                if (!resp.isSuccessful) return ServerResult(null)
                val o = JSONObject(text)
                if (o.optInt("code", -1) != 0) return ServerResult(null)
                val view = o.optString("view", "")
                val download = o.optString("download", "")
                if (view.isEmpty() || download.isEmpty()) return ServerResult(null)
                ServerResult(Uploaded(view, download, o.optLong("size", 0L)))
            } finally {
                resp.close()
            }
        } catch (e: Exception) {
            ServerResult(null)
        }
    }

    /* ==================== 限流与设备标识 ==================== */

    /** 本地距下次可上传的剩余毫秒；0 表示现在可以上传。 */
    private fun localRateRemainMs(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(PREF_LAST_UPLOAD, 0L)
            if (last <= 0L) return 0L
            (last + RATE_LIMIT_MS - System.currentTimeMillis()).coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    /** 记录本次上传时间（本地节流基准）。 */
    private fun markUploaded(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(PREF_LAST_UPLOAD, System.currentTimeMillis()).apply()
        } catch (_: Exception) {
        }
    }

    /**
     * 稳定设备标识（安装级 UUID，持久化）。
     *
     * 不能用 SignedRequest.deviceId()——那是**每次启动随机生成**的，做限流键毫无意义。
     * 首次调用生成并落盘，卸载重装才会变（符合"每台设备 15 分钟一次"的语义）。
     */
    private fun installId(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(PREF_INSTALL_ID, null)
            if (!existing.isNullOrEmpty()) return existing
            val gen = java.util.UUID.randomUUID().toString().replace("-", "").take(32)
            prefs.edit().putString(PREF_INSTALL_ID, gen).apply()
            gen
        } catch (_: Exception) {
            // 兜底：拿不到持久化时用机型，至少避免"完全不限流"
            android.os.Build.MODEL ?: "unknown"
        }
    }

    /** 毫秒向上取整为分钟（用于提示文案）。 */
    private fun ceilMinutes(ms: Long): Long = ((ms + 59_999L) / 60_000L).coerceAtLeast(1L)

    /** 降级路径：把日志按字节切分成多张飞书卡片连续发送。 */
    private fun uploadChunked(url: String, report: String, model: String, time: String): Result {
        val chunks = splitChunks(report)
        val total = chunks.size
        for (i in chunks.indices) {
            val payload = buildPayload(chunks[i], model, time, i + 1, total)
            val error = post(url, payload)
            if (error != null) {
                return if (i == 0) Result(false, error)
                else Result(false, "第 ${i + 1}/$total 片失败（前 $i 片已送达）：$error")
            }
            if (i < chunks.size - 1) {
                try {
                    Thread.sleep(SEND_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        return Result(true, if (total > 1) "分片模式 · 共 $total 片，${report.length} 字符" else "${report.length} 字符")
    }

    /** 已上传日志的对外链接。 */
    private data class Uploaded(val view: String, val download: String, val size: Long) {
        val sizeKb: String get() = String.format(Locale.US, "%.1f", size / 1024.0)
    }

    /** 发一张卡片；返回 null 表示成功，否则为失败原因。 */
    private fun post(url: String, payload: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("Content-Type", "application/json; charset=utf-8")
                .post(RequestBody.create(JSON, payload))
                .build()
            var result: String? = null
            val resp: Response = HttpClientProvider.get().newCall(req).execute()
            try {
                val text = if (resp.body() != null) resp.body()!!.string() else ""
                if (!resp.isSuccessful) {
                    result = "HTTP " + resp.code()
                } else if (!isFeishuOk(text)) {
                    // 业务码非 0 视为失败（如关键词不匹配返回 code 190021）
                    result = "飞书返回异常: " + text.take(200)
                }
            } finally {
                resp.close()
            }
            result
        } catch (e: Exception) {
            "网络异常: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** 飞书成功响应形如 {"code":0,...} 或 {"StatusCode":0,...}；容忍空格。 */
    private fun isFeishuOk(text: String): Boolean {
        if (text.isEmpty()) return false
        val compact = text.replace(" ", "")
        return compact.contains("\"code\":0") || compact.contains("\"StatusCode\":0")
    }

    /** 组装上报内容：设备信息 + 全量日志 + 接口信息（与 LogActivity 展示格式一致）。 */
    private fun buildReport(context: Context): String {
        val logBody = AppLog.dumpFile().ifEmpty { "（暂无日志）" }
        return "===== 设备信息 =====\n" +
                DeviceInfo.describe(context) + "\n\n" +
                "===== 日志 =====\n" + logBody + "\n\n" +
                "===== 接口信息 =====\n" +
                "Base URL: " + AppLog.baseUrl.ifEmpty { "-" } + "\n" +
                "device_id: " + AppLog.deviceId.ifEmpty { "-" } + "\n" +
                "iid: " + AppLog.iid.ifEmpty { "-" } + "\n" +
                "X-Gorgon: " + AppLog.lastGorgon.ifEmpty { "-" }
    }

    /**
     * 按行切分为不超过 [CHUNK_BYTES] 字节的分片；超长单行按字节硬切。
     * 逐行扫描并保留换行符，保证分片拼回与原文字节完全一致（不吞不加）。
     * 超过 [MAX_CHUNKS] 片时截断并追加提示（宁丢尾部也不刷屏）。
     */
    private fun splitChunks(report: String): List<String> {
        if (byteLen(report) <= CHUNK_BYTES) return listOf(report)
        val chunks = ArrayList<String>()
        val sb = StringBuilder()
        var sbBytes = 0
        var i = 0
        while (i < report.length) {
            // 取出一整行（含其换行符；末行可能无换行）
            val nl = report.indexOf('\n', i)
            val end = if (nl < 0) report.length else nl + 1
            val line = report.substring(i, end)
            i = end
            val lineBytes = byteLen(line)
            if (lineBytes > CHUNK_BYTES) {
                // 超长单行：先落盘已缓存内容，再按字节硬切成整片
                if (sb.isNotEmpty()) {
                    chunks.add(sb.toString())
                    sb.setLength(0)
                    sbBytes = 0
                }
                chunks.addAll(hardSplitByBytes(line))
                continue
            }
            if (sbBytes + lineBytes > CHUNK_BYTES) {
                chunks.add(sb.toString())
                sb.setLength(0)
                sbBytes = 0
            }
            sb.append(line)
            sbBytes += lineBytes
        }
        if (sb.isNotEmpty()) chunks.add(sb.toString())

        if (chunks.size > MAX_CHUNKS) {
            val kept = ArrayList(chunks.subList(0, MAX_CHUNKS))
            kept[MAX_CHUNKS - 1] = kept[MAX_CHUNKS - 1] +
                    "\n……（日志过长，已截断至前 $MAX_CHUNKS 片，共 ${chunks.size} 片）……"
            return kept
        }
        return chunks
    }

    /** 把一段长文本按 UTF-8 字节上限硬切成多片（不在多字节字符中间切断）。 */
    private fun hardSplitByBytes(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val w = utf8Width(cp)
            if (bytes + w > CHUNK_BYTES) {
                out.add(text.substring(start, i))
                start = i
                bytes = 0
            }
            bytes += w
            i += Character.charCount(cp)
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }

    /** 单个码点的 UTF-8 字节宽度。 */
    private fun utf8Width(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    private fun byteLen(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** 组装飞书 markdown 卡片 payload；多片时标题带 (i/N)。 */
    private fun buildPayload(chunk: String, model: String, time: String, index: Int, total: Int): String {
        // 标题里带 [KEYWORD]，满足机器人「自定义关键词」安全校验
        val seq = if (total > 1) " ($index/$total)" else ""
        val title = "[$KEYWORD] HougTime 上报 · $model · $time$seq"

        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"msg_type\":\"interactive\",")
        sb.append("\"card\":{")
        sb.append("\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"")
        sb.append(escapeJson(title))
        sb.append("\"},\"template\":\"blue\"},")
        sb.append("\"elements\":[{\"tag\":\"markdown\",\"content\":\"")
        sb.append(escapeJson(chunk))
        sb.append("\"}]}}")
        return sb.toString()
    }

    /**
     * 组装「文件模式」短卡片：标题 + 摘要 + 日志预览片段 + 两个跳转按钮。
     * 正文极短（固定几百字符），不受卡片长度限制，也不再需要分片。
     */
    private fun buildLinkPayload(up: Uploaded, report: String, model: String, time: String): String {
        val title = "[$KEYWORD] HougTime 上报 · $model · $time"

        // 摘要：设备信息段（===== 设备信息 ===== 到下一个 ===== 之间），多行压成一行
        val preview = report
            .substringBefore("\n\n=====")
            .substringAfter("===== 设备信息 =====")
            .trim()
            .replace('\n', ' ')
            .take(240)

        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"msg_type\":\"interactive\",")
        sb.append("\"card\":{")
        sb.append("\"header\":{\"title\":{\"tag\":\"plain_text\",\"content\":\"")
        sb.append(escapeJson(title))
        sb.append("\"},\"template\":\"blue\"},")
        sb.append("\"elements\":[")
        // 摘要 + 预览
        sb.append("{\"tag\":\"markdown\",\"content\":\"")
        sb.append(escapeJson("**日志已文件化，点下方按钮查看/下载**\n\n" +
                "大小：${up.sizeKb}KB ｜ ${report.length} 字符\n" +
                "设备：$preview"))
        sb.append("\"},")
        // 跳转按钮组（飞书卡片 action 组件：双按钮横排）
        sb.append("{\"tag\":\"action\",\"actions\":[")
        sb.append("{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"在线查看\"},")
        sb.append("\"type\":\"primary\",\"url\":\"").append(escapeJson(up.view)).append("\"},")
        sb.append("{\"tag\":\"button\",\"text\":{\"tag\":\"plain_text\",\"content\":\"下载 .txt\"},")
        sb.append("\"type\":\"default\",\"url\":\"").append(escapeJson(up.download)).append("\"}")
        sb.append("]}")
        sb.append("]}}")
        return sb.toString()
    }

    /** JSON 字符串转义：\" \\ \n \r \t 与控制字符。 */
    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (element in s) {
            when (element) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (element < ' ') {
                    sb.append(String.format("\\u%04x", element.code))
                } else {
                    sb.append(element)
                }
            }
        }
        return sb.toString()
    }
}
