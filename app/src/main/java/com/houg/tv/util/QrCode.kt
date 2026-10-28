package com.houg.tv.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码生成（跨屏输入用）。
 *
 * 只依赖 zxing core（纯 Java，无 Android 组件），生成黑白点阵 Bitmap 供 ImageView 展示。
 * 纠错级别用 M（15%）：电视上远距离拍摄容易糊，M 在容错与密度间较均衡；
 * 边距固定 1 模块，避免白边过宽导致实际码面过小。
 */
object QrCode {

    /** 把 [text] 生成为边长 [sizePx] 的二维码位图；失败返回 null。 */
    fun encode(text: String, sizePx: Int): Bitmap? {
        if (text.isEmpty() || sizePx <= 0) return null
        return try {
            val hints = HashMap<EncodeHintType, Any>()
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
            hints[EncodeHintType.MARGIN] = 1
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            val black = Color.BLACK
            val white = Color.WHITE
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) black else white
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        } catch (e: Throwable) {
            null
        }
    }
}
