package dev.opensource.attenora.enforcement

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.opensource.attenora.AttenoraApplication
import dev.opensource.attenora.data.FocusSchedule
import dev.opensource.attenora.data.Habit
import dev.opensource.attenora.data.HabitReminders
import dev.opensource.attenora.ui.common.formatDuration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Alarm wiring for schedule starts/ends and the optional reminders.
 *
 * Alarms are set with AlarmManager.setWindow (inexact, no SCHEDULE_EXACT_ALARM
 * needed, battery friendly). Enforcement itself is lazy: even if an alarm is
 * deferred, the moment the user opens an app the foreground monitor decides
 * whether a schedule window is active.
 */
object Alarms {

    const val ACTION_SCHEDULE = "dev.opensource.attenora.action.SCHEDULE_START"
    const val ACTION_SCHEDULE_END = "dev.opensource.attenora.action.SCHEDULE_END"
    const val ACTION_REMINDER = "dev.opensource.attenora.action.DAILY_REMINDER"
    const val ACTION_WEEKLY = "dev.opensource.attenora.action.WEEKLY_SUMMARY"
    const val ACTION_HABIT_REMINDER = "dev.opensource.attenora.action.HABIT_REMINDER"
    const val ACTION_HABIT_REFRESH = "dev.opensource.attenora.action.HABIT_REFRESH"
    const val EXTRA_SCHEDULE_ID = "schedule_id"
    /** Which time slot a habit-reminder alarm belongs to; -1 = unknown/legacy. */
    const val EXTRA_HABIT_MINUTE = "habit_minute"
    private const val WINDOW_MS = 60_000L
    private const val DAY_MS = 24L * 60 * 60 * 1000L

    fun rearmAll(context: Context, schedules: List<FocusSchedule>) {
        schedules.forEach { setScheduleAlarm(context, it) }
    }

