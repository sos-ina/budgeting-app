package com.sosina.terefe.budgetingapp.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.sqrt

/** One piece of a chart: a label with its amount and share of the total. */
data class ChartEntry(
    val key: String,
    val label: String,
    val emoji: String,
    val color: Long,
    val value: Long,       // in cents
    val fraction: Float    // share of the total, 0 to 1
)

/** Plays a grow-in animation (0 -> 1) whenever the data changes. */
@Composable
private fun rememberGrowAnimation(key: Any): Float {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(durationMillis = 700))
    }
    return progress.value
}

/**
 * A ring chart. Each entry gets an arc sized by its share.
 * Anything placed in [center] is drawn in the hole in the middle.
 */
@Composable
fun DonutChart(
    entries: List<ChartEntry>,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp,
    thickness: Dp = 34.dp,
    center: @Composable BoxScope.() -> Unit = {}
) {
    val progress = rememberGrowAnimation(entries)

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = thickness.toPx()
            val diameter = this.size.minDimension - stroke
            val topLeft = Offset(
                (this.size.width - diameter) / 2f,
                (this.size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            // A small gap between slices, unless there's only one.
            val gap = if (entries.size > 1) 1.5f else 0f
            val drawnSoFar = 360f * progress   // how much of the ring is visible during the animation
            var startAngle = -90f              // start at the top

            entries.forEach { entry ->
                val sweep = entry.fraction * 360f
                val alreadyDrawn = startAngle + 90f
                val visible = minOf(sweep, drawnSoFar - alreadyDrawn) - gap
                if (visible > 0f) {
                    drawArc(
                        color = Color(entry.color),
                        startAngle = startAngle,
                        sweepAngle = visible,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke)
                    )
                }
                startAngle += sweep
            }
        }
        center()
    }
}

/**
 * Horizontal bars, one per entry, longest = biggest amount.
 * Easier to read than a donut when there are many labels.
 */
@Composable
fun HorizontalBarChart(
    entries: List<ChartEntry>,
    formatValue: (Long) -> String,
    modifier: Modifier = Modifier
) {
    val progress = rememberGrowAnimation(entries)
    val max = entries.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1L

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${entry.emoji}  ${entry.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatValue(entry.value),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // Track (background) with the colored bar on top.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(entry.value.toFloat() / max * progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(entry.color))
                    )
                }
            }
        }
    }
}

// ======================= Calendar heatmap =======================

/**
 * A calendar where each day is shaded by how much was spent.
 * Weeks run Monday to Sunday. Tapping a day shows its total below.
 */
@Composable
fun CalendarHeatmap(
    start: LocalDate,
    end: LocalDate,
    totals: Map<LocalDate, Long>,
    formatValue: (Long) -> String,
    modifier: Modifier = Modifier
) {
    var selected by androidx.compose.runtime.remember(start, end) { mutableStateOf<LocalDate?>(null) }
    val max = totals.values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val today = LocalDate.now()

    // Pad the grid to whole weeks.
    val gridStart = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val gridEnd = end.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val weeks = generateSequence(gridStart) { it.plusWeeks(1) }
        .takeWhile { !it.isAfter(gridEnd) }
        .map { weekStart -> (0L..6L).map { weekStart.plusDays(it) } }
        .toList()

    val primary = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Weekday letters
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { letter ->
                Text(
                    text = letter,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        weeks.forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { day ->
                    val inRange = !day.isBefore(start) && !day.isAfter(end)
                    val amount = totals[day] ?: 0L
                    // Square root spreads out the shades, so small days aren't all nearly blank.
                    val intensity = sqrt(amount.toFloat() / max)
                    val color = when {
                        !inRange -> Color.Transparent
                        amount == 0L -> empty
                        else -> primary.copy(alpha = 0.2f + 0.8f * intensity)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color)
                            .then(
                                if (day == selected) Modifier.border(2.dp, outline, RoundedCornerShape(6.dp))
                                else Modifier
                            )
                            .then(if (inRange) Modifier.clickable { selected = day } else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        if (inRange) {
                            Text(
                                text = day.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (intensity > 0.5f) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (day == today) androidx.compose.ui.text.font.FontWeight.Bold else null
                            )
                        }
                    }
                }
            }
        }

        // Details of the tapped day
        val day = selected
        Text(
            text = if (day == null) "Tap a day to see what you spent."
            else "${day.format(DAY_FORMAT)}: ${formatValue(totals[day] ?: 0L)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault())

// ======================= Cumulative line chart =======================

/**
 * Spending adding up day by day, compared with:
 * - the income for the range (dashed line), and
 * - an "even pace" line: spending the income evenly across every day (dotted).
 * Above the even-pace line means spending faster than the month allows.
 */
@Composable
fun CumulativeLineChart(
    start: LocalDate,
    end: LocalDate,
    totals: Map<LocalDate, Long>,
    income: Long,
    modifier: Modifier = Modifier
) {
    val progress = rememberGrowAnimation(totals)
    val dayCount = ChronoUnit.DAYS.between(start, end).toInt() + 1
    if (dayCount < 2) return

    // Running total for each day, stopping at today for ranges that include the future.
    val lastDay = minOf(end, LocalDate.now())
    val cumulative = mutableListOf<Long>()
    var running = 0L
    var day = start
    while (!day.isAfter(lastDay)) {
        running += totals[day] ?: 0L
        cumulative += running
        day = day.plusDays(1)
    }
    // A range that starts in the future has nothing to draw yet.
    if (cumulative.isEmpty()) return

    val maxY = (maxOf(income, running).takeIf { it > 0 } ?: 1L) * 1.1f

    val spentColor = MaterialTheme.colorScheme.primary
    val incomeColor = MaterialTheme.colorScheme.tertiary
    val paceColor = MaterialTheme.colorScheme.outline
    val gridColor = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            val w = size.width
            val h = size.height
            fun x(index: Int) = index.toFloat() / (dayCount - 1) * w
            fun y(value: Float) = h - value / maxY * h

            // Baseline
            drawLine(gridColor, Offset(0f, h), Offset(w, h), strokeWidth = 2f)

            if (income > 0) {
                // Income: dashed horizontal line
                drawLine(
                    color = incomeColor,
                    start = Offset(0f, y(income.toFloat())),
                    end = Offset(w, y(income.toFloat())),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
                )
                // Even pace: dotted diagonal from 0 to income
                drawLine(
                    color = paceColor,
                    start = Offset(0f, h),
                    end = Offset(w, y(income.toFloat())),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 12f))
                )
            }

            // Spending line, drawn from left to right as it animates in.
            val pointsToDraw = (cumulative.size * progress).toInt().coerceAtLeast(1)
            val path = Path()
            cumulative.take(pointsToDraw).forEachIndexed { i, value ->
                val point = Offset(x(i), y(value.toFloat()))
                if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            drawPath(path, color = spentColor, style = Stroke(width = 6f, cap = StrokeCap.Round))

            // A dot at the latest point
            val lastIndex = pointsToDraw - 1
            drawCircle(spentColor, radius = 9f, center = Offset(x(lastIndex), y(cumulative[lastIndex].toFloat())))
        }

        // Dates under the chart
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(start.format(SHORT_DATE), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(end.format(SHORT_DATE), style = MaterialTheme.typography.labelSmall)
        }

        // Legend
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LegendKey(spentColor, "Spent")
            if (income > 0) {
                LegendKey(incomeColor, "Income")
                LegendKey(paceColor, "Even pace")
            }
        }
    }
}

@Composable
private fun LegendKey(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 4.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            text = "  $label",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
