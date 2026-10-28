package com.houg.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.NetClient

/**
 * 一行筛选的胶囊：单选，首项固定「全部」（id 空串 = 不传该维度）。
 * 选中项变化只重绘受影响的两个位置，点完焦点仍停在被按的胶囊上。
 */
class FilterChipAdapter(
    private val type: String,
    private val allLabel: String,
    private val onSelect: (type: String, id: String) -> Unit
) : RecyclerView.Adapter<FilterChipAdapter.VH>() {

    private data class Chip(val id: String, val label: String)

    private val items = ArrayList<Chip>()
    private var selectedId = ""

    fun submit(options: List<NetClient.FilterOption>, selected: String) {
        items.clear()
        items.add(Chip("", allLabel))
        options.forEach { items.add(Chip(it.id, it.label)) }
        selectedId = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_filter_chip, parent, false)
        UiScale.softenTouchFocus(v)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chip = items[position]
        holder.text.text = chip.label
        holder.text.isSelected = chip.id == selectedId
        holder.text.setOnClickListener {
            if (selectedId == chip.id) return@setOnClickListener
            val previous = items.indexOfFirst { it.id == selectedId }
            selectedId = chip.id
            if (previous >= 0) notifyItemChanged(previous)
            notifyItemChanged(position)
            onSelect(type, chip.id)
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }
}
