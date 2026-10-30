package com.lostf1sh.pixelplayeross.data.telegram

import com.lostf1sh.pixelplayeross.data.security.EncryptedSecretStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the channel IDs explicitly added by the user.
 *
 * Public Telegram channels do not have to be joined to be searched and streamed.
 * TDLib therefore does not necessarily include them in GetChats(Main/Archive);
 * keeping their IDs separately preserves the user's in-app channel list.
 */
@Singleton
class TelegramChannelStore @Inject constructor(
    private val secretStore: EncryptedSecretStore,
) {
    companion object {
        private const val SAVED_CHAT_IDS = "telegram_saved_chat_ids"
    }

    @Synchronized
    fun getSavedChatIds(): Set<Long> = TelegramSavedChatIdsCodec.decode(
        secretStore.getString(SAVED_CHAT_IDS),
    )

    @Synchronized
    fun save(chatId: Long) {
        require(chatId != 0L) { "A valid Telegram chat ID is required." }
        val updated = getSavedChatIds() + chatId
        secretStore.putString(SAVED_CHAT_IDS, TelegramSavedChatIdsCodec.encode(updated))
    }

    @Synchronized
    fun remove(chatId: Long) {
        val updated = getSavedChatIds() - chatId
        secretStore.putString(SAVED_CHAT_IDS, TelegramSavedChatIdsCodec.encode(updated))
    }

    @Synchronized
    fun remove(chatId: Long) {
        val updated = getSavedChatIds() - chatId
        secretStore.putString(SAVED_CHAT_IDS, TelegramSavedChatIdsCodec.encode(updated))
    }
}

internal object TelegramSavedChatIdsCodec {
    fun decode(value: String?): Set<Long> = value.orEmpty()
        .split(',')
        .mapNotNull { it.trim().toLongOrNull() }
        .filter { it != 0L }
        .toSet()

    fun encode(chatIds: Collection<Long>): String = chatIds
        .asSequence()
        .filter { it != 0L }
        .distinct()
        .sorted()
        .joinToString(",")
}
