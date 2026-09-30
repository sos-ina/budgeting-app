package com.sosina.terefe.budgetingapp.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Currency
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.domain.mascot.Mascot
import com.sosina.terefe.budgetingapp.domain.model.BudgetPeriod
import com.sosina.terefe.budgetingapp.domain.model.MascotType
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.ui.labels.EmojiBadge
import com.sosina.terefe.budgetingapp.ui.mascot.ConfettiHost
import com.sosina.terefe.budgetingapp.ui.mascot.MascotView
import com.sosina.terefe.budgetingapp.ui.mascot.RoastCardHost
import com.sosina.terefe.budgetingapp.ui.mascot.StreakBanner
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onAddExpense: () -> Unit,
    onEditExpense: (transactionId: String) -> Unit,
    onScanReceipt: () -> Unit,
    onQuickAdd: () -> Unit,
    onAddIncome: () -> Unit,
    onEditIncome: (incomeId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSplit: () -> Unit,
    onOpenStats: () -> Unit,
    deletedItem: DeletedItem?,             // set right after something is deleted
    onDeletedItemHandled: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val salaryPrompt by viewModel.salaryPrompt.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPeriod()
    }

    // After a delete, offer "Undo" for a few seconds.
    LaunchedEffect(deletedItem) {
        val item = deletedItem ?: return@LaunchedEffect
        val message = when (item) {
            is DeletedItem.IncomeItem -> "Income deleted"
            is DeletedItem.ExpenseItem -> "Expense deleted"
        }
        val result = snackbarHostState.showSnackbar(message = message, actionLabel = "Undo")
        if (result == SnackbarResult.ActionPerformed) viewModel.restore(item)
        onDeletedItemHandled()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("My Budget") },
                    actions = {
                        IconButton(onClick = onOpenStats) { Text("📊") }
                        IconButton(onClick = onOpenHistory) { Text("📜") }
                        IconButton(onClick = onOpenSplit) { Text("🧾") }
                        IconButton(onClick = onOpenSettings) { Text("⚙️") }
                    }
                )
            },
            floatingActionButton = {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExtendedFloatingActionButton(
                            onClick = onQuickAdd,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ) { Text("⚡  Quick") }
                        ExtendedFloatingActionButton(
                            onClick = onScanReceipt,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        ) { Text("📷  Scan") }
                    }
                    ExtendedFloatingActionButton(
                        onClick = onAddIncome,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) { Text("+  Income") }
                    ExtendedFloatingActionButton(onClick = onAddExpense) {
                        Text("+  Expense")
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            val period = state.period
            if (state.isLoading || period == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                // Extra bottom space so the three buttons don't cover the last rows.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 230.dp)
            ) {
                item(key = "roast") {
                    RoastCardHost()
                }

                if (state.mascot != MascotType.NONE) {
                    item(key = "mascot") {
                        MascotView(
                            mascot = state.mascot,
                            mood = Mascot.moodFor(state.incomeTotal, state.spentTotal),
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }

                item(key = "summary") {
                    SummaryCard(state = state, period = period)
                }

                item(key = "streak") {
                    StreakBanner(modifier = Modifier.padding(top = 12.dp))
                }

                if (state.days.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "Nothing here yet.\nAdd your income, then your first expense!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                        )
                    }
                }

                state.days.forEach { day ->
                    item(key = "day-${day.date}") {
                        Text(
                            text = dayHeader(day.date),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
                        )
                    }
                    items(day.rows, key = { it.key }) { row ->
                        when (row) {
                            is ActivityRow.Expense -> ExpenseListItem(
                                row = row,
                                currencyCode = state.currencyCode,
                                onClick = { onEditExpense(row.transaction.id) }
                            )
                            is ActivityRow.IncomeEntry -> IncomeListItem(
                                row = row,
                                currencyCode = state.currencyCode,
                                onClick = { onEditIncome(row.income.id) }
                            )
                        }
                    }
                }
            }
        }
        ConfettiHost()
    }

    salaryPrompt?.let { prompt ->
        SalaryPromptDialog(
            prompt = prompt,
            currencyCode = state.currencyCode,
            onAdd = { amountText -> viewModel.acceptSalaryPrompt(prompt, amountText) },
            onNotNow = { viewModel.dismissSalaryPrompt(prompt) }
        )
    }
}

// ---------------- New month salary prompt ----------------

