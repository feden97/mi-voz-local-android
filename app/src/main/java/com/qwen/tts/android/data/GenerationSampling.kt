package com.qwen.tts.android.data

import java.security.SecureRandom

/** Resolve one effective seed before work starts, then keep it in the diagnostic report. */
internal data class GenerationSampling(
    val temperature: Float = 0.9f,
    val fixedSeed: Int? = null,
) {
    init {
        require(temperature.isFinite() && temperature in 0.1f..1.5f) {
            "La temperatura debe estar entre 0,1 y 1,5."
        }
        require(fixedSeed == null || fixedSeed > 0) { "La semilla debe ser un entero positivo." }
    }

    fun resolve(): ResolvedGenerationSampling = ResolvedGenerationSampling(
        temperature = temperature,
        seed = fixedSeed ?: (random.nextInt(Int.MAX_VALUE) + 1),
        seedMode = if (fixedSeed == null) "random_per_job" else "fixed",
    )

    companion object {
        private val random = SecureRandom()
    }
}

internal data class ResolvedGenerationSampling(
    val temperature: Float,
    val seed: Int,
    val seedMode: String,
) {
    /** Zero-based chunk index. The first chunk uses the reported job seed unchanged. */
    fun seedForChunk(index: Int): Int {
        require(index >= 0) { "El índice del fragmento no puede ser negativo." }
        if (index == 0) return seed
        // SplitMix64 provides a stable derivation without correlated adjacent PRNG seeds.
        var mixed = seed.toLong() + index.toLong() * -7046029254386353131L
        mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
        mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
        mixed = mixed xor (mixed ushr 31)
        return (mixed and Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
    }
}
