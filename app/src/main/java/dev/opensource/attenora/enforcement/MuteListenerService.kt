package dev.opensource.attenora.enforcement

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.opensource.attenora.AttenoraApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * "Block Notifications" (optional special access). While a package is
 * currently blocked by a limit/schedule, its notifications are cancelled.
 * Nothing else is read, stored, or sent anywhere — Attenora has no network.
 */
class MuteListenerService : NotificationListenerService() {

    private val container get() = (application as AttenoraApplication).container

    /** Tiny verdict cache: pkg -> (blockedAt, blocked). Avoids DB work per post. */
    private val cache = HashMap<String, LongArray>(8)

    override fun onListenerConnected() {
        super.onListenerConnected()
        container.muteListenerConnected = true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (pkg == packageName) return
        val now = System.currentTimeMillis()
        container.scope.launch {
            if (!container.settings.muteBlocked.first()) return@launch

            val cached = cache[pkg]
            val blocked = if (cached != null && now - cached[0] < CACHE_TTL_MS) {
                cached[1] == 1L
            } else {
                val decision = container.enforcement.decisionFor(pkg, now)
                cache[pkg] = longArrayOf(now, if (decision.blocked) 1L else 0L)
                decision.blocked
            }

            if (blocked) {
                runCatching { cancelNotification(sbn.key) }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    override fun onListenerDisconnected() {
        container.muteListenerConnected = false
        super.onListenerDisconnected()
    }

    companion object {
        private const val CACHE_TTL_MS = 10_000L
    }
}
