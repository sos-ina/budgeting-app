package com.sosina.terefe.budgetingapp.ui.income

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.domain.model.Income
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/**
 * @param onFinished called after saving or cancelling (go back).
 * @param onDeleted called after deleting, with a copy for "Undo".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IncomeEditScreen(
    onFinished: () -> Unit,
    onDeleted: (Income) -> Unit = { onFinished() },
    viewModel: IncomeEditViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }

    // React to one-time events from the ViewModel.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                IncomeEditEvent.Saved -> onFinished()
                is IncomeEditEvent.Deleted -> onDeleted(event.income)
            }
        }
    }

    // When adding new income, put the cursor in the amount field right away.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && !state.isEditing) {
            runCatching { amountFocus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit income" else "Add income") },
                navigationIcon = {
                    TextButton(onClick = onFinished) { Text("Cancel") }
                },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave) {
                        Text("Save")
                    }
                }
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---------- Amount ----------
            OutlinedTextField(
                value = state.amountText,
                onValueChange = viewModel::onAmountChange,
                label = { Text("Amount") },
                prefix = { Text(currencySymbol(state.currencyCode) + " ") },
                isError = state.amountError != null,
                supportingText = state.amountError?.let { error -> { Text(error) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocus)
            )

            // ---------- Label ----------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Label", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.labels
                        // Hidden labels are skipped, unless this income already uses one.
                        .filter { !it.isHidden || it.id == state.labelId }
                        .forEach { label ->
                            FilterChip(
                                selected = label.id == state.labelId,
                                onClick = { viewModel.onLabelSelect(label.id) },
                                label = { Text("${label.emoji}  ${label.name}") }
                            )
                        }
                }
            }

            // ---------- Date ----------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Date", style = MaterialTheme.typography.labelLarge)
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(state.date.format(DATE_FORMAT))
                }
            }

            // ---------- Note ----------
            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChange,
                label = { Text("Note (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            // ---------- Delete (editing only) ----------
            if (state.isEditing) {
                TextButton(
                    onClick = viewModel::delete,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Delete income") }
            }
        }
    }

    // ---------- Date picker pop-up ----------
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date.toPickerMillis()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        viewModel.onDateChange(it.pickerMillisToDate())
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())

private fun currencySymbol(code: String): String =
    runCatching { Currency.getInstance(code).symbol }.getOrDefault(code)

// The date picker works with milliseconds in UTC, so we convert carefully
// to avoid the date shifting by a day in some time zones.
private fun LocalDate.toPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.pickerMillisToDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
