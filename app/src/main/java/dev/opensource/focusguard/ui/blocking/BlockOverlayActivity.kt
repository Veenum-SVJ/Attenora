package dev.opensource.focusguard.ui.blocking

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.FocusApp
import dev.opensource.focusguard.data.FocusSchedule
import dev.opensource.focusguard.data.UnlockChallenge
import dev.opensource.focusguard.enforcement.Notifier
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.icons.FgIcons
import dev.opensource.focusguard.ui.theme.FocusGuardTheme
import kotlinx.coroutines.flow.first
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

        const val REASON_LIMIT = "limit"
        const val REASON_SCHEDULE = "schedule"
        const val REASON_BLOCK_ALL = "blockall"
        const val REASON_WEBSITE = "website"

        @Volatile
        var visible = false
            private set

        fun launch(context: Context, pkg: String?, reason: String, domain: String? = null) {
            val intent = Intent(context, BlockOverlayActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                .putExtra(EXTRA_PACKAGE, pkg)
                .putExtra(EXTRA_REASON, reason)
                .putExtra(EXTRA_DOMAIN, domain)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_LIMIT
        val domain = intent.getStringExtra(EXTRA_DOMAIN)
        setContent {
            FocusGuardTheme {
                BlockContent(pkg = pkg, reason = reason, domain = domain)
            }
        }
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
private fun BlockContent(pkg: String?, reason: String, domain: String?) {
    val context = LocalContext.current
    val container = (context.applicationContext as FocusApp).container
    val label = remember(pkg) { pkg?.let { appLabel(context, it) } ?: "FocusGuard" }

    val title = when (reason) {
        BlockOverlayActivity.REASON_WEBSITE -> "Site blocked"
        BlockOverlayActivity.REASON_BLOCK_ALL -> "Focus session"
        BlockOverlayActivity.REASON_SCHEDULE -> "Focus time"
        else -> "Daily limit reached"
    }
    val subtitle = when (reason) {
        BlockOverlayActivity.REASON_WEBSITE -> "$domain is on your blocklist."
        BlockOverlayActivity.REASON_BLOCK_ALL -> "You started a blocking session. Use the time for something better."
        BlockOverlayActivity.REASON_SCHEDULE -> "A focus schedule is active right now."
        else -> "$label has used up its allowed time for today."
    }

    val unlockAt by produceState<Long?>(initialValue = null) {
        value = when (reason) {
            BlockOverlayActivity.REASON_BLOCK_ALL -> container.enforcement.blockAllUntil.value
            BlockOverlayActivity.REASON_SCHEDULE ->
                container.enforcement.activeScheduleAt(System.currentTimeMillis())
                    ?.let { scheduleEndMillis(it, System.currentTimeMillis()) }
            BlockOverlayActivity.REASON_WEBSITE -> nextMidnightMillis()
            else -> nextMidnightMillis()
        }
    }

    // What it takes to end an active focus session early (Curbox-style):
    // only offered when an unlock challenge is configured — otherwise the
    // overlay stays a hard stop, same as before.
    val unlockType by produceState<String?>(initialValue = null) {
        value = container.settings.unlockChallenge.first()
    }
    val intentLast by produceState(initialValue = "") {
        value = container.settings.intentLast.first()
    }
    var showEndChallenge by remember { mutableStateOf(false) }
    val canEndEarly = reason == BlockOverlayActivity.REASON_BLOCK_ALL &&
        unlockType != null &&
        unlockType != UnlockChallenge.OFF &&
        unlockType != UnlockChallenge.NONE

    if (showEndChallenge && unlockType != null) {
        UnlockChallengeDialog(
            type = unlockType!!,
            lastIntent = intentLast,
            onPass = { intent ->
                if (intent.isNotBlank()) {
                    container.settings.setIntent(intent, System.currentTimeMillis())
                }
                container.settings.setBlockAllUntil(0L)
                Notifier.cancelFocusSession(context)
                showEndChallenge = false
            },
            onCancel = { showEndChallenge = false }
        )
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
                onClick = { showEndChallenge = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("End focus session early…") }
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
