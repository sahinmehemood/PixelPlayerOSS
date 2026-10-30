package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramByteRangeTest {
    @Test
    fun fullRequestReturnsEntireFile() {
        assertEquals(
            TelegramByteRange.Range(0L, 999L, isPartial = false),
            TelegramByteRange.parse(null, 1_000L),
        )
    }

    @Test
    fun parsesClosedRange() {
        assertEquals(
            TelegramByteRange.Range(100L, 199L, isPartial = true),
            TelegramByteRange.parse("bytes=100-199", 1_000L),
        )
    }

    @Test
    fun parsesOpenEndedRange() {
        assertEquals(
            TelegramByteRange.Range(500L, 999L, isPartial = true),
            TelegramByteRange.parse("bytes=500-", 1_000L),
        )
    }

    @Test
    fun parsesSuffixRange() {
        assertEquals(
            TelegramByteRange.Range(900L, 999L, isPartial = true),
            TelegramByteRange.parse("bytes=-100", 1_000L),
        )
    }

    @Test
    fun clampsEndToFileSize() {
        assertEquals(
            TelegramByteRange.Range(900L, 999L, isPartial = true),
            TelegramByteRange.parse("bytes=900-5000", 1_000L),
        )
    }

    @Test
    fun clampsSuffixLargerThanFileToWholeFile() {
        assertEquals(
            TelegramByteRange.Range(0L, 999L, isPartial = true),
            TelegramByteRange.parse("bytes=-5000", 1_000L),
        )
    }

    @Test
    fun rejectsStartAtOrPastEndOfFile() {
        assertNull(TelegramByteRange.parse("bytes=1000-", 1_000L))
    }

    @Test
    fun rejectsReversedRange() {
        assertNull(TelegramByteRange.parse("bytes=200-100", 1_000L))
    }

    @Test
    fun rejectsMultipleRanges() {
        assertNull(TelegramByteRange.parse("bytes=0-10,20-30", 1_000L))
    }

    @Test
    fun rejectsMalformedAndOverflowingValues() {
        assertNull(TelegramByteRange.parse("items=0-10", 1_000L))
        assertNull(TelegramByteRange.parse("bytes=-0", 1_000L))
        assertNull(TelegramByteRange.parse("bytes=999999999999999999999-", 1_000L))
        assertNull(TelegramByteRange.parse("bytes=0-10".repeat(10), 1_000L))
    }

    @Test
    fun rejectsOverflowingRangeEnd() {
        assertNull(
            TelegramByteRange.parse("bytes=0-999999999999999999999", 1_000L)
        )
    }

    @Test
    fun rejectsNonPositiveFileSize() {
        assertNull(TelegramByteRange.parse(null, 0L))
        assertNull(TelegramByteRange.parse("bytes=0-0", -1L))
    }
}
