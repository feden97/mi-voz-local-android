package com.qwen.tts.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenModelCatalogTest {
    @Test
    fun eachNativeSearchDirectoryContainsExactlyOneTokenizerPrecision() {
        // The C++ runtime prefers BF16 over Q4 regardless of the selected talker.
        // Mixing both codecs into one folder would silently change the Q4 workload.
        QwenModel.variants.groupBy { it.storageGroup }.values.forEach { group ->
            assertEquals(1, group.map { it.tokenizer }.distinct().size)
            assertEquals(1, group.map { it.quantized }.distinct().size)
        }
        assertEquals(3, QwenModel.variants.map { it.storageGroup }.distinct().size)
        val hybrid = QwenModel.variantById("1_7b_q4_bf16_codec")
        assertEquals(QwenModel.variantById("1_7b_q4_k_m").talker, hybrid.talker)
        assertEquals(QwenModel.variantById("1_7b_bf16").tokenizer, hybrid.tokenizer)
        assertTrue(hybrid.storageGroup != QwenModel.variantById("1_7b_q4_k_m").storageGroup)
        assertTrue(hybrid.storageGroup != QwenModel.variantById("1_7b_bf16").storageGroup)
    }

    @Test
    fun downloadsStayOnThePinnedRuntimeFormatAndHaveIndependentHashes() {
        val files = QwenModel.variants.flatMap { it.files }.distinct()
        assertEquals(6, files.size) // Four talkers, two shared codecs.
        assertEquals(files.size, files.map { it.sha256 }.distinct().size)
        files.forEach { file ->
            assertTrue(file.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(file.url.contains("/resolve/${QwenModel.REVISION}/${file.name}"))
            assertTrue(file.sizeBytes > 0)
        }
    }

    @Test
    fun downloadSizesUseLongsAndIncludeTheCodec() {
        assertEquals(883_879_808L, QwenModel.defaultVariant.totalBytes)
        assertEquals(1_474_220_000L, QwenModel.variantById("1_7b_q4_k_m").totalBytes)
        assertEquals(2_200_696_672L, QwenModel.variantById("0_6b_bf16").totalBytes)
        assertEquals(4_235_180_000L, QwenModel.variantById("1_7b_bf16").totalBytes)
        assertEquals(1_578_225_632L, QwenModel.variantById("1_7b_q4_bf16_codec").totalBytes)
    }
}
