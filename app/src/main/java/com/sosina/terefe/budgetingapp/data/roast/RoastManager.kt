package com.sosina.terefe.budgetingapp.data.roast

import android.util.Log
import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.IncomeRepository
import com.sosina.terefe.budgetingapp.data.repository.LabelRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.mascot.Mascot
import com.sosina.terefe.budgetingapp.domain.mascot.MascotMood
import com.sosina.terefe.budgetingapp.domain.model.MascotType
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** A roast waiting to be shown on the dashboard. */
data class Roast(
    val text: String,
    val mascot: MascotType,
    val mood: MascotMood
)

/**
 * Watches for new expenses and, if roast mode is on, writes a snarky line about them.
 * It works in its own background scope, so leaving the expense screen doesn't cancel it.
 */
@Singleton
class RoastManager @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val periodRepository: BudgetPeriodRepository,
    private val incomeRepository: IncomeRepository,
    private val transactionRepository: TransactionRepository,
    private val labelRepository: LabelRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _pending = MutableStateFlow<Roast?>(null)
    val pending: StateFlow<Roast?> = _pending.asStateFlow()

    fun dismiss() {
        _pending.value = null
    }

    /** Call after saving NEW expenses (not edits). With several, the biggest one gets roasted. */
    fun onExpensesAdded(added: List<Transaction>) {
        if (added.isEmpty()) return
        scope.launch {
            try {
                val settings = settingsRepository.settings.first()
                if (!settings.roastMode) return@launch

                val expense = added.maxBy { it.amount }
                val context = buildContext(expense, settings.currencyCode)
                _pending.value = Roast(
                    text = RoastWriter.write(context),
                    mascot = settings.mascot,
                    mood = Mascot.moodFor(context.income, context.spent)
                )
            } catch (e: Exception) {
                Log.w("Roast", "Couldn't write a roast", e) // a missing roast is never a problem
            }
        }
    }

    private suspend fun buildContext(t: Transaction, currencyCode: String): RoastContext {
        val date = t.dateTime.toLocalDate()
        val period = periodRepository.getPeriodFor(date)
        val income = incomeRepository.getTotal(period.startDate, period.endDate)
        val spent = transactionRepository.getTotalSpent(period.startDate, period.endDate)

        val label = t.labelId?.let { labelRepository.getLabel(it) }
        val subLabel = t.subLabelId?.let { labelRepository.getLabel(it) }
        // What to call it: the title if typed, otherwise the most specific label.
        val thing = t.title?.takeIf { it.isNotBlank() }
            ?: subLabel?.name?.lowercase()
            ?: label?.name?.lowercase()
            ?: "that"

        // How many of the same thing this week (Monday to today), including this one.
        val today = LocalDate.now()
        val weekStart = today.with(DayOfWeek.MONDAY)
        val thisWeek = transactionRepository.observeTransactions(weekStart, today).first()
        val sameThisWeek = thisWeek.count { other ->
            when {
                !t.title.isNullOrBlank() -> other.title.equals(t.title, ignoreCase = true)
                t.subLabelId != null -> other.subLabelId == t.subLabelId
                else -> other.labelId == t.labelId
            }
        }

        return RoastContext(
            thing = thing,
            amount = t.amount,
            currencyCode = currencyCode,
            income = income,
            spent = spent,
            periodDays = period.lengthInDays,
            daysLeft = (ChronoUnit.DAYS.between(today, period.endDate) + 1).coerceAtLeast(0),
            sameThisWeek = sameThisWeek,
            hour = t.dateTime.hour,
            isToday = date == today,
            isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY,
            timeText = t.dateTime.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        )
    }
}

/** Everything the roast writer knows about the expense and the month. */
data class RoastContext(
    val thing: String,
    val amount: Long,
    val currencyCode: String,
    val income: Long,
    val spent: Long,          // this month, including this expense
    val periodDays: Long,
    val daysLeft: Long,
    val sameThisWeek: Int,
    val hour: Int,
    val isToday: Boolean,
    val isWeekend: Boolean,
    val timeText: String
)

