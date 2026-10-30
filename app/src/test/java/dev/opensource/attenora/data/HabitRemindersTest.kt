package dev.opensource.attenora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HabitReminders] is the whole of "which habit is nudged at which minute", so
 * these tests are the only thing standing between a morning habit and an
 * evening alarm. The alarm side never sees anything but the finished map.
 */
class HabitRemindersTest {

    private fun habit(id: Long, name: String, own: Int = -1) =
        Habit(id = id, name = name, reminderMinute = own)

    @Test
    fun `a habit without its own time follows the default`() {
        assertEquals(20 * 60, HabitReminders.effectiveMinute(habit(1, "Water"), 20 * 60))
    }

    @Test
    fun `a habit's own time wins over the default`() {
        assertEquals(
            8 * 60,
            HabitReminders.effectiveMinute(habit(1, "Water", 8 * 60), 20 * 60)
        )
    }

    @Test
    fun `an impossible minute counts as no time of its own`() {
        assertEquals(20 * 60, HabitReminders.effectiveMinute(habit(1, "Water", 1440), 20 * 60))
        assertEquals(-1, HabitReminders.effectiveMinute(habit(1, "Water", -1), -1))
    }

    @Test
    fun `habits group into one slot per distinct time`() {
        val slots = HabitReminders.slots(
            listOf(
                habit(1, "Water", 8 * 60),
                habit(2, "Stretch"),
                habit(3, "Journal"),
                habit(4, "Read", 8 * 60)
            ),
            defaultMinute = 20 * 60
        )
        assertEquals(setOf(8 * 60, 20 * 60), slots.keys)
        assertEquals(listOf("Water", "Read"), slots[8 * 60]!!.map { it.name })
        assertEquals(listOf("Stretch", "Journal"), slots[20 * 60]!!.map { it.name })
    }

    @Test
    fun `a shared time costs one slot, not one per habit`() {
        val habits = (1L..9L).map { habit(it, "Habit $it", 21 * 60) }
        assertEquals(1, HabitReminders.slots(habits, -1).size)
    }

    @Test
    fun `turning the default off leaves only habits that chose a time`() {
        val slots = HabitReminders.slots(
            listOf(habit(1, "Water"), habit(2, "Stretch", 7 * 60)),
            defaultMinute = -1
        )
        assertEquals(setOf(7 * 60), slots.keys)
    }

    @Test
    fun `no times anywhere means no alarm is spent`() {
        assertTrue(HabitReminders.slots(listOf(habit(1, "Water")), -1).isEmpty())
        assertTrue(HabitReminders.slots(emptyList(), 20 * 60).isEmpty())
    }

    @Test
    fun `a slot only nudges its own left habits`() {
        val water = habit(1, "Water", 8 * 60)
        val stretch = habit(2, "Stretch")
        val left = listOf(water, stretch)

        assertEquals(
            listOf("Water"),
            HabitReminders.leftInSlot(left, 8 * 60, 20 * 60).map { it.name }
        )
        assertEquals(
            listOf("Stretch"),
            HabitReminders.leftInSlot(left, 20 * 60, 20 * 60).map { it.name }
        )
        assertTrue(HabitReminders.leftInSlot(left, 13 * 60, 20 * 60).isEmpty())
    }
}
