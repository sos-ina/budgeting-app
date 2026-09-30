package com.sosina.terefe.budgetingapp.ui.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sosina.terefe.budgetingapp.domain.model.Label

/** Colors the user can choose for a label. */
val LabelColors: List<Long> = listOf(
    0xFFE57373, 0xFFF06292, 0xFFBA68C8, 0xFF9575CD,
    0xFF7986CB, 0xFF64B5F6, 0xFF4FC3F7, 0xFF4DD0E1,
    0xFF4DB6AC, 0xFF81C784, 0xFFAED581, 0xFFFFD54F,
    0xFFFFB74D, 0xFFFF8A65, 0xFFA1887F, 0xFF90A4AE
)

/** The emoji in a soft colored circle, used wherever a label is shown. */
@Composable
fun EmojiBadge(emoji: String, color: Long, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(color).copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Text(text = emoji, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Add or edit a label.
 * - existing = null, parent = null  -> new top-level label
 * - existing = null, parent = X     -> new sub-label inside X
 * - existing = label                -> edit that label
 */
@Composable
fun LabelEditDialog(
    existing: Label?,
    parent: Label?,
    onSave: (name: String, emoji: String, color: Long) -> Unit,
    onToggleHidden: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember(existing, parent) { mutableStateOf(existing?.name ?: "") }
    var emoji by remember(existing, parent) { mutableStateOf(existing?.emoji ?: parent?.emoji ?: "") }
    var color by remember(existing, parent) {
        mutableLongStateOf(existing?.color ?: parent?.color ?: LabelColors.first())
    }

    val title = when {
        existing != null -> "Edit label"
        parent != null -> "New sub-label in ${parent.name}"
        else -> "New label"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it.take(8) },
                        label = { Text("Emoji") },
                        singleLine = true,
                        modifier = Modifier.width(88.dp)
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text("Color", style = MaterialTheme.typography.labelLarge)
                ColorPicker(selected = color, onSelect = { color = it })

                // Extra actions only when editing an existing label.
                if (existing != null && (onToggleHidden != null || onDelete != null)) {
                    HorizontalDivider()
                    Column {
                        if (onToggleHidden != null) {
                            TextButton(onClick = onToggleHidden) {
                                Text(if (existing.isHidden) "Show label" else "Hide label")
                            }
                            Text(
                                text = "Hidden labels don't appear when adding expenses. " +
                                    "Past expenses keep them.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                        if (onDelete != null) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = onDelete,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) { Text("Delete label") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, emoji, color) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** A grid of color circles; the chosen one gets an outline. */
@Composable
private fun ColorPicker(selected: Long, onSelect: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelColors.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    val isSelected = c == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .background(Color(c))
                            .then(
                                if (isSelected) Modifier.border(
                                    3.dp, MaterialTheme.colorScheme.onSurface, CircleShape
                                ) else Modifier
                            )
                            .clickable { onSelect(c) }
                    )
                }
            }
        }
    }
}

/**
 * Asks where a deleted label's expenses and income should go.
 * Nothing but the label itself is ever deleted.
 */
@Composable
fun LabelDeleteDialog(
    label: Label,
    subLabelCount: Int,
    moveTargets: List<Label>,
    defaultTargetId: String?,
    onConfirm: (moveToLabelId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedId by remember(label) { mutableStateOf(defaultTargetId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${label.name}\"?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (label.isSubLabel) {
                    Text("Its expenses will stay in the main label. No money records are deleted.")
                } else {
                    if (subLabelCount > 0) {
                        val word = if (subLabelCount == 1) "sub-label" else "sub-labels"
                        Text("Its $subLabelCount $word will be deleted too.")
                    }
                    Text("Move its records to:")
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                        items(moveTargets, key = { it.id }) { target ->
                            RadioRow(
                                text = "${target.emoji}  ${target.name}",
                                selected = selectedId == target.id,
                                onClick = { selectedId = target.id }
                            )
                        }
                        item {
                            RadioRow(
                                text = "Uncategorized",
                                selected = selectedId == null,
                                onClick = { selectedId = null }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedId) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RadioRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text)
    }
}
