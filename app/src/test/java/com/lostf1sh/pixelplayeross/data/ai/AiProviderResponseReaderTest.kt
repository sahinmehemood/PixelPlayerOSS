package com.lostf1sh.pixelplayeross.data.ai

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiProviderResponseReaderTest {
    @Test
    fun readsEmptyResponse() {
        assertEquals("", AiProviderResponseReader.read(ByteArrayInputStream(byteArrayOf())))
    }

    @Test
    fun acceptsResponseExactlyAtByteLimit() {
        val bytes = ByteArray(AiProviderResponseReader.MAX_RESPONSE_BYTES) { 'x'.code.toByte() }

        assertEquals(
            "x".repeat(AiProviderResponseReader.MAX_RESPONSE_BYTES),
            AiProviderResponseReader.read(ByteArrayInputStream(bytes)),
        )
    }

    @Test
    fun rejectsOversizedResponseEvenWhenLengthIsUnknown() {
        val bytes = ByteArray(AiProviderResponseReader.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() }

        val error = assertThrows(IllegalStateException::class.java) {
            AiProviderResponseReader.read(ByteArrayInputStream(bytes))
        }

        assertEquals("AI provider response exceeded the 1 MB safety limit.", error.message)
    }

    @Test
    fun enforcesLimitUsingUtf8BytesRatherThanCharacterCount() {
        val text = "é".repeat((AiProviderResponseReader.MAX_RESPONSE_BYTES / 2) + 1)

        assertThrows(IllegalStateException::class.java) {
            AiProviderResponseReader.read(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun handlesStreamsThatReturnZeroWithoutSpinning() {
        val input = object : InputStream() {
            private var zeroReturned = false
            private val payload = "ok".toByteArray(Charsets.UTF_8)
            private var position = 0

            override fun read(): Int =
                if (position >= payload.size) -1 else payload[position++].toInt() and 0xff

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!zeroReturned) {
                    zeroReturned = true
                    return 0
                }
                return super.read(buffer, offset, length)
            }
        }

        assertEquals("ok", AiProviderResponseReader.read(input))
    }
}
