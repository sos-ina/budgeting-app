package com.sosina.terefe.budgetingapp.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.sync.AccountMigrationManager
import com.sosina.terefe.budgetingapp.data.sync.AccountPrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountSetupViewModel @Inject constructor(
    private val manager: AccountMigrationManager
) : ViewModel() {

    val prompt: StateFlow<AccountPrompt?> = manager.prompt
    val isUploading: StateFlow<Boolean> = manager.isUploading
    val error: StateFlow<String?> = manager.error

    fun saveGuestData(uid: String) {
        viewModelScope.launch { manager.saveGuestData(uid) }
    }

    fun startFresh(uid: String) {
        viewModelScope.launch { manager.startFresh(uid) }
    }

    fun dismiss() = manager.dismissPrompt()
}

/** Shows the right pop-up after sign-in, or nothing at all. */
@Composable
fun AccountSetupHost(viewModel: AccountSetupViewModel = hiltViewModel()) {
    val prompt by viewModel.prompt.collectAsStateWithLifecycle()
    val isUploading by viewModel.isUploading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    when (val current = prompt) {
        is AccountPrompt.SaveGuestData -> SaveGuestDataDialog(
            isUploading = isUploading,
            error = error,
            onSave = { viewModel.saveGuestData(current.uid) },
            onStartFresh = { viewModel.startFresh(current.uid) }
        )

        AccountPrompt.AlreadyHasData -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text("Welcome back! 👋") },
            text = {
                Text(
                    "You already have data in this account, so we've loaded it.\n\n" +
                        "The guest data on this phone was left as it is. You'll find it again " +
                        "if you sign out and continue as guest."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text("OK") }
            }
        )

        null -> Unit
    }
}

@Composable
private fun SaveGuestDataDialog(
    isUploading: Boolean,
    error: String?,
    onSave: () -> Unit,
    onStartFresh: () -> Unit
) {
    AlertDialog(
        // A choice is needed, so tapping outside doesn't close it.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Keep your guest data?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "You have income and expenses saved on this phone as a guest.\n\n" +
                        "Save them to your account to back them up and use them on other devices, " +
                        "or start this account fresh. If you start fresh, your guest data stays on this phone."
                )
                if (isUploading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("  Uploading…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = !isUploading) {
                Text(if (error != null) "Try again" else "Save my data")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onStartFresh, enabled = !isUploading) { Text("Start fresh") }
                Spacer8()
            }
        }
    )
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
