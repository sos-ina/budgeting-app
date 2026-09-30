package com.sosina.terefe.budgetingapp.domain.achievements

/** Things the app counts for achievements. */
enum class Counter { EXPENSES, SCANS, SPLITS, QUICK_ADDS }

/**
 * Every achievement.
 * - Ones with a [counter] unlock when that counter reaches [target] (and show progress).
 * - Ones without a counter are checked by special rules (streaks, months, time of day).
 */
enum class Achievement(
    val emoji: String,
    val title: String,
    val description: String,
    val counter: Counter? = null,
    val target: Int = 1
) {
    FIRST_BLOOD("🩸", "First Blood", "Log your first expense", Counter.EXPENSES, 1),
    GETTING_THE_HANG("📒", "Getting the Hang of It", "Log 50 expenses", Counter.EXPENSES, 50),
    CENTURION("💯", "Centurion", "Log 100 expenses", Counter.EXPENSES, 100),

    PAPARAZZI("📸", "Paparazzi", "Scan your first receipt", Counter.SCANS, 1),
    RECEIPT_HOARDER("🧾", "Receipt Hoarder", "Scan 50 receipts", Counter.SCANS, 50),

    FAIR_SHARE("🤝", "Fair Share", "Split your first bill", Counter.SPLITS, 1),
    SPLIT_PERSONALITY("🍕", "Split Personality", "Split 10 bills", Counter.SPLITS, 10),

    SPEED_DEMON("⚡", "Speed Demon", "Use Quick add 10 times", Counter.QUICK_ADDS, 10),

    TIGHT_WALLET("🔒", "Tight Wallet", "Reach a 3-day no-spend streak", target = 3),
    MONK_MODE("🧘", "Monk Mode", "Reach a 7-day no-spend streak", target = 7),

    SURVIVED_THE_MONTH("🏆", "Survived the Month", "Finish a budget month without overspending"),
    SUPER_SAVER("🐷", "Super Saver", "Save 20% of a month's income"),

    NIGHT_OWL("🦉", "Night Owl", "Log an expense between midnight and 4 a.m.");

    /** Streak achievements, unlocked by reaching [target] days in a row. */
    val isStreak: Boolean get() = this == TIGHT_WALLET || this == MONK_MODE
}
