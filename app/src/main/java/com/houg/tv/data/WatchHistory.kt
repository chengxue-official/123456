package com.houg.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 观看历史 + 收藏：基于 SharedPreferences 持久化，兼容 API 14，使用系统自带 org.json。
 *
 * 两个集合分开存：
 *  - entries   ：观看历史（记录上次看到第几集 + 各集内的播放进度 ms），用于「续播」；
 *  - favorites ：收藏（用户主动标记，与是否看过无关）。
 * 同一部剧可以同时出现在两边（历史里看进度，收藏里看标记）。
 *
 * 进度按「集号 → 位置ms」映射存储：来回切集时，非当前集的进度不会被覆盖
 * （此前只存单条 {lastEp, positionMs}，从第 6 集切到第 5 集再切回来，
 * 第 6 集的进度会被抹成 0，用户下次又从头看）。
 */
class WatchHistory(context: Context) {

    data class Entry(
        val seriesId: String,
        val title: String,
        val cover: String,
        val lastEp: Int,
        val time: Long,
        /** 各集 → 集内播放位置（ms）。缺省 0 表示从头播。 */
        val positions: Map<Int, Long> = emptyMap()
    ) {
        /** 上次在该集内的播放位置（ms），没有记录返回 0。 */
        fun positionOf(ep: Int): Long = positions[ep] ?: 0L
    }

    private val prefs = context.getSharedPreferences("watch_history", Context.MODE_PRIVATE)
    private val lock = Any()

    /* ==================== 观看历史 ==================== */

    /**
     * 记录/更新观看进度。
     * @param ep 集号
     * @param positionMs 该集内播放位置（ms）。<=0 表示"本次拿不到有效位置"
     *                   （例如 prepare 未完成时 currentPosition 恒为 0），
     *                   此时**保留**该集已有的进度不动，避免把续播点抹掉。
     */
    fun record(seriesId: String, title: String, cover: String, ep: Int, positionMs: Long) {
        if (seriesId.isEmpty() || ep <= 0) return
        synchronized(lock) {
            val map = loadAll(KEY).toMutableMap()
            val old = map[seriesId]
            // 各集进度映射：保留其他集的进度，只更新当前集
            val positions = HashMap<Int, Long>(old?.positions ?: emptyMap())
            if (positionMs > 0) positions[ep] = positionMs
            // 剧名/封面缺失时不要用空值覆盖已有的完整信息
            map[seriesId] = Entry(
                seriesId,
                title.ifEmpty { old?.title ?: "" },
                cover.ifEmpty { old?.cover ?: "" },
                ep,
                System.currentTimeMillis(),
                positions
            )
            saveAll(KEY, map)
        }
    }

    /** 上次看到第几集（没看过返回 0） */
    fun lastEpisode(seriesId: String): Int {
        synchronized(lock) {
            return loadAll(KEY)[seriesId]?.lastEp ?: 0
        }
    }

    /**
     * 标记某集「已看完」：清空该集进度（下次进入从头播），并把 lastEp 推进到该集。
     *
     * ⚠️ 必须清空进度：一集播完时若把「片尾位置」留作续播点，用户再次进入该集会被
     * seek 到片尾，立刻触发 STATE_ENDED → 又被判为播完 → 自动跳下一集；而下一集的
     * 片尾位置同样被留下，于是连环跳集、周而复始（用户反馈的恶性 bug）。
     */
    fun markEpisodeCompleted(seriesId: String, title: String, cover: String, ep: Int) {
        if (seriesId.isEmpty() || ep <= 0) return
        synchronized(lock) {
            val map = loadAll(KEY).toMutableMap()
            val old = map[seriesId]
            val positions = HashMap<Int, Long>(old?.positions ?: emptyMap())
            positions.remove(ep)
            map[seriesId] = Entry(
                seriesId,
                title.ifEmpty { old?.title ?: "" },
                cover.ifEmpty { old?.cover ?: "" },
                ep,
                System.currentTimeMillis(),
                positions
            )
            saveAll(KEY, map)
        }
    }

    /** 上次在第 ep 集的播放位置（ms）；没有记录返回 0。 */
    fun positionOf(seriesId: String, ep: Int): Long {
        synchronized(lock) {
            return loadAll(KEY)[seriesId]?.positionOf(ep) ?: 0L
        }
    }

    /** 观看历史（按最近观看倒序） */
    fun list(): List<Entry> {
        synchronized(lock) {
            return loadAll(KEY).values.sortedByDescending { it.time }
        }
    }

