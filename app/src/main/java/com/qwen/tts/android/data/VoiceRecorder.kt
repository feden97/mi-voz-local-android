package com.qwen.tts.android.data

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

data class RecorderState(
    val isRecording: Boolean = false,
    val elapsedMillis: Long = 0L,
    val level: Float = 0f,
    val outputPath: String? = null,
)

data class RecordingResult(
    val file: File,
    val durationMillis: Long,
)

class VoiceRecorder {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sampleRate = 24_000
    private val maxSamples = sampleRate * 30
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        .coerceAtLeast(sampleRate / 5)
    private val operationLock = Any()

    private class Session(val record: AudioRecord, val file: File) {
        val bufferLock = Any()
        val deviceLock = Any()
        val accepting = AtomicBoolean(true)
        val bytes = ByteArrayOutputStream()
        var sampleCount = 0
        var stopped = false
        var released = false

        fun stopDevice() = synchronized(deviceLock) {
            if (!stopped && !released) {
                stopped = true
                runCatching { record.stop() }
            }
        }

        // Only the reader releases the device, after its blocking read has returned.
        fun releaseDevice() = synchronized(deviceLock) {
            if (!released) {
                if (!stopped) runCatching { record.stop() }
                stopped = true
                runCatching { record.release() }
                released = true
            }
        }
    }

    @Volatile private var currentSession: Session? = null

    private val _state = MutableStateFlow(RecorderState())
    val state = _state.asStateFlow()

    @SuppressLint("MissingPermission")
    fun start(file: File): Boolean = synchronized(operationLock) {
        if (currentSession != null) return@synchronized true
        file.parentFile?.mkdirs()
        var record: AudioRecord? = null
        val ready = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(audioEncoding)
                        .build(),
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .build()
                .also { created ->
                    record = created
                    check(created.state == AudioRecord.STATE_INITIALIZED)
                    created.startRecording()
                    check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                }
        }.getOrElse {
            runCatching { record?.release() }
            return@synchronized false
        }
        val session = Session(ready, file)
        currentSession = session
        _state.value = RecorderState(isRecording = true, outputPath = file.absolutePath)
        scope.launch {
            try {
                val buffer = ShortArray(minBufferSize / 2)
                while (session.accepting.get()) {
                    val count = session.record.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    synchronized(session.bufferLock) {
                        if (session.accepting.get() && currentSession === session) {
                            val accepted = minOf(count, maxSamples - session.sampleCount)
                            var sumSquares = 0.0
                            for (i in 0 until accepted) {
                                val sample = buffer[i].toInt()
                                session.bytes.write(sample and 0xff)
                                session.bytes.write((sample shr 8) and 0xff)
                                sumSquares += sample.toDouble() * sample.toDouble()
                            }
                            session.sampleCount += accepted
                            val atLimit = session.sampleCount >= maxSamples
                            val rms = if (accepted > 0) sqrt(sumSquares / accepted) / 32768.0 else 0.0
                            _state.value = RecorderState(
                                // Remain finishable with stop() after the microphone hits its hard limit.
                                // The UI may consume this immediately, or when returning to Voices.
                                isRecording = true,
                                elapsedMillis = session.sampleCount.toLong() * 1000 / sampleRate,
                                level = if (atLimit) 0f else rms.toFloat().coerceIn(0f, 1f),
                                outputPath = file.absolutePath,
                            )
                            if (atLimit) session.accepting.set(false)
                        }
                    }
                }
            } catch (_: Exception) {
                // A device/permission error leaves any captured samples available to stop().
            } finally {
                session.accepting.set(false)
                session.releaseDevice()
            }
        }
        true
    }

    fun stop(): RecordingResult? = synchronized(operationLock) {
        val session = currentSession ?: return@synchronized null
        val pcm: ByteArray
        val duration: Long
        synchronized(session.bufferLock) {
            session.accepting.set(false)
            currentSession = null
            pcm = session.bytes.toByteArray()
            duration = session.sampleCount.toLong() * 1000 / sampleRate
            _state.value = RecorderState(isRecording = false, outputPath = session.file.absolutePath)
        }
        session.stopDevice()
        runCatching {
            session.file.outputStream().use { output -> writeWav(output, pcm, sampleRate) }
            RecordingResult(file = session.file, durationMillis = duration)
        }.getOrElse {
            session.file.delete()
            null
        }
    }

    fun cancel(): Unit = synchronized(operationLock) {
        val session = currentSession
        if (session != null) {
            synchronized(session.bufferLock) {
                session.accepting.set(false)
                currentSession = null
                _state.value = RecorderState()
            }
            session.stopDevice()
            runCatching { session.file.delete() }
        } else {
            _state.value = RecorderState()
        }
    }

    private fun writeWav(output: OutputStream, pcmBytes: ByteArray, sampleRate: Int) {
        output.writeAscii("RIFF")
        output.writeIntLe(36 + pcmBytes.size)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(sampleRate)
        output.writeIntLe(sampleRate * 2)
        output.writeShortLe(2)
        output.writeShortLe(16)
        output.writeAscii("data")
        output.writeIntLe(pcmBytes.size)
        output.write(pcmBytes)
    }
}

private fun OutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun OutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
    write((value shr 16) and 0xff)
    write((value shr 24) and 0xff)
}

private fun OutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
}
