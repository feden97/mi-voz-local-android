package com.qwen.tts.android.data

import java.text.BreakIterator
import java.util.Locale

internal object ReadingText {
    const val PREVIEW_CHARACTERS = 160

    private val spanish = Locale.forLanguageTag("es-AR")
    private val paragraphBreak = Regex("\\n[\\t\\p{Zs}]*\\n(?:[\\t\\p{Zs}]*\\n)*")
    private val horizontalSpace = Regex("[\\t\\p{Zs}]+")
    private val beforePunctuation = Regex("(?<=\\S) +(?=[,;.!?…])")
    private val spokenTime = Regex(
        "(?i)\\b(a las?|desde las?|hasta las?|son las?|es las?) +([01]?[0-9]|2[0-3]):([0-5][0-9])" +
            "(?![\\p{L}\\p{N}:]|[.,][0-9]| +[ap]\\.? *m\\.?(?!\\p{L})| *(?:[-–—]|a +[0-9]))",
    )
    private val acronym = Regex("(?<![\\p{L}\\p{N}_./@%+=#-])(?:DNI|PDF|USB)(?![\\p{L}\\p{N}_/@%+=#-]|\\.[\\p{L}\\p{N}])")
    private val acronyms = mapOf("DNI" to "de ene i", "PDF" to "pe de efe", "USB" to "u ese be")
    private val protected = Regex("(?i)(?:https?://|www\\.)\\S+|[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}.\\-]+|`[^`\\n]+`")
    private val plainQuestionOrExclamation = Regex("^[\\p{L}][\\p{L}\\p{M}\\p{N}\\p{Zs}’'\\-]*[?!]$")
    private val word = Regex("\\p{L}+")
    private val numbers = listOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
        "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve",
        "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis",
        "veintisiete", "veintiocho", "veintinueve",
    )
    private val tens = mapOf(30 to "treinta", 40 to "cuarenta", 50 to "cincuenta")

    fun prepareSpanish(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
        .trim().split(paragraphBreak)
        .joinToString("\n\n") { paragraph ->
            val lines = paragraph.lines().map { line ->
                val prepared = prepareLine(line.trim())
                if (plainQuestionOrExclamation.matches(prepared)) {
                    val opening = if (prepared.endsWith('?')) '¿' else '¡'
                    opening + prepared
                } else prepared
            }
            val last = lines.last()
            val endsWithProtectedText = protected.findAll(last).lastOrNull()?.range?.last == last.lastIndex
            val addPeriod = last.lastOrNull()?.isLetterOrDigit() == true && !endsWithProtectedText &&
                word.findAll(last).take(3).count() == 3
            lines.joinToString("\n") + if (addPeriod) "." else ""
        }

    private fun prepareLine(line: String): String = buildString {
        var previous = 0
        for (match in protected.findAll(line)) {
            append(prepareProse(line.substring(previous, match.range.first)))
            append(match.value)
            previous = match.range.last + 1
        }
        append(prepareProse(line.substring(previous)))
    }

    private fun prepareProse(text: String): String {
        val spaced = beforePunctuation.replace(horizontalSpace.replace(text, " "), "")
        val withTimes = spokenTime.replace(spaced) { match ->
            val (prefix, hourText, minuteText) = match.destructured
            val hour = hourText.toInt()
            val minute = minuteText.toInt()
            val hourWords = if (hour == 1) "una" else numbers[hour]
            val spokenHour = when (minute) {
                0 -> "$hourWords en punto"
                15 -> "$hourWords y cuarto"
                30 -> "$hourWords y media"
                else -> "$hourWords y ${number(minute)}"
            }
            val articleMatchesHour = prefix.endsWith("las", ignoreCase = true) == (hour != 1)
            val prefixWithArticle = when {
                articleMatchesHour -> prefix
                hour == 1 -> prefix.dropLast(1)
                prefix.last().isUpperCase() -> prefix + "S"
                else -> prefix + "s"
            }
            "$prefixWithArticle $spokenHour"
        }
        return acronym.replace(withTimes) { acronyms.getValue(it.value) }
    }

    private fun number(value: Int): String {
        if (value < numbers.size) return numbers[value]
        val ten = tens.getValue(value / 10 * 10)
        val unit = value % 10
        return if (unit == 0) ten else "$ten y ${numbers[unit]}"
    }

    /** Propose a sentence from the first nonempty line when there is no selection. */
    fun firstPhrase(text: String, spanishScript: Boolean): String {
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isEmpty()) return line
        val sentences = BreakIterator.getSentenceInstance(if (spanishScript) spanish else Locale.ROOT)
        sentences.setText(line)
        val sentence = line.substring(0, sentences.next()).trimEnd()
        if (TextChunks.count(sentence) <= PREVIEW_CHARACTERS) return sentence

        val limit = sentence.offsetByCodePoints(0, PREVIEW_CHARACTERS)
        val lastSpace = (limit downTo 1).firstOrNull { sentence[it].isWhitespace() }
        return if (lastSpace != null) sentence.substring(0, lastSpace).trimEnd()
        else sentence.takeWhile { !it.isWhitespace() }
    }
}
