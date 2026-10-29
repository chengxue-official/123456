package com.houg.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.houg.tv.net.LanInputReceiver
import com.houg.tv.net.LanInputSession
import com.houg.tv.net.NetClient
import com.houg.tv.repo.DramaIndex
import com.houg.tv.util.QrCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 搜索页：内置软键盘 + 大家都在搜 + 搜索历史 三栏，外加跨屏输入（二维码）。
 *
 * 设计取舍：
 *  - **内置软键盘**取代系统 IME：电视遥控器唤不出输入法、老盒子 IME 崩溃是常态，
 *    纯遥控器可打字的键盘是刚需（对齐参考形态）。
 *  - **大家都在搜**：HG没有独立「热词」接口，用官方热播榜的剧名充当热词
 *    （既是真实热门内容，也省一个接口），点击即搜该剧名。
 *  - **跨屏输入**：手机连同一 Wi-Fi 扫码 → 浏览器输入剧名 → 回传电视直接搜索。
 *    比遥控器打字快得多，是「搜长剧名」的主路径。
 *  - 搜索仍保留双通道：本地拼音首字母索引（秒开）+ 服务端接口（补全、修正坏 ID）。
 */
class SearchActivity : AppCompatActivity(), LanInputReceiver {

    /** 全局 density/字号缩放（1280×720dp 设计画布），见 UiScale.warp */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiScale.warp(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var adapter: DramaAdapter
    private lateinit var input: EditText
    private lateinit var progress: View
    private lateinit var statusText: TextView
    private lateinit var panelsRow: View
    private lateinit var resultPanel: View
    private lateinit var hotList: RecyclerView
    private lateinit var hotAdapter: SearchChipAdapter
    private lateinit var historyChips: RecyclerView
    private lateinit var historyAdapter: SearchChipAdapter
    private lateinit var softKeyboard: SoftKeyboard
    /** 跨屏输入二维码弹窗（同一时刻最多一个） */
    private var qrDialog: AlertDialog? = null
    /** 搜索代次：快速连续搜索时丢弃过期响应 */
    private var searchGen = 0
    /** 本地片名索引是否已就绪 */
    private var indexReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiScale.applyImmersive(this)
        // TV 三栏横排；手机竖屏改为「热门/历史在上、内置键盘满宽在下」
        setContentView(
            if (UiScale.isTv(this)) R.layout.activity_search
            else R.layout.activity_search_phone
        )
        // 手机端触控目标：32–44dp 按钮/胶囊抬到 48dp（TV 不生效）
        UiScale.enforceTouchTargets(findViewById(android.R.id.content))

        input = findViewById(R.id.search_input)
        progress = findViewById(R.id.progress)
        statusText = findViewById(R.id.status_text)
        panelsRow = findViewById(R.id.panels_row)
        resultPanel = findViewById(R.id.result_panel)
        hotList = findViewById(R.id.hot_list)
        historyChips = findViewById(R.id.history_chips)
        softKeyboard = findViewById(R.id.soft_keyboard)
        val recycler = findViewById<RecyclerView>(R.id.recycler)

        adapter = DramaAdapter { drama -> openDetail(drama) }
        installAutoGrid(recycler, UiScale.coverWidthDp(this), 2, 10)
        recycler.adapter = adapter

        // 搜索记录：自动换行展示，遥控器可直接导航到每条
        historyAdapter = SearchChipAdapter(
            onClick = { kw -> applyKeyword(kw) },
            onLongClick = { kw ->
                App.searchHistory.remove(kw)
                renderSearchHistory()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
        )
        // ⚠️ 列数必须按「本面板的实际宽度」算，不能用整屏宽。
        // 三栏布局下历史面板只占屏幕约 1/3（960dp 屏 → 约 290dp 宽），
        // 旧代码用 adaptiveGridColumns(this, 130, 2, 8) 按整屏宽算出 7 列，
        // 每列仅约 40dp，连一个关键词胶囊都放不下 —— 文字被 ellipsize 成省略号，
        // 用户于是以为「搜索历史没被记录」（其实磁盘里存着，只是渲染不出来）。
        // 先给个保守列数，待面板完成布局后按实际宽度重算。
        // 手机端改单列满宽：胶囊按内容自适应宽度，不被网格列宽截断（QA Bug-03）。
        historyChips.layoutManager = if (UiScale.isTv(this)) {
            GridLayoutManager(this, CHIP_COLS_MIN)
        } else {
            LinearLayoutManager(this)
        }
        historyChips.adapter = historyAdapter
        historyChips.post { applyHistoryColumns() }

        // 大家都在搜：竖向单列列表（目标形态是逐行铺开，遥控器上下移动即可）
        hotAdapter = SearchChipAdapter(
            onClick = { kw -> applyKeyword(kw) }
        )
        hotList.layoutManager = LinearLayoutManager(this)
        hotList.adapter = hotAdapter

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_do_search).setOnClickListener { doSearch() }
        findViewById<View>(R.id.btn_lan_input).setOnClickListener { showQrInput() }
        findViewById<View>(R.id.btn_clear_history).setOnClickListener { confirmClearHistory() }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        // 手机端用系统输入法：弹出时压缩内容区，避免遮住输入框与热词/历史
        if (!UiScale.isTv(this)) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        // 内置键盘：点键把字符追加到输入框；功能键走删除/清空/搜索
        // 手机端键盘已在布局里隐藏，改用系统输入法（见 showIme），无需绑定
        if (UiScale.isTv(this)) {
            softKeyboard.setOnKey { c -> appendChar(c) }
            softKeyboard.setOnCmd { cmd ->
                when (cmd) {
                    SoftKeyboard.Cmd.BACKSPACE -> backspace()
                    SoftKeyboard.Cmd.CLEAR -> {
                        input.setText("")
                        input.setSelection(0)
                    }
                    SoftKeyboard.Cmd.SEARCH -> doSearch()
                }
            }
        }

        // 先读本地索引；缺失或过期（>7 天）则后台静默重建，期间旧索引继续可用
        val cached = DramaIndex.load(this)
        indexReady = cached.isNotEmpty()
        if (DramaIndex.isStale(this)) {
            rebuildIndexInBackground()
        }

        renderSearchHistory()
        loadHotWords()

        // 绑定跨屏输入会话：本页即接收方。会话是进程级的，
        // 搜索、进详情、返回上一级都不会销毁它（只有空闲超时才自停）。
        LanInputSession.bind(this)

        // 会话快照恢复：从详情页等中断动作返回后，恢复离开时的输入框文本与光标
        LanInputSession.snapshotOrNull()?.let { snap ->
            if (snap.text.isNotEmpty()) {
                input.setText(snap.text)
                input.setSelection(snap.selection.coerceIn(0, snap.text.length))
            }
        }

        // TV：默认焦点给键盘首键（遥控器进页面即可直接打字），而不是输入框；
        // 手机：进页面直接唤起系统输入法（onResume 里统一处理）
        if (UiScale.isTv(this)) softKeyboard.focusFirstKey()
    }

