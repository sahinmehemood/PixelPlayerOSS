package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TelegramApiCredentialsValidatorTest {
    @Test
    fun acceptsValidApiCredentials() {
        assertTrue(
            TelegramApiCredentialsValidator.isValid(
                123456,
                "0123456789abcdef0123456789abcdef",
            ),
        )
    }

    @Test
    fun acceptsUppercaseHashAndSurroundingWhitespace() {
        assertTrue(
            TelegramApiCredentialsValidator.isValid(
                123456,
                " 0123456789ABCDEF0123456789ABCDEF ",
            ),
        )
    }

    @Test
    fun rejectsNonPositiveApiIds() {
        assertFalse(
            TelegramApiCredentialsValidator.isValid(
                0,
                "0123456789abcdef0123456789abcdef",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TelegramApiCredentialsValidator.validate(
                -1,
                "0123456789abcdef0123456789abcdef",
            )
        }
    }

    @Test
    fun rejectsHashesWithWrongLengthOrCharacters() {
        assertFalse(TelegramApiCredentialsValidator.isValid(123456, "0123456789abcdef"))
        assertFalse(TelegramApiCredentialsValidator.isValid(123456, "g123456789abcdef0123456789abcdef"))
        assertThrows(IllegalArgumentException::class.java) {
            TelegramApiCredentialsValidator.validate(123456, "not-a-real-hash")
        }
    }
}
