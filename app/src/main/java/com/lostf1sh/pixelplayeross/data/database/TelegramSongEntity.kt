package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lostf1sh.pixelplayeross.data.model.Song

/**
 * Metadata for a Telegram message containing playable audio.
 *
 * The persistent identity is chat/message based. In particular, we never persist the
 * loopback proxy URL: its port and authentication token are process-local and rotate
 * when TDLib or the app process restarts.
 */
@Entity(
    tableName = "telegram_songs",
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["message_id"]),
        Index(value = ["file_id"]),
        Index(value = ["chat_id", "message_id"], unique = true),
        Index(value = ["thread_id"]),
    ],
)
data class TelegramSongEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "chat_id")
    val chatId: Long,
    @ColumnInfo(name = "message_id")
    val messageId: Long,
    @ColumnInfo(name = "file_id")
    val fileId: Int,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "artist")
    val artist: String,
    @ColumnInfo(name = "duration")
    val duration: Long,
    @ColumnInfo(name = "file_size", defaultValue = "0")
    val fileSize: Long = 0L,
    @ColumnInfo(name = "mime_type")
    val mimeType: String,
    @ColumnInfo(name = "date_added")
    val dateAdded: Long,
    @ColumnInfo(name = "album_art_uri")
    val albumArtUri: String? = null,
    @ColumnInfo(name = "thread_id")
    val threadId: Long? = null,
) {
    fun toSong(channelTitle: String): Song {
        val stableUri = stableUri(chatId, messageId)
        return Song(
            id = id,
            title = title,
            artist = artist,
            artistId = -1L,
            album = channelTitle,
            albumId = -1L,
            path = "",
            contentUriString = stableUri,
            albumArtUriString = albumArtUri,
            duration = duration.coerceAtLeast(0L),
            genre = null,
            lyrics = null,
            isFavorite = false,
            trackNumber = 0,
            discNumber = null,
            year = 0,
            dateAdded = dateAdded.coerceAtLeast(0L),
            dateModified = 0L,
            mimeType = mimeType,
            bitrate = null,
            sampleRate = null,
        )
    }

    companion object {
        fun stableUri(chatId: Long, messageId: Long): String =
            "telegram://$chatId/$messageId"
    }
}
