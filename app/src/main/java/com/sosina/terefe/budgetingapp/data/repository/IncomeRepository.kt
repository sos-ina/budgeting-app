package com.sosina.terefe.budgetingapp.data.repository

import com.sosina.terefe.budgetingapp.data.local.IncomeEntity
import com.sosina.terefe.budgetingapp.data.local.dao.IncomeDao
import com.sosina.terefe.budgetingapp.data.local.dao.LabelDao
import com.sosina.terefe.budgetingapp.domain.model.Income
import com.sosina.terefe.budgetingapp.domain.model.Label
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the app can do with income. */
interface IncomeRepository {

    /** All income between two dates (both included), newest first, live-updating. */
    fun observeIncomes(start: LocalDate, end: LocalDate): Flow<List<Income>>

    /** Total income between two dates, live-updating. */
    fun observeTotal(start: LocalDate, end: LocalDate): Flow<Long>

    /** Total income between two dates, once. */
    suspend fun getTotal(start: LocalDate, end: LocalDate): Long

    suspend fun getIncome(id: String): Income?

    /**
     * Adds a new income or updates an existing one (matched by id).
     * Also makes sure the budget month for its date exists.
     */
    suspend fun saveIncome(income: Income)

    suspend fun deleteIncome(income: Income)

    /** The most recent salary amount, used to pre-fill "Add this month's salary?". */
    suspend fun getLatestSalaryAmount(): Long?
}

@Singleton
class RoomIncomeRepository @Inject constructor(
    private val incomeDao: IncomeDao,
    private val labelDao: LabelDao,
    private val budgetPeriodRepository: RoomBudgetPeriodRepository
) : IncomeRepository {

    override fun observeIncomes(start: LocalDate, end: LocalDate): Flow<List<Income>> =
        incomeDao.observeBetween(start, end).map { list -> list.map { it.toDomain() } }

    override fun observeTotal(start: LocalDate, end: LocalDate): Flow<Long> =
        incomeDao.observeTotalBetween(start, end)

    override suspend fun getTotal(start: LocalDate, end: LocalDate): Long =
        incomeDao.getTotalBetween(start, end)

    override suspend fun getIncome(id: String): Income? =
        incomeDao.getById(id)?.toDomain()

    override suspend fun saveIncome(income: Income) {
        require(income.amount > 0) { "Income amount must be more than zero." }

        // If the date is in a month that doesn't exist yet (e.g. next month's salary
        // entered early), create that month so the income shows up in the right place.
        budgetPeriodRepository.getPeriodFor(income.date)

        incomeDao.upsert(
            income.copy(
                note = income.note?.trim()?.ifBlank { null },
                updatedAt = System.currentTimeMillis()
            ).toEntity()
        )
    }

    override suspend fun deleteIncome(income: Income) {
        incomeDao.delete(income.toEntity())
    }

    override suspend fun getLatestSalaryAmount(): Long? {
        val salaryLabel = labelDao.getBySystemKey(Label.SYSTEM_KEY_INCOME_SALARY) ?: return null
        return incomeDao.getLatestWithLabel(salaryLabel.id)?.amount
    }
}

// ---------------- Converting between database rows and screen models ----------------

private fun IncomeEntity.toDomain() = Income(
    id = id,
    amount = amount,
    labelId = labelId,
    date = date,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun Income.toEntity() = IncomeEntity(
    id = id,
    amount = amount,
    labelId = labelId,
    date = date,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt
)
