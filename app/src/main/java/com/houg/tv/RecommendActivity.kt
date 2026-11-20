package com.houg.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
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
 * 推荐页：全站混合推荐流。
 *
 * 数据源不是独立接口——HG 的「推荐」是分类 landpage 接口的一个排序维度，
 * 见 [NetClient.recommend]：三大类（真人剧/漫剧/AI剧）混合 + 服务端推荐排序。
 * 响应结构与分类页一致，因此列表、分页、点进详情都复用分类页那套。
 */
class RecommendActivity : AppCompatActivity() {

    /** 全局 density/字号缩放（1280×720dp 设计画布），见 UiScale.warp */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.warp(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: DramaAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var progress: View
    private lateinit var statusText: TextView

    private var nextOffset = 0
    private var sessionId = ""
    private var hasMore = false
    private var loading = false
    /** 请求代次：快速重进 / 重试时丢弃过期响应 */
    private var generation = 0
    /** 首屏为空时的页面级自动重试次数（网络层已重试两轮，这里只补一次） */
    private var autoRetry = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureImmersive()
        setContentView(R.layout.activity_recommend)
        // 手机端触控目标：40dp 返回按钮抬到 48dp（TV 不生效）
        UiScale.enforceTouchTargets(findViewById(android.R.id.content))

        progress = findViewById(R.id.recommend_progress)
        statusText = findViewById(R.id.recommend_status)
        recycler = findViewById(R.id.recommend_list)

        // 页面安全边距：手机 24dp / TV 64dp overscan（与 RankActivity 同款）
        val pageH = UiScale.pageH(this)
        val top = findViewById<View>(R.id.recommend_top)
        top.setPadding(pageH, UiScale.pageTop(this), pageH, top.paddingBottom)
        recycler.setPadding(pageH, recycler.paddingTop, pageH, recycler.paddingBottom)

        adapter = DramaAdapter { drama -> openDetail(drama) }
        installAutoGrid(recycler, UiScale.coverWidthDp(this), 2, 10)
        recycler.adapter = adapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? GridLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= adapter.itemCount - 6 && hasMore && !loading) {
                    loadPage(reset = false)
                }
            }
        })

        findViewById<TextView>(R.id.recommend_back).setOnClickListener { finish() }

        loadPage(reset = true)
    }

    private fun loadPage(reset: Boolean) {
        if (loading && !reset) return
        loading = true
        generation++
        val gen = generation
        val offset = if (reset) 0 else nextOffset
        val session = if (reset) "" else sessionId

        progress.visibility = View.VISIBLE
        if (reset) {
            statusText.visibility = View.GONE
        }

        scope.launch {
            var retryNeeded = false
            try {
                val result = withContext(Dispatchers.IO) {
                    var page = App.netClient.recommend(offset, session)
                    if (page.dramas.isEmpty() && offset == 0) {
                        // 兜底：服务端若把「三大类混合」判为非法而返回空，
                        // 退回单大类的推荐排序，避免整页空白。
                        page = App.netClient.category(
                            "short_play", "default", 0, "", HashMap(), false
                        )
                    }
                    page
                }
                if (gen != generation) return@launch
                if (reset) adapter.submit(result.dramas) else adapter.append(result.dramas)
                nextOffset = result.nextOffset
                sessionId = result.sessionId
                hasMore = result.hasMore
                retryNeeded = adapter.current().isEmpty()
            } catch (e: Exception) {
                if (gen != generation) return@launch
                retryNeeded = adapter.current().isEmpty()
            }
            if (gen != generation) return@launch
            progress.visibility = View.GONE
            loading = false
            if (retryNeeded && autoRetry < MAX_AUTO_RETRIES) {
                // 首屏为空：短暂等待后自动再来一次，用户不用手动退出重进
                autoRetry++
                progress.visibility = View.VISIBLE
                delay(AUTO_RETRY_DELAY_MS)
                if (gen == generation && !isFinishing) loadPage(reset = true)
                return@launch
            }
            if (!retryNeeded) autoRetry = 0
            if (adapter.current().isEmpty()) {
                statusText.text = getString(
                    if (autoRetry > 0) R.string.load_failed else R.string.empty
                )
                statusText.visibility = View.VISIBLE
            }
        }
    }

    private fun openDetail(drama: NetClient.Drama) {
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(DetailActivity.EXTRA_SERIES_ID, drama.id)
                .putExtra(DetailActivity.EXTRA_TITLE, drama.title)
                .putExtra(DetailActivity.EXTRA_COVER, drama.cover)
        )
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
