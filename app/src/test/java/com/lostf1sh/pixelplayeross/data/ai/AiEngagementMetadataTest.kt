package com.lostf1sh.pixelplayeross.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiEngagementMetadataTest {
    @Test
    fun returnsNullWhenTrackHasNeverBeenPlayed() {
        assertNull(AiEngagementMetadata.daysSinceLastPlayed(0L, 1_000_000L))
        assertNull(AiEngagementMetadata.daysSinceLastPlayed(-1L, 1_000_000L))
    }

    @Test
    fun roundsRecencyDownToWholeDays() {
        val now = 20L * 86_400_000L + 12_345L
        val lastPlayed = now - (4L * 86_400_000L) - 86_399_999L

        assertEquals(4L, AiEngagementMetadata.daysSinceLastPlayed(lastPlayed, now))
    }

    @Test
    fun returnsZeroForRecentPlayback() {
        val now = 10L * 86_400_000L
        assertEquals(0L, AiEngagementMetadata.daysSinceLastPlayed(now - 60_000L, now))
    }

    @Test
    fun clampsFutureTimestampsToZeroDays() {
        val now = 10L * 86_400_000L
        assertEquals(0L, AiEngagementMetadata.daysSinceLastPlayed(now + 60_000L, now))
    }
}
