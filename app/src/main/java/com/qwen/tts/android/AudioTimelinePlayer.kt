package com.qwen.tts.android

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Keeps asynchronous seeks ordered so playback only resumes at the latest requested position. */
internal class AudioTimelinePlayer(private val durationMillis: Long) {
    var ready by mutableStateOf(false)
        private set
    var playing by mutableStateOf(false)
        private set
    var position by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null
    private var seekingTo: Long? = null
    private var queuedSeek: Long? = null
    private var resumeAfterSeek = false
    private var stopAt: Long? = null

    fun open(file: File, initialPosition: Long) {
        try {
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener {
                if (player === it) {
                    ready = true
                    if (initialPosition > 0) seek(initialPosition)
                }
            }
            media.setOnSeekCompleteListener { current ->
                if (player === current) guarded { finishSeek(it) }
            }
            media.setOnCompletionListener { current ->
                if (player === current && seekingTo == null) {
                    playing = false
                    position = durationMillis
                    stopAt = null
                }
            }
            media.setOnErrorListener { current, _, _ ->
                if (player === current) fail()
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            fail()
        }
    }

    fun play() = guarded { media ->
        stopAt = null
        when {
            seekingTo != null -> resumeAfterSeek = true
            position >= durationMillis -> requestSeek(media, 0, resume = true)
            else -> start(media)
        }
    }

    fun pause() = guarded { media ->
        if (playing) {
            media.pause()
            position = media.currentPosition.toLong().coerceIn(0, durationMillis)
        }
        playing = false
        resumeAfterSeek = false
        stopAt = null
    }

    fun seek(millis: Long) = guarded { media ->
        stopAt = null
        requestSeek(media, millis, resume = playing || resumeAfterSeek)
    }

    fun playRange(start: Long, end: Long) = guarded { media ->
        require(start in 0 until end && end <= durationMillis)
        stopAt = end
        requestSeek(media, start, resume = true)
    }

    fun poll() = guarded { media ->
        if (!playing) return@guarded
        position = media.currentPosition.toLong().coerceIn(0, durationMillis)
        val end = stopAt
        if (end != null && position >= end) {
            stopAt = null
            requestSeek(media, end, resume = false)
        }
    }

    fun close() {
        val media = player
        player = null
        ready = false
        playing = false
        seekingTo = null
        queuedSeek = null
        resumeAfterSeek = false
        stopAt = null
        media?.release()
    }

    private fun requestSeek(media: MediaPlayer, millis: Long, resume: Boolean) {
        if (playing) media.pause()
        playing = false
        position = millis.coerceIn(0, durationMillis)
        resumeAfterSeek = resume && position < durationMillis
        if (seekingTo == null) sendSeek(media, position) else queuedSeek = position
    }

    private fun sendSeek(media: MediaPlayer, millis: Long) {
        seekingTo = millis
        media.seekTo(millis, MediaPlayer.SEEK_CLOSEST)
    }

    private fun finishSeek(media: MediaPlayer) {
        val completed = seekingTo
        val queued = queuedSeek
        queuedSeek = null
        if (queued != null && queued != completed) {
            sendSeek(media, queued)
            return
        }
        seekingTo = null
        if (resumeAfterSeek) start(media)
        resumeAfterSeek = false
    }

    private fun start(media: MediaPlayer) {
        media.start()
        playing = true
    }

    private inline fun guarded(action: (MediaPlayer) -> Unit) {
        val media = player ?: return
        if (!ready) return
        try { action(media) } catch (_: Exception) { fail() }
    }

    private fun fail() {
        close()
        error = "No se pudo reproducir este audio."
    }
}