/**
 * Picks the most roastable situation, then a random line for it.
 * Lines use {placeholders} that are filled in from the context.
 */
object RoastWriter {

    fun write(c: RoastContext, random: Random = Random.Default): String {
        val money = { cents: Long -> Money.format(cents, c.currencyCode) }
        val left = c.income - c.spent
        val pctOfIncome = if (c.income > 0) (c.amount * 100 / c.income) else 0
        val leftPct = if (c.income > 0) (left * 100 / c.income) else 0
        val dailyBudget = if (c.income > 0 && c.periodDays > 0) c.income / c.periodDays else 0
        val daysOfBudget = if (dailyBudget > 0) c.amount.toDouble() / dailyBudget else 0.0

        val lines = when {
            c.income <= 0 -> NO_INCOME
            left < 0 -> OVERSPENT
            pctOfIncome >= 20 -> BIG
            c.sameThisWeek >= 3 -> REPEAT
            c.isToday && (c.hour >= 23 || c.hour < 5) -> LATE_NIGHT
            leftPct < 20 && c.daysLeft > 3 -> RUNNING_LOW
            c.isWeekend && random.nextBoolean() -> WEEKEND
            daysOfBudget >= 1.0 -> DAYS_OF_BUDGET
            else -> GENERIC
        }

        return lines.random(random)
            .replace("{thing}", c.thing)
            .replace("{amount}", money(c.amount))
            .replace("{over}", money(-left))
            .replace("{left}", money(left))
            .replace("{pct}", pctOfIncome.toString())
            .replace("{leftPct}", leftPct.toString())
            .replace("{daysLeft}", c.daysLeft.toString())
            .replace("{days}", String.format(java.util.Locale.getDefault(), "%.1f", daysOfBudget))
            .replace("{nth}", ordinal(c.sameThisWeek))
            .replace("{time}", c.timeText)
    }

    /** 1 -> "1st", 2 -> "2nd", 3 -> "3rd", 11 -> "11th", 22 -> "22nd"... */
    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }
        return "$n$suffix"
    }

    // ---------------- The lines ----------------

    private val NO_INCOME = listOf(
        "No income added this month, so technically you're spending pure vibes.",
        "Spending before adding income? Living dangerously, I see."
    )

    private val OVERSPENT = listOf(
        "And with that, you're officially overspent by {over}. Bold strategy.",
        "{over} over budget. Your wallet has filed a missing person report.",
        "Congratulations! You now spend more than you earn. Economists hate this one trick."
    )

    private val BIG = listOf(
        "That's {pct}% of your whole month in one go. Hope it was worth it.",
        "{amount} on {thing}? Your future self just felt a chill.",
        "One purchase, {pct}% of your income. Speedrunning the month, are we?"
    )

    private val REPEAT = listOf(
        "That's your {nth} {thing} this week. They know your name by now, don't they?",
        "{nth} {thing} this week. It's not a habit anymore, it's a lifestyle.",
        "{thing}, again? That's the {nth} time this week. I'm not judging. (I'm judging.)"
    )

    private val LATE_NIGHT = listOf(
        "Spending at {time}? Nothing good gets bought after midnight.",
        "{thing} at this hour? Go to sleep. Your budget needs rest too."
    )

    private val RUNNING_LOW = listOf(
        "Only {left} left for {daysLeft} days. {thing} was a brave choice.",
        "{leftPct}% of the month's money left, and you chose {thing}. Respect, I guess."
    )

    private val WEEKEND = listOf(
        "Weekend spending detected. Your budget would like a weekend off too.",
        "It's the weekend, so money isn't real. Right? ...Right?"
    )

    private val DAYS_OF_BUDGET = listOf(
        "That {thing} just cost {days} days of your daily budget.",
        "{amount}. That's about {days} days' worth of budget. Just saying."
    )

    private val GENERIC = listOf(
        "Noted. Judged. Filed.",
        "Another one for the collection.",
        "Your wallet felt that one."
    )
}
