package com.lostf1sh.pixelplayeross.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TelegramDao {
    @Query("SELECT * FROM telegram_channels ORDER BY title COLLATE NOCASE ASC")
    fun observeChannels(): Flow<List<TelegramChannelEntity>>

    @Query("SELECT * FROM telegram_channels ORDER BY title COLLATE NOCASE ASC")
    suspend fun getChannels(): List<TelegramChannelEntity>

    @Query("SELECT * FROM telegram_channels WHERE chat_id = :chatId LIMIT 1")
    suspend fun getChannel(chatId: Long): TelegramChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: TelegramChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannels(channels: List<TelegramChannelEntity>)

    @Query("UPDATE telegram_channels SET is_saved = :isSaved WHERE chat_id = :chatId")
    suspend fun setChannelSaved(chatId: Long, isSaved: Boolean)

    @Query("DELETE FROM telegram_channels WHERE chat_id = :chatId")
    suspend fun deleteChannel(chatId: Long)

    @Query("SELECT * FROM telegram_songs WHERE chat_id = :chatId ORDER BY date_added DESC, message_id DESC")
    suspend fun getSongsByChatId(chatId: Long): List<TelegramSongEntity>

    @Query("SELECT * FROM telegram_songs WHERE chat_id = :chatId AND thread_id = :threadId ORDER BY date_added DESC, message_id DESC")
    suspend fun getSongsByTopic(chatId: Long, threadId: Long): List<TelegramSongEntity>

    @Query("SELECT * FROM telegram_songs WHERE id = :id LIMIT 1")
    suspend fun getSong(id: String): TelegramSongEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSongs(songs: List<TelegramSongEntity>)

    /**
     * Replace a complete channel scan atomically. If the process dies midway,
     * Room rolls back the delete and does not leave an empty/partial library.
     */
    @Transaction
    suspend fun replaceChannelSnapshot(
        channel: TelegramChannelEntity,
        songs: List<TelegramSongEntity>,
    ) {
        upsertChannel(channel)
        deleteSongsByChat(channel.chatId)
        if (songs.isNotEmpty()) upsertSongs(songs)
    }

    /**
     * Merge a partial scan atomically. This is used when TDLib rejects one of the
     * audio/document filters; already cached tracks must survive that partial failure.
     */
    @Transaction
    suspend fun mergeChannelSnapshot(
        channel: TelegramChannelEntity,
        songs: List<TelegramSongEntity>,
    ) {
        upsertChannel(channel)
        if (songs.isNotEmpty()) upsertSongs(songs)
    }

    @Query("DELETE FROM telegram_songs WHERE chat_id = :chatId")
    suspend fun deleteSongsByChat(chatId: Long)

    @Query("DELETE FROM telegram_songs WHERE chat_id = :chatId AND thread_id = :threadId")
    suspend fun deleteSongsByTopic(chatId: Long, threadId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTopics(topics: List<TelegramTopicEntity>)

    @Query("SELECT * FROM telegram_topics WHERE chat_id = :chatId ORDER BY name COLLATE NOCASE ASC")
    fun observeTopics(chatId: Long): Flow<List<TelegramTopicEntity>>

    @Query("SELECT * FROM telegram_topics WHERE chat_id = :chatId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getTopics(chatId: Long): List<TelegramTopicEntity>

    @Query("DELETE FROM telegram_topics WHERE chat_id = :chatId")
    suspend fun deleteTopicsByChat(chatId: Long)

    @Transaction
    suspend fun removeChannelAndCachedMedia(chatId: Long) {
        deleteSongsByChat(chatId)
        deleteTopicsByChat(chatId)
        deleteChannel(chatId)
    }
}
