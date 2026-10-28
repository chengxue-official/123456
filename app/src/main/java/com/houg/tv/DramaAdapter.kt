package com.houg.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.houg.tv.net.NetClient

/** 剧集卡片列表 Adapter（首页分类 / 搜索结果共用） */
class DramaAdapter(
    private val onItemClick: (NetClient.Drama) -> Unit
) : RecyclerView.Adapter<DramaAdapter.VH>() {

    private val items = ArrayList<NetClient.Drama>()

    fun submit(list: List<NetClient.Drama>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<NetClient.Drama>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun current(): List<NetClient.Drama> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_drama, parent, false)
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
        private val remark: TextView = view.findViewById(R.id.remark)

        fun bind(d: NetClient.Drama) {
            title.text = d.title
            remark.text = d.remark
            val coverUrl = d.cover
            if (coverUrl.isNotEmpty()) {
                Glide.with(itemView.context)
                    .load(coverUrl)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .into(cover)
            } else {
                cover.setImageResource(android.R.color.darker_gray)
            }
            itemView.setOnClickListener { onItemClick(d) }
        }
    }
}
