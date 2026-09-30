package com.sosina.terefe.budgetingapp.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun WelcomeScreen(viewModel: WelcomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding() // stays clear of the status bar and nav bar
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "💸", fontSize = 72.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Welcome!",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Track what comes in, what goes out,\nand what's left.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(48.dp))

            Button(
                onClick = { viewModel.signInWithGoogle(context) },
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign up with Google") }

            Spacer(Modifier.height(12.dp))

            FilledTonalButton(
                onClick = { viewModel.signInWithGoogle(context) },
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign in with Google") }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = viewModel::continueAsGuest,
                enabled = !state.isWorking,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Continue as guest") }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Guest data stays on this phone only. You can sign up later to back it up.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.isWorking) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }

            state.error?.let { error ->
                Spacer(Modifier.height(24.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
