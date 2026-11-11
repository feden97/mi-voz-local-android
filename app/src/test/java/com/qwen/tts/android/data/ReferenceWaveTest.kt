package com.qwen.tts.android.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceWaveTest {
    @Test fun downsamplingPreservesSpeechBandAndSuppressesAliasing() {
        fun tone(hz: Int) = FloatArray(24_000) { sin(2 * PI * hz * it / 24_000).toFloat() }
        fun rms(value: FloatArray): Double = sqrt(value.drop(64).dropLast(64).sumOf { it.toDouble() * it } / (value.size - 128))
        val speech = ReferenceWave.resample(tone(1_000), 24_000)
        val ultrasonic = ReferenceWave.resample(tone(10_000), 24_000)
        assertEquals(16_000, speech.size)
        assertTrue("Speech-band energy must survive", rms(speech) > 0.65)
        assertTrue("10 kHz must not alias into 6 kHz", rms(ultrasonic) < 0.03)
    }

    @Test fun dcIsPreservedIncludingAtClipEdges() {
        val output = ReferenceWave.resample(FloatArray(24_000) { 0.25f }, 24_000)
        output.forEach { assertEquals(0.25f, it, 0.000001f) }
    }

    @Test fun readsStereoPcmAndSkipsPaddedMetadata() {
        val audio = ByteBuffer.allocate(2_400 * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2_400) { audio.putShort(16_384); audio.putShort(-16_384) }
        withWave(audio.array(), channels = 2, bits = 16, format = 1, junk = true) { file ->
            val output = ReferenceWave.read16k(file)
            assertEquals(1_600, output.size)
            output.forEach { assertEquals(0f, it, 0f) }
        }
    }

    @Test fun rejectsNonFiniteFloatSamplesAndTruncatedData() {
        val audio = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array()
        withWave(audio, channels = 1, bits = 32, format = 3) { file ->
            assertThrows(IllegalArgumentException::class.java) { ReferenceWave.read16k(file) }
        }
        withWave(ByteArray(480), channels = 1, bits = 16, format = 1) { file ->
            val data = file.readBytes()
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 1_000_000)
            file.writeBytes(data)
            assertThrows(IllegalArgumentException::class.java) { ReferenceWave.read16k(file) }
        }
    }

    private fun withWave(
        audio: ByteArray, channels: Int, bits: Int, format: Int, junk: Boolean = false, action: (File) -> Unit,
    ) {
        val extra = if (junk) 10 else 0
        val wave = ByteBuffer.allocate(44 + extra + audio.size).order(ByteOrder.LITTLE_ENDIAN)
        fun id(value: String) { wave.put(value.toByteArray(Charsets.US_ASCII)) }
        id("RIFF"); wave.putInt(wave.capacity() - 8); id("WAVE")
        if (junk) { id("JUNK"); wave.putInt(1); wave.put(42); wave.put(0) }
        id("fmt "); wave.putInt(16); wave.putShort(format.toShort()); wave.putShort(channels.toShort())
        wave.putInt(24_000); wave.putInt(24_000 * channels * bits / 8)
        wave.putShort((channels * bits / 8).toShort()); wave.putShort(bits.toShort())
        id("data"); wave.putInt(audio.size); wave.put(audio)
        val file = File.createTempFile("reference-wave-", ".wav")
        try { file.writeBytes(wave.array()); action(file) } finally { file.delete() }
    }
}
