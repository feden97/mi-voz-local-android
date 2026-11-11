package com.qwen.tts.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticFormattingTest {
    @Test fun rtfUsesLongSampleCountsAndElapsedSeconds() {
        assertEquals(3.0, DiagnosticFormatting.rtf(300_000, 2_400_000L, 24_000)!!, 0.000001)
        assertEquals(1.0, DiagnosticFormatting.rtf(100_000_000, 2_400_000_000L, 24_000)!!, 0.000001)
    }

    @Test fun unavailableOrInvalidAudioDoesNotProduceMisleadingRtf() {
        assertNull(DiagnosticFormatting.rtf(1_000, 0, 24_000))
        assertNull(DiagnosticFormatting.rtf(-1, 24_000, 24_000))
        assertNull(DiagnosticFormatting.rtf(1_000, 24_000, 0))
    }

    @Test fun technicalLabelsDoNotLeakKnownPrivatePathFormats() {
        assertFalse(DiagnosticFormatting.safeLabel("Failed C:\\Users\\Fede\\voices\\sample.wav").contains("Fede"))
        assertFalse(DiagnosticFormatting.safeLabel("Failed /data/user/0/example/voices/reference.wav").contains("reference.wav"))
        assertEquals("encoder_loaded", DiagnosticFormatting.safeLabel("encoder_loaded"))
        assertEquals(500, DiagnosticFormatting.safeLabel("a".repeat(501)).length)
    }
}
