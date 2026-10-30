package dev.opensource.attenora.data

import dev.opensource.attenora.domain.HabitStreaks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/**
 * Everything the habits tab draws, already grouped by habit so the UI never has
 * to join four tables per frame.
 */
data class HabitBoard(
    val today: LocalDate,
    val habits: List<Habit>,
    val entries: Map<Long, Map<LocalDate, Int>>,
    val steps: Map<Long, List<HabitStep>>,
    /** Step ids already checked today, per habit. */
    val checksToday: Map<Long, Set<Long>>
) {
    fun habit(id: Long): Habit? = habits.firstOrNull { it.id == id }

    fun entriesFor(habitId: Long): Map<LocalDate, Int> = entries[habitId].orEmpty()

    fun keptFor(habitId: Long): Set<LocalDate> = HabitStreaks.keptDays(entriesFor(habitId))

    fun countFor(habitId: Long, day: LocalDate = today): Int =
        (entriesFor(habitId)[day] ?: 0).coerceAtLeast(0)

    fun stepsFor(habitId: Long): List<HabitStep> = steps[habitId].orEmpty()

    fun checkedCount(habitId: Long): Int {
        val checked = checksToday[habitId].orEmpty()
        return stepsFor(habitId).count { it.id in checked }
    }

    fun allTime(habitId: Long): Int =
        entriesFor(habitId).values.sumOf { it.coerceAtLeast(0) }

    fun recentTotal(habitId: Long, days: Int = 7): Int =
        HabitStreaks.recentTotal(entriesFor(habitId), today, days)

    /** Habits the schedule puts on today's plate. */
    fun dueToday(): List<Habit> =
        habits.filter { HabitStreaks.isScheduled(today, it.daysMask) }

    fun doneToday(): List<Habit> = dueToday().filter { countFor(it.id) > 0 }

    /** What the strip, the reminder and the notification are all about. */
    fun leftToday(): List<Habit> = dueToday().filter { countFor(it.id) <= 0 }

    /** True when there is nothing left to nag about (or nothing was due). */
    fun nothingLeftToday(): Boolean = leftToday().isEmpty()
}

/**
 * Which habit is nudged at which minute of the day.
 *
 * A habit that picked a time of its own owns that slot; a habit without one
 * follows the daily default from Settings. The two switches are independent:
 * turning the default off does not silence a habit that chose its own hour,
 * and a habit with no time at all gets no slot — so no alarm is spent on it.
 *
 * Pure on purpose: the alarm side only ever sees the finished map, which is
 * what makes "one alarm, next slot" testable without an AlarmManager.
 */
object HabitReminders {

    /** -1 (or anything outside the day) means "no time of its own". */
    fun effectiveMinute(habit: Habit, defaultMinute: Int): Int =
        habit.reminderMinute.takeIf { it in 0..1439 } ?: defaultMinute

    /** minute → the habits nudged then, in habit order. Sorted for stability. */
    fun slots(habits: List<Habit>, defaultMinute: Int): Map<Int, List<Habit>> =
        habits.groupBy { effectiveMinute(it, defaultMinute) }
            .filterKeys { it in 0..1439 }
            .toSortedMap()

    /** The unmarked habits that belong to one firing of one slot. */
    fun leftInSlot(left: List<Habit>, minute: Int, defaultMinute: Int): List<Habit> =
        left.filter { effectiveMinute(it, defaultMinute) == minute }
}

/**
 * Reads and writes the habits tab. Every rule about what a streak means lives
 * in [HabitStreaks]; this class only turns taps into rows.
 */
