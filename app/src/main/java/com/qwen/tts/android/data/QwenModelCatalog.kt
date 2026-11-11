package com.qwen.tts.android.data

internal data class ModelFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)

internal data class ModelVariant(
    val id: String,
    val displayName: String,
    val storageGroup: String,
    val quantized: Boolean,
    val talker: ModelFile,
    val tokenizer: ModelFile,
) {
    val files: List<ModelFile> = listOf(tokenizer, talker)
    val talkerName: String = talker.name
    val totalBytes: Long = files.sumOf { it.sizeBytes }
}

/** GGUF files for the pinned qwen3-tts.cpp runtime, not the incompatible llama.cpp GGUF format. */
internal object QwenModel {
    const val REVISION = "968442208ea86f312b6b67ac8ef0c1b551967e35"
    private const val BASE_URL = "https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/$REVISION"

    private fun file(name: String, size: Long, hash: String) =
        ModelFile(name, "$BASE_URL/$name?download=true", size, hash)

    private val tokenizerQ4 = file(
        "qwen-tokenizer-12hz-Q4_K_M.gguf", 254_974_752L,
        "cf3788b4d50aaa665fb6e57c170396aae03a3555fea52d2b5d0cda902d658039",
    )
    private val tokenizerBf16 = file(
        "qwen-tokenizer-12hz-BF16.gguf", 358_980_384L,
        "cc04d7352ff4cb339435cdccf59cf38e6e92f3cf789bc94a6a1b467093bafa18",
    )

    val defaultVariant = ModelVariant(
        id = "q4_k_m", displayName = "0.6B Base · Q4_K_M", storageGroup = "q4_k_m", quantized = true,
        talker = file("qwen-talker-0.6b-base-Q4_K_M.gguf", 628_905_056L,
            "4b468ec7b1f62b90ef4ca316c0aa57deadfd54b2cf9651703ea753cedaf04226"),
        tokenizer = tokenizerQ4,
    )

    val variants = listOf(
        defaultVariant,
        ModelVariant(
            id = "0_6b_bf16", displayName = "0.6B Base · BF16 sin cuantizar", storageGroup = "bf16", quantized = false,
            talker = file("qwen-talker-0.6b-base-BF16.gguf", 1_841_716_288L,
                "f0070d96aa4e1a437e2963cc2925b0c9b1e307e334faa36c2120f39fc0b729eb"),
            tokenizer = tokenizerBf16,
        ),
        ModelVariant(
            id = "1_7b_q4_k_m", displayName = "1.7B Base · Q4_K_M", storageGroup = "q4_k_m", quantized = true,
            talker = file("qwen-talker-1.7b-base-Q4_K_M.gguf", 1_219_245_248L,
                "ea393ebaf2167ea23ce9fc18b093822851358a950d7075cd47ab4f6ce23e887d"),
            tokenizer = tokenizerQ4,
        ),
        ModelVariant(
            id = "1_7b_bf16", displayName = "1.7B Base · BF16 sin cuantizar", storageGroup = "bf16", quantized = false,
            talker = file("qwen-talker-1.7b-base-BF16.gguf", 3_876_199_616L,
                "7f4567113f3aa07e842fac6d17a10a98cf3d0068c0871ac245ac9befc63affa5"),
            tokenizer = tokenizerBf16,
        ),
    )

    fun variantById(id: String): ModelVariant =
        variants.firstOrNull { it.id == id } ?: defaultVariant
}
