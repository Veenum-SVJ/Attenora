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
