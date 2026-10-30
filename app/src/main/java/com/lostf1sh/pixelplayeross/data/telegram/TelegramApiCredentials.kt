package com.lostf1sh.pixelplayeross.data.telegram

/**
 * Validated Telegram application credentials. The API hash is never included in toString()
 * so accidental diagnostic logging cannot expose it.
 */
class TelegramApiCredentials private constructor(
    val apiId: Int,
    val apiHash: String,
) {
    override fun toString(): String = "TelegramApiCredentials(apiId=$apiId, apiHash=[redacted])"

    companion object {
        private val apiHashPattern = Regex("^[A-Fa-f0-9]{32}$")

        fun parse(apiIdInput: String, apiHashInput: String): TelegramApiCredentials {
            val apiId = apiIdInput.trim().toIntOrNull()
                ?: throw IllegalArgumentException("Telegram API ID must be a positive number.")
            require(apiId > 0) { "Telegram API ID must be a positive number." }

            val apiHash = apiHashInput.trim()
            require(apiHashPattern.matches(apiHash)) {
                "Telegram API hash must contain exactly 32 hexadecimal characters."
            }
            return TelegramApiCredentials(apiId, apiHash.lowercase())
        }

        fun fromCompiledConfig(apiId: Int, apiHash: String): TelegramApiCredentials? =
            runCatching { parse(apiId.toString(), apiHash) }.getOrNull()
    }
}
