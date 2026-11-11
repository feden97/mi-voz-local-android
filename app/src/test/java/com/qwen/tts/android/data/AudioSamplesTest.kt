package com.qwen.tts.android.data

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioSamplesTest {
    @Test
    fun mixesStereoToMonoAndPreservesThreeSecondDuration() {
        val frames = 48_000 * 3
        val pcm = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            pcm.putShort(24_576.toShort())
            pcm.putShort(8_192.toShort())
        }
        pcm.flip()
        val samples = AudioSamples(48_000, 2)
        samples.append(pcm, false)
        val converted = samples.toMono24k()
        assertEquals(72_000, converted.size)
        assertEquals(0.5f, converted[100], 0.00001f)
    }

    @Test
    fun interpolatesAcrossPcmBufferBoundaries() {
        val frames = 44_100 * 3
        val samples = AudioSamples(44_100, 1)
        val first = ByteBuffer.allocate(2 * 4).order(ByteOrder.LITTLE_ENDIAN)
        first.putFloat(0.1f).putFloat(0.2f).flip()
        samples.append(first, true)
        val rest = ByteBuffer.allocate((frames - 2) * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames - 2) { rest.putFloat(0.3f) }
        rest.flip()
        samples.append(rest, true)
        val converted = samples.toMono24k()
        assertEquals(72_000, converted.size)
        assertEquals(0.28375f, converted[1], 0.00001f)
    }

    @Test
    fun rejectsFramesBeyondThirtySecondsBeforeAllocatingThem() {
        val samples = AudioSamples(8_000, 1)
        val tooLong = ByteBuffer.allocate((8_000 * 30 + 1) * 2)
        assertThrows(IllegalArgumentException::class.java) { samples.append(tooLong, false) }
        assertEquals(0, samples.frameCount)
    }

    @Test
    fun rejectsShortSilentAndNonFiniteAudio() {
        val short = AudioSamples(24_000, 1)
        assertThrows(IllegalArgumentException::class.java) { short.toMono24k() }
        val silent = AudioSamples(24_000, 1)
        silent.append(ByteBuffer.allocate(72_000 * 2), false)
        assertThrows(IllegalArgumentException::class.java) { silent.toMono24k() }
        val invalid = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN)
        invalid.flip()
        assertThrows(IllegalArgumentException::class.java) { short.append(invalid, true) }
    }

    @Test
    fun writesPcm16Mono24kHeaderAndClipsWithoutWrapping() {
        val output = ByteArrayOutputStream()
        AudioSamples.writeWav(output, floatArrayOf(-2f, -1f, 0f, 1f, 2f))
        val bytes = output.toByteArray()
        val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(46, wav.getInt(4))
        assertEquals(1, wav.getShort(22).toInt())
        assertEquals(24_000, wav.getInt(24))
        assertEquals(16, wav.getShort(34).toInt())
        assertEquals(10, wav.getInt(40))
        assertEquals(-32768, wav.getShort(44).toInt())
        assertEquals(-32768, wav.getShort(46).toInt())
        assertEquals(0, wav.getShort(48).toInt())
        assertEquals(32767, wav.getShort(50).toInt())
        assertEquals(32767, wav.getShort(52).toInt())
    }
}
