package com.sosina.terefe.budgetingapp.ui.expense

import android.content.ActivityNotFoundException
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.domain.model.Transaction
import com.sosina.terefe.budgetingapp.ui.mascot.ScanningIndicator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExpenseEditScreen(
    onFinished: () -> Unit,
    onDeleted: (Transaction) -> Unit = { onFinished() },
    startWithCamera: Boolean = false,
    sharedImageUri: String? = null,
    viewModel: ExpenseEditViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val amountFocus = remember { FocusRequester() }
    val context = LocalContext.current

    // Where the camera app saves the photo. Kept as text so it survives screen rotation.
    var pendingPhotoUri by rememberSaveable { mutableStateOf<String?>(null) }

    // Opens the phone's camera app. "saved" is true if a photo was taken.
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingPhotoUri
        if (saved && uri != null) viewModel.scanReceipt(Uri.parse(uri))
    }

    // Opens Android's photo picker (no permission needed).
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.scanReceipt(uri)
    }

    val openCamera: () -> Unit = {
        val uri = viewModel.createPhotoUri()
        pendingPhotoUri = uri.toString()
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No camera app found on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    val openGallery: () -> Unit = {
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // Opened from the dashboard's Scan button: go straight to the camera, only once.
    var autoCameraDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (startWithCamera && !autoCameraDone && !state.isLoading) {
            autoCameraDone = true
            openCamera()
        }
    }

    // Opened with an image shared from another app: scan it, only once.
    var sharedScanDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.isLoading) {
        if (sharedImageUri != null && !sharedScanDone && !state.isLoading) {
            sharedScanDone = true
            viewModel.scanReceipt(Uri.parse(sharedImageUri))
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ExpenseEditEvent.Saved -> onFinished()
                is ExpenseEditEvent.Deleted -> onDeleted(event.transaction)
            }
        }
    }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading && !state.isEditing && !startWithCamera && sharedImageUri == null) {
            runCatching { amountFocus.requestFocus() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) "Edit expense" else "Add expense") },
                navigationIcon = { TextButton(onClick = onFinished) { Text("Cancel") } },
                actions = {
                    TextButton(onClick = viewModel::save, enabled = state.canSave) { Text("Save") }
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

        val symbol = currencySymbol(state.currencyCode)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---------- Scan (new expenses only) ----------
            if (!state.isEditing) {
                ScanSection(
                    isScanning = state.isScanning,
                    onCamera = openCamera,
                    onGallery = openGallery
                )
            }
            state.scanMessage?.let { message ->
                ScanMessageCard(message = message, onDismiss = viewModel::dismissScanMessage)
            }

            // ---------- Total ----------
            OutlinedTextField(
                value = state.amountText,
                onValueChange = viewModel::onAmountChange,
                label = { Text("Total") },
                placeholder = {
                    if (state.hasItems && state.itemsTotal > 0) Text(Money.toInputText(state.itemsTotal))
                },
                prefix = { Text("$symbol ") },
                isError = state.amountError != null,
                supportingText = state.amountError?.let { error -> { Text(error) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.headlineSmall,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(amountFocus)
            )

            // ---------- Label ----------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Label", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.topLevelLabels
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

            // ---------- Sub-label (only if the chosen label has some) ----------
            val visibleSubLabels = state.subLabels.filter { !it.isHidden || it.id == state.subLabelId }
            if (visibleSubLabels.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sub-label (optional)", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.subLabelId == null,
                            onClick = { viewModel.onSubLabelSelect(null) },
                            label = { Text("None") }
                        )
                        visibleSubLabels.forEach { sub ->
                            FilterChip(
                                selected = sub.id == state.subLabelId,
                                onClick = { viewModel.onSubLabelSelect(sub.id) },
                                label = { Text("${sub.emoji}  ${sub.name}") }
                            )
                        }
                    }
                }
            }

            // ---------- Date & time ----------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("When", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(2f)
                    ) { Text(state.date.format(DATE_FORMAT)) }
                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f)
                    ) { Text(state.time.format(TIME_FORMAT)) }
                }
            }

            // ---------- Title ----------
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("Store or title (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // ---------- Items ----------
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Items (optional)", style = MaterialTheme.typography.labelLarge)

                state.items.forEach { item ->
                    ItemRow(
                        item = item,
                        onNameChange = { viewModel.onItemNameChange(item.id, it) },
                        onPriceChange = { viewModel.onItemPriceChange(item.id, it) },
                        onRemove = { viewModel.removeItem(item.id) }
                    )
                }

                OutlinedButton(onClick = viewModel::addItem) { Text("+  Add item") }

                if (state.hasItems) {
                    ItemsSummary(
                        state = state,
                        onUseItemsTotal = viewModel::useItemsTotalAsAmount
                    )
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
                ) { Text("Delete expense") }
            }
        }
    }

    // ---------- Date picker ----------
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = state.date.toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { viewModel.onDateChange(it.pickerMillisToDate()) }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = pickerState)
        }
    }

    // ---------- Time picker ----------
    if (showTimePicker) {
        val context = LocalContext.current
        val timeState = rememberTimePickerState(
            initialHour = state.time.hour,
            initialMinute = state.time.minute,
            is24Hour = DateFormat.is24HourFormat(context) // follows the phone's setting
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onTimeChange(LocalTime.of(timeState.hour, timeState.minute))
                    showTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = timeState) }
        )
    }
}

/** The two scan buttons, or a "reading" indicator while a scan runs. */
@Composable
private fun ScanSection(isScanning: Boolean, onCamera: () -> Unit, onGallery: () -> Unit) {
    if (isScanning) {
        ScanningIndicator()
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCamera, modifier = Modifier.weight(1f)) {
                Text("📷  Scan receipt")
            }
            OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f)) {
                Text("🖼️  From gallery")
            }
        }
    }
}

/** Tells the user what the scan found (or didn't). */
@Composable
private fun ScanMessageCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun ItemRow(
    item: ItemDraft,
    onNameChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = item.name,
            onValueChange = onNameChange,
            placeholder = { Text("Item") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = item.priceText,
            onValueChange = onPriceChange,
            placeholder = { Text("0.00") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(110.dp)
        )
        TextButton(onClick = onRemove) { Text("✕") }
    }
}

/** Shows how the items compare to the total, including "Other / unlisted". */
@Composable
private fun ItemsSummary(state: ExpenseEditUiState, onUseItemsTotal: () -> Unit) {
    val itemsTotal = state.itemsTotal
    val total = state.amountCents

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider()
        SummaryLine("Items total", Money.format(itemsTotal, state.currencyCode))

        when {
            total == null ->
                Text(
                    text = "No total typed, so the total will be ${Money.format(itemsTotal, state.currencyCode)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

            total > itemsTotal ->
                SummaryLine("Other / unlisted", Money.format(total - itemsTotal, state.currencyCode))

            total < itemsTotal -> {
                Text(
                    text = "Items add up to more than the total.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                TextButton(onClick = onUseItemsTotal) {
                    Text("Set total to ${Money.format(itemsTotal, state.currencyCode)}")
                }
            }

            else ->
                Text(
                    text = "✓ Items match the total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
        }
    }
}

@Composable
private fun SummaryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------- Helpers ----------------

private val DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

private val TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun currencySymbol(code: String): String =
    runCatching { Currency.getInstance(code).symbol }.getOrDefault(code)

private fun LocalDate.toPickerMillis(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.pickerMillisToDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
