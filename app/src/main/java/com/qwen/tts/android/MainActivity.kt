package com.qwen.tts.android

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
            modelReady = isModelReady(savedVariant),
            downloadTotalBytes = savedVariant.totalBytes,
        ),
    )
    val uiState = _uiState.asStateFlow()

    private var engine: QwenEngine? = null
    private val nativeLock = Any()
    private val cleared = AtomicBoolean(false)
    private var generatedAudio: FloatArray? = null
    private var playJob: Job? = null
    private var operationTickerJob: Job? = null
    private var activeTrack: AudioTrack? = null

    init {
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

    fun updateCpuThreads(value: Int) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(selectedCpuThreads = value, cpuThreads = value) }
        synchronized(nativeLock) { if (!cleared.get()) engine?.setCpuThreads(value) }
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
        generatedAudio = null
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
                sampleRate = 0,
                sampleCount = 0,
                synthesisMillis = 0,
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
        if (text.length > 350) {
            _uiState.update { it.copy(error = "En este prototipo, usá hasta 350 caracteres por generación para evitar cortes. Dividí los textos largos en frases.") }
            return
        }
        viewModelScope.launch {
            val variant = selectedVariant()
            val selectedVoice = _uiState.value.selectedVoiceId?.let { id -> voices.value.firstOrNull { it.voiceId == id } }
            // History predates model selection and has no model ID. Do not mix its timings across models.
            startBusy(status = "Generando voz", resetSynthesis = true)

            val result = runCatching {
                withContext(Dispatchers.IO) {
                  withEngineContext { native ->
                    val transcript = selectedVoice?.let { VoicePromptFiles.readTranscript(File(it.referenceWavPath)) }.orEmpty()
                    val iclPrompt = selectedVoice?.takeIf { transcript.isNotBlank() }?.let {
                        prepareIclPrompt(native, variant, File(it.referenceWavPath), transcript)
                    }
                    ensureEngineLoaded(variant)
                    val embeddingPath = if (iclPrompt == null) selectedVoice?.let { speakerEmbeddingFor(native, it, variant) } else null
                    native.setProgressCallback { frames, maxTokens ->
                        _uiState.update {
                            it.copy(
                                loaded = true,
                                status = "Generando audio",
                                synthesisFrames = frames,
                                maxAudioTokens = maxTokens,
                            )
                        }
                    }
                    try {
                        val params = QwenEngine.NativeParams(languageId = _uiState.value.selectedLanguageId)
                        if (iclPrompt != null) {
                            native.synthesizeWithIclPrompt(text, iclPrompt.absolutePath, params)
                        } else {
                            native.synthesize(text = text, speakerEmbeddingPath = embeddingPath, params = params)
                        }
                    } finally {
                        _uiState.update {
                            it.copy(activeBackendName = native.getActiveBackendName()?.takeIf { name -> name.isNotBlank() })
                        }
                        native.setProgressCallback(null)
                    }
                  }
                }
            }

            result.fold(
                onSuccess = { nativeResult ->
                    stopBusyTicker()
                    if (!nativeResult.success || nativeResult.audio == null) {
                        _uiState.update {
                            it.copy(
                                busy = false,
                                loaded = true,
                                status = "Falló la generación",
                                error = nativeResult.errorMsg ?: "Falló el motor de voz",
                            )
                        }
                        return@fold
                    }

                    generatedAudio = nativeResult.audio
                    val sampleRate = nativeResult.sampleRate.takeIf { it > 0 } ?: 24_000
                    val voiceName = selectedVoice?.name ?: "Voz predeterminada"
                    val saveError = runCatching {
                        persistGeneratedAudio(text, nativeResult.audio, sampleRate, voiceName, selectedVoice?.voiceId, nativeResult.timeMs)
                    }.exceptionOrNull()
                    if (saveError is CancellationException) throw saveError
                    _uiState.update {
                        it.copy(
                            busy = false,
                            loaded = true,
                            status = if (saveError == null) "Audio listo y guardado" else "Audio generado; falló el guardado",
                            sampleRate = sampleRate,
                            sampleCount = nativeResult.audio.size,
                            synthesisMillis = nativeResult.timeMs,
                            tokenizeMillis = nativeResult.tokenizeMs,
                            encodeMillis = nativeResult.encodeMs,
                            generateMillis = nativeResult.generateMs,
                            decodeMillis = nativeResult.decodeMs,
                            decodeFrames = nativeResult.decodeFrames,
                            decodeSamples = nativeResult.decodeSamples,
                            decodeGraphComputeMillis = nativeResult.decodeGraphComputeMs,
                            estimatedSynthesisMillis = null,
                            estimateSampleCount = 0,
                            error = saveError?.let { "No se pudo guardar en el historial: ${it.message}" },
                        )
                    }
                    playAudio()
                },
                onFailure = { throwable ->
                    stopBusyTicker()
                    _uiState.update {
                        it.copy(
                            busy = false,
                            status = "Falló la generación",
                            error = throwable.message ?: "Falló la generación",
                        )
                    }
                },
            )
        }
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
                            loadReferenceEncoder(native, variant)
                            check(native.extractSpeakerEmbedding(reference.absolutePath, embedding.absolutePath)) {
                                native.getLastError() ?: "No se pudo extraer el perfil de voz."
                            }
                            if (transcript.isNotBlank()) {
                                prepareIclPrompt(native, variant, reference, transcript, encoderReady = true)
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
        if (_uiState.value.playing && _uiState.value.playingGenerationId == generationId) {
            stopAudio()
            return
        }
        viewModelScope.launch {
            val generation = withContext(Dispatchers.IO) { dao.getGeneration(generationId) } ?: return@launch
            val loaded = runCatching { withContext(Dispatchers.IO) { readWav(generation.wavPath) } }
            loaded.onSuccess { wav ->
                stopAudio()
                generatedAudio = wav.samples
                _uiState.update {
                    it.copy(
                        sampleRate = wav.sampleRate,
                        sampleCount = wav.samples.size,
                        status = "Audio listo",
                        error = null,
                    )
                }
                playAudio(generationId)
            }.onFailure { throwable ->
                _uiState.update { it.copy(error = throwable.message ?: "No se pudo reproducir el audio") }
            }
        }
    }

    fun deleteGeneration(generation: GenerationEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteGeneration(generation)
            runCatching { File(generation.wavPath).delete() }
        }
    }

    fun playAudio(historyGenerationId: Long? = null) {
        val samples = generatedAudio ?: return
        val sampleRate = _uiState.value.sampleRate.takeIf { it > 0 } ?: 24_000
        stopAudio()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeTrack = track
            _uiState.update { it.copy(playing = true, playingGenerationId = historyGenerationId) }
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val durationMillis = (samples.size.toDouble() / sampleRate.toDouble() * 1000.0).toLong()
            kotlinx.coroutines.delay(durationMillis.coerceAtLeast(200L))
            track.release()
            if (activeTrack == track) activeTrack = null
            _uiState.update { it.copy(playing = false, playingGenerationId = null) }
        }
    }

    fun stopAudio() {
        playJob?.cancel()
        playJob = null
        activeTrack?.let { track ->
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        activeTrack = null
        _uiState.update { it.copy(playing = false, playingGenerationId = null) }
    }

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
            block(native)
        }

    private inline fun <T> withEngine(variant: ModelVariant, block: (QwenEngine) -> T): T =
        withEngineContext { block(ensureEngineLoaded(variant)) }

    private fun loadReferenceEncoder(native: QwenEngine, variant: ModelVariant) {
        variant.files.forEach { file ->
            check(isVerifiedModelFile(File(modelDirectory(variant), file.name), file)) {
                "El modelo no está verificado. Descargalo o reparalo en Ajustes."
            }
        }
        check(native.setBackendPreference(selectedBackendOption().preference)) { "No se pudo seleccionar el motor." }
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
        val backend = selectedBackendOption()
        if (!native.setBackendPreference(backend.preference)) {
            error("No se pudo seleccionar ${backend.label}")
        }
        val threads = selectedCpuThreadCount()
        native.setCpuThreads(threads)
        if (!_uiState.value.loaded) {
            if (!native.loadModels(modelDirectory(variant).absolutePath, variant.talkerName)) {
                error(native.getLastError() ?: "No se pudo cargar el modelo local")
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

    private fun startBusy(
        status: String,
        downloading: Boolean = false,
        resetSynthesis: Boolean = false,
        synthesisEstimate: GenerationTimeEstimate? = null,
    ) {
        operationTickerJob?.cancel()
        val start = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                busy = true,
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

    private suspend fun persistGeneratedAudio(
        text: String,
        samples: FloatArray,
        sampleRate: Int,
        voiceName: String,
        voiceId: String?,
        synthesisMillis: Long,
    ) {
        withContext(Dispatchers.IO) {
            generationDir.mkdirs()
            val file = File(generationDir, "generation-${UUID.randomUUID()}.wav")
            try {
              file.outputStream().use { writeWav(it, samples, sampleRate) }
              dao.insertGeneration(
                GenerationEntity(
                    text = text,
                    voiceId = voiceId,
                    voiceName = voiceName,
                    wavPath = file.absolutePath,
                    sampleRate = sampleRate,
                    sampleCount = samples.size,
                    synthesisMillis = synthesisMillis,
                ),
              )
            } catch (error: Throwable) {
                file.delete()
                throw error
            }
        }
    }

    private fun makeUniqueVoiceName(name: String): String {
        val existing = voices.value.map { it.name }.toSet()
        if (name !in existing) return name
        var index = 2
        while ("$name $index" in existing) index += 1
        return "$name $index"
    }

    private fun writeWav(output: OutputStream, samples: FloatArray, sampleRate: Int) {
        val dataBytes = samples.size * 2
        output.writeAscii("RIFF")
        output.writeIntLe(36 + dataBytes)
        output.writeAscii("WAVE")
        output.writeAscii("fmt ")
        output.writeIntLe(16)
        output.writeShortLe(1)
        output.writeShortLe(1)
        output.writeIntLe(sampleRate)
        output.writeIntLe(sampleRate * 2)
        output.writeShortLe(2)
        output.writeShortLe(16)
        output.writeAscii("data")
        output.writeIntLe(dataBytes)
        samples.forEach { sample ->
            val clamped = sample.coerceIn(-1f, 1f)
            output.writeShortLe((clamped * 32767f).toInt())
        }
    }

    override fun onCleared() {
        cleared.set(true)
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
private fun QwenTtsApp(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: AppDestination.Studio.route
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

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
                HistoryScreen(viewModel)
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
    val voices by viewModel.voices.collectAsState()
    val transcripts by viewModel.voiceTranscripts.collectAsState()
    var showVoicePicker by remember { mutableStateOf(false) }
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
                    !transcripts[state.selectedVoiceId.orEmpty()].isNullOrBlank() -> "Clonación ICL: audio y transcripción exacta de la referencia."
                    else -> "Solo timbre: esta voz no tiene transcripción; podés añadirla en Voces para usar ICL."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if ((state.busy && !state.downloading) || state.sampleCount > 0) {
                ResultPanel(
                    state = state,
                    onPlay = viewModel::playAudio,
                    onStop = viewModel::stopAudio,
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
                onClick = viewModel::synthesize,
                enabled = state.modelReady && state.text.isNotBlank() && !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (state.busy && !state.downloading) "Generando..." else "Generar")
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
                    label = { Text("Transcripción exacta (opcional, activa ICL)") },
                    isError = referenceText.length > VoicePromptFiles.MAX_TRANSCRIPT_CHARS,
                    supportingText = { Text("Con texto: ICL. Sin texto: solo timbre. ${referenceText.length}/${VoicePromptFiles.MAX_TRANSCRIPT_CHARS}") },
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
private fun HistoryScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val generations by viewModel.generations.collectAsState()
    var pendingExport by remember { mutableStateOf<GenerationEntity?>(null) }
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
        Text(state.status, style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        RuntimePanel(
            state = state,
            onCpuThreadsChange = viewModel::updateCpuThreads,
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
                label = { Text("Texto (máximo 350 caracteres)") },
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
) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Text("Rendimiento", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "Motor CPU",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(4, 6).forEach { threads ->
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

@Composable
private fun ResultPanel(state: QwenTtsUiState, onPlay: () -> Unit, onStop: () -> Unit) {
    ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.busy && !state.downloading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                GenerationStats(state)
            } else {
                if (state.sampleCount > 0 && state.sampleRate > 0) {
                    Text(
                        "Audio ${formatSeconds(state.sampleCount.toDouble() / state.sampleRate.toDouble())} · generado en ${formatDuration(state.synthesisMillis)}",
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
    val remainingMillis = state.estimatedSynthesisMillis
        ?.minus(state.operationElapsedMillis)
        ?.coerceAtLeast(0L)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Tiempo ${formatDuration(state.operationElapsedMillis)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.synthesisFrames > 0) {
            Text(
                "Audio generado hasta ahora: ~${formatSeconds(generatedAudioSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (remainingMillis != null && state.estimateSampleCount >= 3) {
            Text(
                "Tiempo restante estimado: ${formatDuration(remainingMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class WavData(val samples: FloatArray, val sampleRate: Int)

private fun readWav(path: String): WavData {
    val bytes = File(path).readBytes()
    require(bytes.size > 44) { "El WAV está vacío" }
    fun intLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)
    fun shortLe(offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    var cursor = 12
    var sampleRate = 24_000
    var channels = 1
    var bits = 16
    var dataOffset = -1
    var dataSize = 0
    while (cursor + 8 <= bytes.size) {
        val id = String(bytes, cursor, 4, Charsets.US_ASCII)
        val size = intLe(cursor + 4)
        val payload = cursor + 8
        when (id) {
            "fmt " -> {
                channels = shortLe(payload + 2).coerceAtLeast(1)
                sampleRate = intLe(payload + 4)
                bits = shortLe(payload + 14)
            }
            "data" -> {
                dataOffset = payload
                dataSize = size
                break
            }
        }
        cursor = payload + size
    }
    require(dataOffset >= 0 && bits == 16) { "El historial admite WAV PCM16" }
    val frameBytes = channels * 2
    val frameCount = dataSize / frameBytes
    val samples = FloatArray(frameCount)
    var offset = dataOffset
    for (i in 0 until frameCount) {
        var sum = 0f
        for (channel in 0 until channels) {
            val value = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 0xff)).toShort()
            sum += value / 32768f
            offset += 2
        }
        samples[i] = sum / channels
    }
    return WavData(samples, sampleRate)
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
