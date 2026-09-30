package com.sosina.terefe.budgetingapp.ui.expense

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.funstate.AchievementManager
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.data.roast.RoastManager
import com.sosina.terefe.budgetingapp.data.scan.ReceiptScanner
import com.sosina.terefe.budgetingapp.data.scan.SmartReceiptScanner
import com.sosina.terefe.budgetingapp.data.scan.SmartScanOutcome
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import com.sosina.terefe.budgetingapp.domain.model.TransactionItem
import com.sosina.terefe.budgetingapp.domain.scan.ParsedReceipt
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
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject

/**
 * One item row while it's being typed.
 * The price stays as text until saving, so half-typed values like "12." work.
 */
data class ItemDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val priceText: String = "",
    val quantity: Int = 1
)

data class ExpenseEditUiState(
    val isLoading: Boolean = true,
    val isEditing: Boolean = false,
    val amountText: String = "",
    val amountError: String? = null,
    val labelId: String? = null,
    val subLabelId: String? = null,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.now().truncatedTo(ChronoUnit.MINUTES),
    val title: String = "",
    val note: String = "",
    val items: List<ItemDraft> = emptyList(),
    val labels: List<Label> = emptyList(),   // all expense labels and sub-labels
    val currencyCode: String = Money.deviceCurrencyCode(),
    val isSaving: Boolean = false,
    val isScanning: Boolean = false,
    val scanMessage: String? = null     // e.g. "Found 5 items. Check them before saving."
) {
    /** The total the user typed, or null if the field is empty or invalid. */
    val amountCents: Long? get() = Money.parse(amountText)

    /** What the item rows add up to. */
    val itemsTotal: Long get() = items.sumOf { Money.parse(it.priceText) ?: 0L }

    val hasItems: Boolean get() = items.isNotEmpty()

    val topLevelLabels: List<Label> get() = labels.filter { it.parentId == null }

    /** Sub-labels of the currently selected label. */
    val subLabels: List<Label> get() = labels.filter { it.parentId != null && it.parentId == labelId }

    /** Save works with a typed total, or with items that add up to something. */
    val canSave: Boolean
        get() = !isLoading && !isSaving && !isScanning && (amountText.isNotBlank() || itemsTotal > 0)
}

sealed interface ExpenseEditEvent {
    data object Saved : ExpenseEditEvent
    data class Deleted(val transaction: Transaction) : ExpenseEditEvent
}

