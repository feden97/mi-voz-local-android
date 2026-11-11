package com.qwen.tts.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.qwen.tts.android.data.QwenModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** User-started local audio generation. Never restarts work after a process death. */
class GenerationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var watchdog: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var generationModel: MainViewModel? = null
    private var expectedStop = false
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Generación de voz", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progreso y cancelación de una generación local iniciada por vos"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            GenerationRuntime.existing()?.cancelSynthesis()
            // An obsolete notification must not create a new idle service.
            if (generationModel == null) finishService()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 35) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            // ServiceCompat's API-34 mask strips mediaProcessing (API 35), passing
            // NONE and causing InvalidForegroundServiceTypeException on Android 15+.
            // minSdk is 31, so the platform's typed overload is available throughout.
            // Promote before waiting for state or preparing models.
            startForeground(NOTIFICATION_ID, notification(null), type)
            GenerationRuntime.foregroundServiceActive = true
            generationModel = GenerationRuntime.get(this)
            expectedStop = false
            if (observer?.isActive != true) observeOperation()
        } catch (error: Exception) {
            failAndStop("Android no permitió iniciar la generación en segundo plano: ${error.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun observeOperation() {
        observer = scope.launch {
            try {
                val model = requireNotNull(generationModel)
                // begin() is invoked immediately before the UI starts its job.
                // An initial idle state must not stop the service prematurely.
                val active = withTimeoutOrNull(START_GRACE_MILLIS) {
                    model.uiState.first { it.busy }
                }
                if (active == null) {
                    finishService()
                    return@launch
                }
                acquireCpuWakeLock()
                watchdog = scope.launch {
                    delay(MAX_OPERATION_MILLIS)
                    failAndStop("La generación alcanzó el tiempo máximo permitido en segundo plano; se solicitó cancelar.")
                }
                model.uiState.collect { state ->
                    if (!state.busy) {
                        finishService()
                    } else {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotificationAt >= 1_000L) {
                            lastNotificationAt = now
                            updateNotification(state)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failAndStop("No se pudo mantener la generación en segundo plano: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun acquireCpuWakeLock() {
        val manager = getSystemService(PowerManager::class.java)
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiVozLocal:Generation").apply {
            setReferenceCounted(false)
            acquire(MAX_OPERATION_MILLIS)
        }
    }

    private fun releaseCpuWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    private fun updateNotification(state: QwenTtsUiState) {
        // Notification permission is not required to start an FGS. If denied,
        // Android still exposes the service in its active-apps task manager.
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
        }
    }

    private fun notification(state: QwenTtsUiState?): Notification {
        val open = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 11, Intent(this, GenerationForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val model = state?.let { QwenModel.variantById(it.selectedModelId).displayName }
        val elapsed = state?.operationElapsedMillis?.div(1000L) ?: 0L
        val time = "%d:%02d".format(elapsed / 60L, elapsed % 60L)
        val parts = state?.let { if (it.plannedChunks > 0) "Fragmento ${it.currentChunk.coerceAtLeast(1)}/${it.plannedChunks}" else "Preparando audio" }
            ?: "Preparando generación"
        val text = listOfNotNull(model, parts, time).joinToString(" · ")
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (state?.cancelling == true) "Cancelando generación" else "Mi Voz Local está generando audio")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (state == null || state.canCancel) {
                    addAction(android.R.drawable.ic_media_pause, "Cancelar", cancel)
                }
            }
            .build()
    }

    private fun failAndStop(message: String) {
        (generationModel ?: GenerationRuntime.existing())?.backgroundServiceFailed(message)
        finishService()
    }

    private fun finishService() {
        expectedStop = true
        GenerationRuntime.foregroundServiceActive = false
        releaseCpuWakeLock()
        observer?.cancel()
        observer = null
        watchdog?.cancel()
        watchdog = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @androidx.annotation.RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ grants only a few seconds to stop after its FGS quota.
        failAndStop("Android alcanzó el límite de tiempo en segundo plano; se solicitó cancelar la generación.")
    }

    override fun onDestroy() {
        GenerationRuntime.foregroundServiceActive = false
        if (!expectedStop && generationModel?.uiState?.value?.canCancel == true) {
            generationModel?.backgroundServiceFailed("Android detuvo el servicio de generación; se solicitó cancelar.")
        }
        releaseCpuWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "local-generation"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_CANCEL = "ar.com.mivozlocal.android.CANCEL_GENERATION"
        private const val START_GRACE_MILLIS = 5_000L
        private const val MAX_OPERATION_MILLIS = 6L * 60L * 60L * 1000L

        /** Call from the visible user action, immediately before starting its job. */
        fun begin(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, GenerationForegroundService::class.java))
            true
        } catch (error: Exception) {
            GenerationRuntime.existing()?.backgroundServiceFailed(
                "Android no permitió iniciar el servicio de generación: ${error.javaClass.simpleName}",
            )
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GenerationForegroundService::class.java))
        }
    }
}
