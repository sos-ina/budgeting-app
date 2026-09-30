package com.sosina.terefe.budgetingapp.domain.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** One budget "month", as the screens see it. Both dates are included. */
data class BudgetPeriod(
    val id: String,
    val startDate: LocalDate,
    val endDate: LocalDate
) {
    /** Lets you write: if (date in period) ... */
    operator fun contains(date: LocalDate): Boolean =
        !date.isBefore(startDate) && !date.isAfter(endDate)

    val lengthInDays: Long
        get() = ChronoUnit.DAYS.between(startDate, endDate) + 1

    /** True when the period is exactly the 1st to the last day of one month. */
    val isCalendarMonth: Boolean
        get() = startDate.dayOfMonth == 1 &&
            endDate == startDate.withDayOfMonth(startDate.lengthOfMonth())
}

/** A simple start-to-end date range (both included). */
data class DateRange(val start: LocalDate, val end: LocalDate)

/**
 * The rules for where budget months begin, based on the user's settings.
 *
 * Key idea: a "boundary" is the day a new budget month starts.
 * - Calendar mode: the 1st of every month.
 * - Payday mode:   the payday of every month (e.g. the 25th),
 *                  or the last day of the month if it's shorter (payday 31 -> Feb 28).
 *
 * A period always runs from one boundary up to the day before the next one.
 */
data class PeriodRules(
    val mode: PeriodMode,
    val paydayDay: Int
) {

    /** The day a budget month starts within a given calendar month. */
    fun boundaryIn(month: YearMonth): LocalDate = when (mode) {
        PeriodMode.CALENDAR -> month.atDay(1)
        PeriodMode.PAYDAY -> month.atDay(paydayDay.coerceIn(1, month.lengthOfMonth()))
    }

    /** The first boundary strictly after [date]. */
    fun nextBoundaryAfter(date: LocalDate): LocalDate {
        val month = YearMonth.from(date)
        val boundary = boundaryIn(month)
        return if (boundary.isAfter(date)) boundary else boundaryIn(month.plusMonths(1))
    }

    /** The latest boundary on or before [date]. */
    fun lastBoundaryOnOrBefore(date: LocalDate): LocalDate {
        val month = YearMonth.from(date)
        val boundary = boundaryIn(month)
        return if (!boundary.isAfter(date)) boundary else boundaryIn(month.minusMonths(1))
    }

    /** The full budget month that contains [date]. Used for the very first period. */
    fun rangeContaining(date: LocalDate): DateRange {
        val start = lastBoundaryOnOrBefore(date)
        return DateRange(start, nextBoundaryAfter(start).minusDays(1))
    }

    /**
     * A period that must start on a fixed day (the day after the previous period ended).
     * It ends the day before the next boundary.
     *
     * This is what makes setting changes apply "from the next month":
     * the old period keeps its dates, and the new one starts right after it.
     */
    fun rangeStartingOn(start: LocalDate): DateRange =
        DateRange(start, nextBoundaryAfter(start).minusDays(1))

    /**
     * A period that must end on a fixed day (the day before the earliest existing period).
     * Used when the user adds an expense dated before their first budget month.
     */
    fun rangeEndingOn(end: LocalDate): DateRange =
        DateRange(lastBoundaryOnOrBefore(end), end)
}
