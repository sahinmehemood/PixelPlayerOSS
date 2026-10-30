package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lostf1sh.pixelplayeross.data.telegram.TelegramChannel

@Entity(
    tableName = "telegram_channels",
    indices = [Index(value = ["title"])],
)
data class TelegramChannelEntity(
    @PrimaryKey
    @ColumnInfo(name = "chat_id")
    val chatId: Long,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "username")
    val username: String? = null,
    @ColumnInfo(name = "is_channel", defaultValue = "1")
    val isChannel: Boolean = true,
    @ColumnInfo(name = "is_saved", defaultValue = "0")
    val isSaved: Boolean = false,
    @ColumnInfo(name = "song_count", defaultValue = "0")
    val songCount: Int = 0,
    @ColumnInfo(name = "last_sync_time", defaultValue = "0")
    val lastSyncTime: Long = 0L,
) {
    fun toTelegramChannel(): TelegramChannel = TelegramChannel(
        chatId = chatId,
        title = title,
        username = username,
        isChannel = isChannel,
        isSaved = isSaved,
    )

    companion object {
        fun from(channel: TelegramChannel, songCount: Int = 0, lastSyncTime: Long = 0L) =
            TelegramChannelEntity(
                chatId = channel.chatId,
                title = channel.title,
                username = channel.username,
                isChannel = channel.isChannel,
                isSaved = channel.isSaved,
                songCount = songCount.coerceAtLeast(0),
                lastSyncTime = lastSyncTime.coerceAtLeast(0L),
            )
    }
}
