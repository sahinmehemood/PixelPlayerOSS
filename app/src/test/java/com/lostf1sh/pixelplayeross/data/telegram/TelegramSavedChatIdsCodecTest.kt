package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramSavedChatIdsCodecTest {
    @Test
    fun encodesUniqueSortedNonZeroIds() {
        assertEquals(
            "-20,10,30",
            TelegramSavedChatIdsCodec.encode(listOf(30L, 10L, 30L, 0L, -20L)),
        )
    }

    @Test
    fun decodesUniqueNonZeroIds() {
        assertEquals(
            setOf(10L, 20L, -30L),
            TelegramSavedChatIdsCodec.decode("10,20,10,0,-30,invalid"),
        )
    }

    @Test
    fun blankAndMalformedValuesBecomeAnEmptySet() {
        assertEquals(emptySet<Long>(), TelegramSavedChatIdsCodec.decode(null))
        assertEquals(emptySet<Long>(), TelegramSavedChatIdsCodec.decode(""))
        assertEquals(emptySet<Long>(), TelegramSavedChatIdsCodec.decode("nope,,0"))
    }

    @Test
    fun encodingAnEmptySetProducesAnEmptyValue() {
        assertEquals("", TelegramSavedChatIdsCodec.encode(emptySet()))
    }
}
