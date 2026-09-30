package com.sosina.terefe.budgetingapp.domain.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * One line on a receipt, like "Milk – 12".
 * price is the total for this line (already includes quantity).
 */
data class TransactionItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val price: Long,       // line total, in cents
    val quantity: Int = 1
)

/**
 * One expense. It may have no items ("Groceries – 250")
 * or many items (a full receipt).
 */
data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val amount: Long,                   // total, in cents
    val labelId: String?,
    val subLabelId: String? = null,
    val dateTime: LocalDateTime,
    val title: String? = null,          // e.g. store name
    val note: String? = null,
    val receiptImageUri: String? = null,
    val items: List<TransactionItem> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val hasItems: Boolean get() = items.isNotEmpty()

    /** What the listed items add up to. */
    val itemsTotal: Long get() = items.sumOf { it.price }

    /**
     * The part of the total not covered by items, shown as "Other / unlisted".
     * - 0 when there are no items, or they match the total exactly
     * - positive when items add up to less than the total
     * - negative when items add up to MORE than the total (the screen warns about this)
     */
    val unlistedAmount: Long get() = if (items.isEmpty()) 0 else amount - itemsTotal
}

/** How much was spent with one label, for charts. labelId null = Uncategorized. */
data class LabelSpending(
    val labelId: String?,
    val total: Long
)

/** How much was spent on one day, for the calendar heatmap. */
data class DaySpending(
    val date: LocalDate,
    val total: Long
)
