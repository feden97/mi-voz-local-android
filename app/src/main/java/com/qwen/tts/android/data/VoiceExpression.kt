package com.qwen.tts.android.data

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal enum class VoiceExpression(
    val label: String,
    val description: String,
    val speed: Float,
    val pitch: Float,
    val gain: Float,
) {
    NATURAL("Natural", "Conserva el ritmo, el tono y el volumen de la toma.", 1f, 1f, 1f),
    JOYFUL("Alegre", "Un poco más aguda, rápida y sonora.", 1.09f, 1.10f, 1.10f),
    CALM("Calmada", "Más pausada y suave.", 0.89f, 0.97f, 0.86f),
    SAD("Triste", "Más grave, lenta y suave.", 0.86f, 0.91f, 0.74f),
    EMPHATIC("Enfática", "Algo más grave, pausada y fuerte.", 0.94f, 0.95f, 1.27f),
}

@OptIn(UnstableApi::class)
internal object ExpressionAudio {
    fun render(source: File, target: File, range: WaveRange, expression: VoiceExpression) {
        require(expression != VoiceExpression.NATURAL)
        require(source.canonicalFile != target.canonicalFile)
        val layout = EditableWave.inspect(source)
        require(range.start >= 0 && range.start < range.end && range.end <= layout.frames) {
            "Elegí un tramo de audio."
        }
        require(target.createNewFile()) { "Ya existe un archivo con ese nombre." }
        try {
            val processor = SonicAudioProcessor().apply {
                setSpeed(expression.speed)
                setPitch(expression.pitch)
                configure(AudioProcessor.AudioFormat(layout.sampleRate, 1, C.ENCODING_PCM_16BIT))
                flush()
            }
            val block = ByteArray(16_384)
            val input = ByteBuffer.allocateDirect(block.size).order(ByteOrder.nativeOrder())
            RandomAccessFile(source, "r").use { pcm ->
                pcm.seek(layout.dataOffset + range.start * 2)
                var remaining = (range.end - range.start) * 2
                StreamingWavWriter(target, layout.sampleRate).use { writer ->
                    fun drain() {
                        val output = processor.output
                        while (output.hasRemaining()) {
                            val size = minOf(output.remaining(), block.size)
                            for (i in 0 until size step 2) {
                                val sample = (output.short * expression.gain).roundToInt().coerceIn(-32768, 32767)
                                block[i] = sample.toByte()
                                block[i + 1] = (sample shr 8).toByte()
                            }
                            writer.appendPcm16(block, size)
                        }
                    }
                    while (remaining > 0) {
                        val bytes = minOf(remaining, block.size.toLong()).toInt()
                        pcm.readFully(block, 0, bytes)
                        input.clear()
                        input.put(block, 0, bytes).flip()
                        while (input.hasRemaining()) {
                            processor.queueInput(input)
                            drain()
                        }
                        remaining -= bytes
                    }
                    processor.queueEndOfStream()
                    while (!processor.isEnded) drain()
                    check(writer.sampleCount > 0) { "La selección es demasiado corta. Probá con un tramo más largo." }
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }
}
