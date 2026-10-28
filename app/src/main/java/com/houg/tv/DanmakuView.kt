package com.houg.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.houg.tv.net.NetClient
import kotlin.math.abs

/**
 * 滚动弹幕 View：从屏幕右缘外侧完整滑到左缘外侧，多轨道自动排布。
 *
 * 位置由播放时钟驱动（基准位置 + 墙钟经过时间 × 倍速），播放器每 500ms 校准一次。
 *
 * ⚠️ 两条「不能中途消失」的设计要点（对齐 B站/抖音的观感）：
 *
 * 1. **进度基准与弹幕时间点解耦**。旧实现用 `elapsed = 播放位置 - 弹幕时间点` 算进度，
 *    但弹幕是**异步拉取**的（网络到达通常比弹幕时间点晚 1~2 秒），于是弹幕一出生就
 *    落在屏幕中间、只走完残缺的一段就消失，观感就是"从右边出来，走到中间就没了"。
 *    现在改为：每条弹幕 spawn 时记录当时的播放位置 [Flying.spawnPosMs]，
 *    进度 = `(当前位置 - spawnPosMs) / 全程时长`，**任何弹幕只要出现，就在右缘外侧
 *    出生并走完整条路径**。数据晚到只会让它稍晚一点从右边进来，不会半路出现。
 *
 * 2. **不在飞行中途强摘弹幕**。旧实现在轨道占满时会把最旧的飞行弹幕直接删掉，
 *    视觉上同样是"弹幕凭空消失"。现在轨道占用改为**几何判定**：只有当同轨道上一条
 *    弹幕的右边缘已经让开屏幕右缘（+间距）时，才允许新弹幕进入该轨道；
 *    若当下没有可用轨道，新弹幕排队等待（超时丢弃），绝不摘掉正在飞的。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class Flying(
        val item: NetClient.DanmakuItem,
        val lane: Int,
        val width: Float,
        /** spawn 那一刻的播放位置（ms）——进度基准，与 item.timeMs 解耦 */
        val spawnPosMs: Long,
        /** 走完全程所需的「播放时钟」时长（ms），已按倍速换算 */
        val travelMs: Long
    )

    /** 走完整个屏幕宽度（不含文字自身宽度）所需的墙钟时间 */
    private val screenTravelWallMs = 8000L
    /** 轨道数量（弹幕区占画面高度 55%，1080p 下 6 条互不重叠） */
    private val laneCount = 6

    private val items = ArrayList<NetClient.DanmakuItem>()
    private val flying = ArrayList<Flying>()
    private var pendingIndex = 0

    private var basePositionMs = 0L
    private var baseWallTime = 0L
    private var playing = false
    private var rate = 1f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var textSizePx = 0f
    private var lineHeightPx = 0f
    private var lastWidth = -1

    init {
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 3f
        strokePaint.color = 0xFF000000.toInt()
        fillPaint.color = 0xFFFFFFFF.toInt()
        updateTextSize()
    }

    /** 累积追加一个窗口的弹幕（按时间归并去重），不覆盖已有窗口数据。 */
    fun addItems(list: List<NetClient.DanmakuItem>) {
        for (it in list) {
            // 按 (timeMs, id) 去重，避免倒退重放后重复
            val dup = items.any { e -> e.timeMs == it.timeMs && e.id == it.id }
            if (!dup) items.add(it)
        }
        items.sortBy { it.timeMs }
        invalidate()
    }

    /**
     * 播放位置校准（每 500ms 一次）。
     *
     * 位置发生**跳变**（seek / 切集）时：在途弹幕的进度基准已失效，清空重来，
     * 并把扫描指针推进到当前位置附近，避免把跳变前的老弹幕补发一遍刷屏。
     */
    fun setPositionMs(pos: Long) {
        val expected = currentPositionMs()
        if (abs(pos - expected) > JUMP_TOLERANCE_MS) {
            flying.clear()
            var i = 0
            while (i < items.size && items[i].timeMs < pos - DROP_AFTER_MS) i++
            pendingIndex = i
        }
        basePositionMs = pos
        baseWallTime = SystemClock.uptimeMillis()
        invalidate()
    }

    fun setPlaying(p: Boolean) {
        if (playing != p) baseWallTime = SystemClock.uptimeMillis()
        playing = p
        invalidate()
    }

    fun setRate(r: Float) {
        if (rate != r) baseWallTime = SystemClock.uptimeMillis()
        rate = if (r > 0f) r else 1f
        invalidate()
    }

    fun reset() {
        items.clear()
        pendingIndex = 0
        flying.clear()
        basePositionMs = 0L
        baseWallTime = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun currentPositionMs(): Long {
        if (!playing) return basePositionMs
        val elapsed = (SystemClock.uptimeMillis() - baseWallTime) * rate
        return basePositionMs + elapsed.toLong()
    }

    /**
     * 单条弹幕走完全程所需的墙钟时长：保持**恒定像素速度**，
     * 所以文字越宽停留越久（距离 = 屏宽 + 文字宽）。
     */
    private fun travelWallMsFor(textWidth: Float, viewWidth: Int): Long {
        val w = viewWidth.toFloat()
        if (w <= 0f) return screenTravelWallMs
        return (screenTravelWallMs * (w + textWidth) / w).toLong().coerceAtLeast(2000L)
    }

    /** 换算为播放时钟时长：播放位置前进速度 = rate × 墙钟，故需乘以 rate。 */
    private fun travelVideoMsFor(textWidth: Float, viewWidth: Int): Long =
        (travelWallMsFor(textWidth, viewWidth) * rate).toLong().coerceAtLeast(2000L)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != lastWidth) {
            lastWidth = w
            updateTextSize()
            flying.clear()
            pendingIndex = 0
        }
    }

    private fun updateTextSize() {
        val sp = if (lastWidth in 1..599) 16f else 20f
        textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
        lineHeightPx = textSizePx * 1.5f
        fillPaint.textSize = textSizePx
        strokePaint.textSize = textSizePx
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        if (w <= 0) {
            postInvalidateOnAnimation()
            return
        }
        val pos = currentPositionMs()
        spawnDue(pos, w)
        removeFinished(pos)

        val areaHeight = height * 0.55f
        val fm = fillPaint.fontMetrics
        for (f in flying) {
            val elapsed = (pos - f.spawnPosMs).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / f.travelMs.toFloat()).coerceIn(0f, 1f)
            // progress 0 → 文字左缘在屏幕右缘（完全在画面外）；1 → 完全移出左缘
            val x = w - (w + f.width) * progress
            val laneTop = lineHeightPx * 0.5f + f.lane * lineHeightPx
            if (laneTop > areaHeight) continue
            val baseY = laneTop + lineHeightPx / 2f + (fm.bottom - fm.top) / 2f - fm.bottom
            canvas.drawText(f.item.text, x, baseY, strokePaint)
            canvas.drawText(f.item.text, x, baseY, fillPaint)
        }
        // 播放中且还有待出场/在飞弹幕时持续重绘；暂停时位置静止，无需空转
        if (playing && (flying.isNotEmpty() || pendingIndex < items.size)) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * 让所有「到点」的弹幕出场。
     * - timeMs 远早于当前播放位置（seek 后残留 / 网络晚到太多）→ 丢弃，不补发；
     * - 当下没有可用轨道 → 本帧不出场，等下一帧（**不摘掉正在飞的弹幕**）。
     */
    private fun spawnDue(pos: Long, viewWidth: Int) {
        while (pendingIndex < items.size) {
            val it = items[pendingIndex]
            if (it.timeMs > pos) break
            val late = pos - it.timeMs
            if (late > DROP_AFTER_MS) {
                pendingIndex++
                continue
            }
            val textW = fillPaint.measureText(it.text)
            val lane = pickLane(textW, pos, viewWidth) ?: break
            pendingIndex++
            flying.add(Flying(it, lane, textW, pos, travelVideoMsFor(textW, viewWidth)))
        }
    }

    /**
     * 挑选可用轨道（几何判定）：只有该轨道上一条弹幕的右边缘已让开屏幕右缘
     * （留出 [laneGapPx] 间距）才可用，保证同轨道两条弹幕不重叠。全部占满返回 null。
     */
    private fun pickLane(textW: Float, pos: Long, viewWidth: Int): Int? {
        val gap = textSizePx * 0.6f
        for (lane in 0 until laneCount) {
            var blocked = false
            for (f in flying) {
                if (f.lane != lane) continue
                val elapsed = (pos - f.spawnPosMs).coerceAtLeast(0L)
                val progress = (elapsed.toFloat() / f.travelMs.toFloat()).coerceIn(0f, 1f)
                val x = viewWidth - (viewWidth + f.width) * progress
                if (x + f.width + gap > viewWidth) {
                    blocked = true
                    break
                }
            }
            if (!blocked) return lane
        }
        return null
    }

    /** 走完全程即移除（基于 spawn 位置基准，不受数据晚到影响）。 */
    private fun removeFinished(pos: Long) {
        var i = flying.size - 1
        while (i >= 0) {
            val f = flying[i]
            if (pos - f.spawnPosMs >= f.travelMs) flying.removeAt(i)
            i--
        }
    }

    private companion object {
        /** 位置跳变判定阈值：超过即视为 seek/切集，重基准（缓冲抖动不会被误判） */
        const val JUMP_TOLERANCE_MS = 3_000L
        /** 弹幕时间点过去超过此时长仍未出场则丢弃（不补发老弹幕） */
        const val DROP_AFTER_MS = 3_000L
    }
}
