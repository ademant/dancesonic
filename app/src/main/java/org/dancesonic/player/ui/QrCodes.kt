package org.dancesonic.player.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Renders QR codes in memory. Nothing is written to a file or to the cache: the caller keeps the
 * bitmap only while it is on screen.
 */
internal object QrCodes {
    /** The module matrix for [text], [sizePx] pixels square (ZXing adds the quiet zone). */
    fun matrix(
        text: String,
        sizePx: Int,
    ): BitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx)

    /** [text] as a black-on-white bitmap, [sizePx] pixels square. */
    fun bitmap(
        text: String,
        sizePx: Int,
    ): Bitmap {
        val matrix = matrix(text, sizePx)
        val pixels =
            IntArray(sizePx * sizePx) { index ->
                val x = index % sizePx
                val y = index / sizePx
                if (matrix.get(x, y)) BLACK else WHITE
            }
        return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
