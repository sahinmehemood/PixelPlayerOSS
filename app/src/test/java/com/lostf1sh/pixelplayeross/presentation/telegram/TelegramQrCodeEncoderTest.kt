package com.lostf1sh.pixelplayeross.presentation.telegram

import com.google.zxing.BinaryBitmap
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramQrCodeEncoderTest {
    @Test
    fun encodedQrCodeDecodesBackToExactTelegramLoginLink() {
        val link = "tg://login?token=abcdefghijklmnopqrstuvwxyz0123456789_-"
        val matrix = TelegramQrCodeEncoder.encode(link, size = 256)
        val pixels = IntArray(matrix.width * matrix.height)

        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[y * matrix.width + x] =
                    if (matrix[x, y]) 0xff000000.toInt() else 0xffffffff.toInt()
            }
        }

        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))

        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
        assertTrue((0 until matrix.width).any { x ->
            (0 until matrix.height).any { y -> matrix[x, y] }
        })
        assertEquals(link, decoded.text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonTelegramLinks() {
        TelegramQrCodeEncoder.encode("https://example.com/login")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyLoginTokens() {
        TelegramQrCodeEncoder.encode("tg://login?token=")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedDimensions() {
        TelegramQrCodeEncoder.encode("tg://login?token=abc", size = 8_192)
    }
}
