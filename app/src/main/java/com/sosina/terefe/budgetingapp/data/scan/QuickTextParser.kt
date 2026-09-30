package com.sosina.terefe.budgetingapp.data.scan

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import com.sosina.terefe.budgetingapp.domain.model.Money
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private val Context.quickTextDataStore: DataStore<Preferences> by preferencesDataStore(name = "quick_text")

/** One expense understood from the user's text. */
data class QuickEntry(
    val title: String,
    val amount: Long,          // in cents
    val labelName: String?,    // one of the offered label names, or null
    val date: LocalDate?       // null = today
)

/** The result, and whether the AI or the simple on-phone parser produced it. */
sealed interface QuickTextOutcome {
    val entries: List<QuickEntry>

    data class Smart(override val entries: List<QuickEntry>) : QuickTextOutcome
    data class Local(override val entries: List<QuickEntry>, val reason: String) : QuickTextOutcome
}

@Singleton
class QuickTextParser @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private object Keys {
        val DAY = longPreferencesKey("day")
        val COUNT = intPreferencesKey("count")
    }

    suspend fun parse(
        text: String,
        labelNames: List<String>,
        today: LocalDate = LocalDate.now()
    ): QuickTextOutcome {
        val reason = if (remainingToday() <= 0) {
            "daily limit reached"
        } else {
            try {
                return QuickTextOutcome.Smart(parseWithAi(text, labelNames, today))
            } catch (e: TimeoutCancellationException) {
                "took too long"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("QuickText", "AI parsing failed", e)
                "no internet or AI unavailable"
            }
        }
        return QuickTextOutcome.Local(LocalQuickTextParser.parse(text, today), reason)
    }

    // ---------------- With Gemini ----------------

    private suspend fun parseWithAi(text: String, labelNames: List<String>, today: LocalDate): List<QuickEntry> {
        val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema(labelNames)
            }
        )

        val weekday = today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val prompt = """
            Today is $weekday, $today.
            The user is describing money they spent. Split it into separate expenses.
            For each expense give:
            - title: a short name, capitalized (e.g. "Coffee", "Lunch with Sara").
            - amount: the number spent, without currency symbols.
            - date: YYYY-MM-DD, only if a day is mentioned (like "yesterday" or "on Monday").
            - category: the best match from the allowed list.
            Ignore anything that is not an expense.

            User text: "$text"
        """.trimIndent()

        val response = withTimeout(TIMEOUT_MS) { model.generateContent(prompt) }
        recordUse()

        val result = json.decodeFromString<GeminiEntries>(response.text ?: return emptyList())
        return result.expenses.mapNotNull { e ->
            val cents = BigDecimal.valueOf(e.amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
            if (cents <= 0 || e.title.isBlank()) return@mapNotNull null
            QuickEntry(
                title = e.title.trim().take(60),
                amount = cents,
                labelName = e.category?.takeIf { it in labelNames },
                date = e.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?.takeIf { !it.isAfter(today) && it.year >= 2000 }
            )
        }
    }

    private fun schema(labelNames: List<String>) = Schema.obj(
        properties = mapOf(
            "expenses" to Schema.array(
                Schema.obj(
                    properties = buildMap {
                        put("title", Schema.string())
                        put("amount", Schema.double())
                        put("date", Schema.string())
                        if (labelNames.isNotEmpty()) put("category", Schema.enumeration(labelNames))
                    },
                    optionalProperties = listOf("date", "category")
                )
            )
        )
    )

    @Serializable
    private data class GeminiEntries(val expenses: List<GeminiEntry> = emptyList())

    @Serializable
    private data class GeminiEntry(
        val title: String = "",
        val amount: Double = 0.0,
        val date: String? = null,
        val category: String? = null
    )

    // ---------------- Daily limit ----------------

    private suspend fun remainingToday(): Int {
        val prefs = context.quickTextDataStore.data.first()
        val used = if (prefs[Keys.DAY] == LocalDate.now().toEpochDay()) prefs[Keys.COUNT] ?: 0 else 0
        return (DAILY_LIMIT - used).coerceAtLeast(0)
    }

    private suspend fun recordUse() {
        val today = LocalDate.now().toEpochDay()
        context.quickTextDataStore.edit { prefs ->
            val count = if (prefs[Keys.DAY] == today) prefs[Keys.COUNT] ?: 0 else 0
            prefs[Keys.DAY] = today
            prefs[Keys.COUNT] = count + 1
        }
    }

    private companion object {
        // Keep this in step with SmartReceiptScanner's model name.
        const val MODEL_NAME = "gemini-3.1-flash-lite"
        const val DAILY_LIMIT = 30   // text requests are much cheaper than images
        const val TIMEOUT_MS = 20_000L
    }
}

/**
 * The offline fallback: splits on commas, "and", "&", "+", or new lines,
 * and takes the number in each part as the amount.
 * "coffee 15, taxi 22 yesterday" -> Coffee 15 (today), Taxi 22 (yesterday)
 */
internal object LocalQuickTextParser {

    private val SPLIT = Regex("""\s*(?:,|;|\n|&|\+|\band\b)\s*""", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("""\d+(?:[.,]\d{1,2})?""")
    private val FILLER = Regex(
        """\b(?:for|on|at|spent|paid|bought|got|i|a|an|the|yesterday|today)\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(text: String, today: LocalDate): List<QuickEntry> =
        text.split(SPLIT).mapNotNull { part ->
            val number = NUMBER.find(part) ?: return@mapNotNull null
            val amount = Money.parse(number.value)?.takeIf { it > 0 } ?: return@mapNotNull null

            val date = if (part.contains("yesterday", ignoreCase = true)) today.minusDays(1) else null
            val title = part.removeRange(number.range)
                .replace(FILLER, " ")
                .replace(Regex("""[^\p{L}\p{N}\s'-]"""), " ")   // drop stray symbols like $ or :
                .replace(Regex("""\s+"""), " ")
                .trim()
                .replaceFirstChar { it.uppercase() }
                .ifBlank { "Expense" }

            QuickEntry(title = title.take(60), amount = amount, labelName = null, date = date)
        }
}
