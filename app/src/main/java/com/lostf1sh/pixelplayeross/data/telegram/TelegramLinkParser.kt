package com.lostf1sh.pixelplayeross.data.telegram

sealed interface TelegramChannelTarget {
    data class PublicUsername(val username: String) : TelegramChannelTarget
    data class PrivateInviteLink(val inviteLink: String) : TelegramChannelTarget
}

object TelegramLinkParser {
    private val usernamePattern = Regex("^[A-Za-z0-9_]{5,32}$")

    fun parse(input: String): TelegramChannelTarget {
        val value = input.trim()
        require(value.isNotEmpty()) { "Enter a Telegram username or invite link." }

        val withoutQuery = value.substringBefore('?').trimEnd('/')
        val lower = withoutQuery.lowercase()
        val isPrivateInvite = lower.contains("t.me/+") ||
            lower.contains("telegram.me/+") ||
            lower.contains("/joinchat/") ||
            withoutQuery.startsWith("+")

        if (isPrivateInvite) {
            val canonical = when {
                withoutQuery.startsWith("+") -> "https://t.me/$withoutQuery"
                lower.startsWith("http://") || lower.startsWith("https://") -> withoutQuery
                    .replace(Regex("^http://"), "https://")
                lower.startsWith("t.me/") -> "https://$withoutQuery"
                lower.startsWith("telegram.me/") -> "https://t.me/" + withoutQuery.substringAfter('/')
                else -> "https://t.me/$withoutQuery"
            }
            require(canonical.contains("/+") || canonical.contains("/joinchat/")) {
                "That invite link does not look valid."
            }
            return TelegramChannelTarget.PrivateInviteLink(canonical)
        }

        val candidate = when {
            lower.contains("t.me/") -> withoutQuery.substringAfter("t.me/").substringBefore('/')
            lower.contains("telegram.me/") -> withoutQuery.substringAfter("telegram.me/").substringBefore('/')
            value.startsWith("@") -> value.removePrefix("@")
            else -> value.substringBefore('/')
        }.removePrefix("@")

        require(usernamePattern.matches(candidate)) {
            "Use a public Telegram username (5–32 letters, numbers or underscores) or a private invite link."
        }
        return TelegramChannelTarget.PublicUsername(candidate)
    }
}
