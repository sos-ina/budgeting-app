package com.sosina.terefe.budgetingapp.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.data.auth.SessionState
import com.sosina.terefe.budgetingapp.domain.model.AppSettings
import com.sosina.terefe.budgetingapp.domain.model.MascotType
import com.sosina.terefe.budgetingapp.domain.model.PeriodMode
import com.sosina.terefe.budgetingapp.domain.model.ThemeMode
import java.util.Currency

/** Which pop-up dialog is currently open, if any. */
private enum class SettingsDialog { CURRENCY, THEME, PERIOD, PAYDAY, MASCOT, SIGN_OUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onOpenLabels: (() -> Unit)? = null,
    onOpenAchievements: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text("Back") }
                    }
                }
            )
        }
    ) { padding ->
        val current = settings
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            SettingsContent(
                settings = current,
                viewModel = viewModel,
                onOpenLabels = onOpenLabels,
                onOpenAchievements = onOpenAchievements,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun SettingsContent(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onOpenLabels: (() -> Unit)?,
    onOpenAchievements: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    var openDialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val session by viewModel.session.collectAsStateWithLifecycle()
    val isSigningIn by viewModel.isSigningIn.collectAsStateWithLifecycle()
    val accountMessage by viewModel.accountMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // ---------- Account ----------
        SectionHeader("Account")

        when (val s = session) {
            is SessionState.SignedIn -> {
                SettingRow(
                    title = s.user.name ?: "Signed in",
                    subtitle = s.user.email ?: "Google account",
                    onClick = {}
                )
                SettingRow(
                    title = "Sign out",
                    subtitle = "Return to the welcome screen",
                    onClick = { openDialog = SettingsDialog.SIGN_OUT }
                )
            }

            SessionState.Guest -> {
                SettingRow(
                    title = "Guest mode",
                    subtitle = "Your data is only saved on this phone",
                    onClick = {}
                )
                SettingRow(
                    title = if (isSigningIn) "Signing in…" else "Sign up with Google",
                    subtitle = "Back up your data and use it on other devices",
                    onClick = { viewModel.signInWithGoogle(context) }
                )
            }

            else -> Unit
        }

        accountMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ---------- General ----------
        SectionHeader("General")

        SettingRow(
            title = "Currency",
            subtitle = currencyLabel(settings.currencyCode),
            onClick = { openDialog = SettingsDialog.CURRENCY }
        )
        SettingRow(
            title = "Theme",
            subtitle = settings.themeMode.label(),
            onClick = { openDialog = SettingsDialog.THEME }
        )
        if (onOpenLabels != null) {
            SettingRow(
                title = "Labels",
                subtitle = "Add, edit, hide, or delete labels",
                onClick = onOpenLabels
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ---------- Budget month ----------
        SectionHeader("Budget month")

        SettingRow(
            title = "Budget month",
            subtitle = when (settings.periodMode) {
                PeriodMode.CALENDAR -> "Calendar month (1st to end of month)"
                PeriodMode.PAYDAY -> "Starts on day ${settings.paydayDay} of each month"
            },
            onClick = { openDialog = SettingsDialog.PERIOD }
        )
        if (settings.periodMode == PeriodMode.PAYDAY) {
            SettingRow(
                title = "Payday",
                subtitle = "Day ${settings.paydayDay}",
                onClick = { openDialog = SettingsDialog.PAYDAY }
            )
        }
        Text(
            text = "Changes apply from your next budget month.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ---------- Fun ----------
        SectionHeader("Fun")

        if (onOpenAchievements != null) {
            SettingRow(
                title = "🏅 Achievements",
                subtitle = "See the badges you've earned",
                onClick = onOpenAchievements
            )
        }

        SettingRow(
            title = "Mascot",
            subtitle = settings.mascot.label(),
            onClick = { openDialog = SettingsDialog.MASCOT }
        )
        SwitchRow(
            title = "Roast mode",
            subtitle = "Get a snarky comment after adding expenses",
            checked = settings.roastMode,
            onCheckedChange = viewModel::setRoastMode
        )
        SwitchRow(
            title = "Sound effects",
            subtitle = "Ka-ching! and friends",
            checked = settings.soundEffects,
            onCheckedChange = viewModel::setSoundEffects
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    // ---------- Dialogs ----------
    when (openDialog) {
        SettingsDialog.CURRENCY -> CurrencyDialog(
            selectedCode = settings.currencyCode,
            onSelect = { viewModel.setCurrency(it); openDialog = null },
            onDismiss = { openDialog = null }
        )

        SettingsDialog.THEME -> SingleChoiceDialog(
            title = "Theme",
            options = ThemeMode.entries,
            selected = settings.themeMode,
            label = { it.label() },
            onSelect = { viewModel.setThemeMode(it); openDialog = null },
            onDismiss = { openDialog = null }
        )

        SettingsDialog.PERIOD -> SingleChoiceDialog(
            title = "Budget month",
            options = PeriodMode.entries,
            selected = settings.periodMode,
            label = { it.label() },
            onSelect = {
                viewModel.setPeriodMode(it)
                // Choosing payday opens the day picker right away.
                openDialog = if (it == PeriodMode.PAYDAY) SettingsDialog.PAYDAY else null
            },
            onDismiss = { openDialog = null }
        )

        SettingsDialog.PAYDAY -> PaydayDialog(
            selectedDay = settings.paydayDay,
            onSelect = { viewModel.setPaydayDay(it); openDialog = null },
            onDismiss = { openDialog = null }
        )

        SettingsDialog.MASCOT -> SingleChoiceDialog(
            title = "Mascot",
            options = MascotType.entries,
            selected = settings.mascot,
            label = { it.label() },
            onSelect = { viewModel.setMascot(it); openDialog = null },
            onDismiss = { openDialog = null }
        )

        SettingsDialog.SIGN_OUT -> AlertDialog(
            onDismissRequest = { openDialog = null },
            title = { Text("Sign out?") },
            text = { Text("You'll go back to the welcome screen. You can sign in again anytime.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.signOut()
                    openDialog = null
                }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { openDialog = null }) { Text("Cancel") }
            }
        )

        null -> Unit
    }
}

// ================= Small building blocks =================

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
        modifier = Modifier.clickable { onCheckedChange(!checked) }
    )
}

/** A reusable pop-up with a list of radio-button options. */
@Composable
private fun <T> SingleChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelect(option) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Searchable list of every currency the phone knows about. */
@Composable
private fun CurrencyDialog(
    selectedCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val allCurrencies = remember {
        Currency.getAvailableCurrencies().sortedBy { it.currencyCode }
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) allCurrencies
        else allCurrencies.filter {
            it.currencyCode.contains(query, ignoreCase = true) ||
                it.displayName.contains(query, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Currency") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search, e.g. dollar or USD") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filtered, key = { it.currencyCode }) { currency ->
                        ListItem(
                            headlineContent = { Text(currency.displayName) },
                            supportingContent = { Text("${currency.currencyCode} · ${currency.symbol}") },
                            trailingContent = {
                                RadioButton(
                                    selected = currency.currencyCode == selectedCode,
                                    onClick = { onSelect(currency.currencyCode) }
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onSelect(currency.currencyCode) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** A small calendar-style grid of days 1 to 31. */
@Composable
private fun PaydayDialog(
    selectedDay: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payday") },
        text = {
            Column {
                Text(
                    text = "Your budget month will start on this day.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                (1..31).chunked(7).forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        week.forEach { day ->
                            val isSelected = day == selectedDay
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else Color.Transparent
                                    )
                                    .clickable { onSelect(day) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = day.toString(),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        // Fill the last row so the days stay aligned.
                        repeat(7 - week.size) { Spacer(modifier = Modifier.weight(1f)) }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "If a month is shorter, it starts on that month's last day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ================= Display labels =================

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun PeriodMode.label(): String = when (this) {
    PeriodMode.CALENDAR -> "Calendar month (1st to end of month)"
    PeriodMode.PAYDAY -> "Starts on payday"
}

private fun MascotType.label(): String = when (this) {
    MascotType.NONE -> "🚫  No mascot"
    MascotType.PIGEON -> "🐦  Pigeon"
    MascotType.HORSE -> "🐴  Horse"
    MascotType.DONKEY -> "🫏  Donkey"
    MascotType.CAT -> "🐱  Cat"
    MascotType.DOG -> "🐶  Dog"
}

private fun currencyLabel(code: String): String =
    runCatching { Currency.getInstance(code) }
        .map { "${it.currencyCode} · ${it.displayName}" }
        .getOrDefault(code)
