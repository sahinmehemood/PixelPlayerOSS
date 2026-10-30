package com.lostf1sh.pixelplayeross.data.telegram

internal object TelegramApiCredentialsValidator {
    private val apiHashPattern = Regex("^[A-Fa-f0-9]{32}$")

    fun isValid(apiId: Int, apiHash: String): Boolean =
        apiId > 0 && apiHashPattern.matches(apiHash.trim())

    fun validate(apiId: Int, apiHash: String) {
        require(apiId > 0) { "Telegram API ID must be a positive number." }
        require(apiHashPattern.matches(apiHash.trim())) {
            "Telegram API hash must be the 32-character hexadecimal value from my.telegram.org."
        }
    }
}
