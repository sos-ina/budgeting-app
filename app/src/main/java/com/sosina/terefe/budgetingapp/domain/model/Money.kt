package com.sosina.terefe.budgetingapp.domain.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * All money in the app is stored as a Long in hundredths ("cents").
 * Example: 12.50 is stored as 1250.
 *
 * Using whole numbers avoids floating-point errors like 0.1 + 0.2 = 0.30000000000000004.
 */
object Money {

    private const val SCALE = 2 // two decimal places
    private const val FALLBACK_CURRENCY = "USD"

    /**
     * Turns what the user typed ("12.5", "12,50", "1,250.75") into cents.
     * Returns null if the text isn't a valid positive amount.
     */
    fun parse(input: String): Long? {
        var text = input.trim().replace(" ", "")
        if (text.isEmpty()) return null

        // If there's a dot, commas are thousands separators ("1,250.75").
        // If there's no dot, a comma is the decimal separator ("12,50").
        text = if (text.contains('.')) text.replace(",", "") else text.replace(',', '.')

        return try {
            val value = BigDecimal(text)
            if (value.signum() < 0) return null
            value.movePointRight(SCALE)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        } catch (e: NumberFormatException) {
            null
        } catch (e: ArithmeticException) {
            null
        }
    }

    /**
     * Formats cents for display with the currency symbol, e.g. 1250 -> "$12.50".
     * Works with negative amounts too (for "overspent").
     */
    fun format(amountInCents: Long, currencyCode: String): String {
        val currency = currencyOrFallback(currencyCode)

        // Most currencies use 2 decimals, some use 0 (like JPY).
        val digits = currency.defaultFractionDigits.let { if (it in 0..SCALE) it else SCALE }

        val formatter = NumberFormat.getCurrencyInstance(Locale.getDefault()).apply {
            this.currency = currency
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return formatter.format(BigDecimal.valueOf(amountInCents, SCALE))
    }

    /**
     * Turns cents back into plain text for editing, e.g. 1250 -> "12.5".
     */
    fun toInputText(amountInCents: Long): String =
        BigDecimal.valueOf(amountInCents, SCALE).stripTrailingZeros().toPlainString()

    /** The currency of the phone's region, used as the default setting. */
    fun deviceCurrencyCode(): String =
        runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }
            .getOrDefault(FALLBACK_CURRENCY)

    private fun currencyOrFallback(code: String): Currency =
        runCatching { Currency.getInstance(code) }
            .getOrElse { Currency.getInstance(FALLBACK_CURRENCY) }
}
