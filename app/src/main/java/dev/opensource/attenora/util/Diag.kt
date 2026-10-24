package dev.opensource.attenora.util

import android.util.Log

/**
 * One log tag for every decision the enforcement stack makes.
 *
 * Release builds keep these calls (there is no `-assumenosideeffects` rule for
 * Log in proguard-rules.pro), so `adb logcat -s FGD` explains a block that did
 * not appear, a reel that was not counted, or a rule that fired on the wrong
 * screen — on the user's phone, with no debug build and no file handling.
 *
 * Lines are deliberately short and greppable: "<area> k=v k=v".
 */
object Diag {

    const val TAG = "FGD"

    fun d(message: String) {
        runCatching { Log.d(TAG, message) }
    }
}
