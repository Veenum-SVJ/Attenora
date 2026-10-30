package dev.opensource.attenora.enforcement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.opensource.attenora.R
import dev.opensource.attenora.domain.HabitCopy
import dev.opensource.attenora.ui.common.appLabel
import dev.opensource.attenora.ui.common.formatDuration
import dev.opensource.attenora.ui.common.hasNotificationPermission
import kotlinx.coroutines.flow.first

/**
 * All notification posting in one place. Every call is a no-op unless the
 * user enabled that specific notification in Usage Notifications — and
 * nothing here ever schedules work; callers are foreground transitions,
 * block hits and existing alarms.
 */
object Notifier {

    const val CHANNEL_ALERTS = "fg_alerts"
    const val CHANNEL_USAGE = "fg_usage"
    const val CHANNEL_ASSISTANT = "fg_assistant"
    const val CHANNEL_HABITS = "fg_habits"
    const val CHANNEL_HABIT_STATUS = "fg_habit_status"

    private const val ID_ASSISTANT = 4100
    private const val ID_TOTAL10 = 4200
    private const val ID_FOCUS = 4300
    private const val ID_MONITOR_OFF = 4400
    private const val ID_MONITOR_STOPPED = 4500
    private const val ID_HABIT_REMINDER = 4600
    private const val ID_HABIT_STATUS = 4700

