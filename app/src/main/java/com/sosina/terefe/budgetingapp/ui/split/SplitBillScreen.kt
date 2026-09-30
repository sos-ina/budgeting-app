package com.sosina.terefe.budgetingapp.ui.split

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.domain.model.Money
import com.sosina.terefe.budgetingapp.domain.split.ME
import com.sosina.terefe.budgetingapp.domain.split.SplitPerson
import com.sosina.terefe.budgetingapp.ui.mascot.ScanningIndicator
import java.util.Currency

/** The add/rename person pop-up: null person = adding someone new. */
private data class PersonDialog(val person: SplitPerson?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SplitBillScreen(
    onBack: () -> Unit,
    viewModel: SplitBillViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var personDialog by remember { mutableStateOf<PersonDialog?>(null) }

    val symbol = runCatching { Currency.getInstance(state.currencyCode).symbol }.getOrDefault(state.currencyCode)
    val money = { cents: Long -> Money.format(cents, state.currencyCode) }
    val result = state.result

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is SplitBillEvent.AddedToBudget) {
                Toast.makeText(context, "Your share was added to your budget", Toast.LENGTH_SHORT).show()
                onBack()
            }
        }
    }

    // ---------- Camera & gallery (same approach as Add Expense) ----------
    var pendingPhotoUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingPhotoUri
        if (saved && uri != null) viewModel.scanReceipt(Uri.parse(uri))
    }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split the bill") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- Where ----------
            item {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = viewModel::onTitleChange,
                    label = { Text("Where? (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---------- Scan ----------
            item {
                if (state.isScanning) {
                    ScanningIndicator()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = openCamera, modifier = Modifier.weight(1f)) {
                            Text("📷  Scan bill")
                        }
                        OutlinedButton(
                            onClick = {
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("🖼️  From gallery") }
                    }
                }
            }

            state.message?.let { message ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
                        }
                    }
                }
            }

            // ---------- People ----------
            item { SectionTitle("People") }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.people.forEach { person ->
                        AssistChip(
                            onClick = { if (person.id != ME) personDialog = PersonDialog(person) },
                            label = { Text(if (person.id == ME) "🙋 ${person.name}" else "👤 ${person.name}") }
                        )
                    }
                    AssistChip(
                        onClick = { personDialog = PersonDialog(null) },
                        label = { Text("+ Add") }
                    )
                }
            }

            // ---------- Items ----------
            item { SectionTitle("Items") }
            items(state.items, key = { it.id }) { item ->
                ItemCard(
                    item = item,
                    people = state.people,
                    currencySymbol = symbol,
                    onNameChange = { viewModel.onItemNameChange(item.id, it) },
                    onPriceChange = { viewModel.onItemPriceChange(item.id, it) },
                    onRemove = { viewModel.removeItem(item.id) },
                    onEveryone = { viewModel.setItemForEveryone(item.id) },
                    onTogglePerson = { personId -> viewModel.toggleItemPerson(item.id, personId) }
                )
            }
            item {
                OutlinedButton(onClick = viewModel::addItem) { Text("+  Add item") }
            }

            // ---------- Extras ----------
            item { SectionTitle("Tax, tip & service") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField("Tax", state.taxText, symbol, viewModel::onTaxChange, Modifier.weight(1f))
                    AmountField("Tip", state.tipText, symbol, viewModel::onTipChange, Modifier.weight(1f))
                    AmountField("Service", state.serviceText, symbol, viewModel::onServiceChange, Modifier.weight(1f))
                }
            }
            item {
                Text(
                    text = "Split by what each person ordered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---------- Results ----------
            item { SectionTitle("Who pays what") }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        result.shares.forEach { share ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(share.person.name, style = MaterialTheme.typography.titleMedium)
                                    if (share.extras > 0) {
                                        Text(
                                            text = "${money(share.itemsSubtotal)} + ${money(share.extras)} extras",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Text(
                                    text = money(share.total),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        HorizontalDivider()
                        Row {
                            Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(money(result.grandTotal), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            // ---------- Actions ----------
            item {
                val myTotal = result.shares.firstOrNull { it.person.id == ME }?.total ?: 0L
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, viewModel.shareText())
                            context.startActivity(Intent.createChooser(intent, "Share the split"))
                        },
                        enabled = result.grandTotal > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📤  Share with friends") }

                    Button(
                        onClick = viewModel::addMyShareToBudget,
                        enabled = myTotal > 0 && !state.isAddingToBudget,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Add my share (${money(myTotal)}) to my budget") }
                }
            }
        }
    }

    // ---------- Add / rename person ----------
    personDialog?.let { dialog ->
        PersonNameDialog(
            person = dialog.person,
            onSave = { name ->
                val existing = dialog.person
                if (existing == null) viewModel.addPerson(name) else viewModel.renamePerson(existing.id, name)
                personDialog = null
            },
            onRemove = dialog.person?.let { p -> { viewModel.removePerson(p.id); personDialog = null } },
            onDismiss = { personDialog = null }
        )
    }
}

// ================= Pieces =================

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun AmountField(
    label: String,
    value: String,
    currencySymbol: String,
    onChange: (String) -> Unit,
    modifier: Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        prefix = { Text("$currencySymbol ") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

/** One item: its name and price, then "who shared this?" chips underneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemCard(
    item: SplitItemDraft,
    people: List<SplitPerson>,
    currencySymbol: String,
    onNameChange: (String) -> Unit,
    onPriceChange: (String) -> Unit,
    onRemove: () -> Unit,
    onEveryone: () -> Unit,
    onTogglePerson: (String) -> Unit
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
                    prefix = { Text("$currencySymbol ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(120.dp)
                )
                TextButton(onClick = onRemove) { Text("✕") }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = item.sharedBy.isEmpty(),
                    onClick = onEveryone,
                    label = { Text("👥 Everyone") }
                )
                people.forEach { person ->
                    FilterChip(
                        selected = person.id in item.sharedBy,
                        onClick = { onTogglePerson(person.id) },
                        label = { Text(person.name) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonNameDialog(
    person: SplitPerson?,
    onSave: (String) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by remember(person) { mutableStateOf(person?.name.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (person == null) "Add person" else "Edit person") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("Name") },
                    placeholder = { Text("Leave empty for \"Person 2\"") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (onRemove != null) {
                    TextButton(
                        onClick = onRemove,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Remove from split") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text(if (person == null) "Add" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
