package com.qwen.tts.android.data

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Private sidecars keep transcripts and model-specific ICL prompts alongside immutable reference WAVs. */
internal object VoicePromptFiles {
    const val MAX_TRANSCRIPT_CHARS = 4_000
    private const val MAX_TRANSCRIPT_BYTES = MAX_TRANSCRIPT_CHARS * 4L
    private const val PROMPT_PREFIX = "icl-v1-"

    fun readTranscript(referenceWav: File): String {
        val file = transcriptFile(referenceWav)
        if (!file.exists()) return ""
        require(file.isFile && file.length() <= MAX_TRANSCRIPT_BYTES) { "La transcripción guardada no es válida." }
        return normalize(file.readText(Charsets.UTF_8))
    }

    fun saveTranscript(referenceWav: File, value: String): String {
        val text = normalize(value)
        val file = transcriptFile(referenceWav)
        if (text.isBlank()) {
            check(!file.exists() || file.delete()) { "No se pudo quitar la transcripción." }
        } else {
            val pending = File(file.parentFile, ".reference-${UUID.randomUUID()}.txt.tmp")
            try {
                pending.writeText(text, Charsets.UTF_8)
                try {
                    Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                pending.delete()
            }
        }
        // Even when a transcript is removed, old prompts must not silently keep its previous text.
        referenceWav.parentFile?.listFiles()?.filter {
            it.isFile && it.name.startsWith(PROMPT_PREFIX) && it.name.endsWith(".json")
        }?.forEach { check(it.delete()) { "No se pudo invalidar un prompt anterior." } }
        return text
    }

    fun promptFile(referenceWav: File, variantId: String, transcript: String): File {
        require(variantId.matches(Regex("[A-Za-z0-9_]+"))) { "Identificador de modelo no válido." }
        val text = normalize(transcript)
        require(text.isNotBlank()) { "El modo ICL necesita la transcripción exacta de la referencia." }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(requireNotNull(referenceWav.parentFile), "$PROMPT_PREFIX$variantId-$digest.json")
    }

    private fun transcriptFile(referenceWav: File): File =
        File(requireNotNull(referenceWav.parentFile), "reference.txt")

    private fun normalize(value: String): String {
        val text = value.replace("\r\n", "\n").replace('\r', '\n').trim()
        require(text.length <= MAX_TRANSCRIPT_CHARS) { "La transcripción no puede superar los $MAX_TRANSCRIPT_CHARS caracteres." }
        require('\u0000' !in text) { "La transcripción contiene caracteres no válidos." }
        return text
    }
}
