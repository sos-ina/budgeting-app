package com.sosina.terefe.budgetingapp.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.domain.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showRangePicker by remember { mutableStateOf(false) }
    val format: (Long) -> String = { Money.format(it, state.currencyCode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stats") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // ---------- Range chips ----------
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(StatsRange.entries) { range ->
                        FilterChip(
                            selected = state.rangeType == range,
                            onClick = {
                                if (range == StatsRange.CUSTOM) showRangePicker = true
                                else viewModel.selectRange(range)
                            },
                            label = { Text(range.label()) }
                        )
                    }
                }
            }

            // ---------- Title ----------
            item {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)
                )
            }

            // ---------- Summary ----------
            item {
                SummaryCard(state = state, format = format)
            }

            // ---------- Spending by label ----------
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Spending by label",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    // One tap switches between donut and bars.
                    IconButton(onClick = viewModel::toggleChartType) {
                        Text(if (state.chartType == ChartType.DONUT) "📊" else "🍩")
                    }
                }
            }

            if (state.entries.isEmpty()) {
                item {
                    Text(
                        text = "No expenses in this range.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                    )
                }
            } else if (state.chartType == ChartType.DONUT) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DonutChart(entries = state.entries) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Spent", style = MaterialTheme.typography.labelMedium)
                                Text(
                                    text = format(state.spent),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                items(state.entries, key = { it.key }) { entry ->
                    LegendRow(entry = entry, format = format)
                }
            } else {
                item {
                    HorizontalBarChart(
                        entries = state.entries,
                        formatValue = format,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // ---------- Extra views for the chosen range ----------
            state.range?.let { range ->
                val days = ChronoUnit.DAYS.between(range.start, range.end) + 1

                // Calendar: only for up to about two months, or the squares get too small.
                item {
                    SectionHeader("Daily spending")
                }
                item {
                    if (days <= 62) {
                        CalendarHeatmap(
                            start = range.start,
                            end = range.end,
                            totals = state.dailyTotals,
                            formatValue = format,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    } else {
                        HintText("Pick a range of two months or less to see the calendar.")
                    }
                }

                // Line chart
                if (days in 2..400) {
                    item { SectionHeader("Spending over time") }
                    item {
                        CumulativeLineChart(
                            start = range.start,
                            end = range.end,
                            totals = state.dailyTotals,
                            income = state.income,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                // Top items
                item { SectionHeader("Top items") }
                if (state.topItems.isEmpty()) {
                    item { HintText("Add titles or items to your expenses to see what you buy most.") }
                } else {
                    items(state.topItems, key = { "top-" + it.name.lowercase() }) { item ->
                        TopItemRow(item = item, format = format)
                    }
                }
            }
        }
    }

    // ---------- Custom range picker ----------
    if (showRangePicker) {
        val pickerState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = state.customRange?.start?.toPickerMillis(),
            initialSelectedEndDateMillis = state.customRange?.end?.toPickerMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = pickerState.selectedStartDateMillis
                        val end = pickerState.selectedEndDateMillis ?: start
                        if (start != null && end != null) {
                            viewModel.setCustomRange(start.pickerMillisToDate(), end.pickerMillisToDate())
                        }
                        showRangePicker = false
                    },
                    enabled = pickerState.selectedStartDateMillis != null
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showRangePicker = false }) { Text("Cancel") }
            }
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryCard(state: StatsUiState, format: (Long) -> String) {
    val isNegative = state.resultAmount < 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Figure("Income", format(state.income), Modifier.weight(1f))
                Figure("Spent", format(state.spent), Modifier.weight(1f), alignEnd = true)
            }
            Text(
                text = state.resultLabel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = format(state.resultAmount),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = if (isNegative) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onPrimaryContainer
            )
            state.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier, alignEnd: Boolean = false) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** One row under the donut: color dot, label, percentage, amount. */
@Composable
private fun LegendRow(entry: ChartEntry, format: (Long) -> String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(Color(entry.color), CircleShape)
        )
        Text(
            text = "${entry.emoji}  ${entry.label}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${(entry.fraction * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = format(entry.value), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp)
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun TopItemRow(item: TopItem, format: (Long) -> String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (item.count == 1) "Bought once" else "Bought ${item.count} times",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(format(item.total), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun StatsRange.label(): String = when (this) {
    StatsRange.THIS_MONTH -> "This month"
    StatsRange.LAST_MONTH -> "Last month"
    StatsRange.CUSTOM -> "Custom"
    StatsRange.ALL_TIME -> "All time"
}

private fun LocalDate.toPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.pickerMillisToDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
