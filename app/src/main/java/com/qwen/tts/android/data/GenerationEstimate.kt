package com.qwen.tts.android.data

import kotlin.math.ceil
import kotlin.math.max

internal data class RemainingEstimate(val lowMillis: Long, val highMillis: Long)

/** Acoustic-token limits are safety caps, never a forecast of the utterance length. */
internal object GenerationEstimate {
    const val FRAMES_PER_SECOND = 12.5

    fun remaining(
        textCharacters: Int,
        frames: Int,
        measuredMillis: Long,
        measuredFrames: Int,
        historicalCharactersPerSecond: List<Double>,
        historicalTailMillis: List<Long>,
    ): RemainingEstimate? {
        if (textCharacters <= 0 || frames < 4 || measuredFrames < 3 || measuredMillis < 2_000) return null
        val rates = historicalCharactersPerSecond.filter { it.isFinite() && it in 3.0..100.0 }
        // First-run range is deliberately broad; speaking rate and natural EOS are unknown.
        val slow = rates.minOrNull()?.times(0.75)?.coerceAtLeast(3.0) ?: 8.0
        val fast = rates.maxOrNull()?.times(1.25)?.coerceAtMost(100.0) ?: 25.0
        val millisPerFrame = measuredMillis.toDouble() / measuredFrames
        val lowerFrames = max(0.0, textCharacters / fast * FRAMES_PER_SECOND - frames)
        val upperFrames = max(0.0, textCharacters / slow * FRAMES_PER_SECOND - frames)
        val tails = historicalTailMillis.filter { it in 0L..3_600_000L }
        val lowerTail = tails.minOrNull()?.times(0.75) ?: 0.0
        val upperTail = tails.maxOrNull()?.times(1.5) ?: 0.0
        // If speech outlasts the forecast, extend the window instead of claiming zero remaining.
        val high = if (upperFrames == 0.0) millisPerFrame * FRAMES_PER_SECOND * 3 else upperFrames * millisPerFrame * 1.4
        return RemainingEstimate(
            ceil(lowerFrames * millisPerFrame * 0.7 + lowerTail).toLong().coerceAtLeast(0),
            ceil(high + upperTail).toLong().coerceAtLeast(1_000),
        )
    }
}
