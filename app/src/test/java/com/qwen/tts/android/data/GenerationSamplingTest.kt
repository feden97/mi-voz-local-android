package com.qwen.tts.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSamplingTest {
    @Test
    fun fixedJobSeedRemainsRepeatableAcrossChunks() {
        val first = GenerationSampling(0.85f, 42).resolve()
        val second = GenerationSampling(0.85f, 42).resolve()
        assertEquals("fixed", first.seedMode)
        assertEquals(42, first.seedForChunk(0))
        assertEquals((0..32).map(first::seedForChunk), (0..32).map(second::seedForChunk))
        assertEquals(33, (0..32).map(first::seedForChunk).distinct().size)
        assertTrue((0..32).all { first.seedForChunk(it) > 0 })
    }

    @Test
    fun randomJobExposesOneEffectivePositiveSeed() {
        repeat(32) {
            val resolved = GenerationSampling().resolve()
            assertEquals("random_per_job", resolved.seedMode)
            assertTrue(resolved.seed > 0)
            assertEquals(resolved.seed, resolved.seedForChunk(0))
            assertEquals(resolved.seedForChunk(7), resolved.seedForChunk(7))
        }
    }

    @Test
    fun rejectsValuesThatCouldSilentlyChangeOrBreakNativeSampling() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, 0.099f, 1.501f).forEach { value ->
            assertRejected { GenerationSampling(value) }
        }
        listOf(Int.MIN_VALUE, -1, 0).forEach { value -> assertRejected { GenerationSampling(fixedSeed = value) } }
        assertRejected { GenerationSampling().resolve().seedForChunk(-1) }
        GenerationSampling(0.1f, 1)
        GenerationSampling(1.5f, Int.MAX_VALUE)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }
}
