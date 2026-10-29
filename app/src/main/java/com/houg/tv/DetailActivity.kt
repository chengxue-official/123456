package com.houg.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.houg.tv.net.NetClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 详情页：封面 + 简介 + 收藏按钮 + 选集（带续播定位） */
class DetailActivity : AppCompatActivity() {

    /** 全局 density/字号缩放（1280×720dp 设计画布），见 UiScale.warp */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.warp(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var seriesId: String
    private lateinit var seriesTitle: String
    private lateinit var seriesCover: String

    private var episodes: List<NetClient.Episode> = emptyList()
    private var lastEp = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiScale.applyImmersive(this)
        // 布局按设备形态选：TV 横排（左封面右选集）/ 手机竖排（海报卡 + 下方选集）。
        // 不用 layout-port：manifest 是 landscape 且 configChanges 声明了 orientation，
        // 旋转不重建 Activity，orientation 限定符不可靠。
        setContentView(
            if (UiScale.isTv(this)) R.layout.activity_detail
            else R.layout.activity_detail_phone
        )
        // 手机触摸端：按钮降级为普通可点击，避免第一次点击只聚焦
        UiScale.softenTouchFocus(findViewById(android.R.id.content))
        // 手机端触控目标：32–44dp 按钮/胶囊抬到 48dp（TV 不生效）
        UiScale.enforceTouchTargets(findViewById(android.R.id.content))

        seriesId = intent.getStringExtra(EXTRA_SERIES_ID) ?: ""
        seriesTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        seriesCover = intent.getStringExtra(EXTRA_COVER) ?: ""

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // 选集网格：列数/格宽自适应（TV 100dp / 手机 88dp 目标宽），与剧集卡片同一套算法
        installAutoGrid(findViewById(R.id.episode_recycler), UiScale.episodeWidthDp(this), 4, 12)

        val titleView = findViewById<TextView>(R.id.detail_title)
        titleView.text = seriesTitle

        val cover = findViewById<ImageView>(R.id.detail_cover)
        if (seriesCover.isNotEmpty()) {
            Glide.with(this).load(seriesCover)
                .placeholder(android.R.color.darker_gray)
                .error(android.R.color.darker_gray)
                .into(cover)
        }

        updateFavoriteButton()
        findViewById<View>(R.id.btn_favorite).setOnClickListener { toggleFavorite() }

        loadDetail()
    }

