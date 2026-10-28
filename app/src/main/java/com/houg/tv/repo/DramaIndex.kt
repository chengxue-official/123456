package com.houg.tv.repo

import android.content.Context
import com.houg.tv.net.NetClient
import com.houg.tv.util.AppLog
import com.houg.tv.util.Pinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地片名索引：拉取红果全量片单（3 个分类），把剧名转成拼音首字母，
 * 供「首字母搜索」本地匹配。索引持久化到 filesDir，避免每次冷启动重拉。
 *
 * 并发与可靠性约定（线上事故教训）：
 *  - **单飞**：两个 SearchActivity 实例并发 rebuild 时只允许一个真正拉取，
 *    拿锁后先检查「是否已有新鲜索引」，避免重复全量拉取；
 *  - **空结果不落盘不覆盖**：全部分类拉取失败时保留旧索引（哪怕旧数据也比搜不到强），
 *    严禁把内存/磁盘索引覆写成空（1.0.19 线上「3412 条 → 0 条」的真因）；
 *  - **部分失败合并**：任一分类失败时，用旧索引按 id 补齐缺失条目；
 *  - **过期刷新**：索引带 builtAt，超过 [INDEX_TTL_MS] 由调用方触发后台重建，
 *    重建期间旧索引继续可用（搜索不等重建）。
 */
object DramaIndex {

    private const val FILE = "drama_index.json"
    private const val TAG = "DramaIndex"
    /**
     * 索引结构/抓取策略版本。凡是改变「拉取范围或字段」的改动都必须 +1，
     * 否则老设备会一直沿用磁盘上的旧索引。
     * v3：加入 builtAt（TTL 刷新）+ 防覆写/合并策略。
     */
    private const val SCHEMA_VERSION = 3
    /**
     * 每个分类最多翻的页数（每页 18 条，实际到服务端无更多数据即提前停）。
     * 注意：ai_series 实测在 offset=1080 时 has_more 仍为 true，即分类池超过
     * 60×18=1080 条。若上限太低，排在后面的剧（如多季剧的个别季）会被截断，
     * 导致本地首字母搜索漏剧。200 页 × 18 ≈ 3600 条/分类，足以覆盖现有分类池。
     */
    private const val MAX_PAGES_PER_GENRE = 200

    /** 索引最长保留时长：超过则视为过期，由调用方在后台静默重建。 */
    private const val INDEX_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** 片名索引项 */
    data class Entry(val id: String, val title: String, val cover: String, val initials: String)

    /** 三个分类（对齐 MainActivity 的 genres） */
    private val GENRES = listOf(
        Pair("short_play", "default"),
        Pair("comic_series", "comic_series"),
        Pair("ai_series", "ai_series"),
    )

    @Volatile
    private var entries: List<Entry>? = null

    /** 索引构建时间（内存与磁盘同步维护；0 表示未知/旧格式）。 */
    @Volatile
    private var builtAt: Long = 0L

    /** 单飞锁：并发 rebuild 只允许一个真正拉取。 */
    private val rebuildMutex = Mutex()

    /**
     * 读索引（内存 → 磁盘）。磁盘索引版本与 [SCHEMA_VERSION] 不一致时
     * 视为失效，返回空列表以触发重建。可能返回空列表（尚未建立）。
     */
    fun load(context: Context): List<Entry> {
        entries?.let { return it }
        synchronized(this) {
            entries?.let { return it }
            val fromDisk = readFromDisk(context)
            entries = fromDisk
            return fromDisk
        }
    }

