package com.sosina.terefe.budgetingapp.ui.mascot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.data.roast.Roast
import com.sosina.terefe.budgetingapp.data.roast.RoastManager
import com.sosina.terefe.budgetingapp.domain.model.MascotType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class RoastViewModel @Inject constructor(
    private val roastManager: RoastManager
) : ViewModel() {
    val roast: StateFlow<Roast?> = roastManager.pending
    fun dismiss() = roastManager.dismiss()
}

/** Shows the latest roast, if there is one. Slides in and out. */
@Composable
fun RoastCardHost(
    modifier: Modifier = Modifier,
    viewModel: RoastViewModel = hiltViewModel()
) {
    val roast by viewModel.roast.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = roast != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        // Keep showing the last roast while the "exit" animation plays.
        roast?.let { RoastCard(it, onDismiss = viewModel::dismiss) }
    }
}

@Composable
private fun RoastCard(roast: Roast, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (roast.mascot != MascotType.NONE) {
                AnimatedMascot(mascot = roast.mascot, mood = roast.mood, size = 56)
            } else {
                Text("🔥", fontSize = 32.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = roast.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("😅") }
        }
    }
}
