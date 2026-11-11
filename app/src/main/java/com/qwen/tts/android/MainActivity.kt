package com.qwen.tts.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qwen.tts.android.data.VoiceRecorder
import com.qwen.tts.android.data.AudioImporter
import com.qwen.tts.android.data.RecordingResult
import com.qwen.tts.android.data.ModelFile
import com.qwen.tts.android.data.ModelVariant
import com.qwen.tts.android.data.QwenModel
import com.qwen.tts.android.data.VoicePromptFiles
import com.qwen.tts.android.data.GenerationEstimate
import com.qwen.tts.android.data.TextChunks
import com.qwen.tts.android.data.StreamingWavWriter
import com.qwen.tts.android.data.GenerationDiagnostics
import com.qwen.tts.android.data.db.GenerationEntity
import com.qwen.tts.android.data.db.QwenDatabase
import com.qwen.tts.android.data.db.VoiceProfileEntity
import com.qwen.tts.android.ui.theme.QwenTtsTheme
import com.qwen.tts.studio.engine.QwenEngine
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LanguageOption(val label: String, val id: Int)
private data class BackendOption(val id: String, val label: String, val preference: Int)
private data class GenerationTimeEstimate(val totalMillis: Long, val sampleSize: Int)

private val languageOptions = listOf(
    LanguageOption("Inglés", 2050),
    LanguageOption("Alemán", 2053),
    LanguageOption("Español", 2054),
    LanguageOption("Francés", 2061),
    LanguageOption("Chino", 2055),
    LanguageOption("Japonés", 2058),
    LanguageOption("Coreano", 2064),
    LanguageOption("Ruso", 2069),
)

private val backendOptions = listOf(
    BackendOption("cpu", "CPU", QwenEngine.BACKEND_CPU),
)

private val defaultBackendOption = backendOptions.first { it.id == "cpu" }

