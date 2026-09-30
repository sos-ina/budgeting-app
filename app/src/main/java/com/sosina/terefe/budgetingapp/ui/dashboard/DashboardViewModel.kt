package com.sosina.terefe.budgetingapp.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.IncomeRepository
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.data.sound.Sound
import com.sosina.terefe.budgetingapp.data.sound.SoundPlayer
import com.sosina.terefe.budgetingapp.domain.model.BudgetPeriod
import com.sosina.terefe.budgetingapp.domain.model.Income
import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import com.sosina.terefe.budgetingapp.domain.model.MascotType
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

/** One line in the dashboard list: either an expense or an income. */
sealed interface ActivityRow {
    val key: String

    data class Expense(
        val transaction: Transaction,
        val label: Label?,
        val subLabel: Label?
    ) : ActivityRow {
        override val key: String get() = "expense-${transaction.id}"
    }

    data class IncomeEntry(
        val income: Income,
        val label: Label?
    ) : ActivityRow {
        override val key: String get() = "income-${income.id}"
    }
}

/** All the rows for one day, shown under a "Today" / "Mon, 21 Sep" header. */
data class DayGroup(
    val date: LocalDate,
    val rows: List<ActivityRow>
)

/** Something that was just deleted and can still be brought back with "Undo". */
sealed interface DeletedItem {
    data class IncomeItem(val income: Income) : DeletedItem
    data class ExpenseItem(val transaction: Transaction) : DeletedItem
}

/** The "Add this month's salary?" question, when it should be shown. */
data class SalaryPrompt(
    val periodId: String,
    val periodStart: LocalDate,
    val suggestedAmount: Long,   // last salary, in cents
    val salaryLabelId: String
)

data class DashboardUiState(
    val isLoading: Boolean = true,
    val period: BudgetPeriod? = null,
    val currencyCode: String = Money.deviceCurrencyCode(),
    val mascot: MascotType = MascotType.NONE,
    val incomeTotal: Long = 0,
    val spentTotal: Long = 0,
    val days: List<DayGroup> = emptyList()
) {
    /** What's left of this month's money. Negative means overspent. */
    val left: Long get() = incomeTotal - spentTotal

    val isOverspent: Boolean get() = left < 0

    /** How full the progress bar is, from 0 (nothing spent) to 1 (all spent). */
    val spentFraction: Float
        get() = when {
            incomeTotal > 0 -> (spentTotal.toFloat() / incomeTotal).coerceIn(0f, 1f)
            spentTotal > 0 -> 1f
            else -> 0f
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val periodRepository: BudgetPeriodRepository,
    private val incomeRepository: IncomeRepository,
    private val transactionRepository: TransactionRepository,
    labelRepository: LabelRepository,
    private val settingsRepository: SettingsRepository,
    private val soundPlayer: SoundPlayer
) : ViewModel() {

    private val currentPeriod = MutableStateFlow<BudgetPeriod?>(null)

    private val incomes = currentPeriod.filterNotNull().flatMapLatest { period ->
        incomeRepository.observeIncomes(period.startDate, period.endDate)
    }

    private val transactions = currentPeriod.filterNotNull().flatMapLatest { period ->
        transactionRepository.observeTransactions(period.startDate, period.endDate)
    }

    /** Every label (expense and income) by ID, for showing names and emojis. */
    private val labelsById = combine(
        labelRepository.observeLabels(LabelType.EXPENSE),
        labelRepository.observeLabels(LabelType.INCOME)
    ) { expense, income -> (expense + income).associateBy { it.id } }

    val uiState: StateFlow<DashboardUiState> = combine(
        currentPeriod.filterNotNull(),
        incomes,
        transactions,
        labelsById,
        settingsRepository.settings
    ) { period, incomeList, transactionList, labels, settings ->
        DashboardUiState(
            isLoading = false,
            period = period,
            currencyCode = settings.currencyCode,
            mascot = settings.mascot,
            incomeTotal = incomeList.sumOf { it.amount },
            spentTotal = transactionList.sumOf { it.amount },
            days = buildDayGroups(incomeList, transactionList, labels)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState()
    )

    /**
     * Non-null when the dashboard should ask about this month's salary:
     * - this month has no salary yet,
     * - there's an earlier salary to suggest,
     * - and the user hasn't tapped "Not now" for this month.
     */
    val salaryPrompt: StateFlow<SalaryPrompt?> = combine(
        currentPeriod.filterNotNull(),
        incomes,
        labelsById,
        settingsRepository.salaryPromptDismissedFor
    ) { period, incomeList, labels, dismissedFor ->
        if (dismissedFor == period.id) return@combine null

        val salaryLabel = labels.values
            .firstOrNull { it.systemKey == Label.SYSTEM_KEY_INCOME_SALARY }
            ?: return@combine null

        if (incomeList.any { it.labelId == salaryLabel.id }) return@combine null

        val suggested = incomeRepository.getLatestSalaryAmount() ?: return@combine null

        SalaryPrompt(
            periodId = period.id,
            periodStart = period.startDate,
            suggestedAmount = suggested,
            salaryLabelId = salaryLabel.id
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    /** "Add salary" tapped: saves it on the first day of the month. */
    fun acceptSalaryPrompt(prompt: SalaryPrompt, amountText: String) {
        val amount = Money.parse(amountText)?.takeIf { it > 0 } ?: return
        viewModelScope.launch {
            incomeRepository.saveIncome(
                Income(
                    amount = amount,
                    labelId = prompt.salaryLabelId,
                    date = prompt.periodStart
                )
            )
            soundPlayer.play(Sound.KA_CHING)
        }
    }

    /** "Not now" tapped: don't ask again until next month. */
    fun dismissSalaryPrompt(prompt: SalaryPrompt) {
        viewModelScope.launch {
            settingsRepository.setSalaryPromptDismissedFor(prompt.periodId)
        }
    }

    init {
        refreshPeriod()
    }

    fun refreshPeriod() {
        viewModelScope.launch {
            currentPeriod.value = periodRepository.getCurrentPeriod()
        }
    }

    /** Brings back something deleted a moment ago ("Undo"). */
    fun restore(item: DeletedItem) {
        viewModelScope.launch {
            when (item) {
                is DeletedItem.IncomeItem -> incomeRepository.saveIncome(item.income)
                is DeletedItem.ExpenseItem -> transactionRepository.saveTransaction(item.transaction)
            }
        }
    }

    /**
     * Mixes income and expenses into one list, newest first, grouped by day.
     * Income has no time of day, so it's placed at the start of its day.
     */
    private fun buildDayGroups(
        incomes: List<Income>,
        transactions: List<Transaction>,
        labels: Map<String, Label>
    ): List<DayGroup> {
        val expenseRows: List<Pair<LocalDateTime, ActivityRow>> = transactions.map { t ->
            t.dateTime to ActivityRow.Expense(
                transaction = t,
                label = t.labelId?.let(labels::get),
                subLabel = t.subLabelId?.let(labels::get)
            )
        }
        // Sorting is newest-first, so income is keyed to the LATEST moment of its day
        // (not midnight) so it lands at the top of that day's rows, ahead of expenses.
        val incomeRows: List<Pair<LocalDateTime, ActivityRow>> = incomes.map { i ->
            i.date.atTime(LocalTime.MAX) to ActivityRow.IncomeEntry(
                income = i,
                label = i.labelId?.let(labels::get)
            )
        }

        return (expenseRows + incomeRows)
            .sortedByDescending { it.first }
            .groupBy { it.first.toLocalDate() } // keeps the newest-first order
            .map { (date, rows) -> DayGroup(date, rows.map { it.second }) }
    }
}
