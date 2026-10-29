package com.houg.tv

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.houg.tv.net.HttpClientProvider
import com.houg.tv.sign.DeviceRegister
import com.houg.tv.update.UpdateDialog
import com.houg.tv.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页（HOUG CINEMA）：四分页（播放交互 / 外观导航 / 内容推荐 / 系统高级）。
 * 播放偏好写 player_prefs（键与 PlayerActivity 共用，s6 起播时读取）；
 * 推荐开关写 houg_ui（MainActivity.onResume 里 applyRecPreferences 生效）；
 * 外观形态走 UiScale.setUiMode + recreate。
 */
class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(UiScale.warp(newBase))

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiScale.applyImmersive(this)
        setContentView(R.layout.activity_settings)

        applyPageInsets()

        tabs = listOf(
            findViewById(R.id.tab_play),
            findViewById(R.id.tab_look),
            findViewById(R.id.tab_rec),
            findViewById(R.id.tab_sys)
        )
        pages = listOf(
            findViewById(R.id.page_play),
            findViewById(R.id.page_look),
            findViewById(R.id.page_rec),
            findViewById(R.id.page_sys)
        )
        tabs.forEachIndexed { i, tab ->
            tab.setOnClickListener { showTab(i) }
        }
        showTab(0)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        bindPlay()
        bindLook()
        bindRec()
        bindSys()
    }

    /** 横向安全边距：与首页一致，TV overscan 安全区（代码注入，不用限定符） */
    private fun applyPageInsets() {
        val h = UiScale.pageH(this)
        findViewById<View>(R.id.settings_top).setPadding(h, UiScale.pageTop(this), h, 0)
        findViewById<View>(R.id.tab_scroll).setPadding(h, 0, h, 0)
        findViewById<View>(R.id.settings_body).setPadding(h, dp(14), h, dp(28))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showTab(index: Int) {
        pages.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t -> t.isSelected = i == index }
        // 切页后回到顶部，避免上个页的滚动位置留下大片空白
        findViewById<View>(R.id.settings_scroll).scrollTo(0, 0)
    }

    /* ==================== 播放交互 ==================== */

    private fun playerPrefs() = getSharedPreferences(PlayerActivity.PREFS, MODE_PRIVATE)

    private fun bindPlay() {
        val qualityRes = intArrayOf(
            R.string.quality_auto, R.string.quality_480, R.string.quality_720,
            R.string.quality_1080, R.string.quality_origin
        )
        val aspectRes = intArrayOf(
            R.string.aspect_fit, R.string.aspect_stretch,
            R.string.aspect_original, R.string.aspect_fill
        )
        val seekSteps = longArrayOf(10_000L, 15_000L, 30_000L, 60_000L)
        val prefs = playerPrefs()

        // 清晰度
        val optQuality = findViewById<TextView>(R.id.opt_quality)
        fun renderQuality() {
            val idx = prefs.getInt(PlayerActivity.PREF_QUALITY, PlayerActivity.QUALITY_AUTO)
            optQuality.text = getString(qualityRes[idx.coerceIn(0, qualityRes.size - 1)])
        }
        renderQuality()
        optQuality.setOnClickListener {
            val cur = prefs.getInt(PlayerActivity.PREF_QUALITY, PlayerActivity.QUALITY_AUTO)
            pickOption(getString(R.string.menu_quality), qualityRes.map { getString(it) }.toTypedArray(), cur) { which ->
                prefs.edit().putInt(PlayerActivity.PREF_QUALITY, which).apply()
                renderQuality()
            }
        }

        // 解码方式
        val optDecoder = findViewById<TextView>(R.id.opt_decoder)
        fun renderDecoder() {
            val soft = prefs.getBoolean(PlayerActivity.PREF_DECODER_SOFT, false)
            optDecoder.text = getString(if (soft) R.string.menu_decoder_soft else R.string.menu_decoder_hard)
        }
        renderDecoder()
        optDecoder.setOnClickListener {
            val soft = prefs.getBoolean(PlayerActivity.PREF_DECODER_SOFT, false)
            val options = arrayOf(getString(R.string.menu_decoder_hard), getString(R.string.menu_decoder_soft))
            pickOption(getString(R.string.menu_decoder), options, if (soft) 1 else 0) { which ->
                prefs.edit().putBoolean(PlayerActivity.PREF_DECODER_SOFT, which == 1).apply()
                renderDecoder()
            }
        }

        // 默认倍速
        val optSpeed = findViewById<TextView>(R.id.opt_speed)
        fun renderSpeed() {
            val idx = prefs.getInt(PlayerActivity.PREF_SPEED, 2).coerceIn(0, PlayerActivity.SPEEDS.size - 1)
            optSpeed.text = "${PlayerActivity.SPEEDS[idx]}×"
        }
        renderSpeed()
        optSpeed.setOnClickListener {
            val cur = prefs.getInt(PlayerActivity.PREF_SPEED, 2)
            val options = PlayerActivity.SPEEDS.map { "${it}×" }.toTypedArray()
            pickOption(getString(R.string.set_speed_label), options, cur) { which ->
                prefs.edit().putInt(PlayerActivity.PREF_SPEED, which).apply()
                renderSpeed()
            }
        }

        // 快进步长
        val optSeek = findViewById<TextView>(R.id.opt_seek)
        fun renderSeek() {
            val step = prefs.getLong(PlayerActivity.PREF_SEEK_STEP, 15_000L)
            optSeek.text = getString(R.string.set_seek_fmt, (step / 1000).toInt())
        }
        renderSeek()
        optSeek.setOnClickListener {
            val cur = seekSteps.indexOf(prefs.getLong(PlayerActivity.PREF_SEEK_STEP, 15_000L)).coerceAtLeast(0)
            val options = seekSteps.map { getString(R.string.set_seek_fmt, (it / 1000).toInt()) }.toTypedArray()
            pickOption(getString(R.string.set_seek_label), options, cur) { which ->
                prefs.edit().putLong(PlayerActivity.PREF_SEEK_STEP, seekSteps[which]).apply()
                renderSeek()
            }
        }

        // 画幅
        val optAspect = findViewById<TextView>(R.id.opt_aspect)
        fun renderAspect() {
            val idx = prefs.getInt(PlayerActivity.PREF_ASPECT, PlayerActivity.ASPECT_FIT)
            optAspect.text = getString(aspectRes[idx.coerceIn(0, aspectRes.size - 1)])
        }
        renderAspect()
        optAspect.setOnClickListener {
            val cur = prefs.getInt(PlayerActivity.PREF_ASPECT, PlayerActivity.ASPECT_FIT)
            pickOption(getString(R.string.set_aspect_label), aspectRes.map { getString(it) }.toTypedArray(), cur) { which ->
                prefs.edit().putInt(PlayerActivity.PREF_ASPECT, which).apply()
                renderAspect()
            }
        }

        // 自动连播 / 弹幕
        bindSwitch(R.id.sw_auto_next, { prefs.getBoolean(PlayerActivity.PREF_AUTO_NEXT, true) }) {
            prefs.edit().putBoolean(PlayerActivity.PREF_AUTO_NEXT, it).apply()
        }
        bindSwitch(R.id.sw_danmaku, { prefs.getBoolean(PlayerActivity.PREF_DANMAKU, true) }) {
            prefs.edit().putBoolean(PlayerActivity.PREF_DANMAKU, it).apply()
        }
    }

    /* ==================== 外观导航 ==================== */

    private fun bindLook() {
        val opt = findViewById<TextView>(R.id.opt_uimode)
        val labels = arrayOf(
            getString(R.string.set_ui_auto), getString(R.string.set_ui_tv), getString(R.string.set_ui_phone)
        )
        // MODE_* 是字符串常量（"auto"/"tv"/"phone"），按同序数组取值
        val modes = arrayOf(UiScale.MODE_AUTO, UiScale.MODE_TV, UiScale.MODE_PHONE)

        fun render() {
            val cur = UiScale.uiMode(this@SettingsActivity)
            opt.text = labels[modes.indexOf(cur).coerceAtLeast(0)]
        }
        render()
        opt.setOnClickListener {
            val cur = UiScale.uiMode(this@SettingsActivity)
            pickOption(getString(R.string.set_uimode_label), labels, modes.indexOf(cur).coerceAtLeast(0)) { which ->
                if (modes[which] != cur) {
                    UiScale.setUiMode(this@SettingsActivity, modes[which])
                    recreate()
                }
            }
        }
    }

    /* ==================== 内容推荐 ==================== */

    private fun uiPrefs() = getSharedPreferences(UI_PREFS, MODE_PRIVATE)

    private fun bindRec() {
        val p = uiPrefs()
        bindSwitch(R.id.sw_rec_continue, { p.getBoolean(REC_CONTINUE, true) }) {
            p.edit().putBoolean(REC_CONTINUE, it).apply()
        }
        bindSwitch(R.id.sw_rec_hot, { p.getBoolean(REC_HOT, true) }) {
            p.edit().putBoolean(REC_HOT, it).apply()
        }
        bindSwitch(R.id.sw_rec_new, { p.getBoolean(REC_NEW, true) }) {
            p.edit().putBoolean(REC_NEW, it).apply()
        }
    }

    /* ==================== 系统高级 ==================== */

    private fun bindSys() {
        // 版本 + 页脚
        val (name, code) = versionInfo()
        findViewById<TextView>(R.id.tv_version).text = getString(R.string.set_version_fmt, name, code)
        findViewById<TextView>(R.id.tv_footer).text = getString(R.string.set_ver_footer_fmt, name, code)

        // 清空三连
        findViewById<View>(R.id.link_clear_history).setOnClickListener {
            confirm(getString(R.string.clear_history_confirm)) {
                App.watchHistory.clearHistory()
                toast(R.string.cleared)
            }
        }
        findViewById<View>(R.id.link_clear_fav).setOnClickListener {
            confirm(getString(R.string.set_clear_fav_confirm)) {
                App.watchHistory.clearFavorites()
                toast(R.string.cleared)
            }
        }
        findViewById<View>(R.id.link_clear_search).setOnClickListener {
            confirm(getString(R.string.clear_search_history_confirm)) {
                App.searchHistory.clear()
                toast(R.string.cleared)
            }
        }

        // 设备身份
        refreshDevid()
        val btnReset = findViewById<TextView>(R.id.link_reset_identity)
        btnReset.setOnClickListener {
            confirm(getString(R.string.set_reset_confirm)) { resetIdentity(btnReset) }
        }

        // 关于
        findViewById<View>(R.id.link_update).setOnClickListener {
            UpdateDialog.start(this, scope)
        }
        findViewById<View>(R.id.link_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
    }

    private fun versionInfo(): Pair<String, Long> = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        (pi.versionName ?: "-") to code
    } catch (e: Exception) {
        "-" to 0L
    }

    private fun refreshDevid() {
        findViewById<TextView>(R.id.tv_devid).text =
            getString(R.string.set_devid_fmt, AppLog.deviceId.ifEmpty { "-" })
    }

    /** 与 LogActivity.resetIdentity 同款：清特征 → 立即重注册 */
    private fun resetIdentity(btn: TextView) {
        if (!btn.isEnabled) return
        btn.isEnabled = false
        btn.text = getString(R.string.set_reset_doing)
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
            btn.text = getString(R.string.set_reset_action)
            refreshDevid()
            Toast.makeText(this@SettingsActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    /* ==================== 通用控件 ==================== */

    /** 单选胶囊：AlertDialog 与首页现有弹窗同风格 */
    private fun pickOption(title: String, options: Array<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, checked) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun confirm(message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk() }
            .setNegativeButton(getString(R.string.cancel), null)
            .setCancelable(true)
            .show()
    }

    /**
     * 自定义开关：外层 settings_sw_focus 提供遥控焦点框，
     * 内部轨道切 switch_track_on/off，滑块 translationX 平移 + tint 变色。
     */
    private fun bindSwitch(rootId: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        val root = findViewById<ViewGroup>(rootId)
        val track = root.getChildAt(0) as ViewGroup
        val knob = track.getChildAt(0)
        val travel = (30 * resources.displayMetrics.density).toInt()
        fun render(on: Boolean) {
            track.setBackgroundResource(
                if (on) R.drawable.switch_track_on else R.drawable.switch_track_off
            )
            knob.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (on) R.color.accent else R.color.switch_knob_off)
            )
            knob.animate().translationX(if (on) travel.toFloat() else 0f).setDuration(140).start()
        }
        render(get())
        root.setOnClickListener {
            val next = !get()
            set(next)
            render(next)
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** UiScale 的偏好文件名（UiScale.PREFS 是 private，这里镜像一份） */
        const val UI_PREFS = "houg_ui"
        /** 内容推荐开关：MainActivity.onResume 里按这三个键门控各区块 */
        const val REC_CONTINUE = "rec_continue"
        const val REC_HOT = "rec_hot"
        const val REC_NEW = "rec_new"
    }
}
