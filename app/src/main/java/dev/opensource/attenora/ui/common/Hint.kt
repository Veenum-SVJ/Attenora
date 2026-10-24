package dev.opensource.attenora.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.opensource.attenora.ui.icons.FgIcons
import kotlinx.coroutines.launch

/**
 * One line of "here is what this screen is for", shown the first time you open
 * a screen and never again once you close it.
 *
 * This is deliberately not a walkthrough: no coach marks, no spotlight, no
 * sequence of steps. One card, one close button, and the screen is yours.
 * Dismissal is recorded in DataStore under [id], so it survives restarts.
 */
@Composable
fun FirstRunHint(id: String, text: String, modifier: Modifier = Modifier) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val seen by container.settings.hintsSeen.collectAsState(initial = emptySet())
    if (id in seen) return

    FgCard(modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FgIcon(
                FgIcons.Lightbulb,
                null,
                size = 20.dp,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { scope.launch { container.settings.markHintSeen(id) } }) {
                FgIcon(
                    FgIcons.Close,
                    "Dismiss this hint",
                    size = 18.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
