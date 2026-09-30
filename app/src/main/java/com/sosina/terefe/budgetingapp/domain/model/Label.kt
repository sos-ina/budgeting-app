package com.sosina.terefe.budgetingapp.domain.model

/**
 * A label as the screens see it.
 * Separate from LabelEntity so the UI doesn't depend on the database.
 */
data class Label(
    val id: String,
    val name: String,
    val emoji: String,
    val color: Long,              // ARGB, e.g. 0xFFE57373
    val type: LabelType,
    val parentId: String? = null, // set for sub-labels
    val systemKey: String? = null,// set for built-in labels
    val isHidden: Boolean = false,
    val sortOrder: Int = 0
) {
    val isSubLabel: Boolean get() = parentId != null

    /** Built-in label that came with the app (can still be renamed or hidden). */
    val isBuiltIn: Boolean get() = systemKey != null

    /** "Other" is the fallback for everything, so it can never be deleted. */
    val canDelete: Boolean get() = systemKey != SYSTEM_KEY_EXPENSE_OTHER

    companion object {
        const val SYSTEM_KEY_EXPENSE_OTHER = "expense_other"
        const val SYSTEM_KEY_INCOME_SALARY = "income_salary"
    }
}
