package org.dancesonic.player.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.dancesonic.player.account.ConnectionUri
import org.dancesonic.player.subsonic.ServerSettings
import org.dancesonic.player.subsonic.ServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class QrCodesTest {
    @Test
    fun `connection code decodes back to the same text`() {
        val text =
            ConnectionUri.build(
                ServerSettings(ServerUrl.parse("https://music.example.com/"), "key-abc123"),
                ServerUrl.parse("https://dance.example.com/"),
            )
        val matrix = QrCodes.matrix(text, SIZE_PX)
        val pixels =
            IntArray(matrix.width * matrix.height) { index ->
                val x = index % matrix.width
                val y = index / matrix.width
                if (matrix.get(x, y)) BLACK else WHITE
            }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)

        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))

        assertEquals(text, decoded.text)
    }

    private companion object {
        const val SIZE_PX = 300
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
