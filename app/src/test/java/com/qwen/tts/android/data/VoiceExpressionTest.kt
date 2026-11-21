package com.qwen.tts.android.data

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceExpressionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun processedPhraseChangesPitchAndDurationAndLeavesOtherSamplesIntact() {
        val rate = 24_000
        val source = File(temp.root, "source.wav")
        val preview = File(temp.root, "preview.wav")
        val copy = File(temp.root, "copy.wav")
        val range = WaveRange(rate / 2L, 3L * rate / 2)
        val expression = VoiceExpression.JOYFUL
        StreamingWavWriter(source, rate).use { writer ->
            writer.append(FloatArray(2 * rate) { (0.4 * sin(2 * PI * 220 * it / rate)).toFloat() }, rate)
        }

        ExpressionAudio.render(source, preview, range, expression)
        val previewLayout = EditableWave.inspect(preview)
        val samples = pcm(preview)
        val start = rate / 10
        val end = samples.size - start
        val crossings = (start until end).count { samples[it - 1] < 0 && samples[it] >= 0 }
        val pitch = crossings.toDouble() * rate / (end - start)
        assertEquals(rate, previewLayout.sampleRate)
        assertEquals(rate / expression.speed.toDouble(), previewLayout.frames.toDouble(), rate * 0.025)
        assertEquals(220 * expression.pitch.toDouble(), pitch, 8.0)
        assertTrue(samples.max() > pcm(source).max())

        val original = pcm(source)
        EditableWave.saveCopy(source, copy, range, WaveEdit.REPLACE, preview)
        val saved = pcm(copy)
        assertArrayEquals(original.copyOfRange(0, range.start.toInt()), saved.copyOfRange(0, range.start.toInt()))
        assertArrayEquals(samples, saved.copyOfRange(range.start.toInt(), range.start.toInt() + samples.size))
        assertArrayEquals(original.copyOfRange(range.end.toInt(), original.size), saved.takeLast(rate / 2).toIntArray())
        assertArrayEquals(original, pcm(source))
    }

    private fun pcm(path: File): IntArray {
        val layout = EditableWave.inspect(path)
        return RandomAccessFile(path, "r").use { input ->
            input.seek(layout.dataOffset)
            IntArray(layout.frames.toInt()) { java.lang.Short.reverseBytes(input.readShort()).toInt() }
        }
    }
}
