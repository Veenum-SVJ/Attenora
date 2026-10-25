package dev.opensource.attenora.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.opensource.attenora.AttenoraApplication
import dev.opensource.attenora.data.ThemeMode
import dev.opensource.attenora.ui.common.LocalAccentColor
import dev.opensource.attenora.ui.common.LocalAppContainer
import dev.opensource.attenora.ui.theme.FgColors
import dev.opensource.attenora.ui.theme.AttenoraTheme

/**
 * The app itself. There is no lock screen: Attenora opens straight into
 * your stats. A challenge still exists, but only as one of the ways to get
 * past a *limit* change (Settings -> Usage Limits and Tracking -> Limit
 * protection): a math sum, or a passage you retype.
 */
class MainActivity : ComponentActivity() {

    private var firstFrameLogged = false
    private val startT0 = android.os.SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val t0 = android.os.SystemClock.elapsedRealtime()
        enableEdgeToEdge()
        val container = (application as AttenoraApplication).container
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
                    AttenoraTheme(darkTheme = darkTheme, accentIndex = accentIdx) {
                        // Paint the themed background edge-to-edge so onboarding
                        // (which sits outside the app Scaffold) doesn't fall
                        // through to the system window background.
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            AttenoraApp()
                        }
                    }
                }
            }
        }
    }
}
