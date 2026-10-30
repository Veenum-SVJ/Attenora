package dev.opensource.attenora.domain

import java.time.LocalDate

/**
 * Streak arithmetic for habits.
 *
 * Kept pure and clockless on purpose: habits are the one part of the app whose
 * correctness is entirely about the calendar, so every rule here can be tested
 * against fixed dates instead of whatever day the test happens to run on.
 *
 * The rules are deliberately forgiving. A habit tracker that punishes a missed
 * day gets uninstalled, so a day counts only when it was both scheduled and
 * kept, and an unfinished today is never counted as a miss.
 */
object HabitStreaks {

    /** Guard against an unbounded walk on a corrupt date set. */
    private const val MAX_WALK_DAYS = 4000

    /** Bit 0 = Monday … bit 6 = Sunday, matching [dev.opensource.attenora.data.UsageLimit.daysMask]. */
    fun dayBit(date: LocalDate): Int = 1 shl (date.dayOfWeek.value - 1)

    fun isScheduled(date: LocalDate, daysMask: Int): Boolean =
        daysMask != 0 && (daysMask and dayBit(date)) != 0

    /** Days that count as kept: any entry with a mark on it. */
    fun keptDays(entries: Map<LocalDate, Int>): Set<LocalDate> =
        entries.filterValues { it > 0 }.keys

    /**
     * Consecutive kept scheduled days ending today (or yesterday, if today is
     * still open).
     *
     * Today is not held against you: an undone today is skipped rather than
     * treated as a miss, so the number does not collapse at 07:00 and rebuild
     * itself after breakfast.
     */
    fun currentStreak(kept: Set<LocalDate>, daysMask: Int, today: LocalDate): Int {
        if (daysMask == 0) return 0
        var day = today
        if (isScheduled(day, daysMask) && day !in kept) day = day.minusDays(1)
        var streak = 0
        var guard = 0
        while (guard++ < MAX_WALK_DAYS) {
            if (isScheduled(day, daysMask)) {
                if (day in kept) streak++ else return streak
            }
            day = day.minusDays(1)
        }
        return streak
    }

    /** Longest run ever, over the same scheduled-day rule. */
    fun bestStreak(kept: Set<LocalDate>, daysMask: Int, today: LocalDate): Int {
        if (daysMask == 0) return 0
        var run = 0
        var best = 0
        var previous: LocalDate? = null
        for (day in kept.filter { !it.isAfter(today) }.sorted()) {
            if (!isScheduled(day, daysMask)) continue
            val continues = previous?.let { consecutiveScheduled(it, day, daysMask) } ?: false
            run = if (continues) run + 1 else 1
            if (run > best) best = run
            previous = day
        }
        return best
    }

    /** True when no scheduled day sits between two kept days. */
    private fun consecutiveScheduled(from: LocalDate, to: LocalDate, daysMask: Int): Boolean {
        var day = from.plusDays(1)
        var guard = 0
        while (day.isBefore(to) && guard++ < MAX_WALK_DAYS) {
            if (isScheduled(day, daysMask)) return false
            day = day.plusDays(1)
        }
        return !day.isBefore(to)
    }

    /** The [days] dates ending at [today], oldest first. */
    fun window(today: LocalDate, days: Int = 14): List<LocalDate> =
        (0 until days).map { today.minusDays((days - 1 - it).toLong()) }

    /** Sum of marks over the [days] ending today, for counting habits. */
    fun recentTotal(entries: Map<LocalDate, Int>, today: LocalDate, days: Int = 7): Int =
        window(today, days).sumOf { (entries[it] ?: 0).coerceAtLeast(0) }
}

/**
 * The words for "what is left today".
 *
 * The strip on the habits tab, the daily reminder and the ongoing notification
 * all describe the same situation; keeping the phrasing in one place is what
 * stops them drifting into three slightly different sentences.
 */
object HabitCopy {

    /** Headline: "2 of 5 done today", or the settled states. */
    fun progressLine(done: Int, due: Int): String = when {
        due <= 0 -> "Nothing scheduled today"
        done >= due -> "All $due done today"
        else -> "$done of $due done today"
    }

    /** "Read, Water and 2 more" — never a wall of names. */
    fun condense(names: List<String>, max: Int = 2): String = when {
        names.isEmpty() -> ""
        names.size <= max -> if (names.size == 1) names.first()
        else names.dropLast(1).joinToString(", ") + " and " + names.last()
        else -> names.take(max).joinToString(", ") + " and ${names.size - max} more"
    }

    /** The second line of the strip; empty when there is nothing to nag about. */
    fun leftLine(names: List<String>): String =
        if (names.isEmpty()) "" else "${condense(names)} still open"

    /** Notification title for the daily nudge. */
    fun reminderTitle(left: Int): String =
        if (left == 1) "1 habit left today" else "$left habits left today"

    /** Notification body: one habit reads as a sentence, several as a list. */
    fun reminderBody(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> "${names.first()} is still unmarked today."
        else -> "${condense(names, max = 3)} are still unmarked today."
    }

    /** Ongoing notification line: "3 of 5 left · Read, Water". */
    fun statusLine(done: Int, due: Int, names: List<String>): String {
        val left = (due - done).coerceAtLeast(0)
        val head = when {
            left == 0 -> "All $due done today"
            left == 1 -> "1 left of $due"
            else -> "$left of $due left"
        }
        val tail = condense(names, max = 2)
        return if (tail.isEmpty() || left == 0) head else "$head · $tail"
    }
}
