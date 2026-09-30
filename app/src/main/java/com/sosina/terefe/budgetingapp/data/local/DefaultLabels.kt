package com.sosina.terefe.budgetingapp.data.local

import com.sosina.terefe.budgetingapp.domain.model.Label
import com.sosina.terefe.budgetingapp.domain.model.LabelType

/**
 * The labels every new user starts with.
 * Users can rename, recolor, hide, or delete them (except "Other").
 */
object DefaultLabels {

    /** Helper to describe a default label and its optional sub-labels. */
    private data class Def(
        val key: String,
        val name: String,
        val emoji: String,
        val color: Long,
        val subLabels: List<Def> = emptyList()
    )

    private val expenseLabels = listOf(
        Def(
            "expense_food", "Food & Dining", "🍔", 0xFFFF8A65,
            subLabels = listOf(
                Def("expense_food_coffee", "Coffee", "☕", 0xFFFF8A65),
                Def("expense_food_restaurants", "Restaurants", "🍽️", 0xFFFF8A65),
                Def("expense_food_delivery", "Delivery", "🛵", 0xFFFF8A65)
            )
        ),
        Def("expense_groceries", "Groceries", "🛒", 0xFF81C784),
        Def(
            "expense_transport", "Transport", "🚗", 0xFF64B5F6,
            subLabels = listOf(
                Def("expense_transport_fuel", "Fuel", "⛽", 0xFF64B5F6),
                Def("expense_transport_taxi", "Taxi", "🚕", 0xFF64B5F6),
                Def("expense_transport_public", "Public transport", "🚌", 0xFF64B5F6)
            )
        ),
        Def("expense_housing", "Rent & Housing", "🏠", 0xFFA1887F),
        Def("expense_bills", "Bills & Utilities", "💡", 0xFFFFD54F),
        Def("expense_shopping", "Shopping", "🛍️", 0xFFF06292),
        Def("expense_health", "Health", "💊", 0xFF4DB6AC),
        Def("expense_entertainment", "Entertainment", "🎬", 0xFFBA68C8),
        Def("expense_subscriptions", "Subscriptions", "📺", 0xFF7986CB),
        Def("expense_education", "Education", "📚", 0xFF4FC3F7),
        Def("expense_gifts", "Gifts", "🎁", 0xFFE57373),
        Def("expense_travel", "Travel", "✈️", 0xFF4DD0E1),
        Def(Label.SYSTEM_KEY_EXPENSE_OTHER, "Other", "📦", 0xFF90A4AE)
    )

    private val incomeLabels = listOf(
        Def(Label.SYSTEM_KEY_INCOME_SALARY, "Salary", "💼", 0xFF66BB6A),
        Def("income_bonus", "Bonus", "🎉", 0xFFFFCA28),
        Def("income_side", "Side income", "🤝", 0xFF26A69A),
        Def("income_gift", "Gift", "🎁", 0xFFEC407A),
        Def("income_refund", "Refund", "↩️", 0xFF78909C)
    )

    /** Builds every default label (and sub-label) as rows ready to insert. */
    fun createAll(): List<LabelEntity> =
        toEntities(expenseLabels, LabelType.EXPENSE) + toEntities(incomeLabels, LabelType.INCOME)

    /** Just the "Other" label, used if it's ever missing. */
    fun createOtherExpense(sortOrder: Int): LabelEntity {
        val other = expenseLabels.first { it.key == Label.SYSTEM_KEY_EXPENSE_OTHER }
        return LabelEntity(
            name = other.name,
            emoji = other.emoji,
            color = other.color,
            type = LabelType.EXPENSE,
            systemKey = other.key,
            sortOrder = sortOrder
        )
    }

    private fun toEntities(defs: List<Def>, type: LabelType): List<LabelEntity> {
        val result = mutableListOf<LabelEntity>()
        defs.forEachIndexed { index, def ->
            val parent = LabelEntity(
                name = def.name,
                emoji = def.emoji,
                color = def.color,
                type = type,
                systemKey = def.key,
                sortOrder = index
            )
            result += parent
            def.subLabels.forEachIndexed { subIndex, sub ->
                result += LabelEntity(
                    name = sub.name,
                    emoji = sub.emoji,
                    color = sub.color,
                    type = type,
                    parentId = parent.id, // links the sub-label to its parent
                    systemKey = sub.key,
                    sortOrder = subIndex
                )
            }
        }
        return result
    }
}
