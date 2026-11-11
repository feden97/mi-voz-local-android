package com.qwen.tts.android.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun refusesRootAndSiblingWithSamePrefix() {
        val root = temporary.newFolder("voices")
        val sibling = temporary.newFolder("voices-other")
        val outside = File(sibling, "reference.wav").apply { writeText("keep") }
        assertThrows(IllegalArgumentException::class.java) { PrivateFiles.deleteTree(root, root) }
        assertThrows(IllegalArgumentException::class.java) { PrivateFiles.deleteFile(root, outside) }
        assertThrows(IllegalArgumentException::class.java) {
            PrivateFiles.deleteFile(root, File(root, "../voices-other/reference.wav"))
        }
        assertEquals("keep", outside.readText())
    }

    @Test fun removesOnlyChosenVoiceIncludingNestedPromptFiles() {
        val root = temporary.newFolder()
        val chosen = File(root, "chosen")
        File(chosen, "prompts/icl.bin").apply { requireNotNull(parentFile).mkdirs(); writeText("prompt") }
        File(chosen, "reference.wav").writeText("reference")
        val kept = File(root, "other/reference.wav").apply { requireNotNull(parentFile).mkdirs(); writeText("keep") }
        PrivateFiles.deleteTree(root, chosen)
        assertFalse(chosen.exists())
        assertTrue(root.exists())
        assertEquals("keep", kept.readText())
        PrivateFiles.deleteTree(root, chosen) // Already absent is safe to retry.
    }

    @Test fun deletesOnlyRequestedAudioAndAllowsAlreadyAbsentFile() {
        val root = temporary.newFolder()
        val audio = File(root, "chosen.wav").apply { writeText("delete") }
        val kept = File(root, "other.wav").apply { writeText("keep") }
        PrivateFiles.deleteFile(root, audio)
        PrivateFiles.deleteFile(root, audio)
        assertFalse(audio.exists())
        assertEquals("keep", kept.readText())
        assertThrows(IllegalArgumentException::class.java) { PrivateFiles.deleteFile(root, temporary.newFolder()) }
    }
}
