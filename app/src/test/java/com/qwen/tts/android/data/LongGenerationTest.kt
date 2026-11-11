package com.qwen.tts.android.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LongGenerationTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun splitsLongTextWithoutLosingCharactersOrSurrogates() {
        val text = "¡Hola! 👩🏽‍💻 ¿Vos venís mañana? Esta es otra frase. ".repeat(150)
        val chunks = TextChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { TextChunks.count(it) <= 220 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }
    @Test fun limitsUseCodepointsAndRejectUnbrokenLongTokens() {
        assertEquals(1, TextChunks.count("😀"))
        assertThrows(IllegalArgumentException::class.java) { TextChunks.split("a".repeat(221) + " fin") }
        assertThrows(IllegalArgumentException::class.java) { TextChunks.split("x ".repeat(10_001)) }
        assertEquals("x ".repeat(10_000), TextChunks.split("x ".repeat(10_000)).joinToString(""))
    }
    @Test fun wavAppendHasCorrectDurationPauseAndRecoverablePrefix() {
        val wav = File(temp.root, "test.wav")
        StreamingWavWriter(wav).use { output ->
            output.append(FloatArray(24_000) { 0.25f }, 24_000)
            val first = ByteBuffer.wrap(wav.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(48_000, first.getInt(40))
            output.append(FloatArray(24_000) { -0.25f }, 24_000)
            assertEquals(51_600L, output.sampleCount)
        }
        val bytes = wav.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(bytes.size - 44, buffer.getInt(40))
        assertEquals(bytes.size - 8, buffer.getInt(4))
        assertEquals(0, buffer.getShort(44 + 48_000).toInt())
    }
}
