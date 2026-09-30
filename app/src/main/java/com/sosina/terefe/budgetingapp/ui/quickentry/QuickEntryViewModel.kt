package com.sosina.terefe.budgetingapp.ui.quickentry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.funstate.AchievementManager
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.data.roast.RoastManager
import com.sosina.terefe.budgetingapp.data.scan.QuickTextOutcome
import com.sosina.terefe.budgetingapp.data.scan.QuickTextParser
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/** One understood expense, editable before saving. */
data class DraftEntry(
    val key: String = UUID.randomUUID().toString(),
    val title: String,
    val amountText: String,
    val labelId: String?,
    val date: LocalDate
)

data class QuickEntryUiState(
    val text: String = "",
    val isParsing: Boolean = false,
    val entries: List<DraftEntry> = emptyList(),
    val labels: List<Label> = emptyList(),     // visible top-level expense labels
    val message: String? = null,
    val isSaving: Boolean = false,
    val currencyCode: String = Money.deviceCurrencyCode()
) {
    /** Entries with a valid amount, which is what will be saved. */
    val validCount: Int get() = entries.count { (Money.parse(it.amountText) ?: 0L) > 0L }
}

sealed interface QuickEntryEvent {
    data class Saved(val count: Int) : QuickEntryEvent
}

@HiltViewModel
class QuickEntryViewModel @Inject constructor(
    private val parser: QuickTextParser,
    private val labelRepository: LabelRepository,
    private val transactionRepository: TransactionRepository,
    private val roastManager: RoastManager,
    private val achievementManager: AchievementManager,
    settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickEntryUiState())
    val uiState: StateFlow<QuickEntryUiState> = _uiState.asStateFlow()

    private val _events = Channel<QuickEntryEvent>(Channel.BUFFERED)
    val events: Flow<QuickEntryEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s -> _uiState.update { it.copy(currencyCode = s.currencyCode) } }
        }
        viewModelScope.launch {
            labelRepository.observeLabels(LabelType.EXPENSE).collect { labels ->
                _uiState.update { state ->
                    state.copy(labels = labels.filter { it.parentId == null && !it.isHidden })
                }
            }
        }
    }

    fun onTextChange(text: String) {
        _uiState.update { it.copy(text = text.take(500), message = null) }
    }

    /** Voice input: adds what was said, then understands it right away. */
    fun onSpokenText(spoken: String) {
        _uiState.update { state ->
            val joined = listOf(state.text.trim(), spoken.trim()).filter { it.isNotEmpty() }.joinToString(", ")
            state.copy(text = joined.take(500))
        }
        understand()
    }

    /** Sends the text to be understood, then shows the results for checking. */
    fun understand() {
        val state = _uiState.value
        if (state.text.isBlank() || state.isParsing) return
        _uiState.update { it.copy(isParsing = true, message = null) }

        viewModelScope.launch {
            val labels = _uiState.value.labels
            val other = labelRepository.getOtherExpenseLabel()
            val outcome = parser.parse(state.text, labels.map { it.name })

            val drafts = outcome.entries.map { entry ->
                DraftEntry(
                    title = entry.title,
                    amountText = Money.toInputText(entry.amount),
                    labelId = entry.labelName
                        ?.let { name -> labels.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id }
                        ?: other.id,
                    date = entry.date ?: LocalDate.now()
                )
            }

            val message = when {
                drafts.isEmpty() -> "Couldn't find any amounts. Try something like \"coffee 15, lunch 40\"."
                outcome is QuickTextOutcome.Smart -> "✨ Found ${drafts.size}. Check them before saving."
                outcome is QuickTextOutcome.Local ->
                    "Simple mode (${outcome.reason}): found ${drafts.size}. Check titles and labels."
                else -> null
            }

            _uiState.update { it.copy(isParsing = false, entries = drafts, message = message) }
        }
    }

    // ---------------- Editing the results ----------------

    fun onTitleChange(key: String, title: String) = updateEntry(key) { it.copy(title = title.take(60)) }

    fun onAmountChange(key: String, text: String) = updateEntry(key) {
        it.copy(amountText = text.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(15))
    }

    fun onLabelChange(key: String, labelId: String) = updateEntry(key) { it.copy(labelId = labelId) }

    fun removeEntry(key: String) {
        _uiState.update { state -> state.copy(entries = state.entries.filterNot { it.key == key }) }
    }

    private fun updateEntry(key: String, change: (DraftEntry) -> DraftEntry) {
        _uiState.update { state -> state.copy(entries = state.entries.map { if (it.key == key) change(it) else it }) }
    }

    // ---------------- Saving ----------------

    fun saveAll() {
        val state = _uiState.value
        if (state.isSaving || state.validCount == 0) return
        _uiState.update { it.copy(isSaving = true) }

        viewModelScope.launch {
            val savedList = mutableListOf<Transaction>()
            state.entries.forEach { draft ->
                val amount = Money.parse(draft.amountText)?.takeIf { it > 0 } ?: return@forEach
                // Today's entries get the current time; past days get midday.
                val dateTime = if (draft.date == LocalDate.now()) {
                    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
                } else {
                    draft.date.atTime(12, 0)
                }
                val transaction = Transaction(
                    amount = amount,
                    labelId = draft.labelId,
                    dateTime = dateTime,
                    title = draft.title.ifBlank { "Expense" }
                )
                transactionRepository.saveTransaction(transaction)
                savedList += transaction
            }
            roastManager.onExpensesAdded(savedList)
            achievementManager.onExpensesAdded(savedList)
            if (savedList.isNotEmpty()) achievementManager.onQuickAdd()
            _events.send(QuickEntryEvent.Saved(savedList.size))
        }
    }
}
