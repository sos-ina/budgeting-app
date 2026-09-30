package com.sosina.terefe.budgetingapp.ui.split

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.funstate.AchievementManager
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.data.scan.ReceiptScanner
import com.sosina.terefe.budgetingapp.data.scan.SmartReceiptScanner
import com.sosina.terefe.budgetingapp.data.scan.SmartScanOutcome
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import com.sosina.terefe.budgetingapp.domain.model.TransactionItem
import com.sosina.terefe.budgetingapp.domain.split.ME
import com.sosina.terefe.budgetingapp.domain.split.SplitCalculator
import com.sosina.terefe.budgetingapp.domain.split.SplitItem
import com.sosina.terefe.budgetingapp.domain.split.SplitPerson
import com.sosina.terefe.budgetingapp.domain.split.SplitResult
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
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** One bill item while it's being typed. Empty sharedBy = everyone. */
data class SplitItemDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val priceText: String = "",
    val sharedBy: Set<String> = emptySet()
)

data class SplitBillUiState(
    val title: String = "",
    val people: List<SplitPerson> = listOf(SplitPerson(ME, "Me")),
    val items: List<SplitItemDraft> = emptyList(),
    val taxText: String = "",
    val tipText: String = "",
    val serviceText: String = "",
    val isScanning: Boolean = false,
    val message: String? = null,
    val isAddingToBudget: Boolean = false,
    val currencyCode: String = Money.deviceCurrencyCode()
) {
    val extras: Long
        get() = listOf(taxText, tipText, serviceText).sumOf { Money.parse(it) ?: 0L }

    /** The live calculation, redone on every change. */
    val result: SplitResult
        get() = SplitCalculator.calculate(
            people = people,
            items = items.map {
                SplitItem(it.id, it.name.ifBlank { "Item" }, Money.parse(it.priceText) ?: 0L, it.sharedBy)
            },
            extras = extras
        )
}

sealed interface SplitBillEvent {
    data object AddedToBudget : SplitBillEvent
}

