package com.sosina.terefe.budgetingapp.domain.split

/** Someone splitting the bill. The user is always included, with id [ME]. */
data class SplitPerson(val id: String, val name: String)

/**
 * One item on the bill.
 * [sharedBy] lists who shared it; an empty set means "everyone".
 */
data class SplitItem(
    val id: String,
    val name: String,
    val price: Long,               // in cents
    val sharedBy: Set<String> = emptySet()
)

/** One item's slice for one person, e.g. "Pizza (1/3) – 10.00". */
data class ShareLine(
    val itemName: String,
    val amount: Long,
    val sharedWith: Int            // how many people shared this item
)

/** What one person owes. */
data class PersonShare(
    val person: SplitPerson,
    val lines: List<ShareLine>,
    val itemsSubtotal: Long,
    val extras: Long               // their part of tax, tip, and service
) {
    val total: Long get() = itemsSubtotal + extras
}

data class SplitResult(
    val shares: List<PersonShare>,
    val itemsTotal: Long,
    val extrasTotal: Long
) {
    val grandTotal: Long get() = itemsTotal + extrasTotal
}

const val ME = "me"

/**
 * The rules:
 * 1. Each item is split equally among the people who shared it.
 * 2. Tax, tip, and service are split in proportion to what each person ordered,
 *    so someone who had a salad pays less tip than someone who had steak.
 * 3. Leftover cents from rounding are handed out so that everyone's totals
 *    always add up to the exact bill, never a cent more or less.
 */
object SplitCalculator {

    fun calculate(people: List<SplitPerson>, items: List<SplitItem>, extras: Long): SplitResult {
        if (people.isEmpty()) return SplitResult(emptyList(), 0, 0)

        val allIds = people.map { it.id }
        val lines = allIds.associateWith { mutableListOf<ShareLine>() }

        // ---------- 1. Split each item ----------
        items.filter { it.price > 0 }.forEach { item ->
            // Only people who are still in the list; nobody left means everyone.
            val sharers = allIds.filter { item.sharedBy.isEmpty() || it in item.sharedBy }
                .ifEmpty { allIds }

            val base = item.price / sharers.size
            val leftoverCents = (item.price % sharers.size).toInt()

            sharers.forEachIndexed { index, id ->
                // The first few people absorb the leftover cents, 1 each.
                val amount = base + if (index < leftoverCents) 1 else 0
                lines.getValue(id) += ShareLine(item.name, amount, sharers.size)
            }
        }

        val subtotals = allIds.associateWith { id -> lines.getValue(id).sumOf { it.amount } }
        val itemsTotal = subtotals.values.sum()

        // ---------- 2. Split the extras ----------
        val extrasShares = splitProportionally(
            total = extras.coerceAtLeast(0),
            weights = allIds.map { subtotals.getValue(it) }
        )

        val shares = people.mapIndexed { index, person ->
            PersonShare(
                person = person,
                lines = lines.getValue(person.id),
                itemsSubtotal = subtotals.getValue(person.id),
                extras = extrasShares[index]
            )
        }
        return SplitResult(shares, itemsTotal, extras.coerceAtLeast(0))
    }

    /**
     * Splits [total] cents by [weights] so the parts add up to exactly [total].
     * Everyone first gets the rounded-down amount; the leftover cents then go
     * to whoever was closest to rounding up ("largest remainder" method).
     * If all weights are zero, the split is equal.
     */
    internal fun splitProportionally(total: Long, weights: List<Long>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        val weightSum = weights.sum()
        val effective = if (weightSum > 0) weights else weights.map { 1L }
        val sum = effective.sum()

        val floors = effective.map { w -> total * w / sum }
        val remainders = effective.map { w -> total * w % sum }
        var leftover = total - floors.sum()

        val result = floors.toMutableList()
        remainders.withIndex()
            .sortedByDescending { it.value }
            .forEach { (index, _) ->
                if (leftover > 0) {
                    result[index] += 1
                    leftover--
                }
            }
        return result
    }
}
