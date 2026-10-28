package com.houg.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.NetClient
import com.houg.tv.update.UpdateChecker
import com.houg.tv.update.UpdateDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首页：真人剧 / 漫剧 / AI剧 三个分类 + 搜索 / 追剧入口 */
class MainActivity : AppCompatActivity() {

    /** 全局 density/字号缩放（1280×720dp 设计画布），见 UiScale.warp */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.warp(newBase))
    }

    private data class Genre(val key: String, val scene: String, val label: String)

    /**
     * 页面安全边距：手机 24dp / TV 64dp（overscan 5% 安全区）。
     * 用代码设置而非资源限定符——AppCompat 重建 Resources 时会把 warp 强设的
     * uiMode 打回系统值，values-television 在 Activity 层不生效，见 UiScale.isTv。
     */
    private fun applyPageInsets() {
        val h = UiScale.pageH(this)
        val header = findViewById<View>(R.id.header)
        header.setPadding(h, UiScale.pageTop(this), h, header.paddingBottom)
        listOf(R.id.tab_bar, R.id.nav_row).forEach { id ->
            val v = findViewById<View>(id)
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            lp.marginStart = h
            v.layoutParams = lp
        }
        recycler.setPadding(h, recycler.paddingTop, h, recycler.paddingBottom)
        if (!UiScale.isTv(this)) fitNavCards(h)
    }

    /**
     * 手机竖屏可用宽度只有约 363dp（411dp - 两侧 24dp 边距），4 张 wrap_content
     * 导航卡（padding 20dp + 图标 + 文字 + 12dp 间距）合计约 452dp，
     * 末尾「分类」被右边缘裁掉（QA Bug-01）。改成等分满宽 + 紧凑内边距，
     * 一屏放下不再滚动；TV 上内容能放下，保持原样。
     */
    private fun fitNavCards(h: Int) {
        fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
        val row = findViewById<LinearLayout>(R.id.nav_row)
        row.layoutParams = row.layoutParams.apply {
            width = resources.displayMetrics.widthPixels - 2 * h
        }
        listOf(R.id.btn_history, R.id.btn_favorite, R.id.btn_rank, R.id.btn_category)
            .forEachIndexed { i, id ->
                val card = findViewById<LinearLayout>(id)
                card.setPadding(dp(12), 0, dp(12), 0)
                val lp = card.layoutParams as LinearLayout.LayoutParams
                lp.width = 0
                lp.weight = 1f
                lp.marginStart = if (i == 0) 0 else dp(8)
                card.layoutParams = lp
                val label = card.getChildAt(1)
                (label.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
                label.layoutParams = label.layoutParams
            }
    }

    private val genres = listOf(
        Genre("short_play", "default", "真人剧"),
        Genre("comic_series", "comic_series", "漫剧"),
        Genre("ai_series", "ai_series", "AI剧")
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: DramaAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var progress: View
    private lateinit var statusText: TextView

    private var currentGenre = 0
    private var nextOffset = 0
    private var sessionId = ""
    private var hasMore = false
    private var loading = false
    /** 请求代次：快速切分类时丢弃过期响应，避免数据错位 */
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()
        setContentView(R.layout.activity_main)

        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        recycler = findViewById(R.id.recycler)

        applyPageInsets()

        adapter = DramaAdapter { drama -> openDetail(drama) }
        installAutoGrid(recycler, UiScale.coverWidthDp(this), 2, 10)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                val last = lm.findLastVisibleItemPosition()
                if (last >= adapter.itemCount - 6 && hasMore && !loading) {
                    loadPage(false)
                }
            }
        })

        setupTabs()
        setupNavButtons()

        selectGenre(0)
        silentCheckUpdate()
    }

    /**
     * 启动时静默检查更新：只在「确实有新版本」时才弹窗，检查失败/已最新都静默，
     * 避免每次开机打扰用户。用户也可随时点「检查更新」手动触发并看到结果。
     */
    private fun silentCheckUpdate() {
        scope.launch {
            val current = try {
                packageManager.getPackageInfo(packageName, 0).versionCode
            } catch (e: Exception) {
                -1
            }
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(this@MainActivity, current) }
            if (result.hasUpdate) UpdateDialog.start(this@MainActivity, scope)
        }
    }

    private fun setupTabs() {
        val tabs = listOf(
            findViewById<TextView>(R.id.tab_real),
            findViewById<TextView>(R.id.tab_comic),
            findViewById<TextView>(R.id.tab_ai)
        )
        tabs.forEachIndexed { index, tab ->
            tab.setOnClickListener { selectGenre(index) }
            tab.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.08f else 1f)
                    .scaleY(if (hasFocus) 1.08f else 1f)
                    .setDuration(140)
                    .start()
                v.translationZ = if (hasFocus) 8f else 0f
            }
        }
        // 首焦点落在首个分类胶囊（与参考图一致），而非右上角工具图标。
        // 用 post 等首帧布局完成后再请求，否则会被父容器的默认焦点搜索覆盖。
        tabs.first().post { tabs.first().requestFocus() }
    }

    private fun setupNavButtons() {
        findViewById<View>(R.id.btn_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.btn_history).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java)
                .putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_HISTORY))
        }
        findViewById<View>(R.id.btn_favorite).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java)
                .putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_FAVORITE))
        }
        findViewById<View>(R.id.btn_rank).setOnClickListener {
            startActivity(Intent(this, RankActivity::class.java))
        }
        findViewById<View>(R.id.btn_category).setOnClickListener {
            startActivity(Intent(this, CategoryActivity::class.java))
        }
        findViewById<View>(R.id.btn_log).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<View>(R.id.btn_update).setOnClickListener {
            UpdateDialog.start(this, scope)
        }
        setupUiModeButton()

        // 焦点微放大：遥控器进出焦点时有反馈，避免「只有描边变化」的死板感
        listOf(R.id.btn_search, R.id.btn_history, R.id.btn_favorite,
            R.id.btn_rank, R.id.btn_category, R.id.btn_log, R.id.btn_update)
            .forEach { attachFocusScale(findViewById(it)) }
    }

    /**
     * 顶部边界中间的 UI 形态兜底按钮：电视被误判成手机时（VIDAA 等非 Android TV
     * 系统自动判不出），从这里手动切「手机/电视」形态。选择持久化后重启生效
     * （isTv 影响 attachBaseContext 的 density warp 和窗口方向，必须重建进程）。
     */
    private fun setupUiModeButton() {
        val btn = findViewById<TextView>(R.id.btn_uimode)
        val mode = UiScale.uiMode(this)
        btn.text = when (mode) {
            UiScale.MODE_TV -> "界面：电视"
            UiScale.MODE_PHONE -> "界面：手机"
            else -> "界面：自动"
        }
        btn.setOnClickListener {
            val options = arrayOf("自动检测", "电视界面", "手机界面")
            val values = arrayOf(UiScale.MODE_AUTO, UiScale.MODE_TV, UiScale.MODE_PHONE)
            val checked = values.indexOf(mode).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("切换界面形态")
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    dialog.dismiss()
                    if (values[which] != mode) {
                        UiScale.setUiMode(this, values[which])
                        // 重建当前页：isTv 同时影响 attachBaseContext 的 density warp、
                        // 窗口方向与布局选择，recreate 会重走这两步，无需杀进程。
                        recreate()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /** 聚焦放大到 1.06 倍、失焦回落，并置顶避免被相邻卡片压住 */
    private fun attachFocusScale(view: View) {
        view.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(140)
                .start()
            v.translationZ = if (hasFocus) 8f else 0f
        }
    }

    private fun selectGenre(index: Int) {
        currentGenre = index
        generation++
        val tabs = listOf(
            findViewById<TextView>(R.id.tab_real),
            findViewById<TextView>(R.id.tab_comic),
            findViewById<TextView>(R.id.tab_ai)
        )
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
        // 允许新分类立即发起请求，旧请求由 generation 校验丢弃
        loading = false
        loadPage(true)
    }

    private fun loadPage(reset: Boolean) {
        if (loading) return
        loading = true
        val gen = generation
        val genre = genres[currentGenre]
        val offset = if (reset) 0 else nextOffset
        val session = if (reset) "" else sessionId

        progress.visibility = View.VISIBLE
        statusText.visibility = View.GONE

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    App.netClient.catalog(genre.key, genre.scene, offset, session)
                }
                if (gen != generation) return@launch
                if (reset) {
                    adapter.submit(result.dramas)
                } else {
                    adapter.append(result.dramas)
                }
                nextOffset = result.nextOffset
                sessionId = result.sessionId
                hasMore = result.hasMore
                if (adapter.itemCount == 0) {
                    statusText.text = getString(R.string.empty)
                    statusText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                if (gen != generation) return@launch
                if (adapter.itemCount == 0) {
                    statusText.text = getString(R.string.load_failed)
                    statusText.visibility = View.VISIBLE
                }
            } finally {
                if (gen == generation) {
                    progress.visibility = View.GONE
                    loading = false
                }
            }
        }
    }

    private fun openDetail(drama: NetClient.Drama) {
        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(DetailActivity.EXTRA_SERIES_ID, drama.id)
        intent.putExtra(DetailActivity.EXTRA_TITLE, drama.title)
        intent.putExtra(DetailActivity.EXTRA_COVER, drama.cover)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        configureImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) configureImmersive()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** 全屏沉浸（兼容 API 14）；方向按设备：TV 横屏 / 手机竖屏 */
    private fun configureImmersive() {
        UiScale.applyImmersive(this)
    }

    /** 首页返回键：弹确认退出，避免误触直接退出软件 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showExitConfirm()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showExitConfirm() {
        AlertDialog.Builder(this)
            .setTitle("退出")
            .setMessage("确定退出红果短剧吗？")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .setCancelable(true)
            .show()
    }
}
