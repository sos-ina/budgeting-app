package com.sosina.terefe.budgetingapp.ui.mascot

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.sin
import kotlin.random.Random

private val CONFETTI_COLORS = listOf(
    Color(0xFFE57373), Color(0xFFFFD54F), Color(0xFF81C784), Color(0xFF64B5F6),
    Color(0xFFBA68C8), Color(0xFFFF8A65), Color(0xFF4DD0E1), Color(0xFFF06292)
)

/** One piece of confetti. Positions are fractions of the screen (0 to 1). */
private data class Piece(
    val startX: Float,
    val delay: Float,       // when it starts falling, as a share of the animation
    val speed: Float,       // how far down it gets
    val drift: Float,       // sideways movement
    val wobblePhase: Float,
    val spins: Float,       // full turns while falling
    val size: Float,        // in dp
    val color: Color,
    val isRound: Boolean
)

/**
 * Plays a confetti shower each time [trigger] changes to a new number above 0.
 * Put it on top of a screen inside a Box; it doesn't block taps.
 */
@Composable
fun ConfettiBurst(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger <= 0) return

    val pieces = remember(trigger) {
        List(90) {
            Piece(
                startX = Random.nextFloat(),
                delay = Random.nextFloat() * 0.3f,
                speed = 0.8f + Random.nextFloat() * 0.5f,
                drift = (Random.nextFloat() - 0.5f) * 0.3f,
                wobblePhase = Random.nextFloat() * 6.28f,
                spins = 1f + Random.nextFloat() * 3f,
                size = 6f + Random.nextFloat() * 6f,
                color = CONFETTI_COLORS.random(),
                isRound = Random.nextInt(4) == 0
            )
        }
    }

    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = 2800, easing = LinearEasing))
    }
    if (progress.value >= 1f) return // finished: draw nothing

    Canvas(modifier = modifier.fillMaxSize()) {
        pieces.forEach { p ->
            // This piece's own progress, from 0 (just started) to 1 (done).
            val t = ((progress.value - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
            if (t <= 0f) return@forEach

            val x = (p.startX + p.drift * t + sin(t * 12f + p.wobblePhase) * 0.02f) * size.width
            val y = (-0.05f + t * p.speed * 1.1f) * size.height
            val alpha = if (t > 0.85f) (1f - t) / 0.15f else 1f   // fade out at the end
            val pieceSize = p.size * density
            val color = p.color.copy(alpha = alpha)

            if (p.isRound) {
                drawCircle(color = color, radius = pieceSize / 2f, center = Offset(x, y))
            } else {
                rotate(degrees = t * 360f * p.spins, pivot = Offset(x, y)) {
                    drawRect(
                        color = color,
                        topLeft = Offset(x - pieceSize / 2f, y - pieceSize / 4f),
                        size = Size(pieceSize, pieceSize / 2f)
                    )
                }
            }
        }
    }
}
