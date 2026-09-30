package com.sosina.terefe.budgetingapp.ui.quickentry

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.domain.model.Label
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickEntryScreen(
    onFinished: () -> Unit,
    viewModel: QuickEntryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is QuickEntryEvent.Saved) {
                val word = if (event.count == 1) "expense" else "expenses"
                Toast.makeText(context, "Added ${event.count} $word", Toast.LENGTH_SHORT).show()
                onFinished()
            }
        }
    }

    // Opens the phone's own speech-to-text pop-up (no permission needed).
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let(viewModel::onSpokenText)
        }
    }
    val startSpeaking: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your expenses, like \"coffee 15 and lunch 40\"")
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Voice input isn't available on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    val symbol = runCatching { Currency.getInstance(state.currencyCode).symbol }.getOrDefault(state.currencyCode)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick add") },
                navigationIcon = { TextButton(onClick = onFinished) { Text("Cancel") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- The text box ----------
            item {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::onTextChange,
                    label = { Text("What did you spend?") },
                    placeholder = { Text("e.g. coffee 15, lunch 40 and taxi 22 yesterday") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = startSpeaking, enabled = !state.isParsing) {
                        Text("🎤  Speak")
                    }
                    FilledTonalButton(
                        onClick = viewModel::understand,
                        enabled = state.text.isNotBlank() && !state.isParsing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("✨  Understand")
                    }
                }
            }

            if (state.isParsing) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Reading that…")
                    }
                }
            }

            state.message?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // ---------- Results to check ----------
            items(state.entries, key = { it.key }) { entry ->
                EntryCard(
                    entry = entry,
                    labels = state.labels,
                    currencySymbol = symbol,
                    onTitleChange = { viewModel.onTitleChange(entry.key, it) },
                    onAmountChange = { viewModel.onAmountChange(entry.key, it) },
                    onLabelChange = { viewModel.onLabelChange(entry.key, it) },
                    onRemove = { viewModel.removeEntry(entry.key) }
                )
            }

            if (state.entries.isNotEmpty()) {
                item {
                    val count = state.validCount
                    Button(
                        onClick = viewModel::saveAll,
                        enabled = count > 0 && !state.isSaving,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (count == 1) "Save 1 expense" else "Save $count expenses")
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(
    entry: DraftEntry,
    labels: List<Label>,
    currencySymbol: String,
    onTitleChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = entry.title,
                    onValueChange = onTitleChange,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = entry.amountText,
                    onValueChange = onAmountChange,
                    prefix = { Text("$currencySymbol ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(120.dp)
                )
                TextButton(onClick = onRemove) { Text("✕") }
            }

            Text(
                text = dateLabel(entry.date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(labels, key = { it.id }) { label ->
                    FilterChip(
                        selected = label.id == entry.labelId,
                        onClick = { onLabelChange(label.id) },
                        label = { Text("${label.emoji} ${label.name}") }
                    )
                }
            }
        }
    }
}

private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())

private fun dateLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "📅 Today"
    LocalDate.now().minusDays(1) -> "📅 Yesterday"
    else -> "📅 ${date.format(DATE_FORMAT)}"
}
