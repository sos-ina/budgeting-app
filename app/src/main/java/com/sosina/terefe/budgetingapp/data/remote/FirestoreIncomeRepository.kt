package com.sosina.terefe.budgetingapp.data.remote

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.IncomeRepository
import com.sosina.terefe.budgetingapp.domain.model.Income
import com.sosina.terefe.budgetingapp.domain.model.Label
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/**
 * Income for a signed-in user, stored in Firestore.
 * Dates are stored as day numbers, like in Room.
 */
class FirestoreIncomeRepository(
    private val paths: UserPaths,
    private val budgetPeriodRepository: BudgetPeriodRepository
) : IncomeRepository {

    /** All income between two day numbers (both included). */
    private fun queryBetween(start: LocalDate, end: LocalDate): Query =
        paths.incomes
            .whereGreaterThanOrEqualTo("date", start.toEpochDay())
            .whereLessThanOrEqualTo("date", end.toEpochDay())

    override fun observeIncomes(start: LocalDate, end: LocalDate): Flow<List<Income>> =
        queryBetween(start, end).snapshotFlow().map { snapshot ->
            snapshot.documents
                .mapNotNull { it.toIncome() }
                .sortedWith(compareByDescending<Income> { it.date }.thenByDescending { it.createdAt })
        }

    override fun observeTotal(start: LocalDate, end: LocalDate): Flow<Long> =
        queryBetween(start, end).snapshotFlow().map { snapshot ->
            snapshot.documents.sumOf { it.getLong("amount") ?: 0L }
        }

    override suspend fun getTotal(start: LocalDate, end: LocalDate): Long =
        queryBetween(start, end).get().await().documents.sumOf { it.getLong("amount") ?: 0L }

    override suspend fun getIncome(id: String): Income? =
        paths.incomes.document(id).get().await().toIncome()

    override suspend fun saveIncome(income: Income) {
        require(income.amount > 0) { "Income amount must be more than zero." }
        budgetPeriodRepository.getPeriodFor(income.date)

        val cleaned = income.copy(
            note = income.note?.trim()?.ifBlank { null },
            updatedAt = System.currentTimeMillis()
        )
        paths.incomes.document(cleaned.id).set(cleaned.toMap()).logFailures("Save income")
    }

    override suspend fun deleteIncome(income: Income) {
        paths.incomes.document(income.id).delete().logFailures("Delete income")
    }

    override suspend fun getLatestSalaryAmount(): Long? {
        val salaryLabelId = paths.labels
            .whereEqualTo("systemKey", Label.SYSTEM_KEY_INCOME_SALARY)
            .limit(1).get().await()
            .documents.firstOrNull()?.id
            ?: return null

        // Sorted here instead of in the query, so Firestore doesn't need an extra index.
        return paths.incomes
            .whereEqualTo("labelId", salaryLabelId)
            .get().await()
            .documents.mapNotNull { it.toIncome() }
            .maxByOrNull { it.date }
            ?.amount
    }
}

// ---------------- Converting ----------------

internal fun Income.toMap(): Map<String, Any?> = mapOf(
    "amount" to amount,
    "labelId" to labelId,
    "date" to date.toEpochDay(),
    "note" to note,
    "createdAt" to createdAt,
    "updatedAt" to updatedAt
)

internal fun DocumentSnapshot.toIncome(): Income? {
    if (!exists()) return null
    val amount = getLong("amount") ?: return null
    val date = getLong("date") ?: return null
    return Income(
        id = id,
        amount = amount,
        labelId = getString("labelId"),
        date = LocalDate.ofEpochDay(date),
        note = getString("note"),
        createdAt = getLong("createdAt") ?: 0L,
        updatedAt = getLong("updatedAt") ?: 0L
    )
}
