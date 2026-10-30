package com.lostf1sh.pixelplayeross.presentation.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramQrCodeEncoderTest {
    @Test
    fun encodesTelegramLoginLinkIntoSquareQrCode() {
        val matrix = TelegramQrCodeEncoder.encode(
            "tg://login?token=abcdefghijklmnopqrstuvwxyz0123456789_-",
            size = 256,
        )

        assertEquals(256, matrix.width)
        assertEquals(256, matrix.height)
        assertTrue((0 until matrix.width).any { x ->
            (0 until matrix.height).any { y -> matrix[x, y] }
        })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonTelegramLinks() {
        TelegramQrCodeEncoder.encode("https://example.com/login")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedDimensions() {
        TelegramQrCodeEncoder.encode("tg://login?token=abc", size = 8_192)
    }
}
