package com.sosina.terefe.budgetingapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sosina.terefe.budgetingapp.data.local.BudgetPeriodEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface BudgetPeriodDao {

    /** The period that includes the given date, if it exists yet. */
    @Query("SELECT * FROM budget_periods WHERE :date BETWEEN startDate AND endDate LIMIT 1")
    suspend fun getContaining(date: LocalDate): BudgetPeriodEntity?

    /** Same as above, but keeps watching for changes. */
    @Query("SELECT * FROM budget_periods WHERE :date BETWEEN startDate AND endDate LIMIT 1")
    fun observeContaining(date: LocalDate): Flow<BudgetPeriodEntity?>

    @Query("SELECT * FROM budget_periods WHERE id = :id")
    suspend fun getById(id: String): BudgetPeriodEntity?

    /** The most recent period (the one with the latest start date). */
    @Query("SELECT * FROM budget_periods ORDER BY startDate DESC LIMIT 1")
    suspend fun getLatest(): BudgetPeriodEntity?

    /** The very first period, used for "All time" stats. */
    @Query("SELECT * FROM budget_periods ORDER BY startDate ASC LIMIT 1")
    suspend fun getEarliest(): BudgetPeriodEntity?

    /** Every period, newest first. */
    @Query("SELECT * FROM budget_periods ORDER BY startDate DESC")
    fun observeAll(): Flow<List<BudgetPeriodEntity>>

    /** Periods that have fully ended before the given date (for savings totals). */
    @Query("SELECT * FROM budget_periods WHERE endDate < :date ORDER BY startDate ASC")
    suspend fun getFinishedBefore(date: LocalDate): List<BudgetPeriodEntity>

    /**
     * IGNORE means: if a period with the same start date already exists,
     * quietly skip it instead of crashing.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(period: BudgetPeriodEntity)
}
