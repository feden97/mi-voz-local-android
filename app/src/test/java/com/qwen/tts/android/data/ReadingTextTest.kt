package com.qwen.tts.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTextTest {
    @Test fun preparesSpanishHoursPunctuationAndParagraphs() {
        val text = " Fede , vení   a las 8:30 y llevá el DNI\r\n\r\n\r\n Mirá el PDF a las 01:15 "
        val expected = "Fede, vení a las ocho y media y llevá el de ene i.\n\nMirá el pe de efe a la una y cuarto."
        assertEquals(expected, ReadingText.prepareSpanish(text))
        assertEquals(expected, ReadingText.prepareSpanish(expected))
        assertEquals("El trabajo empieza a las veintitrés y cincuenta y nueve.",
            ReadingText.prepareSpanish("El trabajo empieza a las 23:59"))
    }

    @Test fun preservesAddressesIdentifiersAndAmbiguousNumbers() {
        val text = "Dura 8:30. Llegá a las 8:30 p. m. o a las 9:00-10:00. " +
            "El valor es 3,14 y el ID es PDF-8. Escribí a PDF@ejemplo.com o entrá a https://ejemplo.com/PDF"
        assertEquals(text, ReadingText.prepareSpanish(text))
        assertEquals("Abrí USB.txt y copiá `DNI`.", ReadingText.prepareSpanish("Abrí USB.txt y copiá `DNI`."))
    }

    @Test fun proposesOneSentenceOrAShortPrefixWithoutBreakingWords() {
        assertEquals("¿Venís mañana?", ReadingText.firstPhrase("\n  ¿Venís mañana? Te espero.\nDespués hablamos.", true))
        val word = "🙂saludo"
        val phrase = ReadingText.firstPhrase("$word ".repeat(40), true)
        assertTrue(TextChunks.count(phrase) <= ReadingText.PREVIEW_CHARACTERS)
        assertTrue(phrase.split(' ').all { it == word })
        val longIdentifier = "x".repeat(ReadingText.PREVIEW_CHARACTERS + 1)
        assertEquals(longIdentifier, ReadingText.firstPhrase("$longIdentifier y algo más", true))
    }
}
