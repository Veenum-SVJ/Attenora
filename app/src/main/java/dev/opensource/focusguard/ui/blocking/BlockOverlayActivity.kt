package dev.opensource.focusguard.ui.blocking

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.FocusApp
import dev.opensource.focusguard.data.FocusSchedule
import dev.opensource.focusguard.data.ThemeMode
import dev.opensource.focusguard.domain.ResetEngine
import dev.opensource.focusguard.domain.UnlockEngine
import dev.opensource.focusguard.enforcement.Notifier
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAccentColor
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.icons.FgIcons
import dev.opensource.focusguard.ui.theme.FgColors
import dev.opensource.focusguard.ui.theme.FocusGuardTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The blocking screen. Launched with a new task whenever a limit, focus
 * schedule, block-now session, or blocked website triggers. Pressing back
 * dismisses it — the monitor simply brings it back, which is exactly the
 * "nag" behavior you want from a focus app.
 */
class BlockOverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "fg_pkg"
        const val EXTRA_REASON = "fg_reason"
        const val EXTRA_DOMAIN = "fg_domain"
        const val EXTRA_UNTIL = "fg_until"

        const val REASON_LIMIT = "limit"
        const val REASON_SCHEDULE = "schedule"
        const val REASON_BLOCK_ALL = "blockall"
        const val REASON_WEBSITE = "website"
        const val REASON_COOLDOWN = "cooldown"

        @Volatile
        var visible = false
            private set

        fun launch(
            context: Context,
            pkg: String?,
            reason: String,
            domain: String? = null,
            untilMs: Long = 0L
        ) {
            val intent = Intent(context, BlockOverlayActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                .putExtra(EXTRA_PACKAGE, pkg)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_DOMAIN, domain)
                .putExtra(EXTRA_UNTIL, untilMs)
            val started = runCatching { context.startActivity(intent) }
            dev.opensource.focusguard.util.Diag.d(
                "launch pkg=$pkg reason=$reason until=$untilMs " +
                    (started.exceptionOrNull()?.let { "threw=${it.javaClass.simpleName}:${it.message}" } ?: "ok")
            )
        }

        private fun paramsOf(intent: Intent?) = Params(
            pkg = intent?.getStringExtra(EXTRA_PACKAGE),
            reason = intent?.getStringExtra(EXTRA_REASON) ?: REASON_LIMIT,
            domain = intent?.getStringExtra(EXTRA_DOMAIN),
            untilMs = intent?.getLongExtra(EXTRA_UNTIL, 0L) ?: 0L
        )
    }

    /** What the overlay is currently showing; replaced on every new intent. */
    private data class Params(val pkg: String?, val reason: String, val domain: String?, val untilMs: Long)

    private var params by mutableStateOf(Params(null, REASON_LIMIT, null, 0L))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        params = paramsOf(intent)
        val container = (application as FocusApp).container
        setContent {
            // Same theme plumbing as MainActivity: without it the overlay
            // ignored both the light/dark choice and the accent, and every
            // Text fell back to LocalContentColor's default black.
            val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val darkTheme = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            val accentIdx by container.settings.iconAccent.collectAsState(initial = 0)
            CompositionLocalProvider(LocalAccentColor provides FgColors.accentColor(accentIdx)) {
                FocusGuardTheme(darkTheme = darkTheme, accentIndex = accentIdx) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val p = params
                        BlockContent(
                            pkg = p.pkg,
                            reason = p.reason,
                            domain = p.domain,
                            untilMs = p.untilMs
                        )
                    }
                }
            }
        }
    }

    // CLEAR_TOP reuses a live (paused) instance instead of creating one, so the
    // new target/reason must be applied here or the overlay shows stale data.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        params = paramsOf(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }
}