class HabitRepository(
    private val db: AppDatabase,
    private val todayKey: () -> String
) {

    /**
     * The whole tab as one flow. Anything that changes a habit, a mark, a step
     * or a step tick re-emits, so the list, the counters and the grid can never
     * disagree with each other.
     */
    fun observe(): Flow<HabitBoard> = combine(
        db.habitDao().observeAll(),
        db.habitEntryDao().observeAll(),
        db.habitStepDao().observeAll(),
        db.habitStepCheckDao().observeAll()
    ) { habits, entries, steps, checks ->
        val today = runCatching { LocalDate.parse(todayKey()) }.getOrElse { LocalDate.now() }
        val todayString = today.toString()

        val byHabitEntries = HashMap<Long, MutableMap<LocalDate, Int>>()
        for (entry in entries) {
            val day = runCatching { LocalDate.parse(entry.day) }.getOrNull() ?: continue
            byHabitEntries.getOrPut(entry.habitId) { HashMap() }[day] = entry.count
        }

        val stepsByHabit = steps.groupBy { it.habitId }
        val checkedToday = HashMap<Long, MutableSet<Long>>()
        val stepOwner = steps.associate { it.id to it.habitId }
        for (check in checks) {
            if (check.day != todayString) continue
            val habitId = stepOwner[check.stepId] ?: continue
            checkedToday.getOrPut(habitId) { HashSet() }.add(check.stepId)
        }

        HabitBoard(
            today = today,
            habits = habits,
            entries = byHabitEntries,
            steps = stepsByHabit,
            checksToday = checkedToday
        )
    }

    // ---- marks ---------------------------------------------------------------

    suspend fun setCount(habitId: Long, day: String, count: Int) {
        if (count <= 0) db.habitEntryDao().clear(habitId, day)
        else db.habitEntryDao().set(habitId, day, count.coerceAtMost(999))
    }

    suspend fun addCount(habitId: Long, day: String, delta: Int) {
        val current = db.habitEntryDao().countFor(habitId, day) ?: 0
        setCount(habitId, day, current + delta)
    }

    /** One tap on a check habit: done if it was empty, undone if it was marked. */
    suspend fun toggleDone(habitId: Long, day: String) {
        val current = db.habitEntryDao().countFor(habitId, day) ?: 0
        setCount(habitId, day, if (current > 0) 0 else 1)
    }

    // ---- routines ------------------------------------------------------------

    suspend fun toggleStep(habitId: Long, stepId: Long, day: String) {
        val checked = db.habitStepCheckDao().forDay(day).any { it.stepId == stepId }
        if (checked) db.habitStepCheckDao().uncheck(stepId, day)
        else db.habitStepCheckDao().check(HabitStepCheck(stepId, day))
        syncRoutine(habitId, day)
    }

    /**
     * A routine's day is done exactly when every step is ticked. Mirrored into
     * `habit_entries` so streaks, the tab list and the detail grid all read one
     * source of truth instead of special-casing routines everywhere.
     */
    private suspend fun syncRoutine(habitId: Long, day: String) {
        val steps = db.habitStepDao().forHabit(habitId)
        if (steps.isEmpty()) return
        val checked = db.habitStepCheckDao().forDay(day).map { it.stepId }.toSet()
        if (steps.all { it.id in checked }) db.habitEntryDao().set(habitId, day, 1)
        else db.habitEntryDao().clear(habitId, day)
    }

    // ---- habits --------------------------------------------------------------

    /** A step being written: [id] is null for a step that does not exist yet. */
    data class StepDraft(val id: Long?, val label: String)

    /**
     * Insert or update a habit and reconcile its steps in one pass. Steps keep
     * their ids across an edit, so renaming a routine step does not throw away
     * the ticks already recorded against it.
     */
    suspend fun saveHabit(habit: Habit, steps: List<StepDraft>): Long {
        val id = if (habit.id != 0L) {
            db.habitDao().upsert(habit)
            habit.id
        } else {
            db.habitDao().upsert(habit)
        }

        val existing = db.habitStepDao().forHabit(id)
        val keptIds = steps.mapNotNull { it.id }.toSet()
        for (stale in existing.filter { it.id !in keptIds }) {
            db.habitStepDao().delete(stale.id)
            db.habitStepCheckDao().clearForStep(stale.id)
        }
        steps.forEachIndexed { index, draft ->
            val known = draft.id != null && existing.any { it.id == draft.id }
            if (known) {
                db.habitStepDao().update(HabitStep(draft.id!!, id, draft.label, index))
            } else {
                db.habitStepDao().insert(HabitStep(habitId = id, label = draft.label, sortOrder = index))
            }
        }
        syncRoutine(id, todayKey())
        return id
    }

    suspend fun deleteHabit(id: Long) {
        for (step in db.habitStepDao().forHabit(id)) {
            db.habitStepCheckDao().clearForStep(step.id)
        }
        db.habitStepDao().deleteForHabit(id)
        db.habitEntryDao().clearForHabit(id)
        db.habitDao().delete(id)
    }

    /** Next free list position, so a new habit lands at the bottom. */
    suspend fun nextSortOrder(): Int =
        (db.habitDao().all().maxOfOrNull { it.sortOrder } ?: -1) + 1
}
