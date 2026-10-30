package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TelegramLinkParserTest {
    @Test
    fun parsesAtUsername() {
        assertEquals(
            TelegramChannelTarget.PublicUsername("example_music"),
            TelegramLinkParser.parse("@example_music"),
        )
    }

    @Test
    fun parsesBareUsername() {
        assertEquals(
            TelegramChannelTarget.PublicUsername("example_music"),
            TelegramLinkParser.parse("example_music"),
        )
    }

    @Test
    fun parsesPublicTelegramLinkAndIgnoresQuery() {
        assertEquals(
            TelegramChannelTarget.PublicUsername("example_music"),
            TelegramLinkParser.parse("https://t.me/example_music?single"),
        )
    }

    @Test
    fun parsesBareTelegramDomain() {
        assertEquals(
            TelegramChannelTarget.PublicUsername("example_music"),
            TelegramLinkParser.parse("t.me/example_music"),
        )
    }

    @Test
    fun parsesPrivatePlusInviteLink() {
        assertEquals(
            TelegramChannelTarget.PrivateInviteLink("https://t.me/+AbCdEf123"),
            TelegramLinkParser.parse("https://t.me/+AbCdEf123"),
        )
    }

    @Test
    fun canonicalizesHttpPrivateInviteLink() {
        assertEquals(
            TelegramChannelTarget.PrivateInviteLink("https://t.me/+AbCdEf123"),
            TelegramLinkParser.parse("http://t.me/+AbCdEf123"),
        )
    }

    @Test
    fun parsesLegacyJoinChatInviteLink() {
        assertEquals(
            TelegramChannelTarget.PrivateInviteLink("https://t.me/joinchat/AbCdEf123"),
            TelegramLinkParser.parse("https://t.me/joinchat/AbCdEf123"),
        )
    }

    @Test
    fun rejectsUnresolvableUsername() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramLinkParser.parse("not a valid username")
        }
    }

    @Test
    fun rejectsUrlWithTelegramLookingPathOnForeignHost() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramLinkParser.parse("https://evil.example/t.me/+AbCdEf123")
        }
    }

    @Test
    fun rejectsLookalikeTelegramDomain() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramLinkParser.parse("https://t.me.evil.example/example_music")
        }
    }

    @Test
    fun rejectsUnsupportedScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramLinkParser.parse("ftp://t.me/example_music")
        }
    }

    @Test
    fun rejectsUrlsWithUserInfo() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramLinkParser.parse("https://user@t.me/example_music")
        }
    }

    @Test
    fun rejectsUrlsWithExplicitPort() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramLinkParser.parse("https://t.me:8443/example_music")
        }
    }

    @Test
    fun rejectsExtraPathSegments() {
        assertThrows(IllegalArgumentException::class.java) {
            TelegramLinkParser.parse("https://t.me/example_music/extra")
        }
    }
}
