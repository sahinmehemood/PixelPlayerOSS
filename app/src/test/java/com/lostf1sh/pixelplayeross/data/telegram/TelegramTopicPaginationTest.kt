package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramTopicPaginationTest {
    @Test
    fun identifiesOnlyTheAllZeroCursorAsTerminal() {
        assertTrue(TelegramTopicPagination.Cursor(0, 0L, 0).isTerminal)
        assertFalse(TelegramTopicPagination.Cursor(1, 0L, 0).isTerminal)
        assertFalse(TelegramTopicPagination.Cursor(0, 10L, 0).isTerminal)
        assertFalse(TelegramTopicPagination.Cursor(0, 0L, 10).isTerminal)
    }

    @Test
    fun recognizesProgressWhenAnyCursorComponentChanges() {
        val current = TelegramTopicPagination.Cursor(100, 500L, 40)

        assertTrue(
            TelegramTopicPagination.Cursor(99, 500L, 40).advancesFrom(current),
        )
        assertTrue(
            TelegramTopicPagination.Cursor(100, 499L, 40).advancesFrom(current),
        )
        assertTrue(
            TelegramTopicPagination.Cursor(100, 500L, 39).advancesFrom(current),
        )
    }

    @Test
    fun rejectsAnUnchangedCursor() {
        val current = TelegramTopicPagination.Cursor(100, 500L, 40)

        assertFalse(current.advancesFrom(current.copy()))
    }

    @Test
    fun acceptsNegativeChatMessageOffsetsAsLongValues() {
        val current = TelegramTopicPagination.Cursor(100, -500L, 40)
        val next = TelegramTopicPagination.Cursor(99, -499L, 39)

        assertTrue(next.advancesFrom(current))
        assertFalse(next.isTerminal)
    }
}
