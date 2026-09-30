package com.sosina.terefe.budgetingapp.data.remote

import android.util.Log
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.sosina.terefe.budgetingapp.data.repository.BudgetPeriodRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.domain.model.DaySpending
import com.sosina.terefe.budgetingapp.domain.model.LabelSpending
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import com.sosina.terefe.budgetingapp.domain.model.TransactionItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Expenses for a signed-in user, stored in Firestore.
 *
 * Each expense is ONE document with its items inside as a list,
 * so an expense and its items are always saved and loaded together.
 * Times are stored as seconds, exactly like the Room version.
 */
class FirestoreTransactionRepository(
    private val paths: UserPaths,
    private val budgetPeriodRepository: BudgetPeriodRepository
) : TransactionRepository {

    /** Expenses from the start of [start] up to the end of [end]. */
    private fun queryBetween(start: LocalDate, end: LocalDate): Query =
        paths.transactions
            .whereGreaterThanOrEqualTo("dateTime", start.atStartOfDay().toSeconds())
            .whereLessThan("dateTime", end.plusDays(1).atStartOfDay().toSeconds())

    private fun observeBetween(start: LocalDate, end: LocalDate): Flow<List<Transaction>> =
        queryBetween(start, end).snapshotFlow().map { snapshot ->
            snapshot.documents.mapNotNull { it.toTransaction() }.newestFirst()
        }

    override fun observeTransactions(start: LocalDate, end: LocalDate): Flow<List<Transaction>> =
        observeBetween(start, end)

    override fun observeRecent(limit: Int): Flow<List<Transaction>> =
        paths.transactions
            .orderBy("dateTime", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .snapshotFlow()
            .map { snapshot -> snapshot.documents.mapNotNull { it.toTransaction() }.newestFirst() }

    override fun observeTransaction(id: String): Flow<Transaction?> =
        paths.transactions.document(id).documentFlow().map { it.toTransaction() }

    override suspend fun getTransaction(id: String): Transaction? =
        paths.transactions.document(id).get().await().toTransaction()

    override fun observeTotalSpent(start: LocalDate, end: LocalDate): Flow<Long> =
        observeBetween(start, end).map { list -> list.sumOf { it.amount } }

    override suspend fun getTotalSpent(start: LocalDate, end: LocalDate): Long =
        queryBetween(start, end).get().await().documents.sumOf { it.getLong("amount") ?: 0L }

    override fun observeSpendingByLabel(start: LocalDate, end: LocalDate): Flow<List<LabelSpending>> =
        observeBetween(start, end).map { list ->
            list.groupBy { it.labelId }
                .map { (labelId, items) -> LabelSpending(labelId, items.sumOf { it.amount }) }
                .sortedByDescending { it.total }
        }

    override fun observeSpendingByDay(start: LocalDate, end: LocalDate): Flow<List<DaySpending>> =
        observeBetween(start, end).map { list ->
            list.groupBy { it.dateTime.toLocalDate() }
                .map { (date, items) -> DaySpending(date, items.sumOf { it.amount }) }
                .sortedBy { it.date }
        }

    override suspend fun saveTransaction(transaction: Transaction) {
        require(transaction.amount > 0) { "Expense amount must be more than zero." }
        budgetPeriodRepository.getPeriodFor(transaction.dateTime.toLocalDate())

        val cleaned = transaction.copy(
            title = transaction.title?.trim()?.ifBlank { null },
            note = transaction.note?.trim()?.ifBlank { null },
            items = transaction.items
                .filter { it.name.isNotBlank() || it.price > 0 }
                .map { it.copy(name = it.name.trim()) },
            updatedAt = System.currentTimeMillis()
        )
        paths.transactions.document(cleaned.id).set(cleaned.toMap()).logFailures("Save expense")
    }

    override suspend fun deleteTransaction(transaction: Transaction) {
        paths.transactions.document(transaction.id).delete().logFailures("Delete expense")
    }
}

// ---------------- Helpers ----------------

private fun LocalDateTime.toSeconds(): Long = toEpochSecond(ZoneOffset.UTC)

private fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofEpochSecond(this, 0, ZoneOffset.UTC)

private fun List<Transaction>.newestFirst(): List<Transaction> =
    sortedWith(compareByDescending<Transaction> { it.dateTime }.thenByDescending { it.createdAt })

/** Watches a single document, the same way snapshotFlow watches a query. */
private fun DocumentReference.documentFlow(): Flow<DocumentSnapshot> = callbackFlow {
    val registration = addSnapshotListener { snapshot, error ->
        if (error != null) {
            Log.w("Firestore", "Document listener stopped", error)
            close()
            return@addSnapshotListener
        }
        if (snapshot != null) trySend(snapshot)
    }
    awaitClose { registration.remove() }
}

// ---------------- Converting ----------------

internal fun Transaction.toMap(): Map<String, Any?> = mapOf(
    "amount" to amount,
    "labelId" to labelId,
    "subLabelId" to subLabelId,
    "dateTime" to dateTime.toSeconds(),
    "title" to title,
    "note" to note,
    "receiptImageUri" to receiptImageUri,
    // Items are saved in order, inside the expense itself.
    "items" to items.map { item ->
        mapOf(
            "id" to item.id,
            "name" to item.name,
            "price" to item.price,
            "quantity" to item.quantity
        )
    },
    "createdAt" to createdAt,
    "updatedAt" to updatedAt
)

internal fun DocumentSnapshot.toTransaction(): Transaction? {
    if (!exists()) return null
    val amount = getLong("amount") ?: return null
    val dateTime = getLong("dateTime") ?: return null

    @Suppress("UNCHECKED_CAST")
    val rawItems = get("items") as? List<Map<String, Any?>> ?: emptyList()
    val items = rawItems.mapNotNull { raw ->
        TransactionItem(
            id = raw["id"] as? String ?: return@mapNotNull null,
            name = raw["name"] as? String ?: "",
            price = (raw["price"] as? Number)?.toLong() ?: 0L,
            quantity = (raw["quantity"] as? Number)?.toInt() ?: 1
        )
    }

    return Transaction(
        id = id,
        amount = amount,
        labelId = getString("labelId"),
        subLabelId = getString("subLabelId"),
        dateTime = dateTime.toLocalDateTime(),
        title = getString("title"),
        note = getString("note"),
        receiptImageUri = getString("receiptImageUri"),
        items = items,
        createdAt = getLong("createdAt") ?: 0L,
        updatedAt = getLong("updatedAt") ?: 0L
    )
}
