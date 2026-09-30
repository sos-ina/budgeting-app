package com.sosina.terefe.budgetingapp.domain.model

import java.time.LocalDate
import java.util.UUID

/**
 * Money coming in, as the screens see it.
 * A new Income gets a fresh random ID automatically.
 */
data class Income(
    val id: String = UUID.randomUUID().toString(),
    val amount: Long,            // in cents
    val labelId: String?,        // null = Uncategorized
    val date: LocalDate,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
