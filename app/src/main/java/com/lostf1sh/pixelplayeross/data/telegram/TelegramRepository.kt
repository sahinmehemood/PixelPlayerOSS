package com.lostf1sh.pixelplayeross.data.telegram

import android.net.Uri
import com.lostf1sh.pixelplayeross.data.database.TelegramChannelEntity
import com.lostf1sh.pixelplayeross.data.database.TelegramDao
import com.lostf1sh.pixelplayeross.data.database.TelegramSongEntity
import com.lostf1sh.pixelplayeross.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi
import timber.log.Timber

@Singleton
class TelegramRepository @Inject constructor(
    private val clientManager: TelegramClientManager,
    private val streamProxy: TelegramStreamProxy,
    private val channelStore: TelegramChannelStore,
    private val telegramDao: TelegramDao,
) {
    val authorizationState = clientManager.authorizationState
    val initializationError = clientManager.initializationError
    companion object {
        private const val CHAT_RESOLUTION_CONCURRENCY = 8
        private const val SEARCH_PAGE_SIZE = 100
        private const val MAX_SEARCH_PAGES = 200
    }

    val errors = clientManager.errors

    fun hasApiCredentials(): Boolean = clientManager.hasApiCredentials()

    fun isNativeLibraryLoaded(): Boolean = clientManager.isNativeLibraryLoaded()

    fun isReady(): Boolean = clientManager.isReady()

    fun configureApiCredentials(apiId: Int, apiHash: String) {
        val credentials = TelegramApiCredentials.parse(apiId.toString(), apiHash)
        clientManager.configureApiCredentials(credentials)
    }

    suspend fun resetApiCredentials() {
        clientManager.resetApiCredentials()
        streamProxy.invalidateSession()
    }

    suspend fun requestQrCodeAuthentication() = clientManager.requestQrCodeAuthentication()
    suspend fun sendPhoneNumber(phoneNumber: String) = clientManager.sendPhoneNumber(phoneNumber)
    suspend fun checkAuthenticationCode(code: String) = clientManager.checkAuthenticationCode(code)
    suspend fun checkAuthenticationPassword(password: String) = clientManager.checkAuthenticationPassword(password)
    suspend fun logout() {
        clientManager.logout()
        streamProxy.invalidateSession()
    }

    suspend fun addChannel(input: String): TelegramChannel {
        check(clientManager.awaitReady()) { "Sign in to Telegram before adding a channel." }
        val channel = when (val target = TelegramLinkParser.parse(input)) {
            is TelegramChannelTarget.PublicUsername -> {
                val chat = clientManager.sendRequest<TdApi.Chat>(
                    TdApi.SearchPublicChat(target.username)
                )
                chat.toTelegramChannel()
            }
            is TelegramChannelTarget.PrivateInviteLink -> joinPrivateInvite(target.inviteLink)
        }
        // Public channels can be browsed without joining them. Save the chat ID
        // separately so they remain visible in PixelPlayer after this screen closes.
        val previous = telegramDao.getChannel(channel.chatId)
        val savedChannel = channel.copy(isSaved = true)
        channelStore.save(channel.chatId)
        telegramDao.upsertChannel(
            TelegramChannelEntity.from(
                channel = savedChannel,
                songCount = previous?.songCount ?: 0,
                lastSyncTime = previous?.lastSyncTime ?: 0L,
            ),
        )
        return savedChannel
    }

    suspend fun removeSavedChannel(chatId: Long) {
        channelStore.remove(chatId)
        telegramDao.setChannelSaved(chatId, isSaved = false)
    }

    suspend fun getCachedAudioMessages(channel: TelegramChannel): List<Song> =
        telegramDao.getSongsByChatId(channel.chatId).map { it.toSong(channel.title) }

    /**
     * Turns a stable telegram://chatId/messageId URI into a fresh authenticated
     * loopback URL for Media3. The URL is deliberately generated at playback time,
     * never persisted in the queue or Telegram cache.
     */
    suspend fun resolveTelegramPlaybackUri(uriString: String): Uri {
        val target = TelegramMessageUri.parse(uriString)
            ?: throw IllegalArgumentException("This is not a valid Telegram media URI.")
        check(clientManager.awaitReady(30_000L)) { "Sign in to Telegram before playing this track." }

        // Prefer the persistent metadata snapshot. This avoids an extra TDLib
        // GetMessage round-trip each time Media3 reopens a source for a seek/retry.
        val cached = telegramDao.getSong(
            "telegram:${target.chatId}:${target.messageId}",
        )
        val details = cached?.let {
            AudioFileDetails(
                fileId = it.fileId,
                fileSize = it.fileSize,
                title = it.title,
                artist = it.artist,
                durationMs = it.duration,
                mimeType = it.mimeType,
            )
        } ?: run {
            val message = clientManager.sendRequest<TdApi.Message>(
                TdApi.GetMessage(target.chatId, target.messageId),
            )
            val freshDetails = audioFileDetails(message)
                ?: throw IllegalStateException(
                    "This Telegram message no longer contains a supported audio file.",
                )
            mapAudioMessage(message)?.let { telegramDao.upsertSongs(listOf(it)) }
            freshDetails
        }
        if (details.fileId <= 0) {
            throw IllegalStateException("Telegram returned an invalid audio file reference.")
        }
        return Uri.parse(
            streamProxy.streamUrl(details.fileId, details.fileSize, details.mimeType),
        )
    }

    private suspend fun joinPrivateInvite(inviteLink: String): TelegramChannel {
        val invite = clientManager.sendRequest<TdApi.ChatInviteLinkInfo>(
            TdApi.CheckChatInviteLink(inviteLink)
        )
        if (invite.chatId != 0L) {
            return clientManager.sendRequest<TdApi.Chat>(TdApi.GetChat(invite.chatId)).toTelegramChannel()
        }

        val joinedChat = clientManager.sendRequest<TdApi.Chat>(TdApi.JoinChatByInviteLink(inviteLink))
        if (joinedChat.id != 0L) return joinedChat.toTelegramChannel()
        repeat(12) {
            delay(350)
            val refreshed = try {
                clientManager.sendRequest<TdApi.ChatInviteLinkInfo>(TdApi.CheckChatInviteLink(inviteLink))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.d(error, "Telegram invite status is not available yet")
                null
            }
            if (refreshed != null && refreshed.chatId != 0L) {
                return clientManager.sendRequest<TdApi.Chat>(TdApi.GetChat(refreshed.chatId)).toTelegramChannel()
            }
            val joinedChats = try {
                listJoinedChannels()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.d(error, "Telegram chat list is not available while resolving invite")
                emptyList()
            }
            val joined = joinedChats.firstOrNull { it.title.equals(invite.title, ignoreCase = true) }
            if (joined != null) return joined
        }
        throw IllegalStateException(
            "Telegram accepted the invite, but the channel has not appeared in your chat list yet. Refresh and try again."
        )
    }

    suspend fun listJoinedChannels(): List<TelegramChannel> {
        check(clientManager.awaitReady()) { "Telegram is not connected." }
        val chatLists = listOf<TdApi.ChatList>(
            TdApi.ChatListMain(),
            TdApi.ChatListArchive(),
        )
        val chatIds = linkedSetOf<Long>()

        chatLists.forEach { chatList ->
            // LoadChats may report that a list is already fully loaded. The cached
            // list remains readable through GetChats even when loading is skipped.
            try {
                clientManager.sendRequest<TdApi.Ok>(TdApi.LoadChats(chatList, 500))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.d(error, "Telegram chat list is already loaded or could not be extended")
            }

            val chats = try {
                clientManager.sendRequest<TdApi.Chats>(TdApi.GetChats(chatList, 500))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.d(error, "Could not read one Telegram chat list")
                null
            }
            chats?.chatIds?.forEach(chatIds::add)
        }

        // Read both the legacy encrypted ID list and the Room library. Keeping
        // the legacy source during this transition preserves channels added by older
        // app versions; new writes are mirrored into Room.
        val cachedChannels = telegramDao.getChannels().associateBy { it.chatId }
        val savedChatIds = channelStore.getSavedChatIds() +
            cachedChannels.values.asSequence()
                .filter { it.isSaved }
                .map { it.chatId }
                .toSet()
        chatIds.addAll(savedChatIds)

        // Resolve the union of joined, archived and explicitly saved public channels
        // once, with bounded concurrency. This avoids duplicate TDLib lookups and
        // keeps public channels visible even when the user has not joined them.
        val semaphore = Semaphore(CHAT_RESOLUTION_CONCURRENCY)
        val resolvedChats = coroutineScope {
            chatIds.map { chatId ->
                async {
                    semaphore.withPermit {
                        try {
                            clientManager.sendRequest<TdApi.Chat>(TdApi.GetChat(chatId))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Timber.d(error, "Could not resolve Telegram chat %s", chatId)
                            null
                        }
                    }
                }
            }.awaitAll()
        }

        val channels = resolvedChats.filterNotNull().mapNotNull { chat ->
            runCatching {
                chat.toTelegramChannel().copy(isSaved = chat.id in savedChatIds)
            }.onFailure {
                Timber.d(it, "Skipping non-channel Telegram chat %s", chat.id)
            }.getOrNull()
        }.sortedBy { it.title.lowercase() }

        if (channels.isNotEmpty()) {
            telegramDao.upsertChannels(
                channels.map { channel ->
                    val previous = cachedChannels[channel.chatId]
                    TelegramChannelEntity.from(
                        channel = channel.copy(
                            isSaved = channel.isSaved || previous?.isSaved == true,
                        ),
                        songCount = previous?.songCount ?: 0,
                        lastSyncTime = previous?.lastSyncTime ?: 0L,
                    )
                },
            )
        }
        return channels.map { channel ->
            if (channel.isSaved || cachedChannels[channel.chatId]?.isSaved == true) {
                channel.copy(isSaved = true)
            } else {
                channel
            }
        }
    }

    suspend fun getAudioMessages(
        channel: TelegramChannel,
        forceRefresh: Boolean = false,
    ): List<Song> {
        check(clientManager.awaitReady()) { "Telegram is not connected." }

        val cachedChannel = telegramDao.getChannel(channel.chatId)
        val cachedRows = telegramDao.getSongsByChatId(channel.chatId)
        val now = System.currentTimeMillis()
        if (
            !forceRefresh &&
            TelegramAudioCachePolicy.isFresh(
                lastSyncTimeMs = cachedChannel?.lastSyncTime ?: 0L,
                nowMs = now,
                hasTracks = cachedRows.isNotEmpty(),
            )
        ) {
            return cachedRows.map { it.toSong(channel.title) }
        }

        clientManager.sendRequest<TdApi.Ok>(TdApi.OpenChat(channel.chatId))

        // Scan audio messages and generic document uploads independently. Some
        // channels use both formats, and one TDLib filter failing must not erase
        // valid results from the other filter or the previous persistent cache.
        val messagesById = linkedMapOf<Long, TdApi.Message>()
        val filters = listOf<TdApi.SearchMessagesFilter>(
            TdApi.SearchMessagesFilterAudio(),
            TdApi.SearchMessagesFilterDocument(),
        )
        var completedFilters = 0
        var failedFilters = 0

        filters.forEach { filter ->
            var fromMessageId = 0L
            var page = 0
            var reachedEnd = false
            try {
                while (page < MAX_SEARCH_PAGES) {
                    val request = TdApi.SearchChatMessages().apply {
                        chatId = channel.chatId
                        query = ""
                        senderId = null
                        this.fromMessageId = fromMessageId
                        offset = 0
                        limit = SEARCH_PAGE_SIZE
                        this.filter = filter
                    }
                    val response = clientManager.sendRequest<TdApi.FoundChatMessages>(request)
                    if (response.messages.isEmpty()) {
                        reachedEnd = true
                        break
                    }

                    response.messages.forEach { message ->
                        messagesById.putIfAbsent(message.id, message)
                    }

                    val next = response.nextFromMessageId
                    if (next == 0L) {
                        reachedEnd = true
                        break
                    }
                    if (next == fromMessageId) {
                        throw IllegalStateException(
                            "Telegram search did not advance its pagination cursor.",
                        )
                    }
                    fromMessageId = next
                    page += 1
                }

                if (reachedEnd) {
                    completedFilters += 1
                } else {
                    failedFilters += 1
                    Timber.w(
                        "Telegram %s scan reached its %d-page safety limit for chat %s; preserving older cached tracks.",
                        if (filter is TdApi.SearchMessagesFilterAudio) "audio" else "document",
                        MAX_SEARCH_PAGES,
                        channel.chatId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failedFilters += 1
                Timber.w(
                    error,
                    "Telegram %s search failed for chat %s; preserving partial results",
                    if (filter is TdApi.SearchMessagesFilterAudio) "audio" else "document",
                    channel.chatId,
                )
            }
        }

        // If neither filter completed, do not replace a valid cache with an
        // incomplete/empty result. Surface the failure when there is no fallback.
        if (completedFilters == 0) {
            if (cachedRows.isNotEmpty()) {
                Timber.w(
                    "Telegram scan failed for chat %s; returning %d cached tracks",
                    channel.chatId,
                    cachedRows.size,
                )
                return cachedRows.map { it.toSong(channel.title) }
            }
            throw IllegalStateException(
                "Telegram could not scan this channel. Check your access and try again.",
            )
        }

        val scannedRows = messagesById.values
            .sortedByDescending { it.date }
            .mapNotNull(::mapAudioMessage)

        val previousChannel = cachedChannel ?: telegramDao.getChannel(channel.chatId)
        val isSaved = previousChannel?.isSaved ?: channel.isSaved

        val finalRows = if (failedFilters == 0) {
            // Both filters reached the end: this is a complete snapshot. Removing
            // obsolete rows and writing replacements happens in one Room transaction.
            val snapshot = TelegramChannelEntity.from(
                channel = channel.copy(isSaved = isSaved),
                songCount = scannedRows.size,
                lastSyncTime = System.currentTimeMillis(),
            )
            telegramDao.replaceChannelSnapshot(snapshot, scannedRows)
            scannedRows
        } else {
            // At least one filter was incomplete. Merge the newly discovered rows
            // while retaining any cached tracks that the failed filter may have missed.
            val mergedRows = (
                cachedRows.associateBy { it.id } +
                    scannedRows.associateBy { it.id }
                ).values.sortedWith(
                    compareByDescending<TelegramSongEntity> { it.dateAdded }
                        .thenByDescending { it.messageId },
                )
            val snapshot = TelegramChannelEntity.from(
                channel = channel.copy(isSaved = isSaved),
                songCount = mergedRows.size,
                lastSyncTime = previousChannel?.lastSyncTime ?: 0L,
            )
            telegramDao.mergeChannelSnapshot(snapshot, scannedRows)
            mergedRows
        }

        return finalRows.map { it.toSong(channel.title) }
    }

    private fun mapAudioMessage(message: TdApi.Message): TelegramSongEntity? {
        val details = audioFileDetails(message) ?: return null
        if (details.fileId <= 0) return null

        return TelegramSongEntity(
            id = "telegram:${message.chatId}:${message.id}",
            chatId = message.chatId,
            messageId = message.id,
            fileId = details.fileId,
            title = details.title,
            artist = details.artist,
            duration = details.durationMs,
            fileSize = details.fileSize,
            mimeType = details.mimeType,
            dateAdded = message.date.toLong(),
            albumArtUri = null,
        )
    }

    private data class AudioFileDetails(
        val fileId: Int,
        val fileSize: Long,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val mimeType: String,
    )

    private fun audioFileDetails(message: TdApi.Message): AudioFileDetails? {
        return when (val content = message.content) {
            is TdApi.MessageAudio -> {
                val audio = content.audio
                AudioFileDetails(
                    fileId = audio.audio.id,
                    fileSize = audio.audio.size.toLong().coerceAtLeast(0L),
                    title = audio.title.ifBlank {
                        audio.fileName.substringBeforeLast('.').ifBlank { "Unknown title" }
                    },
                    artist = audio.performer.ifBlank { "Unknown artist" },
                    durationMs = audio.duration.toLong().coerceAtLeast(0L) * 1_000L,
                    mimeType = resolveAudioMimeType(audio.mimeType, audio.fileName),
                )
            }
            is TdApi.MessageDocument -> {
                val document = content.document
                val fileName = document.fileName
                val extension = fileName.substringAfterLast('.', "").lowercase()
                val isAudio = document.mimeType.startsWith("audio/") ||
                    document.mimeType == "application/ogg" ||
                    extension in setOf(
                        "mp3", "flac", "wav", "m4a", "ogg", "opus", "aac", "ape", "alac",
                    )
                if (!isAudio) return null
                AudioFileDetails(
                    fileId = document.document.id,
                    fileSize = document.document.size.toLong().coerceAtLeast(0L),
                    title = fileName.substringBeforeLast('.').ifBlank { "Unknown title" },
                    artist = "Unknown artist",
                    durationMs = 0L,
                    mimeType = resolveAudioMimeType(document.mimeType, fileName),
                )
            }
            else -> null
        }
    }

    private fun TdApi.Chat.toTelegramChannel(): TelegramChannel {
        val isChannel = when (val chatType = type) {
            is TdApi.ChatTypeSupergroup -> chatType.isChannel
            is TdApi.ChatTypeBasicGroup -> false
            else -> throw IllegalArgumentException("This Telegram chat is not a group or channel.")
        }
        return TelegramChannel(
            chatId = id,
            title = title.ifBlank { "Telegram chat $id" },
            username = null,
            isChannel = isChannel,
        )
    }

    private fun resolveAudioMimeType(reportedMime: String, fileName: String): String {
        val normalized = reportedMime.trim().lowercase()
        if (normalized.startsWith("audio/") || normalized == "application/ogg") return normalized
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a", "m4b", "alac" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "opus" -> "audio/ogg"
            "ape" -> "audio/ape"
            else -> "audio/mpeg"
        }
    }

    suspend fun getFile(fileId: Int): TdApi.File =
        clientManager.sendRequest(TdApi.GetFile(fileId))

    suspend fun downloadFile(fileId: Int): TdApi.File =
        clientManager.sendRequest(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))
}
