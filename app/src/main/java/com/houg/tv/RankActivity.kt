package com.houg.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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
 * 榜单页：HG官方排行榜（热播 / 真人剧 / 漫剧 / AI剧）。
 * 数据来自网页 {@code /rank/<board>}，与搜索同站免签名；支持上下翻页。
 */
class RankActivity : AppCompatActivity() {

    /** 全局 density/字号缩放（1280×720dp 设计画布），见 UiScale.warp */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.warp(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: RankAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var progress: View
    private lateinit var statusText: TextView
    private lateinit var updatedText: TextView
    private lateinit var tabs: List<TextView>

    private var boardIndex = 0
    private var page = 1
    private var totalPages = 1
    private var loading = false
    /** 空列表自动重试次数：网络层已重试两轮，这里再补一次兜底，避免用户手动反复重进 */
    private var autoRetry = 0
    /** 请求代次：快速切榜单时丢弃过期响应 */
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()
        setContentView(R.layout.activity_rank)

        progress = findViewById(R.id.rank_progress)
        statusText = findViewById(R.id.rank_status)
        updatedText = findViewById(R.id.rank_updated)
        recycler = findViewById(R.id.rank_list)

        // 页面安全边距：手机 24dp / TV 64dp overscan（同 MainActivity.applyPageInsets）
        val pageH = UiScale.pageH(this)
        findViewById<View>(R.id.rank_top).setPadding(pageH, UiScale.pageTop(this), pageH, 0)
        recycler.setPadding(pageH, recycler.paddingTop, pageH, recycler.paddingBottom)

        adapter = RankAdapter { drama -> openDetail(drama) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) {
                    if (page < totalPages && !loading) loadPage(page + 1, false)
                }
            }
        })

        tabs = listOf(
            findViewById(R.id.board_hot),
            findViewById(R.id.board_real),
            findViewById(R.id.board_comic),
            findViewById(R.id.board_ai)
        )
        tabs.forEachIndexed { index, tab ->
            tab.text = NetClient.RANK_BOARDS[index].label
            tab.setOnClickListener { selectBoard(index) }
        }
        // 手机竖屏顶栏只有约 232dp 给胶囊，四枚按原字号需要 307dp 会挤掉「AI剧榜」，
        // 压一档字号和内边距让四个都露出（TV 空间充足，保持原样）
        if (!UiScale.isTv(this)) {
            val hPad = (6 * resources.displayMetrics.density).toInt()
            tabs.forEach { tab ->
                tab.textSize = 13f
                tab.setPadding(hPad, tab.paddingTop, hPad, tab.paddingBottom)
            }
        }
        findViewById<TextView>(R.id.rank_back).setOnClickListener { finish() }

        selectBoard(0)
    }

    private fun selectBoard(index: Int) {
        if (boardIndex == index && adapter.itemCount > 0) return
        boardIndex = index
        autoRetry = 0
        tabs.forEachIndexed { i, tab -> tab.isSelected = i == index }
        loadPage(1, true)
    }

    private fun loadPage(target: Int, reset: Boolean) {
        // 翻页请求在途时不再叠加；但切榜单（reset）允许打断：
        // generation 自增会让旧请求的回调作废，避免「内容与选中 tab 错位」
        if (loading && !reset) return
        loading = true
        generation++
        val gen = generation
        val board = NetClient.RANK_BOARDS[boardIndex]

        progress.visibility = View.VISIBLE
        if (reset) {
            statusText.visibility = View.GONE
            adapter.submit(emptyList())
        }

        scope.launch {
            var retryNeeded = false
            try {
                val result = withContext(Dispatchers.IO) {
                    App.netClient.ranking(board, target)
                }
                if (gen != generation) return@launch
                page = result.page
                totalPages = result.totalPages
                if (reset) adapter.submit(result.items) else adapter.append(result.items)
                updatedText.text = result.updatedText
                // 手机竖屏顶栏放不下「更新于 xx」，直接不显示，把宽度留给榜单胶囊
                updatedText.visibility = when {
                    !UiScale.isTv(this@RankActivity) -> View.GONE
                    result.updatedText.isNullOrEmpty() -> View.GONE
                    else -> View.VISIBLE
                }
                retryNeeded = adapter.itemCount == 0
            } catch (e: Exception) {
                if (gen != generation) return@launch
                retryNeeded = adapter.itemCount == 0
            }
            if (gen != generation) return@launch
            progress.visibility = View.GONE
            loading = false
            if (retryNeeded && autoRetry < MAX_AUTO_RETRIES) {
                // 首屏为空：短暂等待后自动再来一次，用户不用手动退出重进
                autoRetry++
                progress.visibility = View.VISIBLE
                delay(AUTO_RETRY_DELAY_MS)
                if (gen == generation && !isFinishing) loadPage(target, reset)
                return@launch
            }
            if (!retryNeeded) autoRetry = 0
            if (adapter.itemCount == 0) {
                statusText.text = getString(
                    if (autoRetry > 0) R.string.rank_load_failed else R.string.rank_empty
                )
                statusText.visibility = View.VISIBLE
            }
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
        /** 首屏为空时的页面级自动重试次数（网络层已重试两轮，这里只补一次）。 */
        private const val MAX_AUTO_RETRIES = 1
        private const val AUTO_RETRY_DELAY_MS = 1200L
    }
}
