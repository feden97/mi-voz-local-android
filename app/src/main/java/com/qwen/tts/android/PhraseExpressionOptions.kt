package com.qwen.tts.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.qwen.tts.android.data.VoiceExpression
import com.qwen.tts.android.data.WaveRange
import com.qwen.tts.android.data.db.VoiceProfileEntity

internal sealed interface AudioPreview {
    val sourceId: Long?
    val expression: VoiceExpression

    data class Phrase(
        val text: String,
        override val expression: VoiceExpression,
        val referenceVoiceId: String?,
        override val sourceId: Long? = null,
    ) : AudioPreview

    data class Selection(
        override val sourceId: Long,
        val range: WaveRange,
        override val expression: VoiceExpression,
    ) : AudioPreview
}

@Composable
internal fun ExpressionPicker(value: VoiceExpression, enabled: Boolean, onChange: (VoiceExpression) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Expresión · ${value.label}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VoiceExpression.entries.forEach { expression ->
                DropdownMenuItem(text = { Text(expression.label) }, onClick = {
                    expanded = false
                    onChange(expression)
                })
            }
        }
    }
    Text(value.description, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun ExpressiveReferencePicker(
    voices: List<VoiceProfileEntity>,
    selectedId: String?,
    enabled: Boolean,
    onChange: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = if (selectedId == null) "Original" else voices.firstOrNull { it.voiceId == selectedId }?.name ?: "No disponible"
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("Referencia · $selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Original") }, onClick = { expanded = false; onChange(null) })
            voices.forEach { voice ->
                DropdownMenuItem(text = { Text(voice.name) }, onClick = { expanded = false; onChange(voice.voiceId) })
            }
        }
    }
    Text("Para intentar imitar una emoción al generar, usá una grabación transcripta de la misma voz interpretándola.",
        style = MaterialTheme.typography.bodySmall)
}
