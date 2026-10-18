package dev.opensource.focusguard.enforcement

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import dev.opensource.focusguard.FocusApp
import dev.opensource.focusguard.domain.Allowlist
import dev.opensource.focusguard.domain.UrlMatcher
import dev.opensource.focusguard.ui.common.formatDuration
import dev.opensource.focusguard.util.Grayscale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * The single accessibility service. Replaces the former three services so the
 * user enables FocusGuard exactly once in system accessibility settings.
 *
 * It always reports foreground app changes (screen-time accounting + blocking).
 * When a browser is foreground it additionally reads only the address bar to
 * enforce website blocking and daily limits. When an app has enabled in-app
 * element rules it hides matched elements and shows the countdown pill.
 *
 * Payloads stay cheap: package-map lookups before traversal, hard node caps,
 * and per-event throttles. No other app's content is ever read.
 */
class FocusGuardAccessibilityService : AccessibilityService() {

    private val container get() = (application as FocusApp).container

    private val mainHandler = Handler(Looper.getMainLooper())

    // --- website state
    private var lastCheckAt = 0L
    private var lastDomain: String? = null
    private var lastCreditAt = 0L

    // --- element state
    private val enabledRules = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private var rulesJob: kotlinx.coroutines.Job? = null
    private var lastDetectAt = 0L
    private var lastBackAt = 0L
    private var lastForeground: String? = null
    private val lastSeen = HashMap<String, Long>(8)

