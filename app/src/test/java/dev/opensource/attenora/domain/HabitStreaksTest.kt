package dev.opensource.attenora.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Fixed dates, not "today": streak rules are entirely calendar arithmetic, and
 * a test that passes on a Tuesday and fails on a Monday is worse than no test.
 */
class HabitStreaksTest {

    private val monday = LocalDate.of(2026, 10, 26)
    private val everyDay = 0x7F
    private val monWedFri = (1 shl 0) or (1 shl 2) or (1 shl 4)

    @Test
    fun `the fixture week starts on Monday and the bits are Monday first`() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(1, HabitStreaks.dayBit(monday))
        assertEquals(1 shl 6, HabitStreaks.dayBit(monday.plusDays(6)))
    }

    @Test
    fun `a day that is not scheduled yet is not a miss`() {
        // Today is Thursday and nothing is marked for it: the chain that ends
        // yesterday is still the current streak.
        val kept = setOf(monday, monday.plusDays(1), monday.plusDays(2))
        assertEquals(3, HabitStreaks.currentStreak(kept, everyDay, monday.plusDays(3)))
    }

    @Test
    fun `a missed day ends the run`() {
        val kept = setOf(monday, monday.plusDays(1), monday.plusDays(3))
        assertEquals(1, HabitStreaks.currentStreak(kept, everyDay, monday.plusDays(3)))
    }

    @Test
    fun `non scheduled days neither add to nor break a weekday habit`() {
        // Mon and Wed kept, today is Friday: Tue and Thu do not exist for this
        // habit, so the run is 2 rather than 0.
        val kept = setOf(monday, monday.plusDays(2))
        assertEquals(2, HabitStreaks.currentStreak(kept, monWedFri, monday.plusDays(4)))
    }

    @Test
    fun `a missed scheduled day breaks a weekday habit`() {
        // Only Monday kept, Wednesday skipped, today Friday.
        val kept = setOf(monday)
        assertEquals(0, HabitStreaks.currentStreak(kept, monWedFri, monday.plusDays(4)))
    }

    @Test
    fun `the best streak survives a broken current one`() {
        val kept = setOf(
            monday, monday.plusDays(1), monday.plusDays(2), // a run of 3
            monday.plusDays(7), monday.plusDays(8)          // then a run of 2
        )
        val today = monday.plusDays(8)
        assertEquals(3, HabitStreaks.bestStreak(kept, everyDay, today))
        assertEquals(2, HabitStreaks.currentStreak(kept, everyDay, today))
    }

    @Test
    fun `future marks do not inflate the best streak`() {
        val kept = setOf(monday, monday.plusDays(1), monday.plusDays(5))
        assertEquals(2, HabitStreaks.bestStreak(kept, everyDay, monday.plusDays(1)))
    }

    @Test
    fun `the fortnight ends today and runs oldest first`() {
        val today = monday.plusDays(20)
        val window = HabitStreaks.window(today, 14)
        assertEquals(14, window.size)
        assertEquals(monday.plusDays(7), window.first())
        assertEquals(today, window.last())
    }

    @Test
    fun `the weekly total only counts the last seven days`() {
        val today = monday.plusDays(9)
        val entries = mapOf(
            monday to 99,                       // nine days ago: outside the window
            today.minusDays(1) to 2,
            today to 3,
            today.plusDays(1) to 50             // future rows are not today's total
        )
        assertEquals(5, HabitStreaks.recentTotal(entries, today))
    }

    @Test
    fun `a zero row is not a kept day`() {
        val kept = HabitStreaks.keptDays(mapOf(monday to 1, monday.plusDays(1) to 0))
        assertEquals(setOf(monday), kept)
    }

    @Test
    fun `a habit with no scheduled days has no streak`() {
        val kept = setOf(monday, monday.plusDays(1))
        assertEquals(0, HabitStreaks.currentStreak(kept, 0, monday.plusDays(1)))
        assertEquals(0, HabitStreaks.bestStreak(kept, 0, monday.plusDays(1)))
        assertFalse(HabitStreaks.isScheduled(monday, 0))
    }

    @Test
    fun `every day mask schedules all seven days`() {
        for (offset in 0L..6L) {
            assertTrue(HabitStreaks.isScheduled(monday.plusDays(offset), everyDay))
        }
    }
}
