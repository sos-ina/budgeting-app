package com.sosina.terefe.budgetingapp.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sosina.terefe.budgetingapp.domain.model.LabelType
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/*
 * A note on IDs: every row uses a random text ID (UUID) instead of 1, 2, 3...
 * This matters later: when a guest's data is uploaded to their Google account,
 * or the same account is used on two phones, random IDs never clash.
 */

// ======================= Labels =======================

/**
 * A category like "🍔 Food & Dining". Sub-labels (like "Coffee")
 * are also labels, with parentId pointing to their parent label.
 */
@Entity(
    tableName = "labels",
    foreignKeys = [
        ForeignKey(
            entity = LabelEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE // deleting a label deletes its sub-labels
        )
    ],
    indices = [
        Index("parentId"),
        Index(value = ["systemKey"], unique = true)
    ]
)
data class LabelEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val emoji: String,
    val color: Long,                 // ARGB color, e.g. 0xFFE57373
    val type: LabelType,
    val parentId: String? = null,    // null = top-level label
    val systemKey: String? = null,   // only set for built-in labels, e.g. "expense_other"
    val isHidden: Boolean = false,
    val sortOrder: Int = 0
)

// ======================= Budget periods =======================

/**
 * One budget "month", e.g. Sep 25 to Oct 24.
 * Both dates are included in the period.
 *
 * Periods are saved as rows (instead of calculated on the fly) so that
 * changing the payday setting later doesn't rewrite past months.
 */
@Entity(
    tableName = "budget_periods",
    indices = [Index(value = ["startDate"], unique = true)]
)
data class BudgetPeriodEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val startDate: LocalDate,
    val endDate: LocalDate
)

// ======================= Income =======================

/**
 * Money coming in: salary, bonus, side income, etc.
 * It belongs to whichever period contains its date.
 */
@Entity(
    tableName = "incomes",
    foreignKeys = [
        ForeignKey(
            entity = LabelEntity::class,
            parentColumns = ["id"],
            childColumns = ["labelId"],
            onDelete = ForeignKey.SET_NULL // safety net: never lose income if a label is deleted
        )
    ],
    indices = [Index("labelId"), Index("date")]
)
data class IncomeEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val amount: Long,                // in cents
    val labelId: String?,
    val date: LocalDate,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ======================= Transactions (expenses) =======================

/**
 * One expense, like "Groceries – 250".
 * It may have zero items (just a total) or many items (a full receipt).
 */
@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = LabelEntity::class,
            parentColumns = ["id"],
            childColumns = ["labelId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = LabelEntity::class,
            parentColumns = ["id"],
            childColumns = ["subLabelId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("labelId"), Index("subLabelId"), Index("dateTime")]
)
data class TransactionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val amount: Long,                // total, in cents
    val labelId: String?,
    val subLabelId: String? = null,
    val dateTime: LocalDateTime,
    val title: String? = null,       // e.g. store name
    val note: String? = null,
    val receiptImageUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One line inside a transaction, like "Milk – 12".
 *
 * price is the total for this line (already includes quantity),
 * so "2 × Milk" costing 12 in total is stored as price = 1200, quantity = 2.
 */
@Entity(
    tableName = "transaction_items",
    foreignKeys = [
        ForeignKey(
            entity = TransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE // deleting a transaction deletes its items
        )
    ],
    indices = [Index("transactionId")]
)
data class TransactionItemEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val transactionId: String,
    val name: String,
    val price: Long,                 // line total, in cents
    val quantity: Int = 1,
    val position: Int = 0            // keeps items in the order they were added
)
