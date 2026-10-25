package dev.opensource.attenora.enforcement

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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.opensource.attenora.AttenoraApplication
import dev.opensource.attenora.domain.Allowlist
import dev.opensource.attenora.domain.UrlMatcher
import dev.opensource.attenora.ui.blocking.BlockOverlayActivity
import dev.opensource.attenora.ui.common.formatDuration
import dev.opensource.attenora.util.Diag
import dev.opensource.attenora.util.Grayscale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.Date

/**
 * The single accessibility service. Replaces the former three services so the
 * user enables Attenora exactly once in system accessibility settings.
 *
 * It always reports foreground app changes (screen-time accounting + blocking).
 * When a browser is foreground it additionally reads only the address bar to
 * enforce website blocking and daily limits. When an app has enabled in-app
 * element rules it hides matched elements and shows the countdown pill.
 *
 * Payloads stay cheap: package-map lookups before traversal, hard node caps,
 * and per-event throttles. No other app's content is ever read.
 */
class AttenoraAccessibilityService : AccessibilityService() {

    private val container get() = (application as AttenoraApplication).container

    private val mainHandler = Handler(Looper.getMainLooper())

    // --- website state
    private var lastCheckAt = 0L
    private var lastDomain: String? = null
    private var lastCreditAt = 0L

    // --- element state
    private val enabledRules = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private var rulesJob: kotlinx.coroutines.Job? = null
    private var liveJob: kotlinx.coroutines.Job? = null
    private var lastDetectAt = 0L
    private var lastDetectLogAt = 0L
    private var lastLimitRecheckAt = 0L
    private var lastBackAt = 0L
    private var lastForeground: String? = null
    private val lastSeen = HashMap<String, Long>(8)

