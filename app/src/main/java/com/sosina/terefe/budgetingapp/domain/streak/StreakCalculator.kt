package com.sosina.terefe.budgetingapp.domain.streak

import java.time.LocalDate

/**
 * @param current days in a row with no spending, ending yesterday
 * @param longest the best run ever (finished days only)
 * @param todayIsClean true if nothing has been spent yet today
 */
data class StreakInfo(
    val current: Int,
    val longest: Int,
    val todayIsClean: Boolean
)

object StreakCalculator {

    /**
     * @param spendDays every day that has at least one expense
     * @param trackingStart the first day the user used the app (days before it never count)
     * @param today the current date
     */
    fun calculate(spendDays: Set<LocalDate>, trackingStart: LocalDate, today: LocalDate): StreakInfo {
        val todayIsClean = today !in spendDays
        if (!trackingStart.isBefore(today)) {
            // Started today: no finished days yet.
            return StreakInfo(current = 0, longest = 0, todayIsClean = todayIsClean)
        }

        // ---------- Current streak: walk back from yesterday ----------
        var current = 0
        var day = today.minusDays(1)
        while (!day.isBefore(trackingStart) && day !in spendDays) {
            current++
            day = day.minusDays(1)
        }

        // ---------- Longest streak: walk forward through every finished day ----------
        var longest = 0
        var run = 0
        day = trackingStart
        while (day.isBefore(today)) {
            if (day in spendDays) {
                run = 0
            } else {
                run++
                if (run > longest) longest = run
            }
            day = day.plusDays(1)
        }

        return StreakInfo(current = current, longest = longest, todayIsClean = todayIsClean)
    }
}
