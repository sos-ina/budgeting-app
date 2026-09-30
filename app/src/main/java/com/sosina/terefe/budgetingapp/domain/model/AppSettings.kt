package com.sosina.terefe.budgetingapp.domain.model

/** Light, dark, or follow the phone's setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How budget months are counted. */
enum class PeriodMode {
    CALENDAR, // 1st to end of month
    PAYDAY    // starts on a chosen day each month
}

/** The mascot the user picked, or none. */
enum class MascotType { NONE, PIGEON, HORSE, DONKEY, CAT, DOG }

/**
 * Every user setting in one place.
 * The values here are the defaults for a brand-new user.
 */
data class AppSettings(
    val currencyCode: String = Money.deviceCurrencyCode(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val periodMode: PeriodMode = PeriodMode.CALENDAR,
    val paydayDay: Int = 1,           // 1 to 31, only used in PAYDAY mode
    val mascot: MascotType = MascotType.NONE,
    val roastMode: Boolean = false,
    val soundEffects: Boolean = true
)
