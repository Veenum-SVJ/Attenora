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
import dev.opensource.focusguard.domain.ResetEngine
import dev.opensource.focusguard.ui.common.formatDuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * The third (optional) accessibility service: in-app element blocking plus the
 * floating usage-countdown overlay.
 *
 * Battery rules: no timers while nothing is visible, hard node caps, per-event
 * throttles, and a cheap package map lookup before any traversal — events from
 * apps without enabled element rules are ignored immediately.
 *
 * "Smart hide + auto-close": covered elements are visually replaced by an
 * overlay window; fullscreen surfaces are left with a Back gesture. Android
 * gives no API to actually delete another app's view.
 */
class ElementBlockService : AccessibilityService() {

    private val container get() = (application as FocusApp).container

    private val mainHandler = Handler(Looper.getMainLooper())

    /** pkg -> enabled element keys; kept fresh from Room, read on every event. */
    private val enabledRules = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    private var rulesJob: kotlinx.coroutines.Job? = null

    // --- detection throttling / state
    private var lastDetectAt = 0L
    private var lastBackAt = 0L
    private var lastForeground: String? = null

    /** "pkg|element" -> last attribution stamp (for the 7-day donut). */
    private val lastSeen = HashMap<String, Long>(8)

    // --- cover overlay
    private var coverView: View? = null
    private var coverShown = false

    // --- countdown pill
    private var pillView: View? = null
    private var pillShown = false
    private var pillPkg: String? = null

    private val pillTicker: Runnable = object : Runnable {
        override fun run() {
            val pkg = pillPkg ?: return
            if (!pillShown) return
            container.scope.launch {
                val decision = container.enforcement.decisionFor(pkg)
                val remaining = decision.remainingMs
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
        container.elementConnected = true
        rulesJob = container.scope.launch {
            container.db.elementRuleDao().observeAll().collect { rules ->
                enabledRules.value = rules.filter { it.enabled }
                    .groupBy({ it.packageName }, { it.element })
                    .mapValues { (_, keys) -> keys.toSet() }
            }
        }
        // Refresh pill state once after (re)connecting over a limited app.
        lastForeground?.let { updateCountdown(it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastForeground = pkg
                updateCountdown(pkg)
                resetAttribution()
                runDetection(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (enabledRules.value[pkg] == null && enabledRules.value[canonical(pkg)] == null) return
                val now = System.currentTimeMillis()
                if (now - lastDetectAt < DETECT_THROTTLE_MS) return
                lastDetectAt = now
                runDetection(pkg)
            }
        }
    }

    private fun canonical(pkg: String): String = when (pkg) {
        "com.ss.android.ugc.trill" -> "com.zhiliaoapp.musically"
        "app.revanced.android.youtube", "com.vanced.android.youtube" -> "com.google.android.youtube"
        "com.facebook.lite" -> "com.facebook.katana"
        "com.instagram.lite" -> "com.instagram.android"
        else -> pkg
    }

    private fun rulesFor(pkg: String): Set<String> =
        enabledRules.value[pkg] ?: enabledRules.value[canonical(pkg)] ?: emptySet()

    // --- countdown pill -----------------------------------------------------

    private fun updateCountdown(pkg: String) {
        container.scope.launch {
            val wantCountdown = container.settings.countdownOn.first()
            if (!wantCountdown) {
                mainHandler.post { hidePill() }
                return@launch
            }
            val decision = container.enforcement.decisionFor(pkg)
            mainHandler.post {
                if (decision.blocked || decision.remainingMs == Long.MAX_VALUE || decision.remainingMs <= 0L) {
                    hidePill()
                } else {
                    showPill(pkg, decision.remainingMs)
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

    // --- detection ----------------------------------------------------------

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
            while (queue.isNotEmpty() && visited < MAX_NODES && matched.size < specs.size) {
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

        // Attribute foreground time per element for the 7-day donut.
        // Credits go under the canonical package so aliases (clones/mods)
        // roll up into the same card and rules.
        val creditPkg = canonical(pkg)
        val now = System.currentTimeMillis()
        for ((spec, _) in matched) {
            val key = "$creditPkg|${spec.key}"
            val prev = lastSeen[key]
            lastSeen[key] = now
            if (prev != null) {
                val elapsed = (now - prev).coerceIn(0L, MAX_CREDIT_MS)
                if (elapsed >= MIN_CREDIT_MS) {
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
            val id = node.viewIdResourceName ?: "" // requires flagReportViewIds
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
        // Returning to the app starts attribution fresh; the per-step cap
        // prevents long gaps from inflating any single credit.
        lastSeen.clear()
    }

    // --- cover overlay ------------------------------------------------------

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
        container.elementConnected = false
        rulesJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        hideCover()
        hidePill()
        super.onDestroy()
    }

    companion object {
        private const val MAX_NODES = 300
        private const val DETECT_THROTTLE_MS = 400L
        private const val BACK_COOLDOWN_MS = 2_000L
        private const val MAX_CREDIT_MS = 15_000L
        private const val MIN_CREDIT_MS = 2_000L
        private const val FULLSCREEN_RATIO = 0.55
    }
}
