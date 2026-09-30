package com.sosina.terefe.budgetingapp.data.repository

import com.sosina.terefe.budgetingapp.data.local.TransactionEntity
import com.sosina.terefe.budgetingapp.data.local.TransactionItemEntity
import com.sosina.terefe.budgetingapp.data.local.dao.TransactionDao
import com.sosina.terefe.budgetingapp.data.local.dao.TransactionWithItems
import com.sosina.terefe.budgetingapp.domain.model.DaySpending
import com.sosina.terefe.budgetingapp.domain.model.LabelSpending
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import com.sosina.terefe.budgetingapp.domain.model.TransactionItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the app can do with expenses.
 * Date ranges here use plain dates, both included (Sep 25 to Oct 24),
 * just like budget periods. The conversion to exact times happens inside.
 */
interface TransactionRepository {

    fun observeTransactions(start: LocalDate, end: LocalDate): Flow<List<Transaction>>

    fun observeRecent(limit: Int): Flow<List<Transaction>>

    fun observeTransaction(id: String): Flow<Transaction?>

    suspend fun getTransaction(id: String): Transaction?

    fun observeTotalSpent(start: LocalDate, end: LocalDate): Flow<Long>

    suspend fun getTotalSpent(start: LocalDate, end: LocalDate): Long

    fun observeSpendingByLabel(start: LocalDate, end: LocalDate): Flow<List<LabelSpending>>

    fun observeSpendingByDay(start: LocalDate, end: LocalDate): Flow<List<DaySpending>>

    /**
     * Adds a new expense or updates an existing one (matched by id), including its items.
     * Also makes sure the budget month for its date exists.
     */
    suspend fun saveTransaction(transaction: Transaction)

    /** Deletes an expense and its items. Save the same object again to undo. */
    suspend fun deleteTransaction(transaction: Transaction)
}

@Singleton
class RoomTransactionRepository @Inject constructor(
    private val transactionDao: TransactionDao,
    private val budgetPeriodRepository: RoomBudgetPeriodRepository
) : TransactionRepository {

    override fun observeTransactions(start: LocalDate, end: LocalDate): Flow<List<Transaction>> =
        transactionDao.observeWithItemsBetween(start.startOfDay(), end.endExclusive())
            .map { list -> list.map { it.toDomain() } }

    override fun observeRecent(limit: Int): Flow<List<Transaction>> =
        transactionDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    override fun observeTransaction(id: String): Flow<Transaction?> =
        transactionDao.observeWithItemsById(id).map { it?.toDomain() }

    override suspend fun getTransaction(id: String): Transaction? =
        transactionDao.getWithItemsById(id)?.toDomain()

    override fun observeTotalSpent(start: LocalDate, end: LocalDate): Flow<Long> =
        transactionDao.observeTotalBetween(start.startOfDay(), end.endExclusive())

    override suspend fun getTotalSpent(start: LocalDate, end: LocalDate): Long =
        transactionDao.getTotalBetween(start.startOfDay(), end.endExclusive())

    override fun observeSpendingByLabel(start: LocalDate, end: LocalDate): Flow<List<LabelSpending>> =
        transactionDao.observeTotalsByLabel(start.startOfDay(), end.endExclusive())
            .map { list -> list.map { LabelSpending(it.labelId, it.total) } }

    override fun observeSpendingByDay(start: LocalDate, end: LocalDate): Flow<List<DaySpending>> =
        transactionDao.observeTotalsByDay(start.startOfDay(), end.endExclusive())
            .map { list -> list.map { DaySpending(LocalDate.ofEpochDay(it.epochDay), it.total) } }

    override suspend fun saveTransaction(transaction: Transaction) {
        require(transaction.amount > 0) { "Expense amount must be more than zero." }

        // Make sure the budget month for this date exists.
        budgetPeriodRepository.getPeriodFor(transaction.dateTime.toLocalDate())

        val cleaned = transaction.copy(
            title = transaction.title?.trim()?.ifBlank { null },
            note = transaction.note?.trim()?.ifBlank { null },
            // Drop empty item rows the user left blank.
            items = transaction.items.filter { it.name.isNotBlank() || it.price > 0 },
            updatedAt = System.currentTimeMillis()
        )

        transactionDao.saveWithItems(
            transaction = cleaned.toEntity(),
            items = cleaned.items.mapIndexed { index, item -> item.toEntity(cleaned.id, index) }
        )
    }

    override suspend fun deleteTransaction(transaction: Transaction) {
        transactionDao.delete(transaction.toEntity())
    }
}

// ---------------- Date range helpers ----------------

/** Sep 25 -> Sep 25 at 00:00 (included). */
private fun LocalDate.startOfDay(): LocalDateTime = atStartOfDay()

/** Oct 24 -> Oct 25 at 00:00 (excluded), so the whole of Oct 24 is covered. */
private fun LocalDate.endExclusive(): LocalDateTime = plusDays(1).atStartOfDay()

// ---------------- Converting between database rows and screen models ----------------

private fun TransactionWithItems.toDomain() = Transaction(
    id = transaction.id,
    amount = transaction.amount,
    labelId = transaction.labelId,
    subLabelId = transaction.subLabelId,
    dateTime = transaction.dateTime,
    title = transaction.title,
    note = transaction.note,
    receiptImageUri = transaction.receiptImageUri,
    items = items
        .sortedBy { it.position }
        .map { TransactionItem(id = it.id, name = it.name, price = it.price, quantity = it.quantity) },
    createdAt = transaction.createdAt,
    updatedAt = transaction.updatedAt
)

private fun Transaction.toEntity() = TransactionEntity(
    id = id,
    amount = amount,
    labelId = labelId,
    subLabelId = subLabelId,
    dateTime = dateTime,
    title = title,
    note = note,
    receiptImageUri = receiptImageUri,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun TransactionItem.toEntity(transactionId: String, position: Int) = TransactionItemEntity(
    id = id,
    transactionId = transactionId,
    name = name.trim(),
    price = price,
    quantity = quantity,
    position = position
)
