package dev.opensource.focusguard.util

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * App-scoped grayscale filter (Curbox port, same mechanism it documents):
 * Android's color-correction "daltonizer" forced to monochrome mode.
 *
 * Reading the state needs no permission; writing needs WRITE_SECURE_SETTINGS,
 * granted once with:
 *   adb shell pm grant <package> android.permission.WRITE_SECURE_SETTINGS
 */
object Grayscale {

    private const val TAG = "FgGrayscale"
    private const val DALTONIZER = "accessibility_display_daltonizer"
    private const val DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MONOCHROME_MODE = 0

    fun hasPermission(context: Context): Boolean = runCatching {
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Current monochrome state (no permission needed to read). */
    fun isEnabled(context: Context): Boolean = runCatching {
        Settings.Secure.getInt(context.contentResolver, DALTONIZER_ENABLED, 0) == 1
    }.getOrDefault(false)

    /** Apply/remove the grayscale filter. Returns false when the write failed. */
    fun set(context: Context, on: Boolean): Boolean = try {
        val resolver = context.contentResolver
        if (on) {
            Settings.Secure.putInt(resolver, DALTONIZER, MONOCHROME_MODE)
            Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, 1)
        } else {
            Settings.Secure.putInt(resolver, DALTONIZER_ENABLED, 0)
        }
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "WRITE_SECURE_SETTINGS missing — grayscale not applied", e)
        false
    } catch (e: RuntimeException) {
        Log.w(TAG, "grayscale write failed", e)
        false
    }
}