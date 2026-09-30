package com.sosina.terefe.budgetingapp

import com.sosina.terefe.budgetingapp.domain.model.DateRange
import com.sosina.terefe.budgetingapp.domain.model.PeriodMode
import com.sosina.terefe.budgetingapp.domain.model.PeriodRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Automatic checks for the budget month rules.
 * Each test sets up a situation and checks the answer is what we expect.
 */
class PeriodRulesTest {

    private val calendar = PeriodRules(PeriodMode.CALENDAR, paydayDay = 1)
    private fun payday(day: Int) = PeriodRules(PeriodMode.PAYDAY, paydayDay = day)
    private fun date(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)

    // ---------- Calendar mode ----------

    @Test
    fun `calendar month contains the whole month`() {
        assertEquals(
            DateRange(date(2026, 9, 1), date(2026, 9, 30)),
            calendar.rangeContaining(date(2026, 9, 15))
        )
    }

    @Test
    fun `calendar month handles February in a leap year`() {
        assertEquals(
            DateRange(date(2028, 2, 1), date(2028, 2, 29)),
            calendar.rangeContaining(date(2028, 2, 10))
        )
    }

    // ---------- Payday mode ----------

    @Test
    fun `payday 25 after payday starts this month`() {
        assertEquals(
            DateRange(date(2026, 9, 25), date(2026, 10, 24)),
            payday(25).rangeContaining(date(2026, 9, 28))
        )
    }

    @Test
    fun `payday 25 before payday belongs to last month`() {
        assertEquals(
            DateRange(date(2026, 8, 25), date(2026, 9, 24)),
            payday(25).rangeContaining(date(2026, 9, 10))
        )
    }

    @Test
    fun `payday on the exact day starts a new period`() {
        assertEquals(
            date(2026, 9, 25),
            payday(25).rangeContaining(date(2026, 9, 25)).start
        )
    }

    @Test
    fun `payday crosses the new year`() {
        assertEquals(
            DateRange(date(2026, 12, 25), date(2027, 1, 24)),
            payday(25).rangeContaining(date(2027, 1, 5))
        )
    }

    @Test
    fun `payday 31 uses the last day of shorter months`() {
        // February 2026 has 28 days, so payday is Feb 28.
        assertEquals(
            DateRange(date(2026, 1, 31), date(2026, 2, 27)),
            payday(31).rangeContaining(date(2026, 2, 10))
        )
        assertEquals(
            DateRange(date(2026, 2, 28), date(2026, 3, 30)),
            payday(31).rangeContaining(date(2026, 2, 28))
        )
    }

    @Test
    fun `payday 1 behaves like calendar months`() {
        assertEquals(
            calendar.rangeContaining(date(2026, 9, 15)),
            payday(1).rangeContaining(date(2026, 9, 15))
        )
    }

    // ---------- Changing settings ----------

    @Test
    fun `switching from calendar to payday 25 creates a short transition period`() {
        // Last calendar period ended Sep 30, so the next one must start Oct 1
        // and runs until the day before the first payday.
        assertEquals(
            DateRange(date(2026, 10, 1), date(2026, 10, 24)),
            payday(25).rangeStartingOn(date(2026, 10, 1))
        )
    }

    @Test
    fun `going backwards ends exactly where requested`() {
        assertEquals(
            DateRange(date(2026, 8, 25), date(2026, 9, 24)),
            payday(25).rangeEndingOn(date(2026, 9, 24))
        )
    }

    // ---------- No gaps, ever ----------

    @Test
    fun `consecutive periods never leave gaps or overlap`() {
        for (day in listOf(1, 15, 25, 28, 29, 30, 31)) {
            val rules = payday(day)
            var period = rules.rangeContaining(date(2026, 1, 1))
            repeat(36) { // three years of months
                val next = rules.rangeStartingOn(period.end.plusDays(1))
                assertEquals(period.end.plusDays(1), next.start)
                assertTrue("Period must not be empty", !next.end.isBefore(next.start))
                period = next
            }
        }
    }
}
