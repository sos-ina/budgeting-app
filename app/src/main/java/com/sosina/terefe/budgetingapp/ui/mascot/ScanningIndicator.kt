package com.sosina.terefe.budgetingapp.ui.mascot

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sosina.terefe.budgetingapp.data.settings.SettingsRepository
import com.sosina.terefe.budgetingapp.domain.mascot.Mascot
import com.sosina.terefe.budgetingapp.domain.model.MascotType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Just tells the indicator which mascot the user picked. */
@HiltViewModel
class MascotPreferenceViewModel @Inject constructor(
    settingsRepository: SettingsRepository
) : ViewModel() {
    val mascot: StateFlow<MascotType> = settingsRepository.settings
        .map { it.mascot }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MascotType.NONE)
}

/**
 * Shown while a receipt is being read.
 * With a mascot: the mascot eats the receipt. Without: a simple spinner.
 */
@Composable
fun ScanningIndicator(
    modifier: Modifier = Modifier,
    viewModel: MascotPreferenceViewModel = hiltViewModel()
) {
    val mascot by viewModel.mascot.collectAsStateWithLifecycle()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (mascot == MascotType.NONE) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(12.dp))
                Text("Reading your receipt…", style = MaterialTheme.typography.bodyLarge)
            } else {
                ReceiptEatingAnimation(mascot)
                Spacer(Modifier.width(12.dp))
                Text(eatingMessage(mascot), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * One loop (1.4 seconds):
 * - first half: the receipt slides over to the mascot
 * - second half: it shrinks away while the mascot eats in its own style
 */
@Composable
private fun ReceiptEatingAnimation(mascot: MascotType) {
    val transition = rememberInfiniteTransition(label = "eating")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "progress"
    )

    val eatStart = 0.55f
    val isEating = progress > eatStart
    val approach = (progress / eatStart).coerceIn(0f, 1f)                 // 0 -> 1 while sliding over
    val swallowed = ((progress - eatStart) / 0.25f).coerceIn(0f, 1f)      // 0 -> 1 while disappearing

    // How fast each animal chews (bites per loop).
    val bites = when (mascot) {
        MascotType.PIGEON -> 6f
        MascotType.DOG -> 4f
        MascotType.HORSE, MascotType.CAT -> 3f
        MascotType.DONKEY -> 1.5f
        MascotType.NONE -> 0f
    }
    // A wave going back and forth while eating, 0 when not eating.
    val wave = if (isEating) sin((progress - eatStart) / (1f - eatStart) * bites * 2 * PI).toFloat() else 0f
    val chew = abs(wave)

    val useFallback = mascot == MascotType.DONKEY && !donkeySupported
    val emoji = if (useFallback) "🐴" else Mascot.emoji(mascot)

    Box(modifier = Modifier.width(110.dp).height(64.dp)) {
        // ---------- The receipt ----------
        if (swallowed < 1f) {
            Text(
                text = "🧾",
                fontSize = 26.sp,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .graphicsLayer {
                        translationX = approach * 50f * density
                        val shrink = 1f - swallowed
                        scaleX = shrink
                        scaleY = shrink
                        alpha = shrink
                        // The cat bats it around on the way.
                        if (mascot == MascotType.CAT && !isEating) {
                            rotationZ = sin(progress * 6 * PI).toFloat() * 25f
                        }
                    }
            )
        }

        // ---------- The mascot, eating its way ----------
        Text(
            text = emoji,
            fontSize = 40.sp,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .graphicsLayer {
                    when (mascot) {
                        // Quick pecks downward.
                        MascotType.PIGEON -> translationY = chew * 6f * density
                        // Big munching squash and stretch.
                        MascotType.HORSE -> {
                            scaleY = 1f - chew * 0.10f
                            scaleX = 1f + chew * 0.05f
                        }
                        // Same, but slow and grumpy.
                        MascotType.DONKEY -> {
                            scaleY = 1f - chew * 0.08f
                            scaleX = 1f + chew * 0.04f
                        }
                        // A little paw swipe.
                        MascotType.CAT -> rotationZ = -chew * 12f
                        // Tugging side to side.
                        MascotType.DOG -> rotationZ = wave * 12f
                        MascotType.NONE -> Unit
                    }
                    if (useFallback) alpha = 0.75f
                }
        )

        // ---------- "nom" ----------
        if (isEating) {
            Text(
                text = "nom",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer { alpha = chew }
            )
        }
    }
}

private fun eatingMessage(mascot: MascotType): String = when (mascot) {
    MascotType.PIGEON -> "Pecking at your receipt… crumb by crumb."
    MascotType.HORSE -> "Munching your receipt… delicious numbers."
    MascotType.DONKEY -> "Chewing your receipt. Slowly. Don't rush me."
    MascotType.CAT -> "Batting your receipt around… I'll read it eventually."
    MascotType.DOG -> "Chewing your receipt! Good receipt! Reading it now!"
    MascotType.NONE -> "Reading your receipt…"
}

/** Same check as MascotView: the donkey emoji needs Android 14 or newer. */
private val donkeySupported: Boolean by lazy { Paint().hasGlyph("🫏") }
