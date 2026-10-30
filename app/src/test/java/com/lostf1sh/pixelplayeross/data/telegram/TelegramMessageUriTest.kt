package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramMessageUriTest {
    @Test
    fun parsesStableTelegramMessageReference() {
        assertEquals(
            TelegramMessageReference(chatId = 123456L, messageId = 987L),
            TelegramMessageUri.parse("telegram://123456/987"),
        )
    }

    @Test
    fun supportsNegativeSupergroupChatIds() {
        assertEquals(
            TelegramMessageReference(chatId = -1001234567890L, messageId = 987L),
            TelegramMessageUri.parse("telegram://-1001234567890/987"),
        )
    }

    @Test
    fun buildsStableUriWithoutProcessLocalProxyCredentials() {
        assertEquals(
            "telegram://-1001234567890/987",
            TelegramMessageUri.build(-1001234567890L, 987L),
        )
    }

    @Test
    fun rejectsWrongSchemesAndMissingIdentifiers() {
        assertNull(TelegramMessageUri.parse("https://127.0.0.1:1234/stream/123/token"))
        assertNull(TelegramMessageUri.parse("telegram://123"))
        assertNull(TelegramMessageUri.parse("telegram:///456"))
        assertNull(TelegramMessageUri.parse("telegram://0/456"))
        assertNull(TelegramMessageUri.parse("telegram://123/0"))
    }

    @Test
    fun rejectsExtraPathSegmentsAndQueryData() {
        assertNull(TelegramMessageUri.parse("telegram://123/456/extra"))
        assertNull(TelegramMessageUri.parse("telegram://123/456?token=secret"))
        assertNull(TelegramMessageUri.parse("telegram://123/456#fragment"))
    }

    @Test
    fun rejectsMalformedAndOverflowingIdentifiers() {
        assertNull(TelegramMessageUri.parse("telegram://not-a-chat/456"))
        assertNull(TelegramMessageUri.parse("telegram://123/999999999999999999999"))
        assertNull(TelegramMessageUri.parse("telegram://123/4%2F5"))
    }
}
