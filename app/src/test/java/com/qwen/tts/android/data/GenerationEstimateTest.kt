package com.qwen.tts.android.data

import org.junit.Assert.*
import org.junit.Test

class GenerationEstimateTest {
    @Test fun waitsForRealProgress() {
        assertNull(GenerationEstimate.remaining(200, 0, 10_000, 0, emptyList(), emptyList()))
        assertNull(GenerationEstimate.remaining(200, 4, 1_000, 3, emptyList(), emptyList()))
    }
    @Test fun slowerObservedFramesIncreaseForecast() {
        val fast = GenerationEstimate.remaining(200, 12, 12_000, 10, emptyList(), emptyList())!!
        val slow = GenerationEstimate.remaining(200, 12, 24_000, 10, emptyList(), emptyList())!!
        assertTrue(slow.highMillis > fast.highMillis)
        assertTrue(fast.highMillis >= fast.lowMillis)
    }
    @Test fun calibratedTailIsIncludedAndBadSamplesIgnored() {
        val speech = GenerationEstimate.remaining(160, 20, 10_000, 10, listOf(16.0, Double.NaN), emptyList())!!
        val total = GenerationEstimate.remaining(160, 20, 10_000, 10, listOf(16.0), listOf(20_000))!!
        assertEquals(30_000L, total.highMillis - speech.highMillis)
        assertEquals(15_000L, total.lowMillis - speech.lowMillis)
    }
    @Test fun exceedingPredictedLengthDoesNotPromiseCompletion() {
        val result = GenerationEstimate.remaining(10, 300, 10_000, 10, emptyList(), emptyList())!!
        assertEquals(0L, result.lowMillis)
        assertTrue(result.highMillis > 0)
    }
}
