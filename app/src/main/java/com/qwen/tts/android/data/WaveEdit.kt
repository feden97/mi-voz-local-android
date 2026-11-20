package com.qwen.tts.android.data

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

internal data class WaveLayout(val sampleRate: Int, val frames: Long, val dataOffset: Long) {
    val durationMillis: Long get() = (frames * 1000 + sampleRate - 1) / sampleRate

    fun frameAt(millis: Long): Long = millis.coerceIn(0, durationMillis)
        .let { (it * sampleRate / 1000).coerceAtMost(frames) }
}

internal data class WaveRange(val start: Long, val end: Long)

internal enum class WaveEdit { KEEP, REMOVE, REPLACE, INSERT }

internal object EditableWave {
    fun inspect(file: File): WaveLayout = RandomAccessFile(file, "r").use(::readLayout)

    fun peaks(file: File, layout: WaveLayout, bucketCount: Int = 320): FloatArray {
        require(bucketCount > 0)
        val bins = minOf(layout.frames, bucketCount.toLong()).toInt()
        val buffer = ByteArray(16_384)
        return RandomAccessFile(file, "r").use { input ->
            input.seek(layout.dataOffset)
            var frame = 0L
            FloatArray(bins) { index ->
                var peak = 0
                val end = (index + 1L) * layout.frames / bins
                while (frame < end) {
                    val bytes = (minOf(end - frame, buffer.size.toLong() / 2) * 2).toInt()
                    input.readFully(buffer, 0, bytes)
                    for (offset in 0 until bytes step 2) {
                        val pcm = (buffer[offset].toInt() and 0xff) or (buffer[offset + 1].toInt() shl 8)
                        peak = maxOf(peak, abs(pcm))
                    }
                    frame += bytes / 2
                }
                peak / 32768f
            }
        }
    }

    fun saveCopy(source: File, target: File, range: WaveRange, edit: WaveEdit, take: File? = null): WaveLayout {
        require(target.canonicalFile != source.canonicalFile && target.canonicalFile != take?.canonicalFile)
        val needsTake = edit == WaveEdit.REPLACE || edit == WaveEdit.INSERT
        require(needsTake == (take != null))
        RandomAccessFile(source, "r").use { input ->
            val layout = readLayout(input)
            require(range.start in 0..range.end && range.end <= layout.frames) { "La selección está fuera del audio." }
            require((range.start == range.end) == (edit == WaveEdit.INSERT)) { "Elegí un tramo de audio." }
            require(edit != WaveEdit.REMOVE || range.end - range.start < layout.frames) { "Elegí un tramo más corto que todo el audio." }
            require(target.createNewFile()) { "Ya existe un archivo con ese nombre." }
            try {
                StreamingWavWriter(target, layout.sampleRate).use { writer ->
                    if (edit == WaveEdit.KEEP) {
                        writer.appendPcm16(input, layout.dataOffset + range.start * 2, range.end - range.start)
                    } else {
                        writer.appendPcm16(input, layout.dataOffset, range.start)
                        if (take != null) RandomAccessFile(take, "r").use { replacement ->
                            val replacementLayout = readLayout(replacement)
                            require(replacementLayout.sampleRate == layout.sampleRate) { "La toma tiene otra frecuencia de audio." }
                            writer.appendPcm16(replacement, replacementLayout.dataOffset, replacementLayout.frames)
                        }
                        writer.appendPcm16(input, layout.dataOffset + range.end * 2, layout.frames - range.end)
                    }
                }
                return inspect(target)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
        }
    }

    private fun readLayout(input: RandomAccessFile): WaveLayout {
        fun id(): String = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        fun uint(): Long = Integer.reverseBytes(input.readInt()).toLong() and 0xffff_ffffL
        fun ushort(): Int = java.lang.Short.reverseBytes(input.readShort()).toInt() and 0xffff

        require(input.length() >= 44 && id() == "RIFF") { "El archivo no es un WAV válido." }
        val riffEnd = uint() + 8
        require(riffEnd in 44..input.length() && id() == "WAVE") { "El WAV está incompleto." }
        var sampleRate = 0
        var dataOffset = -1L
        var dataSize = 0L
        while (input.filePointer + 8 <= riffEnd) {
            val name = id()
            val size = uint()
            val offset = input.filePointer
            val next = offset + size + (size and 1L)
            require(next <= riffEnd) { "El WAV está incompleto." }
            when (name) {
                "fmt " -> {
                    require(size >= 16 && ushort() == 1 && ushort() == 1) { "Se necesita un WAV mono PCM de 16 bits." }
                    sampleRate = uint().toInt()
                    uint()
                    require(ushort() == 2 && ushort() == 16) { "Se necesita un WAV mono PCM de 16 bits." }
                }
                "data" -> if (dataOffset < 0) {
                    dataOffset = offset
                    dataSize = size
                }
            }
            input.seek(next)
        }
        require(sampleRate in 8_000..96_000 && dataOffset >= 0 && dataSize >= 2 && dataSize % 2 == 0L) {
            "El WAV no contiene audio válido."
        }
        return WaveLayout(sampleRate, dataSize / 2, dataOffset)
    }
}
