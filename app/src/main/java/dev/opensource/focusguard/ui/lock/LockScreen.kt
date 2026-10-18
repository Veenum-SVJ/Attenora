package dev.opensource.focusguard.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.opensource.focusguard.security.PinManager
import dev.opensource.focusguard.ui.FocusGuardApp
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.icons.FgIcons
import kotlinx.coroutines.launch

/**
 * Gates the whole app behind the PIN/biometric lock when one is configured.
 * "" as the initial value means "still loading from DataStore" — during that
 * window we show a spinner rather than accidentally unlocked content.
 */
@Composable
fun LockGate(unlocked: MutableState<Boolean>, onUnlock: () -> Unit) {
    val container = LocalAppContainer.current
    val pinHash by container.settings.pinHash.collectAsState(initial = "")
    val biometricOn by container.settings.biometricEnabled.collectAsState(initial = true)

    when {
        pinHash == "" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        pinHash == null -> FocusGuardApp()
        unlocked.value -> FocusGuardApp()
        else -> LockScreen(expectedHash = pinHash!!, biometricOn = biometricOn, onUnlock = onUnlock)
    }
}

@Composable
private fun LockScreen(expectedHash: String, biometricOn: Boolean, onUnlock: () -> Unit) {
    val container = LocalAppContainer.current
    val activity = LocalContext.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    var pin by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (biometricOn && activity != null) {
            promptBiometric(activity) { ok -> if (ok) onUnlock() }
        }
    }

    fun submit(candidate: String) {
        scope.launch {
            val salt = container.settings.getPinSalt()
            if (salt != null && PinManager.matches(candidate, salt, expectedHash)) {
                onUnlock()
            } else {
                error = true
                pin = ""
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        FgIcon(
            FgIcons.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            size = 48.dp
        )
        Spacer(Modifier.height(16.dp))
        Text("Enter your PIN", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (error) "Wrong PIN, try again" else "FocusGuard is locked",
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .size(if (i < pin.length) 14.dp else 10.dp)
                        .clip(CircleShape)
                        .background(
                            if (i < pin.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        val rows = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
        for (chunk in rows.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (key in chunk) {
                    if (key.isEmpty()) {
                        Spacer(Modifier.size(72.dp))
                    } else {
                        OutlinedButton(
                            onClick = {
                                error = false
                                when (key) {
                                    "<" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                    else -> {
                                        if (pin.length < 8) {
                                            pin += key
                                            if (pin.length >= 4) submit(pin)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(72.dp)
                        ) {
                            Text(key, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (biometricOn) {
            TextButton(onClick = {
                activity?.let { a -> promptBiometric(a) { ok -> if (ok) onUnlock() } }
            }) {
                FgIcon(FgIcons.User, contentDescription = null, size = 20.dp)
                Spacer(Modifier.size(8.dp))
                Text("Unlock with biometrics")
            }
        }
    }
}

private fun promptBiometric(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
    val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(authenticators) !=
        BiometricManager.BIOMETRIC_SUCCESS
    ) return

    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true)
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock FocusGuard")
        .setAllowedAuthenticators(authenticators)
        .build()
    prompt.authenticate(info)
}
