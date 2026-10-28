package dev.opensource.attenora.enforcement

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import dev.opensource.attenora.AttenoraApplication
import dev.opensource.attenora.util.Diag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Repairs an App monitor that is *enabled but dead*.
 *
 * All of Attenora's monitoring lives in one accessibility service, so when the
 * service stops, limits and the reel counter stop together. Two very different
 * faults look identical from the outside, because the switch in Accessibility
 * only mirrors `enabled_accessibility_services` - a text setting, not a
 * liveness check:
 *
 *  - the setting was cleared (Android/HyperOS does this for sideloaded apps),
 *    so the switch is visibly off; and
 *  - the process died and the framework never rebound the service, so the
 *    switch still reads on while nothing is delivered.
 *
 * The second one is what HyperOS does: its cleaner kills background processes
 * when the phone is locked (`LockScreenClean` / `SwipeUpClean` in
 * `dumpsys activity exit-info`) and reports the kill as USER REQUESTED, which
 * Android honours by *not* restarting the service. Toggling the switch fixes it
 * because that rewrites the setting, which is the rebind signal.
 *
 * Only the process itself can tell the two apart: [AttenoraAccessibilityService]
 * flips `container.a11yConnected` when it is bound. So a monitor is dead when
 * the setting says on and nothing is connected *in this process*.
 *
 * A repair rewrites the setting with our component removed and then restored.
 * The component set has to change for the framework to rebind - writing the
 * identical string back is a no-op - hence the two writes instead of one.
 *
 * This needs WRITE_SECURE_SETTINGS (the adb grant already used for grayscale).
 * Without it we cannot heal, so we say so once in a while instead of failing
 * silently. A setting that is *off* is never rewritten: that is a deliberate
 * choice, and auto-enabling it back would be the app overriding the user.
 */
object MonitorWatchdog {

    /** Periodic job id. Fixed, so re-scheduling replaces instead of stacking. */
    const val JOB_ID = 4711

    /**
     * How often the heartbeat may run. JobScheduler's floor is 15 minutes and
     * it batches, so the real cadence is 15-30 minutes. No network, no wake
     * lock, no foreground service: the system decides when to wake us.
     */
    private const val HEARTBEAT_MS = 15L * 60_000L

    /**
     * Grace after a process start. Any process start means the accessibility
     * manager may have just asked for a bind that has not completed yet, and
     * "not connected yet" must not be mistaken for "dead". Application.onCreate
     * re-checks once this has passed.
     */
    private const val SETTLE_MS = 8_000L

    /** A repair costs two secure-setting writes; never do it twice in a row. */
    private const val REPAIR_COOLDOWN_MS = 60_000L

    /** At most one "monitor stopped" notification per half day. */
    private const val NOTIFY_EVERY_MS = 12L * 60 * 60_000L

    @Volatile private var processStartedAt = 0L

    @Volatile private var lastRepairAt = 0L

