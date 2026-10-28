package com.houg.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.houg.tv.data.WatchHistory

/** 追剧历史列表 Adapter */
class HistoryAdapter(
    private val onItemClick: (WatchHistory.Entry) -> Unit,
    private val onLongClick: (WatchHistory.Entry) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = ArrayList<WatchHistory.Entry>()

    fun submit(list: List<WatchHistory.Entry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        UiScale.softenTouchFocus(v)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val title: TextView = view.findViewById(R.id.title)
        private val progress: TextView = view.findViewById(R.id.progress)

        fun bind(e: WatchHistory.Entry) {
            title.text = e.title
            val ctx = itemView.context
            progress.text = if (e.lastEp > 0) {
                val pos = e.positionOf(e.lastEp)
                if (pos > 0) {
                    ctx.getString(R.string.resume_hint, e.lastEp.toString(), formatMs(pos))
                } else {
                    ctx.getString(R.string.watched_ep, e.lastEp.toString())
                }
            } else {
                // 收藏项（没有观看记录）
                ctx.getString(R.string.favorited)
            }
            if (e.cover.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(e.cover)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .into(cover)
            }
            itemView.setOnClickListener { onItemClick(e) }
            itemView.setOnLongClickListener {
                onLongClick(e)
                true
            }
        }

        private fun formatMs(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }
}
