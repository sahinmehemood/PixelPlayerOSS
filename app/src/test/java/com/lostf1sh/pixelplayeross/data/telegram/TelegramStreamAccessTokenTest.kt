package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramStreamAccessTokenTest {
    @Test
    fun generatesA256BitHexToken() {
        val token = TelegramStreamAccessToken.generate()

        assertEquals(64, token.length)
        assertTrue(token.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun generatedTokensAreDifferent() {
        assertTrue(
            TelegramStreamAccessToken.generate() != TelegramStreamAccessToken.generate()
        )
    }

    @Test
    fun acceptsTheCorrectToken() {
        val token = TelegramStreamAccessToken.generate()

        assertTrue(TelegramStreamAccessToken.matches(token, token))
    }

    @Test
    fun rejectsAnIncorrectToken() {
        val token = TelegramStreamAccessToken.generate()
        val differentToken = TelegramStreamAccessToken.generate()

        assertFalse(TelegramStreamAccessToken.matches(token, differentToken))
    }

    @Test
    fun rejectsEmptyAndMalformedTokens() {
        assertFalse(TelegramStreamAccessToken.matches("", ""))
        assertFalse(TelegramStreamAccessToken.matches("short", "short"))
        assertFalse(TelegramStreamAccessToken.matches(TelegramStreamAccessToken.generate(), ""))
    }
}