data class QwenTtsUiState(
    val text: String = "Hola, esta es una prueba de mi voz generada de forma local en el teléfono.",
    val selectedModelId: String = QwenModel.defaultVariant.id,
    val selectedBackendId: String = defaultBackendOption.id,
    val selectedVoiceId: String? = null,
    val activeBackendName: String? = null,
    val modelReady: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = QwenModel.defaultVariant.totalBytes,
    val status: String = "Modelo sin descargar",
    val error: String? = null,
    val sampleRate: Int = 0,
    val sampleCount: Int = 0,
    val synthesisMillis: Long = 0,
    val playing: Boolean = false,
    val playingGenerationId: Long? = null,
    val operationElapsedMillis: Long = 0,
    val synthesisFrames: Int = 0,
    val maxAudioTokens: Int = 512,
    val cpuThreads: Int = 0,
    val selectedCpuThreads: Int = 0,
    val selectedLanguageId: Int = 2054,
    val tokenizeMillis: Long = 0,
    val encodeMillis: Long = 0,
    val generateMillis: Long = 0,
    val decodeMillis: Long = 0,
    val decodeFrames: Int = 0,
    val decodeSamples: Long = 0,
    val decodeGraphComputeMillis: Long = 0,
    val savedFileName: String? = null,
    val snackbarMessage: String? = null,
    val estimatedSynthesisMillis: Long? = null,
    val estimateSampleCount: Int = 0,
    val canCancel: Boolean = false,
    val cancelling: Boolean = false,
    val etaLowMillis: Long? = null,
    val etaHighMillis: Long? = null,
    val etaIncludesDecode: Boolean = false,
    val etaObservedAtMillis: Long = 0,
    val generationPhase: String = "",
    val resultModelName: String? = null,
    val resultText: String = "",
    val resultBackend: String? = null,
    val resultThreads: Int? = null,
    val resultMode: String? = null,
    val resultStatus: String? = null,
    val resultReportPath: String? = null,
    val resultTotalMillis: Long = 0,
    val resultChunks: Int = 0,
    val plannedChunks: Int = 0,
    val currentChunk: Int = 0,
    val backgroundGenerationEnabled: Boolean = true,
    val supportsCloning: Boolean? = null,
    val speakerEmbeddingDim: Int = 0,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val modelDir = File(application.filesDir, "qwen3-tts-models")
    private val voiceDir = File(application.filesDir, "voices")
    private val generationDir = File(application.filesDir, "generations")
    private val dao = QwenDatabase.getDatabase(application).qwenDao()
    private val recorder = VoiceRecorder()
    private val preferences = application.getSharedPreferences("studio-settings", Application.MODE_PRIVATE)
    private val savedVariant = QwenModel.variantById(preferences.getString("model-id", null).orEmpty())

    val voices = dao.observeVoices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val generations = dao.observeGenerations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recorderState = recorder.state
    private val _voiceTranscripts = MutableStateFlow<Map<String, String>>(emptyMap())
    val voiceTranscripts = _voiceTranscripts.asStateFlow()

    private val _uiState = MutableStateFlow(
        QwenTtsUiState(
            selectedModelId = savedVariant.id,
            selectedBackendId = backendOptionById(preferences.getString("backend-id", null).orEmpty()).id,
            backgroundGenerationEnabled = preferences.getBoolean("background-generation", true),
            modelReady = isModelReady(savedVariant),
            downloadTotalBytes = savedVariant.totalBytes,
        ),
    )
    val uiState = _uiState.asStateFlow()

    @Volatile private var engine: QwenEngine? = null
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var backgroundFailure: String? = null
    private val nativeLock = Any()
    private val cleared = AtomicBoolean(false)
    private var generatedFile: File? = null
    private var playJob: Job? = null
    private var operationTickerJob: Job? = null
    private var activePlayer: MediaPlayer? = null

    init {
        // Discard the experimental GPU selection saved by 0.3.2 on upgrade.
        preferences.edit().putString("backend-id", "cpu").apply()
        val threads = preferredCpuThreadCount()
        _uiState.update { it.copy(selectedCpuThreads = threads, cpuThreads = threads) }
        refreshModelState()
        viewModelScope.launch {
            dao.observeVoices().collect { profiles ->
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        profiles.associate { it.voiceId to VoicePromptFiles.readTranscript(File(it.referenceWavPath)) }
                    }
                }
                result.onSuccess { _voiceTranscripts.value = it }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _uiState.update { it.copy(error = "No se pudieron leer las transcripciones: ${error.message}") }
                }
            }
        }
    }

    fun updateText(value: String) {
        _uiState.update { it.copy(text = value) }
    }

    fun updateBackgroundGeneration(enabled: Boolean) {
        if (_uiState.value.busy) return
        preferences.edit().putBoolean("background-generation", enabled).apply()
        _uiState.update { it.copy(backgroundGenerationEnabled = enabled) }
    }

    fun backgroundServiceFailed(message: String) {
        backgroundFailure = message
        cancelSynthesis()
        _uiState.update { it.copy(error = message, snackbarMessage = message) }
    }

    fun onAppBackgrounded() {
        stopAudio()
        recorder.cancel()
        if (!_uiState.value.backgroundGenerationEnabled) cancelSynthesis()
        viewModelScope.launch {
            uiState.first { !it.busy }
            if (!GenerationRuntime.isUiVisible) releaseMemory()
        }
    }

    fun copyText(text: String) {
        val clipboard = getApplication<Application>().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Texto de Mi Voz Local", text))
        _uiState.update { it.copy(snackbarMessage = "Texto copiado") }
    }

    fun reuseText(text: String): Boolean {
        if (_uiState.value.busy) return false
        updateText(text)
        return true
    }

    fun updateCpuThreads(value: Int) {
        if (_uiState.value.busy || recorderState.value.isRecording || _uiState.value.selectedCpuThreads == value) return
        // GPU schedulers own auxiliary CPU backends configured when the model loads.
        // Recreate the pipeline so the requested thread count applies to those too.
        synchronized(nativeLock) {
            engine?.close()
            engine = null
        }
        _uiState.update { it.copy(selectedCpuThreads = value, cpuThreads = value, loaded = false,
            activeBackendName = null, supportsCloning = null, speakerEmbeddingDim = 0,
            status = "$value hilos seleccionados; cargá el modelo para aplicarlos") }
    }

    fun selectBackend(backendId: String) {
        if (_uiState.value.busy || recorderState.value.isRecording) return
        val option = backendOptionById(backendId)
        if (_uiState.value.selectedBackendId == option.id) return
        stopAudio()
        synchronized(nativeLock) {
            engine?.close()
            engine = null
        }
        preferences.edit().putString("backend-id", option.id).apply()
        _uiState.update { it.copy(selectedBackendId = option.id, loaded = false, activeBackendName = null,
            supportsCloning = null, speakerEmbeddingDim = 0, error = null,
            estimatedSynthesisMillis = null, estimateSampleCount = 0,
            status = "${option.label} seleccionado; cargá el modelo para comprobarlo") }
    }

    fun updateLanguage(languageId: Int) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(selectedLanguageId = languageId) }
    }

    fun selectVoice(voiceId: String?) {
        _uiState.update { it.copy(selectedVoiceId = voiceId) }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun selectModel(modelId: String) {
        if (_uiState.value.busy || recorderState.value.isRecording || _uiState.value.selectedModelId == modelId) return
        val variant = QwenModel.variantById(modelId)
        stopAudio()
        synchronized(nativeLock) {
            engine?.close()
            engine = null
        }
        preferences.edit().putString("model-id", variant.id).apply()
        val ready = isModelReady(variant)
        _uiState.update {
            it.copy(
                selectedModelId = variant.id,
                activeBackendName = null,
                modelReady = ready,
                loaded = false,
                status = if (ready) "Modelo listo" else "Modelo sin descargar",
                error = null,
                downloadProgress = 0f,
                downloadBytes = 0L,
                downloadTotalBytes = variant.totalBytes,
                synthesisFrames = 0,
                tokenizeMillis = 0,
                encodeMillis = 0,
                generateMillis = 0,
                decodeMillis = 0,
                decodeFrames = 0,
                decodeSamples = 0,
                decodeGraphComputeMillis = 0,
                savedFileName = null,
                estimatedSynthesisMillis = null,
                estimateSampleCount = 0,
                supportsCloning = null,
                speakerEmbeddingDim = 0,
            )
        }
    }

    fun downloadModel() {
        if (_uiState.value.busy || recorderState.value.isRecording) return
        synchronized(nativeLock) {
            engine?.close()
            engine = null
        }
        _uiState.update { it.copy(loaded = false) }
        viewModelScope.launch {
            val variant = selectedVariant()
            startBusy(status = "Preparando descarga del modelo", downloading = true)
            _uiState.update {
                it.copy(downloadProgress = 0f, downloadBytes = 0L, downloadTotalBytes = variant.totalBytes)
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    modelDir.mkdirs()
                    downloadFiles(variant)
                }
            }

            result.fold(
                onSuccess = {
                    stopBusyTicker()
                    refreshModelState("Modelo listo")
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            downloading = false,
                            status = "Falló la descarga",
                            error = throwable.message ?: "Falló la descarga",
                        )
                    }
                },
            )
        }
    }

    fun loadModel() {
        if (!_uiState.value.modelReady || _uiState.value.loaded || _uiState.value.busy || recorderState.value.isRecording) return
        viewModelScope.launch {
            startBusy(status = "Cargando modelo local")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    withEngine(selectedVariant()) { native ->
                        Triple(native.getActiveBackendName().orEmpty(), native.getCpuThreads(), native.getModelCapabilities())
                    }
                }
            }

            result.fold(
                onSuccess = { (backend, threads, caps) ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = if (backend.isBlank()) "Modelo cargado" else "Modelo cargado en $backend",
                            activeBackendName = backend.ifBlank { null },
                            cpuThreads = threads,
                            supportsCloning = caps?.supportsCloning,
                            speakerEmbeddingDim = caps?.speakerEmbeddingDim ?: 0,
                        )
                    }
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = false,
                            status = "Falló la carga",
                            error = throwable.message ?: "Falló la carga",
                        )
                    }
                },
            )
        }
    }

    fun synthesize() {
        val text = _uiState.value.text.trim()
        if (text.isEmpty() || _uiState.value.busy || recorderState.value.isRecording) return
        val chunks = runCatching { TextChunks.split(text).filter { it.isNotBlank() } }.getOrElse { error ->
            _uiState.update { it.copy(error = error.message) }; return
        }
        val variant = selectedVariant()
        val selectedVoice = _uiState.value.selectedVoiceId?.let { id -> voices.value.firstOrNull { it.voiceId == id } }
        val language = _uiState.value.selectedLanguageId
        val threads = selectedCpuThreadCount()
        val requestedBackend = selectedBackendOption()
        stopAudio()
        backgroundFailure = null
        startBusy(status = "Preparando generación por fragmentos", resetSynthesis = true)
        val background = _uiState.value.backgroundGenerationEnabled
        _uiState.update { it.copy(plannedChunks = chunks.size, currentChunk = 0, resultModelName = variant.displayName,
            resultBackend = null, resultThreads = threads, resultMode = null, resultReportPath = null, resultStatus = null, resultText = text) }
        if (background && !GenerationForegroundService.begin(getApplication())) {
            stopBusyTicker()
            _uiState.update { it.copy(busy = false, canCancel = false, status = "No se pudo iniciar el servicio; desactivá segundo plano para generar en pantalla.") }
            return
        }
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            var completed = 0
            var nativeMillis = 0L
            var sampleCount = 0L
            var problem: Throwable? = null
            var truncated = false
            var backend = "Sin confirmar"
            var mode = "default"
            var reportFile: File? = null
            var saved: GenerationEntity? = null
            var status = "failed"
            var diagnostics: GenerationDiagnostics? = null
            generationDir.mkdirs()
            val outputFile = File(generationDir, "generation-${UUID.randomUUID()}.wav")
            withContext(Dispatchers.IO) {
                var writer: StreamingWavWriter? = null
                try {
                    val transcript = selectedVoice?.let { VoicePromptFiles.readTranscript(File(it.referenceWavPath)) }.orEmpty()
                    mode = if (transcript.isNotBlank()) "icl" else if (selectedVoice != null) "timbre" else "default"
                    diagnostics = GenerationDiagnostics(getApplication(), variant.id, variant.displayName, backend, threads,
                        mode, language, TextChunks.count(text), chunks.size,
                        visibilityProvider = { GenerationRuntime.isUiVisible },
                        foregroundServiceProvider = { GenerationRuntime.foregroundServiceActive },
                        requestedBackend = requestedBackend.id)
                    val diagnostic = requireNotNull(diagnostics)
                    diagnostic.noteExecution(background)
                    diagnostic.capture("before_preparation", true)
                    runCatching { diagnostic.checkpoint() }.onFailure {
                        diagnostic.warning("No se pudo guardar el registro inicial en disco.")
                    }
                    diagnostic.startSampling()
                    val sink = StreamingWavWriter(outputFile)
                    writer = sink
                    withEngineContext { native ->
                        try {
                            val preparation = SystemClock.elapsedRealtime()
                            val iclPrompt = selectedVoice?.takeIf { transcript.isNotBlank() }?.let {
                                prepareIclPrompt(native, variant, File(it.referenceWavPath), transcript)
                            }
                            throwIfCancelRequested(native)
                            _uiState.update { it.copy(generationPhase = "Cargando modelo") }
                            ensureEngineLoaded(variant)
                            throwIfCancelRequested(native)
                            backend = native.getActiveBackendName().orEmpty().ifBlank { "Sin confirmar" }
                            diagnostic.setBackend(backend)
                            if (requestedBackend.id == "gpu" && backend.startsWith("CPU", ignoreCase = true)) {
                                diagnostic.warning("Se solicitó GPU, pero el motor informó CPU como backend efectivo.")
                            }
                            diagnostic.stage("preparation_and_load", SystemClock.elapsedRealtime() - preparation)
                            diagnostic.capture("model_ready", true)
                            _uiState.update { it.copy(resultBackend = backend, resultMode = mode) }
                            val embedding = if (iclPrompt == null) selectedVoice?.let { speakerEmbeddingFor(native, it, variant) } else null
                            val key = "eta-v3-segmented-${variant.id}-$backend-$threads-$mode-$language"
                            var rates = preferences.getString("$key-rates", "").orEmpty().split(',').mapNotNull { it.toDoubleOrNull() }
                            var tails = preferences.getString("$key-tails", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
                            for ((index, chunk) in chunks.withIndex()) {
                                throwIfCancelRequested(native)
                                val chunkStart = SystemClock.elapsedRealtime()
                                var firstAt = 0L
                                var firstFrame = 0
                                var lastAt = 0L
                                var framesSeen = 0
                                var decodeStartedAt: Long? = null
                                _uiState.update { it.copy(currentChunk = index + 1, synthesisFrames = 0,
                                    generationPhase = "Generando fragmento ${index + 1}/${chunks.size}", etaLowMillis = null, etaHighMillis = null) }
                                native.setProgressCallback { frames, maxTokens ->
                                    val now = SystemClock.elapsedRealtime()
                                    if (firstAt == 0L && frames > 0) { firstAt = now; firstFrame = frames }
                                    if (frames > 0) { framesSeen = frames; lastAt = now }
                                    val decoding = maxTokens == -1
                                    if (decoding && decodeStartedAt == null) decodeStartedAt = now
                                    val estimate = if (!decoding) GenerationEstimate.remaining(TextChunks.count(chunk), frames,
                                        now - firstAt, frames - firstFrame, rates, tails) else null
                                    diagnostic.setPhase(if (decoding) "decode" else "generate")
                                    _uiState.update { current -> current.copy(
                                        status = if (cancelRequested.get()) "Cancelación solicitada; esperando al motor" else "Generando audio",
                                        generationPhase = if (decoding) "Decodificando fragmento ${index + 1}/${chunks.size}" else "Generando fragmento ${index + 1}/${chunks.size}",
                                        etaLowMillis = if (decoding) tails.minOrNull()?.times(0.75)?.toLong() else estimate?.lowMillis,
                                        etaHighMillis = if (decoding) tails.maxOrNull()?.times(1.5)?.toLong() else estimate?.highMillis,
                                        etaIncludesDecode = tails.isNotEmpty(), etaObservedAtMillis = current.operationElapsedMillis,
                                        synthesisFrames = frames, maxAudioTokens = if (maxTokens >= 0) maxTokens else current.maxAudioTokens) }
                                }
                                val params = QwenEngine.NativeParams(languageId = language)
                                val chunkText = chunk.trim()
                                val nativeCallStartedAt = SystemClock.elapsedRealtime()
                                val result = if (iclPrompt != null) native.synthesizeWithIclPrompt(chunkText, iclPrompt.absolutePath, params)
                                    else native.synthesize(chunkText, speakerEmbeddingPath = embedding, params = params)
                                val nativeCallReturnedAt = SystemClock.elapsedRealtime()
                                throwIfCancelRequested(native)
                                val audio = result.audio
                                check(result.success && audio != null && audio.isNotEmpty()) { result.errorMsg ?: "El motor no produjo audio." }
                                check(result.sampleRate == 24_000) { "Frecuencia de audio inesperada." }
                                truncated = framesSeen >= params.maxAudioTokens || audio.size >= params.maxAudioTokens * 1920
                                sink.append(audio, result.sampleRate)
                                completed += 1
                                nativeMillis += result.timeMs
                                diagnostic.chunk(index + 1, TextChunks.count(chunk), result.timeMs, audio.size, result.sampleRate,
                                    framesSeen, if (truncated) "acoustic_token_limit_reached" else null,
                                    timings = mapOf("tokenize" to result.tokenizeMs, "encode" to result.encodeMs,
                                        "generate" to result.generateMs, "decode" to result.decodeMs,
                                        "decodeGraphCompute" to result.decodeGraphComputeMs),
                                    observedTimings = mapOf("nativeCall" to (nativeCallReturnedAt - nativeCallStartedAt),
                                        "beforeDecodeEvent" to decodeStartedAt?.let { it - nativeCallStartedAt },
                                        "afterDecodeEvent" to decodeStartedAt?.let { nativeCallReturnedAt - it }))
                                diagnostic.checkpoint()
                                if (truncated) { diagnostic.warning("Se alcanzó el límite acústico; se detuvo el trabajo para evitar omisiones silenciosas."); break }
                                val duration = audio.size.toDouble() / result.sampleRate
                                rates = (rates + TextChunks.count(chunk) / duration).takeLast(5)
                                if (lastAt > chunkStart) tails = (tails + (SystemClock.elapsedRealtime() - lastAt).coerceAtLeast(0)).takeLast(5)
                                preferences.edit().putString("$key-rates", rates.joinToString(",")).putString("$key-tails", tails.joinToString(",")).apply()
                                diagnostic.capture("chunk_saved", true)
                            }
                        } finally {
                            native.setProgressCallback(null)
                            _uiState.update { it.copy(generationPhase = "Liberando memoria del modelo") }
                            native.close()
                            engine = null
                            _uiState.update { it.copy(loaded = false, activeBackendName = null) }
                        }
                    }
                } catch (error: Throwable) {
                    problem = error
                } finally {
                    sampleCount = writer?.sampleCount ?: 0L
                    runCatching { writer?.close() }.onFailure { if (problem == null) problem = it }
                    _uiState.update { it.copy(canCancel = false, cancelling = false, generationPhase = "Guardando resultado y registro") }
                    status = when {
                        truncated -> "truncated"
                        completed == chunks.size -> "completed"
                        completed > 0 -> "partial"
                        cancelRequested.get() || problem is CancellationException -> "cancelled"
                        else -> "failed"
                    }
                    diagnostics?.let { diagnostic ->
                        diagnostic.stopSampling()
                        diagnostic.capture("after_native_release", true)
                        backgroundFailure?.let { diagnostic.warning(it); diagnostic.event("background_service_interrupted") }
                        diagnostic.finish(status, SystemClock.elapsedRealtime() - started, sampleCount, 24_000, problem?.javaClass?.simpleName)
                        runCatching { diagnostic.writeTo(generationDir) }.onSuccess { reportFile = it }
                    }
                    if (completed > 0 && sampleCount > 0) {
                        val entity = GenerationEntity(text = text, voiceId = selectedVoice?.voiceId,
                            voiceName = selectedVoice?.name ?: "Voz predeterminada", wavPath = outputFile.absolutePath,
                            sampleRate = 24_000, sampleCount = Math.toIntExact(sampleCount), synthesisMillis = nativeMillis,
                            modelId = variant.id, modelName = variant.displayName, backend = backend, cpuThreads = threads,
                            cloningMode = mode, reportPath = reportFile?.absolutePath, totalMillis = SystemClock.elapsedRealtime() - started,
                            chunkCount = chunks.size, completedChunks = completed, status = status)
                        runCatching { dao.insertGeneration(entity) }.onSuccess { id -> saved = entity.copy(generationId = id) }
                            .onFailure { problem = it }
                    } else outputFile.delete()
                }
            }
            stopBusyTicker()
            reportFile?.let { preferences.edit().putString("last-report", it.absolutePath).apply() }
            generatedFile = if (completed > 0 && outputFile.isFile) outputFile else null
            val complete = status == "completed"
            _uiState.update { it.copy(busy = false, loaded = false, canCancel = false, cancelling = false,
                status = when { complete && saved != null -> "Audio completo y guardado; memoria del modelo liberada"
                    completed > 0 -> "Audio parcial conservado ($completed/${chunks.size} fragmentos); memoria liberada"
                    status == "cancelled" -> "Generación cancelada; registro disponible"
                    else -> "Falló la generación; registro disponible si pudo guardarse" },
                error = backgroundFailure ?: if (problem != null && problem !is CancellationException && !cancelRequested.get()) problem?.message else null,
                sampleCount = if (generatedFile != null) Math.toIntExact(sampleCount) else 0, sampleRate = 24_000,
                synthesisMillis = nativeMillis, generationPhase = "", etaLowMillis = null, etaHighMillis = null,
                resultModelName = variant.displayName, resultBackend = backend, resultThreads = threads, resultMode = mode,
                resultStatus = status, resultReportPath = reportFile?.absolutePath, resultTotalMillis = SystemClock.elapsedRealtime() - started,
                resultChunks = completed, plannedChunks = chunks.size, currentChunk = completed, resultText = text,
                playingGenerationId = null) }
            GenerationForegroundService.stop(getApplication())
            if (complete && generatedFile != null && GenerationRuntime.isUiVisible) playAudio()
        }
    }

    fun cancelSynthesis() {
        if (!_uiState.value.canCancel || !_uiState.value.busy || !cancelRequested.compareAndSet(false, true)) return
        _uiState.update { it.copy(cancelling = true, status = "Cancelación solicitada; esperando al motor") }
        // Global atomic signal: never wait for the monitor held by synchronous inference.
        engine?.requestCancel()
    }

    private fun throwIfCancelRequested(native: QwenEngine) {
        if (cancelRequested.get()) {
            native.requestCancel()
            throw CancellationException("Generación cancelada")
        }
    }

    private fun finishCancelled() {
        stopBusyTicker()
        generatedFile = null
        _uiState.update { it.copy(busy = false, canCancel = false, cancelling = false,
            status = "Generación cancelada; no se guardó audio incompleto", error = null,
            sampleCount = 0, etaLowMillis = null, etaHighMillis = null, generationPhase = "") }
    }

    fun startVoiceRecording() {
        if (_uiState.value.busy || !_uiState.value.modelReady || recorderState.value.isRecording) return
        val file = File(voiceDir, "pending-${System.currentTimeMillis()}.wav")
        if (!recorder.start(file)) {
            _uiState.update { it.copy(error = "No se pudo iniciar el micrófono") }
        }
    }

    fun stopVoiceRecordingAndCreate(name: String, referenceText: String = "") {
        if (_uiState.value.busy) return
        val result = recorder.stop() ?: return
        createVoice(name, referenceText, "Creando perfil de voz") { result }
    }

    fun importVoice(uri: Uri, name: String, referenceText: String = "") {
        if (_uiState.value.busy || !_uiState.value.modelReady || recorderState.value.isRecording) return
        createVoice(name, referenceText, "Importando audio y creando perfil") {
            val pending = File(voiceDir, "import-${UUID.randomUUID()}.wav")
            AudioImporter.importAudio(getApplication(), uri, pending)
        }
    }

    private fun createVoice(name: String, referenceText: String, status: String, source: suspend () -> RecordingResult) {
        val trimmedName = name.trim().ifBlank { "Voz ${voices.value.size + 1}" }
        startBusy(status = status)
        viewModelScope.launch {
            val created = runCatching {
                withContext(Dispatchers.IO) {
                    val voiceId = "voice-${UUID.randomUUID()}"
                    val targetDir = File(voiceDir, voiceId).apply { mkdirs() }
                    var input: RecordingResult? = null
                    try {
                        input = source()
                        require(input.durationMillis in 3_000L..30_100L) {
                            "La referencia debe durar entre 3 y 30 segundos; se recomiendan 10 a 15 segundos de voz limpia."
                        }
                        val reference = File(targetDir, "reference.wav")
                        input.file.copyTo(reference, overwrite = true)
                        val transcript = VoicePromptFiles.saveTranscript(reference, referenceText)
                        val variant = selectedVariant()
                        val embedding = File(targetDir, "speaker-${variant.id}.json")
                        withEngineContext { native ->
                          try {
                            loadReferenceEncoder(native, variant)
                            check(native.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)) {
                                native.getLastError() ?: "No se pudo extraer el perfil de voz."
                            }
                            if (transcript.isNotBlank()) {
                                prepareIclPrompt(native, variant, reference, transcript, encoderReady = true)
                            }
                          } finally {
                            native.close()
                            engine = null
                            _uiState.update { it.copy(loaded = false, activeBackendName = null) }
                          }
                        }
                        val voice = VoiceProfileEntity(
                            voiceId = voiceId,
                            name = makeUniqueVoiceName(trimmedName),
                            referenceWavPath = reference.absolutePath,
                            speakerEmbeddingPath = embedding.absolutePath,
                            durationMillis = input.durationMillis,
                        )
                        dao.insertVoice(voice)
                        voice
                    } catch (error: Throwable) {
                        targetDir.deleteRecursively()
                        throw error
                    } finally {
                        input?.file?.delete()
                    }
                }
            }

            created.fold(
                onSuccess = { voice ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            selectedVoiceId = voice.voiceId,
                            status = "Voz creada",
                            snackbarMessage = "Perfil guardado y seleccionado",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    if (throwable is CancellationException) throw throwable
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "No se pudo crear la voz",
                            error = throwable.message ?: "No se pudo crear la voz",
                        )
                    }
                },
            )
        }
    }

    fun cancelVoiceRecording() {
        recorder.cancel()
    }

    fun saveVoiceTranscript(voice: VoiceProfileEntity, text: String) {
        if (_uiState.value.busy || recorderState.value.isRecording) return
        startBusy("Guardando transcripción")
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { VoicePromptFiles.saveTranscript(File(voice.referenceWavPath), text) }
            }
            stopBusyTicker()
            result.onSuccess { saved ->
                _voiceTranscripts.update { it + (voice.voiceId to saved) }
                _uiState.update { it.copy(busy = false, status = "Transcripción guardada", error = null,
                    snackbarMessage = if (saved.isBlank()) "Modo solo timbre: sin transcripción" else "ICL activado; se preparará al generar") }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update { it.copy(busy = false, status = "No se pudo guardar la transcripción", error = error.message) }
            }
        }
    }

    fun deleteVoice(voice: VoiceProfileEntity) {
        if (_uiState.value.busy || recorderState.value.isRecording) return
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteVoice(voice)
            runCatching { File(voice.referenceWavPath).parentFile?.deleteRecursively() }
            if (_uiState.value.selectedVoiceId == voice.voiceId) {
                _uiState.update { it.copy(selectedVoiceId = null) }
            }
        }
    }

    fun playGeneration(generationId: Long) {
        if (_uiState.value.playing && _uiState.value.playingGenerationId == generationId) { stopAudio(); return }
        viewModelScope.launch {
            val generation = withContext(Dispatchers.IO) { dao.getGeneration(generationId) } ?: return@launch
            generatedFile = File(generation.wavPath)
            _uiState.update { it.copy(sampleRate = generation.sampleRate, sampleCount = generation.sampleCount,
                synthesisMillis = generation.synthesisMillis, resultModelName = generation.modelName,
                resultBackend = generation.backend, resultThreads = generation.cpuThreads, resultMode = generation.cloningMode,
                resultStatus = generation.status, resultReportPath = generation.reportPath, resultText = generation.text,
                resultTotalMillis = generation.totalMillis ?: generation.synthesisMillis,
                resultChunks = generation.completedChunks ?: 0, plannedChunks = generation.chunkCount ?: 0,
                status = "Audio del historial", error = null) }
            playAudio(generationId)
        }
    }

    fun deleteGeneration(generation: GenerationEntity) {
        if (_uiState.value.playingGenerationId == generation.generationId) stopAudio()
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteGeneration(generation)
            runCatching { File(generation.wavPath).delete() }
            generation.reportPath?.let { path -> runCatching { File(path).delete(); File(path).resolveSibling(File(path).nameWithoutExtension + ".txt").delete() } }
        }
    }

    fun playAudio(historyGenerationId: Long? = null) {
        val file = generatedFile ?: return
        stopAudio()
        val player = MediaPlayer()
        activePlayer = player
        try {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { current ->
                if (activePlayer === current) { current.start(); _uiState.update { it.copy(playing = true, playingGenerationId = historyGenerationId) } }
            }
            player.setOnCompletionListener { current -> if (activePlayer === current) stopAudio() }
            player.setOnErrorListener { current, _, _ ->
                if (activePlayer === current) { stopAudio(); _uiState.update { it.copy(error = "No se pudo reproducir el archivo.") } }; true
            }
            player.prepareAsync()
        } catch (error: Exception) {
            stopAudio(); _uiState.update { it.copy(error = error.message ?: "No se pudo abrir el audio") }
        }
    }

    fun stopAudio() {
        playJob?.cancel(); playJob = null
        activePlayer?.let { player -> runCatching { player.setOnPreparedListener(null); player.setOnCompletionListener(null); player.setOnErrorListener(null); player.release() } }
        activePlayer = null
        _uiState.update { it.copy(playing = false, playingGenerationId = null) }
    }

    fun releaseMemory() {
        if (_uiState.value.busy) return
        if (engine == null) return
        startBusy("Liberando memoria del modelo")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                synchronized(nativeLock) {
                    engine?.close(); engine = null
                    _uiState.update { it.copy(loaded = false, activeBackendName = null) }
                }
            }
            stopBusyTicker()
            _uiState.update { it.copy(busy = false, loaded = false, activeBackendName = null, status = "Memoria del modelo liberada") }
        }
    }

    fun exportReportToUri(path: String?, uri: Uri, asText: Boolean) {
        if (path == null) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val report = File(path)
                    val text = if (asText) GenerationDiagnostics.renderText(report) else GenerationDiagnostics.exportableJson(report)
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("No se pudo abrir el destino")
                }
            }.onSuccess { _uiState.update { it.copy(snackbarMessage = "Registro exportado. No incluye el guion, la referencia ni el audio.") } }
                .onFailure { error -> _uiState.update { it.copy(error = error.message ?: "No se pudo exportar el registro") } }
        }
    }

    fun latestReportPath(): String? = File(getApplication<Application>().filesDir, "diagnostics/latest.json")
        .takeIf { it.isFile }?.absolutePath

    fun suggestedExportFileName(generation: GenerationEntity): String {
        val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
        return "qwen3-tts-export-${LocalDateTime.now().format(formatter)}-${generation.generationId}.wav"
    }

    fun exportGenerationToUri(generation: GenerationEntity, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)?.use { output ->
                        File(generation.wavPath).inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: error("No se pudo abrir el destino")
                }
            }
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            savedFileName = null,
                            snackbarMessage = "Exportación completa",
                            status = "WAV exportado",
                            error = null,
                        )
                    }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            snackbarMessage = throwable.message ?: "No se pudo exportar WAV",
                            error = null,
                        )
                    }
                },
            )
        }
    }

    private inline fun <T> withEngineContext(block: (QwenEngine) -> T): T =
        synchronized(nativeLock) {
            check(!cleared.get()) { "La sesión del motor terminó." }
            val native = engine ?: QwenEngine().also { engine = it }
            try {
                native.resetCancellation()
                throwIfCancelRequested(native)
                block(native)
            } finally {
                if (cancelRequested.get()) {
                    // A cancelled GGML graph can leave KV/scheduler state incomplete.
                    // Free only after native execution has returned and while holding the monitor.
                    native.close()
                    engine = null
                    _uiState.update { it.copy(loaded = false, activeBackendName = null) }
                }
            }
        }

    private inline fun <T> withEngine(variant: ModelVariant, block: (QwenEngine) -> T): T =
        withEngineContext { block(ensureEngineLoaded(variant)) }

    private fun loadReferenceEncoder(native: QwenEngine, variant: ModelVariant) {
        variant.files.forEach { file ->
            check(isVerifiedModelFile(File(modelDirectory(variant), file.name), file)) {
                "El modelo no está verificado. Descargalo o reparalo en Ajustes."
            }
        }
        configureBackend(native)
        native.setCpuThreads(selectedCpuThreadCount())
        // This call unloads the transformer/decoder; ensureEngineLoaded must run before synthesis.
        _uiState.update { it.copy(loaded = false, supportsCloning = null, status = "Cargando encoder de referencia") }
        check(native.loadIclPromptEncoder(modelDirectory(variant).absolutePath, variant.talkerName)) {
            native.getLastError() ?: "No se pudo cargar el encoder de referencia."
        }
    }

    private fun prepareIclPrompt(
        native: QwenEngine,
        variant: ModelVariant,
        reference: File,
        transcript: String,
        encoderReady: Boolean = false,
    ): File {
        throwIfCancelRequested(native)
        _uiState.update { it.copy(generationPhase = "Preparando la voz de referencia") }
        val prompt = VoicePromptFiles.promptFile(reference, variant.id, transcript)
        if (prompt.isFile && prompt.length() > 0L) return prompt
        if (!encoderReady) loadReferenceEncoder(native, variant)
        _uiState.update { it.copy(status = "Preparando ICL: audio y transcripción") }
        val pending = File(prompt.parentFile, ".${prompt.name}.${UUID.randomUUID()}.tmp")
        try {
            check(native.extractIclPrompt(reference.absolutePath, transcript, pending.absolutePath)) {
                native.getLastError() ?: "No se pudo crear el prompt ICL. Revisá la transcripción y el audio."
            }
            check(pending.isFile && pending.length() > 0L) { "El prompt ICL quedó vacío." }
            check(pending.renameTo(prompt)) { "No se pudo guardar el prompt ICL." }
        } finally {
            pending.delete()
        }
        return prompt
    }

    private fun ensureEngineLoaded(variant: ModelVariant): QwenEngine {
        if (!_uiState.value.loaded) {
            variant.files.forEach { file ->
                check(isVerifiedModelFile(File(modelDirectory(variant), file.name), file)) {
                    "El archivo ${file.name} no coincide con el modelo esperado. Volvé a descargarlo en Ajustes."
                }
            }
        }
        val native = engine ?: QwenEngine().also { engine = it }
        configureBackend(native)
        val threads = selectedCpuThreadCount()
        native.setCpuThreads(threads)
        if (!_uiState.value.loaded) {
            if (!native.loadModels(modelDirectory(variant).absolutePath, variant.talkerName)) {
                val reason = native.getLastError() ?: "No se pudo cargar el modelo local"
                error(if (selectedBackendOption().id == "gpu") "$reason. Podés volver a CPU en Ajustes → Rendimiento." else reason)
            }
            val caps = native.getModelCapabilities()
            _uiState.update {
                it.copy(
                    loaded = true,
                    cpuThreads = native.getCpuThreads(),
                    activeBackendName = native.getActiveBackendName()?.takeIf { name -> name.isNotBlank() },
                    supportsCloning = caps?.supportsCloning,
                    speakerEmbeddingDim = caps?.speakerEmbeddingDim ?: 0,
                )
            }
        }
        return native
    }

    /** Embeddings can have different dimensions across 0.6B and 1.7B; reuse the WAV, not another model's embedding. */
    private fun speakerEmbeddingFor(native: QwenEngine, voice: VoiceProfileEntity, variant: ModelVariant): String {
        val reference = File(voice.referenceWavPath)
        check(reference.isFile) { "No se encuentra la referencia de ${voice.name}. Volvé a importar esa voz." }
        val embedding = File(reference.parentFile, "speaker-${variant.id}.json")
        if (!embedding.isFile || embedding.length() == 0L) {
            val success = native.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)
            if (!success) {
                embedding.delete()
                error(native.getLastError() ?: "No se pudo preparar ${voice.name} para ${variant.displayName}")
            }
        }
        return embedding.absolutePath
    }

    private fun preferredCpuThreadCount(): Int {
        val available = Runtime.getRuntime().availableProcessors()
        return when {
            available >= 8 -> 4
            available >= 4 -> 4
            else -> available.coerceAtLeast(1)
        }
    }

    private fun selectedCpuThreadCount(): Int =
        _uiState.value.selectedCpuThreads.takeIf { it > 0 } ?: preferredCpuThreadCount()

    private fun selectedBackendOption(): BackendOption =
        backendOptionById(_uiState.value.selectedBackendId)

    private fun configureBackend(native: QwenEngine) {
        val backend = selectedBackendOption()
        check(backend.id != "gpu" || (native.getCompiledBackendMask() and 2) != 0) {
            "Esta APK no incluye el motor GPU. Elegí CPU en Ajustes."
        }
        check(native.setBackendPreference(backend.preference)) {
            "No se pudo seleccionar ${backend.label}. Liberá el modelo y volvé a cargarlo; también podés elegir CPU en Ajustes."
        }
    }

    private fun startBusy(
        status: String,
        downloading: Boolean = false,
        resetSynthesis: Boolean = false,
        synthesisEstimate: GenerationTimeEstimate? = null,
    ) {
        operationTickerJob?.cancel()
        cancelRequested.set(false)
        val start = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                busy = true,
                canCancel = resetSynthesis,
                cancelling = false,
                etaLowMillis = null,
                etaHighMillis = null,
                generationPhase = if (resetSynthesis) "Preparando generación" else "",
                downloading = downloading,
                error = null,
                status = status,
                operationElapsedMillis = 0L,
                synthesisFrames = if (resetSynthesis) 0 else it.synthesisFrames,
                sampleCount = if (resetSynthesis) 0 else it.sampleCount,
                synthesisMillis = if (resetSynthesis) 0 else it.synthesisMillis,
                tokenizeMillis = if (resetSynthesis) 0 else it.tokenizeMillis,
                encodeMillis = if (resetSynthesis) 0 else it.encodeMillis,
                generateMillis = if (resetSynthesis) 0 else it.generateMillis,
                decodeMillis = if (resetSynthesis) 0 else it.decodeMillis,
                decodeFrames = if (resetSynthesis) 0 else it.decodeFrames,
                decodeSamples = if (resetSynthesis) 0 else it.decodeSamples,
                decodeGraphComputeMillis = if (resetSynthesis) 0 else it.decodeGraphComputeMillis,
                savedFileName = if (resetSynthesis) null else it.savedFileName,
                estimatedSynthesisMillis = synthesisEstimate?.totalMillis ?: if (resetSynthesis) null else it.estimatedSynthesisMillis,
                estimateSampleCount = synthesisEstimate?.sampleSize ?: if (resetSynthesis) 0 else it.estimateSampleCount,
            )
        }
        operationTickerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(250L)
                val elapsed = SystemClock.elapsedRealtime() - start
                _uiState.update { current ->
                    if (current.busy) current.copy(operationElapsedMillis = elapsed) else current
                }
            }
        }
    }

    private fun stopBusyTicker() {
        operationTickerJob?.cancel()
        operationTickerJob = null
    }

    private fun refreshModelState(status: String? = null) {
        val variant = selectedVariant()
        val ready = isModelReady(variant)
        stopBusyTicker()
        _uiState.update {
            it.copy(
                modelReady = ready,
                busy = false,
                downloading = false,
                downloadTotalBytes = variant.totalBytes,
                status = status ?: if (ready) "Modelo listo" else "Modelo sin descargar",
            )
        }
    }

    private fun selectedVariant(): ModelVariant =
        QwenModel.variantById(_uiState.value.selectedModelId)

    // The native runtime picks the highest-precision tokenizer present in its directory.
    // Keep precision groups separate, sharing each group's tokenizer across the two talkers.
    private fun modelDirectory(variant: ModelVariant): File = File(modelDir, variant.storageGroup)

    private fun isModelReady(variant: ModelVariant): Boolean =
        variant.files.all { file ->
            val local = File(modelDirectory(variant), file.name)
            local.isFile && local.length() == file.sizeBytes
        }

    private fun isVerifiedModelFile(local: File, expected: ModelFile): Boolean {
        if (!local.isFile || local.length() != expected.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        local.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return actual == expected.sha256
    }

    private fun downloadFiles(variant: ModelVariant) {
        val directory = modelDirectory(variant).apply { mkdirs() }
        var completedBytes = 0L
        variant.files.forEachIndexed { index, file ->
            val target = File(directory, file.name)
            if (isVerifiedModelFile(target, file)) {
                completedBytes += file.sizeBytes
                return@forEachIndexed
            }
            check(directory.usableSpace >= file.sizeBytes + 64L * 1024 * 1024) {
                "Falta espacio para ${file.name}. Liberá al menos ${formatBytes(file.sizeBytes)} y volvé a intentar."
            }
            val url = file.url
            val temp = File(directory, "${file.name}.download")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            try {
              check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                  "Error HTTP ${connection.responseCode} al descargar ${file.name}"
              }
              connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var fileBytes = 0L
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        fileBytes += read
                        check(fileBytes <= file.sizeBytes) { "La descarga supera el tamaño esperado" }
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250L) {
                            lastUpdate = now
                            val total = completedBytes + fileBytes
                            _uiState.update {
                                it.copy(
                                    downloadBytes = total,
                                    downloadProgress = total.toFloat() / variant.totalBytes.toFloat(),
                                    status = "Descargando ${index + 1}/${variant.files.size}: ${file.name}",
                                )
                            }
                        }
                    }
                }
              }
              check(isVerifiedModelFile(temp, file)) { "Descarga incompleta o SHA-256 incorrecto: ${file.name}" }
              check(!target.exists() || target.delete()) { "No se pudo reemplazar ${file.name}" }
              check(temp.renameTo(target)) { "No se pudo guardar ${file.name}" }
            } finally {
                connection.disconnect()
                temp.delete()
            }
            completedBytes += file.sizeBytes
        }
    }

    private fun makeUniqueVoiceName(name: String): String {
        val existing = voices.value.map { it.name }.toSet()
        if (name !in existing) return name
        var index = 2
        while ("$name $index" in existing) index += 1
        return "$name $index"
    }

    override fun onCleared() {
        cleared.set(true)
        cancelRequested.set(true)
        engine?.requestCancel()
        recorder.cancel()
        stopAudio()
        stopBusyTicker()
        // JNI synthesis is synchronous. Wait off the UI thread before freeing its context.
        CoroutineScope(Dispatchers.IO).launch {
            synchronized(nativeLock) {
                engine?.close()
                engine = null
            }
        }
        super.onCleared()
    }
}

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        GenerationRuntime.isUiVisible = true
    }

    override fun onStop() {
        GenerationRuntime.isUiVisible = false
        if (!isChangingConfigurations) GenerationRuntime.existing()?.onAppBackgrounded()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QwenTtsTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    QwenTtsApp()
                }
            }
        }
    }
}

