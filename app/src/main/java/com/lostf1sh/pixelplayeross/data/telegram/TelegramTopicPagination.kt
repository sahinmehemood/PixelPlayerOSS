package com.lostf1sh.pixelplayeross.data.telegram

/**
 * Typed pagination state for TdApi.GetForumTopics.
 *
 * TDLib uses a three-part cursor. Treating only the date or message ID as the
 * cursor can falsely detect a repeated page because topic IDs can advance while
 * the other components remain unchanged.
 */
internal object TelegramTopicPagination {
    data class Cursor(
        val date: Int,
        val messageId: Long,
        val forumTopicId: Int,
    ) {
        val isTerminal: Boolean
            get() = date == 0 && messageId == 0L && forumTopicId == 0

        fun advancesFrom(previous: Cursor): Boolean = this != previous
    }
}
