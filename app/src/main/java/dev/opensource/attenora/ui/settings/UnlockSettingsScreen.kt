package dev.opensource.attenora.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opensource.attenora.domain.ResetEngine
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.ui.common.PageHeader
import dev.opensource.attenora.ui.common.SectionCard
import dev.opensource.attenora.ui.theme.FgColors
import kotlinx.coroutines.launch

/**
 * Settings → Focus Tools → "Unlock delay & quota". Ending a focus session
 * early never asks for a code: it costs one of the day's unlocks, and the
 * button only becomes tappable once the delay has run out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnlockSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val delaySec by container.settings.unlockDelaySec.collectAsState(initial = 0)
    val quota by container.settings.unlockQuota.collectAsState(initial = 3)
    val usedDay by container.settings.unlockUsedDay.collectAsState(initial = "")
    val usedCount by container.settings.unlockUsedCount.collectAsState(initial = 0)
    val resetHour by container.settings.resetHour.collectAsState(initial = 0)

    val today = remember(resetHour) { ResetEngine.dayKey(System.currentTimeMillis(), resetHour) }
    val usedToday = if (usedDay == today) usedCount else 0

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        PageHeader(
            "Unlock delay & quota",
            onBack = onBack,
            subtitle = "A pause and a daily allowance before a focus session ends early"
        )

        Spacer(Modifier.height(8.dp))

        SectionCard("Delay before choosing") {
            Text(
                "\"End focus session early\" stays greyed out for a moment after the " +
                    "block appears — a small gap between the urge and the scroll.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0 to "Off", 5 to "5s", 10 to "10s", 30 to "30s", 60 to "60s")
                    .forEach { (sec, label) ->
                        Chip(label, delaySec == sec) {
                            scope.launch { container.settings.setUnlockDelay(sec) }
                        }
                    }
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard("Daily unlock quota") {
            Text(
                "How many times per usage day you may end a session early. Once they're " +
                    "gone, the session runs to its end and the block returns " +
                    "to being a hard stop until your usage day resets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0 to "Unlimited", 1 to "1 / day", 3 to "3 / day", 5 to "5 / day")
                    .forEach { (q, label) ->
                        Chip(label, quota == q) {
                            scope.launch { container.settings.setUnlockQuota(q) }
                        }
                    }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (quota <= 0) "Unlocks used today: $usedToday (unlimited)"
                else "Unlocks used today: $usedToday / $quota",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/** Pill selector sized to the 48dp minimum so the target is the pill itself. */
@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (active) FgColors.AttenoraTeal else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Color(0xFF17131F) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}