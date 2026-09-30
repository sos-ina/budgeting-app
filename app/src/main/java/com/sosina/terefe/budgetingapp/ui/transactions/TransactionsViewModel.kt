package com.sosina.terefe.budgetingapp.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.IncomeRepository
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.BudgetPeriod
import com.sosina.terefe.budgetingapp.domain.model.DateRange
import com.sosina.terefe.budgetingapp.domain.model.Income
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import com.sosina.terefe.budgetingapp.ui.dashboard.ActivityRow
import com.sosina.terefe.budgetingapp.ui.dashboard.DayGroup
import com.sosina.terefe.budgetingapp.ui.dashboard.DeletedItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

enum class EntryTypeFilter { ALL, EXPENSES, INCOME }

/** Which months are being looked at. */
private data class Selection(
    val periodId: String? = null,
    val allTime: Boolean = false
)

/** The filters the user has set. */
private data class Filters(
    val query: String = "",
    val type: EntryTypeFilter = EntryTypeFilter.ALL,
    val labelId: String? = null
)

data class TransactionsUiState(
    val isLoading: Boolean = true,
    val selectedPeriod: BudgetPeriod? = null,
    val isAllTime: Boolean = false,
    val canGoOlder: Boolean = false,
    val canGoNewer: Boolean = false,
    val query: String = "",
    val typeFilter: EntryTypeFilter = EntryTypeFilter.ALL,
    val labelFilterId: String? = null,
    val filterLabels: List<Label> = emptyList(),   // top-level expense labels for the chips
    val days: List<DayGroup> = emptyList(),
    val resultCount: Int = 0,
    val expenseTotal: Long = 0,
    val incomeTotal: Long = 0,
    val currencyCode: String = Money.deviceCurrencyCode()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val periodRepository: BudgetPeriodRepository,
    private val transactionRepository: TransactionRepository,
    private val incomeRepository: IncomeRepository,
    labelRepository: LabelRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    private val selection = MutableStateFlow(Selection())
    private val filters = MutableStateFlow(Filters())

    /** All budget months, newest first. */
    private val periods = periodRepository.observeAllPeriods()

    /** The date range being shown: one month, or everything. */
    private val range = combine(periods, selection) { list, sel ->
        when {
            list.isEmpty() -> null
            sel.allTime -> DateRange(list.last().startDate, list.first().endDate)
            else -> list.firstOrNull { it.id == sel.periodId }
                ?.let { DateRange(it.startDate, it.endDate) }
        }
    }.distinctUntilChanged()

    /** The raw expenses and income in that range, before filters. */
    private val entries = range.filterNotNull().flatMapLatest { r ->
        combine(
            transactionRepository.observeTransactions(r.start, r.end),
            incomeRepository.observeIncomes(r.start, r.end)
        ) { transactions, incomes -> transactions to incomes }
    }

    private val labelsAndCurrency = combine(
        labelRepository.observeLabels(LabelType.EXPENSE),
        labelRepository.observeLabels(LabelType.INCOME),
        settingsRepository.settings
    ) { expense, income, settings ->
        Triple(expense, (expense + income).associateBy { it.id }, settings.currencyCode)
    }

    val uiState: StateFlow<TransactionsUiState> = combine(
        periods, selection, entries, filters, labelsAndCurrency
    ) { periodList, sel, (transactions, incomes), f, (expenseLabels, labelsById, currency) ->
        val index = periodList.indexOfFirst { it.id == sel.periodId }

        // ---------- Apply filters ----------
        val query = f.query.trim().lowercase()
        val shownTransactions =
            if (f.type == EntryTypeFilter.INCOME) emptyList()
            else transactions.filter { t ->
                (f.labelId == null || t.labelId == f.labelId || t.subLabelId == f.labelId) &&
                    (query.isEmpty() || t.matches(query, labelsById))
            }
        // A label filter means "expenses with this label", so income is hidden then.
        val shownIncomes =
            if (f.type == EntryTypeFilter.EXPENSES || f.labelId != null) emptyList()
            else incomes.filter { i -> query.isEmpty() || i.matches(query, labelsById) }

        TransactionsUiState(
            isLoading = false,
            selectedPeriod = periodList.getOrNull(index),
            isAllTime = sel.allTime,
            canGoOlder = !sel.allTime && index in 0 until periodList.lastIndex,
            canGoNewer = !sel.allTime && index > 0,
            query = f.query,
            typeFilter = f.type,
            labelFilterId = f.labelId,
            filterLabels = expenseLabels.filter { it.parentId == null && !it.isHidden },
            days = groupByDay(shownTransactions, shownIncomes, labelsById),
            resultCount = shownTransactions.size + shownIncomes.size,
            expenseTotal = shownTransactions.sumOf { it.amount },
            incomeTotal = shownIncomes.sumOf { it.amount },
            currencyCode = currency
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TransactionsUiState()
    )

    init {
        // Start on the current month.
        viewModelScope.launch {
            val current = periodRepository.getCurrentPeriod()
            selection.value = Selection(periodId = current.id)
        }
    }

    // ---------------- Month navigation ----------------

    fun goOlder() = moveBy(+1)   // periods are newest first, so older = further down the list
    fun goNewer() = moveBy(-1)

    private fun moveBy(step: Int) {
        viewModelScope.launch {
            val list = periodRepository.observeAllPeriods().first()
            val index = list.indexOfFirst { it.id == selection.value.periodId }
            list.getOrNull(index + step)?.let { selection.value = Selection(periodId = it.id) }
        }
    }

    fun toggleAllTime() {
        selection.update { it.copy(allTime = !it.allTime) }
    }

    // ---------------- Filters ----------------

    fun onQueryChange(query: String) = filters.update { it.copy(query = query.take(50)) }

    /**
     * Label chips are expense labels only, and any label filter hides income.
     * Switching to "Income" while one is active would otherwise show nothing
     * with no explanation, so it clears the label filter instead.
     */
    fun onTypeFilterChange(type: EntryTypeFilter) = filters.update {
        it.copy(type = type, labelId = if (type == EntryTypeFilter.INCOME) null else it.labelId)
    }

    /** Picking a label filter only makes sense for expenses, so it drops an "Income" type filter. */
    fun onLabelFilterChange(labelId: String?) = filters.update {
        it.copy(
            labelId = labelId,
            type = if (labelId != null && it.type == EntryTypeFilter.INCOME) EntryTypeFilter.ALL else it.type
        )
    }

    // ---------------- Undo ----------------

    fun restore(item: DeletedItem) {
        viewModelScope.launch {
            when (item) {
                is DeletedItem.IncomeItem -> incomeRepository.saveIncome(item.income)
                is DeletedItem.ExpenseItem -> transactionRepository.saveTransaction(item.transaction)
            }
        }
    }
}

// ---------------- Search matching ----------------

/** True if the search text appears in the store, note, labels, or any item name. */
private fun Transaction.matches(query: String, labels: Map<String, Label>): Boolean {
    val fields = buildList {
        title?.let(::add)
        note?.let(::add)
        labelId?.let { labels[it]?.name }?.let(::add)
        subLabelId?.let { labels[it]?.name }?.let(::add)
        items.forEach { add(it.name) }
    }
    return fields.any { it.lowercase().contains(query) }
}

private fun Income.matches(query: String, labels: Map<String, Label>): Boolean {
    val fields = listOfNotNull(note, labelId?.let { labels[it]?.name })
    return fields.any { it.lowercase().contains(query) }
}

// ---------------- Grouping ----------------

/** Mixes income and expenses, newest first, grouped by day. */
private fun groupByDay(
    transactions: List<Transaction>,
    incomes: List<Income>,
    labels: Map<String, Label>
): List<DayGroup> {
    val rows: List<Pair<LocalDateTime, ActivityRow>> =
        transactions.map { t ->
            t.dateTime to ActivityRow.Expense(
                transaction = t,
                label = t.labelId?.let(labels::get),
                subLabel = t.subLabelId?.let(labels::get)
            )
        } + incomes.map { i ->
            // Newest-first sort: key income to the day's latest moment so it lands
            // at the top of that day's rows, ahead of expenses (same as the dashboard).
            i.date.atTime(LocalTime.MAX) to ActivityRow.IncomeEntry(i, i.labelId?.let(labels::get))
        }

    return rows
        .sortedByDescending { it.first }
        .groupBy { it.first.toLocalDate() }
        .map { (date, list) -> DayGroup(date, list.map { it.second }) }
}
