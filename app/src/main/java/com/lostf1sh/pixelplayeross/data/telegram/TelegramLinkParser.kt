package com.lostf1sh.pixelplayeross.data.telegram

import java.net.URI

sealed interface TelegramChannelTarget {
    data class PublicUsername(val username: String) : TelegramChannelTarget
    data class PrivateInviteLink(val inviteLink: String) : TelegramChannelTarget
}

object TelegramLinkParser {
    private val usernamePattern = Regex("^[A-Za-z0-9_]{5,32}$")
    private val inviteHashPattern = Regex("^[A-Za-z0-9_-]{1,128}$")

    fun parse(input: String): TelegramChannelTarget {
        val value = input.trim()
        require(value.isNotEmpty()) { "Enter a Telegram username or invite link." }

        if (value.startsWith("@")) {
            return publicUsername(value.substring(1))
        }

        val lower = value.lowercase()
        val isTelegramUrl = lower.startsWith("t.me/") ||
            lower.startsWith("telegram.me/") ||
            lower.startsWith("https://t.me/") ||
            lower.startsWith("http://t.me/") ||
            lower.startsWith("https://telegram.me/") ||
            lower.startsWith("http://telegram.me/")

        if (isTelegramUrl || value.contains("://") || value.startsWith("//")) {
            return parseTelegramUrl(value)
        }

        require(!value.contains('/') && !value.contains('?') && !value.contains('#')) {
            "Enter a Telegram username or a link from t.me or telegram.me."
        }
        return publicUsername(value)
    }

    private fun parseTelegramUrl(value: String): TelegramChannelTarget {
        val normalized = when {
            value.startsWith("//") -> "https:$value"
            value.substringBefore("://", "").isEmpty() -> "https://$value"
            else -> value
        }
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw IllegalArgumentException("That Telegram link is not valid.")

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        require(scheme == "http" || scheme == "https") {
            "Telegram links must use HTTP or HTTPS."
        }
        require(host == "t.me" || host == "telegram.me") {
            "Only links hosted on t.me or telegram.me are supported."
        }
        require(uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null) {
            "That Telegram link contains unsupported URL components."
        }

        val rawPath = uri.rawPath.orEmpty()
        require(!rawPath.contains('%') && !rawPath.contains('\\')) {
            "Encoded or escaped Telegram link paths are not supported."
        }
        val path = rawPath.trimEnd('/')
        require(path.startsWith('/') && path.length > 1) {
            "That Telegram link does not contain a username or invite code."
        }
        val segments = path.substring(1).split('/')
        require(segments.none { it.isEmpty() }) {
            "That Telegram link contains an invalid path."
        }

        val canonicalPath = when {
            segments.size == 1 && segments[0].startsWith("+") -> {
                val hash = segments[0].substring(1)
                require(inviteHashPattern.matches(hash)) { "That private invite link is not valid." }
                "+$hash"
            }
            segments.size == 2 && segments[0].equals("joinchat", ignoreCase = true) -> {
                val hash = segments[1]
                require(inviteHashPattern.matches(hash)) { "That private invite link is not valid." }
                "joinchat/$hash"
            }
            segments.size == 1 -> {
                val username = segments[0].removePrefix("@")
                require(usernamePattern.matches(username)) {
                    "Use a public Telegram username (5–32 letters, numbers or underscores) or a private invite link."
                }
                return TelegramChannelTarget.PublicUsername(username)
            }
            else -> throw IllegalArgumentException("That Telegram link has an unsupported path.")
        }

        return TelegramChannelTarget.PrivateInviteLink("https://t.me/$canonicalPath")
    }

    private fun publicUsername(value: String): TelegramChannelTarget.PublicUsername {
        require(usernamePattern.matches(value)) {
            "Use a public Telegram username (5–32 letters, numbers or underscores) or a private invite link."
        }
        return TelegramChannelTarget.PublicUsername(value)
    }
}
