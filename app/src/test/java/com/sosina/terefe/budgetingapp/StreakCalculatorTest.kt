package com.sosina.terefe.budgetingapp

import com.sosina.terefe.budgetingapp.domain.streak.StreakCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StreakCalculatorTest {

    private val today = LocalDate.of(2026, 9, 29)
    private fun daysAgo(n: Long) = today.minusDays(n)

    @Test
    fun `no spending since starting counts every finished day`() {
        val info = StreakCalculator.calculate(emptySet(), trackingStart = daysAgo(5), today = today)
        assertEquals(5, info.current)
        assertEquals(5, info.longest)
        assertTrue(info.todayIsClean)
    }

    @Test
    fun `spending yesterday resets the current streak`() {
        val info = StreakCalculator.calculate(setOf(daysAgo(1)), trackingStart = daysAgo(10), today = today)
        assertEquals(0, info.current)
    }

    @Test
    fun `spending today does not break the streak yet, but today is not clean`() {
        val info = StreakCalculator.calculate(setOf(today), trackingStart = daysAgo(3), today = today)
        assertEquals(3, info.current)
        assertFalse(info.todayIsClean)
    }

    @Test
    fun `longest streak remembers the best run`() {
        // Spent 8 and 3 days ago: runs are 7-4 days ago (4 days) and 2-1 days ago (2 days).
        val info = StreakCalculator.calculate(
            spendDays = setOf(daysAgo(8), daysAgo(3)),
            trackingStart = daysAgo(8),
            today = today
        )
        assertEquals(2, info.current)
        assertEquals(4, info.longest)
    }

    @Test
    fun `started today means no streak yet`() {
        val info = StreakCalculator.calculate(emptySet(), trackingStart = today, today = today)
        assertEquals(0, info.current)
        assertEquals(0, info.longest)
    }

    @Test
    fun `days before starting never count`() {
        val info = StreakCalculator.calculate(emptySet(), trackingStart = daysAgo(2), today = today)
        assertEquals(2, info.current)
    }
}
