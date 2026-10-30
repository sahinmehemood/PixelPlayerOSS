package com.lostf1sh.pixelplayeross.data.telegram

data class TelegramChannel(
    val chatId: Long,
    val title: String,
    val username: String? = null,
    val isChannel: Boolean = true,
)
