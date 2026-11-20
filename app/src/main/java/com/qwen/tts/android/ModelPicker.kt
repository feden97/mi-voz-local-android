package com.qwen.tts.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.qwen.tts.android.data.ModelVariant
import com.qwen.tts.android.data.QwenModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelPicker(
    state: QwenTtsUiState,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val sorted = QwenModel.variants.sortedByDescending { it.id in state.favoriteModelIds }
    val (downloaded, available) = sorted.partition { it.id in state.downloadedModelIds }
    var showAvailable by rememberSaveable { mutableStateOf(downloaded.isEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Elegir modelo",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() },
        )
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 20.dp)) {
            if (downloaded.isNotEmpty()) {
                item { ModelGroupLabel("Descargados") }
                items(downloaded, key = { it.id }) { model ->
                    ModelPickerRow(model, state, onSelect, onToggleFavorite)
                }
            }
            if (available.isNotEmpty()) {
                item {
                    if (downloaded.isNotEmpty()) HorizontalDivider(Modifier.padding(top = 8.dp))
                    if (downloaded.isNotEmpty()) {
                        TextButton(onClick = { showAvailable = !showAvailable }) {
                            Text(if (showAvailable) "Ocultar catálogo" else "Ver modelos para descargar")
                        }
                    } else {
                        ModelGroupLabel("Para descargar")
                    }
                }
                if (showAvailable) {
                    items(available, key = { it.id }) { model ->
                        ModelPickerRow(model, state, onSelect, onToggleFavorite)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelGroupLabel(label: String) {
    Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModelPickerRow(
    model: ModelVariant,
    state: QwenTtsUiState,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    val selected = model.id == state.selectedModelId
    val favorite = model.id in state.favoriteModelIds
    val detail = when {
        selected && state.modelReady -> "En uso"
        selected -> "Seleccionado · ${formatBytes(model.totalBytes)}"
        model.id in state.downloadedModelIds -> null
        else -> formatBytes(model.totalBytes)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = { onSelect(model.id) },
            enabled = !state.busy,
            shape = MaterialTheme.shapes.medium,
            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.weight(1f).semantics { this.selected = selected },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        IconButton(onClick = { onToggleFavorite(model.id) }) {
            Icon(
                if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (favorite) "Quitar ${model.displayName} de favoritos" else "Marcar ${model.displayName} como favorito",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
