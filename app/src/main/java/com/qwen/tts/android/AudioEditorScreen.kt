package com.qwen.tts.android

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qwen.tts.android.data.ReadingText
import com.qwen.tts.android.data.TextChunks
import com.qwen.tts.android.data.WaveEdit
import com.qwen.tts.android.data.WaveLayout
import com.qwen.tts.android.data.WaveRange
import com.qwen.tts.android.data.db.GenerationEntity
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class AudioEditorDocument(
    val generation: GenerationEntity,
    val file: File,
    val layout: WaveLayout,
    val peaks: FloatArray,
    val languageId: Int?,
)

internal data class AudioEditSave(
    val sourceId: Long,
    val saving: Boolean = false,
    val savedId: Long? = null,
    val error: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioEditorScreen(viewModel: MainViewModel, generationId: Long, onClose: () -> Unit, onOpenCopy: (Long) -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val savedEdit by viewModel.audioEditSave.collectAsState()
    val save = savedEdit?.takeIf { it.sourceId == generationId }
    val keepOpen = state.previewInProgress || save?.saving == true
    var source by remember(generationId) { mutableStateOf<AudioEditorDocument?>(null) }
    var loadError by remember(generationId) { mutableStateOf<String?>(null) }

    LaunchedEffect(generationId) {
        viewModel.stopAudio()
        try {
            source = viewModel.loadAudioEditor(generationId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            loadError = error.message ?: "No se pudo abrir el audio."
        }
    }
    val close = {
        viewModel.clearPreview()
        viewModel.clearAudioEditSave()
        onClose()
    }
    BackHandler { if (!keepOpen) close() }
    LaunchedEffect(save?.savedId) {
        save?.savedId?.let { copyId ->
            viewModel.clearAudioEditSave()
            onOpenCopy(copyId)
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) source?.let { viewModel.exportGenerationToUri(it.generation, uri) }
    }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Editar audio") },
            navigationIcon = {
                IconButton(onClick = close, enabled = !keepOpen) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
            },
            actions = {
                IconButton(onClick = { source?.let { export.launch(viewModel.suggestedExportFileName(it.generation)) } },
                    enabled = source != null && !state.busy) {
                    Icon(Icons.Default.Download, contentDescription = "Exportar WAV")
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        val document = source
        when {
            loadError != null -> Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp))
            document == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> AudioEditorControls(viewModel, document, state, save)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioEditorControls(viewModel: MainViewModel, source: AudioEditorDocument, state: QwenTtsUiState, save: AudioEditSave?) {
    val duration = source.layout.durationMillis
    val player = remember(source.file) { AudioTimelinePlayer(duration) }
    val owner = LocalLifecycleOwner.current
    var playhead by rememberSaveable(source.generation.generationId) { mutableStateOf(0L) }
    var start by rememberSaveable(source.generation.generationId) { mutableStateOf(0L) }
    var end by rememberSaveable(source.generation.generationId) { mutableStateOf(duration) }
    var takeText by rememberSaveable(source.generation.generationId) { mutableStateOf<String?>(null) }
    var jumpText by rememberSaveable(source.generation.generationId) { mutableStateOf<String?>(null) }
    var sliderPosition by remember(source.file) { mutableStateOf<Float?>(null) }
    val canPlay = player.ready && !state.busy && !state.previewPlaying
    val selected = WaveRange(source.layout.frameAt(start), source.layout.frameAt(end))
    val hasSelection = selected.start < selected.end
    val wholeTrack = selected.start == 0L && selected.end == source.layout.frames

    DisposableEffect(player, owner) {
        player.open(source.file, playhead)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.close()
        }
    }
    LaunchedEffect(player, player.playing) {
        while (player.playing) {
            player.poll()
            delay(50)
        }
    }
    LaunchedEffect(player) {
        snapshotFlow { player.position }.collect { if (player.ready) playhead = it }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(source.generation.editLabel ?: source.generation.voiceName, style = MaterialTheme.typography.titleMedium)
        val shown = sliderPosition?.roundToLong() ?: player.position
        AudioWaveform(source.peaks, duration, shown, start, end, canPlay, player::seek)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { jumpText = timelineClock(shown) }, enabled = canPlay) {
                Text("Ir a ${timelineClock(shown)}", style = MaterialTheme.typography.labelLarge)
            }
            Text(timelineClock(duration), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = shown.toFloat(), onValueChange = { sliderPosition = it },
            onValueChangeFinished = { sliderPosition?.let { player.seek(it.roundToLong()) }; sliderPosition = null },
            valueRange = 0f..duration.toFloat(), enabled = canPlay,
            modifier = Modifier.semantics { contentDescription = "Posición de reproducción" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { player.seek(player.position - 5_000) }, enabled = canPlay) { Text("−5 s") }
            Button(onClick = { if (player.playing) player.pause() else player.play() }, enabled = canPlay, modifier = Modifier.weight(1f)) {
                Icon(if (player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                Text(if (player.playing) "Pausar" else "Reproducir", modifier = Modifier.padding(start = 6.dp))
            }
            TextButton(onClick = { player.seek(player.position + 5_000) }, enabled = canPlay) { Text("+5 s") }
        }
        player.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text("Selección", style = MaterialTheme.typography.titleMedium)
        RangeSlider(
            value = start.toFloat()..end.toFloat(),
            onValueChange = { range -> start = range.start.roundToLong(); end = range.endInclusive.roundToLong() },
            valueRange = 0f..duration.toFloat(), enabled = !state.busy,
            modifier = Modifier.semantics { contentDescription = "Inicio y fin del tramo" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Inicio ${timelineClock(start)}", style = MaterialTheme.typography.labelMedium)
            Text("Fin ${timelineClock(end)}", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { start = player.position }, enabled = canPlay && player.position < end, modifier = Modifier.weight(1f)) {
                Text("Inicio en cursor", textAlign = TextAlign.Center)
            }
            TextButton(onClick = { end = player.position }, enabled = canPlay && player.position > start, modifier = Modifier.weight(1f)) {
                Text("Fin en cursor", textAlign = TextAlign.Center)
            }
        }
        OutlinedButton(onClick = { player.playRange(start, end) }, enabled = canPlay && hasSelection, modifier = Modifier.fillMaxWidth()) {
            Text("Escuchar selección")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { player.pause(); viewModel.saveAudioEdit(source, selected, WaveEdit.KEEP) },
                enabled = !state.busy && hasSelection && !wholeTrack, modifier = Modifier.weight(1f)) {
                Text("Conservar tramo", textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = { player.pause(); viewModel.saveAudioEdit(source, selected, WaveEdit.REMOVE) },
                enabled = !state.busy && hasSelection && !wholeTrack, modifier = Modifier.weight(1f)) {
                Text("Quitar tramo", textAlign = TextAlign.Center)
            }
        }
        Button(onClick = {
            player.pause()
            viewModel.clearPreview()
            viewModel.clearAudioEditSave()
            takeText = ""
        }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Generar toma nueva") }
        Text("Cada edición guarda una copia en Historial.", style = MaterialTheme.typography.bodySmall)
        if (save?.saving == true) LinearProgressIndicator(Modifier.fillMaxWidth())
        save?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    jumpText?.let { text ->
        val requested = parseTimelineTime(text)?.takeIf { it <= duration }
        AlertDialog(
            onDismissRequest = { jumpText = null },
            title = { Text("Ir a un tiempo") },
            text = {
                OutlinedTextField(value = text, onValueChange = { jumpText = it }, label = { Text("Minutos y segundos") },
                    supportingText = { Text("Por ejemplo 01:30,500 o 90,5 segundos") },
                    singleLine = true, isError = text.isNotBlank() && requested == null)
            },
            dismissButton = { TextButton(onClick = { jumpText = null }) { Text("Cerrar") } },
            confirmButton = {
                TextButton(onClick = { requested?.let(player::seek); jumpText = null }, enabled = requested != null && canPlay) {
                    Text("Ir")
                }
            },
        )
    }
    takeText?.let { draft ->
        val cursor = source.layout.frameAt(player.position)
        val selectionTime = if (hasSelection) "${timelineClock(start)} a ${timelineClock(end)}" else null
        NewTakeDialog(state, source, draft, selectionTime, timelineClock(player.position), save?.error,
            onChange = { takeText = it },
            onDismiss = { viewModel.clearPreview(); takeText = null },
            onGenerate = { viewModel.synthesizePreview(draft, source) },
            onCancel = viewModel::cancelSynthesis,
            onListen = { if (state.previewPlaying) viewModel.stopAudio() else viewModel.playPreview() },
            onReplace = { viewModel.saveAudioEdit(source, selected, WaveEdit.REPLACE, draft) },
            onInsert = { viewModel.saveAudioEdit(source, WaveRange(cursor, cursor), WaveEdit.INSERT, draft) },
        )
    }
}

@Composable
private fun AudioWaveform(
    peaks: FloatArray, duration: Long, position: Long, start: Long, end: Long, enabled: Boolean, onSeek: (Long) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.37f)
    val shade = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val cursor = MaterialTheme.colorScheme.onSurface
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        val scrub = if (enabled) Modifier.pointerInput(duration) {
            detectTapGestures { touch -> onSeek((duration * (touch.x / size.width).coerceIn(0f, 1f)).roundToLong()) }
        } else Modifier
        Canvas(Modifier.fillMaxWidth().height(116.dp).then(scrub)) {
            val startX = size.width * start / duration
            val endX = size.width * end / duration
            if (endX > startX) drawRect(shade, topLeft = Offset(startX, 0f), size = Size(endX - startX, size.height))
            val step = size.width / peaks.size
            val center = size.height / 2
            peaks.forEachIndexed { index, peak ->
                val x = step * (index + 0.5f)
                val height = (peak * center * 0.86f).coerceAtLeast(1.dp.toPx())
                drawLine(if (x in startX..endX) accent else quiet, Offset(x, center - height), Offset(x, center + height), strokeWidth = maxOf(1.dp.toPx(), step * 0.66f))
            }
            val playX = size.width * position / duration
            drawLine(cursor, Offset(playX, 0f), Offset(playX, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun NewTakeDialog(
    state: QwenTtsUiState, source: AudioEditorDocument, draft: String, selectionTime: String?, insertionTime: String,
    error: String?, onChange: (String) -> Unit, onDismiss: () -> Unit, onGenerate: () -> Unit,
    onCancel: () -> Unit, onListen: () -> Unit, onReplace: () -> Unit, onInsert: () -> Unit,
) {
    val count = remember(draft) { TextChunks.count(draft.trim()) }
    val limit = ReadingText.PREVIEW_CHARACTERS
    val canListen = state.previewText == draft.trim() && count > 0
    val takeReady = canListen && state.previewSourceId == source.generation.generationId && !state.busy
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Generar toma nueva") },
        text = {
            val focus = LocalFocusManager.current
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(listOfNotNull(source.generation.voiceName, source.generation.modelName).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = draft, onValueChange = onChange, enabled = !state.busy, minLines = 2, maxLines = 4,
                    label = { Text("Texto de la toma") }, isError = count > limit,
                    supportingText = { Text("$count / $limit caracteres") }, modifier = Modifier.fillMaxWidth())
                if (state.previewInProgress) GenerationStats(state)
                state.previewMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!state.busy) {
                    OutlinedButton(onClick = { focus.clearFocus(); onGenerate() }, enabled = count in 1..limit, modifier = Modifier.fillMaxWidth()) {
                        Text(if (canListen) "Generar otra toma" else "Generar")
                    }
                    if (canListen || state.previewPlaying) {
                        OutlinedButton(onClick = onListen, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.previewPlaying) "Detener toma" else "Escuchar toma")
                        }
                    }
                    if (takeReady) {
                        selectionTime?.let { Text("Selección de $it", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = onInsert, modifier = Modifier.fillMaxWidth()) { Text("Insertar en $insertionTime") }
                    }
                } else if (!state.previewInProgress) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cerrar") } },
        confirmButton = {
            if (state.previewInProgress) {
                TextButton(onClick = onCancel, enabled = !state.cancelling) { Text("Cancelar toma") }
            } else if (takeReady && selectionTime != null) {
                TextButton(onClick = onReplace) { Text("Reemplazar selección") }
            }
        },
    )
}

private fun timelineClock(millis: Long) = String.format(Locale.US, "%02d:%02d.%03d", millis / 60_000, millis / 1_000 % 60, millis % 1_000)

private fun parseTimelineTime(value: String): Long? {
    val fields = value.trim().replace(',', '.').split(':')
    if (fields.size !in 1..2) return null
    val seconds = fields.last().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
    val minutes = if (fields.size == 2) {
        if (seconds >= 60) return null
        fields.first().toLongOrNull()?.takeIf { it >= 0 } ?: return null
    } else 0L
    return ((minutes.toDouble() * 60 + seconds) * 1000).roundToLong()
}
