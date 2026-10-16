package dev.opensource.focusguard.enforcement

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.opensource.focusguard.FocusApp
import dev.opensource.focusguard.domain.UrlMatcher
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Optional website blocking + website visit attribution. Only acts while the
 * user granted the second accessibility service. It extracts the address
 * shown by known browser apps (bounded traversal, throttled) and:
 *  - credits foreground time per domain (for website daily limits),
 *  - matches the host against the local blocklist,
 *  - enforces website daily limits,
 *  - matches block keywords against the address/title text.
 * No other app's contents are ever read.
 */
class WebBlockService : AccessibilityService() {

    private val container get() = (application as FocusApp).container

    private var lastCheckAt = 0L

    /** Attribution state: which domain is showing and when we last credited it. */
    private var lastDomain: String? = null
    private var lastCreditAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        container.webMonitorConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in BROWSERS) return

        val now = System.currentTimeMillis()
        if (now - lastCheckAt < MIN_CHECK_INTERVAL_MS) return
        lastCheckAt = now

        val raw = findAddressText() ?: return
        val host = UrlMatcher.normalizeDomain(raw) ?: return

        attribute(host, now)

        val webOn = container.webBlockingActive.value
        val enforcement = container.enforcement

        if (webOn) {
            val hit = UrlMatcher.matches(host, container.blocklist.value)
            if (hit != null) {
                block(hit, pkg)
                return
            }
        }

        container.scope.launch {
            // Keyword check across address text and the window title.
            val titleText = e.text?.joinToString(" ") ?: ""
            val keyword = enforcement.keywordHit("$raw $titleText")
            if (keyword != null) {
                block(keyword, pkg)
                return@launch
            }
            if (enforcement.domainLimitExhausted(host)) {
                block(host, pkg)
            }
        }
    }

    private fun block(target: String, pkg: String) {
        container.scope.launch { container.enforcement.blockWebsite(target, pkg) }
    }

    /**
     * Credits elapsed foreground time to the visible domain so website daily
     * limits have something to measure. Capped per step so screen-off gaps
     * never inflate attribution. Coarse but honest.
     */
    private fun attribute(host: String, now: Long) {
        val prev = lastDomain
        if (prev != host) {
            prev?.let { credit(it, now) }
            lastDomain = host
            lastCreditAt = now
            return
        }
        credit(host, now)
    }

    private fun credit(domain: String, now: Long) {
        val elapsed = (now - lastCreditAt).coerceIn(0L, MAX_CREDIT_MS)
        lastCreditAt = now
        if (elapsed < MIN_CREDIT_MS) return
        container.scope.launch { container.usage.creditWebsite(domain, elapsed, now) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        container.webMonitorConnected = false
        // Flush whatever was credited so far for the last domain.
        lastDomain?.let { domain ->
            val elapsed = (System.currentTimeMillis() - lastCreditAt).coerceIn(0L, MAX_CREDIT_MS)
            if (elapsed >= MIN_CREDIT_MS) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        container.usage.creditWebsite(domain, elapsed, System.currentTimeMillis())
                    }
                }
            }
        }
        super.onDestroy()
    }

    /**
     * Bounded traversal of the active window looking for an address-bar-like
     * EditText. Hard node cap keeps the callback cheap.
     */
    private fun findAddressText(): String? {
        val root = rootInActiveWindow ?: return null
        val candidates = ArrayList<String>(4)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.poll()
                visited++
                val cls = node.className?.toString() ?: ""
                if (cls.endsWith("EditText")) {
                    node.text?.toString()?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                    node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                    node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (_: Exception) {
            // Best effort only — never crash the accessibility pipeline.
        }
        return candidates.firstOrNull { UrlMatcher.looksLikeWebAddress(it) }
    }

    companion object {
        private const val MAX_NODES = 250
        private const val MIN_CHECK_INTERVAL_MS = 1_200L
        private const val MAX_CREDIT_MS = 60_000L
        private const val MIN_CREDIT_MS = 1_000L

        val BROWSERS = setOf(
            "com.android.chrome",
            "com.google.android.apps.chrome",
            "org.chromium.chrome",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "org.mozilla.fenix",
            "com.mozilla.firefox",
            "com.microsoft.emmx",
            "com.microsoft.browser",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.opera.gx",
            "com.brave.browser",
            "com.duckduckgo.mobile.android",
            "com.UCMobile",
            "com.uc.browser",
            "com.mi.globalbrowser",
            "com.mi.globalbrowser.minus",
            "com.android.browser",
            "com.huawei.browser",
            "com.vivo.browser",
            "com.oppo.browser",
            "com.oneplus.browser",
            "com.zhiliaoapp.musically" // TikTok's in-app browser is a common escape hatch
        )
    }
}
