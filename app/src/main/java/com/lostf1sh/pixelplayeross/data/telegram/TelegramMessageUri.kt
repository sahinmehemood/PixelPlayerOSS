package com.lostf1sh.pixelplayeross.data.telegram

import java.net.URI

data class TelegramMessageReference(
    val chatId: Long,
    val messageId: Long,
)

/**
 * Parses the stable identifier used by Telegram queue items.
 *
 * The URI carries only Telegram's chat/message identifiers. It must never contain
 * TDLib file paths, file IDs, auth material, or the per-process HTTP proxy token.
 */
object TelegramMessageUri {
    fun parse(value: String): TelegramMessageReference? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("telegram", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.port != -1 || uri.rawQuery != null || uri.rawFragment != null) {
            return null
        }
        val chatId = uri.rawAuthority?.toLongOrNull()?.takeIf { it != 0L } ?: return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith("/") || path.count { it == '/' } != 1) return null
        val messageId = path.removePrefix("/").toLongOrNull()?.takeIf { it > 0L } ?: return null
        return TelegramMessageReference(chatId = chatId, messageId = messageId)
    }

    fun build(chatId: Long, messageId: Long): String {
        require(chatId != 0L) { "A valid Telegram chat ID is required." }
        require(messageId > 0L) { "A valid Telegram message ID is required." }
        return "telegram://$chatId/$messageId"
    }
}
