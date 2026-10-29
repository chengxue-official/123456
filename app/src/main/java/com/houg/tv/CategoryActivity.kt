package com.houg.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.NetClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 分类页：真人剧 / 漫剧 / AI剧 三大类 + 六维筛选（主题 / 设定 / 背景 / 推荐 / 受众 / 时间）。
 * 筛选行由服务端 {@code selector_rows} 下发，筛选项通过 {@code select_items} 回传；
 * 数据只走 App 接口，不做网页兜底（网页版不支持筛选，静默降级会让筛选看起来失效）。
 */
class CategoryActivity : AppCompatActivity() {

    /** 全局 density/字号缩放（1280×720dp 设计画布），见 UiScale.warp */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.warp(newBase))
    }

    private data class Genre(val key: String, val scene: String, val label: String)

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
    private lateinit var filterContainer: LinearLayout
    private lateinit var tabs: List<TextView>

    /** 已选筛选项：type → id（「全部」= 该维度不出现在 map 里） */
    private val filters = HashMap<String, String>()

    private var currentGenre = 0
    private var nextOffset = 0
    private var sessionId = ""
    private var hasMore = false
    private var loading = false
    /** 请求代次：切分类 / 换筛选时丢弃过期响应 */
    private var generation = 0
    private var autoRetry = 0
    /** 下一次请求是否需要重新拉筛选行（首次进入 / 切大类时需要） */
    private var needSelector = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()
        // 布局按设备形态选：TV 版带 106dp overscan 安全区，手机版 24dp 边距
        setContentView(
            if (UiScale.isTv(this)) R.layout.activity_category
            else R.layout.activity_category_phone
        )
        // 手机端触控目标：32–44dp 按钮/筛选胶囊抬到 48dp（TV 不生效）
        UiScale.enforceTouchTargets(findViewById(android.R.id.content))

        progress = findViewById(R.id.category_progress)
        statusText = findViewById(R.id.category_status)
        filterContainer = findViewById(R.id.filter_container)
        recycler = findViewById(R.id.category_list)

        adapter = DramaAdapter { drama -> openDetail(drama) }
        installAutoGrid(recycler, UiScale.coverWidthDp(this), 2, 10)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6 && hasMore && !loading) {
                    loadPage(false)
                }
            }
        })

        setupTabs()
        val back = findViewById<TextView>(R.id.category_back)
        back.setOnClickListener { finish() }

        selectGenre(0)
    }

    private fun setupTabs() {
        tabs = listOf(
            findViewById(R.id.tab_real),
            findViewById(R.id.tab_comic),
            findViewById(R.id.tab_ai)
        )
        tabs.forEachIndexed { index, tab ->
            tab.text = genres[index].label
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
        // 首焦点落在首个大类胶囊（与首页一致）
        tabs.first().post { tabs.first().requestFocus() }
    }

    private fun selectGenre(index: Int) {
        if (currentGenre == index && adapter.itemCount > 0 && !needSelector) return
        currentGenre = index
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
        // 主题/设定/背景的 cate_* id 按大类下发，切大类必须清掉；推荐/受众/时间是通用值，保留
        filters.remove("category_dim_theme")
        filters.remove("category_dim_role")
        filters.remove("category_dim_epoch")
        needSelector = true
        loading = false
        autoRetry = 0
        loadPage(true)
    }

    private fun onFilterSelected(type: String, id: String) {
        if (id.isEmpty()) filters.remove(type) else filters[type] = id
        needSelector = false
        loading = false
        autoRetry = 0
        loadPage(true)
    }

    private fun loadPage(reset: Boolean) {
        if (loading && !reset) return
        loading = true
        generation++
        val gen = generation
        val genre = genres[currentGenre]
        val offset = if (reset) 0 else nextOffset
        val session = if (reset) "" else sessionId
        val wantSelector = reset && needSelector

        progress.visibility = View.VISIBLE
        if (reset) {
            statusText.visibility = View.GONE
            adapter.submit(emptyList())
        }

        scope.launch {
            var failed = false
            try {
                val result = withContext(Dispatchers.IO) {
                    App.netClient.category(
                        genre.key, genre.scene, offset, session, filters, wantSelector
                    )
                }
                if (gen != generation) return@launch
                if (reset) adapter.submit(result.dramas) else adapter.append(result.dramas)
                nextOffset = result.nextOffset
                sessionId = result.sessionId
                hasMore = result.hasMore
                if (wantSelector && result.filterRows.isNotEmpty()) {
                    renderFilterRows(result.filterRows)
                    needSelector = false
                }
            } catch (e: Exception) {
                if (gen != generation) return@launch
                failed = true
            }
            if (gen != generation) return@launch
            progress.visibility = View.GONE
            loading = false
            if (reset && adapter.itemCount == 0 && autoRetry < MAX_AUTO_RETRIES) {
                // 首屏为空：短暂等待后自动再来一次，用户不用手动退出重进
                autoRetry++
                progress.visibility = View.VISIBLE
                delay(AUTO_RETRY_DELAY_MS)
                if (gen == generation && !isFinishing) loadPage(true)
                return@launch
            }
            if (!failed) autoRetry = 0
            if (adapter.itemCount == 0) {
                statusText.text = getString(
                    if (failed) R.string.category_failed else R.string.category_empty
                )
                statusText.visibility = View.VISIBLE
            }
        }
    }

    /** 按服务端下发的行顺序渲染：【主题】【设定】【背景】【推荐】【受众】【时间】 */
    private fun renderFilterRows(rows: List<NetClient.FilterRow>) {
        filterContainer.removeAllViews()
        val allLabel = getString(R.string.filter_all)
        rows.forEach { row ->
            val item = layoutInflater.inflate(R.layout.view_filter_row, filterContainer, false)
            item.findViewById<TextView>(R.id.filter_label).text = row.label
            val chips = item.findViewById<LinearLayout>(R.id.filter_chips)
            val selected = filters[row.type] ?: ""
            // 「全部」胶囊由 UI 插入（id 空串），服务端不下发
            addChip(chips, row.type, "", allLabel, selected.isEmpty())
            row.options.forEach { option ->
                addChip(chips, row.type, option.id, option.label, option.id == selected)
            }
            filterContainer.addView(item)
        }
        filterContainer.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun addChip(
        parent: LinearLayout,
        type: String,
        id: String,
        label: String,
        selected: Boolean
    ) {
        val chip = layoutInflater.inflate(R.layout.item_filter_chip, parent, false) as TextView
        // 手机版必须降级 focusableInTouchMode（同各 Adapter 的处理）。这些胶囊是手工
        // inflate 的、不走 Adapter，漏掉就会踩 View.onTouchEvent 的 focusTaken 分支：
        // 第一次点击只把焦点要过去、performClick 被跳过，表现为"要点两下才生效"。
        UiScale.softenTouchFocus(chip)
        chip.text = label
        chip.tag = id
        chip.isSelected = selected
        chip.setOnClickListener { v ->
            if ((filters[type] ?: "") == id) return@setOnClickListener
            (v.parent as? LinearLayout)?.let { applyChipSelection(it, id) }
            onFilterSelected(type, id)
        }
        parent.addView(chip)
    }

    private fun applyChipSelection(parent: LinearLayout, selectedId: String) {
        for (i in 0 until parent.childCount) {
            parent.getChildAt(i).isSelected = (parent.getChildAt(i).tag as? String) == selectedId
        }
    }

    private fun openDetail(drama: NetClient.Drama) {
        startActivity(Intent(this, DetailActivity::class.java)
            .putExtra(DetailActivity.EXTRA_SERIES_ID, drama.id)
            .putExtra(DetailActivity.EXTRA_TITLE, drama.title)
            .putExtra(DetailActivity.EXTRA_COVER, drama.cover))
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun configureImmersive() {
        UiScale.applyImmersive(this)
    }

    companion object {
        private const val MAX_AUTO_RETRIES = 1
        private const val AUTO_RETRY_DELAY_MS = 1200L
    }
}
