package dev.opensource.focusguard.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import dev.opensource.focusguard.FocusApp
import dev.opensource.focusguard.data.ThemeMode
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.lock.LockGate
import dev.opensource.focusguard.ui.theme.FocusGuardTheme

class MainActivity : FragmentActivity() {

    private val unlocked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FocusApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
                val darkTheme = when (themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    else -> isSystemInDarkTheme()
                }
                FocusGuardTheme(darkTheme = darkTheme) {
                    LockGate(unlocked = unlocked, onUnlock = { unlocked.value = true })
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Re-lock as soon as the app leaves the foreground.
        unlocked.value = false
    }
}
