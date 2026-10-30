package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TelegramLinkParserTest {
    @Test fun parsesAtUsername() {
        assertEquals(TelegramChannelTarget.PublicUsername("example_music"), TelegramLinkParser.parse("@example_music"))
    }

    @Test fun parsesPublicTelegramLink() {
        assertEquals(TelegramChannelTarget.PublicUsername("example_music"), TelegramLinkParser.parse("https://t.me/example_music?single"))
    }

    @Test fun parsesPrivatePlusInviteLink() {
        assertEquals(TelegramChannelTarget.PrivateInviteLink("https://t.me/+AbCdEf123"), TelegramLinkParser.parse("https://t.me/+AbCdEf123"))
    }

    @Test fun parsesLegacyJoinChatInviteLink() {
        assertEquals(TelegramChannelTarget.PrivateInviteLink("https://t.me/joinchat/AbCdEf123"), TelegramLinkParser.parse("https://t.me/joinchat/AbCdEf123"))
    }

    @Test fun rejectsUnresolvableUsername() {
        assertThrows(IllegalArgumentException::class.java) { TelegramLinkParser.parse("not a valid username") }
    }
}
