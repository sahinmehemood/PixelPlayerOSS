package com.lostf1sh.pixelplayeross.data.telegram

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import timber.log.Timber

internal object TelegramStreamAccessToken {
    private const val TOKEN_BYTES = 32
    private const val HEX = "0123456789abcdef"
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        synchronized(random) { random.nextBytes(bytes) }
        return buildString(TOKEN_BYTES * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    fun matches(expected: String, supplied: String): Boolean {
        if (expected.length != TOKEN_BYTES * 2 || supplied.length != expected.length) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8),
        )
    }
}


internal object TelegramStreamFileSize {
    /**
     * Only exact sizes can be advertised as HTTP Content-Length or used for byte ranges.
     * TdApi.File.expectedSize is approximate and must not be treated as an exact length.
     */
    fun resolve(
        actualSize: Long,
        hintedSize: Long,
        localFileSize: Long,
        isDownloadingCompleted: Boolean,
        maxBytes: Long,
    ): Long? {
        val candidate = when {
            actualSize > 0L -> actualSize
            hintedSize > 0L -> hintedSize
            isDownloadingCompleted && localFileSize > 0L -> localFileSize
            else -> return null
        }
        return candidate.takeIf { it in 1..maxBytes }
    }

    fun exceedsLimit(actualSize: Long, hintedSize: Long, localFileSize: Long, maxBytes: Long): Boolean =
        actualSize > maxBytes || hintedSize > maxBytes || localFileSize > maxBytes
}

internal object TelegramByteRange {
    data class Range(
        val start: Long,
        val end: Long,
        val isPartial: Boolean,
    )

    private val pattern = Regex("^bytes=(\\d*)-(\\d*)$")

    fun parse(header: String?, size: Long): Range? {
        if (size <= 0L) return null
        if (header == null) return Range(0L, size - 1L, isPartial = false)
        if (header.length > 64) return null

        val match = pattern.matchEntire(header.trim()) ?: return null
        val first = match.groupValues[1]
        val last = match.groupValues[2]
        if (first.isEmpty() && last.isEmpty()) return null

        return if (first.isEmpty()) {
            val suffixLength = last.toLongOrNull()?.takeIf { it > 0L } ?: return null
            Range(
                start = (size - suffixLength).coerceAtLeast(0L),
                end = size - 1L,
                isPartial = true,
            )
        } else {
            val start = first.toLongOrNull() ?: return null
            val end = if (last.isEmpty()) {
                size - 1L
            } else {
                last.toLongOrNull() ?: return null
            }
            if (start >= size || end < start) return null
            Range(
                start = start,
                end = end.coerceAtMost(size - 1L),
                isPartial = true,
            )
        }
    }
}

