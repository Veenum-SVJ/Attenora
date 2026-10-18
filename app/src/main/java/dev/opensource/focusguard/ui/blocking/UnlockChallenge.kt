package dev.opensource.focusguard.ui.blocking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.domain.ChallengeEngine
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Unlock challenge shown before a blocked app gets more time (Curbox port).
 *
 * Types: `math` (solve arithmetic), `quote` (retype the sentence),
 * `intent` (state why you are unlocking — stored by the caller). The dialog
 * only gates: [onPass] receives the typed intent ("" for the other types) and
 * is responsible for persisting the unlock and granting time. Cancel/Back
 * never grants — the caller decides what "bail out" means.
 */
@Composable
fun UnlockChallengeDialog(
    type: String,
    lastIntent: String,
    onPass: suspend (intent: String) -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val quote = remember { ChallengeEngine.pickQuote(Random(System.nanoTime())) }
    val problem = remember { ChallengeEngine.randomMath(Random(System.nanoTime())) }

    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val isMath = type == "math"
    val isIntent = type == "intent"
    val shown = when {
        isMath -> problem.prompt()
        isIntent -> "Write why you're unlocking, then confirm."
        else -> quote
    }
    val minIntent = 8
    val canConfirm = !busy && when {
        isMath -> input.isNotBlank()
        isIntent -> input.trim().length >= minIntent
        else -> input.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = {
            Text(
                when {
                    isMath -> "Solve to unlock"
                    isIntent -> "State your intent"
                    else -> "Type to unlock"
                },
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                if (isIntent && lastIntent.isNotBlank()) {
                    Text(
                        "Last time you wrote: \u201C$lastIntent\u201D",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    shown,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = false },
                    singleLine = !isIntent,
                    minLines = if (isIntent) 3 else 1,
                    isError = error,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isMath) KeyboardType.Number else KeyboardType.Text
                    ),
                    supportingText = if (error) {
                        {
                            Text(
                                if (isMath) "Not quite — try again" else "It doesn't match yet"
                            )
                        }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!isMath) {
                    Text(
                        if (isIntent) "Stays on this phone only — shown back to you next time."
                        else "This pause is intentional — retype the sentence above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = {
                val ok = when {
                    isMath -> ChallengeEngine.checkMath(input, problem)
                    isIntent -> input.trim().length >= minIntent
                    else -> ChallengeEngine.matchesQuote(input, quote)
                }
                if (!ok) {
                    error = true
                    if (!isIntent && !isMath) input = ""
                } else {
                    busy = true
                    val text = input.trim().take(200)
                    scope.launch {
                        val result = runCatching { onPass(text) }
                        // Only a failed grant re-enables the dialog; success
                        // finishes the prompt / clears the overlay state.
                        if (result.isFailure) busy = false
                    }
                }
            }) { Text("Unlock") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onCancel) { Text("Not now") }
        }
    )
}