    /**
     * 手机端进入/回到本页时直接唤起系统输入法，省掉「先点一下输入框」这一下。
     * 结果面板可见时（正在看搜索结果）不弹，避免来回切页时反复顶起键盘。
     */
    override fun onResume() {
        super.onResume()
        if (!UiScale.isTv(this) && resultPanel.visibility != View.VISIBLE) showIme()
    }

    /* ==================== 系统输入法（手机） ==================== */

    private fun showIme() {
        input.requestFocus()
        if (input.text.isNotEmpty()) input.setSelection(input.text.length)
        input.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return@post
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /* ==================== 输入辅助 ==================== */

    /** 键盘输入的字符追加到输入框末尾（无论焦点在哪，都以遥控器输入为准）。 */
    private fun appendChar(c: Char) {
        val cur = input.text.toString()
        val next = cur + c
        input.setText(next)
        input.setSelection(next.length)
    }

    private fun backspace() {
        val cur = input.text.toString()
        if (cur.isEmpty()) return
        val next = cur.substring(0, cur.length - 1)
        input.setText(next)
        input.setSelection(next.length)
    }

    /** 用给定关键词填充输入框并立即搜索（历史/热词共用）。 */
    private fun applyKeyword(kw: String) {
        input.setText(kw)
        input.setSelection(kw.length)
        doSearch()
    }

    /* ==================== 大家都在搜 ==================== */

    /**
     * 热词取自HG官方「热播榜」（网页 /rank/hot-drama，免签名）。
     * 只取前 [HOT_MAX] 条剧名——榜单本身是热门内容，比伪造热词更有意义。
     * 失败静默（热词是锦上添花，不该给用户报错）。
     */
    private fun loadHotWords() {
        scope.launch {
            val words = try {
                withContext(Dispatchers.IO) {
                    val board = NetClient.RANK_BOARDS.firstOrNull() ?: return@withContext emptyList()
                    App.netClient.ranking(board, 1).items
                        .map { it.drama.title }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(HOT_MAX)
                }
            } catch (e: Exception) {
                emptyList()
            }
            hotAdapter.submit(words)
        }
    }

    /* ==================== 搜索记录 ==================== */

    /**
     * 按搜索记录面板的**实际宽度**重算网格列数。
     *
     * 三栏布局下该面板只有约 1/3 屏宽，若沿用整屏宽计算列数会导致每列过窄、
     * 关键词被省略号吞掉（用户误以为历史没记录）。这里以「每个胶囊至少
     * [CHIP_MIN_WIDTH_DP]」为下限反推列数，并夹在 [CHIP_COLS_MIN]~[CHIP_COLS_MAX]。
     * 布局尚未完成（width=0）时保持原列数不动，等下一次 post 再算。
     */
    private fun applyHistoryColumns() {
        // 手机端是单列 LinearLayoutManager，不需要也不应改列数
        if (historyChips.layoutManager !is GridLayoutManager) return
        val w = historyChips.width
        if (w <= 0) return
        val cols = (w / dp(CHIP_MIN_WIDTH_DP)).coerceIn(CHIP_COLS_MIN, CHIP_COLS_MAX)
        val lm = historyChips.layoutManager as? GridLayoutManager
        if (lm != null && lm.spanCount == cols) return
        historyChips.layoutManager = GridLayoutManager(this, cols)
    }

    /** dp → px。 */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** 渲染搜索记录胶囊（最多展示 CHIP_MAX 条，避免铺满屏幕）。 */
    private fun renderSearchHistory() {
        val list = App.searchHistory.list()
        historyAdapter.submit(list.take(CHIP_MAX))
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear))
            .setMessage(R.string.clear_search_history_confirm)
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                App.searchHistory.clear()
                renderSearchHistory()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /* ==================== 跨屏输入（二维码） ==================== */