@Composable
private fun SalaryPromptDialog(
    prompt: SalaryPrompt,
    currencyCode: String,
    onAdd: (String) -> Unit,
    onNotNow: () -> Unit
) {
    // Pre-filled with last month's salary; the user can change it.
    var amountText by remember(prompt.periodId) {
        mutableStateOf(Money.toInputText(prompt.suggestedAmount))
    }
    val isValid = (Money.parse(amountText) ?: 0L) > 0L
    val symbol = runCatching { Currency.getInstance(currencyCode).symbol }.getOrDefault(currencyCode)

    AlertDialog(
        onDismissRequest = onNotNow,
        title = { Text("New month! 🎉") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add this month's salary? Here's last month's amount, change it if needed.")
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { text ->
                        amountText = text.filter { it.isDigit() || it == '.' || it == ',' }.take(15)
                    },
                    prefix = { Text("$symbol ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(amountText) }, enabled = isValid) {
                Text("Add salary")
            }
        },
        dismissButton = {
            TextButton(onClick = onNotNow) { Text("Not now") }
        }
    )
}

// ---------------- Summary card ----------------

@Composable
private fun SummaryCard(state: DashboardUiState, period: BudgetPeriod) {
    val leftColor =
        if (state.isOverspent) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onPrimaryContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(period.title(), style = MaterialTheme.typography.titleMedium)
            Text(daysLeftText(period), style = MaterialTheme.typography.bodySmall)

            // The big number: what's left.
            Text(
                text = if (state.isOverspent) "Overspent" else "Left",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = Money.format(state.left, state.currencyCode),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = leftColor
            )

            // Progress bar: how much of the income is spent.
            LinearProgressIndicator(
                progress = { state.spentFraction },
                color = if (state.isOverspent) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                SummaryFigure(
                    label = "Income",
                    value = Money.format(state.incomeTotal, state.currencyCode),
                    modifier = Modifier.weight(1f)
                )
                SummaryFigure(
                    label = "Spent",
                    value = Money.format(state.spentTotal, state.currencyCode),
                    modifier = Modifier.weight(1f),
                    alignEnd = true
                )
            }

            if (state.incomeTotal == 0L) {
                Text(
                    text = "Add this month's income to see what's left.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SummaryFigure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- List rows ----------------

@Composable
fun ExpenseListItem(row: ActivityRow.Expense, currencyCode: String, onClick: () -> Unit) {
    val t = row.transaction
    val labelName = row.label?.name ?: "Uncategorized"

    // Headline: the store/title if there is one, otherwise the most specific label.
    val headline = t.title ?: row.subLabel?.name ?: labelName

    val details = buildList {
        add(if (row.subLabel != null) "$labelName · ${row.subLabel.name}" else labelName)
        add(t.dateTime.format(TIME_FORMAT))
        if (t.hasItems) add(if (t.items.size == 1) "1 item" else "${t.items.size} items")
    }.distinct().joinToString(" · ")

    val badgeLabel = row.subLabel ?: row.label
    ListItem(
        headlineContent = { Text(headline, maxLines = 1) },
        supportingContent = { Text(details, maxLines = 1) },
        leadingContent = {
            EmojiBadge(emoji = badgeLabel?.emoji ?: "❔", color = badgeLabel?.color ?: 0xFF90A4AE)
        },
        trailingContent = {
            Text(
                text = "−" + Money.format(t.amount, currencyCode),
                style = MaterialTheme.typography.titleMedium
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun IncomeListItem(row: ActivityRow.IncomeEntry, currencyCode: String, onClick: () -> Unit) {
    val label = row.label
    ListItem(
        headlineContent = { Text(label?.name ?: "Income", maxLines = 1) },
        supportingContent = row.income.note?.let { note -> { Text(note, maxLines = 1) } },
        leadingContent = {
            EmojiBadge(emoji = label?.emoji ?: "💰", color = label?.color ?: 0xFF66BB6A)
        },
        trailingContent = {
            Text(
                text = "+" + Money.format(row.income.amount, currencyCode),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

// ---------------- Formatting helpers ----------------

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val DAY_HEADER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** "September 2026" for calendar months, or "25 Sep – 24 Oct" for payday months. */
fun BudgetPeriod.title(): String {
    if (isCalendarMonth) return startDate.format(MONTH_YEAR)
    return if (startDate.year == endDate.year) {
        "${startDate.format(SHORT_DATE)} – ${endDate.format(SHORT_DATE)}"
    } else {
        "${startDate.format(SHORT_DATE)} ${startDate.year} – ${endDate.format(SHORT_DATE)} ${endDate.year}"
    }
}

private fun dayHeader(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DAY_HEADER)
    }
}

private fun daysLeftText(period: BudgetPeriod): String {
    val daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), period.endDate) + 1
    return when {
        daysLeft <= 0 -> "This budget month has ended"
        daysLeft == 1L -> "Last day of this budget month!"
        else -> "$daysLeft days left"
    }
}
