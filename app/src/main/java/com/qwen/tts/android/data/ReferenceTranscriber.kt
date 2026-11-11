package com.qwen.tts.android.data

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local reference ASR. Only a public model is downloaded; audio and text never leave the device. */
internal class ReferenceTranscriber(context: Context) {
    companion object {
        const val MODEL_LABEL = "Whisper Base multilingüe Q5 · 60 MB"
        const val MODEL_BYTES = 59_707_625L
        const val MODEL_SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        const val MODEL_REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
        private const val MODEL_FILE = "ggml-base-q5_1.bin"
        private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$MODEL_REVISION/$MODEL_FILE?download=true"
        // Whisper and TTS share GGML, so the UI also releases its TTS engine before calling ASR.
        private val inferenceMutex = Mutex()
        private val nativeLibrary by lazy { System.loadLibrary("mivoz_asr_jni") }
    }

    private val directory = File(context.noBackupFilesDir, "reference-asr")
    private val model = File(directory, MODEL_FILE)
    private val cancelled = AtomicBoolean(false)
    private val operationMutex = Mutex()
    val modelAvailable: Boolean get() = model.isFile && model.length() == MODEL_BYTES

    /** Cancels downloading or computation cooperatively. Never frees a running native context. */
    fun cancel() { cancelled.set(true) }

    /** Call before exposing Cancel or releasing TTS, never after preparation has already started. */
    fun resetCancellation() { cancelled.set(false) }

    /** Returns a draft only. The caller decides when to save it as the voice's ICL transcript. */
    suspend fun transcribe(
        referenceWav: File,
        language: String = "es",
        onProgress: (String) -> Unit = {},
    ): String = operationMutex.withLock {
        withContext(Dispatchers.IO) {
            require(language.matches(Regex("[a-z]{2,3}"))) { "El idioma de transcripción no es válido." }
            val context = coroutineContext
            val job = context[Job]
            fun checkCancelled() {
                context.ensureActive()
                if (cancelled.get()) throw CancellationException("Transcripción cancelada")
            }
            checkCancelled()
            ensureModel(onProgress)
            checkCancelled()
            onProgress("Preparando el audio para transcribir")
            val samples = ReferenceWave.read16k(referenceWav)
            checkCancelled()
            inferenceMutex.withLock {
                checkCancelled()
                nativeLibrary
                onProgress("Transcribiendo en el teléfono")
                val callbacks = NativeCallbacks(cancelled, job, onProgress)
                val bytes = nativeTranscribe(model.absolutePath, samples, language, 4, callbacks)
                checkCancelled()
                val text = bytes.toString(Charsets.UTF_8).trim()
                require(text.isNotBlank()) { "No se reconocieron palabras. Podés escribir la transcripción manualmente." }
                require(text.length <= VoicePromptFiles.MAX_TRANSCRIPT_CHARS && '\u0000' !in text) {
                    "La transcripción no es válida. Podés escribirla manualmente."
                }
                text
            }
        }
    }

    private suspend fun ensureModel(onProgress: (String) -> Unit) {
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo preparar el transcriptor local." }
        onProgress("Verificando el transcriptor local")
        if (modelAvailable && sha256(model) == MODEL_SHA256) return
        if (model.exists()) check(model.delete()) { "No se pudo reparar el modelo de transcripción." }
        val pending = File.createTempFile("whisper-", ".download", directory)
        var connection: HttpURLConnection? = null
        try {
            check(directory.usableSpace > MODEL_BYTES + 32L * 1024 * 1024) {
                "Se necesitan unos 100 MB libres para descargar el transcriptor."
            }
            onProgress("Descargando transcriptor local · 0 / 60 MB")
            val request = URL(MODEL_URL).openConnection() as HttpURLConnection
            connection = request
            request.connectTimeout = 20_000
            request.readTimeout = 20_000
            request.setRequestProperty("Accept-Encoding", "identity")
            check(request.responseCode in 200..299) { "No se pudo descargar el transcriptor. Revisá la conexión y volvé a intentar." }
            val hash = MessageDigest.getInstance("SHA-256")
            request.inputStream.buffered().use { input ->
                pending.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var lastMb = -1L
                    while (true) {
                        coroutineContext.ensureActive()
                        if (cancelled.get()) throw CancellationException("Transcripción cancelada")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MODEL_BYTES) { "La descarga del transcriptor no tiene el tamaño esperado." }
                        output.write(buffer, 0, count)
                        hash.update(buffer, 0, count)
                        val mb = total / 1_000_000
                        if (mb != lastMb) {
                            onProgress("Descargando transcriptor local · $mb / 60 MB")
                            lastMb = mb
                        }
                    }
                    require(total == MODEL_BYTES && hash.digest().hex() == MODEL_SHA256) {
                        "La descarga del transcriptor está incompleta o dañada. Volvé a intentar."
                    }
                }
            }
            coroutineContext.ensureActive()
            if (cancelled.get()) throw CancellationException("Transcripción cancelada")
            check(pending.renameTo(model)) { "No se pudo guardar el transcriptor local." }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw IOException("No se pudo descargar el transcriptor. Hace falta conexión solo la primera vez; el audio permanece en tu teléfono.", error)
        } finally {
            connection?.disconnect()
            pending.delete()
        }
    }

    private suspend fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                if (cancelled.get()) throw CancellationException("Transcripción cancelada")
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** JNI keeps a global reference until whisper_full returns, including during cooperative abort. */
    private class NativeCallbacks(
        private val cancelled: AtomicBoolean,
        private val job: Job?,
        private val onProgress: (String) -> Unit,
    ) {
        @Suppress("unused") fun isCancelled(): Boolean = cancelled.get() || job?.isActive == false
        @Suppress("unused") fun progress(percent: Int) = onProgress("Transcribiendo en el teléfono · ${percent.coerceIn(0, 100)} %")
    }

    private external fun nativeTranscribe(
        modelPath: String, samples: FloatArray, language: String, threads: Int, callbacks: NativeCallbacks,
    ): ByteArray
}
