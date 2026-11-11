package com.qwen.tts.android.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoicePromptFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun transcriptRoundTripsAccentsAndKeepsInternalPunctuation() {
        val wav = File(temp.newFolder(), "reference.wav").apply { writeBytes(byteArrayOf()) }
        VoicePromptFiles.saveTranscript(wav, "  ¡Qué bueno, che!\r\n¿Vos venís mañana?  ")
        assertEquals("¡Qué bueno, che!\n¿Vos venís mañana?", VoicePromptFiles.readTranscript(wav))
    }

    @Test
    fun cacheKeyChangesWithTranscriptAndModel() {
        val wav = File(temp.newFolder(), "reference.wav")
        val first = VoicePromptFiles.promptFile(wav, "q4_k_m", "Hola, ¿cómo estás?")
        assertEquals(first, VoicePromptFiles.promptFile(wav, "q4_k_m", " Hola, ¿cómo estás? "))
        assertNotEquals(first, VoicePromptFiles.promptFile(wav, "1_7b_q4_k_m", "Hola, ¿cómo estás?"))
        assertNotEquals(first, VoicePromptFiles.promptFile(wav, "q4_k_m", "Hola. ¿Cómo estás?"))
    }

    @Test
    fun editingOrRemovingTranscriptInvalidatesPromptsButKeepsOtherVoiceFiles() {
        val directory = temp.newFolder()
        val wav = File(directory, "reference.wav").apply { writeText("reference") }
        val speaker = File(directory, "speaker-q4_k_m.json").apply { writeText("embedding") }
        VoicePromptFiles.saveTranscript(wav, "Hola")
        val stale = VoicePromptFiles.promptFile(wav, "q4_k_m", "Hola").apply { writeText("prompt") }
        VoicePromptFiles.saveTranscript(wav, "Chau")
        assertFalse(stale.exists())
        val second = VoicePromptFiles.promptFile(wav, "q4_k_m", "Chau").apply { writeText("prompt") }
        VoicePromptFiles.saveTranscript(wav, " ")
        assertEquals("", VoicePromptFiles.readTranscript(wav))
        assertFalse(second.exists())
        assertEquals("reference", wav.readText())
        assertEquals("embedding", speaker.readText())
    }

    @Test
    fun rejectsInvalidTextAndPathComponentsWithoutTouchingSavedTranscript() {
        val wav = File(temp.newFolder(), "reference.wav")
        VoicePromptFiles.saveTranscript(wav, "Texto válido")
        assertThrows(IllegalArgumentException::class.java) { VoicePromptFiles.saveTranscript(wav, "a".repeat(4_001)) }
        assertThrows(IllegalArgumentException::class.java) { VoicePromptFiles.saveTranscript(wav, "hola\u0000mundo") }
        assertThrows(IllegalArgumentException::class.java) { VoicePromptFiles.promptFile(wav, "../model", "Hola") }
        assertThrows(IllegalArgumentException::class.java) { VoicePromptFiles.promptFile(wav, "q4_k_m", " ") }
        assertEquals("Texto válido", VoicePromptFiles.readTranscript(wav))
    }
}
