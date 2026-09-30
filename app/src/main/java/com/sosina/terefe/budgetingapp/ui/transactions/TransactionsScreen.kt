package com.sosina.terefe.budgetingapp.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.ui.dashboard.ActivityRow
import com.sosina.terefe.budgetingapp.ui.dashboard.DeletedItem
import com.sosina.terefe.budgetingapp.ui.dashboard.ExpenseListItem
import com.sosina.terefe.budgetingapp.ui.dashboard.IncomeListItem
import com.sosina.terefe.budgetingapp.ui.dashboard.title
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onBack: () -> Unit,
    onEditExpense: (transactionId: String) -> Unit,
    onEditIncome: (incomeId: String) -> Unit,
    deletedItem: DeletedItem?,
    onDeletedItemHandled: () -> Unit,
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Same "Undo" behavior as the dashboard, for deletes made from here.
    LaunchedEffect(deletedItem) {
        val item = deletedItem ?: return@LaunchedEffect
        val message = if (item is DeletedItem.IncomeItem) "Income deleted" else "Expense deleted"
        val result = snackbarHostState.showSnackbar(message = message, actionLabel = "Undo")
        if (result == SnackbarResult.ActionPerformed) viewModel.restore(item)
        onDeletedItemHandled()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ---------- Month switcher ----------
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = viewModel::goOlder, enabled = state.canGoOlder) {
                    Text("‹", style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    text = if (state.isAllTime) "All time" else state.selectedPeriod?.title().orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::goNewer, enabled = state.canGoNewer) {
                    Text("›", style = MaterialTheme.typography.headlineSmall)
                }
            }

            // ---------- Search ----------
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text("Search items, stores, notes…") },
                singleLine = true,
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        TextButton(onClick = { viewModel.onQueryChange("") }) { Text("✕") }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // ---------- Type filter + all time ----------
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.isAllTime,
                        onClick = viewModel::toggleAllTime,
                        label = { Text("🗓️  All time") }
                    )
                }
                items(EntryTypeFilter.entries) { type ->
                    FilterChip(
                        selected = state.typeFilter == type,
                        onClick = { viewModel.onTypeFilterChange(type) },
                        label = {
                            Text(
                                when (type) {
                                    EntryTypeFilter.ALL -> "All"
                                    EntryTypeFilter.EXPENSES -> "Expenses"
                                    EntryTypeFilter.INCOME -> "Income"
                                }
                            )
                        }
                    )
                }
            }

            // ---------- Label filter ----------
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.labelFilterId == null,
                        onClick = { viewModel.onLabelFilterChange(null) },
                        label = { Text("All labels") }
                    )
                }
                items(state.filterLabels, key = { it.id }) { label ->
                    FilterChip(
                        selected = state.labelFilterId == label.id,
                        onClick = { viewModel.onLabelFilterChange(label.id) },
                        label = { Text("${label.emoji}  ${label.name}") }
                    )
                }
            }

            // ---------- Totals for what's shown ----------
            Text(
                text = resultsSummary(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ---------- The list ----------
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)
            ) {
                if (state.days.isEmpty()) {
                    item {
                        Text(
                            text = if (state.query.isNotBlank()) "Nothing matches \"${state.query}\"."
                            else "Nothing recorded here.",
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
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
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
    }
}

private fun resultsSummary(state: TransactionsUiState): String {
    val count = if (state.resultCount == 1) "1 entry" else "${state.resultCount} entries"
    val parts = buildList {
        add(count)
        if (state.typeFilter != EntryTypeFilter.INCOME) {
            add("Spent ${Money.format(state.expenseTotal, state.currencyCode)}")
        }
        if (state.typeFilter != EntryTypeFilter.EXPENSES && state.labelFilterId == null) {
            add("Income ${Money.format(state.incomeTotal, state.currencyCode)}")
        }
    }
    return parts.joinToString(" · ")
}

private val DAY_THIS_YEAR = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())
private val DAY_OTHER_YEAR = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.getDefault())

private fun dayHeader(date: LocalDate): String {
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(DAY_THIS_YEAR)
        else -> date.format(DAY_OTHER_YEAR)
    }
}
