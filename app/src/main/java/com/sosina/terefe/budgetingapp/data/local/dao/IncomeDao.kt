package com.sosina.terefe.budgetingapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.sosina.terefe.budgetingapp.data.local.IncomeEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface IncomeDao {

    /** All income between two dates (both included), newest first. */
    @Query(
        "SELECT * FROM incomes WHERE date BETWEEN :start AND :end " +
            "ORDER BY date DESC, createdAt DESC"
    )
    fun observeBetween(start: LocalDate, end: LocalDate): Flow<List<IncomeEntity>>

    /** Total income between two dates. Returns 0 if there's none. */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM incomes WHERE date BETWEEN :start AND :end")
    fun observeTotalBetween(start: LocalDate, end: LocalDate): Flow<Long>

    /** Same total, as a one-time result instead of a live stream. */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM incomes WHERE date BETWEEN :start AND :end")
    suspend fun getTotalBetween(start: LocalDate, end: LocalDate): Long

    @Query("SELECT * FROM incomes WHERE id = :id")
    suspend fun getById(id: String): IncomeEntity?

    @Query("SELECT * FROM incomes")
    suspend fun getAll(): List<IncomeEntity>

    @Query("SELECT COUNT(*) FROM incomes")
    suspend fun count(): Int

    /**
     * The most recent income with a given label.
     * Used to pre-fill "Add this month's salary?" with last month's amount.
     */
    @Query("SELECT * FROM incomes WHERE labelId = :labelId ORDER BY date DESC LIMIT 1")
    suspend fun getLatestWithLabel(labelId: String): IncomeEntity?

    /** Moves all income from one label to another (or to none) before a label is deleted. */
    @Query("UPDATE incomes SET labelId = :toLabelId WHERE labelId = :fromLabelId")
    suspend fun moveToLabel(fromLabelId: String, toLabelId: String?)

    /**
     * Upsert = insert if it's new, update if it already exists.
     * One function covers both "add" and "edit".
     */
    @Upsert
    suspend fun upsert(income: IncomeEntity)

    @Delete
    suspend fun delete(income: IncomeEntity)
}