    private fun toggleFavorite() {
        val now = App.watchHistory.toggleFavorite(seriesId, seriesTitle, seriesCover)
        updateFavoriteButton()
        Toast.makeText(
            this,
            if (now) R.string.favorite_added else R.string.favorite_removed,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun updateFavoriteButton() {
        val btn = findViewById<TextView>(R.id.btn_favorite)
        val fav = seriesId.isNotEmpty() && App.watchHistory.isFavorite(seriesId)
        btn.text = if (fav) getString(R.string.favorited) else getString(R.string.favorite)
        btn.isSelected = fav
    }

    private fun loadDetail() {
        val progress = findViewById<View>(R.id.progress)
        val statusText = findViewById<TextView>(R.id.status_text)
        progress.visibility = View.VISIBLE
        statusText.visibility = View.GONE

        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    App.netClient.detail(seriesId)
                }
                episodes = detail.episodes
                val titleView = findViewById<TextView>(R.id.detail_title)
                titleView.text = detail.title
                // 详情接口拿到的标题/封面比列表页更准，回填给收藏与历史用
                if (detail.title.isNotEmpty()) seriesTitle = detail.title
                if (detail.cover.isNotEmpty()) seriesCover = detail.cover
                val meta = findViewById<TextView>(R.id.detail_meta)
                val countText = getString(R.string.episode_count, episodes.size.toString())
                meta.text = countText
                val intro = findViewById<TextView>(R.id.detail_intro)
                intro.text = detail.intro
                if (detail.cover.isNotEmpty()) {
                    Glide.with(this@DetailActivity).load(detail.cover)
                        .placeholder(android.R.color.darker_gray)
                        .error(android.R.color.darker_gray)
                        .into(findViewById(R.id.detail_cover))
                }

                lastEp = App.watchHistory.lastEpisode(seriesId)
                // 详情接口拿到的标题/封面更权威，若已收藏则**原地**刷新收藏项元数据
                // （不动收藏时间，避免"只是看了下详情"就把该剧顶到收藏列表最前）
                App.watchHistory.updateFavoriteMeta(seriesId, seriesTitle, seriesCover)

                // 续播入口：有历史进度时在选集上方显示「继续观看 第N集」
                val resumeBtn = findViewById<TextView>(R.id.btn_resume)
                if (lastEp > 0 && episodes.isNotEmpty()) {
                    resumeBtn.text = getString(R.string.continue_ep, lastEp.toString())
                    resumeBtn.visibility = View.VISIBLE
                    resumeBtn.setOnClickListener { resumePlay() }
                } else {
                    resumeBtn.visibility = View.GONE
                }

                // 同系列其他季 + 相关推荐：后台取数，命中才展示，不挡首屏
                loadCompanions(detail.title, seriesId)

                val episodeAdapter = EpisodeAdapter(episodes, lastEp) { ep -> playEpisode(ep) }
                val recycler = findViewById<RecyclerView>(R.id.episode_recycler)
                recycler.adapter = episodeAdapter
                // 焦点：优先落在「继续观看」，否则落在上次看的集，最后才落第 1 集
                recycler.post {
                    if (lastEp > 0 && resumeBtn.visibility == View.VISIBLE) {
                        resumeBtn.requestFocus()
                    } else {
                        val idx = episodes.indexOfFirst { it.index == lastEp }.coerceAtLeast(0)
                        val holder = recycler.findViewHolderForAdapterPosition(idx)
                        if (holder != null) holder.itemView.requestFocus() else recycler.requestFocus()
                    }
                }
                updateFavoriteButton()

                if (episodes.isEmpty()) {
                    statusText.text = if (detail.title == seriesId) {
                        // 服务端没这个条目（已下架/无效 ID）：App 版与网页版都拿不到
                        // 标题，回退为传入的 seriesId
                        "该内容可能已下架或暂时无法观看"
                    } else {
                        getString(R.string.empty)
                    }
                    statusText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                // 「已下架」类业务异常直接显示；网络类异常显示通用重试文案
                val msg = e.message ?: ""
                statusText.text = if (msg.contains("下架")) msg
                    else getString(R.string.load_failed)
                statusText.visibility = View.VISIBLE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /**
     * 「同系列剧集」+「相关推荐」两个区块：放 IO 线程取数，失败静默——
     * 联动是锦上添花，不能影响详情页可用性。
     */
    private fun loadCompanions(title: String, selfId: String) {
        // 无季标记的片名也查：查不到兄弟季就整块隐藏
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    App.netClient.findSiblingSeasons(title, selfId)
                } catch (e: Exception) {
                    emptyList<NetClient.Drama>()
                }
            }
            fillCardRow(R.id.season_section, R.id.season_row, list)
        }
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                try {
                    App.netClient.findRelated(title, selfId)
                } catch (e: Exception) {
                    emptyList<NetClient.Drama>()
                }
            }
            fillCardRow(R.id.related_section, R.id.related_row, list)
        }
    }

    /** 把剧集列表填进横滑卡片行（item_season 小卡），空列表时整块隐藏。 */
    private fun fillCardRow(sectionId: Int, rowId: Int, list: List<NetClient.Drama>) {
        val section = findViewById<View>(sectionId)
        val row = findViewById<LinearLayout>(rowId)
        row.removeAllViews()
        if (list.isEmpty()) {
            section.visibility = View.GONE
            return
        }
        val gap = (10 * resources.displayMetrics.density).toInt()
        list.forEachIndexed { i, drama ->
            val item = layoutInflater.inflate(R.layout.item_season, row, false)
            item.findViewById<TextView>(R.id.season_title).text = drama.title
            if (drama.cover.isNotEmpty()) {
                Glide.with(this).load(drama.cover)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .into(item.findViewById(R.id.season_cover))
            }
            if (i > 0) {
                (item.layoutParams as LinearLayout.LayoutParams).marginStart = gap
            }
            item.setOnClickListener { openDrama(drama) }
            row.addView(item)
        }
        section.visibility = View.VISIBLE
    }

    /** 跳到对应剧集的详情页（不 finish 本页，返回键可回到当前剧）。 */
    private fun openDrama(drama: NetClient.Drama) {
        if (drama.id.isEmpty()) return
        startActivity(
            Intent(this, DetailActivity::class.java)
                .putExtra(EXTRA_SERIES_ID, drama.id)
                .putExtra(EXTRA_TITLE, drama.title)
                .putExtra(EXTRA_COVER, drama.cover)
        )
    }

    /** 「继续观看」：直接进入上次那集，播放器会自动 seek 到上次位置。 */
    private fun resumePlay() {
        val ep = episodes.firstOrNull { it.index == lastEp } ?: episodes.firstOrNull() ?: return
        playEpisode(ep)
    }

    private fun playEpisode(ep: NetClient.Episode) {
        // 统一按集号升序排序，vid 列表与集号列表按下标一一对应
        val sorted = episodes.sortedBy { it.index }
        val pos = sorted.indexOfFirst { it.vid == ep.vid }.coerceAtLeast(0)

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_SERIES_ID, seriesId)
        intent.putExtra(PlayerActivity.EXTRA_TITLE, seriesTitle)
        intent.putExtra(PlayerActivity.EXTRA_COVER, seriesCover)
        intent.putExtra(PlayerActivity.EXTRA_EPISODE_POS, pos)
        intent.putStringArrayListExtra(
            PlayerActivity.EXTRA_VID_LIST,
            ArrayList(sorted.map { it.vid })
        )
        intent.putIntegerArrayListExtra(
            PlayerActivity.EXTRA_EP_LIST,
            ArrayList(sorted.map { it.index })
        )
        startActivity(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SERIES_ID = "series_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COVER = "cover"
    }
}
