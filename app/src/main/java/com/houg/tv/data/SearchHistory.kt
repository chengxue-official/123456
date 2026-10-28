package com.houg.tv.data

import android.content.Context
import org.json.JSONArray

/**
 * 搜索历史：最近搜索关键词，去重（同词上浮）、上限截断。
 * 兼容 API 14，使用系统自带 org.json。
 */
class SearchHistory(context: Context) {

    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val lock = Any()

    /** 记录关键词（已存在则提到最前）。 */
    fun add(keyword: String) {
        val kw = keyword.trim()
        if (kw.isEmpty() || kw.length > MAX_LEN) return
        synchronized(lock) {
            val list = list().toMutableList()
            list.remove(kw)
            list.add(0, kw)
            while (list.size > MAX_ITEMS) list.removeAt(list.size - 1)
            save(list)
        }
    }

    /** 搜索历史（最近在前）。 */
    fun list(): List<String> {
        synchronized(lock) {
            val raw = prefs.getString(KEY, null) ?: return emptyList()
            val out = ArrayList<String>()
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i, "")
                    if (s.isNotEmpty()) out.add(s)
                }
            } catch (_: Exception) {
            }
            return out
        }
    }

    /** 删除单条。 */
    fun remove(keyword: String) {
        synchronized(lock) {
            val list = list().toMutableList()
            list.remove(keyword)
            save(list)
        }
    }

    /** 清空全部搜索历史。 */
    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY).apply()
        }
    }

    private fun save(list: List<String>) {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "keywords"
        const val MAX_ITEMS = 20
        const val MAX_LEN = 40
    }
}
