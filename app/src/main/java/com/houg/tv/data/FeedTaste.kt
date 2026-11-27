package com.houg.tv.data

import android.content.Context

/**
 * 刷短剧的「口味记忆」（纯本地，不上报任何数据）。
 *
 * 为什么需要：推荐流接口没有个性化，只按热度排序。用户刷久了会反复看到同类。
 * 这里记录**用户实际刷过的剧的标签**（tags 里含 category_list，如「都市/古装/甜宠」），
 * 下次打开刷短剧时把这些标签命中的剧排在前面——只改顺序，不改内容集合。
 *
 * 存储：SharedPreferences("feed_taste")，键 tags，值形如 "都市:12,古装:7"（计数降序）。
 */
object FeedTaste {

    private const val PREF = "feed_taste"
    private const val KEY = "tags"
    /** 只记前 N 个标签，避免冷门长尾淹没有效信号。 */
    private const val MAX_KEEP = 40

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun load(ctx: Context): LinkedHashMap<String, Int> {
        val raw = prefs(ctx).getString(KEY, "").orEmpty()
        val map = LinkedHashMap<String, Int>()
        if (raw.isEmpty()) return map
        for (part in raw.split(',')) {
            val i = part.lastIndexOf(':')
            if (i <= 0) continue
            val k = part.substring(0, i)
            val v = part.substring(i + 1).toIntOrNull() ?: continue
            if (k.isNotEmpty() && v > 0) map[k] = v
        }
        return map
    }

    private fun save(ctx: Context, map: Map<String, Int>) {
        val out = map.entries.sortedByDescending { it.value }.take(MAX_KEEP)
            .joinToString(",") { it.key + ":" + it.value }
        prefs(ctx).edit().putString(KEY, out).apply()
    }

    /** 用户刷了某部剧：把它的标签计数 +1。 */
    fun bump(ctx: Context, tags: Collection<String>) {
        if (tags.isEmpty()) return
        val map = load(ctx)
        for (t in tags) {
            val k = t.trim()
            if (k.isEmpty()) continue
            map[k] = (map[k] ?: 0) + 1
        }
        save(ctx, map)
    }

    /** 用户口味的前 n 个标签（没有记录时为空）。 */
    fun top(ctx: Context, n: Int = 3): List<String> =
        load(ctx).entries.sortedByDescending { it.value }.take(n).map { it.key }

    /**
     * 按口味给候选剧排序：命中口味标签多的排前面，其余保持原顺序（稳定排序）。
     * 返回新列表，不改动入参。
     */
    fun rank(ctx: Context, list: List<FeedItem>): List<FeedItem> {
        val top = top(ctx)
        if (top.isEmpty()) return list
        return list.sortedByDescending { item ->
            item.tags.count { it in top }
        }
    }

    /** 排行榜用的最小载体：id + 标签。 */
    data class FeedItem(val id: String, val tags: List<String>)

    /** 清空口味记忆（设置里的「重置偏好」会一并调用）。 */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY).apply()
    }
}
