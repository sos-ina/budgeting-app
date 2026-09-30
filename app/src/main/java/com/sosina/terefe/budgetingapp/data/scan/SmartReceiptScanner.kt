package com.sosina.terefe.budgetingapp.data.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
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
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.sosina.terefe.budgetingapp.domain.scan.ParsedItem
import com.sosina.terefe.budgetingapp.domain.scan.ParsedReceipt
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private val Context.smartScanDataStore: DataStore<Preferences> by preferencesDataStore(name = "smart_scan")

/** What happened when trying a smart scan. */
sealed interface SmartScanOutcome {
    data class Success(
        val receipt: ParsedReceipt,
        val suggestedLabel: String?,   // one of the label names we offered, or null
        val remainingToday: Int
    ) : SmartScanOutcome

    /** Smart scan couldn't be used; [reason] is shown to the user, and Quick scan takes over. */
    data class Unavailable(val reason: String) : SmartScanOutcome
}

/**
 * Smart scan: sends the receipt photo to Gemini (through Firebase AI Logic)
 * and gets back clean, structured receipt details.
 */
@Singleton
class SmartReceiptScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private object Keys {
        val DAY = longPreferencesKey("day")       // which day the counter is for
        val COUNT = intPreferencesKey("count")    // smart scans used that day
    }

    suspend fun remainingToday(): Int {
        val prefs = context.smartScanDataStore.data.first()
        val usedToday = if (prefs[Keys.DAY] == LocalDate.now().toEpochDay()) prefs[Keys.COUNT] ?: 0 else 0
        return (DAILY_LIMIT - usedToday).coerceAtLeast(0)
    }

    /**
     * @param labelNames the user's expense labels, so Gemini can suggest one.
     */
    suspend fun scan(imageUri: Uri, labelNames: List<String>): SmartScanOutcome {
        if (remainingToday() <= 0) {
            return SmartScanOutcome.Unavailable("daily smart scan limit reached")
        }

        return try {
            val bitmap = withContext(Dispatchers.IO) { loadScaledBitmap(imageUri) }

            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = MODEL_NAME,
                generationConfig = generationConfig {
                    // Ask for JSON in exactly this shape, so it can be read reliably.
                    responseMimeType = "application/json"
                    responseSchema = receiptSchema(labelNames)
                }
            )

            val response = withTimeout(TIMEOUT_MS) {
                model.generateContent(
                    content {
                        image(bitmap)
                        text(prompt())
                    }
                )
            }

            recordUse()
            val text = response.text ?: return SmartScanOutcome.Unavailable("no answer from smart scan")
            val result = json.decodeFromString<GeminiReceipt>(text)

            SmartScanOutcome.Success(
                receipt = result.toParsedReceipt(),
                suggestedLabel = result.category?.takeIf { it in labelNames },
                remainingToday = remainingToday()
            )
        } catch (e: CancellationException) {
            // A timeout is also a CancellationException; treat it as "unavailable", not a crash.
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                SmartScanOutcome.Unavailable("smart scan took too long")
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.w("SmartScan", "Smart scan failed", e)
            SmartScanOutcome.Unavailable("no internet or smart scan unavailable")
        }
    }

    // ---------------- The request ----------------

    private fun prompt() = """
        This is a photo of a shopping receipt or an order confirmation.
        Extract:
        - storeName: the shop or restaurant name.
        - date: the purchase date as YYYY-MM-DD, only if it is printed.
        - items: every purchased product, with a short readable name and its line total price.
          Do NOT include subtotal, total, tax, VAT, discounts, tips, payment method, cash, or change lines.
        - total: the final amount paid.
        - category: the best matching category from the allowed list.
        Use numbers exactly as printed, without currency symbols.
        If the image is not a receipt, return an empty items list.
    """.trimIndent()

    private fun receiptSchema(labelNames: List<String>) = Schema.obj(
        properties = buildMap {
            put("storeName", Schema.string())
            put("date", Schema.string())
            put(
                "items", Schema.array(
                    Schema.obj(
                        properties = mapOf(
                            "name" to Schema.string(),
                            "price" to Schema.double()
                        )
                    )
                )
            )
            put("total", Schema.double())
            if (labelNames.isNotEmpty()) put("category", Schema.enumeration(labelNames))
        },
        // Everything except the item list may be missing on some receipts.
        optionalProperties = listOf("storeName", "date", "total", "category")
    )

    // ---------------- Reading the answer ----------------

    @Serializable
    private data class GeminiReceipt(
        val storeName: String? = null,
        val date: String? = null,
        val items: List<GeminiItem> = emptyList(),
        val total: Double? = null,
        val category: String? = null
    )

    @Serializable
    private data class GeminiItem(
        val name: String = "",
        val price: Double = 0.0
    )

    private fun GeminiReceipt.toParsedReceipt() = ParsedReceipt(
        storeName = storeName?.trim()?.take(60)?.ifBlank { null },
        date = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { it.year >= 2000 && !it.isAfter(LocalDate.now().plusDays(1)) },
        items = items.mapNotNull { item ->
            val cents = item.price.toCents()
            if (item.name.isBlank() || cents <= 0) null
            else ParsedItem(item.name.trim().take(60), cents)
        },
        total = total?.toCents()?.takeIf { it > 0 }
    )

    /** 12.5 -> 1250, rounding safely (doubles can't store every decimal exactly). */
    private fun Double.toCents(): Long =
        BigDecimal.valueOf(this).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    // ---------------- Helpers ----------------

    private suspend fun recordUse() {
        val today = LocalDate.now().toEpochDay()
        context.smartScanDataStore.edit { prefs ->
            val count = if (prefs[Keys.DAY] == today) prefs[Keys.COUNT] ?: 0 else 0
            prefs[Keys.DAY] = today
            prefs[Keys.COUNT] = count + 1
        }
    }

    /**
     * Loads the photo at a size that keeps receipt text readable
     * but uploads quickly (full camera photos can be 12+ megapixels).
     */
    private fun loadScaledBitmap(uri: Uri, maxSide: Int = 2048): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            // ImageDecoder also turns sideways photos the right way up.
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }

        // Older phones (Android 8): measure first, then load a smaller copy.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Couldn't open the image")
    }

    private companion object {
        const val MODEL_NAME = "gemini-3.1-flash-lite"
        const val DAILY_LIMIT = 10
        const val TIMEOUT_MS = 30_000L
    }
}
