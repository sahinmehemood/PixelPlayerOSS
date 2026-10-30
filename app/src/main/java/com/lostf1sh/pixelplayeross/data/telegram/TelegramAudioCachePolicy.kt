package com.lostf1sh.pixelplayeross.data.telegram

/**
 * Short-lived metadata cache for channel scans.
 *
 * A fresh cache avoids hitting TDLib every time a user reopens a channel. Stale
 * caches remain visible while a new scan runs; they are never silently treated
 * as fresh after a clock rollback or an unknown sync timestamp.
 */
internal object TelegramAudioCachePolicy {
    const val MAX_AGE_MS: Long = 10L * 60L * 1_000L

    fun isFresh(lastSyncTimeMs: Long, nowMs: Long, hasTracks: Boolean): Boolean {
        if (!hasTracks || lastSyncTimeMs <= 0L || nowMs < lastSyncTimeMs) return false
        return nowMs - lastSyncTimeMs <= MAX_AGE_MS
    }
}
