package dev.opensource.focusguard.enforcement

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.FocusApp
import dev.opensource.focusguard.data.SessionGrant
import dev.opensource.focusguard.ui.blocking.BlockOverlayActivity
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.theme.FocusGuardTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * "How long do you want to use this app?" — the variable session limit prompt.
 * Choosing a duration grants a session window; backing out blocks the app, so
 * the limit cannot be bypassed by simply dismissing the dialog.
 */
class SessionPromptActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_PKG = "fg_pkg"
        private const val EXTRA_CHOICES = "fg_choices"

        fun launch(context: Context, pkg: String, choices: IntArray) {
            context.startActivity(
                Intent(context, SessionPromptActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    .putExtra(EXTRA_PKG, pkg)
                    .putExtra(EXTRA_CHOICES, choices)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: run { finish(); return }
        val choices = intent.getIntArrayExtra(EXTRA_CHOICES) ?: intArrayOf(15, 30, 60, 1440)
        // Dismissing without a choice = blocked: back press routes into the block overlay.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                BlockOverlayActivity.launch(this@SessionPromptActivity, pkg, BlockOverlayActivity.REASON_LIMIT)
                finish()
            }
        })
        setContent {
            FocusGuardTheme {
                PromptContent(pkg = pkg, choices = choices)
            }
        }
    }
}

@Composable
private fun PromptContent(pkg: String, choices: IntArray) {
    val context = LocalContext.current
    val activity = context as? SessionPromptActivity
    val container = (context.applicationContext as FocusApp).container
    val scope = rememberCoroutineScope()
    val label = remember(pkg) { appLabel(context, pkg) }

    fun choose(minutes: Int) {
        scope.launch {
            val now = System.currentTimeMillis()
            val until = if (minutes >= 1440) {
                endOfUsageDay(container, now)
            } else {
                now + minutes * 60_000L
            }
            container.db.sessionGrantDao().upsert(SessionGrant(pkg, until))
            activity?.finish()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "How long do you want to use $label?",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick a session length — the app re-locks when time runs out.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        for (minutes in choices) {
            Button(
                onClick = { choose(minutes) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(choiceLabel(minutes), style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = {
                BlockOverlayActivity.launch(context, pkg, BlockOverlayActivity.REASON_LIMIT)
                activity?.finish()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Not now — block me")
        }
    }
}

private fun choiceLabel(minutes: Int): String = when {
    minutes >= 1440 -> "Until tomorrow"
    minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60} hour" + if (minutes / 60 > 1) "s" else ""
    else -> "$minutes minutes"
}

private suspend fun endOfUsageDay(container: dev.opensource.focusguard.AppContainer, now: Long): Long {
    val resetHour = container.settings.resetHour.first()
    val zone = ZoneId.systemDefault()
    val usageDate = dev.opensource.focusguard.domain.ResetEngine.instantToDate(now, resetHour, zone)
    return usageDate.plusDays(1)
        .atStartOfDay(zone)
        .plusHours(resetHour.toLong())
        .toInstant()
        .toEpochMilli()
        .coerceAtLeast(now + 60_000L)
}