    /**
     * 从服务端拉全量并持久化，返回最新索引。在 IO 线程调用（挂起函数）。
     *
     * 单飞：并发调用时只有第一个真正拉取，其余等锁后复用结果。
     * 失败语义：单分类失败不中断其它分类；整体为空时保留旧索引并返回旧数据。
     */
    suspend fun rebuild(context: Context, netClient: NetClient): List<Entry> = withContext(Dispatchers.IO) {
        rebuildMutex.withLock {
            val old = entries ?: emptyList()
            // 拿锁期间已有其它调用完成重建且结果仍新鲜 → 直接复用
            if (old.isNotEmpty() && !staleLocked()) {
                return@withLock old
            }
            val list = ArrayList<Entry>()
            var partial = false
            for ((genre, scene) in GENRES) {
                try {
                    val dramas = netClient.catalogAll(genre, scene, MAX_PAGES_PER_GENRE)
                    if (dramas.isEmpty()) partial = true
                    for (d in dramas) {
                        val initials = Pinyin.firstLetters(d.title)
                        if (initials.isNotEmpty()) {
                            list.add(Entry(d.id, d.title, d.cover, initials))
                        }
                    }
                    AppLog.d(TAG, "genre=$genre 索引 " + dramas.size + " 条")
                } catch (e: Exception) {
                    partial = true
                    AppLog.e(TAG, "genre=$genre 拉取失败", e)
                }
            }
            var deduped = list.distinctBy { it.id }
            // 部分失败：用旧索引按 id 补齐，避免漏剧
            if (partial && old.isNotEmpty()) {
                val have = HashSet<String>(deduped.size * 2)
                for (e in deduped) have.add(e.id)
                val merged = ArrayList<Entry>(deduped.size + old.size)
                merged.addAll(deduped)
                for (o in old) {
                    if (o.id.isNotEmpty() && have.add(o.id)) merged.add(o)
                }
                deduped = merged
                AppLog.d(TAG, "部分分类失败，已用旧索引补齐至 " + deduped.size + " 条")
            }
            if (deduped.isEmpty()) {
                // 空结果严禁落盘/覆盖：全部分类失败时旧索引是唯一资产
                AppLog.e(TAG, "重建结果为空，保留旧索引 " + old.size + " 条", null)
                return@withLock old
            }
            builtAt = System.currentTimeMillis()
            writeToDisk(context, deduped)
            entries = deduped
            AppLog.d(TAG, "索引重建完成，共 " + deduped.size + " 条")
            deduped
        }
    }

    /** 是否已有可用索引。 */
    fun isReady(): Boolean {
        val list = entries ?: return false
        return list.isNotEmpty()
    }

    /**
     * 索引是否过期（空 = 过期；非空看 builtAt 距今是否超过 [INDEX_TTL_MS]）。
     * 调用方据此决定是否后台重建；重建期间旧索引继续可用。
     */
    fun isStale(context: Context): Boolean {
        val list = entries ?: load(context)
        if (list.isEmpty()) return true
        return System.currentTimeMillis() - builtAt > INDEX_TTL_MS
    }

    /** 锁内判断当前内存索引是否过期（不再触发磁盘读）。 */
    private fun staleLocked(): Boolean {
        val list = entries ?: return true
        if (list.isEmpty()) return true
        return System.currentTimeMillis() - builtAt > INDEX_TTL_MS
    }

    /**
     * 按输入匹配：纯字母 → 首字母前缀匹配；含中文 → 标题匹配。
     *
     * 两段式排序：前缀命中（更精确）排在包含命中之前——旧实现
     * `startsWith || contains` 把两类混在一起且按遍历顺序返回，
     * 用户搜「LJ」时「绿茶」这类包含命中会被淹没在遍历顺序里。
     *
     * 前缀匹配按「逐字」比对而非直接 `initials.startsWith`：
     * 索引里的首字母串只记 GB2312 主读音（「重生」→ ZS），
     * 用户却常按另一读音输入（CS）。逐字比对时多音字允许任一读音命中，
     * 两种输入都能搜到同一部剧。
     *
     * @param limit 最大返回条数
     */
    fun search(input: String, limit: Int = 50): List<Entry> {
        val e = entries ?: return emptyList()
        val q = input.trim()
        if (q.isEmpty()) return emptyList()
        val isLatin = q.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        val upper = q.uppercase()
        val prefix = ArrayList<Entry>()
        val contains = ArrayList<Entry>()
        for (item in e) {
            if (isLatin) {
                if (Pinyin.matchesPrefix(item.title, upper)) prefix.add(item)
                else if (item.initials.contains(upper)) contains.add(item)
            } else {
                if (item.title.startsWith(q)) prefix.add(item)
                else if (item.title.contains(q)) contains.add(item)
            }
        }
        val result = ArrayList<Entry>(prefix.size + contains.size)
        result.addAll(prefix)
        result.addAll(contains)
        // 精确匹配空手时走模糊兜底（打错一两个字也能搜到），字母输入不参与
        val hits = if (result.isEmpty() && !isLatin) fuzzyMatch(e, q) else result
        return if (hits.size <= limit) hits else hits.subList(0, limit)
    }

