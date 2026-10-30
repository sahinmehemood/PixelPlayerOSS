package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAudioCachePolicyTest {
    @Test
    fun rejectsEmptyTrackCache() {
        assertFalse(
            TelegramAudioCachePolicy.isFresh(
                lastSyncTimeMs = 1_000L,
                nowMs = 2_000L,
                hasTracks = false,
            ),
        )
    }

    @Test
    fun rejectsUnknownSyncTime() {
        assertFalse(TelegramAudioCachePolicy.isFresh(0L, 1_000L, hasTracks = true))
        assertFalse(TelegramAudioCachePolicy.isFresh(-1L, 1_000L, hasTracks = true))
    }

    @Test
    fun acceptsCacheWithinFreshnessWindow() {
        val now = 2_000_000L
        assertTrue(
            TelegramAudioCachePolicy.isFresh(
                lastSyncTimeMs = now - 1L,
                nowMs = now,
                hasTracks = true,
            ),
        )
    }

    @Test
    fun acceptsExactFreshnessBoundary() {
        val now = 5_000_000L
        assertTrue(
            TelegramAudioCachePolicy.isFresh(
                lastSyncTimeMs = now - TelegramAudioCachePolicy.MAX_AGE_MS,
                nowMs = now,
                hasTracks = true,
            ),
        )
    }

    @Test
    fun rejectsCacheOlderThanFreshnessWindow() {
        val now = 5_000_000L
        assertFalse(
            TelegramAudioCachePolicy.isFresh(
                lastSyncTimeMs = now - TelegramAudioCachePolicy.MAX_AGE_MS - 1L,
                nowMs = now,
                hasTracks = true,
            ),
        )
    }

    @Test
    fun rejectsFutureSyncTimeAfterClockRollback() {
        assertFalse(
            TelegramAudioCachePolicy.isFresh(
                lastSyncTimeMs = 2_000L,
                nowMs = 1_000L,
                hasTracks = true,
            ),
        )
    }
}
