package com.sosina.terefe.budgetingapp.ui.mascot

import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sosina.terefe.budgetingapp.domain.mascot.Mascot
import com.sosina.terefe.budgetingapp.domain.mascot.MascotMood
import com.sosina.terefe.budgetingapp.domain.model.MascotType
import java.time.LocalDate

/** The donkey emoji only exists on newer phones (Android 14+); older ones show a box instead. */
private val donkeyEmojiSupported: Boolean by lazy { Paint().hasGlyph("🫏") }

/**
 * The mascot with its current mood, plus a speech bubble.
 * Tapping the mascot makes it say something else.
 */
@Composable
fun MascotView(
    mascot: MascotType,
    mood: MascotMood,
    modifier: Modifier = Modifier
) {
    if (mascot == MascotType.NONE) return

    val lines = Mascot.lines(mascot, mood)
    // Start on a line that changes daily, then move on with each tap.
    var tapCount by remember(mascot, mood) { mutableIntStateOf(0) }
    val lineIndex = ((LocalDate.now().toEpochDay() + tapCount) % lines.size).toInt()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${Mascot.name(mascot)} mascot, ${Mascot.moodDescription(mood)}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedMascot(
            mascot = mascot,
            mood = mood,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null // no grey ripple on the animal
            ) { tapCount++ }
        )

        Card(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.weight(1f)
        ) {
            // A gentle fade whenever the line changes.
            AnimatedContent(
                targetState = lines.getOrElse(lineIndex) { "" },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "speech"
            ) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

/** The animal emoji, animated according to its mood. */
@Composable
fun AnimatedMascot(
    mascot: MascotType,
    mood: MascotMood,
    modifier: Modifier = Modifier,
    size: Int = 80
) {
    val transition = rememberInfiniteTransition(label = "mascot")

    // Each mood uses a different movement.
    val bounce by transition.animateFloat(
        initialValue = 0f, targetValue = -10f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "bounce"
    )
    val wobble by transition.animateFloat(
        initialValue = -6f, targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wobble"
    )
    val shake by transition.animateFloat(
        initialValue = -3f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(70), RepeatMode.Reverse),
        label = "shake"
    )
    val fall by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "fall"
    )
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val twinkle by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "twinkle"
    )

    // Older phones without the donkey emoji get a grey-tinted horse instead.
    val useDonkeyFallback = mascot == MascotType.DONKEY && !donkeyEmojiSupported
    val emoji = if (useDonkeyFallback) "🐴" else Mascot.emoji(mascot)

    Box(modifier = modifier.size(size.dp), contentAlignment = Alignment.Center) {
        // ---------- The animal ----------
        Text(
            text = emoji,
            fontSize = (size * 0.62f).sp,
            modifier = Modifier.graphicsLayer {
                when (mood) {
                    MascotMood.HAPPY -> translationY = bounce * density
                    MascotMood.WORRIED -> rotationZ = wobble
                    MascotMood.SWEATING -> translationX = shake * density
                    MascotMood.FAINTED -> {
                        rotationZ = 90f                       // lying on its side
                        translationY = 10f * density
                    }
                }
                if (useDonkeyFallback) alpha = 0.75f
            }
        )

        // ---------- Mood extras ----------
        when (mood) {
            MascotMood.HAPPY -> Text(
                text = "✨",
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopEnd).alpha(twinkle)
            )

            MascotMood.WORRIED -> Text(
                text = "❔",
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopEnd)
            )

            MascotMood.SWEATING -> Text(
                text = "💦",
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (fall * 22).dp)     // drops fall...
                    .alpha(1f - fall)               // ...and fade away
            )

            MascotMood.FAINTED -> Text(
                text = "💫",
                fontSize = 20.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { rotationZ = spin }
            )
        }
    }
}
