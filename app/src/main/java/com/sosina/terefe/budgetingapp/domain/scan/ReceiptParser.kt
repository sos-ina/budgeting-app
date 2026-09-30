package com.sosina.terefe.budgetingapp.domain.scan

import java.time.DateTimeException
import java.time.LocalDate

/** One item found on a receipt. */
data class ParsedItem(
    val name: String,
    val price: Long   // in cents
)

/** Everything the parser could find. Any part may be missing. */
data class ParsedReceipt(
    val storeName: String?,
    val date: LocalDate?,
    val items: List<ParsedItem>,
    val total: Long?
) {
    val isEmpty: Boolean get() = storeName == null && date == null && items.isEmpty() && total == null
}

/**
 * Turns receipt text (one string per printed line) into a store, date, items, and total.
 *
 * This is a best guess: receipts vary a lot, and the user always
 * checks the result in the expense form before saving.
 */
object ReceiptParser {

    /**
     * A price at the END of a line, like "12.50", "1,234.56", "1.234,56" or "12,50".
     * Up to 3 trailing letters/symbols are allowed after it (e.g. "12.50 A" tax codes, "12.50 AED").
     * Group 1 = the text before the price, group 2 = the price.
     */
    private val PRICE_AT_END = Regex(
        """^(.*?)[\s:]*(-?\d{1,6}(?:[.,\s]\d{3})*[.,]\d{2})\s*[A-Za-z€$£¥₹]{0,3}\s*$"""
    )

    /** Lines containing these are never items. */
    private val NOT_ITEM_WORDS = listOf(
        "total", "subtotal", "sub total", "tax", "vat", "change", "cash", "card", "visa",
        "mastercard", "balance", "tip", "discount", "amount due", "paid", "tendered",
        "rounding", "service", "payment", "credit", "debit", "savings"
    )

    private val DATE_ISO = Regex("""\b(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\b""")
    private val DATE_DMY = Regex("""\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{2,4})\b""")

    fun parse(lines: List<String>, today: LocalDate = LocalDate.now()): ParsedReceipt {
        val cleaned = lines.map { it.trim().replace(Regex("""\s+"""), " ") }.filter { it.isNotEmpty() }

        val items = mutableListOf<ParsedItem>()
        val totalCandidates = mutableListOf<Long>()

        for (line in cleaned) {
            val match = PRICE_AT_END.find(line) ?: continue
            val label = match.groupValues[1].trim()
            val priceText = match.groupValues[2]
            if (priceText.startsWith("-")) continue // discounts and refunds are skipped

            val price = parsePrice(priceText) ?: continue
            val lower = label.lowercase()

            when {
                // "Total", "Grand total", "Total incl. VAT" (but not "Subtotal")
                "total" in lower && "sub" !in lower -> totalCandidates += price
                NOT_ITEM_WORDS.any { it in lower } -> Unit
                isItemName(label) -> items += ParsedItem(cleanName(label), price)
            }
        }

        return ParsedReceipt(
            storeName = findStoreName(cleaned),
            date = findDate(cleaned, today),
            items = items,
            // The grand total is usually the largest "total" line.
            total = totalCandidates.maxOrNull()
        )
    }

    /**
     * Every supported price format ends in exactly two decimals,
     * so the digits alone are the amount in cents: "1,234.56" -> 123456.
     */
    internal fun parsePrice(text: String): Long? {
        val digits = text.filter { it.isDigit() }
        if (digits.length < 3) return null
        return digits.toLongOrNull()?.takeIf { it > 0 }
    }

    /** An item name needs at least two letters (so "12.50" alone or "#4" isn't an item). */
    private fun isItemName(text: String): Boolean = text.count { it.isLetter() } >= 2

    /** Removes stray symbols and trailing currency codes like "AED" or "$". */
    private fun cleanName(text: String): String =
        text.replace(Regex("""\s+(?:[A-Z]{3}|[€$£¥₹])$"""), "")
            .trim(' ', '.', ':', '-', '*', '#')
            .take(60)

    /** The first meaningful line near the top, usually the shop's name. */
    private fun findStoreName(lines: List<String>): String? =
        lines.take(5).firstOrNull { line ->
            val letters = line.count { it.isLetter() }
            val lower = line.lowercase()
            letters >= 3 &&
                letters >= line.length / 2 &&
                NOT_ITEM_WORDS.none { it in lower } &&
                PRICE_AT_END.find(line) == null
        }?.take(60)

    /**
     * Looks for a date like 2026-09-28, 28/09/2026, or 28.09.26.
     * Day-first is assumed, unless the "month" is over 12 (then it's month-first).
     * Dates in the future or before 2000 are ignored as misreads.
     */
    private fun findDate(lines: List<String>, today: LocalDate): LocalDate? {
        for (line in lines) {
            DATE_ISO.find(line)?.let { m ->
                val (y, mo, d) = m.destructured
                safeDate(y.toInt(), mo.toInt(), d.toInt(), today)?.let { return it }
            }
            DATE_DMY.find(line)?.let { m ->
                val (a, b, yText) = m.destructured
                val year = yText.toInt().let { if (it < 100) 2000 + it else it }
                val first = a.toInt()
                val second = b.toInt()
                val date = if (second > 12) safeDate(year, first, second, today)
                else safeDate(year, second, first, today)
                date?.let { return it }
            }
        }
        return null
    }

    private fun safeDate(year: Int, month: Int, day: Int, today: LocalDate): LocalDate? =
        try {
            LocalDate.of(year, month, day).takeIf { it.year >= 2000 && !it.isAfter(today.plusDays(1)) }
        } catch (e: DateTimeException) {
            null
        }
}
