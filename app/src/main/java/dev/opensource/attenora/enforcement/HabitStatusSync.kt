package dev.opensource.attenora.enforcement

import android.content.Context
import dev.opensource.attenora.AppContainer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the ongoing "what is left today" notification honest.
 *
 * Everything here is driven by the database, never by a timer: a habit tick
 * writes a row, Room re-emits, the notification is rewritten in the same
 * breath. The only alarm involved is the daily rollover refresh in [Alarms],
 * because a midnight that nobody is awake for produces no write to react to.
 */
object HabitStatusSync {

    /** One-shot: read the board and make the shade match it. */
    suspend fun refresh(context: Context, container: AppContainer) {
        if (!container.settings.habitNotifyOn.first()) {
            Notifier.cancelHabitStatus(context)
            return
        }
        val board = container.habits.observe().first()
        Notifier.habitStatus(
            context,
            done = board.doneToday().size,
            due = board.dueToday().size,
            leftNames = board.leftToday().map { it.name }
        )
    }

    /** Follow the database for as long as the process lives. */
    fun start(context: Context, container: AppContainer) {
        container.scope.launch {
            combine(container.settings.habitNotifyOn, container.habits.observe()) { on, board -> on to board }
                .collect { (on, board) ->
                    if (on) {
                        Notifier.habitStatus(
                            context,
                            done = board.doneToday().size,
                            due = board.dueToday().size,
                            leftNames = board.leftToday().map { it.name }
                        )
                    } else {
                        Notifier.cancelHabitStatus(context)
                    }
                }
        }
    }
}
