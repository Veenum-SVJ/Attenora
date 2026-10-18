package dev.opensource.focusguard.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Switches the app's launcher icon by enabling exactly one of the
 * `activity-alias` entries declared in AndroidManifest.xml.
 */
object LauncherIcons {

    const val COUNT = 6

    /**
     * Must match FgColors.AccentNames order (Lavender, Sky, Mint, Amber,
     * Rose, Mono) — index N picked in Appearance enables aliases[N], so any
     * other order silently shows the wrong colour on the home screen.
     */
    private val aliases = listOf(
        "dev.opensource.focusguard.ui.LauncherDefault",
        "dev.opensource.focusguard.ui.LauncherSky",
        "dev.opensource.focusguard.ui.LauncherMint",
        "dev.opensource.focusguard.ui.LauncherAmber",
        "dev.opensource.focusguard.ui.LauncherRose",
        "dev.opensource.focusguard.ui.LauncherMono"
    )

    fun apply(context: Context, index: Int) {
        val pm = context.packageManager
        val clamped = index.coerceIn(0, COUNT - 1)
        aliases.forEachIndexed { i, alias ->
            val component = ComponentName(context.packageName, alias)
            val state = if (i == clamped) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }
}