private sealed class AppDestination(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit,
) {
    object Studio : AppDestination("studio", "Estudio", { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) })
    object Voices : AppDestination("voices", "Voces", { Icon(Icons.Default.RecordVoiceOver, contentDescription = null) })
    object History : AppDestination("history", "Historial", { Icon(Icons.Default.History, contentDescription = null) })
    object Settings : AppDestination("settings", "Ajustes", { Icon(Icons.Default.Settings, contentDescription = null) })
}

private val appDestinations = listOf(
    AppDestination.Studio,
    AppDestination.Voices,
    AppDestination.History,
    AppDestination.Settings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QwenTtsApp(viewModel: MainViewModel = GenerationRuntime.get(LocalContext.current)) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.Studio.route
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.stopAudio() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); viewModel.stopAudio() }
    }
    DisposableEffect(state.busy, state.backgroundGenerationEnabled, context) {
        val activity = context as? ComponentActivity
        if (state.busy && !state.backgroundGenerationEnabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }


    LaunchedEffect(state.snackbarMessage) {
        val message = state.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearSnackbarMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                appDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(AppDestination.Studio.route) { saveState = true }
                            }
                        },
                        icon = destination.icon,
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = AppDestination.Studio.route,
            modifier = Modifier.padding(padding),
            enterTransition = {
                val direction = if (destinationIndex(targetState.destination.route) >= destinationIndex(initialState.destination.route)) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                slideIntoContainer(direction, animationSpec = tween(260))
            },
            exitTransition = {
                val direction = if (destinationIndex(targetState.destination.route) >= destinationIndex(initialState.destination.route)) {
                    AnimatedContentTransitionScope.SlideDirection.Start
                } else {
                    AnimatedContentTransitionScope.SlideDirection.End
                }
                slideOutOfContainer(direction, animationSpec = tween(260))
            },
            popEnterTransition = {
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(260))
            },
            popExitTransition = {
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, animationSpec = tween(260))
            },
        ) {
            composable(AppDestination.Studio.route) {
                StudioScreen(viewModel)
            }
            composable(AppDestination.Voices.route) {
                VoicesScreen(viewModel)
            }
            composable(AppDestination.History.route) {
                HistoryScreen(viewModel, onReuse = { navController.navigate(AppDestination.Studio.route) { launchSingleTop = true } })
            }
            composable(AppDestination.Settings.route) {
                SettingsScreen(viewModel)
            }
        }
    }
}

