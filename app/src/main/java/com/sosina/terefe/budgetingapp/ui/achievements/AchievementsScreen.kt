package com.sosina.terefe.budgetingapp.ui.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.funstate.FunStateRepository
import com.sosina.terefe.budgetingapp.domain.achievements.Achievement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/** One badge, with when it was earned (or null) and progress for counted ones. */
data class AchievementRow(
    val achievement: Achievement,
    val earnedOn: LocalDate?,
    val progress: Int?          // current count, only for counter achievements
) {
    val isEarned: Boolean get() = earnedOn != null
}

data class AchievementsUiState(
    val rows: List<AchievementRow> = emptyList()
) {
    val earnedCount: Int get() = rows.count { it.isEarned }
    val total: Int get() = rows.size
}

@HiltViewModel
class AchievementsViewModel @Inject constructor(
    funState: FunStateRepository
) : ViewModel() {

    val uiState: StateFlow<AchievementsUiState> =
        combine(funState.unlocked, funState.counts) { unlocked, counts ->
            val rows = Achievement.entries.map { a ->
                AchievementRow(
                    achievement = a,
                    earnedOn = unlocked[a.name],
                    progress = a.counter?.let { counts[it.name] ?: 0 }
                )
            }
            // Earned badges first (newest first), then locked ones in their usual order.
            AchievementsUiState(
                rows = rows.filter { it.isEarned }.sortedByDescending { it.earnedOn } +
                    rows.filter { !it.isEarned }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AchievementsUiState())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    onBack: () -> Unit,
    viewModel: AchievementsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Achievements") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SummaryCard(earned = state.earnedCount, total = state.total) }
            items(state.rows, key = { it.achievement.name }) { row -> AchievementCard(row) }
        }
    }
}

@Composable
private fun SummaryCard(earned: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏅", fontSize = 36.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "$earned of $total earned",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            earned == 0 -> "Your trophy case is empty. For now."
                            earned == total -> "You got them all. Legend."
                            else -> "Keep going, there's more to unlock!"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { if (total > 0) earned.toFloat() / total else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun AchievementCard(row: AchievementRow) {
    val a = row.achievement

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Locked badges are faded.
            Text(
                text = a.emoji,
                fontSize = 36.sp,
                modifier = Modifier.alpha(if (row.isEarned) 1f else 0.3f)
            )
            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = a.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alpha(if (row.isEarned) 1f else 0.6f)
                )
                Text(
                    text = a.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                val earnedOn = row.earnedOn
                val progress = row.progress
                when {
                    earnedOn != null -> Text(
                        text = "✓ Earned ${earnedOn.format(EARNED_FORMAT)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Counted achievements show how close you are.
                    progress != null -> {
                        LinearProgressIndicator(
                            progress = { (progress.toFloat() / a.target).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${progress.coerceAtMost(a.target)} / ${a.target}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> Text(
                        text = "🔒 Locked",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private val EARNED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