    // --- grayscale state (Curbox-style per-app monochrome filter)
    private val screenOffReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            restoreIfOurs() // never leave the display stuck monochrome behind a locked screen
            stopLimitWatch() // nothing is foreground while the screen is off
            dropReelOverlay() // …and nothing should keep a count over the lock screen
            hidePill() // …nor a countdown ticking over it
            // Locking the phone with an app open ends that foreground stretch:
            // live usage stops accruing and a running session pauses instead
            // of burning against the wall clock while the screen is dark.
            container.enforcement.onForeground(null)
        }
    }

    /**
     * Coming back from the lock screen often emits no window-state event for
     * the app that was already open, so re-derive the foreground from the
     * window actually on screen — otherwise usage tracking and a paused
     * session stay frozen while the user is using the app again.
     */
    private val screenOnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            syncForegroundFromWindow()
        }
    }

    private fun syncForegroundFromWindow() {
        mainHandler.post {
            val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it != packageName } ?: return@post
            lastForeground = pkg
            container.enforcement.onForeground(pkg)
            updateCountdown(pkg)
            applyGrayscale(pkg)
            startLimitWatch(pkg)
            onReelForeground(pkg)
            ensureBlockCovered(pkg)
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
                // The pill belongs to the app that is actually on screen. A
                // window event can be missed (recents, a lock, an OEM-swallowed
                // transition), and the ticker would then keep counting down over
                // another app — which reads exactly like "the timer keeps running
                // after I leave". Re-check what is really in front, once a second.
                val onScreen = runCatching { rootInActiveWindow?.packageName?.toString() }
                    .getOrNull()
                if (onScreen != null && onScreen != pkg) {
                    mainHandler.post { if (pillPkg == pkg) hidePill() }
                    return@launch
                }
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

    // --- limit watch state
    // Re-checks the foreground app's remaining time so a limit/session that
    // runs out while the app stays open still blocks. One delayed wakeup at a
    // time (never a poll loop); bounded by MAX_WATCH_DELAY_MS.
    private var watchPkg: String? = null
    private var lastLoggedPkg: String? = null

    private val limitWatcher: Runnable = Runnable {
        val pkg = watchPkg ?: return@Runnable
        container.scope.launch {
            val decision = runCatching { container.enforcement.evaluate(pkg) }.getOrNull()
                ?: return@launch
            mainHandler.post {
                if (watchPkg != pkg) return@post
                if (decision.blocked) {
                    stopLimitWatch()
                } else {
                    val remaining = remainingFor(decision)
                    if (remaining == Long.MAX_VALUE || remaining <= 0L) stopLimitWatch()
                    else scheduleLimitWatch(remaining)
                }
            }
        }
    }

    private fun startLimitWatch(pkg: String) {
        stopLimitWatch()
        if (pkg == packageName || Allowlist.isAllowlisted(pkg)) return
        watchPkg = pkg
        scheduleLimitWatch(0L)
    }

    private fun scheduleLimitWatch(remainingMs: Long) {
        val delay = (remainingMs + 300L).coerceIn(1_000L, MAX_WATCH_DELAY_MS)
        mainHandler.removeCallbacks(limitWatcher)
        mainHandler.postDelayed(limitWatcher, delay)
    }

    private fun stopLimitWatch() {
        watchPkg = null
        mainHandler.removeCallbacks(limitWatcher)
    }

    /**
     * A clock-based limit must not depend on the single delayed watcher
     * surviving — an OEM can kill or freeze the process mid-session. Every app
     * emits content changes, so a throttled re-check here re-arms the block
     * within seconds of the deadline even when the watcher was lost.
     */
    private fun recheckLimitIfCovered(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastLimitRecheckAt < LIMIT_RECHECK_MS) return
        lastLimitRecheckAt = now
        if (!container.enforcement.hasLimitFor(pkg)) return
        container.scope.launch {
            runCatching { container.enforcement.evaluate(pkg) }
        }
        if (watchPkg == null) startLimitWatch(pkg)
        ensureBlockCovered(pkg)
    }

    /**
     * A limit or schedule row changed. Re-decide whatever is genuinely on
     * screen right now — [lastForeground] is deliberately not used, because it
     * still points at the app the user came from while Attenora's own
     * settings are open (our package is filtered out of window events), and
     * blocking that app behind the settings would be wrong.
     */
    private fun recheckForeground() {
        mainHandler.post {
            val pkg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
                ?: return@post
            if (pkg == packageName || Allowlist.isAllowlisted(pkg)) return@post
            container.scope.launch {
                runCatching { container.enforcement.evaluate(pkg) }
            }
            startLimitWatch(pkg)
        }
    }

    // --- reel counter state
    // Marker-first: [reelMarked] flips on only when a supported app is actually
    // showing its short-video feed, and a swipe then adds one to today's count.
    // The marker scan is throttled; the per-swipe path is a boolean read.
    private var reelView: ReelCounterView? = null
    private var reelShown = false
    private var reelPkg: String? = null
    private var reelMarked = false
    private var reelMarkedAt = 0L
    private var lastReelScanAt = 0L
    private var lastReelCountAt = 0L

    /**
     * Set when a scan sees a conversation/inbox/comment surface. Swipes are
     * ignored until it passes, so the moment after leaving the feed — before
     * the next scan — cannot count either. Every scan inside a chat refreshes
     * it, and chats emit content changes on every swipe, so it holds.
     */
    private var reelBlockedUntil = 0L

    /**
     * A swipe is a burst, not an event: one TikTok swipe sends six scroll
     * events over ~650 ms, so a fixed floor between counts either double-counts
     * a long burst or has to be so long that two real swipes merge. Instead the
     * count is committed once the burst has gone quiet for
     * [REEL_COMMIT_QUIET_MS], which is exactly "one swipe, one reel".
     */
    private var reelPendingPkg: String? = null
    private var reelPendingSrc = ""
    private val reelCommit = Runnable {
        val pkg = reelPendingPkg ?: return@Runnable
        val src = reelPendingSrc
        reelPendingPkg = null
        reelPendingSrc = ""
        if (!reelMarked || !container.reelCounterOn.value) return@Runnable
        val now = System.currentTimeMillis()
        if (now - lastReelCountAt < REEL_MIN_GAP_MS) return@Runnable
        lastReelCountAt = now
        Diag.d("reel +1 $pkg src=$src")

        val credit = Reels.creditKey(pkg)
        container.scope.launch {
            runCatching {
                val day = container.usage.todayKey()
                container.db.reelCountDao().add(day, credit, 1)
                val total = container.db.reelCountDao().totalForDay(day) ?: return@runCatching
                mainHandler.post { showReelCount(total) }
            }
        }
    }

    private val reelFadeOut = Runnable { fadeReelOut() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        container.a11yConnected = true
        Diag.d(
            "service connected reels=${container.reelCounterOn.value} " +
                "web=${container.webBlockingActive.value} sdk=${android.os.Build.VERSION.SDK_INT}"
        )
        rulesJob = container.scope.launch {
            container.db.elementRuleDao().observeAll().collect { rules ->
                val active = rules.filter { it.enabled }
                    .groupBy({ it.packageName }, { it.element })
                    .mapValues { (_, keys) -> keys.toSet() }
                enabledRules.value = active
                Diag.d("rules ${rules.size} active=${active.mapValues { it.value.size }}")
            }
        }
        // Arm the cover fallback the moment a block is enforced. HyperOS drops
        // background activity starts without throwing, and a static app (a
        // calculator, a spent session, a loaded page) never sends another
        // window event — so waiting for one meant no block screen, ever.
        container.enforcement.onBlockEnforced = { target, reason, until, label ->
            ensureBlockCovered(target, reason, until, label)
        }
        // Live limits and schedules. The enforcement data is Room-backed, so an
        // edit in Settings is already visible to the next decision — but a
        // decision only ran on an app switch, so a limit added while the target
        // app stayed open waited for the next window event. Re-deciding on the
        // row change instead makes a limit/schedule edit take effect at once.
        // Event-driven: the collector wakes only when a row actually changes.
        liveJob = container.scope.launch {
            merge(
                container.enforcement.limits.drop(1),
                container.enforcement.schedules.drop(1)
            ).collect { recheckForeground() }
        }
        runCatching {
            registerReceiver(screenOffReceiver, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF))
        }
        runCatching {
            registerReceiver(screenOnReceiver, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON))
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
            startLimitWatch(it)
            onReelForeground(it)
            ensureBlockCovered(it)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) {
            // Attenora is never in the grayscale list, so coming back to our
            // own UI has to lift a filter a previous app turned on. Everything
            // else below deliberately ignores self events.
            applyGrayscale(pkg)
            return
        }

        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != lastLoggedPkg) {
                    lastLoggedPkg = pkg
                    Diag.d(
                        "fg $pkg reels=${Reels.appliesTo(pkg)} " +
                            "rules=${Detectors.appliesTo(pkg)} browser=${isBrowser(pkg)}"
                    )
                }
                container.enforcement.onForeground(pkg)
                lastForeground = pkg
                updateCountdown(pkg)
                applyGrayscale(pkg)
                resetAttribution()
                runDetection(pkg)
                onReelForeground(pkg)
                if (isBrowser(pkg)) checkWebsite(e, pkg)
                startLimitWatch(pkg)
                if (coverPkg != null && coverPkg != pkg) hideBlockCover()
                ensureBlockCovered(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (enabledRules.value[pkg] != null || enabledRules.value[canonical(pkg)] != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastDetectAt >= DETECT_THROTTLE_MS) {
                        lastDetectAt = now
                        runDetection(pkg)
                    }
                }
                noteReelSurface(pkg)
                recheckLimitIfCovered(pkg)
                if (isBrowser(pkg)) checkWebsite(e, pkg)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (isVerticalScroll(e)) noteReelScroll(e)
            }
        }
    }

    // --- website blocking ---------------------------------------------------

    private fun checkWebsite(e: AccessibilityEvent, pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastCheckAt < MIN_CHECK_INTERVAL_MS) return
        lastCheckAt = now

        val raw = findAddressText() ?: run {
            Diag.d("web $pkg no address bar read")
            return
        }
        val host = UrlMatcher.normalizeDomain(raw) ?: run {
            Diag.d("web $pkg raw=$raw host=null")
            return
        }
        Diag.d("web $pkg raw=$raw host=$host active=${container.webBlockingActive.value}")

        attribute(host, now)

        if (container.webBlockingActive.value) {
            val hit = UrlMatcher.matches(host, container.blocklist.value)
            if (hit != null) {
                Diag.d("web $pkg HIT $hit")
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
        val candidates = ArrayList<String>(6)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES_ADDRESS) {
                val node = queue.poll()
                visited++
                val cls = node.className?.toString() ?: ""
                val id = node.viewIdResourceName ?: ""
                // Chrome, Brave, Edge, Firefox and Samsung Internet all show
                // the URL in a plain text bar (id "url_bar", "omnibox", …),
                // not in an EditText — which is why the old EditText-only scan
                // never found a host in Brave and website blocking did nothing.
                val looksLikeBar = cls.endsWith("EditText") ||
                    id.contains("url", ignoreCase = true) ||
                    id.contains("omnibox", ignoreCase = true) ||
                    id.contains("address", ignoreCase = true)
                if (looksLikeBar) {
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

    /**
     * The catalogue package a repackaged client should be treated as.
     *
     * Delegates to [Detectors.canonical] so family matching is shared: a patched
     * YouTube (Morphe/ReVanced, `app.morphe.android.youtube`) or a repacked
     * Instagram resolves by name, not by an exact alias list. The old local map
     * knew four literal package names, so element rules silently never applied
     * to any other repackaged client — the rule row was stored, enabled, and
     * then looked up under a key this function could not produce.
     */
    private fun canonical(pkg: String): String = Detectors.canonical(pkg)

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
        val added = runCatching { wm.addView(tv, params) }
        if (added.isFailure) return
        pillView = tv
        pillShown = true
        mainHandler.removeCallbacks(pillTicker)
        mainHandler.postDelayed(pillTicker, 250L)
    }

    private fun hidePill() {
        mainHandler.removeCallbacks(pillTicker)
        if (!pillShown) return
        runCatching { getSystemService(WindowManager::class.java).removeView(pillView) }
        pillView = null
        pillShown = false
        pillPkg = null
    }

    // --- fallback block cover -----------------------------------------------

    /**
     * The block screen is a normal Activity. Android normally allows an enabled
     * accessibility service to start one, but several OEM builds suppress it
     * (the "display pop-ups while running in background" switch), and the
     * launch then fails silently: the limit fires, the pill disappears, and
     * the app stays usable. If the Activity has not come to the front shortly
     * after a block decision, cover the app with an accessibility overlay
     * instead — that window type needs no permission and cannot be suppressed,
     * exactly like the countdown pill.
     */
    private var coverPkg: String? = null
    private var blockCoverView: View? = null
    private var coverCountdown: TextView? = null
    private var coverUntil = 0L

    private val coverTick = object : Runnable {
        override fun run() {
            if (blockCoverView == null) return
            if (BlockOverlayActivity.visible) {
                hideBlockCover()
                return
            }
            if (coverUntil > 0L && System.currentTimeMillis() >= coverUntil) {
                hideBlockCover()
                return
            }
            coverCountdown?.text =
                if (coverUntil > 0L) "Available again at ${clockText(coverUntil)}" else ""
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    /**
     * [reason] is set when a block was just enforced and the caller already
     * knows why (a website is not an app limit, so re-deriving the decision
     * would answer "not blocked"). It is null on window events, where the
     * current decision is the only truth.
     */
    private fun ensureBlockCovered(
        pkg: String,
        reason: String? = null,
        untilMs: Long = 0L,
        label: String? = null
    ) {
        mainHandler.postDelayed({
            if (BlockOverlayActivity.visible || blockCoverView != null) return@postDelayed
            val fg = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
            if (fg != pkg) return@postDelayed
            container.scope.launch {
                val decision = if (reason != null) {
                    EnforcementManager.Decision(blocked = true, reason = reason, availableAtMs = untilMs)
                } else {
                    runCatching { container.enforcement.decisionFor(pkg) }.getOrNull()
                } ?: return@launch
                Diag.d(
                    "cover check pkg=$pkg blocked=${decision.blocked} " +
                        "screen=${BlockOverlayActivity.visible} reason=${decision.reason}"
                )
                if (decision.blocked) mainHandler.post { showBlockCover(pkg, decision, label) }
            }
        }, BLOCK_FALLBACK_MS)
    }

    private fun showBlockCover(
        pkg: String,
        decision: EnforcementManager.Decision,
        label: String? = null
    ) {
        if (BlockOverlayActivity.visible) return
        hideBlockCover()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        root.addView(TextView(this).apply {
            text = when (decision.reason) {
                BlockOverlayActivity.REASON_COOLDOWN -> "$app session over"
                BlockOverlayActivity.REASON_SCHEDULE -> "Focus time"
                BlockOverlayActivity.REASON_BLOCK_ALL -> "Focus session"
                BlockOverlayActivity.REASON_WEBSITE -> "Site blocked"
                BlockOverlayActivity.REASON_PERMANENT -> "Blocked"
                else -> "$app is blocked"
            }
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = when (decision.reason) {
                BlockOverlayActivity.REASON_COOLDOWN ->
                    "You used this session. Take a break — it opens again on its own."
                BlockOverlayActivity.REASON_SCHEDULE -> "A focus schedule is active right now."
                BlockOverlayActivity.REASON_BLOCK_ALL -> "You started a blocking session. Use the time for something better."
                BlockOverlayActivity.REASON_WEBSITE ->
                    label?.let { "$it is on your blocklist." } ?: "This site is on your blocklist."
                BlockOverlayActivity.REASON_PERMANENT -> "It stays blocked until you remove the limit."
                else -> "You've reached your limit for today."
            }
            setTextColor(Color.argb(170, 255, 255, 255))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(18))
        })
        val countdown = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        }
        root.addView(countdown)
        root.addView(Button(this).apply {
            text = "Go home"
            setOnClickListener {
                runCatching { performGlobalAction(GLOBAL_ACTION_HOME) }
                hideBlockCover()
            }
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        val added = runCatching {
            getSystemService(WindowManager::class.java).addView(root, params)
        }.isSuccess
        Diag.d("cover pkg=$pkg reason=${decision.reason} added=$added")
        if (!added) return
        coverPkg = pkg
        blockCoverView = root
        coverCountdown = countdown
        coverUntil = decision.availableAtMs
        mainHandler.removeCallbacks(coverTick)
        mainHandler.post(coverTick)
    }

    private fun hideBlockCover() {
        mainHandler.removeCallbacks(coverTick)
        val view = blockCoverView ?: return
        blockCoverView = null
        coverCountdown = null
        coverPkg = null
        coverUntil = 0L
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
    }

    private fun clockText(ms: Long): String =
        runCatching { android.text.format.DateFormat.getTimeFormat(this).format(Date(ms)) }
            .getOrDefault("")

    private fun runDetection(pkg: String) {
        val keys = rulesFor(pkg)
        val specs = if (keys.isEmpty()) emptyList()
        else Detectors.specsFor(pkg).filter { it.key in keys }
        if (specs.isEmpty()) {
            if (Detectors.appliesTo(pkg)) {
                val now = System.currentTimeMillis()
                if (now - lastDetectLogAt >= DETECT_LOG_MS) {
                    lastDetectLogAt = now
                    Diag.d("inapp idle $pkg keys=$keys canonical=${canonical(pkg)}")
                }
            }
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
            while (queue.isNotEmpty() && visited < MAX_NODES_DETECT) {
                val node = queue.poll() ?: continue
                visited++
                for (spec in specs) {
                    if (matches(node, spec)) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        // Keep the biggest node per spec: a tab label and the
                        // surface it opens can match the same rule, and the
                        // tab is the one we must never act on.
                        val prev = matched[spec]
                        val area = rect.width().toLong() * rect.height()
                        if (prev == null || area > prev.width().toLong() * prev.height()) {
                            matched[spec] = rect
                        }
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

        // Act on the LARGEST match, and only when it is big enough to be the
        // surface itself. Without this, Instagram's persistent "Reels" nav
        // label matched the rule on the home screen, so every content change
        // pressed Back — which reloaded the feed and sometimes left the app.
        val pair = matched.entries.maxByOrNull {
            it.value.width().toLong() * it.value.height()
        }?.let { it.key to it.value } ?: return
        val primary = pair.first
        val bounds = pair.second
        val area = bounds.width().toLong() * bounds.height()
        val coverage = area.toDouble() / screenArea
        val fullscreen = coverage >= FULLSCREEN_RATIO
        val acted = when (primary.action) {
            DetectAction.BACK -> coverage >= BACK_MIN_COVERAGE
            DetectAction.COVER -> coverage >= COVER_MIN_COVERAGE
            DetectAction.AUTO -> fullscreen || coverage >= COVER_MIN_COVERAGE
        }
        Diag.d(
            "inapp $pkg rule=${primary.key} action=${primary.action} " +
                "cov=${((coverage * 100).toInt())}% acted=$acted"
        )

        when (primary.action) {
            DetectAction.BACK -> if (coverage >= BACK_MIN_COVERAGE) maybeBack() else hideCover()
            DetectAction.COVER -> if (coverage >= COVER_MIN_COVERAGE) showCover(bounds) else hideCover()
            DetectAction.AUTO -> when {
                fullscreen -> maybeBack()
                coverage >= COVER_MIN_COVERAGE -> showCover(bounds)
                else -> hideCover()
            }
        }
    }

    private fun maybeBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < BACK_COOLDOWN_MS) {
            Diag.d("inapp back suppressed cooldown")
            return
        }
        lastBackAt = now
        runCatching { performGlobalAction(GLOBAL_ACTION_BACK) }
        Diag.d("inapp back sent")
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

    // --- reel counter -------------------------------------------------------

    /** Foreground changed: reset feed mode and take the count off the old app. */
    private fun onReelForeground(pkg: String) {
        if (!container.reelCounterOn.value) {
            clearReelState()
            return
        }
        if (reelPkg != pkg) {
            reelPkg = pkg
            reelMarked = false
            reelMarkedAt = 0L
            lastReelScanAt = 0L
            reelBlockedUntil = 0L
            reelPendingPkg = null
            mainHandler.removeCallbacks(reelCommit)
            dropReelOverlay()
        }
        if (!Reels.appliesTo(pkg)) {
            reelMarked = false
            dropReelOverlay()
            return
        }
        // A window change inside the same app is a navigation, so it is a
        // definitive answer: if the feed is gone, the mark goes with it. The
        // sticky window only ever covers content-change scans.
        val now = System.currentTimeMillis()
        if (now - lastReelScanAt >= REEL_NAV_SCAN_GAP_MS) {
            lastReelScanAt = now
            scanReelSurface(pkg, definitive = true)
        }
        // The counter used to appear only after the first swipe, so a user
        // opening the feed could not tell whether it was working at all.
        // Show today's running total as soon as the feed is recognised.
        if (reelMarked) revealReelTotal()
    }

    /** Show today's total on entering a recognised feed, before any swipe. */
    private fun revealReelTotal() {
        container.scope.launch {
            val total = runCatching {
                container.db.reelCountDao().totalForDay(container.usage.todayKey())
            }.getOrNull() ?: 0
            mainHandler.post { if (reelMarked) showReelCount(total) }
        }
    }

    /**
     * Called on content changes inside a supported app. Throttled, because a
     * playing reel emits content updates continuously and each scan is a tree
     * walk; one look every [REEL_SCAN_INTERVAL_MS] is plenty to notice that the
     * feed opened or closed.
     */
    private fun noteReelSurface(pkg: String) {
        if (!container.reelCounterOn.value) {
            if (reelShown || reelMarked) clearReelState()
            return
        }
        if (!Reels.appliesTo(pkg)) return
        if (reelPkg != pkg) {
            reelPkg = pkg
            reelMarked = false
            reelMarkedAt = 0L
            lastReelScanAt = 0L
            reelBlockedUntil = 0L
            reelPendingPkg = null
            mainHandler.removeCallbacks(reelCommit)
            dropReelOverlay()
        }
        val now = System.currentTimeMillis()
        if (now - lastReelScanAt < REEL_SCAN_INTERVAL_MS) return
        lastReelScanAt = now
        val wasMarked = reelMarked
        scanReelSurface(pkg)
        if (reelMarked && !wasMarked) revealReelTotal()
    }

    /**
     * Sets [reelMarked] by looking for a short-video marker on a node big
     * enough to be the video itself rather than a tab button — Instagram keeps
     * a "Reels" tab on every screen, so a bare label match would count its home
     * feed too. See [Reels].
     *
     * The same walk looks for the opposite signal as well, a conversation, an
     * inbox or a comment sheet, and that one wins: those screens keep the
     * player in the tree behind them, which is how swipes inside a chat came to
     * be counted as reels.
     */
    private fun scanReelSurface(pkg: String, definitive: Boolean = false) {
        val markers = Reels.markersFor(pkg)
        if (markers.isEmpty() && !Reels.isFullFeed(pkg)) {
            reelMarked = false
            return
        }
        val root = rootInActiveWindow ?: return
        val dm = resources.displayMetrics
        val windowArea = dm.widthPixels.toLong() * dm.heightPixels
        val bigEnough = (windowArea * REEL_SURFACE_RATIO).toLong()
        val chatEnough = (windowArea * REEL_CHAT_RATIO).toLong()
        val chatIds = Reels.chatIdsFor(pkg)
        val acceptVideoClass = Reels.videoClassFeed(pkg)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var found = false
        var via = "none"
        var chat = ""
        // Only filled on scans that will be logged (a navigation), so the
        // "why did it not count?" question can be answered from a bug report.
        val bigOnes = ArrayList<String>(4)
        val wasMarked = reelMarked
        try {
            // No early exit at the first marker: the video is usually near the
            // top of the tree and the chat container is often its sibling, so
            // stopping early would miss the message list behind the player.
            while (queue.isNotEmpty() && visited < REEL_MAX_NODES) {
                val node = queue.poll() ?: continue
                visited++
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val area = rect.width().toLong() * rect.height()
                // Filled on every logged scan, not just navigations: when a
                // screen is miscounted the only way to tell *which* node
                // carried the marker is to see the big ids beside it. Cheap
                // (four strings, scans run at most every 2 s).
                if (bigOnes.size < 6) {
                    val pct = (area * 100 / windowArea).toInt()
                    if (pct >= 30) {
                        bigOnes.add(
                            (node.viewIdResourceName ?: "").substringAfterLast('/').ifEmpty { "-" } +
                                "@$pct%:" + (node.className?.toString() ?: "").substringAfterLast('.')
                        )
                    }
                }
                if (node.isSelected) {
                    val selected = node.text?.toString()
                        ?: node.contentDescription?.toString() ?: ""
                    if (Reels.isChatTab(selected)) chat = "tab:$selected"
                }
                // Only a conversation that is actually on screen counts. YouTube
                // keeps its engagement panel (the comment sheet) laid out at
                // full height while it is closed, which read as "chat" on the
                // first scan of a Short and dropped the overlay for no reason.
                if (chatIds.isNotEmpty() && area >= chatEnough && node.isVisibleToUser) {
                    val id = node.viewIdResourceName ?: ""
                    if (Reels.isChatSurfaceId(pkg, id)) {
                        chat = "id:${id.substringAfterLast('/')}"
                    }
                }
                if (!found && area >= bigEnough) {
                    val id = node.viewIdResourceName ?: ""
                    val label = node.text?.toString()
                        ?: node.contentDescription?.toString() ?: ""
                    val cls = node.className?.toString() ?: ""
                    val named = markers.any {
                        id.contains(it, ignoreCase = true) ||
                            label.contains(it, ignoreCase = true)
                    }
                    val playingVideo = acceptVideoClass &&
                        Reels.videoClasses.any { cls.contains(it, ignoreCase = true) }
                    if (named || playingVideo) {
                        found = true
                        via = if (named) "marker:$id" else "video:${cls.substringAfterLast('.')}"
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
        } catch (_: Exception) {
            return // never crash the accessibility pipeline
        }
        if (chat.isNotEmpty()) {
            reelBlockedUntil = System.currentTimeMillis() + REEL_BLOCK_HOLD_MS
            reelMarked = false
            reelMarkedAt = 0L
            if (reelShown) dropReelOverlay()
            Diag.d("reel scan $pkg chat=$chat marked=false nodes=$visited")
            return
        }
        if (found) {
            reelMarked = true
            reelMarkedAt = System.currentTimeMillis()
            // The feed is on screen now, so any chat-surface block is over —
            // otherwise the first swipes after coming back from the inbox
            // would be dropped for the rest of the hold.
            reelBlockedUntil = 0L
        } else {
            // A feed can scan as empty for a moment mid-page. Keeping the mark
            // for a few seconds stops a swipe in that gap from being lost —
            // this was why counts came and went on a real device. A definitive
            // scan (a navigation) is trusted, so leaving the feed clears it.
            reelMarked = !definitive &&
                System.currentTimeMillis() - reelMarkedAt < REEL_MARK_STICKY_MS
        }
        if (found || reelMarked != wasMarked || definitive) {
            Diag.d(
                "reel scan $pkg found=$found via=$via marked=$reelMarked " +
                    "definitive=$definitive nodes=$visited big=${bigOnes.joinToString(" | ")}"
            )
        }
    }

    /**
     * One short video per swipe. The feed pages a whole clip at a time, so one
     * scroll event is one video — but a fling can emit a burst, hence the floor
     * between counts.
     */
    private fun noteReelScroll(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (reelPkg != pkg) {
            Diag.d("reel swipe ignored pkg=$pkg feed=$reelPkg")
            return
        }
        if (!container.reelCounterOn.value) return
        // A conversation, inbox or comment sheet was seen a moment ago. The
        // player can still be in the tree behind it, so the mark alone would
        // say yes; this says no.
        if (System.currentTimeMillis() < reelBlockedUntil) {
            Diag.d("reel swipe dropped pkg=$pkg chatSurface")
            return
        }
        // The feed pages the whole window. A half-height comment sheet or a
        // chat composer does not, so a partial source is never a new reel even
        // when the player is still visible above it.
        if (reelMarked && !sourceFillsWindow(e, REEL_MIN_SOURCE_RATIO)) {
            Diag.d(
                "reel swipe dropped pkg=$pkg partial src=${eventSourceClass(e)} " +
                    "fills=${sourceFillsWindow(e)}"
            )
            return
        }
        if (!reelMarked) {
            // The scan runs on a timer while the feed is open, so a swipe can
            // land in the gap between two scans. Look once more at the moment
            // it matters rather than dropping the swipe.
            val scanNow = System.currentTimeMillis()
            if (scanNow - lastReelScanAt >= REEL_SCAN_INTERVAL_MS) {
                lastReelScanAt = scanNow
                scanReelSurface(pkg)
            }
            if (System.currentTimeMillis() < reelBlockedUntil) {
                Diag.d("reel swipe dropped pkg=$pkg chatSurface")
                return
            }
            if (!reelMarked) {
                // TikTok has no stable feed id and often omits its player from
                // the accessibility tree, so a scroll whose source fills the
                // window is the only reliable signal there. Restricted to apps
                // whose whole UI is the feed, so an ordinary list cannot count.
                if (!(Reels.isFeedFirst(pkg) && sourceFillsWindow(e))) {
                    Diag.d(
                        "reel swipe dropped pkg=$pkg unmarked src=${eventSourceClass(e)} " +
                            "fills=${sourceFillsWindow(e)} family=${Reels.canonical(pkg)}"
                    )
                    return
                }
                reelMarked = true
                reelMarkedAt = System.currentTimeMillis()
            }
        }
        // A scroll event that reports no movement is not a swipe. YouTube's
        // pager emits them while a Short is laid out, so opening one was
        // counted twice before the screen was even touched. Only a real delta
        // can start a burst; a still event may still extend one that is
        // already running (later events of a fling often report zero).
        if (e.scrollDeltaY == 0 && e.scrollDeltaX == 0 && reelPendingPkg == null) {
            Diag.d("reel swipe ignored pkg=$pkg still")
            return
        }
        // One gesture, one count: a burst of scroll events is committed once it
        // has been quiet for a moment. See [reelCommit].
        reelPendingPkg = pkg
        reelPendingSrc = "${eventSourceClass(e)} fills=${sourceFillsWindow(e)} dy=${e.scrollDeltaY}"
        mainHandler.removeCallbacks(reelCommit)
        mainHandler.postDelayed(reelCommit, REEL_COMMIT_QUIET_MS)
    }

    /** Short class name of the node that produced [e], for the diagnostics log. */
    private fun eventSourceClass(e: AccessibilityEvent): String =
        runCatching { e.source?.className?.toString()?.substringAfterLast('.') }.getOrNull() ?: "?"

    /** True when the scrolling node itself covers [ratio] of the window height. */
    private fun sourceFillsWindow(e: AccessibilityEvent, ratio: Float = 0.85f): Boolean {
        val src = runCatching { e.source }.getOrNull() ?: return false
        val rect = Rect()
        runCatching { src.getBoundsInScreen(rect) }
        val screenHeight = resources.displayMetrics.heightPixels
        return rect.height() >= (screenHeight * ratio).toInt()
    }

    /**
     * Feeds page vertically. A horizontal swipe — Instagram's "next story", a
     * carousel inside a post — is not a new video and must not count.
     */
    private fun isVerticalScroll(e: AccessibilityEvent): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return true
        val dx = e.scrollDeltaX
        val dy = e.scrollDeltaY
        if (dx == 0 && dy == 0) return true
        return kotlin.math.abs(dy) >= kotlin.math.abs(dx)
    }

    private fun showReelCount(total: Int) {
        if (!container.reelCounterOn.value) return
        val existing = reelView
        val view: ReelCounterView = if (reelShown && existing != null) {
            existing.count = total
            existing
        } else {
            val fresh = ReelCounterView(this).apply {
                count = total
                alpha = 0f
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            val added = runCatching {
                getSystemService(WindowManager::class.java).addView(fresh, params)
            }.isSuccess
            if (!added) return
            reelView = fresh
            reelShown = true
            fresh
        }
        view.animate().cancel()
        view.opacity = container.reelOverlayOpacity.value
        view.animate().alpha(1f).setDuration(REEL_FADE_IN_MS).start()
        mainHandler.removeCallbacks(reelFadeOut)
        mainHandler.postDelayed(reelFadeOut, REEL_HOLD_MS)
    }

    /** Gone a moment after the last swipe, back on the next one. */
    private fun fadeReelOut() {
        val view = reelView ?: return
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(REEL_FADE_OUT_MS).start()
    }

    private fun dropReelOverlay() {
        mainHandler.removeCallbacks(reelFadeOut)
        if (!reelShown) {
            reelView = null
            return
        }
        runCatching { getSystemService(WindowManager::class.java).removeView(reelView) }
        reelView = null
        reelShown = false
    }

    private fun clearReelState() {
        reelPkg = null
        reelMarked = false
        reelMarkedAt = 0L
        lastReelScanAt = 0L
        reelBlockedUntil = 0L
        reelPendingPkg = null
        mainHandler.removeCallbacks(reelCommit)
        dropReelOverlay()
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

    /** Remove the filter only when Attenora applied it itself. */
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
                text = "Hidden by Attenora"
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
        liveJob?.cancel()
        runCatching { unregisterReceiver(screenOffReceiver) }
        restoreIfOurs()
        stopLimitWatch()
        mainHandler.removeCallbacksAndMessages(null)
        hideCover()
        hideBlockCover()
        hidePill()
        dropReelOverlay()
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
        private const val MAX_NODES_DETECT = 500
        private const val MIN_CHECK_INTERVAL_MS = 1_200L
        private const val WEB_MAX_CREDIT_MS = 60_000L
        private const val WEB_MIN_CREDIT_MS = 1_000L
        private const val ELEM_MAX_CREDIT_MS = 15_000L
        private const val ELEM_MIN_CREDIT_MS = 2_000L
        private const val DETECT_THROTTLE_MS = 400L
        private const val DETECT_LOG_MS = 5_000L
        private const val BACK_COOLDOWN_MS = 3_000L
        private const val FULLSCREEN_RATIO = 0.55
        /** Smallest matched node that may trigger Back (a nav tab must not). */
        private const val BACK_MIN_COVERAGE = 0.35
        /** Smallest matched node worth covering with an overlay. */
        private const val COVER_MIN_COVERAGE = 0.06

        /** How long the block Activity gets to appear before the cover steps in. */
        private const val BLOCK_FALLBACK_MS = 1_200L

        /** Upper bound between limit re-checks while an app stays foreground. */
        private const val MAX_WATCH_DELAY_MS = 4 * 60_000L

        /** Throttle for the content-change re-check that recovers a lost watch. */
        private const val LIMIT_RECHECK_MS = 20_000L

        // Reel counter: a bounded marker scan, then one count per vertical swipe.
        // The cap is generous because flagIncludeNotImportantViews (needed to
        // see SurfaceView/TextureView players at all) makes every tree bigger,
        // so the video node can sit deeper than it used to.
        private const val REEL_MAX_NODES = 600
        private const val REEL_SCAN_INTERVAL_MS = 2_000L
        /** How long a mark survives a scan that came back empty. */
        private const val REEL_MARK_STICKY_MS = 3_000L
        /** Smallest gap between the scans triggered by a window change. */
        private const val REEL_NAV_SCAN_GAP_MS = 400L
        private const val REEL_MIN_GAP_MS = 450L
        /** Quiet time that ends a swipe gesture; the count lands after it. */
        private const val REEL_COMMIT_QUIET_MS = 400L
        private const val REEL_SURFACE_RATIO = 0.65
        /**
         * Smallest node that can be a conversation/inbox/comment surface. Big
         * enough to exclude a "Messages" button, small enough to catch a
         * comment sheet that only covers the lower half.
         */
        private const val REEL_CHAT_RATIO = 0.22
        /** How long a seen chat surface keeps blocking counts. */
        private const val REEL_BLOCK_HOLD_MS = 3_000L
        /** A count needs a scroll source covering this much of the window. */
        private const val REEL_MIN_SOURCE_RATIO = 0.60f
        private const val REEL_HOLD_MS = 2_600L
        private const val REEL_FADE_IN_MS = 180L
        private const val REEL_FADE_OUT_MS = 700L

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

        /**
         * Name-based fallback: a browser nobody listed still gets its address
         * bar read. The list above covers the popular ones, but the promise is
         * "any browser" — a sideloaded Chromium fork (net.quetta.browser, a
         * vendor build) never appeared in it and silently escaped blocking.
         */
        private val BROWSER_HINTS = listOf("browser", "chromium", "gecko")

        fun isBrowser(pkg: String): Boolean =
            pkg in BROWSERS || BROWSER_HINTS.any { pkg.contains(it, ignoreCase = true) }
    }
}
