package com.qwen.tts.android.data

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessExitHistoryTest {
    @Test fun signalDoesNotBecomeAnInferredLowMemoryKill() {
        assertEquals("SIGNALED", ProcessExitReasonLabels.label(ApplicationExitInfo.REASON_SIGNALED))
        assertEquals("LOW_MEMORY", ProcessExitReasonLabels.label(ApplicationExitInfo.REASON_LOW_MEMORY))
    }

    @Test fun unknownAndFutureReasonsKeepExplicitLabels() {
        assertEquals("UNKNOWN", ProcessExitReasonLabels.label(ApplicationExitInfo.REASON_UNKNOWN))
        assertEquals("UNRECOGNIZED", ProcessExitReasonLabels.label(Int.MAX_VALUE))
    }
}
