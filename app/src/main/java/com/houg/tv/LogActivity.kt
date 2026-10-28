package com.houg.tv

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.houg.tv.net.HttpClientProvider
import com.houg.tv.net.NetClient
import com.houg.tv.sign.DeviceRegister
import com.houg.tv.util.AppLog
import com.houg.tv.util.DeviceInfo
import com.houg.tv.util.LogUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 调试日志界面：展示设备配置 + AppLog 缓冲 + 接口信息快照 */
class LogActivity : AppCompatActivity() {

    /** 全局 density/字号缩放（1280×720dp 设计画布），见 UiScale.warp */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.warp(newBase))
    }

    private companion object {
        const val SELF_CHECK_TAG = "SelfCheck"
        /** 自检搜索词：命中率高且稳定有免费集，只用于验证链路可用 */
        const val SELF_CHECK_KEYWORD = "总裁"
    }

    private lateinit var logText: TextView
    private lateinit var infoText: TextView
    private lateinit var deviceInfoText: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiScale.applyImmersive(this)
        setContentView(R.layout.activity_log)

        logText = findViewById(R.id.log_text)
        infoText = findViewById(R.id.info_text)
        deviceInfoText = findViewById(R.id.device_info_text)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_selfcheck).setOnClickListener { runSelfCheck() }
        findViewById<View>(R.id.btn_clear).setOnClickListener {
            AppLog.clear()
            logText.text = getString(R.string.log_empty)
        }
        findViewById<View>(R.id.btn_copy).setOnClickListener {
            copyToClipboard(buildLogReport())
            Toast.makeText(this, "日志已复制，请粘贴发送给开发者", Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.btn_share).setOnClickListener { shareLog() }
        findViewById<View>(R.id.btn_upload).setOnClickListener { uploadLog() }
        findViewById<View>(R.id.btn_reset_identity).setOnClickListener { resetIdentity() }

        refresh()
    }

    /**
     * 一键上传全量日志到开发者（飞书群机器人）。电视上分享/传文件很麻烦，
     * 这个入口让用户按一下即可把日志送到开发者手机，无需任何操作。
     *
     * 限流：同一设备 15 分钟内只允许上传一次（本地即时拦截 + 服务端兜底）。
     */
    private fun uploadLog() {
        val btn = findViewById<TextView>(R.id.btn_upload)
        btn.isEnabled = false
        btn.text = "上传中…"
        Toast.makeText(this, "正在上传全量日志…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) { LogUploader.upload(this@LogActivity) }
            btn.isEnabled = true
            btn.text = getString(R.string.log_upload)
            val msg = when {
                result.ok -> "日志已上传（${result.detail}），开发者将收到"
                result.rateLimited -> result.detail   // 限流提示本身就是完整说明，不加"上传失败"前缀
                else -> "上传失败：${result.detail}"
            }
            Toast.makeText(this@LogActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 重置设备身份（等价卸载重装）：丢弃本地 device_id/iid/cookie 等全部特征后
     * 立即重注册，用于「该集未开放」类故障的自救——旧身份被服务端判废时，
     * 换新身份即可恢复，不必让用户卸载重装。
     */
    private fun resetIdentity() {
        val btn = findViewById<TextView>(R.id.btn_reset_identity)
        if (!btn.isEnabled) return
        btn.isEnabled = false
        btn.text = "重置中…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    DeviceRegister.resetIdentity()
                    val r = DeviceRegister.registerNow(HttpClientProvider.get())
                    "✅ 已换新身份 device_id=${r.deviceId}"
                } catch (t: Throwable) {
                    "❌ 重置后注册失败：${t.javaClass.simpleName}: ${t.message}"
                }
            }
            btn.isEnabled = true
            btn.text = getString(R.string.log_reset_identity)
            Toast.makeText(this@LogActivity, result, Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    private fun refresh() {
        // 设备配置信息（实时读取，始终显示）
        deviceInfoText.text = DeviceInfo.describe(this)
        logText.text = AppLog.dump().ifEmpty { getString(R.string.log_empty) }
        infoText.text = interfaceInfo()
    }

    /* ==================== 协议自检 ==================== */

    /**
     * 协议自检：注册 → 搜索 → 播放 → 解密，四段结果直接刷到日志区。
     *
     * 电视上排查「取不到流」时不用连 adb，按一下就能看出断在哪一段：
     * 注册失败说明设备身份没落盘；搜索失败是接口/签名层；播放失败是
     * video_model/v1 或 fallback_api；解密失败说明 key_seed/spade_a 链路走不通。
     */
    private fun runSelfCheck() {
        val btn = findViewById<TextView>(R.id.btn_selfcheck)
        if (!btn.isEnabled) return
        btn.isEnabled = false
        btn.text = "自检中…"
        logText.text = getString(R.string.log_selfcheck_running)
        scope.launch {
            val lines = withContext(Dispatchers.IO) { selfCheck() }
            logText.text = lines.joinToString("\n")
            lines.forEach { AppLog.d(SELF_CHECK_TAG, it) }
            btn.isEnabled = true
            btn.text = getString(R.string.log_selfcheck)
            Toast.makeText(this@LogActivity, "自检完成，结果见上方日志", Toast.LENGTH_SHORT).show()
        }
    }

    /** 四段自检，全部在 IO 线程执行；异常只记结果不抛出。 */
    private fun selfCheck(): List<String> {
        val out = ArrayList<String>()
        val client = HttpClientProvider.get()
        val net = NetClient(client)

        // ① 设备注册：所有播放请求都要带注册下发的 Cookie。
        // 自检是人工排查入口，未注册时强制重打一遍全部主机（跳过播放路径的失败冷却）。
        try {
            val r = if (DeviceRegister.registered()) DeviceRegister.ensureRegistered(client)
                    else DeviceRegister.registerNow(client)
            out.add("① 注册 ✅ device_id=${r.deviceId} install_id=${r.installId}")
        } catch (t: Throwable) {
            out.add("① 注册 ❌ ${t.javaClass.simpleName}: ${t.message} ${DeviceRegister.lastError()}".trim())
        }

        // ② 搜索：验证域名轮询 + 签名层可用
        var seriesId: String? = null
        try {
            val hits = net.search(SELF_CHECK_KEYWORD)
            val first = hits.firstOrNull()
            if (first != null) {
                seriesId = first.id
                out.add("② 搜索 ✅ ${hits.size} 条，首条「${first.title}」series_id=${first.id}")
            } else {
                out.add("② 搜索 ❌ 0 条")
            }
        } catch (t: Throwable) {
            out.add("② 搜索 ❌ ${t.javaClass.simpleName}: ${t.message}")
        }

        // ③ 播放：详情取 vid → video_model/v1 → fallback_api → video_list
        var model: NetClient.VideoModel? = null
        val sid = seriesId
        if (sid == null) {
            out.add("③ 播放 ⏭ 无 series_id，跳过")
        } else {
            try {
                val detail = net.detail(sid)
                val vid = detail.episodes.firstOrNull()?.vid
                    ?: throw IllegalStateException("没有可播集")
                val m = net.videoModel(vid, 1080)
                model = m
                val variants = if (m.variants.isEmpty()) {
                    "直读兜底 ${m.urls.size} 地址"
                } else {
                    m.variants.joinToString("，") {
                        "${it.height}p/${it.codec}" + if (it.encrypted) "(CENC)" else ""
                    }
                }
                out.add("③ 播放 ✅「${detail.title}」${detail.episodes.size} 集 / vid=$vid / ${m.duration}s / $variants")
            } catch (t: Throwable) {
                out.add("③ 播放 ❌ ${t.javaClass.simpleName}: ${t.message}")
            }
        }

        // ④ 解密：main_url 必须已由 key_seed 解成 http(s) 直链；CENC 时密钥必须 16 字节
        val m = model
        if (m == null) {
            out.add("④ 解密 ⏭ 未取到播放数据")
        } else {
            val url = m.urls.firstOrNull()
            val urlOk = url != null && (url.startsWith("https://") || url.startsWith("http://"))
            val keyOk = !m.encrypted || m.cencKey?.size == 16
            if (urlOk && keyOk) {
                out.add("④ 解密 ✅ ${hostOf(url!!)} / key=${m.cencKey?.size ?: 0}B"
                        + if (m.encrypted) " / CENC-AES-CTR" else " / 明文")
            } else {
                out.add("④ 解密 ❌ url=${url?.take(60) ?: "空"} / key=${m.cencKey?.size ?: 0}B")
            }
        }
        return out
    }

    private fun hostOf(url: String): String = try {
        java.net.URI(url).host ?: url.take(60)
    } catch (e: Exception) {
        url.take(60)
    }

    private fun interfaceInfo(): String = getString(
        R.string.log_interface_info,
        AppLog.baseUrl.ifEmpty { "-" },
        AppLog.deviceId.ifEmpty { "-" },
        AppLog.iid.ifEmpty { "-" },
        AppLog.lastGorgon.ifEmpty { "-" }
    )

    /** 组装完整日志报告：设备信息 + 全量日志（文件持久化）+ 接口信息 */
    private fun buildLogReport(): String {
        val logBody = AppLog.dumpFile().ifEmpty { getString(R.string.log_empty) }
        return "===== 设备信息 =====\n" +
                DeviceInfo.describe(this) + "\n\n" +
                "===== 日志 =====\n" + logBody + "\n\n" +
                "===== 接口信息 =====\n" + interfaceInfo()
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("日志", text))
    }

    private fun shareLog() {
        val report = buildLogReport()
        // ACTION_SEND 的 EXTRA_TEXT 走 Binder，超长（约 5000 字符以上）会被系统静默截断，
        // 超限时落盘为临时文件用流式附件分享，保证日志完整。
        if (report.length > 4000) {
            shareAsFile(report)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, report)
        }
        try {
            startActivity(Intent.createChooser(intent, "分享日志"))
        } catch (e: ActivityNotFoundException) {
            copyToClipboard(report)
            Toast.makeText(this, "未找到可分享的应用，日志已复制到剪贴板", Toast.LENGTH_LONG).show()
        }
    }

    /** 超长日志：写临时文件 + FileProvider 附件分享。 */
    private fun shareAsFile(report: String) {
        try {
            val dir = File(cacheDir, "logs").apply { mkdirs() }
            val file = File(dir, "hougtime-log.txt")
            file.writeText(report)
            val uri = FileProvider.getUriForFile(
                this, packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享日志文件"))
        } catch (e: Exception) {
            // 文件分享失败降级：直接复制到剪贴板（无长度限制）
            copyToClipboard(report)
            Toast.makeText(this, "日志较长已转为文件失败，已复制到剪贴板，请粘贴发送", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
