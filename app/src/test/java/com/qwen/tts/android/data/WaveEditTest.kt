package com.qwen.tts.android.data

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WaveEditTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun editsCopyExactSamplesAndKeepTheSource() {
        val source = wave("source.wav", 100, 200, 300, 400, 500)
        val take = wave("take.wav", -1, -2)
        val selection = WaveRange(1, 4)
        val inputs = listOf(
            Triple(WaveEdit.KEEP, selection, intArrayOf(200, 300, 400)),
            Triple(WaveEdit.REMOVE, selection, intArrayOf(100, 500)),
            Triple(WaveEdit.REPLACE, selection, intArrayOf(100, -1, -2, 500)),
            Triple(WaveEdit.INSERT, WaveRange(5, 5), intArrayOf(100, 200, 300, 400, 500, -1, -2)),
        )
        for ((action, range, expected) in inputs) {
            val output = File(temp.root, "${action.name}.wav")
            val replacement = take.takeIf { action == WaveEdit.REPLACE || action == WaveEdit.INSERT }
            val layout = EditableWave.saveCopy(source, output, range, action, replacement)
            assertEquals(expected.size.toLong(), layout.frames)
            assertEquals(24_000, layout.sampleRate)
            assertArrayEquals(expected, pcm(output))
        }
        assertArrayEquals(intArrayOf(100, 200, 300, 400, 500), pcm(source))
    }

    @Test fun usesActualAudioDurationAndReadsBothSignedPeaks() {
        val source = wave("peaks.wav", 0, -32768, 16384, 0)
        val layout = EditableWave.inspect(source)
        assertEquals(1L, layout.durationMillis)
        assertEquals(4L, layout.frameAt(layout.durationMillis))
        assertArrayEquals(floatArrayOf(1f, 0.5f), EditableWave.peaks(source, layout, 2), 0f)
    }

    @Test fun copiesRangesAcrossPcmBufferBoundaries() {
        val samples = IntArray(25_000) { it - 12_500 }
        val source = wave("long.wav", *samples)
        val output = File(temp.root, "long-copy.wav")
        EditableWave.saveCopy(source, output, WaveRange(113, 24_000), WaveEdit.KEEP)
        assertArrayEquals(samples.copyOfRange(113, 24_000), pcm(output))
    }

    @Test fun failedEditsLeaveNoCopyAndDoNotOverwriteAnExistingFile() {
        val source = wave("source.wav", 100, 200, 300)
        val invalidTake = wave("other-rate.wav", 10, rate = 16_000)
        val output = File(temp.root, "copy.wav")
        assertThrows(IllegalArgumentException::class.java) {
            EditableWave.saveCopy(source, output, WaveRange(0, 3), WaveEdit.REMOVE)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EditableWave.saveCopy(source, output, WaveRange(1, 2), WaveEdit.REPLACE, invalidTake)
        }
        assertFalse(output.exists())
        val existing = wave("existing.wav", 77)
        assertThrows(IllegalArgumentException::class.java) {
            EditableWave.saveCopy(source, existing, WaveRange(1, 2), WaveEdit.KEEP)
        }
        assertArrayEquals(intArrayOf(77), pcm(existing))
    }

    private fun wave(name: String, vararg samples: Int, rate: Int = 24_000): File = File(temp.root, name).also { path ->
        StreamingWavWriter(path, rate).use { writer ->
            writer.append(FloatArray(samples.size) { samples[it] / 32768f }, rate)
        }
    }

    private fun pcm(path: File): IntArray {
        val layout = EditableWave.inspect(path)
        return RandomAccessFile(path, "r").use { input ->
            input.seek(layout.dataOffset)
            IntArray(layout.frames.toInt()) { java.lang.Short.reverseBytes(input.readShort()).toInt() }
        }
    }
}
