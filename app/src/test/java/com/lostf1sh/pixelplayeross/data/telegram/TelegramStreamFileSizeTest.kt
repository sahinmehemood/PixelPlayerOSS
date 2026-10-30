package com.lostf1sh.pixelplayeross.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TelegramStreamFileSizeTest {
    private val maxBytes = 2L * 1024L * 1024L * 1024L

    @Test
    fun prefersExactTdlibSizeOverMessageHint() {
        assertEquals(
            10_000L,
            TelegramStreamFileSize.resolve(
                actualSize = 10_000L,
                hintedSize = 9_000L,
                localFileSize = 1_024L,
                isDownloadingCompleted = false,
                maxBytes = maxBytes,
            ),
        )
    }

    @Test
    fun usesMessageHintWhenTdlibExactSizeIsUnavailable() {
        assertEquals(
            9_000L,
            TelegramStreamFileSize.resolve(
                actualSize = 0L,
                hintedSize = 9_000L,
                localFileSize = 1_024L,
                isDownloadingCompleted = false,
                maxBytes = maxBytes,
            ),
        )
    }

    @Test
    fun doesNotUsePartialLocalLengthForContentLength() {
        assertNull(
            TelegramStreamFileSize.resolve(
                actualSize = 0L,
                hintedSize = 0L,
                localFileSize = 5_000L,
                isDownloadingCompleted = false,
                maxBytes = maxBytes,
            ),
        )
    }

    @Test
    fun usesLocalLengthOnlyAfterDownloadCompletes() {
        assertEquals(
            5_000L,
            TelegramStreamFileSize.resolve(
                actualSize = 0L,
                hintedSize = 0L,
                localFileSize = 5_000L,
                isDownloadingCompleted = true,
                maxBytes = maxBytes,
            ),
        )
    }

    @Test
    fun rejectsFilesAboveTheConfiguredLimit() {
        assertNull(
            TelegramStreamFileSize.resolve(
                actualSize = maxBytes + 1L,
                hintedSize = 0L,
                localFileSize = 0L,
                isDownloadingCompleted = false,
                maxBytes = maxBytes,
            ),
        )
        assertEquals(
            true,
            TelegramStreamFileSize.exceedsLimit(maxBytes + 1L, 0L, 0L, maxBytes),
        )
        assertEquals(
            true,
            TelegramStreamFileSize.exceedsLimit(0L, maxBytes + 1L, 0L, maxBytes),
        )
        assertEquals(
            true,
            TelegramStreamFileSize.exceedsLimit(0L, 0L, maxBytes + 1L, maxBytes),
        )
    }

    @Test
    fun rejectsEmptyCompletedFiles() {
        assertNull(
            TelegramStreamFileSize.resolve(
                actualSize = 0L,
                hintedSize = 0L,
                localFileSize = 0L,
                isDownloadingCompleted = true,
                maxBytes = maxBytes,
            ),
        )
    }
}