@Composable
private fun BlockContent(pkg: String?, reason: String, domain: String?, untilMs: Long) {
    val context = LocalContext.current
    val container = (context.applicationContext as FocusApp).container
    val label = remember(pkg) { pkg?.let { appLabel(context, it) } ?: "FocusGuard" }

    val title = when (reason) {
        BlockOverlayActivity.REASON_WEBSITE -> "Site blocked"
        BlockOverlayActivity.REASON_BLOCK_ALL -> "Focus session"
        BlockOverlayActivity.REASON_SCHEDULE -> "Focus time"
        BlockOverlayActivity.REASON_COOLDOWN -> "Session over"
        else -> "Daily limit reached"
    }
    val subtitle = when (reason) {
        BlockOverlayActivity.REASON_WEBSITE -> "$domain is on your blocklist."
        BlockOverlayActivity.REASON_BLOCK_ALL -> "You started a blocking session. Use the time for something better."
        BlockOverlayActivity.REASON_SCHEDULE -> "A focus schedule is active right now."
        BlockOverlayActivity.REASON_COOLDOWN ->
            "$label's session is used up. It's taking a break."
        else -> "$label has used up its allowed time for today."
    }

    var unlockAt by remember(reason, untilMs) { mutableStateOf<Long?>(null) }
    LaunchedEffect(reason, untilMs) {
        unlockAt = if (untilMs > 0L) untilMs else when (reason) {
            BlockOverlayActivity.REASON_BLOCK_ALL -> container.enforcement.blockAllUntil.value
            BlockOverlayActivity.REASON_SCHEDULE ->
                container.enforcement.activeScheduleAt(System.currentTimeMillis())
                    ?.let { scheduleEndMillis(it, System.currentTimeMillis()) }
            BlockOverlayActivity.REASON_WEBSITE -> nextMidnightMillis()
            else -> nextMidnightMillis()
        }
    }

    // Ending a focus session early asks for no code. The gate is patience
    // plus a daily allowance, both set in Settings → Focus Tools →
    // "Unlock delay & quota": the button stays disabled for the delay, and
    // each early end spends one of the day's unlocks.
    val scope = rememberCoroutineScope()
    val resetHour by container.settings.resetHour.collectAsState(initial = 0)
    val delaySec by container.settings.unlockDelaySec.collectAsState(initial = 0)
    val quota by container.settings.unlockQuota.collectAsState(initial = 3)
    val usedDay by container.settings.unlockUsedDay.collectAsState(initial = "")
    val usedCount by container.settings.unlockUsedCount.collectAsState(initial = 0)

    val now = System.currentTimeMillis()
    val usedToday = if (usedDay == ResetEngine.dayKey(now, resetHour)) usedCount else 0
    val remaining = if (quota <= 0) Int.MAX_VALUE else (quota - usedToday).coerceAtLeast(0)
    val canEndEarly = reason == BlockOverlayActivity.REASON_BLOCK_ALL && remaining > 0

    // Battery-safe: one suspended delay, no ticking countdown.
    var delayDone by remember(delaySec) { mutableStateOf(delaySec <= 0) }
    LaunchedEffect(delaySec, canEndEarly) {
        if (canEndEarly && delaySec > 0) {
            delay(delaySec * 1000L)
            delayDone = true
        }
    }

    fun endSessionEarly() {
        val (dayKey, count) = UnlockEngine.recordUnlock(
            System.currentTimeMillis(), usedDay, usedCount, quota, resetHour
        )
        // container.scope outlives this Activity, so the write cannot be lost
        // to the finish() below.
        container.scope.launch {
            container.settings.recordUnlock(dayKey, count)
            container.settings.setBlockAllUntil(0L)
        }
        Notifier.cancelFocusSession(context)
        (context as? android.app.Activity)?.finish()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            // Show the blocked app's icon where we know it, otherwise the
            // custom block glyph — never a generic Material icon.
            if (pkg != null && reason != BlockOverlayActivity.REASON_BLOCK_ALL) {
                AppIcon(pkg, size = 72.dp)
            } else {
                FgIcon(
                    FgIcons.Block,
                    null,
                    size = 52.dp,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        unlockAt?.let { at ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Available again at ${formatClockLocal(at)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                (context as? android.app.Activity)?.finishAffinity()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Go to home screen") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                val intent = Intent(context, dev.opensource.focusguard.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                (context as? android.app.Activity)?.finish()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open FocusGuard") }
        if (canEndEarly) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { endSessionEarly() },
                enabled = delayDone,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (delayDone) "End focus session early" else "One moment\u2026") }
            if (quota > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ends the session and uses one of your $remaining unlocks today.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun nextMidnightMillis(): Long =
    LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun scheduleEndMillis(schedule: FocusSchedule, now: Long): Long {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    var candidate = today.atStartOfDay(zone).plusMinutes(schedule.endMinute.toLong())
        .toInstant().toEpochMilli()
    if (candidate <= now) {
        candidate = today.plusDays(1).atStartOfDay(zone).plusMinutes(schedule.endMinute.toLong())
            .toInstant().toEpochMilli()
    }
    return candidate
}

private fun formatClockLocal(epochMillis: Long): String {
    val zdt = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return "%02d:%02d".format(zdt.hour, zdt.minute)
}
