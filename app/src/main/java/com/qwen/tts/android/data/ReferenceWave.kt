package com.qwen.tts.android.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** Bounded RIFF reader and anti-aliased resampler for the saved reference, independent of Android codecs. */
internal object ReferenceWave {
    private const val OUTPUT_RATE = 16_000
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val RADIUS = 16
    private const val PHASES = 512

    fun read16k(file: File): FloatArray = RandomAccessFile(file, "r").use { input ->
        require(input.length() in 44..MAX_BYTES) { "El audio de referencia no es válido." }
        fun id(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun uint(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
        fun ushort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff
        require(id() == "RIFF") { "La referencia debe ser un archivo WAV." }
        val declaredBytes = uint() + 8
        require(declaredBytes <= input.length() && id() == "WAVE") { "El WAV está incompleto." }
        var format = 0
        var channels = 0
        var rate = 0
        var bits = 0
        var alignment = 0
        var dataOffset = -1L
        var dataBytes = 0L
        while (input.filePointer + 8 <= declaredBytes) {
            val chunk = id()
            val size = uint()
            val offset = input.filePointer
            require(size <= declaredBytes - offset) { "El WAV está incompleto." }
            when (chunk) {
                "fmt " -> {
                    require(size >= 16) { "El formato WAV no es válido." }
                    format = ushort()
                    channels = ushort()
                    rate = uint().toInt()
                    uint()
                    alignment = ushort()
                    bits = ushort()
                }
                "data" -> if (dataOffset < 0) { dataOffset = offset; dataBytes = size }
            }
            val next = offset + size + (size and 1L)
            require(next <= declaredBytes) { "El WAV está incompleto." }
            input.seek(next)
        }
        require(rate in 8_000..96_000 && channels in 1..2 &&
            ((format == 1 && bits == 16) || (format == 3 && bits == 32))) {
            "La referencia debe ser WAV PCM16 o float32, mono o estéreo."
        }
        val bytesPerFrame = channels * bits / 8
        require(alignment == bytesPerFrame && dataOffset >= 0 && dataBytes > 0 && dataBytes % bytesPerFrame == 0L) {
            "Los datos del WAV no son válidos."
        }
        val frames = dataBytes / bytesPerFrame
        require(frames <= rate.toLong() * 30) { "La referencia no puede superar los 30 segundos." }
        input.seek(dataOffset)
        val pcm = ByteBuffer.wrap(ByteArray(dataBytes.toInt()).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
        val mono = FloatArray(frames.toInt())
        for (i in mono.indices) {
            var value = 0f
            repeat(channels) {
                val sample = if (format == 1) {
                    pcm.short.toFloat() / 32768f
                } else pcm.float
                require(sample.isFinite()) { "El WAV contiene muestras no válidas." }
                value += sample.coerceIn(-1f, 1f)
            }
            mono[i] = value / channels
        }
        resample(mono, rate)
    }

    internal fun resample(input: FloatArray, sourceRate: Int): FloatArray {
        require(sourceRate in 8_000..96_000)
        if (sourceRate == OUTPUT_RATE) return input
        val count = (input.size.toLong() * OUTPUT_RATE / sourceRate).toInt()
        if (count == 0) return FloatArray(0)
        // Windowed sinc prevents frequencies above 8 kHz aliasing into the speech band.
        val cutoff = 0.47 * min(1.0, OUTPUT_RATE.toDouble() / sourceRate)
        val kernels = Array(PHASES) { phase ->
            DoubleArray(2 * RADIUS) { tap ->
                val distance = tap - RADIUS + 1 - phase.toDouble() / PHASES
                val x = 2 * cutoff * distance
                val sinc = if (kotlin.math.abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)
                val window = 0.42 + 0.5 * cos(PI * distance / RADIUS) + 0.08 * cos(2 * PI * distance / RADIUS)
                2 * cutoff * sinc * window
            }
        }
        return FloatArray(count) { index ->
            val position = index.toDouble() * sourceRate / OUTPUT_RATE
            val center = floor(position).toInt()
            val phase = ((position - center) * PHASES).toInt().coerceIn(0, PHASES - 1)
            val kernel = kernels[phase]
            var value = 0.0
            var weight = 0.0
            for (tap in kernel.indices) {
                val source = center + tap - RADIUS + 1
                if (source in input.indices) {
                    value += input[source] * kernel[tap]
                    weight += kernel[tap]
                }
            }
            (if (kotlin.math.abs(weight) > 1e-12) value / weight else 0.0).toFloat().coerceIn(-1f, 1f)
        }
    }
}
