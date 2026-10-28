package com.houg.tv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 内置软键盘（TV 用）：不依赖系统 IME。
 *
 * 电视/盒子上系统输入法常唤不出来（尤其遥控器机型），搜索页需要能纯遥控器打字。
 * 布局对齐用户给定的参考形态：字母表按 **A-Z 顺序** 每行 6 键排布（比 QWERTY
 * 更适合遥控器方向键导航——目标键的相邻关系稳定），最后一行为数字 0-9，
 * 末尾一行功能键「清空 / 删除 / 搜索」。
 *
 * 交互：光标所在键呈红底白框（[buildKeyBg]），方向键移动、OK 键输入。
 */
class SoftKeyboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Cmd { BACKSPACE, CLEAR, SEARCH }

    private var onKey: ((Char) -> Unit)? = null
    private var onCmd: ((Cmd) -> Unit)? = null

    init {
        orientation = VERTICAL
        setPadding(dp(6), dp(8), dp(6), dp(8))
        build()
    }

    fun setOnKey(listener: (Char) -> Unit) { this.onKey = listener }
    fun setOnCmd(listener: (Cmd) -> Unit) { this.onCmd = listener }

    /**
     * 把遥控器焦点落到第一个字母键（A）。
     *
     * 搜索页默认焦点给键盘而不是输入框：遥控器用户进页面即可直接 D-pad 打字；
     * 若默认落在输入框，用户按键会以为「没反应」（系统 IME 在电视上常唤不出）。
     * 触摸设备仍可点输入框走系统输入法。
     */
    fun focusFirstKey() {
        firstKey?.requestFocus()
    }

    private var firstKey: TextView? = null

    private fun build() {
        // 字母 A-Z，每行 6 键（A-F / G-L / M-R / S-X / Y-Z + 数字 1-4）
        addView(buildRow("ABCDEF"))
        addView(buildRow("GHIJKL"))
        addView(buildRow("MNOPQR"))
        addView(buildRow("STUVWX"))
        addView(buildRow("YZ1234"))
        addView(buildRow("567890"))
        addView(buildFuncRow())
    }

    private fun buildRow(chars: String): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        for (c in chars) {
            row.addView(buildKey(c.toString(), 1f) { onKey?.invoke(c) })
        }
        // 补齐到 6 列，保证各行键宽一致（方向键上下移动时纵向对齐）
        for (i in chars.length until 6) {
            row.addView(buildKey("", 1f, null))
        }
        return row
    }

    private fun buildFuncRow(): LinearLayout {
        val row = LinearLayout(context)
        row.orientation = HORIZONTAL
        row.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        row.addView(buildKey(getString(R.string.clear), 2f) { onCmd?.invoke(Cmd.CLEAR) })
        row.addView(buildKey(getString(R.string.key_delete), 2f) { onCmd?.invoke(Cmd.BACKSPACE) })
        row.addView(buildKey(getString(R.string.nav_search), 2f) { onCmd?.invoke(Cmd.SEARCH) })
        return row
    }

    private fun buildKey(text: String, weight: Float, onClick: (() -> Unit)?): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.textSize = 17f
        tv.setTextColor(Color.WHITE)
        tv.background = makeKeyBg(false)
        if (onClick != null) {
            tv.isClickable = true
            tv.isFocusable = true
            tv.setOnClickListener { onClick() }
            tv.setOnFocusChangeListener { v, hasFocus ->
                v.background = makeKeyBg(hasFocus)
            }
            if (firstKey == null && text.isNotEmpty()) firstKey = tv
        } else {
            // 占位键：不可聚焦、不可点，仅用于撑齐列宽
            tv.isClickable = false
            tv.isFocusable = false
            tv.background = null
        }
        val lp = LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        tv.layoutParams = lp
        return tv
    }

    /** 键背景：常态深灰圆角，聚焦态红底 + 白描边（遥控器焦点一眼可见）。 */
    private fun makeKeyBg(focused: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.cornerRadius = dp(5).toFloat()
        d.setColor(Color.parseColor(if (focused) "#E53935" else "#242424"))
        if (focused) d.setStroke(dp(2), Color.WHITE)
        return d
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun getString(resId: Int): String = context.getString(resId)
}
