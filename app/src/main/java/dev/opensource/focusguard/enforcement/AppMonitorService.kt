package dev.opensource.focusguard.enforcement

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.opensource.focusguard.FocusApp

/**
 * The lightweight monitor: listens ONLY to window-state changes (which app
 * came to the foreground). It never reads screen contents and does no work
 * beyond handing the package name to the enforcement manager.
 */
class AppMonitorService : AccessibilityService() {

    private val container get() = (application as FocusApp).container

    override fun onServiceConnected() {
        super.onServiceConnected()
        container.monitorConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        container.enforcement.onForeground(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        container.monitorConnected = false
        super.onDestroy()
    }
}
