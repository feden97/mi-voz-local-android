package com.qwen.tts.android.data

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Bounded PCM conversion shared by WAV import and Android decoders. */
internal class AudioSamples(val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate in 8_000..96_000) { "La frecuencia del audio debe estar entre 8 y 96 kHz." }
        require(channels in 1..8) { "El audio debe tener entre 1 y 8 canales." }
    }

    private val maxFrames = sampleRate * MAX_SECONDS
    private var samples = FloatArray(minOf(sampleRate, maxFrames))
    var frameCount: Int = 0
        private set

    fun append(pcm: ByteBuffer, floatPcm: Boolean) {
        pcm.order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerFrame = channels * if (floatPcm) 4 else 2
        require(pcm.remaining() % bytesPerFrame == 0) { "El audio contiene una trama PCM incompleta." }
        val incomingFrames = pcm.remaining() / bytesPerFrame
        require(incomingFrames <= maxFrames - frameCount) { "Elegí un audio de hasta $MAX_SECONDS segundos." }
        if (frameCount + incomingFrames > samples.size) {
            samples = samples.copyOf(minOf(maxFrames, maxOf(samples.size * 2, frameCount + incomingFrames)))
        }
        repeat(incomingFrames) {
            var mono = 0.0
            repeat(channels) {
                val value = if (floatPcm) pcm.float else pcm.short / 32768f
                require(value.isFinite()) { "El audio contiene muestras inválidas." }
                mono += value.coerceIn(-1f, 1f)
            }
            samples[frameCount++] = (mono / channels).toFloat()
        }
    }

    /** Linear interpolation; use a clean 24 kHz source when avoiding resampling is important. */
    fun toMono24k(): FloatArray {
        require(frameCount >= sampleRate * MIN_SECONDS) { "Elegí un audio de al menos $MIN_SECONDS segundos." }
        require((0 until frameCount).any { abs(samples[it]) > 0.0001f }) { "El audio está vacío o en silencio." }
        val count = (frameCount.toLong() * OUTPUT_RATE / sampleRate).toInt()
        return FloatArray(count) { i ->
            val source = i.toDouble() * sampleRate / OUTPUT_RATE
            val left = source.toInt().coerceAtMost(frameCount - 1)
            val right = minOf(left + 1, frameCount - 1)
            val fraction = (source - left).toFloat()
            samples[left] + (samples[right] - samples[left]) * fraction
        }
    }

    companion object {
        const val MIN_SECONDS = 3
        const val MAX_SECONDS = 30
        const val OUTPUT_RATE = 24_000

        fun writeWav(output: OutputStream, mono: FloatArray) {
            require(mono.size <= OUTPUT_RATE * MAX_SECONDS)
            val pcmSize = mono.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + pcmSize)
            header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(1.toShort())
            header.putInt(OUTPUT_RATE)
            header.putInt(OUTPUT_RATE * 2)
            header.putShort(2.toShort())
            header.putShort(16.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcmSize)
            output.write(header.array())
            val block = ByteBuffer.allocate(8_192).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in mono) {
                require(sample.isFinite()) { "El audio contiene muestras inválidas." }
                val value = (sample.coerceIn(-1f, 1f) * 32768f).roundToInt().coerceIn(-32768, 32767)
                block.putShort(value.toShort())
                if (!block.hasRemaining()) {
                    output.write(block.array())
                    block.clear()
                }
            }
            if (block.position() > 0) output.write(block.array(), 0, block.position())
        }
    }
}
