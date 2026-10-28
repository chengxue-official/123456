package com.houg.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.NetClient

/** 选集列表 Adapter（详情页） */
class EpisodeAdapter(
    private val episodes: List<NetClient.Episode>,
    private val lastEpisode: Int,
    private val onItemClick: (NetClient.Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        UiScale.softenTouchFocus(v)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ep = episodes[position]
        holder.text.text = ep.title
        // 上次看到的集数高亮
        if (ep.index == lastEpisode) {
            holder.text.setTextColor(holder.text.context.resources.getColor(R.color.primary))
        } else {
            holder.text.setTextColor(holder.text.context.resources.getColor(R.color.text_primary))
        }
        holder.itemView.setOnClickListener { onItemClick(ep) }
    }

    override fun getItemCount(): Int = episodes.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.episode_text)
    }
}
