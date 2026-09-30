package com.sosina.terefe.budgetingapp.data.repository

import androidx.room.withTransaction
import com.sosina.terefe.budgetingapp.data.local.AppDatabase
import com.sosina.terefe.budgetingapp.data.local.BudgetPeriodEntity
import com.sosina.terefe.budgetingapp.data.local.dao.BudgetPeriodDao
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.model.BudgetPeriod
import com.sosina.terefe.budgetingapp.domain.model.DateRange
import com.sosina.terefe.budgetingapp.domain.model.PeriodRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the app can do with budget months. */
interface BudgetPeriodRepository {

    /**
     * The budget month containing [date].
     * If it doesn't exist yet, it's created (along with any months in between).
     */
    suspend fun getPeriodFor(date: LocalDate): BudgetPeriod

    /** The budget month containing today. */
    suspend fun getCurrentPeriod(): BudgetPeriod = getPeriodFor(LocalDate.now())

    /** The month just before [period], or null if there isn't one. Never creates anything. */
    suspend fun getPreviousPeriod(period: BudgetPeriod): BudgetPeriod?

    /** The very first budget month, used for "All time" stats. */
    suspend fun getEarliestPeriod(): BudgetPeriod?

    /** Months that have completely ended before [today], oldest first (for savings). */
    suspend fun getFinishedPeriods(today: LocalDate = LocalDate.now()): List<BudgetPeriod>

    /** Every budget month, newest first, live-updating. */
    fun observeAllPeriods(): Flow<List<BudgetPeriod>>
}

@Singleton
class RoomBudgetPeriodRepository @Inject constructor(
    private val database: AppDatabase,
    private val periodDao: BudgetPeriodDao,
    private val settingsRepository: SettingsRepository
) : BudgetPeriodRepository {

    // Makes sure two parts of the app never create the same month at the same time.
    private val createLock = Mutex()

    override suspend fun getPeriodFor(date: LocalDate): BudgetPeriod {
        // Fast path: the month already exists (true almost every time).
        periodDao.getContaining(date)?.let { return it.toDomain() }

        return createLock.withLock {
            // Check again inside the lock, in case another call just created it.
            periodDao.getContaining(date)?.toDomain() ?: createPeriodsReaching(date)
        }
    }

    override suspend fun getPreviousPeriod(period: BudgetPeriod): BudgetPeriod? =
        periodDao.getContaining(period.startDate.minusDays(1))?.toDomain()

    override suspend fun getEarliestPeriod(): BudgetPeriod? =
        periodDao.getEarliest()?.toDomain()

    override suspend fun getFinishedPeriods(today: LocalDate): List<BudgetPeriod> =
        periodDao.getFinishedBefore(today).map { it.toDomain() }

    override fun observeAllPeriods(): Flow<List<BudgetPeriod>> =
        periodDao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * Creates months until one contains [date].
     *
     * Periods always follow each other with no gaps, so:
     * - no periods yet        -> create the month around [date]
     * - [date] is in the future -> keep adding months after the latest one
     * - [date] is in the past   -> keep adding months before the earliest one
     *
     * The CURRENT settings are used only for new months,
     * so existing months never change their dates.
     */
    private suspend fun createPeriodsReaching(date: LocalDate): BudgetPeriod {
        val settings = settingsRepository.settings.first()
        val rules = PeriodRules(settings.periodMode, settings.paydayDay)

        return database.withTransaction {
            val latest = periodDao.getLatest()
            val earliest = periodDao.getEarliest()

            when {
                latest == null || earliest == null ->
                    insert(rules.rangeContaining(date))

                date.isAfter(latest.endDate) -> {
                    var range = rules.rangeStartingOn(latest.endDate.plusDays(1))
                    var steps = 0
                    while (date.isAfter(range.end)) {
                        insert(range)
                        range = rules.rangeStartingOn(range.end.plusDays(1))
                        check(++steps < MAX_STEPS) { "Date $date is too far in the future" }
                    }
                    insert(range)
                }

                date.isBefore(earliest.startDate) -> {
                    var range = rules.rangeEndingOn(earliest.startDate.minusDays(1))
                    var steps = 0
                    while (date.isBefore(range.start)) {
                        insert(range)
                        range = rules.rangeEndingOn(range.start.minusDays(1))
                        check(++steps < MAX_STEPS) { "Date $date is too far in the past" }
                    }
                    insert(range)
                }

                // Can't happen, since periods never have gaps.
                else -> error("No budget month contains $date")
            }
        }
    }

    private suspend fun insert(range: DateRange): BudgetPeriod {
        val entity = BudgetPeriodEntity(startDate = range.start, endDate = range.end)
        periodDao.insert(entity)
        return entity.toDomain()
    }

    private companion object {
        // Safety limit (100 years of months) so a mistyped year can't loop forever.
        const val MAX_STEPS = 1200
    }
}

private fun BudgetPeriodEntity.toDomain() = BudgetPeriod(
    id = id,
    startDate = startDate,
    endDate = endDate
)
