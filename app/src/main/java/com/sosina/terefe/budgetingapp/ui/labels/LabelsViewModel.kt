package com.sosina.terefe.budgetingapp.ui.labels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A top-level label with its sub-labels, for displaying as a group. */
data class LabelGroup(
    val label: Label,
    val subLabels: List<Label>
)

/** Everything the Labels screen needs to draw itself. */
data class LabelsUiState(
    val type: LabelType = LabelType.EXPENSE,
    val groups: List<LabelGroup> = emptyList(),
    val isLoading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LabelsViewModel @Inject constructor(
    private val repository: LabelRepository
) : ViewModel() {

    /** Which tab is showing: expense labels or income labels. */
    private val selectedType = MutableStateFlow(LabelType.EXPENSE)

    /** Whenever the tab changes, switch to watching that type's labels. */
    private val labels = selectedType.flatMapLatest { type ->
        repository.observeLabels(type)
    }

    val uiState: StateFlow<LabelsUiState> =
        combine(selectedType, labels) { type, list ->
            LabelsUiState(
                type = type,
                groups = groupLabels(list),
                isLoading = false
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LabelsUiState()
        )

    fun selectType(type: LabelType) {
        selectedType.value = type
    }

    /**
     * Saves a label from the edit dialog.
     * If [existing] is null a new label is created, otherwise it's updated.
     */
    fun saveLabel(
        existing: Label?,
        name: String,
        emoji: String,
        color: Long,
        parentId: String?
    ) {
        if (name.isBlank()) return
        viewModelScope.launch {
            if (existing == null) {
                repository.addLabel(
                    name = name,
                    emoji = emoji.ifBlank { DEFAULT_EMOJI },
                    color = color,
                    type = selectedType.value,
                    parentId = parentId
                )
            } else {
                repository.updateLabel(
                    existing.copy(
                        name = name,
                        emoji = emoji.ifBlank { existing.emoji },
                        color = color
                    )
                )
            }
        }
    }

    fun setHidden(label: Label, hidden: Boolean) {
        viewModelScope.launch {
            repository.updateLabel(label.copy(isHidden = hidden))
        }
    }

    /** Deletes a label, moving its money to [moveToLabelId] (or Uncategorized if null). */
    fun deleteLabel(label: Label, moveToLabelId: String?) {
        if (!label.canDelete) return
        viewModelScope.launch {
            repository.deleteLabel(label, moveToLabelId)
        }
    }

    /** Turns a flat list into parent labels with their sub-labels underneath. */
    private fun groupLabels(labels: List<Label>): List<LabelGroup> {
        val childrenByParent = labels
            .filter { it.parentId != null }
            .groupBy { it.parentId }

        return labels
            .filter { it.parentId == null }
            .sortedBy { it.sortOrder }
            .map { parent ->
                LabelGroup(
                    label = parent,
                    subLabels = childrenByParent[parent.id].orEmpty().sortedBy { it.sortOrder }
                )
            }
    }

    companion object {
        const val DEFAULT_EMOJI = "🏷️"
    }
}
