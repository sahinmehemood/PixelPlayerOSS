package com.lostf1sh.pixelplayeross.data.telegram

import com.lostf1sh.pixelplayeross.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi
import timber.log.Timber

@Singleton
class TelegramRepository @Inject constructor(
    private val clientManager: TelegramClientManager,
    private val streamProxy: TelegramStreamProxy,
) {
    val authorizationState = clientManager.authorizationState
    val initializationError = clientManager.initializationError
    val errors = clientManager.errors

    fun hasApiCredentials(): Boolean = clientManager.hasApiCredentials()

    fun isNativeLibraryLoaded(): Boolean = clientManager.isNativeLibraryLoaded()

    fun isReady(): Boolean = clientManager.isReady()

    fun configureApiCredentials(apiId: Int, apiHash: String) {
        TelegramApiCredentialsValidator.validate(apiId, apiHash)
        val credentials = TelegramApiCredentials.parse(apiId.toString(), apiHash)
        clientManager.configureApiCredentials(credentials)
    }

    suspend fun sendPhoneNumber(phoneNumber: String) = clientManager.sendPhoneNumber(phoneNumber)
    suspend fun checkAuthenticationCode(code: String) = clientManager.checkAuthenticationCode(code)
    suspend fun checkAuthenticationPassword(password: String) = clientManager.checkAuthenticationPassword(password)
    suspend fun logout() = clientManager.logout()

    suspend fun addChannel(input: String): TelegramChannel {
        check(clientManager.awaitReady()) { "Sign in to Telegram before adding a channel." }
        return when (val target = TelegramLinkParser.parse(input)) {
            is TelegramChannelTarget.PublicUsername -> {
                val chat = clientManager.sendRequest<TdApi.Chat>(
                    TdApi.SearchPublicChat(target.username)
                )
                chat.toTelegramChannel()
            }
            is TelegramChannelTarget.PrivateInviteLink -> joinPrivateInvite(target.inviteLink)
        }
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
            val refreshed = runCatching {
                clientManager.sendRequest<TdApi.ChatInviteLinkInfo>(TdApi.CheckChatInviteLink(inviteLink))
            }.getOrNull()
            if (refreshed != null && refreshed.chatId != 0L) {
                return clientManager.sendRequest<TdApi.Chat>(TdApi.GetChat(refreshed.chatId)).toTelegramChannel()
            }
            val joined = runCatching { listJoinedChannels() }.getOrDefault(emptyList())
                .firstOrNull { it.title.equals(invite.title, ignoreCase = true) }
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
        val chatsById = linkedMapOf<Long, TdApi.Chat>()
        chatLists.forEach { chatList ->
            // LoadChats may report that a list is already fully loaded. That is not a
            // reason to skip GetChats; the locally cached list is still readable.
            runCatching {
                clientManager.sendRequest<TdApi.Ok>(TdApi.LoadChats(chatList, 500))
            }.onFailure {
                Timber.d(it, "Telegram chat list is already loaded or could not be extended")
            }

            val chats = runCatching {
                clientManager.sendRequest<TdApi.Chats>(
                    TdApi.GetChats(chatList, 500)
                )
            }.onFailure {
                Timber.d(it, "Could not read one Telegram chat list")
            }.getOrNull() ?: return@forEach

            chats.chatIds.forEach { chatId ->
                val chat = runCatching {
                    clientManager.sendRequest<TdApi.Chat>(TdApi.GetChat(chatId))
                }.onFailure {
                    Timber.d(it, "Could not resolve Telegram chat %s", chatId)
                }.getOrNull()
                if (chat != null) chatsById[chat.id] = chat
            }
        }
        return chatsById.values.mapNotNull { chat ->
            runCatching { chat.toTelegramChannel() }
                .onFailure { Timber.d(it, "Skipping non-channel Telegram chat %s", chat.id) }
                .getOrNull()
        }.sortedBy { it.title.lowercase() }
    }

    suspend fun getAudioMessages(channel: TelegramChannel): List<Song> {
        check(clientManager.awaitReady()) { "Telegram is not connected." }
        streamProxy.ensureStarted()
        clientManager.sendRequest<TdApi.Ok>(TdApi.OpenChat(channel.chatId))

        // Search audio messages and generic documents: music files are often uploaded
        // as documents rather than Telegram's dedicated audio message type.
        val messagesById = linkedMapOf<Long, TdApi.Message>()
        val filters = listOf<TdApi.SearchMessagesFilter>(
            TdApi.SearchMessagesFilterAudio(),
            TdApi.SearchMessagesFilterDocument(),
        )
        filters.forEach { filter ->
            var fromMessageId = 0L
            var page = 0
            while (page < 200) {
                val request = TdApi.SearchChatMessages().apply {
                    chatId = channel.chatId
                    query = ""
                    senderId = null
                    this.fromMessageId = fromMessageId
                    offset = 0
                    limit = 100
                    this.filter = filter
                }
                val response = clientManager.sendRequest<TdApi.FoundChatMessages>(request)
                if (response.messages.isEmpty()) break
                response.messages.forEach { message -> messagesById.putIfAbsent(message.id, message) }
                val next = response.nextFromMessageId
                if (next == 0L || next == fromMessageId) break
                fromMessageId = next
                page += 1
            }
        }
        return messagesById.values
            .sortedByDescending { it.date }
            .mapNotNull { message -> mapAudioMessage(message, channel) }
    }

    private suspend fun mapAudioMessage(message: TdApi.Message, channel: TelegramChannel): Song? {
        val fileId: Int
        val title: String
        val performer: String
        val durationMs: Long
        val mimeType: String
        when (val content = message.content) {
            is TdApi.MessageAudio -> {
                val audio = content.audio
                fileId = audio.audio.id
                title = audio.title.ifBlank { audio.fileName.substringBeforeLast('.').ifBlank { "Unknown title" } }
                performer = audio.performer.ifBlank { "Unknown artist" }
                durationMs = audio.duration.toLong().coerceAtLeast(0L) * 1000L
                mimeType = resolveAudioMimeType(audio.mimeType, audio.fileName)
            }
            is TdApi.MessageDocument -> {
                val document = content.document
                val fileName = document.fileName
                val looksLikeAudio = document.mimeType.startsWith("audio/") ||
                    document.mimeType == "application/ogg" ||
                    fileName.substringAfterLast('.', "").lowercase() in
                    setOf("mp3", "flac", "wav", "m4a", "ogg", "opus", "aac", "ape", "alac")
                if (!looksLikeAudio) return null
                fileId = document.document.id
                title = fileName.substringBeforeLast('.').ifBlank { "Unknown title" }
                performer = "Unknown artist"
                durationMs = 0L
                mimeType = resolveAudioMimeType(document.mimeType, fileName)
            }
            else -> return null
        }

        if (fileId <= 0) return null
        val fileSize = when (val content = message.content) {
            is TdApi.MessageAudio -> content.audio.audio.size.toLong()
            is TdApi.MessageDocument -> content.document.document.size.toLong()
            else -> 0L
        }
        val streamUrl = streamProxy.streamUrl(fileId, fileSize, mimeType)
        return Song(
            id = "telegram:${message.chatId}:${message.id}",
            title = title,
            artist = performer,
            artistId = -1L,
            album = channel.title,
            albumId = -1L,
            path = streamUrl,
            contentUriString = streamUrl,
            albumArtUriString = null,
            duration = durationMs,
            genre = null,
            lyrics = null,
            isFavorite = false,
            trackNumber = 0,
            discNumber = null,
            year = 0,
            dateAdded = message.date.toLong(),
            dateModified = 0L,
            mimeType = mimeType,
            bitrate = null,
            sampleRate = null,
        )
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