    fun remove(seriesId: String) {
        synchronized(lock) {
            val map = loadAll(KEY).toMutableMap()
            map.remove(seriesId)
            saveAll(KEY, map)
        }
    }

    /** 清空全部观看历史（收藏不受影响）。 */
    fun clearHistory() {
        synchronized(lock) {
            prefs.edit().remove(KEY).apply()
        }
    }

    /* ==================== 收藏 ==================== */

    /** 是否已收藏 */
    fun isFavorite(seriesId: String): Boolean {
        synchronized(lock) {
            return loadAll(KEY_FAV).containsKey(seriesId)
        }
    }

    /** 切换收藏状态，返回切换后的状态（true=已收藏）。 */
    fun toggleFavorite(seriesId: String, title: String, cover: String): Boolean {
        if (seriesId.isEmpty()) return false
        synchronized(lock) {
            val map = loadAll(KEY_FAV).toMutableMap()
            if (map.containsKey(seriesId)) {
                map.remove(seriesId)
                saveAll(KEY_FAV, map)
                return false
            }
            map[seriesId] = Entry(seriesId, title, cover, 0, System.currentTimeMillis(), emptyMap())
            saveAll(KEY_FAV, map)
            return true
        }
    }

    /** 收藏列表（按收藏时间倒序） */
    fun favorites(): List<Entry> {
        synchronized(lock) {
            return loadAll(KEY_FAV).values.sortedByDescending { it.time }
        }
    }

    /**
     * 原地更新收藏项的标题/封面（不动收藏时间）。
     * 详情接口拿到的标题/封面更权威，用它回填；若用 toggle 重建会刷新 time，
     * 把用户"只是看了下详情"的剧顶到收藏列表最前面，破坏收藏序。
     */
    fun updateFavoriteMeta(seriesId: String, title: String, cover: String) {
        if (seriesId.isEmpty()) return
        synchronized(lock) {
            val map = loadAll(KEY_FAV).toMutableMap()
            val old = map[seriesId] ?: return
            val newTitle = title.ifEmpty { old.title }
            val newCover = cover.ifEmpty { old.cover }
            if (newTitle == old.title && newCover == old.cover) return
            map[seriesId] = old.copy(title = newTitle, cover = newCover)
            saveAll(KEY_FAV, map)
        }
    }

    fun removeFavorite(seriesId: String) {
        synchronized(lock) {
            val map = loadAll(KEY_FAV).toMutableMap()
            map.remove(seriesId)
            saveAll(KEY_FAV, map)
        }
    }

    /** 清空全部收藏（观看历史不受影响）。 */
    fun clearFavorites() {
        synchronized(lock) {
            prefs.edit().remove(KEY_FAV).apply()
        }
    }

    /* ==================== 存储 ==================== */

    private fun loadAll(key: String): Map<String, Entry> {
        val result = LinkedHashMap<String, Entry>()
        val raw = prefs.getString(key, null) ?: return result
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                // 兼容两种格式：旧单条 pos（"pos":1234，仅 lastEp 那集）与新映射 posMap
                val positions = HashMap<Int, Long>()
                val posMap = o.optJSONObject("posMap")
                if (posMap != null) {
                    val keys = posMap.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val ep = k.toIntOrNull() ?: continue
                        val v = posMap.optLong(k, 0L)
                        if (ep > 0 && v > 0) positions[ep] = v
                    }
                }
                val legacyPos = o.optLong("pos", 0L)
                val legacyEp = o.optInt("ep", 0)
                if (legacyPos > 0 && legacyEp > 0 && !positions.containsKey(legacyEp)) {
                    positions[legacyEp] = legacyPos
                }
                result[id] = Entry(
                    id,
                    o.optString("title"),
                    o.optString("cover"),
                    legacyEp,
                    o.optLong("time", 0L),
                    positions
                )
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun saveAll(key: String, map: Map<String, Entry>) {
        val arr = JSONArray()
        for (e in map.values) {
            val o = JSONObject()
            o.put("id", e.seriesId)
            o.put("title", e.title)
            o.put("cover", e.cover)
            o.put("ep", e.lastEp)
            o.put("time", e.time)
            if (e.positions.isNotEmpty()) {
                val posMap = JSONObject()
                for ((ep, pos) in e.positions) posMap.put(ep.toString(), pos)
                o.put("posMap", posMap)
            }
            arr.put(o)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "entries"
        const val KEY_FAV = "favorites"
    }
}
