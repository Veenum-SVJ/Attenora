package dev.opensource.attenora.ui.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opensource.attenora.data.Habit
import dev.opensource.attenora.data.HabitBoard
import dev.opensource.attenora.data.HabitMode
import dev.opensource.attenora.domain.HabitStreaks
import dev.opensource.attenora.ui.common.FgDivider
import dev.opensource.attenora.ui.common.FgGroup
import dev.opensource.attenora.ui.common.FgIcon
import dev.opensource.attenora.ui.common.LocalAccentColor
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.ui.common.NavRow
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.daysSummary
import dev.opensource.attenora.ui.icons.FgIcons
import dev.opensource.attenora.ui.theme.fg
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One habit's own page: how long the current run is, the last fortnight, and
 * the controls that only make sense here (a routine's steps, a counter's
 * undo). The list stays a list; this is where the detail lives.
 */
@Composable
fun HabitDetailScreen(
    habitId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val boardFlow = remember { container.habits.observe() }
    val board by boardFlow.collectAsState(initial = emptyBoard())

    val habit = board.habit(habitId)
    var confirmDelete by remember { mutableStateOf(false) }

    if (habit == null) {
        // Deleted while open (or a stale route after a backup import).
        Column(Modifier.fillMaxSize()) {
            PageHeader("Habit", onBack = onBack)
            Text(
                "This habit is no longer here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        }
        return
    }

    val kept = board.keptFor(habit.id)
    val streak = HabitStreaks.currentStreak(kept, habit.daysMask, board.today)
    val best = HabitStreaks.bestStreak(kept, habit.daysMask, board.today)
    val count = board.countFor(habit.id)
    val accent = LocalAccentColor.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        item {
            PageHeader(
                title = habit.name,
                onBack = onBack,
                subtitle = "Since " + formatSince(habit.createdAtMs)
            )
        }

        if (habit.reason.isNotBlank()) {
            item {
                Text(
                    habit.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }
        }

        // ---- numbers -----------------------------------------------------
        item {
            FgGroup(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (habit.mode == HabitMode.COUNT) {
                    StatRow("Today", "$count")
                    FgDivider(inset = 20.dp)
                    StatRow("This week", "${board.recentTotal(habit.id, 7)}")
                    FgDivider(inset = 20.dp)
                    StatRow("All time", "${board.allTime(habit.id)}")
                } else {
                    StatRow("Current streak", days(streak))
                    FgDivider(inset = 20.dp)
                    StatRow("Best streak", days(best))
                    if (habit.daysMask != 0x7F) {
                        FgDivider(inset = 20.dp)
                        StatRow("Days", daysSummary(habit.daysMask))
                    }
                }
            }
        }

        // ---- today's control ---------------------------------------------
        // Count habits get the counter below and routines get their step list;
        // a check habit needs a control here, otherwise its only way to mark
        // today is hunting for the last square in the grid.
        if (habit.mode == HabitMode.CHECK) {
            item {
                FgGroup(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    container.habits.toggleDone(habit.id, board.today.toString())
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (count > 0) "Done today" else "Mark today",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (count > 0) accent else Color.Transparent)
                                .border(
                                    width = 1.5.dp,
                                    color = if (count > 0) accent else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (count > 0) {
                                FgIcon(
                                    FgIcons.Check,
                                    "Done",
                                    size = 16.dp,
                                    tint = MaterialTheme.colorScheme.background
                                )
                            }
                        }
                    }
                }
            }
        }

        if (habit.mode == HabitMode.COUNT) {
            item {
                FgGroup(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Mark one today",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        StepButton(FgIcons.Minus, "Remove one") {
                            scope.launch {
                                container.habits.addCount(habit.id, board.today.toString(), -1)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        StepButton(FgIcons.Plus, "Add one") {
                            scope.launch {
                                container.habits.addCount(habit.id, board.today.toString(), 1)
                            }
                        }
                    }
                }
            }
        }

        // ---- routine steps ------------------------------------------------
        if (habit.mode == HabitMode.ROUTINE) {
            item {
                Text(
                    "Steps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp)
                )
            }
            item {
                val steps = board.stepsFor(habit.id)
                if (steps.isEmpty()) {
                    FgGroup(Modifier.padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("No steps yet.", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Edit this routine to add the steps you work through in order.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val checked = board.checksToday[habit.id].orEmpty()
                    FgGroup(Modifier.padding(horizontal = 16.dp)) {
                        steps.forEachIndexed { index, step ->
                            val isChecked = step.id in checked
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        scope.launch {
                                            container.habits.toggleStep(
                                                habit.id, step.id, board.today.toString()
                                            )
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StepRing(isChecked)
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    step.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (index != steps.lastIndex) FgDivider(inset = 54.dp)
                        }
                    }
                }
            }
        }

        // ---- last fortnight ------------------------------------------------
        item {
            FgGroup(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
                    Text(
                        "Last 14 days",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 10.dp)
                    )
                    HabitGrid(habit, board) { day ->
                        scope.launch {
                            container.habits.toggleDone(habit.id, day.toString())
                        }
                    }
                }
            }
        }
        if (habit.mode == HabitMode.CHECK) {
            item {
                Text(
                    "Tap an earlier day to fix a missed tick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp)
                )
            }
        }

        // ---- manage --------------------------------------------------------
        item {
            FgGroup(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                NavRow("Edit habit", onClick = { onEdit(habit.id) })
                FgDivider(inset = 20.dp)
                NavRow(
                    "Delete habit",
                    onClick = { confirmDelete = true },
                    valueColor = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this habit?") },
            text = {
                Text(
                    "\"${habit.name}\" and every day it recorded will be removed from this " +
                        "device. There is no copy anywhere else."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        container.habits.deleteHabit(habit.id)
                        onBack()
                    }
                }) { Text("Delete", color = MaterialTheme.fg.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

// ---- pieces ----------------------------------------------------------------

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Two weeks, seven to a row: a fortnight is short enough to read at a glance. */
@Composable
private fun HabitGrid(habit: Habit, board: HabitBoard, onToggleDay: (LocalDate) -> Unit) {
    val days = HabitStreaks.window(board.today, 14)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        days.chunked(7).forEach { week ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                week.forEach { day ->
                    DayCell(habit, board, day, onToggleDay, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    habit: Habit,
    board: HabitBoard,
    day: LocalDate,
    onToggleDay: (LocalDate) -> Unit,
    modifier: Modifier
) {
    val accent = LocalAccentColor.current
    val kept = (board.entriesFor(habit.id)[day] ?: 0) > 0
    val scheduled = HabitStreaks.isScheduled(day, habit.daysMask)
    val isFuture = day.isAfter(board.today)
    val tappable = habit.mode == HabitMode.CHECK && !isFuture

    val fill = when {
        kept -> accent
        !scheduled -> Color.Transparent
        isFuture -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            WEEK_LETTERS[day.dayOfWeek.value - 1],
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (scheduled) 1f else 0.4f)
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(fill)
                .then(if (tappable) Modifier.clickable { onToggleDay(day) } else Modifier)
        )
    }
}

@Composable
private fun StepRing(checked: Boolean) {
    val accent = LocalAccentColor.current
    Box(
        Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(if (checked) accent else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            FgIcon(FgIcons.Check, null, size = 13.dp, tint = MaterialTheme.colorScheme.background)
        }
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FgIcon(icon, description, size = 18.dp)
    }
}

private val WEEK_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")

private fun days(n: Int): String = if (n == 1) "1 day" else "$n days"

private fun formatSince(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

private fun emptyBoard() = HabitBoard(
    today = LocalDate.now(),
    habits = emptyList(),
    entries = emptyMap(),
    steps = emptyMap(),
    checksToday = emptyMap()
)
