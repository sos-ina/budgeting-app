package com.sosina.terefe.budgetingapp.ui.labels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType

/** Which pop-up is open on this screen, if any. */
private sealed interface LabelDialog {
    data class Edit(val existing: Label?, val parent: Label?) : LabelDialog
    data class Delete(val label: Label) : LabelDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: LabelsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<LabelDialog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Labels") },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text("Back") }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { dialog = LabelDialog.Edit(null, null) }) {
                Text("+  New label")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val selectedIndex = if (state.type == LabelType.EXPENSE) 0 else 1
            PrimaryTabRow(selectedTabIndex = selectedIndex) {
                Tab(
                    selected = selectedIndex == 0,
                    onClick = { viewModel.selectType(LabelType.EXPENSE) },
                    text = { Text("Expenses") }
                )
                Tab(
                    selected = selectedIndex == 1,
                    onClick = { viewModel.selectType(LabelType.INCOME) },
                    text = { Text("Income") }
                )
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    // Extra space at the bottom so the button doesn't cover the last label.
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                    state.groups.forEach { group ->
                        item(key = group.label.id) {
                            LabelRow(
                                label = group.label,
                                subtitle = parentSubtitle(group),
                                isSubLabel = false,
                                onClick = { dialog = LabelDialog.Edit(group.label, null) },
                                trailing = {
                                    TextButton(onClick = {
                                        dialog = LabelDialog.Edit(null, group.label)
                                    }) { Text("+ Sub") }
                                }
                            )
                        }
                        items(group.subLabels, key = { it.id }) { sub ->
                            LabelRow(
                                label = sub,
                                subtitle = if (sub.isHidden) "Hidden" else null,
                                isSubLabel = true,
                                onClick = { dialog = LabelDialog.Edit(sub, group.label) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ---------------- Dialogs ----------------
    when (val current = dialog) {
        is LabelDialog.Edit -> {
            val existing = current.existing
            LabelEditDialog(
                existing = existing,
                parent = current.parent,
                onSave = { name, emoji, color ->
                    viewModel.saveLabel(
                        existing = existing,
                        name = name,
                        emoji = emoji,
                        color = color,
                        parentId = current.parent?.id
                    )
                    dialog = null
                },
                onToggleHidden = if (existing != null) {
                    { viewModel.setHidden(existing, !existing.isHidden); dialog = null }
                } else null,
                onDelete = if (existing != null && existing.canDelete) {
                    { dialog = LabelDialog.Delete(existing) }
                } else null,
                onDismiss = { dialog = null }
            )
        }

        is LabelDialog.Delete -> {
            val label = current.label
            // Records can move to any other visible top-level label of the same type.
            val targets = state.groups
                .map { it.label }
                .filter { it.id != label.id && !it.isHidden }
            val defaultTarget = targets
                .firstOrNull { it.systemKey == Label.SYSTEM_KEY_EXPENSE_OTHER }?.id
                ?: targets.firstOrNull()?.id
            val subCount = state.groups
                .firstOrNull { it.label.id == label.id }
                ?.subLabels?.size ?: 0

            LabelDeleteDialog(
                label = label,
                subLabelCount = subCount,
                moveTargets = targets,
                defaultTargetId = defaultTarget,
                onConfirm = { moveTo ->
                    viewModel.deleteLabel(label, moveTo)
                    dialog = null
                },
                onDismiss = { dialog = null }
            )
        }

        null -> Unit
    }
}

@Composable
private fun LabelRow(
    label: Label,
    subtitle: String?,
    isSubLabel: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    ListItem(
        headlineContent = { Text(label.name) },
        supportingContent = subtitle?.let { text -> { Text(text) } },
        leadingContent = {
            EmojiBadge(
                emoji = label.emoji,
                color = label.color,
                size = if (isSubLabel) 32.dp else 40.dp
            )
        },
        trailingContent = trailing,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = if (isSubLabel) 40.dp else 0.dp) // indent sub-labels
            .alpha(if (label.isHidden) 0.5f else 1f)          // fade hidden labels
    )
}

private fun parentSubtitle(group: LabelGroup): String? {
    val parts = buildList {
        if (group.label.isHidden) add("Hidden")
        val count = group.subLabels.size
        if (count > 0) add(if (count == 1) "1 sub-label" else "$count sub-labels")
    }
    return parts.joinToString(" · ").ifEmpty { null }
}