    /**
     * 模糊匹配：仅在精确匹配空手时启用。归一化（去空白/标点、小写）后算
     * Levenshtein 距离，查询 ≤3 字容忍 1 个字、更长容忍 2 个字；按距离升序，
     * 距离相同按片名短的在前。查询不足 2 字不做（1 个字误差太大、噪声多）。
     */
    private fun fuzzyMatch(list: List<Entry>, q: String): List<Entry> {
        val nq = normalize(q)
        if (nq.length < 2) return emptyList()
        val maxEdit = if (nq.length <= 3) 1 else 2
        val scored = ArrayList<Pair<Int, Entry>>()
        for (item in list) {
            val nt = normalize(item.title)
            if (nt.isEmpty()) continue
            val diff = if (nt.length > nq.length) nt.length - nq.length else nq.length - nt.length
            if (diff > maxEdit) continue
            val d = editDistance(nq, nt, maxEdit)
            if (d <= maxEdit) scored.add(d to item)
        }
        scored.sortWith(compareBy({ it.first }, { it.second.title.length }))
        return scored.map { it.second }
    }

    /** 归一化：全角转半角、去空白与常见标点、转小写（与 NetClient.normTitle 同规则）。 */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (raw in s) {
            var c = raw
            if (c in '\uFF01'..'\uFF5E') c = c - 0xFEE0
            if (c == '\u3000') continue
            if (c.isWhitespace() || c in "《》·_－-：:，,！!？?\"'“”‘’") continue
            sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    /** Levenshtein 距离；某一行最小值已超 [cap] 时提前返回 cap+1（剪枝）。 */
    private fun editDistance(a: String, b: String, cap: Int): Int {
        val n = a.length
        val m = b.length
        if (n == 0) return m
        if (m == 0) return n
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > cap) return cap + 1
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[m]
    }

    private fun readFromDisk(context: Context): List<Entry> {
        return try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return emptyList()
            val raw = f.readText()
            val list = ArrayList<Entry>()
            // 新版格式：{"v":3,"builtAt":...,"list":[...]}；旧版裸数组（无版本）一律过期。
            if (raw.trimStart().startsWith("[")) {
                AppLog.d(TAG, "索引为旧版格式，丢弃并重建")
                return emptyList()
            }
            val root = JSONObject(raw)
            if (root.optInt("v", 0) != SCHEMA_VERSION) {
                AppLog.d(TAG, "索引版本不匹配（磁盘=" + root.optInt("v", 0)
                        + "，期望=" + SCHEMA_VERSION + "），丢弃并重建")
                return emptyList()
            }
            builtAt = root.optLong("builtAt", 0L)
            val json = root.optJSONArray("list") ?: return emptyList()
            for (i in 0 until json.length()) {
                val o = json.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val title = o.optString("title")
                val cover = o.optString("cover")
                val initials = o.optString("initials")
                if (id.isNotEmpty() && title.isNotEmpty()) {
                    list.add(Entry(id, title, cover, initials))
                }
            }
            list
        } catch (e: Exception) {
            AppLog.e(TAG, "读取索引失败", e)
            emptyList()
        }
    }

    private fun writeToDisk(context: Context, list: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                val o = JSONObject()
                o.put("id", e.id)
                o.put("title", e.title)
                o.put("cover", e.cover)
                o.put("initials", e.initials)
                arr.put(o)
            }
            val root = JSONObject()
            root.put("v", SCHEMA_VERSION)
            root.put("builtAt", builtAt)
            root.put("list", arr)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(File(context.filesDir, FILE))) {
                // 原子替换失败（个别机型 tmpfs 异常）：退回直接写
                File(context.filesDir, FILE).writeText(root.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "写索引失败", e)
        }
    }
}
