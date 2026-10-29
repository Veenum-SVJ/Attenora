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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.daysSummary
import dev.opensource.attenora.ui.icons.FgIcons
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Fourth tab: the habits themselves.
 *
 * One list, one control per row. Everything the tracker knows about a habit
 * lives on its own page; this screen only answers "what is left today", which
 * is the only question a daily list is allowed to be good at.
 */
@Composable
fun HabitsScreen(
    onOpenHabit: (Long) -> Unit,
    onAddHabit: () -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val boardFlow = remember { container.habits.observe() }
    val board by boardFlow.collectAsState(initial = emptyBoard())

    val habits = board.habits
    val dueToday = habits.filter { HabitStreaks.isScheduled(board.today, it.daysMask) }
    val doneToday = dueToday.count { board.countFor(it.id) > 0 }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp)
        ) {
            item { PageHeader("Habits") }

            item {
                Text(
                    when {
                        habits.isEmpty() -> "Nothing tracked yet"
                        dueToday.isEmpty() -> "Nothing scheduled today"
                        else -> "$doneToday of ${dueToday.size} done today"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            if (habits.isEmpty()) {
                item {
                    FgGroup(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text("No habits yet.", style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Add something you want to keep doing — one tap a day is enough.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                item {
                    FgGroup(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        habits.forEachIndexed { index, habit ->
                            HabitRow(
                                habit = habit,
                                board = board,
                                onOpen = { onOpenHabit(habit.id) },
                                onToggleCheck = {
                                    scope.launch {
                                        container.habits.toggleDone(habit.id, board.today.toString())
                                    }
                                },
                                onAddCount = {
                                    scope.launch {
                                        container.habits.addCount(habit.id, board.today.toString(), 1)
                                    }
                                }
                            )
                            if (index != habits.lastIndex) {
                                FgDivider(inset = 20.dp)
                            }
                        }
                    }
                }
            }
        }

        // Same shape and position as the limits FAB, so "+ adds a thing" reads
        // the same way in every tab.
        Box(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onAddHabit),
                contentAlignment = Alignment.Center
            ) {
                FgIcon(FgIcons.Plus, "Add a habit", size = 26.dp)
            }
        }
    }
}

@Composable
private fun HabitRow(
    habit: Habit,
    board: HabitBoard,
    onOpen: () -> Unit,
    onToggleCheck: () -> Unit,
    onAddCount: () -> Unit
) {
    val kept = board.keptFor(habit.id)
    val streak = HabitStreaks.currentStreak(kept, habit.daysMask, board.today)
    val count = board.countFor(habit.id)

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                habit.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (count > 0) FontWeight.Medium else FontWeight.Normal
            )
            Text(
                secondaryLine(habit, board, streak),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        when (habit.mode) {
            HabitMode.COUNT -> CountButton(count, onAddCount)
            HabitMode.ROUTINE -> FgIcon(
                FgIcons.ChevronRight,
                null,
                size = 18.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            else -> CheckRing(done = count > 0, onClick = onToggleCheck)
        }
    }
}

/**
 * The quiet second line. Streak first when there is one, because that is what
 * people come back for; the schedule only when it is not "every day", so the
 * common case stays silent.
 */
private fun secondaryLine(habit: Habit, board: HabitBoard, streak: Int): String {
    val parts = ArrayList<String>(2)
    when (habit.mode) {
        HabitMode.ROUTINE -> {
            val total = board.stepsFor(habit.id).size
            parts += if (total == 0) "No steps yet" else "${board.checkedCount(habit.id)} of $total steps"
        }
        HabitMode.COUNT -> parts += if (board.countFor(habit.id) == 1) "1 today" else "${board.countFor(habit.id)} today"
        else -> Unit
    }
    if (habit.mode != HabitMode.COUNT) {
        when {
            streak > 0 -> parts += "$streak day streak"
            habit.daysMask != 0x7F -> parts += daysSummary(habit.daysMask)
            else -> parts += if (board.countFor(habit.id) > 0) "Done today" else "Every day"
        }
    } else if (habit.daysMask != 0x7F) {
        parts += daysSummary(habit.daysMask)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun CheckRing(done: Boolean, onClick: () -> Unit) {
    val accent = LocalAccentColor.current
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (done) accent else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (done) accent else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
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

@Composable
private fun CountButton(count: Int, onClick: () -> Unit) {
    val accent = LocalAccentColor.current
    Row(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        FgIcon(
            FgIcons.Plus,
            "Add one",
            size = 18.dp,
            tint = if (count > 0) accent else MaterialTheme.colorScheme.onSurface
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Placeholder board for the first frame, before Room has answered. */
private fun emptyBoard() = HabitBoard(
    today = LocalDate.now(),
    habits = emptyList(),
    entries = emptyMap(),
    steps = emptyMap(),
    checksToday = emptyMap()
)
