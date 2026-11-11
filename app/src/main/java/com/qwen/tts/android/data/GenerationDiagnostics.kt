package com.qwen.tts.android.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Private, bounded technical report. It intentionally never receives scripts or reference text. */
internal class GenerationDiagnostics(
    context: Context,
    modelId: String,
    modelName: String,
    backend: String,
    cpuThreads: Int,
    cloningMode: String,
    languageId: Int,
    textCharacters: Int,
    plannedChunks: Int,
    maxAudioTokens: Int = 512,
    visibilityProvider: (() -> Boolean)? = null,
    foregroundServiceProvider: (() -> Boolean)? = null,
    requestedBackend: String = "cpu",
) {
    private val app = context.applicationContext
    private val started = SystemClock.elapsedRealtime()
    private val id = UUID.randomUUID().toString()
    private var lastCapture = Long.MIN_VALUE
    private var peakPssKb = 0
    private var peakNativeBytes = 0L
    private var highestThermalStatus: Int? = null
    private val snapshots = JSONArray()
    private val stages = JSONArray()
    private val chunks = JSONArray()
    private val warnings = JSONArray()
    private val events = JSONArray()
    private val report = JSONObject()
    private var finished = false
    private val appVisibility = visibilityProvider
    private val serviceActive = foregroundServiceProvider
    private var droppedMemorySamples = 0
    private val samplingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var samplingJob: Job? = null
    @Volatile private var samplingPhase = "preparation"

    /** Stable technical label only; JNI callbacks must not sample memory or write files. */
    fun setPhase(phase: String) { samplingPhase = phase }

    @Synchronized
    fun startSampling() {
        if (samplingJob != null || finished) return
        samplingJob = samplingScope.launch {
            var lastJournal = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(10_000L)
                runCatching { capture(samplingPhase) }
                    .onFailure { warning("No se pudo tomar una muestra periódica del diagnóstico.") }
                if (SystemClock.elapsedRealtime() - lastJournal >= 30_000L) {
                    runCatching { checkpoint() }
                        .onFailure { warning("No se pudo actualizar el registro periódico en disco.") }
                    lastJournal = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    /** Join outside the report monitor so no sample or journal can race final output. */
    suspend fun stopSampling() = withContext(NonCancellable) {
        val job = synchronized(this@GenerationDiagnostics) { samplingJob.also { samplingJob = null } }
        job?.cancelAndJoin()
        samplingScope.cancel()
    }

    init {
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        val variant = QwenModel.variants.firstOrNull { it.id == modelId }
        val files = JSONArray()
        variant?.files?.forEach { file ->
            files.put(JSONObject().put("name", file.name).put("bytes", file.sizeBytes).put("sha256", file.sha256))
        }
        report.put("schemaVersion", 1)
            .put("reportId", id)
            .put("startedAtUtc", Instant.now().toString())
            .put("privacy", "No audio, embeddings, generated text, reference transcript, voice names or absolute paths included.")
            .put("app", JSONObject().put("id", app.packageName).put("versionName", packageInfo.versionName)
                .put("versionCode", packageInfo.longVersionCode))
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("androidRelease", Build.VERSION.RELEASE).put("apiLevel", Build.VERSION.SDK_INT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("process64Bit", android.os.Process.is64Bit())
                .put("availableProcessors", Runtime.getRuntime().availableProcessors()))
            .put("model", JSONObject().put("id", modelId).put("name", modelName)
                .put("revision", QwenModel.REVISION).put("quantized", variant?.quantized ?: JSONObject.NULL)
                .put("files", files))
            .put("engine", JSONObject().put("name", "qwen3-tts.cpp")
                .put("revision", "4562731dc612cb87b4cd1eedac275a17d1078773")
                .put("ggmlRevision", "eced84c86f8b012c752c016f7fe789adea168e1e")
                .put("localChanges", "cooperative CPU cancellation; decode-phase event; CPU-only build; independent diagnostic sampling and historical process exit records"))
            .put("parameters", JSONObject().put("backend", backend).put("requestedBackend", requestedBackend).put("cpuThreads", cpuThreads)
                .put("cloningMode", cloningMode).put("languageId", languageId)
                .put("inputCharacters", textCharacters).put("plannedChunks", plannedChunks)
                .put("chunking", JSONObject().put("maxCharactersPerChunk", TextChunks.CHUNK_CHARACTERS)
                    .put("shortTextSingleChunk", true)
                    .put("policy", "Keep short texts together; prefer natural boundaries for longer texts. Acoustic token limit is independent."))
                .put("maxAudioTokensPerChunk", maxAudioTokens).put("secondsPerAcousticFrame", 0.08)
                .put("nativeOptimization", "O3; NDEBUG; ARMv8.2-a+dotprod+fp16")
                .put("sampling", JSONObject().put("temperature", 0.9).put("topP", 1.0).put("topK", 50)
                    .put("repetitionPenalty", 1.05).put("vocoderLeftContextSeconds", 2.0)
                    .put("seedMode", "random_per_synthesis").put("resolvedSeed", JSONObject.NULL)
                    .put("reproducibility", "Resolved native seed is not exposed; no independent predictor seed or sub_temperature API")))
            .put("memorySamples", snapshots).put("stages", stages).put("chunks", chunks).put("warnings", warnings)
            .put("events", events)
            .put("previousProcessExits", ProcessExitHistory.snapshot(app))
            .put("execution", JSONObject().put("backgroundServiceRequested", JSONObject.NULL)
                .put("visibility", "App visibility is not inferred from screen interactive state"))
            .put("measurementNotes", JSONArray(listOf(
                "PSS is process proportional resident memory, not total allocations or model file size.",
                "Native heap allocated bytes exclude some memory mapped model weights and other allocations.",
                "Battery temperature is not CPU/GPU/SoC temperature.",
                "previousProcessExits are historical Android records, not a confirmed cause of the current job's failure. Records may be absent after a reboot; a signal alone does not establish a low-memory kill.",
                "Memory and thermal peaks are observed samples; short peaks may be missed.",
                "phaseMillis contains internal native timings; null means the pinned JNI does not expose that metric.",
                "observedPhaseMillis measures wall time around the JNI call and its first decode event, including callback and JNI overhead. It does not isolate internal generation or decoder compute.",
                "beforeDecodeEvent and afterDecodeEvent are null when no decode event was observed.",
                "requestedBackend is the user selection; backend is the name reported by the engine after loading. A GPU backend may still execute some operations on CPU.",
                "Periodic samples run on an independent IO coroutine every 10 seconds, including decoder time; captureDurationMillis records their observed collection cost.",
                "The journal is written before preparation and periodically; incomplete chunks do not imply recoverable audio.",
            )))
    }

    @Synchronized
    fun setBackend(name: String) {
        report.getJSONObject("parameters").put("backend", name)
        if (name.contains("OpenCL", ignoreCase = true) && !report.getJSONObject("model").optBoolean("quantized", true)) {
            report.getJSONObject("parameters").put("gpuWeightPrecision", "FP16 (BF16 weights converted on OpenCL upload)")
            warning("El backend OpenCL convierte los pesos BF16 a FP16 en GPU; no equivale a cálculo BF16 nativo.")
        }
    }

    @Synchronized
    fun noteExecution(backgroundEnabled: Boolean) {
        report.getJSONObject("execution").put("backgroundServiceRequested", backgroundEnabled)
        event(if (backgroundEnabled) "foreground_service_requested" else "foreground_service_not_requested")
    }

    /** Stable technical events only: do not pass generated/reference text or a private file name. */
    @Synchronized
    fun event(label: String) {
        if (events.length() < 2_000) events.put(JSONObject().put("name", DiagnosticFormatting.safeLabel(label))
            .put("elapsedMillis", SystemClock.elapsedRealtime() - started))
    }

    /** At most one periodic sample per ten seconds; force=true marks lifecycle boundaries. */
    @Synchronized
    fun capture(stage: String, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCapture != Long.MIN_VALUE && now - lastCapture < 10_000) return
        lastCapture = now
        val sample = JSONObject().put("stage", DiagnosticFormatting.safeLabel(stage)).put("elapsedMillis", now - started)
        sample.put("appVisible", runCatching { appVisibility?.invoke() }.getOrNull() ?: JSONObject.NULL)
        sample.put("foregroundServiceActive", runCatching { serviceActive?.invoke() }.getOrNull() ?: JSONObject.NULL)
        runCatching {
            val runtime = Runtime.getRuntime()
            sample.put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory())
                .put("javaHeapAllocatedBytes", runtime.totalMemory()).put("javaHeapLimitBytes", runtime.maxMemory())
                .put("processCpuMillis", android.os.Process.getElapsedCpuTime())
                .put("appStorageAvailableBytes", app.filesDir.usableSpace)
        }
        runCatching {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            peakPssKb = maxOf(peakPssKb, info.totalPss)
            sample.put("processPssKiB", info.totalPss)
        }.onFailure { sample.put("processPssKiB", JSONObject.NULL) }
        runCatching {
            val bytes = Debug.getNativeHeapAllocatedSize()
            peakNativeBytes = maxOf(peakNativeBytes, bytes)
            sample.put("nativeHeapAllocatedBytes", bytes)
        }.onFailure { sample.put("nativeHeapAllocatedBytes", JSONObject.NULL) }
        runCatching {
            val info = ActivityManager.MemoryInfo()
            requireNotNull(app.getSystemService(ActivityManager::class.java)).getMemoryInfo(info)
            sample.put("systemAvailableBytes", info.availMem).put("systemTotalBytes", info.totalMem)
                .put("systemLowMemory", info.lowMemory)
        }.onFailure { sample.put("systemAvailableBytes", JSONObject.NULL) }
        runCatching {
            val power = requireNotNull(app.getSystemService(PowerManager::class.java))
            val thermal = power.currentThermalStatus
            highestThermalStatus = maxOf(highestThermalStatus ?: thermal, thermal)
            sample.put("androidThermalStatus", thermal)
                .put("screenInteractive", power.isInteractive)
                .put("powerSaveMode", power.isPowerSaveMode)
        }.onFailure { sample.put("androidThermalStatus", JSONObject.NULL) }
        runCatching {
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temperature = battery?.getIntExtra("temperature", Int.MIN_VALUE) ?: Int.MIN_VALUE
            sample.put("batteryTemperatureC", if (temperature in -500..1_500) temperature / 10.0 else JSONObject.NULL)
            sample.put("batteryStatus", battery?.getIntExtra("status", -1) ?: JSONObject.NULL)
        }.onFailure { sample.put("batteryTemperatureC", JSONObject.NULL) }
        if (snapshots.length() >= MAX_SNAPSHOTS) {
            snapshots.remove(1) // Keep the initial sample and recent tail.
            report.put("droppedMemorySamples", ++droppedMemorySamples)
        }
        sample.put("captureDurationMillis", SystemClock.elapsedRealtime() - now)
        snapshots.put(sample)
    }

    @Synchronized
    fun stage(name: String, elapsedMillis: Long) {
        if (stages.length() < 2_000) stages.put(JSONObject().put("name", DiagnosticFormatting.safeLabel(name))
            .put("durationMillis", elapsedMillis.coerceAtLeast(0)))
    }

    @Synchronized
    fun chunk(
        index: Int,
        chars: Int,
        nativeMillis: Long,
        audioSamples: Int,
        sampleRate: Int,
        frames: Int? = null,
        warning: String? = null,
        timings: Map<String, Long?> = emptyMap(),
        observedTimings: Map<String, Long?> = emptyMap(),
    ) {
        if (chunks.length() >= 1_000) return
        val seconds = if (sampleRate > 0) audioSamples.toDouble() / sampleRate else 0.0
        val entry = JSONObject().put("index", index).put("characters", chars)
            .put("nativeMillis", nativeMillis).put("audioSamples", audioSamples).put("sampleRate", sampleRate)
            .put("audioSeconds", seconds).put("rtfNative", DiagnosticFormatting.rtf(nativeMillis, audioSamples.toLong(), sampleRate) ?: JSONObject.NULL)
            .put("acousticFrames", frames ?: JSONObject.NULL)
        if (warning != null) entry.put("warning", DiagnosticFormatting.safeLabel(warning))
        if (timings.isNotEmpty()) {
            val details = JSONObject()
            timings.forEach { (name, time) -> details.put(DiagnosticFormatting.safeLabel(name), time?.coerceAtLeast(0) ?: JSONObject.NULL) }
            entry.put("phaseMillis", details)
        }
        if (observedTimings.isNotEmpty()) {
            val details = JSONObject()
            observedTimings.forEach { (name, time) -> details.put(DiagnosticFormatting.safeLabel(name), time?.coerceAtLeast(0) ?: JSONObject.NULL) }
            entry.put("observedPhaseMillis", details)
        }
        chunks.put(entry)
    }

    @Synchronized
    fun warning(message: String) {
        if (warnings.length() < 200) warnings.put(DiagnosticFormatting.safeLabel(message))
    }

    @Synchronized
    fun finish(status: String, totalMillis: Long, sampleCount: Long, sampleRate: Int, error: String? = null) {
        finished = true
        val seconds = if (sampleRate > 0) sampleCount.toDouble() / sampleRate else 0.0
        val output = JSONObject().put("status", DiagnosticFormatting.safeLabel(status)).put("totalMillis", totalMillis)
            .put("sampleCount", sampleCount).put("sampleRate", sampleRate).put("audioSeconds", seconds)
            .put("rtfTotal", DiagnosticFormatting.rtf(totalMillis, sampleCount, sampleRate) ?: JSONObject.NULL)
            .put("completedChunks", chunks.length()).put("peakObservedPssKiB", peakPssKb)
            .put("peakObservedNativeHeapBytes", peakNativeBytes)
            .put("highestObservedAndroidThermalStatus", highestThermalStatus ?: JSONObject.NULL)
        // Pass a stable error code / exception class, not user text or an original exception containing it.
        if (error != null) output.put("errorCode", DiagnosticFormatting.safeLabel(error))
        report.put("outcome", output).put("finishedAtUtc", Instant.now().toString())
    }

    /** Journal progress after a completed chunk; latest survives a later interruption without claiming completion. */
    @Synchronized
    fun checkpoint() {
        if (!finished) {
            var samples = 0L
            var nativeMillis = 0L
            for (i in 0 until chunks.length()) {
                samples += chunks.getJSONObject(i).optLong("audioSamples")
                nativeMillis += chunks.getJSONObject(i).optLong("nativeMillis")
            }
            report.put("progress", JSONObject().put("status", "running")
                .put("completedChunks", chunks.length()).put("completedSampleCount", samples)
                .put("completedNativeMillis", nativeMillis).put("elapsedMillis", SystemClock.elapsedRealtime() - started)
                .put("note", "Incomplete journal; only completed chunks recorded. No final output or outcome is implied."))
        }
        report.put("lastCheckpointAtUtc", Instant.now().toString())
        val latest = File(app.filesDir, "diagnostics").apply { mkdirs() }
        atomicWrite(File(latest, "latest.json"), report.toString(2))
        atomicWrite(File(latest, "latest.txt"), summary(report))
    }

    /** Unique immutable report for a generation, plus a replaceable 'latest' report for failed runs. */
    @Synchronized
    fun writeTo(directory: File): File {
        val text = report.toString(2)
        val summary = summary(report)
        val latestDir = File(app.filesDir, "diagnostics").apply { mkdirs() }
        // Write latest first so a later output-directory failure still leaves diagnostics to share.
        atomicWrite(File(latestDir, "latest.json"), text)
        atomicWrite(File(latestDir, "latest.txt"), summary)
        check(directory.isDirectory || directory.mkdirs()) { "No se pudo crear la carpeta de diagnóstico." }
        val target = File(directory, "diagnostic-$id.json")
        atomicWrite(target, text)
        atomicWrite(File(directory, "diagnostic-$id.txt"), summary)
        return target
    }

    companion object {
        private const val MAX_SNAPSHOTS = 1_440

        fun renderText(jsonFile: File): String = summary(JSONObject(jsonFile.readText(Charsets.UTF_8)))
        fun exportableJson(jsonFile: File): String = jsonFile.readText(Charsets.UTF_8)

        private fun atomicWrite(file: File, content: String) {
            val pending = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
            try {
                pending.writeText(content, Charsets.UTF_8)
                try {
                    java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(pending.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                pending.delete()
            }
        }

        private fun summary(value: JSONObject): String = buildString {
            val app = value.getJSONObject("app")
            val device = value.getJSONObject("device")
            val model = value.getJSONObject("model")
            val parameters = value.getJSONObject("parameters")
            val outcome = value.optJSONObject("outcome")
            appendLine("Mi Voz Local — diagnóstico técnico")
            appendLine("Informe: ${value.optString("reportId")}")
            appendLine("Inicio UTC: ${value.optString("startedAtUtc")}")
            appendLine("App: ${app.optString("versionName")} (${app.optLong("versionCode")})")
            appendLine("Equipo: ${device.optString("manufacturer")} ${device.optString("model")} / Android ${device.optString("androidRelease")}")
            appendLine("Modelo: ${model.optString("name")} [${model.optString("id")}]")
            appendLine("Revisión de pesos: ${model.optString("revision")}")
            appendLine("Motor: ${parameters.optString("backend")} / ${parameters.optInt("cpuThreads")} hilos")
            if (parameters.has("requestedBackend")) appendLine("Motor solicitado: ${parameters.optString("requestedBackend")}")
            if (parameters.optString("requestedBackend") == "gpu") appendLine("El motor GPU puede ejecutar algunas operaciones en CPU; su nombre no indica que todo el trabajo ocurra en GPU.")
            appendLine("Modo de voz: ${parameters.optString("cloningMode")} / idioma ${parameters.optInt("languageId")}")
            appendLine("Entrada: ${parameters.optInt("inputCharacters")} caracteres / ${parameters.optInt("plannedChunks")} fragmentos previstos")
            parameters.optJSONObject("chunking")?.let {
                appendLine("Fragmentación: hasta ${it.optInt("maxCharactersPerChunk")} caracteres por llamada; textos cortos en una llamada y cortes naturales en textos largos.")
            }
            val execution = value.optJSONObject("execution")
            if (execution != null) appendLine("Servicio de generación en segundo plano solicitado: ${execution.opt("backgroundServiceRequested")}")
            if (outcome == null) {
                appendLine("Estado: registro en curso o interrumpido, sin finalización confirmada")
                value.optJSONObject("progress")?.let { appendLine("Fragmentos completos hasta último checkpoint: ${it.optInt("completedChunks")}") }
            }
            if (outcome != null) {
                appendLine("Estado: ${outcome.optString("status")}")
                appendLine("Tiempo total: ${outcome.optLong("totalMillis")} ms")
                appendLine("Audio: ${"%.2f".format(Locale.US, outcome.optDouble("audioSeconds"))} s / ${outcome.optLong("sampleCount")} muestras")
                appendLine("Fragmentos terminados: ${outcome.optInt("completedChunks")}")
                appendLine("RTF total: ${if (outcome.isNull("rtfTotal")) "sin audio" else "%.2f".format(Locale.US, outcome.optDouble("rtfTotal"))}")
                appendLine("PSS máximo observado: ${outcome.optInt("peakObservedPssKiB")} KiB")
                appendLine("Heap nativo máximo observado: ${outcome.optLong("peakObservedNativeHeapBytes")} bytes (no incluye todo el modelo mapeado)")
                appendLine("Estado térmico Android máximo observado: ${outcome.opt("highestObservedAndroidThermalStatus")}")
                if (outcome.has("errorCode")) appendLine("Código de error: ${outcome.optString("errorCode")}")
            }
            appendLine()
            appendLine("Etapas:")
            val stages = value.getJSONArray("stages")
            for (i in 0 until stages.length()) {
                val stage = stages.getJSONObject(i)
                appendLine("- ${stage.optString("name")}: ${stage.optLong("durationMillis")} ms")
            }
            value.optJSONArray("events")?.let { events ->
                appendLine()
                appendLine("Eventos:")
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    appendLine("- ${event.optLong("elapsedMillis")} ms: ${event.optString("name")}")
                }
            }
            appendLine()
            appendLine("Fragmentos:")
            val chunks = value.getJSONArray("chunks")
            for (i in 0 until chunks.length()) {
                val chunk = chunks.getJSONObject(i)
                appendLine("- ${chunk.optInt("index")}: ${chunk.optInt("characters")} caracteres; ${chunk.optLong("nativeMillis")} ms nativos; ${"%.2f".format(Locale.US, chunk.optDouble("audioSeconds"))} s audio")
                chunk.optJSONObject("observedPhaseMillis")?.let { observed ->
                    fun measured(name: String): String = if (observed.isNull(name)) "sin dato" else "${observed.optLong(name)} ms"
                    appendLine("  Tiempos observados: llamada JNI ${measured("nativeCall")}; antes del evento de decodificación ${measured("beforeDecodeEvent")}; después del evento ${measured("afterDecodeEvent")}.")
                }
                if (chunk.has("warning")) appendLine("  Aviso: ${chunk.optString("warning")}")
            }
            val warnings = value.getJSONArray("warnings")
            if (warnings.length() > 0) {
                appendLine()
                appendLine("Avisos:")
                for (i in 0 until warnings.length()) appendLine("- ${warnings.getString(i)}")
            }
            appendLine()
            appendLine("No incluye audio, guion, transcripción, nombres de voz ni rutas privadas.")
            appendLine("La temperatura de batería no es la del procesador. Los máximos son muestras observadas; pueden perder picos breves.")
            appendLine("Las fases internas no expuestas por JNI se guardan como null. Los tiempos observados incluyen JNI y callbacks; no aíslan el cálculo interno de cada fase.")
            appendLine("El JSON incluye hashes de modelos, capturas de memoria/térmica y todos los datos técnicos guardados.")
        }
    }
}

/** Small pure helpers kept separate from Android calls for host-side unit tests. */
internal object DiagnosticFormatting {
    fun rtf(millis: Long, samples: Long, sampleRate: Int): Double? =
        if (millis >= 0 && samples > 0 && sampleRate > 0) millis.toDouble() / 1_000 / (samples.toDouble() / sampleRate) else null

    fun safeLabel(value: String): String = value
        .replace(Regex("""[A-Za-z]:[\\/][^\r\n"']+"""), "[ruta omitida]")
        .replace(Regex("""/(?:data|storage|sdcard|system|proc|mnt)/[^\r\n"']+"""), "[ruta omitida]")
        .replace('\u0000', ' ').take(500)
}
