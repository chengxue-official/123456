package com.houg.tv

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.NetClient
import com.houg.tv.update.UpdateDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页（HOUG CINEMA 影院流）：
 * mast 品牌行 + RecyclerView 内容流（Hero / 继续观看 / 今日热播 / 新上架）+ 底部 dock。
 * 内容流与新上架共用一个 GridLayoutManager——整行区块 span=列数，
 * 网格卡片 span=1（见 HomeAdapter.gridStartPosition）。
 */
class MainActivity : AppCompatActivity() {

    /** 全局 density/字号缩放（1280×720dp 设计画布），见 UiScale.warp */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.warp(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: HomeAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var progress: View
    private lateinit var statusText: TextView

    private var nextOffset = 0
    private var sessionId = ""
    private var hasMore = false
    private var loading = false
    /** Hero 出现后把首焦点交给「立即播放」，只做一次 */
    private var heroFocused = false
    /** 今日热播只拉一次；设置里关掉再打开时补拉 */
    private var hotRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiScale.applyImmersive(this)
        setContentView(R.layout.activity_main)

        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        recycler = findViewById(R.id.recycler)

        applyPageInsets()

        adapter = HomeAdapter(
            onHeroPlay = { openDetail(it) },
            onToggleFavorite = { toggleFavorite(it) },
            onDramaClick = { openDetail(it) }
        )
        setupGrid()
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

        setupMast()
        setupDock()

        if (recEnabled(SettingsActivity.REC_CONTINUE)) {
            adapter.setContinue(App.watchHistory.list().take(3))
        }
        if (recEnabled(SettingsActivity.REC_HOT)) loadHot()
        if (recEnabled(SettingsActivity.REC_NEW)) loadPage(true)
    }

    /**
     * 页面安全边距：手机 24dp / TV 64dp（overscan 5% 安全区）。
     * 用代码设置而非资源限定符——AppCompat 重建 Resources 时会把 warp 强设的
     * uiMode 打回系统值，values-television 在 Activity 层不生效，见 UiScale.isTv。
     */
    private fun applyPageInsets() {
        val h = UiScale.pageH(this)
        findViewById<View>(R.id.mast).setPadding(h, UiScale.pageTop(this), h, 0)
        findViewById<View>(R.id.dock_row).setPadding(h, 0, h, 0)
        recycler.setPadding(h, 0, h, 0)
        if (!UiScale.isTv(this)) {
            // 原型 body.m：手机 mast 只留品牌 + 搜索，避免交流群文案把按钮挤出屏
            findViewById<View>(R.id.brand_sub).visibility = View.GONE
            findViewById<View>(R.id.community_hint).visibility = View.GONE
        }
    }

