package com.houg.tv.net

import java.lang.ref.WeakReference

/** 跨屏输入的接收方（搜索页实现）。绑定/解绑都走 [LanInputSession]，用弱引用避免泄漏。 */
interface LanInputReceiver {
    fun onLanKeyword(keyword: String)
}

/**
 * 进程级跨屏输入会话。
 *
 * 为什么需要它（历史故障）：
 *  - 旧实现每次点开二维码都新建 [LanInputServer]（换随机端口），弹窗一关就 stop。
 *    结果：手机端提交一次后再发就连接被拒；关掉二维码或返回上一级后，手机上
 *    那个页面彻底失效，只能重新扫码。
 *
 * 现在的生命期：
 *  - 服务是**进程级单例**，与二维码弹窗、单次提交都无关；
 *  - 空闲 [TTL_MS]（10 分钟）内没有任何「有效活动」才自动关闭；
 *  - 有效活动 = 电视端搜索页仍绑定了接收方时的任何请求。电视端一旦解绑
 *    （页面销毁），手机端每 15s 的探活轮询就不再续命，服务最多再活 10 分钟。
 *
 * 续传的两条通道：
 *  - 手机端：会话 id（sid）随二维码 URL 下发，页面刷新/关掉重开都凭它调
 *    `/state` 取回未提交的组合串与光标；
 *  - 电视端：本类保存输入框快照（文本 + 光标），页面重建后由 SearchActivity
 *    恢复，再 bind() 重新接上手机端发来的关键词。
 *
 * 注意：本类刻意不依赖任何 Android API（只用 WeakReference / 系统时钟），
 * 便于在 JVM 上直接编译做协议级验证。
 */
object LanInputSession {

    /** 会话空闲上限：电视端无接收方后，最多再保留这么久 */
    const val TTL_MS = 10 * 60 * 1000L

    /** 电视端输入框快照（返回页面后恢复文本/光标） */
    data class Snapshot(val text: String, val selection: Int, val at: Long)

    private val lock = Any()
    private var server: LanInputServer? = null
    private var receiverRef: WeakReference<LanInputReceiver>? = null
    private var snapshot: Snapshot? = null

    /** 服务是否在跑。 */
    fun isAlive(): Boolean = synchronized(lock) { (server?.port ?: 0) != 0 }

    fun port(): Int = synchronized(lock) { server?.port ?: 0 }

    /**
     * 确保服务已启动，返回端口；失败返回 0。
     * 幂等：已有存活服务时直接复用（端口不变 → 手机上已打开的页面/二维码继续有效）。
     */
    fun ensureStarted(): Int = synchronized(lock) {
        server?.let { existing ->
            if (existing.port != 0) return existing.port
        }
        val created = LanInputServer().apply {
            idleTimeoutMs = TTL_MS
            onKeyword = { keyword -> dispatch(keyword) }
            receiverAvailable = { currentReceiver() != null }
        }
        val p = created.start()
        if (p == 0) {
            created.stop()
            return 0
        }
        server = created
        p
    }

    /** 二维码/手输地址（带 sid）；服务未就绪或取不到局域网地址时返回 null。 */
    fun url(): String? = synchronized(lock) {
        val s = server ?: return null
        val p = s.port
        if (p == 0) return null
        val addr = s.localAddress() ?: return null
        "http://$addr:$p/?sid=${s.sessionId}"
    }

    /** 绑定接收方（页面重建后再次绑定即可续传）。 */
    fun bind(receiver: LanInputReceiver) = synchronized(lock) {
        receiverRef = WeakReference(receiver)
    }

    fun unbind(receiver: LanInputReceiver) = synchronized(lock) {
        if (receiverRef?.get() === receiver) receiverRef = null
    }

    /** 当前是否有人能接收关键词（决定手机端提交是否 503）。 */
    fun canReceive(): Boolean = currentReceiver() != null

    /** 保存电视端输入框快照（onPause 时调用）。 */
    fun saveSnapshot(text: String, selection: Int) = synchronized(lock) {
        snapshot = Snapshot(text, selection, System.currentTimeMillis())
    }

    /** 取快照；超过 TTL 视为失效（不恢复过期内容）。 */
    fun snapshotOrNull(): Snapshot? = synchronized(lock) {
        val s = snapshot ?: return null
        if (System.currentTimeMillis() - s.at > TTL_MS) {
            snapshot = null
            null
        } else {
            s
        }
    }

    /** 关闭会话（仅供调试/测试；正常流程靠空闲 TTL 自动关闭）。 */
    fun stop() = synchronized(lock) {
        try { server?.stop() } catch (_: Throwable) {}
        server = null
    }

    private fun currentReceiver(): LanInputReceiver? = synchronized(lock) {
        val r = receiverRef?.get()
        if (r == null) receiverRef = null
        r
    }

    private fun dispatch(keyword: String) {
        currentReceiver()?.onLanKeyword(keyword)
    }
}
