package dev.opensource.focusguard.enforcement

import android.content.Context
import dev.opensource.focusguard.data.AppDatabase
import dev.opensource.focusguard.data.BlockEvent
import dev.opensource.focusguard.data.FocusSchedule
import dev.opensource.focusguard.data.LimitType
import dev.opensource.focusguard.data.SettingsRepository
import dev.opensource.focusguard.data.TargetKind
import dev.opensource.focusguard.data.UsageLimit
import dev.opensource.focusguard.data.UsageRepository
import dev.opensource.focusguard.domain.Allowlist
import dev.opensource.focusguard.domain.KeywordMatcher
import dev.opensource.focusguard.domain.LimitEngine
import dev.opensource.focusguard.domain.ResetEngine
import dev.opensource.focusguard.domain.ScheduleEngine
import dev.opensource.focusguard.ui.blocking.BlockOverlayActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * The decision brain. Called on foreground transitions from the accessibility
 * monitor, on schedule-start alarms, and on website hits. Never polls.
 *
 * Typed-limit precedence (highest first):
 *   block-now > focus schedule (block-all) > permanent > schedule window >
 *   daily exhaustion > variable-session prompt.
 */
class EnforcementManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    private val usage: UsageRepository,
    private val scope: CoroutineScope
) {

    companion object {
        private const val RECONCILE_THROTTLE_MS = 30_000L
        private const val OVERLAY_THROTTLE_MS = 2_500L
        private const val WEBSITE_OVERLAY_THROTTLE_MS = 1_500L
        private const val BLOCK_LOG_THROTTLE_MS = 60_000L
        const val FIVE_MIN_MS = 5 * 60_000L
    }

    /**
     * blocked / remaining time / why. When [autoSession] is set, a SESSION
     * limit has no active grant yet — the caller starts it silently with the
     * limit's fixed length (no prompt; the "how many minutes?" dialog is gone).
     * [availableAtMs] is set for the cooldown that follows a spent session.
     */
    data class Decision(
        val blocked: Boolean,
        val remainingMs: Long = Long.MAX_VALUE,
        val reason: String = "",
        val autoSession: AutoSession? = null,
        val availableAtMs: Long = 0L
    )

    /** target to grant -> fixed session length in minutes. */
    data class AutoSession(val targetId: String, val minutes: Int)

    val limits: StateFlow<List<UsageLimit>> =
        db.usageLimitDao().observeAll()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val schedules: StateFlow<List<FocusSchedule>> =
        db.focusScheduleDao().observeAll()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val sessionGrants: StateFlow<Map<String, Long>> =
        db.sessionGrantDao().observeAll()
            .map { list -> list.associate { it.targetId to it.untilMs } }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val categoryMembers: StateFlow<List<dev.opensource.focusguard.data.CategoryMember>> =
        db.categoryMemberDao().observeAll()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val keywords: StateFlow<List<String>> =
        db.blockKeywordDao().observeAll()
            .map { list -> list.map { it.keyword } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val blockAllUntil: StateFlow<Long> =
        settings.blockAllUntil.stateIn(scope, SharingStarted.Eagerly, 0L)

    @Volatile private var lastReconcileAt = 0L
    @Volatile private var lastOverlayAt = 0L
    @Volatile private var lastBlockLogAt = 0L

    /** pkg -> usage-day key we already warned about ("5 minutes left"). */
    private val fiveMinWarned = HashMap<String, String>(8)

    fun onForeground(pkg: String?) {
        usage.noteForeground(pkg)
        val target = pkg ?: return
        scope.launch { evaluate(target) }
    }

    suspend fun evaluate(pkg: String, now: Long = System.currentTimeMillis()): Decision {
        if (pkg == context.packageName) return Decision(blocked = false)
        if (Allowlist.isAllowlisted(pkg)) return Decision(blocked = false)

        if (now - lastReconcileAt > RECONCILE_THROTTLE_MS) {
            lastReconcileAt = now
            runCatching { usage.reconcileNow() }
        }

        val decision = decisionFor(pkg, now)

        if (decision.blocked) {
            if (BlockOverlayActivity.visible) return decision
            if (now - lastOverlayAt < OVERLAY_THROTTLE_MS) return decision
            lastOverlayAt = now
            logBlock(pkg, decision.reason, now)
            Notifier.onBlockHit(context, pkg, decision.reason)
            BlockOverlayActivity.launch(context, pkg, decision.reason, untilMs = decision.availableAtMs)
            return decision
        }

        // Variable session limit with no active grant → start the fixed-
        // length session silently (set once when the limit was created).
        decision.autoSession?.let { auto ->
            db.sessionGrantDao().upsert(
                dev.opensource.focusguard.data.SessionGrant(
                    targetId = auto.targetId,
                    untilMs = now + auto.minutes * 60_000L
                )
            )
        }

        maybeWarnFiveMinutes(pkg, decision.remainingMs, now)
        return decision
    }

    /** Pure-ish decision for [pkg]; safe to call from the UI for countdowns. */
    suspend fun decisionFor(pkg: String, now: Long = System.currentTimeMillis()): Decision {
        if (pkg == context.packageName || Allowlist.isAllowlisted(pkg)) return Decision(blocked = false)

        // 1. Block-now.
        if (blockAllUntil.value > now) return Decision(blocked = true, reason = BlockOverlayActivity.REASON_BLOCK_ALL)

        // 2. Focus/sleep schedule (block-all or limited-apps mode).
        val focus = activeScheduleAt(now)
        if (focus != null && focus.blockAll) {
            return Decision(blocked = true, reason = BlockOverlayActivity.REASON_SCHEDULE)
        }

        val mon0 = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).dayOfWeek.value - 1

        // 3+4+5+6. Typed limits that match this package (directly or by category).
        var remaining = Long.MAX_VALUE
        var autoSession: AutoSession? = null

        for (limit in limits.value) {
            if (!limit.enabled) continue
            if (limit.type != LimitType.PERMANENT && !ScheduleEngine.daySelected(limit.daysMask, mon0)) continue
            if (!targetCovers(limit, pkg)) continue

            when (limit.type) {
                LimitType.PERMANENT ->
                    return Decision(blocked = true, reason = BlockOverlayActivity.REASON_LIMIT)

                LimitType.SCHEDULE -> {
                    val prevMon0 = (mon0 + 6) % 7
                    val minuteOfDay = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).let { it.hour * 60 + it.minute }
                    val active = ScheduleEngine.isActive(
                        ScheduleEngine.daySelected(limit.daysMask, mon0),
                        ScheduleEngine.daySelected(limit.daysMask, prevMon0),
                        limit.startMinute, limit.endMinute, minuteOfDay
                    )
                    if (active) return Decision(blocked = true, reason = BlockOverlayActivity.REASON_SCHEDULE)
                }

                LimitType.DAILY -> {
                    // Website visits may be excluded from daily accounting.
                    val ignoreWebsites = settings.ignoreWebsites.first()
                    if (ignoreWebsites && pkg in FocusGuardAccessibilityService.BROWSERS) continue
                    val used = usedForLimit(limit, pkg, now)
                    val decision = LimitEngine.dailyDecision(used, limit.dailyLimitMs)
                    if (decision.blocked) {
                        return Decision(blocked = true, reason = BlockOverlayActivity.REASON_LIMIT)
                    }
                    remaining = minOf(remaining, decision.remainingMs)
                }

                LimitType.SESSION -> {
                    val target = sessionTarget(limit, pkg)
                    val grantUntil = sessionGrants.value[target] ?: 0L
                    when (LimitEngine.sessionAction(grantUntil, limit.cooldownMs, now)) {
                        LimitEngine.SessionAction.ACTIVE -> {
                            // Count the live session toward "remaining" so the
                            // 5-minute-left warning fires as the window closes.
                            remaining = minOf(remaining, grantUntil - now)
                        }
                        LimitEngine.SessionAction.COOLDOWN -> {
                            // Session spent: stay blocked until the cooldown
                            // elapses (or, with no cooldown configured, until
                            // the day rolls over), then a fresh session opens.
                            val end = LimitEngine.cooldownEnd(grantUntil, limit.cooldownMs)
                                .takeIf { it > now } ?: LimitEngine.dayEndMillis(now)
                            return Decision(
                                blocked = true,
                                remainingMs = (end - now).coerceAtLeast(0L),
                                reason = BlockOverlayActivity.REASON_COOLDOWN,
                                availableAtMs = end
                            )
                        }
                        LimitEngine.SessionAction.GRANT -> {
                            // Grant the fixed length stored when the limit was made.
                            autoSession = AutoSession(target, sessionMinutes(limit))
                        }
                    }
                }
            }
        }

        // 7. Website daily limits (attributed as "web:<domain>" usage rows).
        val siteDecision = websiteDecision(pkg, now)
        if (siteDecision != null) {
            if (siteDecision.blocked) return siteDecision
            remaining = minOf(remaining, siteDecision.remainingMs)
        }

        return Decision(blocked = false, remainingMs = remaining, autoSession = autoSession)
    }

    /** Does [limit] target this package directly, via its category, or everything? */
    private fun targetCovers(limit: UsageLimit, pkg: String): Boolean = when (limit.targetKind) {
        TargetKind.APP -> limit.targetId == pkg
        TargetKind.ALL -> true
        TargetKind.CATEGORY -> {
            val catId = limit.targetId.toLongOrNull()
            catId != null && categoryMembers.value.any {
                it.categoryId == catId && it.key == "app:$pkg"
            }
        }
        else -> false
    }

    private fun sessionTarget(limit: UsageLimit, pkg: String): String =
        if (limit.targetKind == TargetKind.CATEGORY || limit.targetKind == TargetKind.ALL) pkg else limit.targetId

    /** Fixed session length for [limit], stored in minutes in dailyLimitMs. */
    private fun sessionMinutes(limit: UsageLimit): Int =
        (limit.dailyLimitMs / 60_000L).toInt().coerceIn(1, 10_080)

    private suspend fun usedForLimit(limit: UsageLimit, pkg: String, now: Long): Long = when (limit.targetKind) {
        TargetKind.CATEGORY -> {
            val catId = limit.targetId.toLongOrNull() ?: 0L
            val keys = categoryMembers.value.filter { it.categoryId == catId && it.key.startsWith("app:") }
                .map { it.key.removePrefix("app:") }
            var total = 0L
            for (member in keys) total += usage.usedMsToday(member, now)
            total
        }
        else -> usage.usedMsToday(pkg, now)
    }

    /** Daily decision for website-target limits while a browser is foreground. */
    private suspend fun websiteDecision(browserPkg: String, now: Long): Decision? {
        if (browserPkg !in FocusGuardAccessibilityService.BROWSERS) return null
        var remaining = Long.MAX_VALUE
        var hit = false
        for (limit in websiteDailyLimits()) {
            val used = usage.websiteMsToday(limit.targetId, now)
            val d = LimitEngine.dailyDecision(used, limit.dailyLimitMs)
            hit = true
            if (d.blocked) return Decision(blocked = true, reason = BlockOverlayActivity.REASON_WEBSITE)
            remaining = minOf(remaining, d.remainingMs)
        }
        return if (hit) Decision(blocked = false, remainingMs = remaining) else null
    }

    fun websiteDailyLimits(): List<UsageLimit> =
        limits.value.filter {
            it.enabled && it.type == LimitType.DAILY && it.targetKind == TargetKind.WEBSITE
        }

    /** True when [domain] (or its parent) has exhausted its daily usage limit. */
    suspend fun domainLimitExhausted(domain: String, now: Long = System.currentTimeMillis()): Boolean {
        for (limit in websiteDailyLimits()) {
            if (dev.opensource.focusguard.domain.UrlMatcher.matches(domain, listOf(limit.targetId)) == null) continue
            val used = usage.websiteMsToday(limit.targetId, now)
            if (LimitEngine.dailyDecision(used, limit.dailyLimitMs).blocked) return true
        }
        return false
    }

    /** Remaining website-limit time for countdown overlays (MAX_VALUE = none). */
    suspend fun websiteRemainingMs(now: Long = System.currentTimeMillis()): Long {
        var remaining = Long.MAX_VALUE
        for (limit in websiteDailyLimits()) {
            val used = usage.websiteMsToday(limit.targetId, now)
            remaining = minOf(remaining, LimitEngine.dailyDecision(used, limit.dailyLimitMs).remainingMs)
        }
        return remaining
    }

    suspend fun blockWebsite(
        domain: String,
        browserPkg: String,
        now: Long = System.currentTimeMillis()
    ) {
        if (browserPkg == context.packageName) return
        if (Allowlist.isAllowlisted(browserPkg)) return
        if (BlockOverlayActivity.visible) return
        if (now - lastOverlayAt < WEBSITE_OVERLAY_THROTTLE_MS) return
        lastOverlayAt = now
        logBlock(domain, BlockOverlayActivity.REASON_WEBSITE, now)
        Notifier.onBlockHit(context, domain, BlockOverlayActivity.REASON_WEBSITE)
        BlockOverlayActivity.launch(context, browserPkg, BlockOverlayActivity.REASON_WEBSITE, domain)
    }

    /** Block-keyword check for extracted page text (called by the accessibility service). */
    suspend fun keywordHit(text: String): String? {
        if (keywords.value.isEmpty()) return null
        return KeywordMatcher.match(text, keywords.value)
    }

    fun activeScheduleAt(now: Long): FocusSchedule? {
        val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val mon0 = zdt.dayOfWeek.value - 1
        val minuteOfDay = zdt.hour * 60 + zdt.minute
        val prevMon0 = (mon0 + 6) % 7
        return schedules.value.firstOrNull { s ->
            s.enabled && ScheduleEngine.isActive(
                dayBitSelected = ScheduleEngine.daySelected(s.daysMask, mon0),
                prevDayBitSelected = ScheduleEngine.daySelected(s.daysMask, prevMon0),
                startMinute = s.startMinute,
                endMinute = s.endMinute,
                minuteOfDay = minuteOfDay
            )
        }
    }

    private fun logBlock(target: String, reason: String, now: Long) {
        if (now - lastBlockLogAt < BLOCK_LOG_THROTTLE_MS) return
        lastBlockLogAt = now
        scope.launch {
            runCatching { db.blockEventDao().insert(BlockEvent(target = target, reason = reason, at = now)) }
        }
    }

    private suspend fun maybeWarnFiveMinutes(pkg: String, remainingMs: Long, now: Long) {
        if (remainingMs == Long.MAX_VALUE || remainingMs > FIVE_MIN_MS || remainingMs <= 0L) return
        if (!settings.fiveMinWarn.first()) return
        val day = ResetEngine.dayKey(now, 0)
        if (fiveMinWarned[pkg] == day) return
        fiveMinWarned[pkg] = day
        Notifier.onFiveMinutes(context, pkg)
    }
}
