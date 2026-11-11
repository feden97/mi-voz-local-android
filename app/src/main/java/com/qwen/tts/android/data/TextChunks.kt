package com.qwen.tts.android.data

/** Bounds native utterances without losing UTF-16 surrogate pairs or any source characters. */
internal object TextChunks {
    const val MAX_CHARACTERS = 20_000
    const val CHUNK_CHARACTERS = 350
    private const val MIN_PREFERRED_CHARACTERS = 100
    private data class Boundary(val offset: Int, val priority: Int)
    private val paragraphs = Regex("\\r?\\n[\\t ]*\\r?\\n\\s*")
    private val sentences = Regex("[.!?…][\"'»”’)\\]]*\\s+")
    private val clauses = Regex("[;:]\\s+")

    fun count(text: String): Int = text.codePointCount(0, text.length)

    fun split(text: String): List<String> {
        require(text.isNotBlank()) { "Escribí el texto que querés generar." }
        require(count(text) <= MAX_CHARACTERS) { "El máximo por trabajo es de 20.000 caracteres." }
        // A short script keeps its prosody in one native call. The acoustic-token
        // cap remains independent: characters cannot predict the speaking rate.
        if (count(text) <= CHUNK_CHARACTERS) return listOf(text)

        val boundaries = buildList {
            paragraphs.findAll(text).forEach { add(Boundary(it.range.last + 1, 0)) }
            sentences.findAll(text).forEach { add(Boundary(it.range.last + 1, 1)) }
            clauses.findAll(text).forEach { add(Boundary(it.range.last + 1, 2)) }
            text.forEachIndexed { index, character ->
                if (character.isWhitespace()) {
                    // Both sides matter when a word itself occupies the full cap.
                    add(Boundary(index, 3))
                    add(Boundary(index + 1, 3))
                }
            }
        }
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.codePointCount(start, text.length)
            var end = if (remaining <= CHUNK_CHARACTERS) text.length else text.offsetByCodePoints(start, CHUNK_CHARACTERS)
            if (end < text.length) {
                val available = boundaries.filter { it.offset > start && it.offset <= end }
                require(available.isNotEmpty()) {
                    "Hay una palabra o dirección de más de $CHUNK_CHARACTERS caracteres. Separala antes de generar."
                }
                val pieces = (remaining + CHUNK_CHARACTERS - 1) / CHUNK_CHARACTERS
                val target = remaining / pieces
                // Leave enough capacity for the remaining pieces, and avoid a
                // tiny final tail when there is room to balance the last pair.
                val minimum = maxOf(remaining - (pieces - 1) * CHUNK_CHARACTERS,
                    minOf(MIN_PREFERRED_CHARACTERS, target / 2))
                val maximum = if (pieces == 2) minOf(CHUNK_CHARACTERS, remaining - MIN_PREFERRED_CHARACTERS) else CHUNK_CHARACTERS
                val minimumOffset = text.offsetByCodePoints(start, minimum)
                val maximumOffset = text.offsetByCodePoints(start, maximum)
                val preferred = available.filter { it.offset in minimumOffset..maximumOffset }
                end = preferred.minWithOrNull(compareBy<Boundary> { it.priority }
                    .thenBy { kotlin.math.abs(text.codePointCount(start, it.offset) - target) }
                    .thenByDescending { it.offset })?.offset
                    // A very long word can make balanced pieces impossible.
                    ?: available.maxOf { it.offset }
            }
            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }
}
