package com.houg.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.houg.tv.net.NetClient

/** 榜单列表 Adapter（一行一名次）。 */
class RankAdapter(
    private val onItemClick: (NetClient.Drama) -> Unit
) : RecyclerView.Adapter<RankAdapter.VH>() {

    private val items = ArrayList<NetClient.RankItem>()

    fun submit(list: List<NetClient.RankItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<NetClient.RankItem>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rank, parent, false)
        UiScale.softenTouchFocus(v)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val no: TextView = view.findViewById(R.id.rank_no)
        private val cover: ImageView = view.findViewById(R.id.rank_cover)
        private val title: TextView = view.findViewById(R.id.rank_title)
        private val status: TextView = view.findViewById(R.id.rank_status)
        private val heat: TextView = view.findViewById(R.id.rank_heat)
        private val score: TextView = view.findViewById(R.id.rank_score)
        private val tags: TextView = view.findViewById(R.id.rank_tags)

        fun bind(item: NetClient.RankItem) {
            no.text = itemView.context.getString(R.string.rank_no, item.rank.toString())
            // 前三名用金/银/铜突出，其余主色。每次重建 drawable，避免共享 ConstantState 串色。
            val badgeColor = when (item.rank) {
                1 -> 0xFFE6A23C.toInt()
                2 -> 0xFFA6A6A6.toInt()
                3 -> 0xFFB87A4B.toInt()
                else -> ContextCompat.getColor(itemView.context, R.color.primary)
            }
            val bg = androidx.core.content.ContextCompat
                .getDrawable(itemView.context, R.drawable.rank_badge)
                ?.mutate() as? android.graphics.drawable.GradientDrawable
            bg?.setColor(badgeColor)
            no.background = bg ?: no.background

            title.text = item.drama.title

            val st = item.statusTags.firstOrNull()
            if (st.isNullOrEmpty()) {
                status.visibility = View.GONE
            } else {
                status.text = st
                status.visibility = View.VISIBLE
            }

            heat.text = item.heat
            heat.visibility = if (item.heat.isNullOrEmpty()) View.GONE else View.VISIBLE

            val extras = ArrayList<String>()
            if (!item.score.isNullOrEmpty()) extras.add("评分 " + item.score)
            if (!item.favorite.isNullOrEmpty()) extras.add(item.favorite)
            if (!item.like.isNullOrEmpty()) extras.add(item.like)
            score.text = extras.joinToString(" · ")
            score.visibility = if (extras.isEmpty()) View.GONE else View.VISIBLE

            val tagText = item.drama.tags.joinToString(" / ")
            tags.text = tagText
            tags.visibility = if (tagText.isEmpty()) View.GONE else View.VISIBLE

            if (item.drama.cover.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(item.drama.cover)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .into(cover)
            } else {
                cover.setImageResource(android.R.color.darker_gray)
            }
            itemView.setOnClickListener { onItemClick(item.drama) }
        }
    }
}
