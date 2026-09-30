@file:OptIn(ExperimentalCoroutinesApi::class)

package com.sosina.terefe.budgetingapp.ui.mascot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.funstate.AchievementManager
import com.sosina.terefe.budgetingapp.data.funstate.FunStateRepository
import com.sosina.terefe.budgetingapp.data.repository.TransactionRepository
import com.sosina.terefe.budgetingapp.domain.streak.StreakCalculator
import com.sosina.terefe.budgetingapp.domain.streak.StreakInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class StreakViewModel @Inject constructor(
    transactionRepository: TransactionRepository,
    private val funState: FunStateRepository,
    private val celebrations: CelebrationManager,
    private val achievementManager: AchievementManager
) : ViewModel() {

    /** Refreshed whenever the dashboard is shown, so a new day is noticed. */
    private val today = MutableStateFlow(LocalDate.now())

    val streak: StateFlow<StreakInfo?> =
        combine(funState.firstUseDate.filterNotNull(), today) { start, day -> start to day }
            .flatMapLatest { (start, day) ->
                transactionRepository.observeSpendingByDay(start, day).map { spending ->
                    val spendDays = spending.filter { it.total > 0 }.map { it.date }.toSet()
                    StreakCalculator.calculate(spendDays, trackingStart = start, today = day)
                }
            }
            .onEach {
                achievementManager.onStreak(it.current)
                celebrateIfNewRecord(it)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { funState.ensureFirstUseDate() }
    }

    fun refresh() {
        today.value = LocalDate.now()
    }

    /** Confetti the first time each new best streak is reached. */
    private suspend fun celebrateIfNewRecord(info: StreakInfo) {
        if (info.current <= funState.bestCelebratedStreak()) return
        funState.setBestCelebratedStreak(info.current)
        val message = if (info.current == 1) "First no-spend day!"
        else "New record: ${info.current}-day no-spend streak!"
        celebrations.celebrate("🔥", message)
    }
}

/** A small card showing the current streak, with a nudge for today. */
@Composable
fun StreakBanner(
    modifier: Modifier = Modifier,
    viewModel: StreakViewModel = hiltViewModel()
) {
    val streak by viewModel.streak.collectAsStateWithLifecycle()

    // Every time the dashboard appears, check whether the day has changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val info = streak ?: return

    val (title, subtitle) = when {
        info.current == 0 && info.todayIsClean ->
            "No streak yet" to "Spend nothing today to start one!"
        info.current == 0 ->
            "No streak yet" to "Tomorrow is a fresh start."
        info.todayIsClean ->
            "${info.current}-day no-spend streak" to "Nothing spent today so far. Keep it going!"
        else ->
            "${info.current}-day no-spend streak" to "You spent today, so tomorrow starts a new count."
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (info.current > 0) "🔥" else "🌱", fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (info.longest > 0) {
                Text(
                    text = "Best: ${info.longest}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
