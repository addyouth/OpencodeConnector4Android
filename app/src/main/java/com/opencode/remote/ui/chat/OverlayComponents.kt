package com.opencode.remote.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opencode.remote.data.api.dto.AgentInfo
import com.opencode.remote.data.api.dto.TodoItem
import com.opencode.remote.ui.strings.AppLocale

// ─── Todo Panel ───────────────────────────────────────────────────────────

@Composable
internal fun TodoPanel(
    todos: List<TodoItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = AppLocale.strings
    Card(
        modifier = modifier
            .width(280.dp)
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = s.todoTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = s.close)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            todos.forEach { todo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = when (todo.status) {
                            "completed" -> Icons.Default.CheckCircle
                            "in_progress" -> Icons.Default.PlayCircle
                            else -> Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = when (todo.status) {
                            "completed" -> MaterialTheme.colorScheme.tertiary
                            "in_progress" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                    Text(
                        text = todo.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (todos.isEmpty()) {
                Text(
                    text = s.noTodos,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
// ─── Selection Dropdown Row ──────────────────────────────────────────────

@Composable
private fun <T> SelectionDropdownRow(
    label: String,
    options: List<T?>,
    selectedOption: T?,
    onOptionSelected: (T?) -> Unit,
    autoLabel: String,
    optionLabel: @Composable (T?) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    optionSupportText: @Composable ((T?) -> String?)? = null,
    optionContent: @Composable (BoxScope.(T?) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = if (selectedOption != null) optionLabel(selectedOption) else autoLabel

    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = {
                    IconButton(onClick = { expanded = true }, enabled = enabled) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = true },
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(0.9f),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            if (optionContent != null) {
                                Box { optionContent(option) }
                            } else {
                                Column {
                                    Text(
                                        optionLabel(option),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (option == selectedOption) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    optionSupportText?.invoke(option)?.let { supportText ->
                                        if (supportText.isNotBlank()) {
                                            Text(
                                                supportText,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

// ─── Chat Selection Config Dialog ────────────────────────────────────────

@Composable
internal fun AgentDetailDialog(
    agentName: String,
    mode: String?,
    description: String?,
    defaultModel: String?,
    isLoading: Boolean,
    closeText: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(agentName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    if (!mode.isNullOrBlank()) {
                        Text(
                            text = mode,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!description.isNullOrBlank()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!defaultModel.isNullOrBlank()) {
                        Text(
                            text = defaultModel,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(closeText) }
        },
    )
}

@Composable
internal fun ChatSelectionConfigDialog(
    selection: ChatSelectionUiState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onAgentSelected: (String?) -> Unit,
    onModelSelected: (ModelSelectionRef?) -> Unit,
    onVariantSelected: (String?) -> Unit,
    onAgentInfo: () -> Unit = {},
) {
    val s = AppLocale.strings

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.selectionTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Agent dropdown
                SelectionDropdownRow(
                    label = s.selectionAgent,
                    options = listOf(null) + selection.availableAgents.map { it.name },
                    selectedOption = selection.draft.agent,
                    onOptionSelected = onAgentSelected,
                    autoLabel = s.selectionAuto,
                    optionLabel = { it ?: (selection.resolvedDefaultAgent?.let { da -> "$da${s.selectionDefaultSuffix}" } ?: s.selectionAuto) },
                    optionSupportText = { opt ->
                        opt?.let { name ->
                            selection.availableAgents.find { it.name == name }?.description
                        }
                    },
                )

                // Model dropdown (two-line items)
                SelectionDropdownRow(
                    label = s.selectionModel,
                    options = listOf(null) + selection.availableModels.map { it.ref },
                    selectedOption = selection.draft.model,
                    onOptionSelected = onModelSelected,
                    autoLabel = s.selectionAuto,
                    optionLabel = { opt ->
                        opt?.let { ref ->
                            selection.availableModels.find { it.ref == ref }?.displayLabel ?: ref.modelId
                        } ?: (selection.resolvedDefaultModel?.let { dm ->
                            (selection.resolveModel(dm)?.displayLabel
                                ?: (dm.providerId + "/" + dm.modelId)) + s.selectionDefaultSuffix
                        } ?: s.selectionAuto)
                    },
                    optionContent = { opt ->
                        if (opt == null) {
                            val dm = selection.resolvedDefaultModel
                            Text(
                                dm?.let {
                                    (selection.resolveModel(it)?.displayLabel
                                        ?: (it.providerId + "/" + it.modelId)) + s.selectionDefaultSuffix
                                } ?: s.selectionAuto,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            val model = selection.availableModels.find { it.ref == opt }
                            Column {
                                Text(
                                    model?.modelName ?: opt.modelId,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    model?.providerName ?: opt.providerId,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )

                // Variant dropdown (only if model selected and has variants)
                val draftVariants = selection.draftVariants
                if (selection.draft.model != null || draftVariants.isNotEmpty()) {
                    SelectionDropdownRow(
                        label = s.selectionLevel,
                        options = listOf(null) + draftVariants.map { it },
                        selectedOption = selection.draft.variant,
                        onOptionSelected = onVariantSelected,
                        autoLabel = s.selectionAuto,
                        optionLabel = { it ?: s.selectionAuto },
                        enabled = draftVariants.isNotEmpty(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(s.selectionConfirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.selectionCancel) }
        },
    )
}

// ─── Settings Button ─────────────────────────────────────────────────────

@Composable
internal fun SettingsButton(
    hasExplicitOverrides: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.testTag("settings_button"),
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = "Settings",
            tint = if (hasExplicitOverrides) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
