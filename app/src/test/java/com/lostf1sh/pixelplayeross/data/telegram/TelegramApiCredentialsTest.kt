package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TelegramApiCredentialsTest {
    @Test
    fun parsesValidCredentialsAndNormalizesHash() {
        val credentials = TelegramApiCredentials.parse(
            " 123456 ",
            "0123456789ABCDEF0123456789ABCDEF",
        )

        assertEquals(123456, credentials.apiId)
        assertEquals("0123456789abcdef0123456789abcdef", credentials.apiHash)
    }

    @Test
    fun rejectsInvalidApiId() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramApiCredentials.parse("0", "0123456789abcdef0123456789abcdef")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelegramApiCredentials.parse("not-a-number", "0123456789abcdef0123456789abcdef")
        }
    }

    @Test
    fun rejectsInvalidApiHash() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramApiCredentials.parse("123456", "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TelegramApiCredentials.parse("123456", "g123456789abcdef0123456789abcdef")
        }
    }

    @Test
    fun compiledConfigReturnsNullWhenCredentialsAreUnavailable() {
        assertEquals(null, TelegramApiCredentials.fromCompiledConfig(0, ""))
        assertEquals(null, TelegramApiCredentials.fromCompiledConfig(123456, "invalid"))
    }

    @Test
    fun toStringNeverRevealsTheApiHash() {
        val secret = "0123456789abcdef0123456789abcdef"
        val credentials = TelegramApiCredentials.parse("123456", secret)

        assertNotEquals(true, credentials.toString().contains(secret))
        assertEquals("TelegramApiCredentials(apiId=123456, apiHash=[redacted])", credentials.toString())
    }
}
