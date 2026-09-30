package com.sosina.terefe.budgetingapp.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sosina.terefe.budgetingapp.domain.model.AppSettings
import com.sosina.terefe.budgetingapp.domain.model.MascotType
import com.sosina.terefe.budgetingapp.domain.model.PeriodMode
import com.sosina.terefe.budgetingapp.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Creates one settings file on the phone, named "settings".
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Saves and loads user settings on the phone.
 * @Singleton means Hilt creates only one of these for the whole app.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private object Keys {
        val CURRENCY = stringPreferencesKey("currency_code")
        val THEME = stringPreferencesKey("theme_mode")
        val PERIOD_MODE = stringPreferencesKey("period_mode")
        val PAYDAY = intPreferencesKey("payday_day")
        val MASCOT = stringPreferencesKey("mascot")
        val ROAST = booleanPreferencesKey("roast_mode")
        val SOUND = booleanPreferencesKey("sound_effects")
        val SALARY_PROMPT_DISMISSED = stringPreferencesKey("salary_prompt_dismissed_period")
    }

    /**
     * The current settings. This is a Flow, meaning it automatically
     * sends a new value whenever any setting changes, so screens update instantly.
     */
    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { e ->
            // If the file can't be read, fall back to defaults instead of crashing.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            val defaults = AppSettings()
            AppSettings(
                currencyCode = prefs[Keys.CURRENCY] ?: defaults.currencyCode,
                themeMode = prefs[Keys.THEME].toEnum(defaults.themeMode),
                periodMode = prefs[Keys.PERIOD_MODE].toEnum(defaults.periodMode),
                paydayDay = prefs[Keys.PAYDAY] ?: defaults.paydayDay,
                mascot = prefs[Keys.MASCOT].toEnum(defaults.mascot),
                roastMode = prefs[Keys.ROAST] ?: defaults.roastMode,
                soundEffects = prefs[Keys.SOUND] ?: defaults.soundEffects
            )
        }

    /** The ID of the budget month where the salary prompt was dismissed, if any. */
    val salaryPromptDismissedFor: Flow<String?> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[Keys.SALARY_PROMPT_DISMISSED] }

    suspend fun setCurrency(code: String) = update { it[Keys.CURRENCY] = code }

    suspend fun setThemeMode(mode: ThemeMode) = update { it[Keys.THEME] = mode.name }

    suspend fun setPeriodMode(mode: PeriodMode) = update { it[Keys.PERIOD_MODE] = mode.name }

    suspend fun setPaydayDay(day: Int) = update { it[Keys.PAYDAY] = day.coerceIn(1, 31) }

    suspend fun setMascot(mascot: MascotType) = update { it[Keys.MASCOT] = mascot.name }

    suspend fun setRoastMode(enabled: Boolean) = update { it[Keys.ROAST] = enabled }

    suspend fun setSoundEffects(enabled: Boolean) = update { it[Keys.SOUND] = enabled }

    /** Replaces every setting at once (used when settings arrive from the cloud). */
    suspend fun setAll(settings: AppSettings) = update { prefs ->
        prefs[Keys.CURRENCY] = settings.currencyCode
        prefs[Keys.THEME] = settings.themeMode.name
        prefs[Keys.PERIOD_MODE] = settings.periodMode.name
        prefs[Keys.PAYDAY] = settings.paydayDay.coerceIn(1, 31)
        prefs[Keys.MASCOT] = settings.mascot.name
        prefs[Keys.ROAST] = settings.roastMode
        prefs[Keys.SOUND] = settings.soundEffects
    }

    suspend fun setSalaryPromptDismissedFor(periodId: String) =
        update { it[Keys.SALARY_PROMPT_DISMISSED] = periodId }

    private suspend fun update(block: (MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { prefs -> block(prefs) }
    }
}

/** Safely turns a saved name back into an enum, using the default if it's missing or unknown. */
private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