@HiltViewModel
class ExpenseEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val transactionRepository: TransactionRepository,
    private val labelRepository: LabelRepository,
    private val receiptScanner: ReceiptScanner,
    private val smartReceiptScanner: SmartReceiptScanner,
    private val roastManager: RoastManager,
    private val achievementManager: AchievementManager,
    settingsRepository: SettingsRepository
) : ViewModel() {

    /** Which expense to edit. null means "add a new one". */
    private val transactionId: String? = savedStateHandle.get<String>("transactionId")

    private var original: Transaction? = null

    private val _uiState = MutableStateFlow(ExpenseEditUiState())
    val uiState: StateFlow<ExpenseEditUiState> = _uiState.asStateFlow()

    private val _events = Channel<ExpenseEditEvent>(Channel.BUFFERED)
    val events: Flow<ExpenseEditEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(currencyCode = settings.currencyCode) }
            }
        }
        viewModelScope.launch {
            labelRepository.observeLabels(LabelType.EXPENSE).collect { labels ->
                _uiState.update { it.copy(labels = labels) }
            }
        }
        viewModelScope.launch { loadInitialState() }
    }

    private suspend fun loadInitialState() {
        val existing = transactionId?.let { transactionRepository.getTransaction(it) }
        original = existing

        if (existing != null) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isEditing = true,
                    amountText = Money.toInputText(existing.amount),
                    labelId = existing.labelId,
                    subLabelId = existing.subLabelId,
                    date = existing.dateTime.toLocalDate(),
                    time = existing.dateTime.toLocalTime(),
                    title = existing.title.orEmpty(),
                    note = existing.note.orEmpty(),
                    items = existing.items.map { item ->
                        ItemDraft(
                            id = item.id,
                            name = item.name,
                            priceText = Money.toInputText(item.price),
                            quantity = item.quantity
                        )
                    }
                )
            }
        } else {
            // New expenses start in "Other" until the user picks something.
            val other = labelRepository.getOtherExpenseLabel()
            _uiState.update { it.copy(isLoading = false, labelId = other.id) }
        }
    }

    // ---------------- Main fields ----------------

    fun onAmountChange(text: String) {
        _uiState.update { it.copy(amountText = cleanAmount(text), amountError = null) }
    }

    fun onLabelSelect(labelId: String) {
        _uiState.update { state ->
            // Changing the main label clears a sub-label that belongs to a different one.
            val keepSub = state.labels.any { it.id == state.subLabelId && it.parentId == labelId }
            state.copy(labelId = labelId, subLabelId = if (keepSub) state.subLabelId else null)
        }
    }

    fun onSubLabelSelect(subLabelId: String?) {
        _uiState.update { it.copy(subLabelId = subLabelId) }
    }

    fun onDateChange(date: LocalDate) {
        _uiState.update { it.copy(date = date) }
    }

    fun onTimeChange(time: LocalTime) {
        _uiState.update { it.copy(time = time) }
    }

    fun onTitleChange(title: String) {
        _uiState.update { it.copy(title = title.take(60)) }
    }

    fun onNoteChange(note: String) {
        _uiState.update { it.copy(note = note.take(300)) }
    }

    // ---------------- Items ----------------

    fun addItem() {
        _uiState.update { it.copy(items = it.items + ItemDraft()) }
    }

    fun onItemNameChange(itemId: String, name: String) {
        updateItem(itemId) { it.copy(name = name.take(60)) }
    }

    fun onItemPriceChange(itemId: String, priceText: String) {
        updateItem(itemId) { it.copy(priceText = cleanAmount(priceText)) }
    }

    fun removeItem(itemId: String) {
        _uiState.update { state -> state.copy(items = state.items.filterNot { it.id == itemId }) }
    }

    /** Sets the total to exactly what the items add up to. */
    fun useItemsTotalAsAmount() {
        _uiState.update {
            it.copy(amountText = Money.toInputText(it.itemsTotal), amountError = null)
        }
    }

    private fun updateItem(itemId: String, change: (ItemDraft) -> ItemDraft) {
        _uiState.update { state ->
            state.copy(items = state.items.map { if (it.id == itemId) change(it) else it })
        }
    }

    // ---------------- Scanning ----------------

    /** A place for the camera app to save the photo. */
    fun createPhotoUri(): Uri = receiptScanner.createPhotoUri()

    /**
     * Tries Smart scan first. If it can't be used (offline, daily limit, error),
     * Quick scan reads the same photo on the phone instead.
     */
    fun scanReceipt(imageUri: Uri) {
        if (_uiState.value.isScanning) return
        _uiState.update { it.copy(isScanning = true, scanMessage = null) }

        viewModelScope.launch {
            try {
                val labelNames = _uiState.value.topLevelLabels.filter { !it.isHidden }.map { it.name }

                when (val smart = smartReceiptScanner.scan(imageUri, labelNames)) {
                    is SmartScanOutcome.Success -> {
                        val left = if (smart.remainingToday == 1) "1 left today" else "${smart.remainingToday} left today"
                        applyScan(
                            receipt = smart.receipt,
                            suggestedLabelName = smart.suggestedLabel,
                            intro = "✨ Smart scan ($left):"
                        )
                    }
                    is SmartScanOutcome.Unavailable -> {
                        val quick = receiptScanner.scan(imageUri)
                        applyScan(
                            receipt = quick,
                            suggestedLabelName = null,
                            intro = "Smart scan unavailable (${smart.reason}), so Quick scan was used:"
                        )
                    }
                }
                achievementManager.onReceiptScanned()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(scanMessage = "Couldn't read that image. Try another photo.") }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun dismissScanMessage() {
        _uiState.update { it.copy(scanMessage = null) }
    }

    private fun applyScan(receipt: ParsedReceipt, suggestedLabelName: String?, intro: String) {
        if (receipt.isEmpty) {
            _uiState.update {
                it.copy(scanMessage = "$intro no receipt details found. Try a clearer, flatter photo in good light.")
            }
            return
        }

        _uiState.update { state ->
            // Use the suggested label only if the user hasn't picked one yet (still on "Other").
            val currentIsDefault = state.labels.firstOrNull { it.id == state.labelId }
                ?.systemKey == com.sosina.terefe.budgetingapp.domain.model.Label.SYSTEM_KEY_EXPENSE_OTHER
            val suggested = suggestedLabelName?.let { name ->
                state.topLevelLabels.firstOrNull { it.name.equals(name, ignoreCase = true) }
            }
            val useSuggestion = suggested != null && (state.labelId == null || currentIsDefault)

            state.copy(
                items = if (receipt.items.isNotEmpty()) {
                    receipt.items.map { ItemDraft(name = it.name, priceText = Money.toInputText(it.price)) }
                } else state.items,
                amountText = receipt.total?.let { Money.toInputText(it) } ?: state.amountText,
                title = state.title.ifBlank { receipt.storeName.orEmpty() },
                date = receipt.date ?: state.date,
                labelId = if (useSuggestion) suggested!!.id else state.labelId,
                subLabelId = if (useSuggestion) null else state.subLabelId,
                amountError = null,
                scanMessage = scanSummary(receipt, intro, if (useSuggestion) suggested else null)
            )
        }
    }

    private fun scanSummary(receipt: ParsedReceipt, intro: String, label: Label?): String {
        val found = when (receipt.items.size) {
            0 -> "no items found, but other details were filled in."
            1 -> "found 1 item."
            else -> "found ${receipt.items.size} items."
        }
        val labelNote = label?.let { " Label set to ${it.emoji} ${it.name}." } ?: ""
        return "$intro $found$labelNote Check everything before saving."
    }

    // ---------------- Save & delete ----------------

    fun save() {
        val state = _uiState.value

        // If no total was typed but there are items, the items decide the total.
        val amount = state.amountCents ?: if (state.hasItems) state.itemsTotal else null
        if (amount == null || amount <= 0L) {
            _uiState.update { it.copy(amountError = "Enter an amount above zero") }
            return
        }

        val items = state.items.map { draft ->
            val price = Money.parse(draft.priceText) ?: 0L
            TransactionItem(
                id = draft.id,
                name = draft.name.trim().ifBlank { if (price > 0) "Item" else "" },
                price = price,
                quantity = draft.quantity
            )
        }
        val dateTime = LocalDateTime.of(state.date, state.time)

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            val base = original
            val transaction = if (base != null) {
                base.copy(
                    amount = amount,
                    labelId = state.labelId,
                    subLabelId = state.subLabelId,
                    dateTime = dateTime,
                    title = state.title,
                    note = state.note,
                    items = items
                )
            } else {
                Transaction(
                    amount = amount,
                    labelId = state.labelId,
                    subLabelId = state.subLabelId,
                    dateTime = dateTime,
                    title = state.title,
                    note = state.note,
                    items = items
                )
            }
            transactionRepository.saveTransaction(transaction)
            // Only new expenses count, not edits.
            if (base == null) {
                roastManager.onExpensesAdded(listOf(transaction))
                achievementManager.onExpensesAdded(listOf(transaction))
            }
            _events.send(ExpenseEditEvent.Saved)
        }
    }

    fun delete() {
        val transaction = original ?: return
        viewModelScope.launch {
            transactionRepository.deleteTransaction(transaction)
            _events.send(ExpenseEditEvent.Deleted(transaction))
        }
    }

    /** Only digits and a decimal separator. */
    private fun cleanAmount(text: String): String =
        text.filter { it.isDigit() || it == '.' || it == ',' }.take(15)
}
