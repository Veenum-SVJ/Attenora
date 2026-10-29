package dev.opensource.attenora.ui.habits

import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opensource.attenora.data.Habit
import dev.opensource.attenora.data.HabitMode
import dev.opensource.attenora.ui.common.FgDivider
import dev.opensource.attenora.ui.common.FgGroup
import dev.opensource.attenora.ui.common.FgIcon
import dev.opensource.attenora.ui.common.LocalAccentColor
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.icons.FgIcons
import kotlinx.coroutines.launch

/** One step being edited. A null [id] is a step that does not exist yet. */
private data class StepDraftUi(val id: Long?, val label: String)

/**
 * Add or edit a habit. Deliberately four fields and a Save button — the habit
 * library, emoji and categories that other trackers offer are exactly the bulk
 * this feature was asked not to have.
 */
@Composable
fun HabitFormScreen(
    habitId: Long?,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit
) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    var name by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(HabitMode.CHECK) }
    var daysMask by rememberSaveable { mutableIntStateOf(0x7F) }
    var sortOrder by rememberSaveable { mutableIntStateOf(0) }
    var loaded by rememberSaveable { mutableStateOf(habitId == null) }
    var stepText by rememberSaveable { mutableStateOf("") }
    val steps = remember { mutableStateListOf<StepDraftUi>() }

    // Editing reads the stored habit once; everything after that is local.
    LaunchedEffect(habitId) {
        if (habitId == null) return@LaunchedEffect
        val habit = container.db.habitDao().get(habitId) ?: return@LaunchedEffect
        name = habit.name
        reason = habit.reason
        mode = habit.mode
        daysMask = habit.daysMask
        sortOrder = habit.sortOrder
        steps.clear()
        steps.addAll(
            container.db.habitStepDao().forHabit(habitId).map { StepDraftUi(it.id, it.label) }
        )
        loaded = true
    }

    val canSave = loaded && name.isNotBlank() && (mode != HabitMode.ROUTINE || steps.isNotEmpty())

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        item {
            PageHeader(
                title = if (habitId == null) "New habit" else "Edit habit",
                onBack = onBack
            )
        }

        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                label = { Text("What's the habit?") },
                placeholder = { Text("e.g. Read 20 minutes") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        item {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(140) },
                label = { Text("Why? (optional)") },
                placeholder = { Text("e.g. sharper evenings") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }

        item {
            Text(
                "How is it tracked?",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp)
            )
        }
        item {
            FgGroup(Modifier.padding(horizontal = 16.dp)) {
                ChoiceRow(
                    title = "Check once a day",
                    subtitle = "One tap marks it done. Tap again to undo.",
                    icon = FgIcons.CircleCheck,
                    selected = mode == HabitMode.CHECK,
                    onClick = { mode = HabitMode.CHECK }
                )
                FgDivider(inset = 56.dp)
                ChoiceRow(
                    title = "Count",
                    subtitle = "Tap to add a mark — reps, pages, cups of water.",
                    icon = FgIcons.Plus,
                    selected = mode == HabitMode.COUNT,
                    onClick = { mode = HabitMode.COUNT }
                )
                FgDivider(inset = 56.dp)
                ChoiceRow(
                    title = "Routine",
                    subtitle = "A set of steps in order. Done when every step is ticked.",
                    icon = FgIcons.Steps,
                    selected = mode == HabitMode.ROUTINE,
                    onClick = { mode = HabitMode.ROUTINE }
                )
            }
        }

        item {
            Text(
                "Days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp)
            )
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                WEEK_LETTERS.forEachIndexed { index, letter ->
                    val on = (daysMask shr index) and 1 == 1
                    DayChip(letter, on) {
                        val bit = 1 shl index
                        // Never leave a habit with no day: it would be invisible
                        // on the tab and silently never due.
                        val next = daysMask xor bit
                        if (next != 0) daysMask = next
                    }
                }
            }
        }

        if (mode == HabitMode.ROUTINE) {
            item {
                Text(
                    "Steps, in order",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 6.dp)
                )
            }
            item {
                FgGroup(Modifier.padding(horizontal = 16.dp)) {
                    steps.forEachIndexed { index, step ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp)
                            )
                            Text(
                                step.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.IconButton(onClick = { steps.removeAt(index) }) {
                                FgIcon(
                                    FgIcons.Close,
                                    "Remove step",
                                    size = 18.dp,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FgDivider(inset = 44.dp)
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = stepText,
                            onValueChange = { stepText = it.take(60) },
                            placeholder = { Text("Add a step") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = stepText.isNotBlank(),
                            onClick = {
                                steps.add(StepDraftUi(null, stepText.trim()))
                                stepText = ""
                            }
                        ) { Text("Add") }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(20.dp)) }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Cancel") }
                OutlinedButton(
                    enabled = canSave,
                    onClick = {
                        scope.launch {
                            val id = container.habits.saveHabit(
                                habit = Habit(
                                    id = habitId ?: 0,
                                    name = name.trim(),
                                    reason = reason.trim(),
                                    mode = mode,
                                    daysMask = daysMask,
                                    sortOrder = if (habitId == null) {
                                        container.habits.nextSortOrder()
                                    } else {
                                        sortOrder
                                    }
                                ),
                                steps = steps.map { dev.opensource.attenora.data.HabitRepository.StepDraft(it.id, it.label) }
                            )
                            onSaved(id)
                        }
                    }
                ) { Text(if (habitId == null) "Add habit" else "Save") }
            }
        }
    }
}

// ---- pieces ----------------------------------------------------------------

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val accent = LocalAccentColor.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FgIcon(
            icon,
            null,
            size = 22.dp,
            tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            FgIcon(FgIcons.Check, "Selected", size = 18.dp, tint = accent)
        }
    }
}

@Composable
private fun DayChip(letter: String, on: Boolean, onClick: () -> Unit) {
    val accent = LocalAccentColor.current
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (on) accent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
        )
    }
}

private val WEEK_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
