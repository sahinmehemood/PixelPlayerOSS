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

    fun isReady(): Boolean = clientManager.isReady()

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

        clientManager.sendRequest<TdApi.Ok>(TdApi.ImportChatInviteLink(inviteLink))
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
        runCatching {
            clientManager.sendRequest<TdApi.Ok>(TdApi.LoadChats(TdApi.ChatListMain(), 500))
        }
        val chats = clientManager.sendRequest<TdApi.Chats>(
            TdApi.GetChats().apply {
                chatList = TdApi.ChatListMain()
                limit = 500
            }
        )
        return chats.chatIds.mapNotNull { chatId ->
            runCatching { clientManager.sendRequest<TdApi.Chat>(TdApi.GetChat(chatId)).toTelegramChannel() }
                .onFailure { Timber.d(it, "Could not resolve Telegram chat %s", chatId) }
                .getOrNull()
        }.distinctBy { it.chatId }
            .sortedBy { it.title.lowercase() }
    }

    suspend fun getAudioMessages(channel: TelegramChannel): List<Song> {
        check(clientManager.awaitReady()) { "Telegram is not connected." }
        streamProxy.ensureStarted()
        clientManager.sendRequest<TdApi.Ok>(TdApi.OpenChat(channel.chatId))

        val songs = ArrayList<Song>()
        var fromMessageId = 0L
        repeat(200) {
            val request = TdApi.SearchChatMessages().apply {
                chatId = channel.chatId
                query = ""
                senderId = null
                this.fromMessageId = fromMessageId
                offset = 0
                limit = 100
                filter = TdApi.SearchMessagesFilterAudio()
            }
            val response = clientManager.sendRequest<TdApi.FoundChatMessages>(request)
            if (response.messages.isEmpty()) return songs
            response.messages.forEach { message ->
                mapAudioMessage(message, channel)?.let(songs::add)
            }
            val next = response.nextFromMessageId
            if (next == 0L || next == fromMessageId) return songs
            fromMessageId = next
        }
        return songs
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
                val fileSize = audio.audio.size.toLong()
                title = audio.title.ifBlank { audio.fileName.substringBeforeLast('.').ifBlank { "Unknown title" } }
                performer = audio.performer.ifBlank { "Unknown artist" }
                durationMs = audio.duration.toLong().coerceAtLeast(0L) * 1000L
                mimeType = audio.mimeType.ifBlank { "audio/mpeg" }
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
                val fileSize = document.document.size.toLong()
                title = fileName.substringBeforeLast('.').ifBlank { "Unknown title" }
                performer = "Unknown artist"
                durationMs = 0L
                mimeType = document.mimeType.ifBlank { "audio/mpeg" }
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
        val isChannel = type is TdApi.ChatTypeSupergroup
        return TelegramChannel(
            chatId = id,
            title = title.ifBlank { "Telegram chat $id" },
            username = null,
            isChannel = isChannel,
        )
    }

    suspend fun getFile(fileId: Int): TdApi.File =
        clientManager.sendRequest(TdApi.GetFile(fileId))

    suspend fun downloadFile(fileId: Int): TdApi.File =
        clientManager.sendRequest(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))
}
