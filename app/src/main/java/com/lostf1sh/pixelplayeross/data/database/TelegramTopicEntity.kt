package com.lostf1sh.pixelplayeross.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "telegram_topics",
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["chat_id", "thread_id"], unique = true),
    ],
)
data class TelegramTopicEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "chat_id")
    val chatId: Long,
    @ColumnInfo(name = "thread_id")
    val threadId: Long,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "song_count", defaultValue = "0")
    val songCount: Int = 0,
    @ColumnInfo(name = "last_sync_time", defaultValue = "0")
    val lastSyncTime: Long = 0L,
    @ColumnInfo(name = "icon_emoji")
    val iconEmoji: String? = null,
)
