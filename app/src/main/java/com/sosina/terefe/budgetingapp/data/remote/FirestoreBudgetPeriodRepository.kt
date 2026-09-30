package com.sosina.terefe.budgetingapp.data.remote

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.BudgetPeriod
import com.sosina.terefe.budgetingapp.domain.model.DateRange
import com.sosina.terefe.budgetingapp.domain.model.PeriodRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/**
 * Budget months for a signed-in user, stored in Firestore.
 *
 * Dates are stored as day numbers (days since 1970), just like in Room,
 * so they sort and compare correctly.
 *
 * Each period's ID comes from its start date (e.g. "p_2026-09-01"),
 * so two phones creating the same month end up with one document, not two.
 */
class FirestoreBudgetPeriodRepository(
    private val paths: UserPaths,
    private val settingsRepository: SettingsRepository
) : BudgetPeriodRepository {

    private val createLock = Mutex()

    override suspend fun getPeriodFor(date: LocalDate): BudgetPeriod {
        findContaining(date)?.let { return it }
        return createLock.withLock {
            findContaining(date) ?: createPeriodsReaching(date)
        }
    }

    override suspend fun getPreviousPeriod(period: BudgetPeriod): BudgetPeriod? =
        findContaining(period.startDate.minusDays(1))

    override suspend fun getEarliestPeriod(): BudgetPeriod? =
        paths.periods.orderBy("startDate", Query.Direction.ASCENDING).limit(1)
            .get().await().documents.firstOrNull()?.toPeriod()

    override suspend fun getFinishedPeriods(today: LocalDate): List<BudgetPeriod> =
        paths.periods.whereLessThan("endDate", today.toEpochDay())
            .get().await().documents
            .mapNotNull { it.toPeriod() }
            .sortedBy { it.startDate }

    override fun observeAllPeriods(): Flow<List<BudgetPeriod>> =
        paths.periods.orderBy("startDate", Query.Direction.DESCENDING)
            .snapshotFlow()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toPeriod() } }

    // ---------------- Helpers ----------------

    /** The latest period starting on or before [date], if it actually contains [date]. */
    private suspend fun findContaining(date: LocalDate): BudgetPeriod? =
        paths.periods
            .whereLessThanOrEqualTo("startDate", date.toEpochDay())
            .orderBy("startDate", Query.Direction.DESCENDING)
            .limit(1)
            .get().await()
            .documents.firstOrNull()?.toPeriod()
            ?.takeIf { date in it }

    /** Same approach as the Room version: fill months with no gaps until [date] is covered. */
    private suspend fun createPeriodsReaching(date: LocalDate): BudgetPeriod {
        val settings = settingsRepository.settings.first()
        val rules = PeriodRules(settings.periodMode, settings.paydayDay)

        val all = paths.periods.get().await().documents.mapNotNull { it.toPeriod() }
        val latest = all.maxByOrNull { it.startDate }
        val earliest = all.minByOrNull { it.startDate }

        val toCreate = mutableListOf<DateRange>()
        when {
            latest == null || earliest == null ->
                toCreate += rules.rangeContaining(date)

            date.isAfter(latest.endDate) -> {
                var range = rules.rangeStartingOn(latest.endDate.plusDays(1))
                while (true) {
                    toCreate += range
                    if (!date.isAfter(range.end)) break
                    range = rules.rangeStartingOn(range.end.plusDays(1))
                    check(toCreate.size < MAX_STEPS) { "Date $date is too far in the future" }
                }
            }

            date.isBefore(earliest.startDate) -> {
                var range = rules.rangeEndingOn(earliest.startDate.minusDays(1))
                while (true) {
                    toCreate += range
                    if (!date.isBefore(range.start)) break
                    range = rules.rangeEndingOn(range.start.minusDays(1))
                    check(toCreate.size < MAX_STEPS) { "Date $date is too far in the past" }
                }
            }

            else -> error("No budget month contains $date")
        }

        val periods = toCreate.map { BudgetPeriod(periodId(it.start), it.start, it.end) }
        paths.db.writeInBatches(periods.map { period ->
            { batch -> batch.set(paths.periods.document(period.id), period.toMap()) }
        })
        return periods.last()
    }

    private companion object {
        const val MAX_STEPS = 1200
        fun periodId(start: LocalDate) = "p_$start"
    }
}

// ---------------- Converting ----------------

internal fun BudgetPeriod.toMap(): Map<String, Any> = mapOf(
    "startDate" to startDate.toEpochDay(),
    "endDate" to endDate.toEpochDay()
)

internal fun DocumentSnapshot.toPeriod(): BudgetPeriod? {
    val start = getLong("startDate") ?: return null
    val end = getLong("endDate") ?: return null
    return BudgetPeriod(id = id, startDate = LocalDate.ofEpochDay(start), endDate = LocalDate.ofEpochDay(end))
}
