package com.sosina.terefe.budgetingapp.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.IncomeRepository
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.BudgetPeriod
import com.sosina.terefe.budgetingapp.domain.model.DateRange
import com.sosina.terefe.budgetingapp.domain.model.LabelSpending
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import com.sosina.terefe.budgetingapp.ui.dashboard.title
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

enum class StatsRange { THIS_MONTH, LAST_MONTH, CUSTOM, ALL_TIME }

enum class ChartType { DONUT, BARS }

/** The chosen range, worked out into real dates. */
private data class ResolvedRange(
    val type: StatsRange,
    val range: DateRange?,        // null = nothing to show (e.g. no last month yet)
    val title: String,
    val savedRange: DateRange?,   // for All time: just the finished months
    val note: String?
)

/** The numbers for one range. */
private data class RangeTotals(
    val income: Long,
    val spent: Long,
    val byLabel: List<LabelSpending>,
    val saved: Long?,
    val transactions: List<Transaction>
)

/** Something bought often or expensively, for the "Top items" list. */
data class TopItem(
    val name: String,
    val total: Long,
    val count: Int
)

data class StatsUiState(
    val isLoading: Boolean = true,
    val rangeType: StatsRange = StatsRange.THIS_MONTH,
    val title: String = "",
    val income: Long = 0,
    val spent: Long = 0,
    val resultLabel: String = "Left",
    val resultAmount: Long = 0,
    val note: String? = null,
    val entries: List<ChartEntry> = emptyList(),
    val chartType: ChartType = ChartType.DONUT,
    val customRange: DateRange? = null,
    val range: DateRange? = null,
    val dailyTotals: Map<LocalDate, Long> = emptyMap(),
    val topItems: List<TopItem> = emptyList(),
    val currencyCode: String = Money.deviceCurrencyCode()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    periodRepository: BudgetPeriodRepository,
    private val incomeRepository: IncomeRepository,
    private val transactionRepository: TransactionRepository,
    labelRepository: LabelRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    private val rangeType = MutableStateFlow(StatsRange.THIS_MONTH)
    private val customRange = MutableStateFlow<DateRange?>(null)
    private val chartType = MutableStateFlow(ChartType.DONUT)

    /** Turns the chosen chip into actual dates. */
    private val resolved: Flow<ResolvedRange> = combine(
        rangeType, customRange, periodRepository.observeAllPeriods()
    ) { type, custom, periods -> resolve(type, custom, periods) }

    /** The resolved range together with its live totals. */
    private val rangeWithTotals: Flow<Pair<ResolvedRange, RangeTotals?>> =
        resolved.flatMapLatest { r ->
            val range = r.range
            if (range == null) flowOf(r to null)
            else totalsFor(range, r.savedRange).map { r to it }
        }

    private val labelsById = combine(
        labelRepository.observeLabels(LabelType.EXPENSE),
        labelRepository.observeLabels(LabelType.INCOME)
    ) { e, i -> (e + i).associateBy { it.id } }

    val uiState: StateFlow<StatsUiState> = combine(
        rangeWithTotals, labelsById, settingsRepository.settings, chartType, customRange
    ) { (r, totals), labels, settings, chart, custom ->
        val income = totals?.income ?: 0L
        val spent = totals?.spent ?: 0L
        val diff = income - spent

        val (resultLabel, resultAmount) = when (r.type) {
            StatsRange.THIS_MONTH -> (if (diff >= 0) "Left" else "Overspent") to diff
            StatsRange.LAST_MONTH -> (if (diff >= 0) "Saved" else "Overspent") to diff
            StatsRange.CUSTOM -> "Difference" to diff
            StatsRange.ALL_TIME -> {
                val saved = totals?.saved ?: 0L
                (if (saved >= 0) "Saved" else "Overspent overall") to saved
            }
        }

        // Chart pieces, biggest first.
        val spentTotal = totals?.byLabel?.sumOf { it.total }?.takeIf { it > 0 } ?: 1L
        val entries = totals?.byLabel.orEmpty()
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
            .map { s ->
                val label = s.labelId?.let(labels::get)
                ChartEntry(
                    key = s.labelId ?: "uncategorized",
                    label = label?.name ?: "Uncategorized",
                    emoji = label?.emoji ?: "❔",
                    color = label?.color ?: 0xFF90A4AE,
                    value = s.total,
                    fraction = s.total.toFloat() / spentTotal
                )
            }

        StatsUiState(
            isLoading = false,
            rangeType = r.type,
            title = r.title,
            income = income,
            spent = spent,
            resultLabel = resultLabel,
            resultAmount = resultAmount,
            note = r.note,
            entries = entries,
            chartType = chart,
            customRange = custom,
            range = r.range,
            dailyTotals = totals?.transactions.orEmpty()
                .groupBy { it.dateTime.toLocalDate() }
                .mapValues { (_, list) -> list.sumOf { it.amount } },
            topItems = topItemsOf(totals?.transactions.orEmpty()),
            currencyCode = settings.currencyCode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState()
    )

    fun selectRange(type: StatsRange) {
        rangeType.value = type
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        customRange.value = if (end.isBefore(start)) DateRange(end, start) else DateRange(start, end)
        rangeType.value = StatsRange.CUSTOM
    }

    fun toggleChartType() {
        chartType.update { if (it == ChartType.DONUT) ChartType.BARS else ChartType.DONUT }
    }

    /** Live income, spending, and per-label totals for a range. */
    private fun totalsFor(range: DateRange, savedRange: DateRange?): Flow<RangeTotals> {
        // All-time savings = income minus spending over every finished month.
        // Because months follow each other with no gaps, that's one simple sum.
        val savedFlow: Flow<Long?> =
            if (savedRange == null) flowOf(null)
            else combine(
                incomeRepository.observeTotal(savedRange.start, savedRange.end),
                transactionRepository.observeTotalSpent(savedRange.start, savedRange.end)
            ) { i, s -> i - s }

        return combine(
            incomeRepository.observeTotal(range.start, range.end),
            transactionRepository.observeTotalSpent(range.start, range.end),
            transactionRepository.observeSpendingByLabel(range.start, range.end),
            savedFlow,
            transactionRepository.observeTransactions(range.start, range.end)
        ) { income, spent, byLabel, saved, transactions ->
            RangeTotals(income, spent, byLabel, saved, transactions)
        }
    }

    private fun resolve(
        type: StatsRange,
        custom: DateRange?,
        periods: List<BudgetPeriod>   // newest first
    ): ResolvedRange {
        val today = LocalDate.now()
        val current = periods.firstOrNull { today in it }

        return when (type) {
            StatsRange.THIS_MONTH -> ResolvedRange(
                type = type,
                range = current?.let { DateRange(it.startDate, it.endDate) },
                title = current?.title() ?: "This month",
                savedRange = null,
                note = null
            )

            StatsRange.LAST_MONTH -> {
                val previous = current?.let { c ->
                    periods.firstOrNull { it.endDate == c.startDate.minusDays(1) }
                }
                ResolvedRange(
                    type = type,
                    range = previous?.let { DateRange(it.startDate, it.endDate) },
                    title = previous?.title() ?: "Last month",
                    savedRange = null,
                    note = if (previous == null) "There's no previous month yet." else null
                )
            }

            StatsRange.ALL_TIME -> {
                val earliest = periods.lastOrNull()
                if (earliest == null || current == null) {
                    ResolvedRange(type, null, "All time", null, null)
                } else {
                    val lastFinishedDay = current.startDate.minusDays(1)
                    val savedRange =
                        if (lastFinishedDay.isBefore(earliest.startDate)) null
                        else DateRange(earliest.startDate, lastFinishedDay)
                    ResolvedRange(
                        type = type,
                        range = DateRange(earliest.startDate, current.endDate),
                        title = "Since ${earliest.startDate.format(LONG_DATE)}",
                        savedRange = savedRange,
                        note = "Saved counts finished months only. " +
                            "This month is added once it ends."
                    )
                }
            }

            StatsRange.CUSTOM -> {
                if (custom == null) {
                    ResolvedRange(type, null, "Custom range", null, "Pick a date range.")
                } else {
                    // Partial = the range cuts through the middle of a budget month.
                    val partial = periods.any { p ->
                        val overlaps = !p.endDate.isBefore(custom.start) && !p.startDate.isAfter(custom.end)
                        overlaps && (p.startDate.isBefore(custom.start) || p.endDate.isAfter(custom.end))
                    }
                    ResolvedRange(
                        type = type,
                        range = custom,
                        title = "${custom.start.format(LONG_DATE)} – ${custom.end.format(LONG_DATE)}",
                        savedRange = null,
                        note = if (partial) "Includes partial months." else null
                    )
                }
            }
        }
    }

    private companion object {
        val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    }
}

/**
 * Adds up what's bought most, by name.
 * Receipt items count individually ("Milk" from every grocery trip);
 * expenses without items count by their title.
 * Names are matched ignoring case, so "milk" and "Milk" are the same.
 */
private fun topItemsOf(transactions: List<Transaction>, limit: Int = 8): List<TopItem> {
    data class Tally(val displayName: String, var total: Long = 0, var count: Int = 0)
    val tallies = linkedMapOf<String, Tally>()

    fun add(name: String?, amount: Long) {
        val clean = name?.trim().orEmpty()
        if (clean.isEmpty() || amount <= 0) return
        val tally = tallies.getOrPut(clean.lowercase()) { Tally(clean) }
        tally.total += amount
        tally.count++
    }

    transactions.forEach { t ->
        if (t.items.isNotEmpty()) t.items.forEach { add(it.name, it.price) }
        else add(t.title, t.amount)
    }

    return tallies.values
        .sortedByDescending { it.total }
        .take(limit)
        .map { TopItem(it.displayName, it.total, it.count) }
}
