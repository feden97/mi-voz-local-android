package com.qwen.tts.android.data

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Imports local documents only: no uploads, network clients, or persistent URI permissions. */
object AudioImporter {
    private const val MAX_INPUT_BYTES = 32L * 1024 * 1024
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val DECODE_DEADLINE_NANOS = 120_000_000_000L

    suspend fun importAudio(context: Context, uri: Uri, outputFile: File): RecordingResult =
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content" || uri.scheme == "file") { "Elegí un archivo de audio local." }
            require(!outputFile.exists()) { "El archivo de destino ya existe." }
            val source = File.createTempFile("voice-import-", ".audio", context.cacheDir)
            val pending = File(outputFile.parentFile, ".${outputFile.name}.${UUID.randomUUID()}.tmp")
            try {
                copyBounded(context, uri, source)
                val decoded = if (isWave(source)) readWave(source) else decodeAudio(source)
                coroutineContext.ensureActive()
                val mono = decoded.toMono24k()
                check(outputFile.parentFile?.isDirectory == true || outputFile.parentFile?.mkdirs() == true) {
                    "No se pudo crear la carpeta de voces."
                }
                pending.outputStream().buffered().use { AudioSamples.writeWav(it, mono) }
                coroutineContext.ensureActive()
                check(pending.renameTo(outputFile)) { "No se pudo guardar el audio importado." }
                RecordingResult(outputFile, mono.size.toLong() * 1000 / AudioSamples.OUTPUT_RATE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                throw IOException("No se pudo importar el audio. Probá con un WAV PCM de 16 bits, MP3 o M4A sin protección.", error)
            } finally {
                source.delete()
                pending.delete()
            }
        }

    private suspend fun copyBounded(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("No se pudo abrir el archivo seleccionado.")
        input.use { source ->
            target.outputStream().buffered().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_INPUT_BYTES) { "El audio no puede superar los 32 MiB." }
                    sink.write(buffer, 0, count)
                }
                require(total > 0) { "El archivo seleccionado está vacío." }
            }
        }
    }

    private fun isWave(file: File): Boolean = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 12) return@use false
        val header = ByteArray(12)
        input.readFully(header)
        String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    /** Decode PCM WAV ourselves: several Android versions have no audio/raw codec. */
    private suspend fun readWave(file: File): AudioSamples = RandomAccessFile(file, "r").use { input ->
        input.seek(12)
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var tag = 0
        var blockAlign = 0
        var dataOffset = -1L
        var dataSize = 0L
        while (input.filePointer + 8 <= input.length()) {
            coroutineContext.ensureActive()
            val id = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val size = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            val start = input.filePointer
            require(size <= input.length() - start) { "El archivo WAV está incompleto." }
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "La cabecera WAV no es válida." }
                    tag = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                    channels = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                    sampleRate = Integer.reverseBytes(input.readInt())
                    input.skipBytes(4)
                    blockAlign = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                    bits = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
                }
                "data" -> if (dataOffset < 0) {
                    dataOffset = start
                    dataSize = size
                }
            }
            val next = start + size + (size and 1L)
            require(next <= input.length()) { "El archivo WAV está incompleto." }
            input.seek(next)
        }
        require(dataOffset >= 0 && dataSize > 0) { "El WAV no contiene audio." }
        require((tag == 1 && bits == 16) || (tag == 3 && bits == 32)) {
            "Este WAV debe ser PCM de 16 bits o float de 32 bits. Exportalo en uno de esos formatos."
        }
        val samples = AudioSamples(sampleRate, channels)
        val bytesPerFrame = channels * (bits / 8)
        require(blockAlign == bytesPerFrame && dataSize % bytesPerFrame == 0L) { "El WAV contiene tramas PCM inválidas." }
        require(dataSize / bytesPerFrame <= sampleRate.toLong() * AudioSamples.MAX_SECONDS) {
            "Elegí un audio de hasta ${AudioSamples.MAX_SECONDS} segundos."
        }
        input.seek(dataOffset)
        val buffer = ByteArray((64 * 1024 / bytesPerFrame) * bytesPerFrame)
        var remaining = dataSize
        while (remaining > 0) {
            coroutineContext.ensureActive()
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            input.readFully(buffer, 0, count)
            samples.append(ByteBuffer.wrap(buffer, 0, count), floatPcm = tag == 3)
            remaining -= count
        }
        samples
    }

    private suspend fun decodeAudio(file: File): AudioSamples {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var codecStarted = false
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("El archivo no contiene una pista de audio.")
            extractor.selectTrack(trackIndex)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) {
                require(sourceFormat.getLong(MediaFormat.KEY_DURATION) <= AudioSamples.MAX_SECONDS * 1_000_000L) {
                    "Elegí un audio de hasta ${AudioSamples.MAX_SECONDS} segundos."
                }
            }
            val mime = requireNotNull(sourceFormat.getString(MediaFormat.KEY_MIME))
            require(mime != "audio/raw") { "Usá un WAV PCM de 16 bits para importar audio sin comprimir." }
            // Request PCM16; verify the real output format before consuming any buffer.
            sourceFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(sourceFormat, null, null, 0)
            decoder.start()
            codecStarted = true
            var inputDone = false
            var outputDone = false
            var collector: AudioSamples? = null
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + DECODE_DEADLINE_NANOS
            while (!outputDone) {
                coroutineContext.ensureActive()
                require(System.nanoTime() < deadline) { "La decodificación tardó demasiado. Probá con otro archivo." }
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(inputIndex))
                        input.clear()
                        val count = extractor.readSampleData(input, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            require((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) == 0) {
                                "No se puede importar audio protegido."
                            }
                            require(count <= input.capacity()) { "El archivo contiene un bloque de audio demasiado grande." }
                            decoder.queueInputBuffer(inputIndex, 0, count, extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            "El decodificador devolvió un formato PCM no compatible. Usá WAV PCM de 16 bits."
                        }
                        require(collector == null || (collector.sampleRate == rate && collector.channels == channels && collector.frameCount == 0)) {
                            "El formato del audio cambió durante la lectura."
                        }
                        collector = AudioSamples(rate, channels)
                        floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    else -> if (outputIndex >= 0) {
                        try {
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val samples = requireNotNull(collector) { "El decodificador no informó el formato del audio." }
                                val output = requireNotNull(decoder.getOutputBuffer(outputIndex))
                                require(info.offset >= 0 && info.size <= output.capacity() - info.offset) { "El audio decodificado no es válido." }
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                samples.append(output.slice().order(ByteOrder.LITTLE_ENDIAN), floatPcm)
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            return requireNotNull(collector) { "No se pudo decodificar el audio seleccionado." }
        } finally {
            if (codecStarted) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