    fun setScheduleAlarm(context: Context, schedule: FocusSchedule) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = schedulePendingIntent(context, schedule.id)
        val endPi = scheduleEndPendingIntent(context, schedule.id)
        if (!schedule.enabled) {
            am.cancel(pi)
            am.cancel(endPi)
            return
        }
        val now = System.currentTimeMillis()
        val start = nextStartMillis(schedule, now)
        val end = nextEndMillis(schedule, now)
        val ok = runCatching {
            am.setWindow(AlarmManager.RTC_WAKEUP, start, WINDOW_MS, pi)
            am.setWindow(AlarmManager.RTC_WAKEUP, end, WINDOW_MS, endPi)
        }
        dev.opensource.attenora.util.Diag.d(
            "alarm id=${schedule.id} start=$start end=$end in=${start - now}ms ok=${ok.isSuccess} " +
                "err=${ok.exceptionOrNull()?.javaClass?.simpleName ?: ""}"
        )
    }

    fun setReminderAlarm(context: Context, minuteOfDay: Int) {
        setDailyAlarm(context, reminderPendingIntent(context), minuteOfDay)
    }

    /**
     * Arm the next slot rather than the whole day: exactly one habit alarm
     * exists at a time, so ten reminder times still cost one wakeup — and
     * there is no stale alarm to cancel when a time is edited or a habit
     * deleted. The PendingIntent is shared, so arming simply replaces the
     * previous alarm.
     *
     * [afterMinute] is the minute that just fired (or the current minute), so
     * a firing slot can never re-arm itself into a loop.
     */
    fun armNextHabitReminder(
        context: Context,
        slots: Map<Int, List<Habit>>,
        afterMinute: Int = minuteOfDay()
    ) {
        // Earliest slot still ahead today, else tomorrow's earliest (which
        // setDailyAlarm pushes to the next day when its time has passed).
        val next = slots.keys.firstOrNull { it > afterMinute } ?: slots.keys.firstOrNull()
        if (next == null) {
            context.getSystemService(AlarmManager::class.java)?.cancel(habitPendingIntent(context, -1))
            return
        }
        setDailyAlarm(context, habitPendingIntent(context, next), next)
    }

    /** Minutes after midnight, local wall clock. */
    internal fun minuteOfDay(): Int {
        val now = java.time.LocalTime.now()
        return now.hour * 60 + now.minute
    }

    /**
     * Just after the usage day rolls over, re-label the ongoing habit status.
     * Without this the shade would still claim "3 of 5 done" from yesterday on
     * a phone that has not been opened since. One inexact alarm a day.
     */
    fun setHabitRefreshAlarm(context: Context, on: Boolean, resetHour: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = habitRefreshPendingIntent(context)
        if (!on) {
            am.cancel(pi)
            return
        }
        val minute = (resetHour.coerceIn(0, 23) * 60 + 1)
        setDailyAlarm(context, pi, minute)
    }

    fun rearmWeekly(context: Context, on: Boolean, dayMon0: Int, minuteOfDay: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = weeklyPendingIntent(context)
        if (!on || minuteOfDay !in 0..1439) {
            am.cancel(pi); return
        }
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (i in 0..7) {
            val date = today.plusDays(i.toLong())
            if (date.dayOfWeek.value - 1 != dayMon0) continue
            val candidate = date.atStartOfDay(zone).plusMinutes(minuteOfDay.toLong())
                .toInstant().toEpochMilli()
            if (candidate > now) {
                am.setWindow(AlarmManager.RTC_WAKEUP, candidate, WINDOW_MS, pi)
                return
            }
        }
    }

    private fun setDailyAlarm(context: Context, pi: PendingIntent1, minuteOfDay: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (minuteOfDay !in 0..1439) {
            am.cancel(pi)
            return
        }
        val zone = ZoneId.systemDefault()
        var trigger = LocalDate.now().atStartOfDay(zone).plusMinutes(minuteOfDay.toLong())
            .toInstant().toEpochMilli()
        if (trigger <= System.currentTimeMillis()) trigger += DAY_MS
        am.setWindow(AlarmManager.RTC_WAKEUP, trigger, WINDOW_MS, pi)
    }

    /** Next future occurrence of the schedule's start time on one of its days. */
    internal fun nextStartMillis(schedule: FocusSchedule, now: Long): Long =
        nextOnSelectedDay(schedule, now) { s, date, zone ->
            date.atStartOfDay(zone).plusMinutes(s.startMinute.toLong()).toInstant().toEpochMilli()
        }

    /** Next future occurrence of the schedule's end time (for end alerts). */
    internal fun nextEndMillis(schedule: FocusSchedule, now: Long): Long =
        nextOnSelectedDay(schedule, now) { s, date, zone ->
            date.atStartOfDay(zone).plusMinutes(s.endMinute.toLong()).toInstant().toEpochMilli()
        }.let { candidate ->
            // Overnight windows end on the day AFTER the selected start day.
            if (schedule.endMinute <= schedule.startMinute && candidate <= now) {
                nextOnSelectedDay(schedule, now) { s, date, zone ->
                    date.plusDays(1).atStartOfDay(zone).plusMinutes(s.endMinute.toLong())
                        .toInstant().toEpochMilli()
                }
            } else candidate
        }

    private fun nextOnSelectedDay(
        schedule: FocusSchedule,
        now: Long,
        at: (FocusSchedule, LocalDate, ZoneId) -> Long
    ): Long {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (i in 0..7) {
            val date = today.plusDays(i.toLong())
            val mon0 = date.dayOfWeek.value - 1
            if (!ScheduleDays.test(schedule.daysMask, mon0)) continue
            val candidate = at(schedule, date, zone)
            if (candidate > now) return candidate
        }
        return now + DAY_MS
    }

    private fun schedulePendingIntent(context: Context, id: Long): PendingIntent1 =
        actionPendingIntent(context, ACTION_SCHEDULE, "schedule_$id".hashCode(), id)

    private fun scheduleEndPendingIntent(context: Context, id: Long): PendingIntent1 =
        actionPendingIntent(context, ACTION_SCHEDULE_END, "schedule_end_$id".hashCode(), id)

    private fun reminderPendingIntent(context: Context): PendingIntent1 =
        actionPendingIntent(context, ACTION_REMINDER, "reminder".hashCode(), 0L)

    private fun weeklyPendingIntent(context: Context): PendingIntent1 =
        actionPendingIntent(context, ACTION_WEEKLY, "weekly".hashCode(), 0L)

    /**
     * One shared request code, so arming a slot replaces the previous alarm.
     * The slot minute rides along in the extra: without it the receiver
     * cannot tell midnight from "unknown" and would match zero habits.
     */
    private fun habitPendingIntent(context: Context, minute: Int): PendingIntent1 =
        actionPendingIntent(context, ACTION_HABIT_REMINDER, "habit_reminder".hashCode(), 0L) {
            putExtra(EXTRA_HABIT_MINUTE, minute)
        }

    private fun habitRefreshPendingIntent(context: Context): PendingIntent1 =
        actionPendingIntent(context, ACTION_HABIT_REFRESH, "habit_refresh".hashCode(), 0L)

    private fun actionPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        id: Long,
        extra: Intent.() -> Unit = {}
    ): PendingIntent1 {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_SCHEDULE_ID, id)
            .also(extra)
        return android.app.PendingIntent.getBroadcast(
            context, requestCode, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    internal fun notifyUser(context: Context, title: String, text: String) {
        Notifier.message(context, text.hashCode(), title, text)
    }
}