    /** Called from Application.onCreate, before anything can ask for a state. */
    fun markProcessStart() {
        if (processStartedAt == 0L) processStartedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Arms the heartbeat. Persisted so it survives a reboot, and skipped when a
     * job is already pending: scheduling the same id again would restart its
     * period, and this is called on every process start.
     */
    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        if (runCatching { jobs.getPendingJob(JOB_ID) }.getOrNull() != null) return
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, MonitorHeartbeatService::class.java)
        )
            .setPeriodic(HEARTBEAT_MS)
            .setPersisted(true)
            .build()
        runCatching { jobs.schedule(job) }
            .onFailure { Diag.d("monitor job schedule failed ${it.javaClass.simpleName}") }
    }

    /** True when the system lists our service as enabled, in either serialisation. */
    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, AttenoraAccessibilityService::class.java)
        val list = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull()
        return accessibilityListHas(
            list,
            component.flattenToString(),
            component.flattenToShortString()
        )
    }

    /**
     * Enabled in settings but nothing is bound in this process. Callers drawing
     * UI use this; it answers false during the post-start grace period.
     */
    fun isStalled(context: Context): Boolean {
        if (settleRemainingMs() > 0L) return false
        val container = (context.applicationContext as? AttenoraApplication)?.container
            ?: return false
        return !container.a11yConnected && isEnabled(context)
    }

    /**
     * The one entry point for every trigger: app start, boot, package replace,
     * our own alarms, and the periodic job. Cheap when healthy (one secure
     * read), so it is safe to call from a receiver's main thread.
     */
    fun check(context: Context, source: String) {
        val app = context.applicationContext as? AttenoraApplication ?: return
        val container = app.container
        if (container.a11yConnected) {
            Diag.d("monitor ok src=$source")
            return
        }
        if (!isEnabled(context)) {
            // Off is a choice, not a fault. The boot receiver tells the user
            // when a service that used to work has been revoked.
            Diag.d("monitor dead src=$source reason=disabled")
            return
        }
        if (settleRemainingMs() > 0L) {
            // A bind may be in flight; Application.onCreate checks again once
            // the grace period is over, so nothing is lost by waiting.
            Diag.d("monitor settling src=$source")
            return
        }
        if (repair(context)) {
            Diag.d("monitor repair src=$source")
        } else {
            Diag.d("monitor repair src=$source result=blocked")
            container.scope.launch { notifyStopped(context, container) }
        }
    }

    /** Milliseconds left of the post-start grace period; 0 once settled. */
    fun settleRemainingMs(): Long {
        if (processStartedAt == 0L) return 0L
        val elapsed = SystemClock.elapsedRealtime() - processStartedAt
        return (SETTLE_MS - elapsed).coerceAtLeast(0L)
    }

    private fun hasWriteSecure(context: Context): Boolean = runCatching {
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Forces a rebind. Returns false only when we may not write the setting;
     * a write that lands is a repair even if the vendor then refuses to bind,
     * because the next check simply tries again.
     */
    private fun repair(context: Context): Boolean {
        if (!hasWriteSecure(context)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastRepairAt < REPAIR_COOLDOWN_MS) return true
        val component = ComponentName(context, AttenoraAccessibilityService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        val resolver = context.contentResolver
        val current = runCatching {
            Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()
        val ok = runCatching {
            if (accessibilityListHas(current, full, short)) {
                // Remove, then put the original list back. The transient gap is
                // self-correcting: if we die between the two writes, the next
                // check sees the setting off and the UI tells the user instead.
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    accessibilityListWithout(current, full, short)
                )
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    current
                )
            } else {
                // The setting lost us (vendor cleanup re-serialised the list).
                // There is no change to make aside from adding us back.
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    accessibilityListWith(current, full, short)
                )
            }
            true
        }.getOrElse { false }
        if (ok) lastRepairAt = now
        return ok
    }

    private suspend fun notifyStopped(
        context: Context,
        container: dev.opensource.attenora.AppContainer
    ) {
        // Record the throttle only when the message can actually be shown;
        // otherwise a device without notification permission would swallow the
        // alert for half a day and then still not show it.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            !dev.opensource.attenora.ui.common.hasNotificationPermission(context)
        ) return
        val last = runCatching { container.settings.monitorNotifiedAt.first() }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        if (now - last < NOTIFY_EVERY_MS) return
        runCatching { container.settings.setMonitorNotifiedAt(now) }
        Notifier.onMonitorStopped(context)
    }
}

/**
 * The heartbeat. A dead monitor cannot be noticed by the app itself, so this is
 * the only trigger that still fires after hours of the phone being idle.
 */
class MonitorHeartbeatService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        // Cheap and main-thread-safe: one settings read plus, when split, two
        // settings writes. Nothing is deferred, so the job reports done now.
        MonitorWatchdog.check(this, "heartbeat")
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

// ---- Setting-list surgery (pure, so it can be unit-tested) ------------------

/**
 * True when [list] contains [full] or [short]. Android rewrites the list in
 * either serialisation, so both forms have to match. Comparison is exact
 * per entry: a different service in the same package must not count.
 */
internal fun accessibilityListHas(list: String?, full: String, short: String): Boolean =
    list.orEmpty().split(':').any { entry ->
        entry.equals(full, ignoreCase = true) || entry.equals(short, ignoreCase = true)
    }

/** [list] with our component removed, in either form. */
internal fun accessibilityListWithout(list: String, full: String, short: String): String =
    list.split(':')
        .filter {
            it.isNotEmpty() &&
                !it.equals(full, ignoreCase = true) &&
                !it.equals(short, ignoreCase = true)
        }
        .joinToString(":")

/**
 * [list] with our component added in full form. Any short-form copy is replaced
 * first, so the result never lists the same service twice.
 */
internal fun accessibilityListWith(list: String, full: String, short: String): String {
    val cleaned = accessibilityListWithout(list, full, short)
    return if (cleaned.isEmpty()) full else "$cleaned:$full"
}
