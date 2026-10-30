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
        fun parse(apiIdInput: String, apiHashInput: String): TelegramApiCredentials {
            val apiId = apiIdInput.trim().toIntOrNull()
                ?: throw IllegalArgumentException("Telegram API ID must be a positive number.")
            TelegramApiCredentialsValidator.validate(apiId, apiHashInput)
            return TelegramApiCredentials(apiId, apiHashInput.trim().lowercase())
        }

        fun fromCompiledConfig(apiId: Int, apiHash: String): TelegramApiCredentials? =
            runCatching { parse(apiId.toString(), apiHash) }.getOrNull()
    }
}
