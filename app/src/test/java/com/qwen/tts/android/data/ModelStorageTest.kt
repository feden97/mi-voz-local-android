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
        assertFalse(storage.hasFiles(small))
        assertTrue(storage.hasFiles(large))
        assertEquals(listOf(smallTalker), invalidated)

        storage.remove(large)
        assertFalse(codec.exists())
        assertFalse(storage.hasFiles(large))
        assertFalse(File(root, small.storageGroup).exists())
    }

    @Test fun unfinishedOtherTalkerAlsoKeepsSharedCodec() {
        val root = temporary.newFolder()
        val small = QwenModel.variantById("0_6b_bf16")
        val large = QwenModel.variantById("1_7b_bf16")
        val storage = ModelStorage(root, QwenModel.variants)
        put(root, small, small.talker.name)
        val codec = put(root, small, small.tokenizer.name)
        val partial = put(root, large, "${large.talker.name}.download")
        storage.remove(small)
        assertTrue(codec.exists())
        assertTrue(partial.exists())
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
        assertTrue(storage.hasFiles(variant))
        storage.remove(variant)
        assertFalse(codec.exists())
        assertFalse(partial.exists())
        assertTrue(unknown.exists())
        assertFalse(storage.hasFiles(variant))
    }

    @Test fun unlinkingReusedWeightsPreservesOtherVariantAndVoiceHistoryDirectories() {
        val root = temporary.newFolder()
        val original = QwenModel.variantById("1_7b_bf16")
        val hybrid = QwenModel.variantById("1_7b_q4_bf16_codec")
        val codec = put(root, original, original.tokenizer.name)
        val reused = File(File(root, hybrid.storageGroup).apply { mkdirs() }, hybrid.tokenizer.name)
        Files.createLink(reused.toPath(), codec.toPath())
        put(root, hybrid, hybrid.talker.name)
        val voice = File(temporary.root, "voices/reference.wav").apply { requireNotNull(parentFile).mkdirs(); writeText("voice") }
        val audio = File(temporary.root, "generations/output.wav").apply { requireNotNull(parentFile).mkdirs(); writeText("audio") }

        ModelStorage(root, QwenModel.variants).remove(hybrid)
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
