package dev.opensource.attenora.ui.limits

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opensource.attenora.data.ChallengeMode
import dev.opensource.attenora.security.PinManager
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.domain.ChallengeEngine
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Gate for sensitive actions (limit edits, blocking toggles, PIN removal,
 * wipe). Usage:
 *
 * ```
 * val gate = rememberChallengeGate()
 * gate.request { doSensitiveThing() }
 * ```
 *
 * Behavior follows Settings → Lock Usage Limits: No Protection runs the
 * action directly; PIN Protection verifies the PIN; Require a Challenge
 * shows the awareness quote (or math) dialog.
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
    val pinHash by container.settings.pinHash.collectAsState(initial = "")

    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val gate = remember { ChallengeGateState({ action -> pending = action }) }

    val action = pending
    if (action != null) {
        when {
            // Still loading the real mode from DataStore — hold, don't leak a bypass.
            mode == "" -> AlertDialog(
                onDismissRequest = { pending = null },
                confirmButton = {},
                text = { Text("Checking lock settings…") }
            )

            mode == ChallengeMode.NONE || (mode == ChallengeMode.PIN && pinHash == null) -> {
                LaunchedEffect(action) { pending = null; action() }
            }

            mode == ChallengeMode.PIN -> PinChallenge(
                expectedHash = pinHash!!,
                onSuccess = { pending = null; action() },
                onCancel = { pending = null }
            )

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
private fun PinChallenge(expectedHash: String, onSuccess: () -> Unit, onCancel: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enter your PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (it.length <= 8) { pin = it; error = false } },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error,
                    supportingText = if (error) {
                        { Text("Wrong PIN") }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4, onClick = {
                scope.launch {
                    val salt = container.settings.getPinSalt()
                    if (salt != null && PinManager.matches(pin, salt, expectedHash)) onSuccess()
                    else { error = true; pin = "" }
                }
            }) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
private fun AwarenessChallenge(type: String, onSuccess: () -> Unit, onCancel: () -> Unit) {
    val quote = remember { ChallengeEngine.pickQuote(Random(System.nanoTime())) }
    val problem = remember { ChallengeEngine.randomMath(Random(System.nanoTime())) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    val isMath = type == "math"

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (isMath) "Solve to continue" else "Type the quote",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
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
                    Text(
                        "— ${quote.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    singleLine = true,
                    isError = error,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isMath) KeyboardType.Number else KeyboardType.Text
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