private fun destinationIndex(route: String?): Int =
    appDestinations.indexOfFirst { it.route == route }.takeIf { it >= 0 } ?: 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val studioContext = LocalContext.current
    var notificationPrompted by rememberSaveable { mutableStateOf(false) }
    val generationNotificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (GenerationRuntime.isUiVisible) viewModel.synthesize()
    }
    val voices by viewModel.voices.collectAsState()
    val transcripts by viewModel.voiceTranscripts.collectAsState()
    var showVoicePicker by remember { mutableStateOf(false) }
    var reportToExport by remember { mutableStateOf<String?>(null) }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportReportToUri(reportToExport, uri, false)
        reportToExport = null
    }
    val textLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.exportReportToUri(reportToExport, uri, true)
        reportToExport = null
    }

    val selectedVoiceName = voices.firstOrNull { it.voiceId == state.selectedVoiceId }?.name ?: "Voz predeterminada"

    if (showVoicePicker) {
        VoicePickerSheet(
            voices = voices,
            selectedVoiceId = state.selectedVoiceId,
            onDismiss = { showVoicePicker = false },
            onVoiceSelected = { voiceId ->
                viewModel.selectVoice(voiceId)
                showVoicePicker = false
            },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Texto a voz", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            state.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            ComposerPanel(
                state = state,
                selectedVoiceName = selectedVoiceName,
                onTextChange = viewModel::updateText,
                onVoicePickerClick = { if (!state.busy) showVoicePicker = true },
                onLanguageChange = viewModel::updateLanguage,
            )
            Text(
                when {
                    state.selectedVoiceId == null -> "Voz del modelo: no usa una referencia personal."
                    !transcripts[state.selectedVoiceId.orEmpty()].isNullOrBlank() -> "Clonación con audio y sus palabras escritas (ICL)."
                    else -> "Solo timbre: en Voces podés escribir lo que dice la grabación original para usar también su contenido al clonar."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if ((state.busy && !state.downloading) || state.sampleCount > 0 || state.resultReportPath != null) {
                ResultPanel(
                    state = state,
                    onPlay = viewModel::playAudio,
                    onStop = viewModel::stopAudio,
                    onJson = { reportToExport = state.resultReportPath; jsonLauncher.launch("mi-voz-registro.json") },
                    onText = { reportToExport = state.resultReportPath; textLauncher.launch("mi-voz-registro.txt") },
                    onCopy = { viewModel.copyText(state.resultText) },
                )
            }
        }

        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Button(
                onClick = {
                    if (state.canCancel) viewModel.cancelSynthesis()
                    else if (state.backgroundGenerationEnabled && Build.VERSION.SDK_INT >= 33 && !notificationPrompted &&
                        ContextCompat.checkSelfPermission(studioContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPrompted = true
                        generationNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else viewModel.synthesize()
                },
                enabled = if (state.canCancel) !state.cancelling else state.modelReady && state.text.isNotBlank() && !state.busy && TextChunks.count(state.text) <= TextChunks.MAX_CHARACTERS,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(when { state.cancelling -> "Cancelando…"; state.canCancel -> "Cancelar generación"; state.busy && !state.downloading -> "Trabajando…"; else -> "Generar" })
            }
        }
    }
}

@Composable
private fun VoicesScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val voices by viewModel.voices.collectAsState()
    val recorderState by viewModel.recorderState.collectAsState()
    val transcripts by viewModel.voiceTranscripts.collectAsState()
    val context = LocalContext.current
    var voiceName by rememberSaveable { mutableStateOf("") }
    var referenceText by rememberSaveable { mutableStateOf("") }
    var editingVoice by remember { mutableStateOf<VoiceProfileEntity?>(null) }
    var editedText by rememberSaveable { mutableStateOf("") }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) viewModel.startVoiceRecording()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importVoice(uri, voiceName, referenceText)
    }

    LaunchedEffect(recorderState.elapsedMillis) {
        if (recorderState.isRecording && recorderState.elapsedMillis >= 30_000L) {
            viewModel.stopVoiceRecordingAndCreate(voiceName, referenceText)
        }
    }

    LaunchedEffect(state.selectedVoiceId, state.status) {
        if (state.status == "Voz creada") {
            voiceName = ""
            referenceText = ""
        }
    }

    editingVoice?.let { voice ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) editingVoice = null },
            title = { Text("Transcripción de ${voice.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Escribí exactamente lo que se oye en el WAV de referencia, con sus pausas y puntuación. Dejalo vacío para usar solo timbre.")
                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 8,
                        enabled = !state.busy,
                        label = { Text("Texto exacto de la referencia") },
                        isError = editedText.length > VoicePromptFiles.MAX_TRANSCRIPT_CHARS,
                        supportingText = { Text("${editedText.length}/${VoicePromptFiles.MAX_TRANSCRIPT_CHARS}") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.busy && editedText.length <= VoicePromptFiles.MAX_TRANSCRIPT_CHARS,
                    onClick = { viewModel.saveVoiceTranscript(voice, editedText); editingVoice = null },
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(enabled = !state.busy, onClick = { editingVoice = null }) { Text("Cancelar") } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text("Nueva voz", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = voiceName,
                    onValueChange = { voiceName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Nombre") },
                )
                OutlinedTextField(
                    value = referenceText,
                    onValueChange = { referenceText = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("Lo que dice el audio de referencia (opcional)") },
                    isError = referenceText.length > VoicePromptFiles.MAX_TRANSCRIPT_CHARS,
                    supportingText = { Text("Ejemplo: si el audio dice «Hola, soy Ana», escribí esas palabras, no el texto nuevo. Con esto se usa audio + texto para clonar. ${referenceText.length}/${VoicePromptFiles.MAX_TRANSCRIPT_CHARS}") },
                )
                Text("Elegí una muestra de 3 a 30 segundos (ideal: 10 a 15), con una sola voz, sin música ni eco. WAV, MP3 o M4A según los códecs del teléfono.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("audio/*", "application/octet-stream")) },
                    enabled = state.modelReady && !state.busy && !recorderState.isRecording && referenceText.length <= VoicePromptFiles.MAX_TRANSCRIPT_CHARS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Importar archivo de audio")
                }
                if (!state.modelReady) {
                    Text("Primero descargá el modelo en Ajustes. Luego la clonación y la generación funcionan sin conexión.", style = MaterialTheme.typography.bodySmall)
                }
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("${state.status} · ${formatDuration(state.operationElapsedMillis)}", style = MaterialTheme.typography.bodySmall)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (recorderState.isRecording) {
                    LinearProgressIndicator(
                        progress = { recorderState.level.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Grabando ${formatDuration(recorderState.elapsedMillis)}", style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            if (recorderState.isRecording) {
                                viewModel.stopVoiceRecordingAndCreate(voiceName, referenceText)
                                voiceName = ""
                            } else if (hasPermission) {
                                viewModel.startVoiceRecording()
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        enabled = state.modelReady && (!state.busy || recorderState.isRecording) && (recorderState.isRecording || referenceText.length <= VoicePromptFiles.MAX_TRANSCRIPT_CHARS),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (recorderState.isRecording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (recorderState.isRecording) "Detener y crear" else "Grabar")
                    }
                    OutlinedButton(
                        onClick = viewModel::cancelVoiceRecording,
                        enabled = recorderState.isRecording,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancelar")
                    }
                }
                if (state.supportsCloning == false) {
                    Text(
                        "El modelo actual no permite clonar voces.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Voces", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (voices.isEmpty()) {
                    Text("Todavía no hay voces guardadas.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    voices.forEach { voice ->
                        VoiceRow(
                            voice = voice,
                            selected = state.selectedVoiceId == voice.voiceId,
                            onSelect = { viewModel.selectVoice(voice.voiceId) },
                            conditioningLabel = if (transcripts[voice.voiceId].isNullOrBlank()) "Solo timbre: sin transcripción" else "ICL: audio y texto",
                            enabled = !state.busy && !recorderState.isRecording,
                            onEditTranscript = {
                                editedText = transcripts[voice.voiceId].orEmpty()
                                editingVoice = voice
                            },
                            onDelete = { viewModel.deleteVoice(voice) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: MainViewModel, onReuse: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val generations by viewModel.generations.collectAsState()
    var pendingExport by remember { mutableStateOf<GenerationEntity?>(null) }
    var pendingReport by remember { mutableStateOf<String?>(null) }
    val reportJsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportReportToUri(pendingReport, uri, false)
        pendingReport = null
    }
    val reportTextLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.exportReportToUri(pendingReport, uri, true)
        pendingReport = null
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav"),
    ) { uri ->
        val generation = pendingExport
        pendingExport = null
        if (uri != null && generation != null) {
            viewModel.exportGenerationToUri(generation, uri)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (generations.isEmpty()) {
            item {
                Text("Todavía no hay audios generados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(generations, key = { it.generationId }) { generation ->
            val isPlayingThis = state.playing && state.playingGenerationId == generation.generationId
            var menuExpanded by remember(generation.generationId) { mutableStateOf(false) }
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(generation.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(generation.modelName ?: "Modelo no registrado (audio anterior)", style = MaterialTheme.typography.labelMedium)
                        Text("${generation.backend ?: "Motor no registrado"} · ${generation.cpuThreads?.let { "$it hilos" } ?: "Hilos no registrados"} · ${modeLabel(generation.cloningMode)}", style = MaterialTheme.typography.bodySmall)
                        if (generation.status in listOf("partial", "truncated")) Text("Audio parcial · ${generation.completedChunks}/${generation.chunkCount} fragmentos", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

                        Text(
                            "${generation.voiceName} · ${formatSeconds(generation.sampleCount.toDouble() / generation.sampleRate.coerceAtLeast(1))} · ${formatDate(generation.createdAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            if (isPlayingThis) {
                                viewModel.stopAudio()
                            } else {
                                viewModel.playGeneration(generation.generationId)
                            }
                        },
                    ) {
                        Icon(
                            if (isPlayingThis) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isPlayingThis) "Detener" else "Reproducir")
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Más")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("Copiar texto original") },
                                onClick = { menuExpanded = false; viewModel.copyText(generation.text) })
                            DropdownMenuItem(text = { Text("Usar texto en Estudio") }, enabled = !state.busy,
                                onClick = { menuExpanded = false; if (viewModel.reuseText(generation.text)) onReuse() })
                            DropdownMenuItem(text = { Text("Guardar registro JSON") }, enabled = generation.reportPath != null,
                                onClick = { menuExpanded = false; pendingReport = generation.reportPath; reportJsonLauncher.launch("mi-voz-${generation.generationId}-registro.json") })
                            DropdownMenuItem(text = { Text("Guardar registro TXT") }, enabled = generation.reportPath != null,
                                onClick = { menuExpanded = false; pendingReport = generation.reportPath; reportTextLauncher.launch("mi-voz-${generation.generationId}-registro.txt") })

                            DropdownMenuItem(
                                text = { Text("Exportar") },
                                leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    pendingExport = generation
                                    exportLauncher.launch(viewModel.suggestedExportFileName(generation))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.deleteGeneration(generation)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAllowed = it }
    val latestJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportReportToUri(viewModel.latestReportPath(), uri, false)
    }
    val latestText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) viewModel.exportReportToUri(viewModel.latestReportPath(), uri, true)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ModelPanel(
            state = state,
            onModelChange = viewModel::selectModel,
            onDownload = viewModel::downloadModel,
            onLoad = viewModel::loadModel,
        )
        OutlinedButton(onClick = viewModel::releaseMemory, enabled = !state.busy) { Text("Liberar memoria del modelo") }
        Text("El modelo se libera automáticamente después de cada generación; cargarlo otra vez puede tardar.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Generar en segundo plano", modifier = Modifier.weight(1f))
            Switch(checked = state.backgroundGenerationEnabled, onCheckedChange = viewModel::updateBackgroundGeneration, enabled = !state.busy)
        }
        Text("Activado: continúa con la pantalla apagada o al usar otra app, con notificación y opción Cancelar. Desactivado: salir de la app cancela y conserva los fragmentos completos. Android aún puede detenerla por límites del sistema.", style = MaterialTheme.typography.bodySmall)
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed) {
            OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Permitir notificación de progreso") }
            Text("Sin este permiso el aviso puede no aparecer en la barra; podés volver a la app para cancelar.", style = MaterialTheme.typography.bodySmall)
        }
        Text(state.status, style = MaterialTheme.typography.bodySmall)
        Text("Último registro disponible: puede pertenecer a una ejecución anterior o interrumpida. El informe identifica su versión y fecha.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { latestJson.launch("mi-voz-ultimo-registro.json") }, enabled = !state.busy && viewModel.latestReportPath() != null) { Text("Último JSON") }
            OutlinedButton(onClick = { latestText.launch("mi-voz-ultimo-registro.txt") }, enabled = !state.busy && viewModel.latestReportPath() != null) { Text("Último TXT") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        RuntimePanel(
            state = state,
            onCpuThreadsChange = viewModel::updateCpuThreads,
            onBackendChange = viewModel::selectBackend,
        )
    }
}

@Composable
private fun ComposerPanel(
    state: QwenTtsUiState,
    selectedVoiceName: String,
    onTextChange: (String) -> Unit,
    onVoicePickerClick: () -> Unit,
    onLanguageChange: (Int) -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                maxLines = 10,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                label = { Text("Texto para generar") },
                enabled = !state.busy,
                isError = TextChunks.count(state.text) > TextChunks.MAX_CHARACTERS,
                supportingText = { Text("${TextChunks.count(state.text)} / 20.000 caracteres · se divide automáticamente en frases cortas") },
            )
            OutlinedButton(
                onClick = onVoicePickerClick,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text("Voz", style = MaterialTheme.typography.labelMedium)
                    Text(selectedVoiceName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            LanguageDropdown(
                selectedLanguageId = state.selectedLanguageId,
                enabled = !state.busy,
                onLanguageChange = onLanguageChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerSheet(
    voices: List<VoiceProfileEntity>,
    selectedVoiceId: String?,
    onDismiss: () -> Unit,
    onVoiceSelected: (String?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Elegir voz", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            VoicePickerItem(
                title = "Voz predeterminada",
                subtitle = "Usar la voz del modelo",
                selected = selectedVoiceId == null,
                onClick = { onVoiceSelected(null) },
            )
            voices.forEach { voice ->
                VoicePickerItem(
                    title = voice.name,
                    subtitle = "Referencia de ${formatDuration(voice.durationMillis)}",
                    selected = selectedVoiceId == voice.voiceId,
                    onClick = { onVoiceSelected(voice.voiceId) },
                )
            }
        }
    }
}

@Composable
private fun VoicePickerItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Icon(Icons.Default.RecordVoiceOver, contentDescription = "Seleccionada", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceProfileEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    conditioningLabel: String,
    enabled: Boolean,
    onEditTranscript: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FilterChip(selected = selected, onClick = onSelect, enabled = enabled, label = { Text(if (selected) "Activa" else "Usar") })
        Column(Modifier.weight(1f)) {
            Text(voice.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$conditioningLabel · ${formatDuration(voice.durationMillis)} · ${formatDate(voice.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onEditTranscript, enabled = enabled) {
            Icon(Icons.Default.Edit, contentDescription = "Editar transcripción de ${voice.name}")
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Default.Delete, contentDescription = "Eliminar")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selectedLanguageId: Int,
    enabled: Boolean,
    onLanguageChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = languageOptions.firstOrNull { it.id == selectedLanguageId } ?: languageOptions.first()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            label = { Text("Idioma") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languageOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onLanguageChange(option.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPanel(
    state: QwenTtsUiState,
    onModelChange: (String) -> Unit,
    onDownload: () -> Unit,
    onLoad: () -> Unit,
) {
    val selectedVariant = QwenModel.variantById(state.selectedModelId)
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExposedDropdownMenuBox(
                expanded = expanded && !state.busy,
                onExpandedChange = { if (!state.busy) expanded = !expanded },
            ) {
                OutlinedTextField(
                    value = selectedVariant.displayName,
                    onValueChange = {},
                    readOnly = true,
                    enabled = !state.busy,
                    label = { Text("Modelo Qwen3-TTS Base") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded && !state.busy, onDismissRequest = { expanded = false }) {
                    QwenModel.variants.forEach { variant ->
                        DropdownMenuItem(
                            text = { Text("${variant.displayName} · ${formatBytes(variant.totalBytes)}") },
                            onClick = {
                                expanded = false
                                onModelChange(variant.id)
                            },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(selectedVariant.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatBytes(selectedVariant.totalBytes)} de modelos en disco",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AssistChip(onClick = {}, label = { Text(if (state.modelReady) "Listo" else "Falta descargar") })
            }
            Text(
                "Se descarga solo la variante elegida. El códec se comparte entre 0.6B y 1.7B de la misma precisión. El tamaño en disco no indica la RAM necesaria.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!selectedVariant.quantized) {
                Text(
                    "BF16 conserva los pesos a 16 bits y usa más memoria. Su velocidad y estabilidad en este teléfono siguen pendientes de prueba; Android puede cerrar el proceso si falta RAM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.downloading) {
                LinearProgressIndicator(progress = { state.downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${formatBytes(state.downloadBytes)} / ${formatBytes(state.downloadTotalBytes)}", style = MaterialTheme.typography.bodySmall)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDownload,
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.modelReady) "Verificar y reparar" else "Descargar modelo")
                }
                OutlinedButton(
                    onClick = onLoad,
                    enabled = state.modelReady && !state.loaded && !state.busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (state.loaded) "Cargado" else "Cargar")
                }
            }
        }
    }
}

@Composable
private fun RuntimePanel(
    state: QwenTtsUiState,
    onCpuThreadsChange: (Int) -> Unit,
    onBackendChange: (String) -> Unit,
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Text("Rendimiento", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                backendOptions.forEach { option ->
                    FilterChip(
                        selected = state.selectedBackendId == option.id,
                        onClick = { onBackendChange(option.id) },
                        enabled = !state.busy,
                        label = { Text(option.label) },
                    )
                }
            }
            Text(
                "Motor efectivo: ${state.activeBackendName ?: "sin confirmar; cargá el modelo"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Esta versión usa solo CPU. GPU fue deshabilitada tras un bloqueo completo del teléfono.", style = MaterialTheme.typography.bodySmall)
            Text("Hilos de CPU · 4 recomendados para 1.7B en este equipo", style = MaterialTheme.typography.bodySmall)
            Text("Más hilos no garantizan menor tiempo: compará la misma frase y controlá el calentamiento.", style = MaterialTheme.typography.bodySmall)
            listOf(2, 4, 6, 8).filter { it <= Runtime.getRuntime().availableProcessors() }.chunked(2).forEach { options ->
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { threads ->
                    FilterChip(
                        selected = state.selectedCpuThreads == threads,
                        onClick = { onCpuThreadsChange(threads) },
                        enabled = !state.busy,
                        label = { Text("$threads hilos") },
                    )
                }
              }
            }
        }
    }
}

@Composable
private fun ResultPanel(state: QwenTtsUiState, onPlay: () -> Unit, onStop: () -> Unit, onJson: () -> Unit, onText: () -> Unit, onCopy: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.busy && !state.downloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                GenerationStats(state)
            } else {
                Text(state.resultModelName ?: "Modelo no registrado", fontWeight = FontWeight.SemiBold)
                OutlinedButton(onClick = onCopy, enabled = state.resultText.isNotEmpty()) { Text("Copiar texto original") }
                Text("${state.resultBackend ?: "Motor no registrado"} · ${state.resultThreads?.let { "$it hilos" } ?: "Hilos no registrados"} · ${modeLabel(state.resultMode)}", style = MaterialTheme.typography.bodySmall)
                if (state.resultStatus in listOf("partial", "truncated")) Text("Audio parcial: ${state.resultChunks}/${state.plannedChunks} fragmentos. Revisá el registro antes de usarlo como completo.", color = MaterialTheme.colorScheme.error)
                if (state.resultTotalMillis > 0) Text("Tiempo total del trabajo: ${formatDuration(state.resultTotalMillis)} · memoria del modelo liberada", style = MaterialTheme.typography.bodySmall)
                if (state.resultReportPath != null) {
                    Text("Registro técnico: modelo, ajustes, tiempos y memoria; sin guion ni grabaciones.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onJson) { Text("Registro JSON") }
                        OutlinedButton(onClick = onText) { Text("Registro TXT") }
                    }
                }
                if (state.sampleCount > 0 && state.sampleRate > 0) {
                    Text(
                        "Audio ${formatSeconds(state.sampleCount.toDouble() / state.sampleRate.toDouble())} · generado en ${formatDuration(state.synthesisMillis)} · RTF %.1f".format(Locale.US, state.synthesisMillis.toDouble() / 1000 / (state.sampleCount.toDouble() / state.sampleRate)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { if (state.playing) onStop() else onPlay() },
                    enabled = state.sampleCount > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Icon(
                        if (state.playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.playing) "Detener" else "Reproducir")
                }
            }
        }
    }
}

@Composable
private fun GenerationStats(state: QwenTtsUiState) {
    val generatedAudioSeconds = state.synthesisFrames * 0.08
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(if (state.cancelling) "Cancelación solicitada; el motor debe detenerse antes de liberar la memoria." else state.generationPhase.ifBlank { state.status }, style = MaterialTheme.typography.bodySmall)
        Text("Fragmento ${state.currentChunk}/${state.plannedChunks} · tiempo total: ${formatDuration(state.operationElapsedMillis)}", style = MaterialTheme.typography.bodySmall)
        Text("Las estimaciones corresponden al fragmento actual, no al trabajo completo.", style = MaterialTheme.typography.bodySmall)
        Text("${state.activeBackendName ?: "Motor sin confirmar"} · ${state.cpuThreads} hilos", style = MaterialTheme.typography.bodySmall)
        if (state.synthesisFrames > 0) {
            Text("Audio estimado generado: ~${formatSeconds(generatedAudioSeconds)} (todavía sin decodificar)", style = MaterialTheme.typography.bodySmall)
        }
        if (!state.cancelling) {
            val age = (state.operationElapsedMillis - state.etaObservedAtMillis).coerceAtLeast(0)
            val low = state.etaLowMillis?.minus(age)?.coerceAtLeast(0)
            val high = state.etaHighMillis?.minus(age)?.coerceAtLeast(0)
            if (high == 0L) {
                Text("La operación está superando el rango estimado; esperando nuevo progreso.", style = MaterialTheme.typography.bodySmall)
            } else if (low != null && high != null) {
                val label = if (state.etaIncludesDecode) "Fin aproximado del fragmento" else "Generación aproximada del fragmento"
                val clock = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                val now = System.currentTimeMillis()
                Text("$label: entre ${clock.format(java.util.Date(now + low))} y ${clock.format(java.util.Date(now + high))}", style = MaterialTheme.typography.bodySmall)
                Text("Tiempo restante estimado: ${formatDuration(low)}–${formatDuration(high)}. Puede variar por pausas y temperatura.", style = MaterialTheme.typography.bodySmall)
                if (!state.etaIncludesDecode) Text("Primera medición: rango amplio; la decodificación final aún no tiene una estimación.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(if (state.generationPhase.startsWith("Decodificando")) "Decodificando audio: todavía no hay datos para estimar esta fase." else "Calculando estimación con el progreso real… La carga y preparación pueden tardar.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return "%.1f %s".format(value, units[unitIndex])
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes > 0L) "%d:%02d".format(Locale.US, minutes, seconds) else "%ds".format(Locale.US, seconds)
}

private fun formatSeconds(seconds: Double): String =
    "%.1fs".format(Locale.US, seconds.coerceAtLeast(0.0))

private fun formatDate(epochMillis: Long): String {
    val date = java.text.SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault())
    return date.format(java.util.Date(epochMillis))
}

private fun backendOptionById(id: String): BackendOption =
    backendOptions.firstOrNull { it.id == id } ?: defaultBackendOption

private fun OutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun OutputStream.writeIntLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
    write((value shr 16) and 0xff)
    write((value shr 24) and 0xff)
}

private fun OutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value shr 8) and 0xff)
}

private fun modeLabel(mode: String?): String = when (mode) {
    "icl" -> "Audio + texto de referencia (ICL)"
    "timbre" -> "Solo timbre"
    "default" -> "Voz del modelo"
    else -> "Modo no registrado"
}
