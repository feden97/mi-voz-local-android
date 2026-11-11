package com.qwen.tts.android.data

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

/** Keeps only one native utterance in Java memory and leaves a playable completed prefix. */
internal class StreamingWavWriter(val file: File, val sampleRate: Int = 24_000) : AutoCloseable {
    private val output = RandomAccessFile(file, "rw")
    var sampleCount: Long = 0; private set
    private var closed = false
    init { output.setLength(0); output.write(ByteArray(44)) }

    fun append(samples: FloatArray, rate: Int, pauseMillis: Int = 150) {
        require(rate == sampleRate) { "La frecuencia del audio cambió entre fragmentos." }
        require(samples.isNotEmpty() && samples.all { it.isFinite() }) { "El fragmento de audio no es válido." }
        val previousLength = output.length()
        val previousSamples = sampleCount
        try {
        if (sampleCount > 0) {
            val pauseSamples = sampleRate.toLong() * pauseMillis / 1000
            writeSilence(pauseSamples)
            sampleCount += pauseSamples
        }
        val block = ByteArray(16_384)
        var used = 0
        for (sample in samples) {
            val value = (sample.coerceIn(-1f, 1f) * 32768).roundToInt().coerceIn(-32768, 32767)
            block[used++] = (value and 0xff).toByte()
            block[used++] = (value shr 8).toByte()
            if (used == block.size) { output.write(block); used = 0 }
        }
        if (used > 0) output.write(block, 0, used)
        sampleCount += samples.size
        updateHeader()
        output.fd.sync()
        } catch (error: Throwable) {
            sampleCount = previousSamples
            runCatching { output.setLength(previousLength); output.seek(previousLength); updateHeader() }
            throw error
        }
    }

    private fun writeSilence(samples: Long) {
        var remaining = samples * 2
        val zero = ByteArray(8192)
        while (remaining > 0) { val count = minOf(remaining, zero.size.toLong()).toInt(); output.write(zero, 0, count); remaining -= count }
    }

    private fun updateHeader() {
        require(sampleCount * 2 <= 0xffff_ffffL - 36) { "El WAV supera el tamaño del formato RIFF." }
        val end = output.filePointer
        output.seek(0)
        fun ascii(s: String) = output.write(s.toByteArray(Charsets.US_ASCII))
        fun intLe(n: Long) { repeat(4) { output.write((n shr (it * 8)).toInt() and 0xff) } }
        fun shortLe(n: Int) { output.write(n and 0xff); output.write((n shr 8) and 0xff) }
        ascii("RIFF"); intLe(36 + sampleCount * 2); ascii("WAVEfmt "); intLe(16)
        shortLe(1); shortLe(1); intLe(sampleRate.toLong()); intLe(sampleRate.toLong() * 2); shortLe(2); shortLe(16)
        ascii("data"); intLe(sampleCount * 2)
        output.seek(end)
    }

    override fun close() {
        if (!closed) {
            try { updateHeader() } finally { closed = true; output.close() }
        }
    }
}
