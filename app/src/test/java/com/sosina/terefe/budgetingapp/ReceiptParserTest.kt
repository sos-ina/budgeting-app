package com.sosina.terefe.budgetingapp

import com.sosina.terefe.budgetingapp.domain.scan.ParsedItem
import com.sosina.terefe.budgetingapp.domain.scan.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {

    private val today = LocalDate.of(2026, 9, 29)

    @Test
    fun `reads a typical receipt`() {
        val receipt = ReceiptParser.parse(
            listOf(
                "FRESH MART",
                "Downtown Branch",
                "Date: 28/09/2026 14:32",
                "Milk 1L          4.50",
                "Bread            3.25",
                "Eggs x12        12.00",
                "SUBTOTAL        19.75",
                "VAT 5%           0.99",
                "TOTAL           20.74",
                "CASH            50.00",
                "CHANGE          29.26"
            ),
            today
        )

        assertEquals("FRESH MART", receipt.storeName)
        assertEquals(LocalDate.of(2026, 9, 28), receipt.date)
        assertEquals(
            listOf(
                ParsedItem("Milk 1L", 450),
                ParsedItem("Bread", 325),
                ParsedItem("Eggs x12", 1200)
            ),
            receipt.items
        )
        assertEquals(2074L, receipt.total)
    }

    @Test
    fun `handles European and thousands formats`() {
        assertEquals(123456L, ReceiptParser.parsePrice("1,234.56"))
        assertEquals(123456L, ReceiptParser.parsePrice("1.234,56"))
        assertEquals(1250L, ReceiptParser.parsePrice("12,50"))
    }

    @Test
    fun `ignores tax codes and currency after the price`() {
        val receipt = ReceiptParser.parse(listOf("Coffee 15.00 A", "Water AED 2.50"), today)
        assertEquals(listOf(ParsedItem("Coffee", 1500), ParsedItem("Water", 250)), receipt.items)
    }

    @Test
    fun `picks the grand total over smaller totals`() {
        val receipt = ReceiptParser.parse(listOf("Total before tax 10.00", "Grand total 10.50"), today)
        assertEquals(1050L, receipt.total)
    }

    @Test
    fun `skips discounts and payment lines`() {
        val receipt = ReceiptParser.parse(
            listOf("Pizza 30.00", "Discount -5.00", "Visa ****1234 25.00"),
            today
        )
        assertEquals(listOf(ParsedItem("Pizza", 3000)), receipt.items)
    }

    @Test
    fun `month first when the day is over 12`() {
        val receipt = ReceiptParser.parse(listOf("09/28/2026"), today)
        assertEquals(LocalDate.of(2026, 9, 28), receipt.date)
    }

    @Test
    fun `ignores future dates as misreads`() {
        val receipt = ReceiptParser.parse(listOf("01/01/2030"), today)
        assertNull(receipt.date)
    }

    @Test
    fun `text with no prices gives an empty result`() {
        val receipt = ReceiptParser.parse(listOf("12", "#44"), today)
        assertTrue(receipt.items.isEmpty())
        assertNull(receipt.total)
    }
}
