package dev.opensource.focusguard.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import dev.opensource.focusguard.FocusApp
import dev.opensource.focusguard.data.ThemeMode
import dev.opensource.focusguard.ui.common.LocalAccentColor
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.theme.FgColors
import dev.opensource.focusguard.ui.lock.LockGate
import dev.opensource.focusguard.ui.theme.FocusGuardTheme

class MainActivity : FragmentActivity() {

    private val unlocked = mutableStateOf(false)
    private var firstFrameLogged = false
    private val startT0 = android.os.SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t0 = android.os.SystemClock.elapsedRealtime()
        enableEdgeToEdge()
        val container = (application as FocusApp).container
        android.util.Log.d("FGSTART", "activity onCreate: ${android.os.SystemClock.elapsedRealtime() - t0}ms")
        setContent {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                androidx.compose.runtime.withFrameNanos { }
                android.util.Log.d(
                    "FGSTART",
                    "first frame drawn: ${android.os.SystemClock.elapsedRealtime() - startT0}ms"
                )
            }
            androidx.compose.runtime.SideEffect {
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    android.util.Log.d(
                        "FGSTART",
                        "first composition: ${android.os.SystemClock.elapsedRealtime() - startT0}ms"
                    )
                }
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                val themeMode by container.settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
                val darkTheme = when (themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    else -> isSystemInDarkTheme()
                }
                val accentIdx by container.settings.iconAccent.collectAsState(initial = 0)

                CompositionLocalProvider(
                    LocalAccentColor provides FgColors.accentColor(accentIdx)
                ) {
                    FocusGuardTheme(darkTheme = darkTheme, accentIndex = accentIdx) {
                        // Paint the themed background edge-to-edge so onboarding
                        // and the lock screen (which sit outside the app Scaffold)
                        // don't fall through to the dark system window background.
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            LockGate(unlocked = unlocked, onUnlock = { unlocked.value = true })
                        }
                    }
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
