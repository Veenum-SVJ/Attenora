package dev.opensource.focusguard.enforcement

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.opensource.focusguard.FocusApp
import dev.opensource.focusguard.data.FocusSchedule
import dev.opensource.focusguard.ui.common.formatDuration
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

    const val ACTION_SCHEDULE = "dev.opensource.focusguard.action.SCHEDULE_START"
    const val ACTION_SCHEDULE_END = "dev.opensource.focusguard.action.SCHEDULE_END"
    const val ACTION_REMINDER = "dev.opensource.focusguard.action.DAILY_REMINDER"
    const val ACTION_WEEKLY = "dev.opensource.focusguard.action.WEEKLY_SUMMARY"
    const val EXTRA_SCHEDULE_ID = "schedule_id"
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
        dev.opensource.focusguard.util.Diag.d(
            "alarm id=${schedule.id} start=$start end=$end in=${start - now}ms ok=${ok.isSuccess} " +
                "err=${ok.exceptionOrNull()?.javaClass?.simpleName ?: ""}"
        )
    }

    fun setReminderAlarm(context: Context, minuteOfDay: Int) {
        setDailyAlarm(context, reminderPendingIntent(context), minuteOfDay)
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

    private fun actionPendingIntent(context: Context, action: String, requestCode: Int, id: Long): PendingIntent1 {
        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_SCHEDULE_ID, id)
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
        val app = context.applicationContext as? FocusApp ?: return
        val container = app.container
        val pending = goAsync()
        container.scope.launch {
            try {
                when (intent.action) {
                    Alarms.ACTION_SCHEDULE -> handleSchedule(context, container, intent)
                    Alarms.ACTION_SCHEDULE_END -> handleScheduleEnd(context, container, intent)
                    Alarms.ACTION_REMINDER -> handleReminder(context, container)
                    Alarms.ACTION_WEEKLY -> handleWeekly(context, container)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handleSchedule(context: Context, container: dev.opensource.focusguard.AppContainer, intent: Intent) {
        val id = intent.getLongExtra(Alarms.EXTRA_SCHEDULE_ID, -1L)
        val schedule = container.enforcement.schedules.value.firstOrNull { it.id == id } ?: return
        // Re-arm for the next occurrence before doing anything else.
        Alarms.setScheduleAlarm(context, schedule)
        if (!schedule.enabled) return
        if (container.settings.scheduleAlerts.first()) {
            Alarms.notifyUser(context, "FocusGuard", "\"${schedule.name}\" is now active")
        }
        container.enforcement.evaluate(
            container.usage.currentForeground() ?: return
        )
    }

    private suspend fun handleScheduleEnd(context: Context, container: dev.opensource.focusguard.AppContainer, intent: Intent) {
        val id = intent.getLongExtra(Alarms.EXTRA_SCHEDULE_ID, -1L)
        val schedule = container.enforcement.schedules.value.firstOrNull { it.id == id } ?: return
        Alarms.setScheduleAlarm(context, schedule)
        if (!schedule.enabled || !container.settings.scheduleAlerts.first()) return
        Alarms.notifyUser(context, "FocusGuard", "\"${schedule.name}\" has ended")
    }

    private suspend fun handleReminder(context: Context, container: dev.opensource.focusguard.AppContainer) {
        Alarms.setReminderAlarm(context, container.settings.reminderMinute.first())
        val day = container.usage.todayKey()
        val total = container.db.dailyUsageDao().forDay(day).sumOf { it.usedMs }
        Alarms.notifyUser(
            context,
            "Screen time today",
            "You've used your phone for ${formatDuration(total)} today. Tap to review your limits."
        )
    }

    private suspend fun handleWeekly(context: Context, container: dev.opensource.focusguard.AppContainer) {
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
}

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val container = (context.applicationContext as? FocusApp)?.container ?: return
        val pending = goAsync()
        container.scope.launch {
            try {
                // Read the table, not the StateFlow: at boot its value is
                // still the empty seed, so schedules would never be re-armed.
                Alarms.rearmAll(context, container.db.focusScheduleDao().observeAll().first())
                Alarms.setReminderAlarm(context, container.settings.reminderMinute.first())
                Alarms.rearmWeekly(
                    context,
                    container.settings.weeklyOn.first(),
                    container.settings.weeklyDay.first(),
                    container.settings.weeklyMinute.first()
                )
            } finally {
                pending.finish()
            }
        }
    }
}
