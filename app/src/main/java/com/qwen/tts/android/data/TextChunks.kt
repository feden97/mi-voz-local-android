package com.qwen.tts.android.data

/** Bounds native utterances without losing UTF-16 surrogate pairs or any source characters. */
internal object TextChunks {
    const val MAX_CHARACTERS = 20_000
    const val CHUNK_CHARACTERS = 220
    fun count(text: String): Int = text.codePointCount(0, text.length)

    fun split(text: String): List<String> {
        require(text.isNotBlank()) { "Escribí el texto que querés generar." }
        require(count(text) <= MAX_CHARACTERS) { "El máximo por trabajo es de 20.000 caracteres." }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.codePointCount(start, text.length)
            var end = if (remaining <= CHUNK_CHARACTERS) text.length else text.offsetByCodePoints(start, CHUNK_CHARACTERS)
            if (end < text.length) {
                val window = text.substring(start, end)
                val sentence = Regex("[.!?;:]\\s+").findAll(window).lastOrNull { it.range.last >= window.length / 3 }
                val whitespace = window.indexOfLast { it.isWhitespace() }
                end = when {
                    sentence != null -> start + sentence.range.last + 1
                    whitespace >= 0 -> start + whitespace + 1
                    else -> throw IllegalArgumentException("Hay una palabra o dirección de más de 220 caracteres. Separala antes de generar.")
                }
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }
}
