package com.sosina.terefe.budgetingapp.data.funstate

import android.util.Log
import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.IncomeRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.data.sound.Sound
import com.sosina.terefe.budgetingapp.data.sound.SoundPlayer
import com.sosina.terefe.budgetingapp.domain.achievements.Achievement
import com.sosina.terefe.budgetingapp.domain.achievements.Counter
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import com.sosina.terefe.budgetingapp.ui.mascot.CelebrationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AchievementManager @Inject constructor(
    private val funState: FunStateRepository,
    private val celebrations: CelebrationManager,
    private val periodRepository: BudgetPeriodRepository,
    private val incomeRepository: IncomeRepository,
    private val transactionRepository: TransactionRepository,
    private val soundPlayer: SoundPlayer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ---------------- Events from around the app ----------------

    /** New expenses were saved (not edits). Also picks the right sound. */
    fun onExpensesAdded(added: List<Transaction>) = run {
        if (added.isEmpty()) return@run
        playExpenseSound(added)

        bump(Counter.EXPENSES, added.size)
        val now = java.time.LocalDateTime.now()
        if (now.hour < 4 && added.any { it.dateTime.toLocalDate() == now.toLocalDate() }) {
            unlock(Achievement.NIGHT_OWL)
        }
    }

    /** A receipt scan finished: the mascot just ate it. */
    fun onReceiptScanned() = run {
        soundPlayer.play(Sound.NOM)
        bump(Counter.SCANS, 1)
    }

    /**
     * Sad trombone if these expenses are what pushed the month over budget,
     * otherwise a friendly blip.
     */
    private suspend fun playExpenseSound(added: List<Transaction>) {
        val period = periodRepository.getPeriodFor(added.maxBy { it.dateTime }.dateTime.toLocalDate())
        val income = incomeRepository.getTotal(period.startDate, period.endDate)
        val spentNow = transactionRepository.getTotalSpent(period.startDate, period.endDate)
        val spentBefore = spentNow - added.sumOf { it.amount }

        val justWentOver = income > 0 && spentNow > income && spentBefore <= income
        soundPlayer.play(if (justWentOver) Sound.SAD_TROMBONE else Sound.BLIP)
    }

    /** A bill split was shared or added to the budget. */
    fun onBillSplit() = run { bump(Counter.SPLITS, 1) }

    /** Quick add saved at least one expense. */
    fun onQuickAdd() = run { bump(Counter.QUICK_ADDS, 1) }

    /** The current no-spend streak changed. */
    fun onStreak(days: Int) = run {
        Achievement.entries
            .filter { it.isStreak && days >= it.target }
            .forEach { unlock(it) }
    }

    /**
     * Looks at every finished budget month for Survived the Month and Super Saver.
     * Called when the app starts; cheap, since months are few.
     */
    fun checkFinishedMonths() = run {
        periodRepository.getFinishedPeriods(LocalDate.now()).forEach { period ->
            val income = incomeRepository.getTotal(period.startDate, period.endDate)
            if (income <= 0) return@forEach
            val spent = transactionRepository.getTotalSpent(period.startDate, period.endDate)

            if (spent <= income) unlock(Achievement.SURVIVED_THE_MONTH)
            if ((income - spent) * 100 / income >= 20) unlock(Achievement.SUPER_SAVER)
        }
    }

    // ---------------- Internals ----------------

    /** Runs [block] in the background; a failure never affects the rest of the app. */
    private fun run(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.w("Achievements", "Achievement check failed", e)
            }
        }
    }

    private suspend fun bump(counter: Counter, by: Int) {
        val total = funState.increment(counter.name, by)
        Achievement.entries
            .filter { it.counter == counter && total >= it.target }
            .forEach { unlock(it) }
    }

    private suspend fun unlock(achievement: Achievement) {
        if (funState.unlock(achievement.name)) {
            celebrations.celebrate(achievement.emoji, "Achievement unlocked:\n${achievement.title}!")
            // If several unlock at once, give each one a moment on screen.
            delay(3_800)
        }
    }
}
