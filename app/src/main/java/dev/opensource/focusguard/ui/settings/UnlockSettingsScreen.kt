package dev.opensource.focusguard.ui.settings

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.UnlockChallenge
import dev.opensource.focusguard.domain.ResetEngine
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.SectionCard
import dev.opensource.focusguard.ui.theme.FgColors
import kotlinx.coroutines.launch

/**
 * Settings → Unlock Challenge (Curbox port): what the block prompt demands
 * before granting more time — delay, challenge type, and a daily quota.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnlockSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val challenge by container.settings.unlockChallenge.collectAsState(initial = UnlockChallenge.OFF)
    val delaySec by container.settings.unlockDelaySec.collectAsState(initial = 0)
    val quota by container.settings.unlockQuota.collectAsState(initial = 3)
    val usedDay by container.settings.unlockUsedDay.collectAsState(initial = "")
    val usedCount by container.settings.unlockUsedCount.collectAsState(initial = 0)
    val resetHour by container.settings.resetHour.collectAsState(initial = 0)
    val intentLast by container.settings.intentLast.collectAsState(initial = "")

    val today = remember(resetHour) { ResetEngine.dayKey(System.currentTimeMillis(), resetHour) }
    val usedToday = if (usedDay == today) usedCount else 0

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        PageHeader(
            "Unlock Challenge",
            onBack = onBack,
            subtitle = "What it takes to get more time on a blocked app"
        )

        Spacer(Modifier.height(8.dp))

        SectionCard("The challenge") {
            Text(
                "Shown when the prompt offers extra time. \"Stay blocked\" never offers any.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Chip("Off", challenge == UnlockChallenge.OFF) {
                    scope.launch { container.settings.setUnlockChallenge(UnlockChallenge.OFF) }
                }
                Chip("Stay blocked", challenge == UnlockChallenge.NONE) {
                    scope.launch { container.settings.setUnlockChallenge(UnlockChallenge.NONE) }
                }
                Chip("Math", challenge == UnlockChallenge.MATH) {
                    scope.launch { container.settings.setUnlockChallenge(UnlockChallenge.MATH) }
                }
                Chip("Type the sentence", challenge == UnlockChallenge.QUOTE) {
                    scope.launch { container.settings.setUnlockChallenge(UnlockChallenge.QUOTE) }
                }
                Chip("State your intent", challenge == UnlockChallenge.INTENT) {
                    scope.launch { container.settings.setUnlockChallenge(UnlockChallenge.INTENT) }
                }
            }
            if (challenge == UnlockChallenge.INTENT && intentLast.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your last intent: \u201C$intentLast\u201D",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard("Delay before choosing") {
            Text(
                "The session buttons stay disabled for a moment after the prompt appears — " +
                    "a small gap between the urge and the scroll.",
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
                "How many times per usage day you may unlock. Once they're gone, " +
                    "the app stays blocked until your usage day resets.",
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

/** Small pill selector matching the Appearance screen's chips. */
@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) FgColors.Lavender else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Color(0xFF17131F) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}