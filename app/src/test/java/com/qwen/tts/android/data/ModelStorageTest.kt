package com.qwen.tts.android.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun put(root: File, variant: ModelVariant, name: String): File =
        File(File(root, variant.storageGroup).apply { mkdirs() }, name).apply { writeText("test weights") }

    @Test fun removingOneSizePreservesSharedCodecUntilLastTalkerIsRemoved() {
        val root = temporary.newFolder()
        val small = QwenModel.variantById("q4_k_m")
        val large = QwenModel.variantById("1_7b_q4_k_m")
        val storage = ModelStorage(root, QwenModel.variants)
        val smallTalker = put(root, small, small.talker.name)
        val largeTalker = put(root, large, large.talker.name)
        val codec = put(root, small, small.tokenizer.name)
        val invalidated = mutableListOf<File>()

        storage.remove(small, invalidated::add)
        assertFalse(smallTalker.exists())
        assertTrue(largeTalker.exists())
        assertTrue(codec.exists())
        assertTrue(storage.removalPlan(small).isEmpty())
        assertEquals(setOf(largeTalker, codec), storage.removalPlan(large).toSet())
        assertEquals(listOf(smallTalker), invalidated)

        storage.remove(large)
        assertFalse(codec.exists())
        assertTrue(storage.removalPlan(large).isEmpty())
        assertFalse(File(root, small.storageGroup).exists())
    }

    @Test fun unfinishedOtherTalkerAlsoKeepsSharedCodec() {
        val root = temporary.newFolder()
        val small = QwenModel.variantById("0_6b_bf16")
        val large = QwenModel.variantById("1_7b_bf16")
        val storage = ModelStorage(root, QwenModel.variants)
        val smallTalker = put(root, small, small.talker.name)
        val codec = put(root, small, small.tokenizer.name)
        val partial = put(root, large, "${large.talker.name}.download")
        val usage = mapOf(
            smallTalker to ModelStorage.DiskUsage(1, 1, 1, 8192),
            codec to ModelStorage.DiskUsage(1, 2, 1, 4096),
            partial to ModelStorage.DiskUsage(1, 3, 1, 512),
        )
        assertEquals(mapOf(small.id to 8192L, large.id to 512L), storage.reclaimableBytesByVariant(usage::get))
        storage.remove(small)
        assertTrue(codec.exists())
        assertTrue(partial.exists())
        assertEquals(mapOf(large.id to 4608L), storage.reclaimableBytesByVariant(usage::get))
        storage.remove(large)
        assertFalse(partial.exists())
        assertFalse(codec.exists())
    }

    @Test fun orphanCodecAndPartialDownloadCanBeRemovedWithoutDeletingUnknownFiles() {
        val root = temporary.newFolder()
        val variant = QwenModel.variantById("1_7b_q4_q8_codec")
        val storage = ModelStorage(root, QwenModel.variants)
        val codec = put(root, variant, variant.tokenizer.name)
        val partial = put(root, variant, "${variant.tokenizer.name}.download")
        val unknown = put(root, variant, "unrelated.txt")
        assertEquals(setOf(codec, partial), storage.removalPlan(variant).toSet())
        storage.remove(variant)
        assertFalse(codec.exists())
        assertFalse(partial.exists())
        assertTrue(unknown.exists())
        assertTrue(storage.removalPlan(variant).isEmpty())
    }

    @Test fun unlinkingReusedWeightsPreservesOtherVariantAndVoiceHistoryDirectories() {
        val root = temporary.newFolder()
        val original = QwenModel.variantById("1_7b_bf16")
        val hybrid = QwenModel.variantById("1_7b_q4_bf16_codec")
        val codec = put(root, original, original.tokenizer.name)
        val reused = File(File(root, hybrid.storageGroup).apply { mkdirs() }, hybrid.tokenizer.name)
        Files.createLink(reused.toPath(), codec.toPath())
        val hybridTalker = put(root, hybrid, hybrid.talker.name)
        val voice = File(temporary.root, "voices/reference.wav").apply { requireNotNull(parentFile).mkdirs(); writeText("voice") }
        val audio = File(temporary.root, "generations/output.wav").apply { requireNotNull(parentFile).mkdirs(); writeText("audio") }

        val storage = ModelStorage(root, QwenModel.variants)
        val shared = ModelStorage.DiskUsage(1, 1, 2, 4096)
        val usage = mapOf(codec to shared, reused to shared, hybridTalker to ModelStorage.DiskUsage(1, 2, 1, 8192))
        assertEquals(8192L, storage.reclaimableBytesByVariant(usage::get)[hybrid.id])
        assertEquals(0L, storage.reclaimableBytesByVariant(usage::get)[original.id])
        storage.remove(hybrid)
        assertFalse(reused.exists())
        assertEquals("test weights", codec.readText())
        assertEquals("voice", voice.readText())
        assertEquals("audio", audio.readText())
    }

    @Test fun malformedCatalogPathsAreRejectedBeforeDeleting() {
        val root = temporary.newFolder()
        val variant = QwenModel.defaultVariant
        val outside = temporary.newFile("outside.gguf").apply { writeText("keep") }
        val storage = ModelStorage(root, emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            storage.remove(variant.copy(storageGroup = ".."))
        }
        assertThrows(IllegalArgumentException::class.java) {
            storage.remove(variant.copy(talker = variant.talker.copy(name = "../../outside.gguf")))
        }
        assertEquals("keep", outside.readText())
    }
}
