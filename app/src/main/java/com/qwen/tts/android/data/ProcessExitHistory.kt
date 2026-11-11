package com.qwen.tts.android.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Historical OS records, captured once per process; these do not describe the current job.
 * Android can omit records, including a device reset. SIGNALED/SIGKILL alone does not
 * establish a low-memory kill. PSS/RSS are the OS samples (zero can mean unavailable).
 * Deliberately excludes descriptions, traces, process names and paths.
 */
internal object ProcessExitHistory {
    private var cachedJson: String? = null

    /** Returns an independent JSON array so callers cannot mutate the cached snapshot. */
    @Synchronized
    fun snapshot(context: Context): JSONArray {
        val serialized = cachedJson ?: capture(context.applicationContext).toString().also { cachedJson = it }
        return JSONArray(serialized)
    }

    private fun capture(context: Context): JSONArray = runCatching {
        // getTimestamp() uses wall time, whereas process start is monotonic. This
        // conversion is approximate if the user/system changed the wall clock.
        val processStartEpochMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime() +
            Process.getStartElapsedRealtime()
        val manager = requireNotNull(context.getSystemService(ActivityManager::class.java))
        val records = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
        JSONArray().apply {
            records.asSequence().filter { it.timestamp < processStartEpochMillis }.take(5).forEach { exit ->
                put(JSONObject()
                    .put("reasonCode", exit.reason)
                    .put("reason", ProcessExitReasonLabels.label(exit.reason))
                    .put("timestampMillis", exit.timestamp)
                    .put("status", exit.status)
                    .put("importance", exit.importance)
                    .put("pssKiB", exit.pss)
                    .put("rssKiB", exit.rss))
            }
        }
    }.getOrElse { JSONArray() }
}

/** Stable labels only: never translate a generic signal into an inferred cause. */
internal object ProcessExitReasonLabels {
    fun label(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNRECOGNIZED"
    }
}
