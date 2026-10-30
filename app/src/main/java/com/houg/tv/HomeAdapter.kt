package com.houg.tv

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.houg.tv.data.WatchHistory
import com.houg.tv.net.NetClient

/**
 * 首页内容流 Adapter：一个 RecyclerView 承载 5 种行——
 * Hero（今日主打）/ 区块标题 / 继续观看横滑 / 今日热播榜单 / 新上架网格卡片。
 * 前四类都是整行（spanSizeLookup 返回 spanCount），末尾网格由
 * gridStartPosition() 之后的行按 1 列占位，配合 MainActivity 的
 * ItemDecoration 做间距。
 */
class HomeAdapter(
    private val onHeroPlay: (NetClient.Drama) -> Unit,
    private val onToggleFavorite: (NetClient.Drama) -> Unit,
    private val onDramaClick: (NetClient.Drama) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Hero(val drama: NetClient.Drama) : Row()
        data class Section(val titleRes: Int, val hintRes: Int) : Row()
        data class Continue(val entries: List<WatchHistory.Entry>) : Row()
        data class Hot(val items: List<NetClient.RankItem>) : Row()
        data class DramaRow(val drama: NetClient.Drama) : Row()
    }

    private val rows = ArrayList<Row>()
    private val catalog = ArrayList<NetClient.Drama>()
    private var hero: NetClient.Drama? = null
    private var continueEntries: List<WatchHistory.Entry> = emptyList()
    private var hotItems: List<NetClient.RankItem> = emptyList()
    private var continueKey = ""
    private var hotKey = ""

    /** 新上架网格的起始行号（之前的都是整行；空网格返回 MAX_VALUE 避免误判） */
    fun gridStartPosition(): Int =
        if (catalog.isEmpty()) Int.MAX_VALUE else rows.size - catalog.size

    fun isGridRow(position: Int): Boolean = position >= gridStartPosition()

    fun currentCatalog(): List<NetClient.Drama> = catalog

    fun hero(): NetClient.Drama? = hero

    fun setHero(d: NetClient.Drama?) {
        if (hero?.id == d?.id) return
        hero = d
        rebuild()
    }

    /** onResume 会反复调用：内容没变直接忽略，避免整表重绘抢走焦点 */
    fun setContinue(list: List<WatchHistory.Entry>) {
        val key = list.joinToString("|") { it.seriesId + ":" + it.lastEp }
        if (key == continueKey) return
        continueKey = key
        continueEntries = list
        rebuild()
    }

    fun setHot(list: List<NetClient.RankItem>) {
        val key = list.joinToString("|") { it.drama.id }
        if (key == hotKey) return
        hotKey = key
        hotItems = list
        rebuild()
    }

    fun submitCatalog(list: List<NetClient.Drama>) {
        catalog.clear()
        catalog.addAll(list)
        rebuild()
    }

    fun appendCatalog(list: List<NetClient.Drama>) {
        if (list.isEmpty()) return
        val start = rows.size
        catalog.addAll(list)
        for (d in list) rows.add(Row.DramaRow(d))
        notifyItemRangeInserted(start, list.size)
    }

    /** 只重绘 Hero 的收藏文案（局部 payload，不整表刷新） */
    fun refreshHeroFavorite() {
        if (rows.isNotEmpty() && rows[0] is Row.Hero) notifyItemChanged(0, PAYLOAD_FAV)
    }

    private fun rebuild() {
        rows.clear()
        hero?.let { rows.add(Row.Hero(it)) }
        if (continueEntries.isNotEmpty()) {
            rows.add(Row.Section(R.string.section_continue, R.string.section_continue_hint))
            rows.add(Row.Continue(continueEntries))
        }
        if (hotItems.isNotEmpty()) {
            rows.add(Row.Section(R.string.section_hot, R.string.section_hot_hint))
            rows.add(Row.Hot(hotItems))
        }
        if (catalog.isNotEmpty()) {
            rows.add(Row.Section(R.string.section_new, R.string.section_new_hint))
            for (d in catalog) rows.add(Row.DramaRow(d))
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Hero -> TYPE_HERO
        is Row.Section -> TYPE_SECTION
        is Row.Continue -> TYPE_CONTINUE
        is Row.Hot -> TYPE_HOT
        is Row.DramaRow -> TYPE_DRAMA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(
            when (viewType) {
                TYPE_HERO -> R.layout.item_home_hero
                TYPE_SECTION -> R.layout.item_home_section
                TYPE_CONTINUE -> R.layout.item_home_continue
                TYPE_HOT -> R.layout.item_home_hot
                else -> R.layout.item_drama
            }, parent, false
        )
        UiScale.softenTouchFocus(v)
        if (viewType == TYPE_HERO) styleHero(v, parent.context)
        return when (viewType) {
            TYPE_HERO -> HeroVH(v)
            TYPE_SECTION -> SectionVH(v)
            TYPE_CONTINUE -> ContinueVH(v)
            TYPE_HOT -> HotVH(v)
            else -> DramaVH(v)
        }
    }

    /**
     * Hero 双形态尺寸：TV 264dp / 标题 42sp，手机 280dp / 29sp。
     * 原型 stage 1920 画布按 2/3 换算到 1280×720dp；手机取 body.m 覆盖值。
     */
    private fun styleHero(v: View, ctx: Context) {
        val tv = UiScale.isTv(ctx)
        v.layoutParams.height = ctx.resources.getDimensionPixelSize(
            if (tv) R.dimen.home_hero_h_tv else R.dimen.home_hero_h
        )
        if (tv) return
        v.findViewById<View>(R.id.hero_ghost).visibility = View.GONE
        v.findViewById<TextView>(R.id.hero_title).textSize = 29f
        v.findViewById<TextView>(R.id.hero_eyebrow).textSize = 10f
        val content = v.findViewById<View>(R.id.hero_content)
        content.setPadding(
            content.paddingLeft,
            (30 * ctx.resources.displayMetrics.density).toInt(),
            content.paddingRight,
            content.paddingBottom
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Hero -> (holder as HeroVH).bind(row.drama)
            is Row.Section -> (holder as SectionVH).bind(row)
            is Row.Continue -> (holder as ContinueVH).bind(row.entries)
            is Row.Hot -> (holder as HotVH).bind(row.items)
            is Row.DramaRow -> (holder as DramaVH).bind(row.drama)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: List<Any>
    ) {
        // 收藏切换走局部重绑，避免整格重载封面闪烁
        if (payloads.contains(PAYLOAD_FAV) && holder is HeroVH) {
            val h = hero ?: return
            holder.bindFavorite(h)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun getItemCount(): Int = rows.size

    /* ==================== VH ==================== */

    inner class HeroVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.hero_cover)
        private val eyebrow: TextView = view.findViewById(R.id.hero_eyebrow)
        private val title: TextView = view.findViewById(R.id.hero_title)
        private val meta: TextView = view.findViewById(R.id.hero_meta)
        private val play: TextView = view.findViewById(R.id.hero_play)
        private val fav: TextView = view.findViewById(R.id.hero_fav)

        fun bind(d: NetClient.Drama) {
            loadCover(cover, d.cover)
            val headline = itemView.context.getString(R.string.home_headline)
            val tags = d.tags.take(2).joinToString(" / ")
            eyebrow.text = if (tags.isEmpty()) headline else "$headline · $tags"
            title.text = d.title
            val text = metaText(itemView.context, d)
            meta.text = text
            meta.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            bindFavorite(d)
            play.setOnClickListener { onHeroPlay(d) }
            fav.setOnClickListener { onToggleFavorite(d) }
        }

        fun bindFavorite(d: NetClient.Drama) {
            val on = App.watchHistory.isFavorite(d.id)
            fav.text = if (on) itemView.context.getString(R.string.favorited)
            else itemView.context.getString(R.string.home_join_list)
        }
    }

    inner class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.section_title)
        private val hint: TextView = view.findViewById(R.id.section_hint)

        fun bind(row: Row.Section) {
            title.setText(row.titleRes)
            hint.setText(row.hintRes)
        }
    }

    inner class ContinueVH(view: View) : RecyclerView.ViewHolder(view) {
        private val rowView: LinearLayout = view.findViewById(R.id.continue_row)

        fun bind(entries: List<WatchHistory.Entry>) {
            rowView.removeAllViews()
            val ctx = rowView.context
            val cardGap = (12 * ctx.resources.displayMetrics.density).toInt()
            entries.forEachIndexed { i, e ->
                val card = LayoutInflater.from(ctx)
                    .inflate(R.layout.item_home_continue_card, rowView, false)
                if (i > 0) {
                    (card.layoutParams as? LinearLayout.LayoutParams)?.marginStart = cardGap
                }
                val cover = card.findViewById<ImageView>(R.id.cc_cover)
                loadCover(cover, e.cover)
                card.findViewById<TextView>(R.id.cc_title).text = e.title
                card.findViewById<TextView>(R.id.cc_meta).text =
                    ctx.getString(R.string.watched_ep, e.lastEp.toString())
                card.setOnClickListener {
                    onDramaClick(NetClient.Drama.minimal(e.seriesId, e.title, e.cover))
                }
                rowView.addView(card)
            }
        }
    }

    inner class HotVH(view: View) : RecyclerView.ViewHolder(view) {
        private val list: LinearLayout = view.findViewById(R.id.hot_list)

        fun bind(items: List<NetClient.RankItem>) {
            list.removeAllViews()
            val ctx = list.context
            items.forEach { item ->
                val row = LayoutInflater.from(ctx)
                    .inflate(R.layout.item_home_hot_row, list, false)
                val index = row.findViewById<TextView>(R.id.hot_index)
                index.text = item.rank.toString()
                // 前三名用品牌红压住视线，其余退到弱化色（原型 li .i 规则）
                index.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (item.rank <= 3) R.color.accent else R.color.faint
                    )
                )
                row.findViewById<TextView>(R.id.hot_title).text = item.drama.title
                row.findViewById<TextView>(R.id.hot_heat).text = item.heat
                row.setOnClickListener { onDramaClick(item.drama) }
                list.addView(row)
            }
        }
    }

    inner class DramaVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val title: TextView = view.findViewById(R.id.title)
        private val remark: TextView = view.findViewById(R.id.remark)

        fun bind(d: NetClient.Drama) {
            title.text = d.title
            remark.text = d.remark
            loadCover(cover, d.cover)
            itemView.setOnClickListener { onDramaClick(d) }
        }
    }

    /* ==================== utils ==================== */

    private fun metaText(ctx: Context, d: NetClient.Drama): String {
        return when {
            d.remark.isNotEmpty() -> d.remark
            d.episodeCount.isNotEmpty() -> ctx.getString(R.string.home_ep_total, d.episodeCount)
            else -> ""
        }
    }

    private fun loadCover(view: ImageView, url: String) {
        if (url.isNotEmpty()) {
            Glide.with(view.context)
                .load(url)
                .placeholder(android.R.color.darker_gray)
                .error(android.R.color.darker_gray)
                .into(view)
        } else {
            view.setImageResource(android.R.color.darker_gray)
        }
    }

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_SECTION = 1
        private const val TYPE_CONTINUE = 2
        private const val TYPE_HOT = 3
        private const val TYPE_DRAMA = 4
        private const val PAYLOAD_FAV = "fav"
    }
}
