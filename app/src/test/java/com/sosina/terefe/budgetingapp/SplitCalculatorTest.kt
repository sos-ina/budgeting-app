package com.sosina.terefe.budgetingapp

import com.sosina.terefe.budgetingapp.domain.split.ME
import com.sosina.terefe.budgetingapp.domain.split.SplitCalculator
import com.sosina.terefe.budgetingapp.domain.split.SplitItem
import com.sosina.terefe.budgetingapp.domain.split.SplitPerson
import org.junit.Assert.assertEquals
import org.junit.Test

class SplitCalculatorTest {

    private val me = SplitPerson(ME, "Me")
    private val ali = SplitPerson("ali", "Ali")
    private val sara = SplitPerson("sara", "Sara")
    private val three = listOf(me, ali, sara)

    private fun totals(result: com.sosina.terefe.budgetingapp.domain.split.SplitResult) =
        result.shares.map { it.total }

    @Test
    fun `shared item is split equally`() {
        val result = SplitCalculator.calculate(three, listOf(SplitItem("1", "Pizza", 3000)), extras = 0)
        assertEquals(listOf(1000L, 1000L, 1000L), totals(result))
    }

    @Test
    fun `item for one person goes only to them`() {
        val result = SplitCalculator.calculate(
            three,
            listOf(SplitItem("1", "Steak", 5000, sharedBy = setOf("ali"))),
            extras = 0
        )
        assertEquals(listOf(0L, 5000L, 0L), totals(result))
    }

    @Test
    fun `item for two people`() {
        val result = SplitCalculator.calculate(
            three,
            listOf(SplitItem("1", "Dessert", 1200, sharedBy = setOf(ME, "sara"))),
            extras = 0
        )
        assertEquals(listOf(600L, 0L, 600L), totals(result))
    }

    @Test
    fun `leftover cents are never lost`() {
        // 10.00 between 3 people can't split evenly: 3.34 + 3.33 + 3.33
        val result = SplitCalculator.calculate(three, listOf(SplitItem("1", "Fries", 1000)), extras = 0)
        assertEquals(listOf(334L, 333L, 333L), totals(result))
        assertEquals(1000L, totals(result).sum())
    }

    @Test
    fun `tip is split by what each person ordered`() {
        val result = SplitCalculator.calculate(
            listOf(me, ali),
            listOf(
                SplitItem("1", "Salad", 1000, sharedBy = setOf(ME)),
                SplitItem("2", "Steak", 3000, sharedBy = setOf("ali"))
            ),
            extras = 400 // tip
        )
        // Me ordered 1/4 of the food, so pays 1/4 of the tip.
        assertEquals(100L, result.shares[0].extras)
        assertEquals(300L, result.shares[1].extras)
    }

    @Test
    fun `everything always adds up to the exact bill`() {
        val result = SplitCalculator.calculate(
            three,
            listOf(
                SplitItem("1", "A", 1001),
                SplitItem("2", "B", 777, sharedBy = setOf(ME, "ali")),
                SplitItem("3", "C", 333, sharedBy = setOf("sara"))
            ),
            extras = 199
        )
        assertEquals(result.grandTotal, totals(result).sum())
        assertEquals(1001L + 777L + 333L + 199L, result.grandTotal)
    }

    @Test
    fun `extras with no items are split equally`() {
        val result = SplitCalculator.calculate(three, emptyList(), extras = 900)
        assertEquals(listOf(300L, 300L, 300L), totals(result))
    }

    @Test
    fun `sharer who was removed falls back to everyone`() {
        val result = SplitCalculator.calculate(
            listOf(me, ali),
            listOf(SplitItem("1", "Drink", 800, sharedBy = setOf("someone-deleted"))),
            extras = 0
        )
        assertEquals(listOf(400L, 400L), totals(result))
    }
}
