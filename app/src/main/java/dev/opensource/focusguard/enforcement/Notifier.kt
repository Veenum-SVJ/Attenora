package dev.opensource.focusguard.enforcement

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.opensource.focusguard.R
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.common.formatDuration
import dev.opensource.focusguard.ui.common.hasNotificationPermission
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

    private const val ID_ASSISTANT = 4100
    private const val ID_TOTAL10 = 4200

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

    /** Plain message sender used by alarms (daily/weekly/morning/night-owl/schedule). */
    fun message(context: Context, id: Int, title: String, text: String, channel: String = CHANNEL_USAGE) {
        post(context, id, base(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build())
    }

    /** "Go to FocusGuard" deep link for reminder-style notifications. */
    fun pendingOpenApp(context: Context): PendingIntent {
        val intent = Intent(context, dev.opensource.focusguard.ui.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, "open".hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

private fun Context.container(): dev.opensource.focusguard.AppContainer =
    (applicationContext as dev.opensource.focusguard.FocusApp).container