private typealias PendingIntent1 = android.app.PendingIntent

/** Small helper so the receiver and engine share one day-bit convention. */
object ScheduleDays {
    fun test(mask: Int, dayOfWeekMon0: Int): Boolean = (mask shr dayOfWeekMon0) and 1 == 1
}

class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? AttenoraApplication ?: return
        val container = app.container
        // An alarm is one of the few things that wakes a killed process. Reuse
        // it to notice and heal a monitor the vendor killed overnight.
        MonitorWatchdog.check(context, "alarm")
        val pending = goAsync()
        container.scope.launch {
            try {
                when (intent.action) {
                    Alarms.ACTION_SCHEDULE -> handleSchedule(context, container, intent)
                    Alarms.ACTION_SCHEDULE_END -> handleScheduleEnd(context, container, intent)
                    Alarms.ACTION_REMINDER -> handleReminder(context, container)
                    Alarms.ACTION_WEEKLY -> handleWeekly(context, container)
                    Alarms.ACTION_HABIT_REMINDER -> handleHabitReminder(context, container, intent)
                    Alarms.ACTION_HABIT_REFRESH -> handleHabitRefresh(context, container)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleSchedule(context: Context, container: dev.opensource.attenora.AppContainer, intent: Intent) {
        val id = intent.getLongExtra(Alarms.EXTRA_SCHEDULE_ID, -1L)
        val schedule = container.enforcement.schedules.value.firstOrNull { it.id == id } ?: return
        // Re-arm for the next occurrence before doing anything else.
        Alarms.setScheduleAlarm(context, schedule)
        if (!schedule.enabled) return
        if (container.settings.scheduleAlerts.first()) {
            Alarms.notifyUser(context, "Attenora", "\"${schedule.name}\" is now active")
        }
        container.enforcement.evaluate(
            container.usage.currentForeground() ?: return
        )
    }

    private suspend fun handleScheduleEnd(context: Context, container: dev.opensource.attenora.AppContainer, intent: Intent) {
        val id = intent.getLongExtra(Alarms.EXTRA_SCHEDULE_ID, -1L)
        val schedule = container.enforcement.schedules.value.firstOrNull { it.id == id } ?: return
        Alarms.setScheduleAlarm(context, schedule)
        if (!schedule.enabled || !container.settings.scheduleAlerts.first()) return
        Alarms.notifyUser(context, "Attenora", "\"${schedule.name}\" has ended")
    }

    private suspend fun handleReminder(context: Context, container: dev.opensource.attenora.AppContainer) {
        Alarms.setReminderAlarm(context, container.settings.reminderMinute.first())
        val day = container.usage.todayKey()
        val total = container.db.dailyUsageDao().forDay(day).sumOf { it.usedMs }
        Alarms.notifyUser(
            context,
            "Screen time today",
            "You've used your phone for ${formatDuration(total)} today. Tap to review your limits."
        )
    }

    private suspend fun handleWeekly(context: Context, container: dev.opensource.attenora.AppContainer) {
        val weeklyOn = container.settings.weeklyOn.first()
        Alarms.rearmWeekly(
            context,
            weeklyOn,
            container.settings.weeklyDay.first(),
            container.settings.weeklyMinute.first()
        )
        if (!weeklyOn) return
        val end = container.usage.todayKey()
        val rows = container.db.dailyUsageDao().range(
            java.time.LocalDate.parse(end).minusDays(6).toString(), end
        )
        val total = rows.sumOf { it.usedMs }
        Alarms.notifyUser(
            context,
            "Weekly summary",
            "This week: ${formatDuration(total)} of screen time across ${rows.map { it.packageName }.distinct().size} apps."
        )
    }

    /**
     * One slot of the habit nudge: the habits timed for this minute, and only
     * those — a morning habit has already had its say by evening. Re-arms the
     * next slot before anything else, so a deferred alarm cannot swallow it,
     * and posts nothing when this slot is already clear: the app should not
     * beg for a tick you already made.
     */
    private suspend fun handleHabitReminder(
        context: Context,
        container: dev.opensource.attenora.AppContainer,
        intent: Intent
    ) {
        val default = container.settings.habitReminderMinute.first()
        val slots = HabitReminders.slots(container.db.habitDao().all(), default)
        val fired = intent.getIntExtra(Alarms.EXTRA_HABIT_MINUTE, -1)
        // An alarm armed before this upgrade carries no slot in its extra;
        // treat it as "now" so it re-arms correctly and notifies nobody.
        val minute = if (fired in 0..1439) fired else Alarms.minuteOfDay()
        Alarms.armNextHabitReminder(context, slots, afterMinute = minute)

        val left = HabitReminders.leftInSlot(
            container.habits.observe().first().leftToday(),
            minute,
            default
        )
        if (left.isEmpty()) return
        Notifier.habitReminder(context, leftNames = left.map { it.name })
    }

    /** Day rollover: re-label the ongoing status without waiting for a launch. */
    private suspend fun handleHabitRefresh(context: Context, container: dev.opensource.attenora.AppContainer) {
        val on = container.settings.habitNotifyOn.first()
        Alarms.setHabitRefreshAlarm(context, on, container.settings.resetHour.first())
        HabitStatusSync.refresh(context, container)
    }
}

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // A clock or timezone change moves every wall-clock alarm with it:
            // a daily reminder set for 21:00 has to stay 21:00 local, not the
            // instant it was scheduled for. Same re-arm as boot.
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val container = (context.applicationContext as? AttenoraApplication)?.container ?: return
        val pending = goAsync()
        container.scope.launch {
            try {
                // Read the table, not the StateFlow: at boot its value is
                // still the empty seed, so schedules would never be re-armed.
                Alarms.rearmAll(context, container.db.focusScheduleDao().observeAll().first())
                Alarms.setReminderAlarm(context, container.settings.reminderMinute.first())
                Alarms.armNextHabitReminder(
                    context,
                    HabitReminders.slots(
                        container.db.habitDao().all(),
                        container.settings.habitReminderMinute.first()
                    )
                )
                Alarms.setHabitRefreshAlarm(
                    context,
                    container.settings.habitNotifyOn.first(),
                    container.settings.resetHour.first()
                )
                HabitStatusSync.refresh(context, container)
                Alarms.rearmWeekly(
                    context,
                    container.settings.weeklyOn.first(),
                    container.settings.weeklyDay.first(),
                    container.settings.weeklyMinute.first()
                )
                // The heartbeat is persisted, but arming it here costs nothing
                // and covers a job the system dropped while the app was
                // force-stopped or updated.
                MonitorWatchdog.schedule(context)
                // HyperOS (and some other vendors) revoke accessibility for
                // sideloaded apps on reboot, silently killing limit blocking
                // and the reel counter. If the monitor had ever worked and is
                // now gone, say so instead of letting it die quietly.
                if (container.settings.a11yEverOn.first() &&
                    !MonitorWatchdog.isEnabled(context)
                ) {
                    Notifier.onMonitorDisabled(context)
                }
                // Application.onCreate already schedules a check once the
                // process has settled, which covers boot as well: this receiver
                // is what started that process.
            } finally {
                pending.finish()
            }
        }
    }
}
