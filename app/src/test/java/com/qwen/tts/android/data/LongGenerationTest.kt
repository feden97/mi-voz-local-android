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
        assertTrue(chunks.all { TextChunks.count(it) <= TextChunks.CHUNK_CHARACTERS && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }
    @Test fun shortScriptsKeepOneNativeUtterance() {
        for (length in listOf(272, 350)) {
            val text = "Una frase corta para conservar la entonación. ".repeat(10).take(length)
            assertEquals(listOf(text), TextChunks.split(text))
        }
    }
    @Test fun balancesLastPairInsteadOfCreatingTinyTailAtLateSentence() {
        val text = "palabra ".repeat(41) + ". " + "corto ".repeat(7)
        val chunks = TextChunks.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { TextChunks.count(it) in 100..TextChunks.CHUNK_CHARACTERS })
    }
    @Test fun prefersParagraphAndSentenceBoundariesWhenTheyFit() {
        val paragraph = "Inicio ".repeat(20).trimEnd() + ".\n\n"
        val paragraphs = TextChunks.split(paragraph.repeat(3))
        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs.first().endsWith("\n\n"))
        assertEquals(paragraph.repeat(3), paragraphs.joinToString(""))

        val sentence = "primera ".repeat(20).trimEnd() + ". "
        val sentences = TextChunks.split(sentence.repeat(3))
        assertEquals(2, sentences.size)
        assertTrue(sentences.first().endsWith(". "))
        assertEquals(sentence.repeat(3), sentences.joinToString(""))
    }
    @Test fun limitsUseCodepointsAndRejectUnbrokenLongTokens() {
        assertEquals(1, TextChunks.count("😀"))
        assertThrows(IllegalArgumentException::class.java) { TextChunks.split("a".repeat(351) + " fin") }
        assertEquals("a".repeat(350) + " fin", TextChunks.split("a".repeat(350) + " fin").joinToString(""))
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
