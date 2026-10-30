package com.lostf1sh.pixelplayeross.data.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TelegramSongEntityTest {
    @Test
    fun cachedTrackUsesStableTelegramMessageUri() {
        val song = sampleEntity().toSong(channelTitle = "Ambient Archive")

        assertEquals("telegram://-1001234567890/987654", song.contentUriString)
        assertEquals("", song.path)
        assertEquals("Telegram Track", song.title)
        assertEquals("Artist", song.artist)
        assertEquals("Ambient Archive", song.album)
    }

    @Test
    fun cachedTrackDoesNotPersistLoopbackProxyUrlOrToken() {
        val entity = sampleEntity()
        val song = entity.toSong(channelTitle = "Ambient Archive")

        assertFalse(entity.id.contains("127.0.0.1"))
        assertFalse(song.contentUriString.contains("127.0.0.1"))
        assertFalse(song.contentUriString.contains("token"))
        assertFalse(song.contentUriString.contains("http"))
    }

    @Test
    fun channelMappingRetainsSavedAndSyncMetadata() {
        val entity = TelegramChannelEntity(
            chatId = -100123L,
            title = "Ambient Archive",
            username = "ambient_archive",
            isChannel = true,
            isSaved = true,
            songCount = 42,
            lastSyncTime = 123456789L,
        )

        val channel = entity.toTelegramChannel()

        assertEquals(-100123L, channel.chatId)
        assertEquals("ambient_archive", channel.username)
        assertEquals(true, channel.isSaved)
        assertEquals(true, channel.isChannel)
    }

    private fun sampleEntity() = TelegramSongEntity(
        id = "telegram:-1001234567890:987654",
        chatId = -1001234567890L,
        messageId = 987654L,
        fileId = 321,
        title = "Telegram Track",
        artist = "Artist",
        duration = 245_000L,
        fileSize = 18_000_000L,
        mimeType = "audio/mpeg",
        dateAdded = 1_700_000_000L,
    )
}
