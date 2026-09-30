package com.sosina.terefe.budgetingapp.ui.income

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.repository.IncomeRepository
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.data.sound.Sound
import com.sosina.terefe.budgetingapp.data.sound.SoundPlayer
import com.sosina.terefe.budgetingapp.domain.model.Income
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import com.sosina.terefe.budgetingapp.domain.model.Money
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Everything the income form shows. */
data class IncomeEditUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,      // false = adding new, true = editing existing
    val amountText: String = "",
    val amountError: String? = null,
    val labelId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val labels: List<Label> = emptyList(),
    val currencyCode: String = Money.deviceCurrencyCode(),
    val isSaving: Boolean = false
) {
    val canSave: Boolean get() = !isLoading && !isSaving && amountText.isNotBlank()
}

/** One-time things that happen, which the screen reacts to (like closing). */
sealed interface IncomeEditEvent {
    data object Saved : IncomeEditEvent
    data class Deleted(val income: Income) : IncomeEditEvent
}

@HiltViewModel
class IncomeEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val incomeRepository: IncomeRepository,
    private val labelRepository: LabelRepository,
    private val soundPlayer: SoundPlayer,
    settingsRepository: SettingsRepository
) : ViewModel() {

    /**
     * Which income to edit. null means "add a new one".
     * Later, navigation will pass this in automatically.
     */
    private val incomeId: String? = savedStateHandle.get<String>("incomeId")

    /** The income as it was before editing, so createdAt etc. are kept. */
    private var original: Income? = null

    private val _uiState = MutableStateFlow(IncomeEditUiState())
    val uiState: StateFlow<IncomeEditUiState> = _uiState.asStateFlow()

    private val _events = Channel<IncomeEditEvent>(Channel.BUFFERED)
    val events: Flow<IncomeEditEvent> = _events.receiveAsFlow()

    init {
        // Keep the currency symbol up to date.
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(currencyCode = settings.currencyCode) }
            }
        }
        // Keep the label list up to date.
        viewModelScope.launch {
            labelRepository.observeLabels(LabelType.INCOME).collect { labels ->
                _uiState.update { it.copy(labels = labels) }
            }
        }
        // Load the income being edited, or set up a blank form.
        viewModelScope.launch { loadInitialState() }
    }

    private suspend fun loadInitialState() {
        val existing = incomeId?.let { incomeRepository.getIncome(it) }
        original = existing

        if (existing != null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isEditing = true,
                    amountText = Money.toInputText(existing.amount),
                    labelId = existing.labelId,
                    date = existing.date,
                    note = existing.note.orEmpty()
                )
            }
        } else {
            // New income: pre-select "Salary", the most common choice.
            val salaryId = labelRepository.observeLabels(LabelType.INCOME).first()
                .firstOrNull { it.systemKey == Label.SYSTEM_KEY_INCOME_SALARY }
                ?.id
            _uiState.update { it.copy(isLoading = false, labelId = salaryId) }
        }
    }

    fun onAmountChange(text: String) {
        // Only allow digits and a decimal separator.
        val cleaned = text.filter { it.isDigit() || it == '.' || it == ',' }.take(15)
        _uiState.update { it.copy(amountText = cleaned, amountError = null) }
    }

    fun onLabelSelect(labelId: String) {
        _uiState.update { it.copy(labelId = labelId) }
    }

    fun onDateChange(date: LocalDate) {
        _uiState.update { it.copy(date = date) }
    }

    fun onNoteChange(note: String) {
        _uiState.update { it.copy(note = note.take(300)) }
    }

    fun save() {
        val state = _uiState.value
        val amount = Money.parse(state.amountText)
        if (amount == null || amount == 0L) {
            _uiState.update { it.copy(amountError = "Enter an amount above zero") }
            return
        }

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val base = original
            val income = if (base != null) {
                base.copy(amount = amount, labelId = state.labelId, date = state.date, note = state.note)
            } else {
                Income(amount = amount, labelId = state.labelId, date = state.date, note = state.note)
            }
            incomeRepository.saveIncome(income)
            if (base == null) soundPlayer.play(Sound.KA_CHING)   // new income only, not edits
            _events.send(IncomeEditEvent.Saved)
        }
    }

    fun delete() {
        val income = original ?: return
        viewModelScope.launch {
            incomeRepository.deleteIncome(income)
            // The previous screen will offer "Undo" using this copy.
            _events.send(IncomeEditEvent.Deleted(income))
        }
    }
}
