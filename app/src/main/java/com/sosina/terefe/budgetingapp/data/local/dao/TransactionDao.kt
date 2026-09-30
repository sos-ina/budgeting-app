package com.sosina.terefe.budgetingapp.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import com.sosina.terefe.budgetingapp.data.local.TransactionEntity
import com.sosina.terefe.budgetingapp.data.local.TransactionItemEntity
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * A transaction loaded together with all of its items.
 * Room fills in "items" automatically by matching transactionId.
 */
data class TransactionWithItems(
    @Embedded val transaction: TransactionEntity,
    @Relation(parentColumn = "id", entityColumn = "transactionId")
    val items: List<TransactionItemEntity>
)

/** Total spent per label, for the donut / bar chart. labelId is null for "Uncategorized". */
data class LabelTotal(
    val labelId: String?,
    val total: Long
)

/** Total spent per day, for the calendar heatmap. epochDay converts back with LocalDate.ofEpochDay(). */
data class DayTotal(
    val epochDay: Long,
    val total: Long
)

/*
 * Date ranges for transactions use "start included, end excluded":
 * to get all of Sep 25 to Oct 24, pass start = Sep 25 00:00 and end = Oct 25 00:00.
 * This way nothing at 23:59:59 gets missed.
 */

/**
 * This is an abstract class (not an interface) because saveWithItems
 * has real code inside it that runs several steps as one.
 */
@Dao
abstract class TransactionDao {

    // ---------------- Reading ----------------

    @Transaction
    @Query(
        "SELECT * FROM transactions WHERE dateTime >= :start AND dateTime < :end " +
            "ORDER BY dateTime DESC, createdAt DESC"
    )
    abstract fun observeWithItemsBetween(
        start: LocalDateTime,
        end: LocalDateTime
    ): Flow<List<TransactionWithItems>>

    /** The latest few transactions, for the dashboard. */
    @Transaction
    @Query("SELECT * FROM transactions ORDER BY dateTime DESC, createdAt DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<TransactionWithItems>>

    @Transaction
    @Query("SELECT * FROM transactions WHERE id = :id")
    abstract fun observeWithItemsById(id: String): Flow<TransactionWithItems?>

    @Transaction
    @Query("SELECT * FROM transactions WHERE id = :id")
    abstract suspend fun getWithItemsById(id: String): TransactionWithItems?

    @Transaction
    @Query("SELECT * FROM transactions")
    abstract suspend fun getAllWithItems(): List<TransactionWithItems>

    @Query("SELECT COUNT(*) FROM transactions")
    abstract suspend fun count(): Int

    // ---------------- Totals for dashboard & stats ----------------

    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM transactions " +
            "WHERE dateTime >= :start AND dateTime < :end"
    )
    abstract fun observeTotalBetween(start: LocalDateTime, end: LocalDateTime): Flow<Long>

    @Query(
        "SELECT COALESCE(SUM(amount), 0) FROM transactions " +
            "WHERE dateTime >= :start AND dateTime < :end"
    )
    abstract suspend fun getTotalBetween(start: LocalDateTime, end: LocalDateTime): Long

    @Query(
        "SELECT labelId, SUM(amount) AS total FROM transactions " +
            "WHERE dateTime >= :start AND dateTime < :end " +
            "GROUP BY labelId ORDER BY total DESC"
    )
    abstract fun observeTotalsByLabel(
        start: LocalDateTime,
        end: LocalDateTime
    ): Flow<List<LabelTotal>>

    // dateTime is stored as seconds, so dividing by 86400 (seconds in a day) gives the day.
    @Query(
        "SELECT dateTime / 86400 AS epochDay, SUM(amount) AS total FROM transactions " +
            "WHERE dateTime >= :start AND dateTime < :end " +
            "GROUP BY epochDay ORDER BY epochDay"
    )
    abstract fun observeTotalsByDay(
        start: LocalDateTime,
        end: LocalDateTime
    ): Flow<List<DayTotal>>

    // ---------------- Label changes ----------------

    /** Moves all transactions from one label to another (or to none) before deleting a label. */
    @Query("UPDATE transactions SET labelId = :toLabelId WHERE labelId = :fromLabelId")
    abstract suspend fun moveToLabel(fromLabelId: String, toLabelId: String?)

    @Query("UPDATE transactions SET subLabelId = :toLabelId WHERE subLabelId = :fromLabelId")
    abstract suspend fun moveToSubLabel(fromLabelId: String, toLabelId: String?)

    // ---------------- Writing ----------------

    @Upsert
    protected abstract suspend fun upsertTransaction(transaction: TransactionEntity)

    @Insert
    protected abstract suspend fun insertItems(items: List<TransactionItemEntity>)

    @Query("DELETE FROM transaction_items WHERE transactionId = :transactionId")
    protected abstract suspend fun deleteItemsFor(transactionId: String)

    /**
     * Saves a transaction and replaces its items, all as one step.
     * @Transaction means: if anything fails halfway, nothing is saved,
     * so you never end up with a transaction missing half its items.
     * Works for both adding a new transaction and editing an existing one.
     */
    @Transaction
    open suspend fun saveWithItems(
        transaction: TransactionEntity,
        items: List<TransactionItemEntity>
    ) {
        upsertTransaction(transaction)
        deleteItemsFor(transaction.id)
        if (items.isNotEmpty()) {
            insertItems(items.map { it.copy(transactionId = transaction.id) })
        }
    }

    /** Deletes a transaction. Its items are deleted automatically (CASCADE). */
    @Delete
    abstract suspend fun delete(transaction: TransactionEntity)
}