    /**
     * 弹出二维码：确保进程级跨屏输入会话在运行，手机扫码直达输入页。
     *
     * 会话与二维码解耦：
     *  - 服务在首次使用时启动，之后复用同一端口，二维码/网址不会因关窗、
     *    搜索、进详情而失效；
     *  - 关窗只是收起弹窗，手机侧会话继续有效（空闲 10 分钟内可续写）；
     *  - 只有服务真的起不来（如无网络）才提示不可用。
     */
    private fun showQrInput() {
        val port = LanInputSession.ensureStarted()
        val url = LanInputSession.url()
        if (port == 0 || url == null) {
            Toast.makeText(this, R.string.lan_input_unavailable, Toast.LENGTH_LONG).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_qr_input, null)
        val bitmap = QrCode.encode(url, QR_SIZE_PX)
        if (bitmap != null) {
            view.findViewById<ImageView>(R.id.qr_image).setImageBitmap(bitmap)
        }
        view.findViewById<TextView>(R.id.qr_url)
            .text = getString(R.string.scan_url_hint, url)

        qrDialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()
        view.findViewById<View>(R.id.qr_close).setOnClickListener { dismissQrDialog() }
        qrDialog?.show()
    }

    private fun dismissQrDialog() {
        qrDialog?.dismiss()
        qrDialog = null
    }