    @Volatile private var lastTotal10At = 0L
    private const val TOTAL10_INTERVAL_MS = 10 * 60_000L

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Focus alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_USAGE, "Usage reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ASSISTANT, "Usage Assistant", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        // A nudge you asked for may make a sound; the ongoing status line is
        // silent by design and must never buzz on every habit tick.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HABITS, "Habit reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_HABIT_STATUS, "Habit status", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
    }

    private fun canPost(context: Context): Boolean =
        hasNotificationPermission(context) || Build.VERSION.SDK_INT < 33

    private fun base(context: Context, channel: String): Notification.Builder =
        Notification.Builder(context, channel).setSmallIcon(R.mipmap.ic_launcher)

    private fun post(context: Context, id: Int, notification: Notification) {
        if (!canPost(context)) return
        ensureChannels(context)
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }

    /** Block-hit alert (toggleable). */
    suspend fun onBlockHit(context: Context, target: String, reason: String) {
        if (!context.container().settings.blockHitOn.first()) return
        val label = if (target.contains('.')) appLabel(context, target) else target
        val text = when (reason) {
            "website" -> "$label is blocked right now."
            "schedule" -> "$label is blocked by an active schedule."
            "blockall" -> "$label is blocked during this focus session."
            "cooldown" -> "$label's session is used up — it's on a break."
            "permanent" -> "$label is blocked until you remove the limit."
            else -> "$label reached its limit."
        }
        post(context, ("block:$target").hashCode(), base(context, CHANNEL_ALERTS)
            .setContentTitle("Blocked")
            .setContentText(text)
            .setAutoCancel(true)
            .build())
    }

    /** "5 minutes left" warning (toggleable, once per app per day). */
    suspend fun onFiveMinutes(context: Context, target: String) {
        if (!context.container().settings.fiveMinWarn.first()) return
        val label = appLabel(context, target)
        post(context, ("five:$target").hashCode(), base(context, CHANNEL_ALERTS)
            .setContentTitle("5 minutes left")
            .setContentText("$label has 5 minutes remaining today.")
            .setAutoCancel(true)
            .build())
    }

    /**
     * The App monitor was turned off under us — a reboot, or the vendor
     * revoking accessibility for a sideloaded app (HyperOS does this). Unlike
     * every other notification this one ignores the per-feature toggles: the
     * user cannot act on limits that nobody is watching, and it fires at most
     * once per boot.
     */
    fun onMonitorDisabled(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        post(context, ID_MONITOR_OFF, base(context, CHANNEL_ALERTS)
            .setContentTitle("App monitor is off")
            .setContentText("Limit blocking and the reel counter are paused. " +
                "Tap to turn the App monitor back on.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    /**
     * The monitor is enabled in Accessibility but nothing is bound, and we are
     * not allowed to rewrite the setting ourselves (WRITE_SECURE_SETTINGS is
     * missing). Tapping opens Accessibility, where toggling Attenora off and on
     * rebinds it - the same thing the repair would have done.
     */
    fun onMonitorStopped(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        post(context, ID_MONITOR_STOPPED, base(context, CHANNEL_ALERTS)
            .setContentTitle("App monitor stopped")
            .setContentText("Limits and the reel counter are paused. " +
                "Tap, then switch Attenora off and on again.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    /**
     * Usage Assistant (ongoing) and the 10-minute Total Time message.
     * Called on foreground transitions — no timers, no polling.
     */
    suspend fun onForegroundTotals(context: Context, appPkg: String?, todayTotalMs: Long) {
        val container = context.container()
        val settings = container.settings
        val assistantOn = settings.assistantOn.first()
        val total10On = settings.total10On.first()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        if (assistantOn) {
            ensureChannels(context)
            val current = appPkg?.let { appLabel(context, it) } ?: "Home"
            val content = "$current · ${formatDuration(todayTotalMs)} today"
            if (canPost(context)) {
                nm.notify(ID_ASSISTANT, base(context, CHANNEL_ASSISTANT)
                    .setContentTitle("Usage Assistant")
                    .setContentText(content)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build())
            }
        } else {
            nm.cancel(ID_ASSISTANT)
        }

        if (total10On) {
            val now = System.currentTimeMillis()
            if (now - lastTotal10At >= TOTAL10_INTERVAL_MS) {
                lastTotal10At = now
                post(context, ID_TOTAL10, base(context, CHANNEL_USAGE)
                    .setContentTitle("Total time today")
                    .setContentText("You've used your phone for ${formatDuration(todayTotalMs)}.")
                    .setAutoCancel(true)
                    .build())
            }
        } else {
            nm.cancel(ID_TOTAL10)
        }
    }

    /**
     * Ongoing focus-session notification. The system chronometer counts down
     * to [endMs] by itself — no timers or refreshes on our side.
     */
    fun focusSession(context: Context, endMs: Long) {
        if (!canPost(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val at = java.time.Instant.ofEpochMilli(endMs).atZone(java.time.ZoneId.systemDefault())
        val notification = base(context, CHANNEL_ASSISTANT)
            .setContentTitle("Focus session")
            .setContentText("Everything unlocks at %02d:%02d".format(at.hour, at.minute))
            .setWhen(endMs)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(ID_FOCUS, notification)
    }

    fun cancelFocusSession(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(ID_FOCUS)
        }
    }

    /**
     * The habit nudge for one time slot: exactly the habits that are still
     * unmarked and timed for it. Silent when everything is already done — a
     * reminder that tells you there is nothing to do is just noise.
     */
    fun habitReminder(context: Context, leftNames: List<String>) {
        if (leftNames.isEmpty()) return
        val body = HabitCopy.reminderBody(leftNames)
        post(context, ID_HABIT_REMINDER, base(context, CHANNEL_HABITS)
            .setContentTitle(HabitCopy.reminderTitle(leftNames.size))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pendingOpenApp(context, tab = 3))
            .setAutoCancel(true)
            .build())
    }

    /**
     * The ongoing "what is left" line: the strip for people who never open the
     * app on their own. Updated in place and silent, so ticking a habit does
     * not buzz; cancelled rather than updated once the day is clear.
     */
    fun habitStatus(context: Context, done: Int, due: Int, leftNames: List<String>) {
        if (due <= 0 || leftNames.isEmpty()) {
            cancelHabitStatus(context)
            return
        }
        if (!canPost(context)) return
        ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val line = HabitCopy.statusLine(done, due, leftNames)
        nm.notify(ID_HABIT_STATUS, base(context, CHANNEL_HABIT_STATUS)
            .setContentTitle("Habits today")
            .setContentText(line)
            .setContentIntent(pendingOpenApp(context, tab = 3))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build())
    }

    fun cancelHabitStatus(context: Context) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(ID_HABIT_STATUS)
        }
    }

    /** Plain message sender used by alarms (daily/weekly/night-owl/schedule). */
    fun message(context: Context, id: Int, title: String, text: String, channel: String = CHANNEL_USAGE) {
        post(context, id, base(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build())
    }

    /** "Go to Attenora" deep link for reminder-style notifications. */
    fun pendingOpenApp(context: Context, tab: Int = -1): PendingIntent {
        val intent = Intent(context, dev.opensource.attenora.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_TAB, tab)
        return PendingIntent.getActivity(
            context, "open".hashCode() + tab, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Tab a notification wants opened; -1 means "wherever the user left off". */
    const val EXTRA_OPEN_TAB = "open_tab"
}

private fun Context.container(): dev.opensource.attenora.AppContainer =
    (applicationContext as dev.opensource.attenora.AttenoraApplication).container