    // --- grayscale state (Curbox-style per-app monochrome filter)
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            restoreIfOurs() // never leave the display stuck monochrome behind a locked screen
        }
    }

    private var coverView: View? = null
    private var coverShown = false
    private var pillView: View? = null
    private var pillShown = false
    private var pillPkg: String? = null

    private val pillTicker: Runnable = object : Runnable {
        override fun run() {
            val pkg = pillPkg ?: return
            if (!pillShown) return
            container.scope.launch {
                val decision = container.enforcement.decisionFor(pkg)
                val remaining = remainingFor(decision)
                mainHandler.post {
                    if (!pillShown || pillPkg != pkg) return@post
                    if (decision.blocked || remaining == Long.MAX_VALUE || remaining <= 0L) {
                        hidePill()
                    } else {
                        (pillView as? TextView)?.text = formatDuration(remaining)
                        mainHandler.postDelayed(pillTicker, 1_000L)
                    }
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        container.a11yConnected = true
        rulesJob = container.scope.launch {
            container.db.elementRuleDao().observeAll().collect { rules ->
                enabledRules.value = rules.filter { it.enabled }
                    .groupBy({ it.packageName }, { it.element })
                    .mapValues { (_, keys) -> keys.toSet() }
            }
        }
        runCatching {
            registerReceiver(screenOffReceiver, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
        }
        // If the service bound after the last window event (e.g. enabled
        // mid-session from Settings), lastForeground is still null — ask the
        // window manager what's on screen right now so evaluate/grant/pill
        // don't all wait for the next app switch (emulator repro, 2026-10-18).
        val current = runCatching { rootInActiveWindow?.packageName?.toString() }
            .getOrNull()
            ?.takeIf { it != packageName }
            ?: lastForeground
        current?.let {
            container.enforcement.onForeground(it)
            updateCountdown(it)
            applyGrayscale(it)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                container.enforcement.onForeground(pkg)
                lastForeground = pkg
                updateCountdown(pkg)
                applyGrayscale(pkg)
                resetAttribution()
                runDetection(pkg)
                if (pkg in BROWSERS) checkWebsite(e, pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (enabledRules.value[pkg] != null || enabledRules.value[canonical(pkg)] != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectAt >= DETECT_THROTTLE_MS) {
                        lastDetectAt = now
                        runDetection(pkg)
                    }
                }
                if (pkg in BROWSERS) checkWebsite(e, pkg)
            }
        }
    }

    // --- website blocking ---------------------------------------------------

    private fun checkWebsite(e: AccessibilityEvent, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < MIN_CHECK_INTERVAL_MS) return
        lastCheckAt = now

        val raw = findAddressText() ?: return
        val host = UrlMatcher.normalizeDomain(raw) ?: return

        attribute(host, now)

        if (container.webBlockingActive.value) {
            val hit = UrlMatcher.matches(host, container.blocklist.value)
            if (hit != null) {
                block(hit, pkg)
                return
            }
        }

        container.scope.launch {
            val titleText = e.text?.joinToString(" ") ?: ""
            val keyword = container.enforcement.keywordHit("$raw $titleText")
            if (keyword != null) {
                block(keyword, pkg)
                return@launch
            }
            if (container.enforcement.domainLimitExhausted(host)) {
                block(host, pkg)
            }
        }
    }

    private fun block(target: String, pkg: String) {
        container.scope.launch { container.enforcement.blockWebsite(target, pkg) }
    }

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
        val elapsed = (now - lastCreditAt).coerceIn(0L, WEB_MAX_CREDIT_MS)
        lastCreditAt = now
        if (elapsed < WEB_MIN_CREDIT_MS) return
        container.scope.launch { container.usage.creditWebsite(domain, elapsed, now) }
    }

    private fun findAddressText(): String? {
        val root = rootInActiveWindow ?: return null
        val candidates = ArrayList<String>(4)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES_ADDRESS) {
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

    // --- element blocking ---------------------------------------------------

    private fun canonical(pkg: String): String = when (pkg) {
        "com.ss.android.ugc.trill" -> "com.zhiliaoapp.musically"
        "app.revanced.android.youtube", "com.vanced.android.youtube" -> "com.google.android.youtube"
        "com.facebook.lite" -> "com.facebook.katana"
        "com.instagram.lite" -> "com.instagram.android"
        else -> pkg
    }

    private fun rulesFor(pkg: String): Set<String> =
        enabledRules.value[pkg] ?: enabledRules.value[canonical(pkg)] ?: emptySet()

    /**
     * Remaining ms to display for [decision]. A pending auto-session (the
     * grant row isn't committed yet — decisionFor can read before evaluate()'s
     * upsert lands) counts as the full fixed session, so the pill shows on the
     * very first open instead of hiding behind `remaining == MAX_VALUE`. The
     * 250 ms ticker re-reads and switches to the committed grant's real value.
     */
    private fun remainingFor(decision: EnforcementManager.Decision): Long {
        if (decision.blocked) return 0L
        if (decision.remainingMs != Long.MAX_VALUE) return decision.remainingMs
        val auto = decision.autoSession ?: return Long.MAX_VALUE
        return auto.minutes * 60_000L
    }

    private fun updateCountdown(pkg: String) {
        container.scope.launch {
            val wantCountdown = container.settings.countdownOn.first()
            if (!wantCountdown) {
                mainHandler.post { hidePill() }
                return@launch
            }
            val decision = container.enforcement.decisionFor(pkg)
            val remaining = remainingFor(decision)
            mainHandler.post {
                if (decision.blocked || remaining == Long.MAX_VALUE || remaining <= 0L) {
                    hidePill()
                } else {
                    showPill(pkg, remaining)
                }
            }
        }
    }

    private fun showPill(pkg: String, remainingMs: Long) {
        val wm = getSystemService(WindowManager::class.java)
        if (pillShown && pillPkg == pkg) {
            (pillView as? TextView)?.text = formatDuration(remainingMs)
            return
        }
        if (pillShown) hidePill()
        pillPkg = pkg
        val tv = TextView(this).apply {
            text = formatDuration(remainingMs)
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(36, 18, 36, 18)
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.argb(225, 38, 38, 47))
                setStroke(4, Color.argb(255, 195, 169, 247))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 180
        }
        runCatching {
            wm.addView(tv, params)
            pillView = tv
            pillShown = true
            mainHandler.removeCallbacks(pillTicker)
            mainHandler.postDelayed(pillTicker, 250L)
        }
    }

    private fun hidePill() {
        mainHandler.removeCallbacks(pillTicker)
        if (!pillShown) return
        runCatching { getSystemService(WindowManager::class.java).removeView(pillView) }
        pillView = null
        pillShown = false
        pillPkg = null
    }

    private fun runDetection(pkg: String) {
        val keys = rulesFor(pkg)
        val specs = if (keys.isEmpty()) emptyList()
        else Detectors.specsFor(pkg).filter { it.key in keys }
        if (specs.isEmpty()) {
            hideCover()
            return
        }

        val root = rootInActiveWindow ?: return
        val dm = resources.displayMetrics
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels
        val matched = LinkedHashMap<ElementSpec, Rect>(4)

        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES_DETECT && matched.size < specs.size) {
                val node = queue.poll() ?: continue
                visited++
                for (spec in specs) {
                    if (spec in matched) continue
                    if (matches(node, spec)) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        matched[spec] = rect
                        break
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (_: Exception) {
            return // never crash the accessibility pipeline
        }

        if (matched.isEmpty()) {
            hideCover()
            return
        }

        val creditPkg = canonical(pkg)
        val now = System.currentTimeMillis()
        for ((spec, _) in matched) {
            val key = "$creditPkg|${spec.key}"
            val prev = lastSeen[key]
            lastSeen[key] = now
            if (prev != null) {
                val elapsed = (now - prev).coerceIn(0L, ELEM_MAX_CREDIT_MS)
                if (elapsed >= ELEM_MIN_CREDIT_MS) {
                    val day = container.usage.todayKey(now)
                    container.scope.launch {
                        runCatching { container.db.elementUsageDao().add(day, creditPkg, spec.key, elapsed) }
                    }
                }
            }
        }

        val (primary, bounds) = matched.entries.first()
        val area = bounds.width().toLong() * bounds.height()
        val fullscreen = area >= (screenArea * FULLSCREEN_RATIO)

        when (primary.action) {
            DetectAction.BACK -> maybeBack()
            DetectAction.COVER -> showCover(bounds)
            DetectAction.AUTO -> if (fullscreen) maybeBack() else showCover(bounds)
        }
    }

    private fun maybeBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < BACK_COOLDOWN_MS) return
        lastBackAt = now
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
    }

    private fun matches(node: AccessibilityNodeInfo, spec: ElementSpec): Boolean {
        if (spec.ids.isNotEmpty()) {
            val id = node.viewIdResourceName ?: ""
            if (id.isNotEmpty() && spec.ids.any { id.contains(it, ignoreCase = true) }) return true
        }
        if (spec.texts.isNotEmpty()) {
            val text = node.text?.toString()?.lowercase()
                ?: node.contentDescription?.toString()?.lowercase()
            if (text != null && spec.texts.any { text.contains(it) }) return true
        }
        return false
    }

    private fun resetAttribution() {
        lastSeen.clear()
    }

    // --- grayscale ----------------------------------------------------------

    /** Apply/restore the per-app monochrome filter for [pkg]. */
    private fun applyGrayscale(pkg: String) {
        val owned = container.grayActive.value
        if (!Grayscale.hasPermission(this)) {
            if (owned) restoreIfOurs()
            return
        }
        val inList = pkg in container.grayscaleApps.value &&
            pkg != packageName &&
            !Allowlist.isAllowlisted(pkg)
        if (inList) {
            if (!owned || !Grayscale.isEnabled(this)) {
                if (Grayscale.set(this, true)) {
                    container.scope.launch { container.settings.setGrayActive(true) }
                }
            }
        } else if (owned && Grayscale.isEnabled(this)) {
            restoreIfOurs()
        }
    }

    /** Remove the filter only when FocusGuard applied it itself. */
    private fun restoreIfOurs() {
        if (!container.grayActive.value) return
        if (Grayscale.isEnabled(this)) Grayscale.set(this, false)
        container.scope.launch { container.settings.setGrayActive(false) }
    }

    private fun showCover(bounds: Rect) {
        val wm = getSystemService(WindowManager::class.java)
        val params = WindowManager.LayoutParams(
            bounds.width().coerceAtLeast(1),
            bounds.height().coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

        if (!coverShown) {
            val view = TextView(this).apply {
                text = "Hidden by FocusGuard"
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Color.argb(255, 195, 169, 247))
                background = GradientDrawable().apply {
                    cornerRadius = 28f
                    setColor(Color.argb(250, 38, 38, 47))
                }
            }
            runCatching {
                wm.addView(view, params)
                coverView = view
                coverShown = true
            }
        } else {
            runCatching { wm.updateViewLayout(coverView, params) }
        }
    }

    private fun hideCover() {
        if (!coverShown) return
        runCatching { getSystemService(WindowManager::class.java).removeView(coverView) }
        coverView = null
        coverShown = false
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        container.a11yConnected = false
        rulesJob?.cancel()
        runCatching { unregisterReceiver(screenOffReceiver) }
        restoreIfOurs()
        mainHandler.removeCallbacksAndMessages(null)
        hideCover()
        hidePill()
        lastDomain?.let { domain ->
            val elapsed = (System.currentTimeMillis() - lastCreditAt).coerceIn(0L, WEB_MAX_CREDIT_MS)
            if (elapsed >= WEB_MIN_CREDIT_MS) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        container.usage.creditWebsite(domain, elapsed, System.currentTimeMillis())
                    }
                }
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val MAX_NODES_ADDRESS = 250
        private const val MAX_NODES_DETECT = 300
        private const val MIN_CHECK_INTERVAL_MS = 1_200L
        private const val WEB_MAX_CREDIT_MS = 60_000L
        private const val WEB_MIN_CREDIT_MS = 1_000L
        private const val ELEM_MAX_CREDIT_MS = 15_000L
        private const val ELEM_MIN_CREDIT_MS = 2_000L
        private const val DETECT_THROTTLE_MS = 400L
        private const val BACK_COOLDOWN_MS = 2_000L
        private const val FULLSCREEN_RATIO = 0.55

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
