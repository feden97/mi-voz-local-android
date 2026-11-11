package com.qwen.tts.android

import android.app.Application
import android.content.Context

/** The service and UI share one pipeline; Activity recreation must not cancel it. */
object GenerationRuntime {
    @Volatile private var instance: MainViewModel? = null
    @Volatile var isUiVisible: Boolean = false
    @Volatile var foregroundServiceActive: Boolean = false

    fun get(context: Context): MainViewModel = instance ?: synchronized(this) {
        instance ?: MainViewModel(context.applicationContext as Application).also { instance = it }
    }

    fun existing(): MainViewModel? = instance
}
