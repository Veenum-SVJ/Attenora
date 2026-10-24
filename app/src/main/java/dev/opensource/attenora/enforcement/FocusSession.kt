package dev.opensource.attenora.enforcement

import dev.opensource.attenora.AppContainer
import dev.opensource.attenora.domain.FocusSessionEngine
import dev.opensource.attenora.domain.ResetEngine
import kotlinx.coroutines.flow.first

/**
 * Focus sessions (Curbox port): bookkeeping on top of the existing
 * block-all machinery. Enforcement keeps reading `settings.blockAllUntil`
 * exactly as before — this object only adds session start/end recording,
 * the "Focused today" accrual, and the ongoing countdown notification.
 *
 * All calls are suspend and idempotent: a finished session is folded into
 * the daily total exactly once, then its record is cleared.
 */
object FocusSession {

    /** True while a session recorded by [start] is still running. */
    suspend fun isRunning(
        container: AppContainer,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val start = container.settings.focusSessionStart.first()
        return start > 0L && now < container.settings.focusSessionEnd.first()
    }

    /** Start a blocking session until [endMs]. Any unfinished previous
     *  session is accrued for the time it actually covered, then replaced. */
    suspend fun start(container: AppContainer, endMs: Long) {
        val now = System.currentTimeMillis()
        val prevStart = container.settings.focusSessionStart.first()
        if (prevStart > 0L) {
            val prevEnd = container.settings.focusSessionEnd.first()
            accrue(container, FocusSessionEngine.Session(prevStart, prevEnd), now)
            container.settings.clearFocusSession()
        }
        container.settings.startFocusSession(now, endMs)
        container.settings.setBlockAllUntil(endMs)
        Notifier.focusSession(container.app, endMs)
    }

    /** End early: accrue the time actually spent, clear the block, drop the
     *  ongoing notification. */
    suspend fun end(
        container: AppContainer,
        now: Long = System.currentTimeMillis()
    ) {
        val start = container.settings.focusSessionStart.first()
        if (start > 0L) {
            val end = container.settings.focusSessionEnd.first()
            accrue(container, FocusSessionEngine.Session(start, end), now)
            container.settings.clearFocusSession()
        }
        container.settings.setBlockAllUntil(0L)
        Notifier.cancelFocusSession(container.app)
    }

    /**
     * Fold a session that has finished (possibly while the process was
     * dead) into "Focused today". A still-running session is left alone —
     * it accrues later, when this is next called.
     */
    suspend fun finalize(
        container: AppContainer,
        now: Long = System.currentTimeMillis()
    ) {
        val start = container.settings.focusSessionStart.first()
        if (start <= 0L) return
        val end = container.settings.focusSessionEnd.first()
        if (now < end) return // still running
        accrue(container, FocusSessionEngine.Session(start, end), now)
        container.settings.clearFocusSession()
        Notifier.cancelFocusSession(container.app)
    }

    /** Minutes accrued on the current usage day (0 after a reset-hour rollover). */
    suspend fun accruedTodayMinutes(
        container: AppContainer,
        now: Long = System.currentTimeMillis()
    ): Int {
        val day = container.settings.focusDay.first()
        val minutes = container.settings.focusMinutes.first()
        val resetHour = container.settings.resetHour.first()
        return FocusSessionEngine.accruedToday(
            FocusSessionEngine.Accrual(day, minutes), now, resetHour
        )
    }

    private suspend fun accrue(
        container: AppContainer,
        session: FocusSessionEngine.Session,
        now: Long
    ) {
        val day = container.settings.focusDay.first()
        val minutes = container.settings.focusMinutes.first()
        val resetHour = container.settings.resetHour.first()
        val next = FocusSessionEngine.accrue(
            FocusSessionEngine.Accrual(day, minutes), session, now, resetHour
        )
        container.settings.setFocusAccrual(next.dayKey, next.minutes)
        // Keep ResetEngine imported for day semantics documentation/readers.
        ResetEngine.dayKey(now, resetHour)
    }
}