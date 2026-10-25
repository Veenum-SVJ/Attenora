package dev.opensource.attenora.ui.limits

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opensource.attenora.data.ChallengeMode
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.domain.ChallengeEngine
import kotlin.random.Random

/**
 * Gate for sensitive actions (limit edits, blocking toggles, wipe). Usage:
 *
 * ```
 * val gate = rememberChallengeGate()
 * gate.request { doSensitiveThing() }
 * ```
 *
 * Behavior follows Settings → Lock Usage Limits: No Protection runs the action
 * directly; Require a Challenge shows the awareness passage (or math) dialog.
 */
class ChallengeGateState internal constructor(
    internal val requestInternal: (()-> Unit) -> Unit
) {
    fun request(action: () -> Unit) = requestInternal(action)
}

@Composable
fun rememberChallengeGate(): ChallengeGateState {
    val container = LocalAppContainer.current

    val mode by container.settings.challengeMode.collectAsState(initial = "")
    val type by container.settings.challengeType.collectAsState(initial = "quote")

    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val gate = remember { ChallengeGateState({ action -> pending = action }) }

    val action = pending
    if (action != null) {
        when (mode) {
            // Still loading the real mode from DataStore — hold, don't leak a bypass.
            "" -> AlertDialog(
                onDismissRequest = { pending = null },
                confirmButton = {},
                text = { Text("Checking lock settings…") }
            )

            ChallengeMode.NONE -> {
                LaunchedEffect(action) { pending = null; action() }
            }

            else -> AwarenessChallenge(
                type = type,
                onSuccess = { pending = null; action() },
                onCancel = { pending = null }
            )
        }
    }

    return gate
}

@Composable
private fun AwarenessChallenge(type: String, onSuccess: () -> Unit, onCancel: () -> Unit) {
    // One seed per challenge, saved across configuration changes: showing the
    // keyboard resizes the window, and a re-picked passage (or a re-picked sum)
    // would strand whatever the user had already typed.
    val seed = rememberSaveable { System.nanoTime() }
    val quote = remember(seed) { ChallengeEngine.pickQuote(Random(seed)) }
    val problem = remember(seed) { ChallengeEngine.randomMath(Random(seed)) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf(false) }

    val isMath = type == "math"

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (isMath) "Solve to continue" else "Type the passage",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            // A passage plus a growing field can outrun a small screen's
            // dialog height, so the whole challenge scrolls if it must.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (isMath) {
                    Text(
                        problem.prompt(),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                } else {
                    Text(
                        quote.text,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 6.dp)
                    )
                    if (quote.source.isNotBlank()) {
                        Text(
                            "— ${quote.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        "Type it exactly. Capitals and punctuation count.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = false },
                    // The sum is one number; the passage is a paragraph, so the
                    // passage gets a tall, growing box that keeps every typed
                    // line visible instead of one line scrolling sideways.
                    singleLine = isMath,
                    minLines = if (isMath) 1 else 4,
                    maxLines = if (isMath) 1 else 8,
                    isError = error,
                    // The lambda must sit directly in the parameter slot: an
                    // if-expression wrapping it loses @Composable inference.
                    placeholder = {
                        if (!isMath) Text("Type the passage here")
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isMath) KeyboardType.Number else KeyboardType.Text,
                        // Suggestions rewrite what was typed; the check is exact.
                        autoCorrectEnabled = if (isMath) null else false
                    ),
                    supportingText = if (error) {
                        { Text(if (isMath) "Not quite — try again" else "It doesn't match yet") }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = input.isNotBlank(), onClick = {
                val ok = if (isMath) ChallengeEngine.checkMath(input, problem)
                else ChallengeEngine.matchesQuote(input, quote.text)
                if (ok) onSuccess() else { error = true; input = "" }
            }) { Text("Confirm") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

/** Small helper for gates used inside rows: run [block] through the gate. */
@Composable
fun gated(gate: ChallengeGateState, block: () -> Unit): () -> Unit = {
    gate.request(block)
}
