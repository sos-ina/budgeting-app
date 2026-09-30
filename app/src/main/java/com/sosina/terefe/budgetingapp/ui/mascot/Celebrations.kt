package com.sosina.terefe.budgetingapp.ui.mascot

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sosina.terefe.budgetingapp.data.sound.Sound
import com.sosina.terefe.budgetingapp.data.sound.SoundPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Something worth confetti. Each one gets a new id, which replays the animation. */
data class Celebration(val id: Int, val emoji: String, val message: String)

/** Anything in the app can call celebrate(); the dashboard shows it. */
@Singleton
class CelebrationManager @Inject constructor(
    private val soundPlayer: SoundPlayer
) {
    private var nextId = 1
    private val _current = MutableStateFlow<Celebration?>(null)
    val current: StateFlow<Celebration?> = _current.asStateFlow()

    @Synchronized
    fun celebrate(emoji: String, message: String) {
        _current.value = Celebration(nextId++, emoji, message)
        soundPlayer.play(Sound.FANFARE)
    }

    fun dismiss() {
        _current.value = null
    }
}

@HiltViewModel
class CelebrationViewModel @Inject constructor(
    private val manager: CelebrationManager
) : ViewModel() {
    val current: StateFlow<Celebration?> = manager.current
    fun dismiss() = manager.dismiss()
}

/**
 * Confetti across the whole screen, plus a message card near the top.
 * Place it last inside a full-screen Box so it's drawn on top.
 */
@Composable
fun ConfettiHost(viewModel: CelebrationViewModel = hiltViewModel()) {
    val celebration by viewModel.current.collectAsStateWithLifecycle()

    // Hide the message after a few seconds (a little longer than the confetti).
    LaunchedEffect(celebration?.id) {
        if (celebration != null) {
            delay(3_500)
            viewModel.dismiss()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ConfettiBurst(trigger = celebration?.id ?: 0)

        AnimatedVisibility(
            visible = celebration != null,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp, start = 24.dp, end = 24.dp)
        ) {
            celebration?.let { c ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(c.emoji, fontSize = 40.sp)
                        Text(
                            text = c.message,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
