package com.sosina.terefe.budgetingapp.data.funstate

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.funStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "fun_state")

/** Streak and achievement progress, kept on this phone. */
@Singleton
class FunStateRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val FIRST_USE_DAY = longPreferencesKey("first_use_day")
        val BEST_CELEBRATED_STREAK = intPreferencesKey("best_celebrated_streak")
        val UNLOCKED = stringSetPreferencesKey("unlocked")   // entries like "PAPARAZZI|20725"
    }

    private val data: Flow<Preferences> = context.funStateDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }

    /** The first day this app was used on this phone. Streaks only count from here. */
    val firstUseDate: Flow<LocalDate?> = data.map { prefs ->
        prefs[Keys.FIRST_USE_DAY]?.let(LocalDate::ofEpochDay)
    }

    /** Saves today as the first-use day, if none is saved yet. */
    suspend fun ensureFirstUseDate() {
        context.funStateDataStore.edit { prefs ->
            if (prefs[Keys.FIRST_USE_DAY] == null) {
                prefs[Keys.FIRST_USE_DAY] = LocalDate.now().toEpochDay()
            }
        }
    }

    /** The longest streak that has already had its confetti. */
    suspend fun bestCelebratedStreak(): Int = data.first()[Keys.BEST_CELEBRATED_STREAK] ?: 0

    suspend fun setBestCelebratedStreak(days: Int) {
        context.funStateDataStore.edit { it[Keys.BEST_CELEBRATED_STREAK] = days }
    }

    // ---------------- Counters ----------------

    /** Every counter by name, e.g. "SCANS" -> 12. */
    val counts: Flow<Map<String, Int>> = data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith(COUNT_PREFIX) && value is Int) key.name.removePrefix(COUNT_PREFIX) to value
            else null
        }.toMap()
    }

    /** Adds [by] to a counter and returns the new total. */
    suspend fun increment(name: String, by: Int = 1): Int {
        var newValue = 0
        context.funStateDataStore.edit { prefs ->
            val key = intPreferencesKey(COUNT_PREFIX + name)
            newValue = (prefs[key] ?: 0) + by
            prefs[key] = newValue
        }
        return newValue
    }

    // ---------------- Achievements ----------------

    /** Unlocked achievements, with the day each was earned. */
    val unlocked: Flow<Map<String, LocalDate>> = data.map { prefs ->
        prefs[Keys.UNLOCKED].orEmpty().mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size != 2) return@mapNotNull null
            val day = parts[1].toLongOrNull() ?: return@mapNotNull null
            parts[0] to LocalDate.ofEpochDay(day)
        }.toMap()
    }

    /** Marks an achievement as earned. Returns true only the first time. */
    suspend fun unlock(id: String): Boolean {
        var isNew = false
        context.funStateDataStore.edit { prefs ->
            val current = prefs[Keys.UNLOCKED].orEmpty()
            if (current.none { it.startsWith("$id|") }) {
                prefs[Keys.UNLOCKED] = current + "$id|${LocalDate.now().toEpochDay()}"
                isNew = true
            }
        }
        return isNew
    }

    private companion object {
        const val COUNT_PREFIX = "count_"
    }
}