@HiltViewModel
class SplitBillViewModel @Inject constructor(
    private val receiptScanner: ReceiptScanner,
    private val smartReceiptScanner: SmartReceiptScanner,
    private val transactionRepository: TransactionRepository,
    private val labelRepository: LabelRepository,
    private val achievementManager: AchievementManager,
    settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplitBillUiState())
    val uiState: StateFlow<SplitBillUiState> = _uiState.asStateFlow()

    private val _events = Channel<SplitBillEvent>(Channel.BUFFERED)
    val events: Flow<SplitBillEvent> = _events.receiveAsFlow()

    /** So one bill split only counts once towards achievements. */
    private var splitCounted = false

    private fun countSplit() {
        if (!splitCounted) {
            splitCounted = true
            achievementManager.onBillSplit()
        }
    }

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s -> _uiState.update { it.copy(currencyCode = s.currencyCode) } }
        }
    }

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title.take(60)) }

    // ---------------- People ----------------

    /** Adds someone. A blank name becomes "Person 2", "Person 3", etc. */
    fun addPerson(name: String) {
        _uiState.update { state ->
            val finalName = name.trim().ifBlank { "Person ${state.people.size + 1}" }.take(30)
            state.copy(people = state.people + SplitPerson(UUID.randomUUID().toString(), finalName))
        }
    }

    fun renamePerson(id: String, name: String) {
        _uiState.update { state ->
            state.copy(people = state.people.map {
                if (it.id == id) it.copy(name = name.take(30).ifBlank { it.name }) else it
            })
        }
    }

    /** Removes someone (never "Me") and takes them off every item. */
    fun removePerson(id: String) {
        if (id == ME) return
        _uiState.update { state ->
            state.copy(
                people = state.people.filterNot { it.id == id },
                items = state.items.map { it.copy(sharedBy = it.sharedBy - id) }
            )
        }
    }

    // ---------------- Items ----------------

    fun addItem() = _uiState.update { it.copy(items = it.items + SplitItemDraft()) }

    fun onItemNameChange(itemId: String, name: String) = updateItem(itemId) { it.copy(name = name.take(60)) }

    fun onItemPriceChange(itemId: String, text: String) = updateItem(itemId) { it.copy(priceText = cleanAmount(text)) }

    fun removeItem(itemId: String) = _uiState.update { s -> s.copy(items = s.items.filterNot { it.id == itemId }) }

    /** Everyone shares this item (the default). */
    fun setItemForEveryone(itemId: String) = updateItem(itemId) { it.copy(sharedBy = emptySet()) }

    /**
     * Taps on a person's chip for an item.
     * Starting from "everyone", the first tap means "just this person".
     * Removing the last person switches back to "everyone".
     */
    fun toggleItemPerson(itemId: String, personId: String) = updateItem(itemId) { item ->
        val current = item.sharedBy
        val updated = when {
            current.isEmpty() -> setOf(personId)
            personId in current -> current - personId
            else -> current + personId
        }
        // Everyone selected one by one is the same as "everyone".
        val allIds = _uiState.value.people.map { it.id }.toSet()
        item.copy(sharedBy = if (updated == allIds) emptySet() else updated)
    }

    private fun updateItem(itemId: String, change: (SplitItemDraft) -> SplitItemDraft) {
        _uiState.update { s -> s.copy(items = s.items.map { if (it.id == itemId) change(it) else it }) }
    }

    // ---------------- Extras ----------------

    fun onTaxChange(text: String) = _uiState.update { it.copy(taxText = cleanAmount(text)) }
    fun onTipChange(text: String) = _uiState.update { it.copy(tipText = cleanAmount(text)) }
    fun onServiceChange(text: String) = _uiState.update { it.copy(serviceText = cleanAmount(text)) }

    // ---------------- Scanning ----------------

    fun createPhotoUri(): Uri = receiptScanner.createPhotoUri()

    fun scanReceipt(imageUri: Uri) {
        if (_uiState.value.isScanning) return
        _uiState.update { it.copy(isScanning = true, message = null) }

        viewModelScope.launch {
            try {
                val receipt = when (val smart = smartReceiptScanner.scan(imageUri, labelNames = emptyList())) {
                    is SmartScanOutcome.Success -> smart.receipt
                    is SmartScanOutcome.Unavailable -> receiptScanner.scan(imageUri)
                }
                achievementManager.onReceiptScanned()

                if (receipt.items.isEmpty()) {
                    _uiState.update { it.copy(message = "No items found. Try a clearer photo, or add items by hand.") }
                    return@launch
                }

                val itemsSum = receipt.items.sumOf { it.price }
                // If the total is higher than the items, the difference is usually tax or service.
                val difference = (receipt.total ?: 0L) - itemsSum

                _uiState.update { state ->
                    state.copy(
                        title = state.title.ifBlank { receipt.storeName.orEmpty() },
                        items = receipt.items.map { SplitItemDraft(name = it.name, priceText = Money.toInputText(it.price)) },
                        taxText = if (difference > 0) Money.toInputText(difference) else state.taxText,
                        message = buildString {
                            append("Found ${receipt.items.size} items. Tap the names under each item to choose who shared it.")
                            if (difference > 0) append(" The difference to the receipt total was added as tax.")
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(message = "Couldn't read that image. Try another photo.") }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    // ---------------- Results ----------------

    /** A message listing everyone's share, ready to send in a chat. */
    fun shareText(): String {
        countSplit()
        val state = _uiState.value
        val result = state.result
        val money = { cents: Long -> Money.format(cents, state.currencyCode) }
        return buildString {
            appendLine(if (state.title.isNotBlank()) "🧾 ${state.title}" else "🧾 Bill split")
            result.shares.forEach { share -> appendLine("${share.person.name}: ${money(share.total)}") }
            append("Total: ${money(result.grandTotal)}")
        }
    }

    /**
     * Creates a normal expense with my items and my part of the extras.
     * The extras show up as "Other / unlisted" on the expense.
     */
    fun addMyShareToBudget() {
        val state = _uiState.value
        val myShare = state.result.shares.firstOrNull { it.person.id == ME } ?: return
        if (myShare.total <= 0 || state.isAddingToBudget) return
        _uiState.update { it.copy(isAddingToBudget = true) }

        viewModelScope.launch {
            // Restaurants are the usual case, so use Food & Dining when it exists.
            val labels = labelRepository.observeLabels(LabelType.EXPENSE).first()
            val label = labels.firstOrNull { it.systemKey == "expense_food" }
                ?: labelRepository.getOtherExpenseLabel()

            val myExpense = Transaction(
                amount = myShare.total,
                labelId = label.id,
                dateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES),
                title = state.title.ifBlank { "Split bill" },
                note = "My share of a bill split between ${state.people.size} people",
                items = myShare.lines.filter { it.amount > 0 }.map { line ->
                    TransactionItem(
                        name = if (line.sharedWith > 1) "${line.itemName} (1/${line.sharedWith})" else line.itemName,
                        price = line.amount
                    )
                }
            )
            transactionRepository.saveTransaction(myExpense)
            countSplit()
            achievementManager.onExpensesAdded(listOf(myExpense))
            _uiState.update { it.copy(isAddingToBudget = false) }
            _events.send(SplitBillEvent.AddedToBudget)
        }
    }

    private fun cleanAmount(text: String) = text.filter { it.isDigit() || it == '.' || it == ',' }.take(15)
}