    /**
     * 网格装配：列数按可用宽与目标卡宽自动算（gridPlan），尺寸变化时重算并替换
     * layoutManager。区块整行 + 网格卡片混排，间距用自写 ItemDecoration——
     * Ui.GridSpacingDecoration 按 pos % spanCount 定列，遇到整行会错位。
     */
    private fun setupGrid() {
        recycler.layoutManager = GridLayoutManager(this, 6).apply {
            spanSizeLookup = spanLookup()
        }
        recycler.addItemDecoration(gridDecoration())
        // 布局过程里不能同步换 layoutManager，必须 post；手机冷启动会先横后竖测两遍
        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            recycler.post { recomputeSpan() }
        }
        recycler.post { recomputeSpan() }
    }

    private fun spanLookup() = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
            val span = (recycler.layoutManager as? GridLayoutManager)?.spanCount ?: 6
            return if (adapter.isGridRow(position)) 1 else span
        }
    }

    private fun gridDecoration(): RecyclerView.ItemDecoration {
        val gapPx = UiScale.gapPx(this)
        return object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
            ) {
                val pos = parent.getChildAdapterPosition(view)
                if (pos == RecyclerView.NO_POSITION) return
                val lm = parent.layoutManager as? GridLayoutManager ?: return
                val start = adapter.gridStartPosition()
                if (pos < start) return
                val idx = pos - start
                outRect.left = if (idx % lm.spanCount == 0) 0 else gapPx
                outRect.top = if (idx >= lm.spanCount) gapPx else 0
            }
        }
    }

    private fun recomputeSpan() {
        val avail = recycler.width - recycler.paddingLeft - recycler.paddingRight
        if (avail <= 0) return
        val gapPx = UiScale.gapPx(this)
        val targetPx = (UiScale.coverWidthDp(this) * resources.displayMetrics.density).toInt()
        val plan = gridPlan(avail, gapPx, targetPx, 2, 12)
        val lm = recycler.layoutManager as? GridLayoutManager
        if (lm == null || lm.spanCount != plan.columns) {
            recycler.layoutManager = GridLayoutManager(this, plan.columns).apply {
                spanSizeLookup = spanLookup()
            }
        }
    }

    /* ==================== mast ==================== */

    private fun setupMast() {
        findViewById<View>(R.id.btn_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.btn_update).setOnClickListener {
            UpdateDialog.start(this, scope)
        }
        // 焦点微放大：遥控器进出焦点时有反馈，避免「只有描边变化」的死板感
        listOf(R.id.btn_search, R.id.btn_update)
            .forEach { attachFocusScale(findViewById(it)) }
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

    /* ==================== dock ==================== */

    private fun setupDock() {
        val actions = mapOf<String, () -> Unit>(
            "home" to { recycler.smoothScrollToPosition(0) },
            "category" to { startActivity(Intent(this, CategoryActivity::class.java)) },
            "rank" to { startActivity(Intent(this, RankActivity::class.java)) },
            "search" to { startActivity(Intent(this, SearchActivity::class.java)) },
            "fav" to {
                startActivity(
                    Intent(this, HistoryActivity::class.java)
                        .putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_FAVORITE)
                )
            },
            "history" to {
                startActivity(
                    Intent(this, HistoryActivity::class.java)
                        .putExtra(HistoryActivity.EXTRA_TAB, HistoryActivity.TAB_HISTORY)
                )
            },
            // 设置入口：播放偏好 / 外观形态 / 内容推荐 / 系统高级
            "settings" to { startActivity(Intent(this, SettingsActivity::class.java)) }
        )
        data class DockEntry(val key: String, val itemId: Int, val labelId: Int, val barId: Int)
        listOf(
            DockEntry("home", R.id.dock_home, R.id.dock_home_label, R.id.dock_home_bar),
            DockEntry("category", R.id.dock_category, R.id.dock_category_label, R.id.dock_category_bar),
            DockEntry("rank", R.id.dock_rank, R.id.dock_rank_label, R.id.dock_rank_bar),
            DockEntry("search", R.id.dock_search, R.id.dock_search_label, R.id.dock_search_bar),
            DockEntry("fav", R.id.dock_fav, R.id.dock_fav_label, R.id.dock_fav_bar),
            DockEntry("history", R.id.dock_history, R.id.dock_history_label, R.id.dock_history_bar),
            DockEntry("settings", R.id.dock_settings, R.id.dock_settings_label, R.id.dock_settings_bar)
        ).forEach { entry ->
            val item = findViewById<View>(entry.itemId)
            val label = item.findViewById<TextView>(entry.labelId)
            val bar = item.findViewById<View>(entry.barId)
            item.setOnClickListener { actions[entry.key]?.invoke() }
            item.setOnFocusChangeListener { _, hasFocus ->
                label.setTextColor(
                    ContextCompat.getColor(
                        this, if (hasFocus) R.color.bone else R.color.faint
                    )
                )
                // 首页是当前页，指示条常亮
                bar.visibility =
                    if (hasFocus || entry.key == "home") View.VISIBLE else View.INVISIBLE
            }
        }
    }

    /* ==================== 数据 ==================== */

    private fun loadPage(reset: Boolean) {
        if (loading) return
        if (!recEnabled(SettingsActivity.REC_NEW)) return
        loading = true
        val offset = if (reset) 0 else nextOffset
        val session = if (reset) "" else sessionId

        progress.visibility = View.VISIBLE
        statusText.visibility = View.GONE

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    // 首页「新上架」固定用真人剧最新流（原型同款数据源）
                    App.netClient.catalog("short_play", "default", offset, session)
                }
                if (reset) {
                    adapter.submitCatalog(result.dramas)
                    adapter.setHero(result.dramas.firstOrNull())
                    recycler.scrollToPosition(0)
                    focusHeroPlay()
                } else {
                    adapter.appendCatalog(result.dramas)
                }
                nextOffset = result.nextOffset
                sessionId = result.sessionId
                hasMore = result.hasMore
                if (adapter.currentCatalog().isEmpty()) {
                    statusText.text = getString(R.string.empty)
                    statusText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                if (adapter.currentCatalog().isEmpty()) {
                    statusText.text = getString(R.string.load_failed)
                    statusText.visibility = View.VISIBLE
                }
            } finally {
                progress.visibility = View.GONE
                loading = false
            }
        }
    }

    /** 今日热播：用热播榜第一页前 5 名；失败静默，不挡首页主体 */
    private fun loadHot() {
        if (hotRequested) return
        if (!recEnabled(SettingsActivity.REC_HOT)) return
        hotRequested = true
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    App.netClient.ranking(NetClient.RANK_BOARDS[0], 1)
                }
                adapter.setHot(page.items.take(5))
            } catch (e: Exception) {
                // 忽略：热播区块缺位可接受
            }
        }
    }

    private fun toggleFavorite(drama: NetClient.Drama) {
        val on = App.watchHistory.toggleFavorite(drama.id, drama.title, drama.cover)
        Toast.makeText(
            this,
            getString(if (on) R.string.favorite_added else R.string.favorite_removed),
            Toast.LENGTH_SHORT
        ).show()
        adapter.refreshHeroFavorite()
    }

    private fun openDetail(drama: NetClient.Drama) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_SERIES_ID, drama.id)
                .putExtra(DetailActivity.EXTRA_TITLE, drama.title)
                .putExtra(DetailActivity.EXTRA_COVER, drama.cover)
        )
    }

    /** Hero 到位后把首焦点交给「立即播放」（TV 遥控直达主行动） */
    private fun focusHeroPlay() {
        if (heroFocused) return
        heroFocused = true
        recycler.post {
            val vh = recycler.findViewHolderForAdapterPosition(0) as? HomeAdapter.HeroVH
            vh?.itemView?.findViewById<View>(R.id.hero_play)?.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        UiScale.applyImmersive(this)
        // 详情页可能改了进度/收藏，设置页可能改了推荐开关，回来统一刷新
        applyRecPreferences()
    }

    /* ==================== 内容推荐开关 ==================== */

    private fun uiPrefs() = getSharedPreferences(SettingsActivity.UI_PREFS, MODE_PRIVATE)

    private fun recEnabled(key: String) = uiPrefs().getBoolean(key, true)

    /**
     * 设置页「内容推荐」三个开关回到前台时生效：
     * 继续观看只看历史；热播/新上架关掉就清空且不再请求，重新打开补一次。
     */
    private fun applyRecPreferences() {
        adapter.setContinue(
            if (recEnabled(SettingsActivity.REC_CONTINUE)) {
                App.watchHistory.list().take(3)
            } else {
                emptyList()
            }
        )

        if (!recEnabled(SettingsActivity.REC_HOT)) {
            adapter.setHot(emptyList())
            hotRequested = false
        } else if (!hotRequested) {
            loadHot()
        }

        if (!recEnabled(SettingsActivity.REC_NEW)) {
            if (adapter.currentCatalog().isNotEmpty()) {
                adapter.submitCatalog(emptyList())
                adapter.setHero(null)
                nextOffset = 0
                sessionId = ""
                hasMore = false
                statusText.visibility = View.GONE
            }
        } else if (adapter.currentCatalog().isEmpty() && !loading) {
            loadPage(true)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
            .setMessage("确定退出HG短剧吗？")
            .setPositiveButton("退出") { _, _ -> finish() }
            .setNegativeButton("取消", null)
            .setCancelable(true)
            .show()
    }
}
