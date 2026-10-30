package dev.opensource.attenora.data

import dev.opensource.attenora.domain.HabitStreaks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What the strip, the nudge and the ongoing notification all read from.
 *
 * The board is a plain data holder, so "what is left today" is decided entirely
 * by these three helpers. Getting them wrong is what would make the app nag
 * about a habit that is not due, so they are tested directly.
 */
class HabitBoardTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 2)
    private val todayBit = HabitStreaks.dayBit(today)

    /** Every day except the one this board considers today. */
    private val otherDays = 0x7F and todayBit.inv()

    private fun habit(id: Long, name: String, mask: Int, mode: String = HabitMode.CHECK) =
        Habit(id = id, name = name, mode = mode, daysMask = mask)

    private fun board(
        habits: List<Habit>,
        marks: Map<Long, Int> = emptyMap()
    ) = HabitBoard(
        today = today,
        habits = habits,
        entries = marks.mapValues { (_, count) -> mapOf(today to count) },
        steps = emptyMap(),
        checksToday = emptyMap()
    )

    @Test
    fun `a habit scheduled for today is due`() {
        val board = board(listOf(habit(1, "Water", 0x7F)))
        assertEquals(listOf("Water"), board.dueToday().map { it.name })
    }

    @Test
    fun `a habit not scheduled today is not due`() {
        val board = board(listOf(habit(1, "Water", otherDays)))
        assertTrue(board.dueToday().isEmpty())
        assertTrue(board.nothingLeftToday())
    }

    @Test
    fun `an unmarked habit is left`() {
        val board = board(listOf(habit(1, "Water", 0x7F)))
        assertEquals(listOf("Water"), board.leftToday().map { it.name })
        assertTrue(board.doneToday().isEmpty())
        assertFalse(board.nothingLeftToday())
    }

    @Test
    fun `a marked habit is done, not left`() {
        val board = board(listOf(habit(1, "Water", 0x7F)), marks = mapOf(1L to 1))
        assertEquals(listOf("Water"), board.doneToday().map { it.name })
        assertTrue(board.leftToday().isEmpty())
        assertTrue(board.nothingLeftToday())
    }

    @Test
    fun `a counting habit is done on any count above zero`() {
        val board = board(
            listOf(habit(1, "Pages", 0x7F, HabitMode.COUNT)),
            marks = mapOf(1L to 3)
        )
        assertEquals(listOf("Pages"), board.doneToday().map { it.name })
        assertTrue(board.nothingLeftToday())
    }

    @Test
    fun `only the unmarked ones are left`() {
        val board = board(
            listOf(
                habit(1, "Water", 0x7F),
                habit(2, "Stretch", 0x7F),
                habit(3, "Journal", 0x7F)
            ),
            marks = mapOf(2L to 1)
        )
        assertEquals(listOf("Water", "Journal"), board.leftToday().map { it.name })
        assertEquals(3, board.dueToday().size)
        assertEquals(1, board.doneToday().size)
    }

    @Test
    fun `a day with no habits at all is never a nag`() {
        val board = board(emptyList())
        assertTrue(board.dueToday().isEmpty())
        assertTrue(board.nothingLeftToday())
    }
}