    /** 手机端提交关键词的回调（网络线程）：切回主线程再动 UI。 */
    override fun onLanKeyword(keyword: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            dismissQrDialog()
            Toast.makeText(this, getString(R.string.lan_input_received, keyword), Toast.LENGTH_SHORT).show()
            if (keyword.isNotBlank()) applyKeyword(keyword)
        }
    }

    /** 离开本页（进详情、回主页等）前保存输入框快照，返回时恢复文本与光标。 */
    override fun onPause() {
        super.onPause()
        val text = input.text.toString()
        LanInputSession.saveSnapshot(text, input.selectionEnd.coerceIn(0, text.length))
    }

    /**
     * 后台重建片名索引（首次进入且无本地缓存时）。
     *
     * 不动 progress/statusText：它们属于结果面板，索引重建时三栏才是可见的；
     * 若在这里无条件隐藏转圈，会把「重建期间用户恰好发起的搜索」的进度也吞掉。
     * 索引没好时搜索走纯服务端，不阻塞用户。
     */
    private fun rebuildIndexInBackground() {
        scope.launch {
            try {
                val list = DramaIndex.rebuild(this@SearchActivity, App.netClient)
                indexReady = list.isNotEmpty()
            } catch (e: Exception) {
                indexReady = false
            }
        }
    }

    /* ==================== 搜索 ==================== */

    private fun doSearch() {
        val keyword = input.text.toString().trim()
        if (keyword.isEmpty()) return
        // 记入搜索历史（去重上浮），并刷新胶囊区
        App.searchHistory.add(keyword)
        renderSearchHistory()
        input.clearFocus()
        // 手机端：收起系统输入法，把屏幕让给搜索结果
        if (!UiScale.isTv(this)) hideIme()

        searchGen++
        val gen = searchGen
        panelsRow.visibility = View.GONE
        resultPanel.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        statusText.visibility = View.GONE
        adapter.submit(emptyList())

        scope.launch {
            // 服务端增量按 id 累积（保留首次出现顺序），每来一批就与本地结果合并上屏
            val acc = LinkedHashMap<String, NetClient.Drama>()
            try {
                // 本地首字母/标题匹配（索引已就绪时先出结果，保证秒开）
                val local = if (indexReady) {
                    withContext(Dispatchers.IO) { DramaIndex.search(keyword) }
                } else emptyList()
                val localDramas = local.map { e ->
                    NetClient.Drama.minimal(e.id, e.title, e.cover)
                }
                if (gen == searchGen && localDramas.isNotEmpty()) {
                    adapter.submit(localDramas)
                }

                // 服务端搜索兜底/补全：本地索引来自分类翻页（每分类有页数上限），
                // 排在截断点之后的剧会缺失（例：多季剧缺个别季）。因此本地命中后
                // 仍查一次服务端，用更全的数据合并修正。
                // 流式接收：官方腿每翻完一页就先上屏，用户不必干等整条腿跑完；
                // 也正因为不再挡首屏，NetClient 才敢把这条腿的翻页预算放宽到 12s。
                val remote = withContext(Dispatchers.IO) {
                    App.netClient.search(keyword, NetClient.SearchSink { batch, done ->
                        // done=true 那批就是最终全量，与返回值相同，交给下面统一提交
                        if (!done) {
                            val pending = batch.toList()
                            runOnUiThread {
                                if (gen == searchGen) {
                                    for (d in pending) {
                                        if (!acc.containsKey(d.id)) acc[d.id] = d
                                    }
                                    val partial = mergeResults(localDramas, acc.values.toList())
                                    adapter.submit(partial)
                                    // 屏上已有结果，居中的转圈就别压在上面
                                    if (partial.isNotEmpty()) progress.visibility = View.GONE
                                }
                            }
                        }
                    })
                }
                if (gen != searchGen) return@launch
                val merged = mergeResults(localDramas, remote)
                adapter.submit(merged)
                if (merged.isEmpty()) {
                    statusText.text = getString(R.string.empty)
                    statusText.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                if (gen != searchGen) return@launch
                if (adapter.itemCount == 0) {
                    statusText.text = getString(R.string.load_failed)
                    statusText.visibility = View.VISIBLE
                }
            } finally {
                if (gen == searchGen) progress.visibility = View.GONE
            }
        }
    }

    /**
     * 合并本地与服务端结果：本地序优先，服务端新增的排在后面。
     * 去重按 id 和归一化标题双键：
     * - 同 id：用服务端更全的数据替换（本地 minimal 只有 id/title/cover）；
     * - 同标题不同 id：保留服务端那条（本地索引的 id 可能是旧版精度 bug
     *   写入磁盘的坏 ID，点进去必然 404，用户看到的「重复的第十季，
     *   一个连不上一个能看」即此因）。
     */
    private fun mergeResults(
        local: List<NetClient.Drama>,
        remote: List<NetClient.Drama>,
    ): List<NetClient.Drama> {
        if (local.isEmpty()) return remote
        if (remote.isEmpty()) return local
        val remoteById = HashMap<String, NetClient.Drama>()
        val remoteByTitle = HashMap<String, NetClient.Drama>()
        for (d in remote) {
            remoteById[d.id] = d
            remoteByTitle[normKey(d.title)] = d
        }
        val seenIds = HashSet<String>()
        val seenTitles = HashSet<String>()
        val result = ArrayList<NetClient.Drama>(local.size + remote.size)
        for (d in local) {
            val tk = normKey(d.title)
            val better = remoteByTitle[tk] ?: remoteById[d.id] ?: d
            if (!seenIds.add(better.id)) continue
            if (!seenTitles.add(tk)) continue
            result.add(better)
        }
        for (d in remote) {
            if (!seenIds.add(d.id)) continue
            if (!seenTitles.add(normKey(d.title))) continue
            result.add(d)
        }
        return result
    }

    /** 标题归一化去重键：去空白与常见标点、全角转半角、转小写。 */
    private fun normKey(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val v = c.code
            var ch = c
            if (v in 0xFF01..0xFF5E) ch = (v - 0xFEE0).toChar()
            if (ch == '　') continue
            if (ch.isWhitespace()) continue
            if (ch in "《》·_-：:，,！!？?\"'“”‘’") continue
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun openDetail(drama: NetClient.Drama) {
        val intent = Intent(this, DetailActivity::class.java)
        intent.putExtra(DetailActivity.EXTRA_SERIES_ID, drama.id)
        intent.putExtra(DetailActivity.EXTRA_TITLE, drama.title)
        intent.putExtra(DetailActivity.EXTRA_COVER, drama.cover)
        startActivity(intent)
    }

    /**
     * 返回键优先「退回三栏」而不是直接退出：
     * 看完搜索结果想改关键词时，一步回键盘比重新进搜索页顺手。
     * 焦点仍落键盘首键（与进页面时一致），遥控器可继续上下键改词。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && resultPanel.visibility == View.VISIBLE) {
            resultPanel.visibility = View.GONE
            panelsRow.visibility = View.VISIBLE
            // TV 交回键盘首键；手机重新唤起系统输入法，落点就在输入框末尾
            if (UiScale.isTv(this)) softKeyboard.focusFirstKey() else showIme()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        dismissQrDialog()
        // 只解绑接收方，不销毁会话：会话是进程级的，下次进搜索页直接复用，
        // 手机端此前的网址与草稿仍然有效（空闲超时后才会自动关闭）。
        LanInputSession.unbind(this)
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        /** 搜索记录最多展示的胶囊数（超出需点「清空」管理，避免铺满屏幕） */
        const val CHIP_MAX = 12

        /** 热词条数（榜单前 N 条剧名，够填满中栏即可） */
        const val HOT_MAX = 10

        /** 二维码位图边长（dp 换算前的 px 基准，实际展示 220dp） */
        const val QR_SIZE_PX = 660

        /** 搜索记录胶囊最小宽度（dp），列数按面板实际宽度反推 */
        const val CHIP_MIN_WIDTH_DP = 110

        /** 搜索记录网格最小/最大列数（三栏布局下历史面板仅约 1/3 屏宽） */
        const val CHIP_COLS_MIN = 1
        const val CHIP_COLS_MAX = 4
    }
}
