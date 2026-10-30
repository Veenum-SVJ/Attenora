package dev.opensource.attenora.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wording shared by the strip, the nudge and the ongoing notification.
 * These are the sentences a user reads forty times a week, so they get tested
 * like logic rather than treated as string decoration.
 */
class HabitCopyTest {

    @Test
    fun `progress line names the remaining count`() {
        assertEquals("2 of 5 done today", HabitCopy.progressLine(done = 2, due = 5))
    }

    @Test
    fun `progress line is settled when everything is done`() {
        assertEquals("All 5 done today", HabitCopy.progressLine(done = 5, due = 5))
    }

    @Test
    fun `progress line survives a day with nothing scheduled`() {
        assertEquals("Nothing scheduled today", HabitCopy.progressLine(done = 0, due = 0))
    }

    @Test
    fun `one name is just the name`() {
        assertEquals("Water", HabitCopy.condense(listOf("Water")))
    }

    @Test
    fun `two names join with and`() {
        assertEquals("Water and Stretch", HabitCopy.condense(listOf("Water", "Stretch")))
    }

    @Test
    fun `three names collapse to a count`() {
        assertEquals(
            "Water, Stretch and 1 more",
            HabitCopy.condense(listOf("Water", "Stretch", "Journal"))
        )
    }

    @Test
    fun `condense can ask for more names`() {
        assertEquals(
            "Water, Stretch and Journal",
            HabitCopy.condense(listOf("Water", "Stretch", "Journal"), max = 3)
        )
    }

    @Test
    fun `left line is empty when nothing is left`() {
        assertEquals("", HabitCopy.leftLine(emptyList()))
    }

    @Test
    fun `left line says what is still open`() {
        assertEquals("Water still open", HabitCopy.leftLine(listOf("Water")))
    }

    @Test
    fun `reminder title is singular for one habit`() {
        assertEquals("1 habit left today", HabitCopy.reminderTitle(1))
    }

    @Test
    fun `reminder title is plural for several`() {
        assertEquals("3 habits left today", HabitCopy.reminderTitle(3))
    }

    @Test
    fun `reminder body reads as a sentence for one habit`() {
        assertEquals(
            "Water is still unmarked today.",
            HabitCopy.reminderBody(listOf("Water"))
        )
    }

    @Test
    fun `reminder body lists several without a wall of names`() {
        assertEquals(
            "Water, Stretch, Journal and 1 more are still unmarked today.",
            HabitCopy.reminderBody(listOf("Water", "Stretch", "Journal", "Read"))
        )
    }

    @Test
    fun `status line leads with the fraction`() {
        assertEquals(
            "3 of 5 left · Water, Stretch and 1 more",
            HabitCopy.statusLine(done = 2, due = 5, names = listOf("Water", "Stretch", "Journal"))
        )
    }

    @Test
    fun `status line drops the names once the day is clear`() {
        assertEquals(
            "All 5 done today",
            HabitCopy.statusLine(done = 5, due = 5, names = emptyList())
        )
    }
}