@Singleton
class TelegramStreamProxy @Inject constructor(
    private val clientManager: TelegramClientManager,
) {
    companion object {
        private const val MAX_STREAM_BYTES = 2L * 1024L * 1024L * 1024L
        private const val MAX_WAIT_MS = 180_000L
    }

    private val startMutex = Mutex()
    @Volatile private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    @Volatile private var port: Int = 0
    @Volatile private var streamToken: String = ""

    suspend fun ensureStarted() {
        if (server != null && port > 0) return
        startMutex.withLock {
            if (server != null && port > 0) return
            val selectedToken = TelegramStreamAccessToken.generate()
            val instance = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
                routing {
                    get("/stream/{fileId}/{token}") {
                        val suppliedToken = call.parameters["token"].orEmpty()
                        if (!TelegramStreamAccessToken.matches(selectedToken, suppliedToken)) {
                            call.respond(HttpStatusCode.NotFound, "Not found")
                            return@get
                        }

                        val fileId = call.parameters["fileId"]?.toIntOrNull()
                        if (fileId == null || fileId <= 0) {
                            call.respond(HttpStatusCode.BadRequest, "Invalid Telegram file id")
                            return@get
                        }
                        if (!clientManager.awaitReady(10_000L)) {
                            call.respond(HttpStatusCode.ServiceUnavailable, "Telegram is not connected")
                            return@get
                        }

                        val hintedSize = call.request.queryParameters["size"]?.toLongOrNull()
                            ?.takeIf { it in 1..MAX_STREAM_BYTES } ?: 0L
                        val mime = call.request.queryParameters["mime"]?.takeIf { it.length <= 80 }
                            ?: "audio/mpeg"

                        var fileInfo = try {
                            clientManager.sendRequest<TdApi.File>(TdApi.DownloadFile(fileId, 32, 0L, 0L, false))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Timber.w(error, "Telegram could not start download for file %s", fileId)
                            call.respond(HttpStatusCode.BadGateway, "Telegram could not start the download")
                            return@get
                        }
                        val pathDeadline = System.currentTimeMillis() + 10_000L
                        while (fileInfo.local.path.isBlank() && System.currentTimeMillis() < pathDeadline) {
                            delay(100)
                            fileInfo = refreshFileInfo(fileId, fileInfo)
                        }
                        if (fileInfo.local.path.isBlank()) {
                            call.respond(HttpStatusCode.GatewayTimeout, "Telegram did not allocate a local file")
                            return@get
                        }

                        val file = File(fileInfo.local.path)
                        val fileDeadline = System.currentTimeMillis() + 15_000L
                        while (!file.isFile && System.currentTimeMillis() < fileDeadline) {
                            delay(100)
                            fileInfo = refreshFileInfo(fileId, fileInfo)
                        }
                        if (!file.isFile) {
                            call.respond(HttpStatusCode.GatewayTimeout, "Telegram did not create the local audio file")
                            return@get
                        }

                        if (TelegramStreamFileSize.exceedsLimit(
                                fileInfo.size,
                                hintedSize,
                                file.length(),
                                MAX_STREAM_BYTES,
                            )
                        ) {
                            call.respond(HttpStatusCode.PayloadTooLarge, "Telegram audio file exceeds the 2 GiB streaming limit")
                            return@get
                        }

                        var size = TelegramStreamFileSize.resolve(
                            actualSize = fileInfo.size,
                            hintedSize = hintedSize,
                            localFileSize = file.length(),
                            isDownloadingCompleted = fileInfo.local.isDownloadingCompleted,
                            maxBytes = MAX_STREAM_BYTES,
                        )

                        // Some TDLib message types do not include a file-size hint. In
                        // that case we cannot safely advertise Content-Length or honour
                        // Range requests while the prefix is still growing. Wait for
                        // completion, then serve the complete local file with real ranges.
                        if (size == null && !fileInfo.local.isDownloadingCompleted) {
                            val sizeDeadline = System.currentTimeMillis() + MAX_WAIT_MS
                            while (!fileInfo.local.isDownloadingCompleted &&
                                System.currentTimeMillis() < sizeDeadline
                            ) {
                                if (!fileInfo.local.isDownloadingActive &&
                                    !fileInfo.local.canBeDownloaded
                                ) {
                                    break
                                }
                                delay(200)
                                fileInfo = refreshFileInfo(fileId, fileInfo)
                            }
                            size = TelegramStreamFileSize.resolve(
                                actualSize = fileInfo.size,
                                hintedSize = hintedSize,
                                localFileSize = file.length(),
                                isDownloadingCompleted = fileInfo.local.isDownloadingCompleted,
                                maxBytes = MAX_STREAM_BYTES,
                            )
                        }

                        if (size == null) {
                            if (TelegramStreamFileSize.exceedsLimit(
                                    fileInfo.size,
                                    hintedSize,
                                    file.length(),
                                    MAX_STREAM_BYTES,
                                )
                            ) {
                                call.respond(HttpStatusCode.PayloadTooLarge, "Telegram audio file exceeds the 2 GiB streaming limit")
                            } else {
                                call.respond(HttpStatusCode.ServiceUnavailable, "Telegram has not reported the audio file size and the download is incomplete")
                            }
                            return@get
                        }

                        val range = TelegramByteRange.parse(call.request.headers["Range"], size)
                        if (range == null) {
                            call.response.header("Content-Range", "bytes */$size")
                            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)
                            return@get
                        }
                        val start = range.start
                        val end = range.end
                        val isPartial = range.isPartial
                        val contentLength = end - start + 1L
                        val responseStatus = if (isPartial) HttpStatusCode.PartialContent else HttpStatusCode.OK
                        call.response.status(responseStatus)
                        call.response.header("Accept-Ranges", "bytes")
                        call.response.header("Content-Length", contentLength.toString())
                        if (isPartial) call.response.header("Content-Range", "bytes $start-$end/$size")

                        call.respondBytesWriter(contentType = ContentType.parse(mime)) {
                            val deadline = System.currentTimeMillis() + MAX_WAIT_MS
                            var current = start
                            var waitMs = 40L
                            var lastFileInfo = fileInfo
                            RandomAccessFile(file, "r").use { randomAccess ->
                                while (current <= end) {
                                    if (System.currentTimeMillis() > deadline) {
                                        throw IllegalStateException("Timed out waiting for Telegram audio bytes")
                                    }
                                    lastFileInfo = refreshFileInfo(fileId, lastFileInfo)

                                    val availablePrefix = lastFileInfo.local.downloadedPrefixSize.toLong().coerceAtLeast(0L)
                                    val availableEnd = minOf(end + 1L, availablePrefix, file.length())
                                    if (current < availableEnd) {
                                        randomAccess.seek(current)
                                        val requested = minOf(64L * 1024L, availableEnd - current).toInt()
                                        val buffer = ByteArray(requested)
                                        val count = randomAccess.read(buffer, 0, requested)
                                        if (count > 0) {
                                            writeFully(buffer, 0, count)
                                            flush()
                                            current += count
                                            waitMs = 40L
                                            continue
                                        }
                                    }

                                    if (lastFileInfo.local.isDownloadingCompleted) {
                                        if (current <= end) {
                                            throw IllegalStateException("Telegram finished the download before the requested range was available")
                                        }
                                        break
                                    }
                                    if (!lastFileInfo.local.isDownloadingActive && lastFileInfo.local.canBeDownloaded.not()) {
                                        throw IllegalStateException("Telegram cannot continue downloading this file")
                                    }
                                    delay(waitMs)
                                    waitMs = (waitMs * 2L).coerceAtMost(600L)
                                }
                            }
                        }
                    }
                }
            }
            instance.start(wait = false)
            val actualPort = instance.engine.resolvedConnectors().firstOrNull()?.port
                ?: throw IllegalStateException("Telegram stream proxy did not bind to a local port.")
            server = instance
            port = actualPort
            streamToken = selectedToken
            Timber.i("Telegram stream proxy listening on 127.0.0.1:%d", actualPort)
        }
    }

    suspend fun streamUrl(fileId: Int, size: Long, mimeType: String): String {
        ensureStarted()
        val safeMime = mimeType.takeIf { it.matches(Regex("^[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+$")) }
            ?: "audio/mpeg"
        val safeSize = size.takeIf { it in 1..MAX_STREAM_BYTES } ?: 0L
        check(streamToken.isNotBlank()) { "Telegram stream proxy access token is unavailable." }
        return "http://127.0.0.1:$port/stream/$fileId/$streamToken?size=$safeSize&mime=$safeMime"
    }



    private suspend fun refreshFileInfo(fileId: Int, previous: TdApi.File): TdApi.File =
        try {
            clientManager.sendRequest<TdApi.File>(TdApi.GetFile(fileId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.d(error, "Could not refresh Telegram file %s status yet", fileId)
            previous
        }

}
