package com.houg.tv

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.roundToInt

/**
 * 固定宽高比封面（150:205，HG卡片比例）：宽度确定后按比例定高。
 * 项目未引入 ConstraintLayout，用 onMeasure 自实现 dimension ratio，零新增依赖。
 */
class RatioCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        if (w > 0) {
            setMeasuredDimension(w, (w * RATIO_H / RATIO_W).roundToInt())
        } else {
            super.onMeasure(widthSpec, heightSpec)
        }
    }

    private companion object {
        const val RATIO_W = 150f
        const val RATIO_H = 205f
    }
}
