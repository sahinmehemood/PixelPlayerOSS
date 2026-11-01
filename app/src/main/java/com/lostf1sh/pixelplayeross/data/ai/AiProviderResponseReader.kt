package com.lostf1sh.pixelplayeross.data.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads a provider response with a hard byte limit, including when the HTTP response does not
 * declare Content-Length. The limit is enforced before appending each chunk to memory.
 */
internal object AiProviderResponseReader {
    const val MAX_RESPONSE_BYTES = 1_000_000
    private const val BUFFER_SIZE = 8 * 1024
    private const val TOO_LARGE_MESSAGE = "AI provider response exceeded the 1 MB safety limit."

    fun read(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0

        while (true) {
            val count = input.read(buffer)
            if (count < 0) break

            // A custom InputStream may legally return zero for a non-empty buffer. Make progress
            // with a single-byte read rather than spinning indefinitely.
            if (count == 0) {
                val nextByte = input.read()
                if (nextByte < 0) break
                if (totalBytes >= MAX_RESPONSE_BYTES) {
                    throw IllegalStateException(TOO_LARGE_MESSAGE)
                }
                output.write(nextByte)
                totalBytes += 1
                continue
            }

            if (count > MAX_RESPONSE_BYTES - totalBytes) {
                throw IllegalStateException(TOO_LARGE_MESSAGE)
            }

            output.write(buffer, 0, count)
            totalBytes += count
        }

        return String(output.toByteArray(), Charsets.UTF_8)
    }
}
