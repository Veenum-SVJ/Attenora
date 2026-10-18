package dev.opensource.focusguard.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/**
 * Packages that are never blocked: FocusGuard itself, the launcher, system UI,
 * phone, messaging, contacts, camera and settings — you need these even while
 * focusing (and you need Settings to change anything).
 */
object Allowlist {
    val packages = setOf(
        "dev.opensource.focusguard",
        "com.android.systemui",
        "com.android.launcher",
        "com.android.launcher2",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.miui.securitycenter",
        "com.android.settings",
        "com.android.phone",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.incallui",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.android.contacts",
        "com.android.camera",
        "com.android.camera2",
        "com.oplus.camera",
        "com.miui.camera",
        "org.lineageos.snap"
    )

    fun isAllowlisted(pkg: String): Boolean = pkg in packages
}

/** Pure limit logic, unit-tested on the JVM. */
object LimitEngine {

    data class Decision(val blocked: Boolean, val remainingMs: Long)

    /**
     * @param dailyLimitMs per-app daily limit; null = no per-app limit configured
     * @param blockAllActive true while "Block now" or a block-all schedule is active
     */
    fun evaluate(
        usedMs: Long,
        dailyLimitMs: Long?,
        blockAllActive: Boolean,
        pkg: String
    ): Decision {
        if (Allowlist.isAllowlisted(pkg)) return Decision(blocked = false, remainingMs = Long.MAX_VALUE)
        if (blockAllActive) return Decision(blocked = true, remainingMs = 0L)
        val limit = dailyLimitMs ?: return Decision(blocked = false, remainingMs = Long.MAX_VALUE)
        return dailyDecision(usedMs, limit)
    }

    /** Daily-limit decision for one target (app or category rollup). */
    fun dailyDecision(usedMs: Long, limitMs: Long): Decision {
        val remaining = limitMs - usedMs
        return if (remaining <= 0L) Decision(blocked = true, remainingMs = 0L)
        else Decision(blocked = false, remainingMs = remaining)
    }

    /** True when a variable-session grant still covers [now]. */
    fun sessionGrantActive(untilMs: Long, now: Long): Boolean = untilMs > now

    /**
     * True while a spent session window is cooling down: once a variable-session
     * window ends at [grantUntil], the target stays blocked for [cooldownMs]
     * before a new window may start. A [grantUntil] of 0 means no window has run
     * yet, so there is nothing to cool down from.
     */
    fun sessionCoolingDown(grantUntil: Long, cooldownMs: Long, now: Long): Boolean =
        grantUntil > 0L && grantUntil <= now && cooldownMs > 0L && now < grantUntil + cooldownMs

    /** When the post-session cooldown ends (0 when no cooldown applies). */
    fun cooldownEnd(grantUntil: Long, cooldownMs: Long): Long =
        if (grantUntil > 0L && cooldownMs > 0L) grantUntil + cooldownMs else 0L

    /** What a fixed-session limit should do for the current instant. */
    enum class SessionAction { ACTIVE, COOLDOWN, GRANT }

    /**
     * Ordering matters here: an active window wins, then a configured cooldown
     * that is still running, then — when no cooldown is configured at all — the
     * block holds until the day rolls over, and only otherwise does a fresh
     * fixed session get granted. Getting this order wrong once made every
     * app stay blocked until midnight after its cooldown had already elapsed.
     */
    fun sessionAction(grantUntil: Long, cooldownMs: Long, now: Long): SessionAction = when {
        sessionGrantActive(grantUntil, now) -> SessionAction.ACTIVE
        sessionCoolingDown(grantUntil, cooldownMs, now) -> SessionAction.COOLDOWN
        grantUntil > 0L && cooldownMs <= 0L -> SessionAction.COOLDOWN
        else -> SessionAction.GRANT
    }

    /**
     * Fallback deadline for a spent session whose limit has no cooldown set:
     * the target stays blocked until the next local midnight, so a session
     * limit can never quietly degrade into an unlimited one.
     */
    fun dayEndMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Minutes offered by a variable-session limit ("15,30,60,1440"), parsed
     * defensively; falls back to the standard preset on garbage input.
     */
    fun parseSessionChoices(raw: String): List<Int> {
        val parsed = raw.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..1440 }
            .distinct()
            .sorted()
        return parsed.ifEmpty { listOf(15, 30, 60, 1440) }
    }
}

/**
 * Pure "usage day" math: a usage day starts at the configurable reset hour,
 * not at midnight — so 02:00 still belongs to yesterday when reset is 03:00.
 */
object ResetEngine {

    /** Usage-day key ("yyyy-MM-dd") for an instant. resetHour in 0..23. */
    fun dayKey(epochMs: Long, resetHour: Int, zone: ZoneId = ZoneId.systemDefault()): String =
        instantToDate(epochMs, resetHour, zone).toString()

    fun instantToDate(epochMs: Long, resetHour: Int, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
            .let { date -> if (date.atStartOfDay(zone).plusHours(resetHour.coerceIn(0, 23).toLong()).toInstant().toEpochMilli() > epochMs) date.minusDays(1) else date }

    /** Inclusive list of usage-day keys between two display dates. */
    fun dayKeys(from: LocalDate, to: LocalDate): List<String> {
        val out = ArrayList<String>()
        var d = from
        while (!d.isAfter(to)) {
            out.add(d.toString())
            d = d.plusDays(1)
        }
        return out
    }

    /** 24 hour-buckets (offset by reset hour) for one usage day. */
    fun hourBuckets(day: LocalDate, resetHour: Int): List<String> {
        // Labels are absolute clock hours 0..23 for display ("4", "5", … "10").
        return (0 until 24).map { ((it + resetHour) % 24).toString() }
    }

    fun hourOfDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        Instant.ofEpochMilli(epochMs).atZone(zone).hour
}

/** First-day-of-week aware ranges for date filters. */
object WeekEngine {

    /** Monday-based (bit 0 = Monday) mask helper. */
    fun dayBit(mon0: Int): Int = 1 shl mon0

    fun allDaysMask(): Int = 0x7F

    /** Start (Monday-or-Sunday) of the week containing [date]. */
    fun weekStart(date: LocalDate, mondayFirst: Boolean): LocalDate =
        if (mondayFirst) date.with(DayOfWeek.MONDAY)
        else date.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    /**
     * Period presets for the Usage Stats date filter.
     * [days] = 1 (Today), 2 (Yesterday), 7/14/28.
     * Returns inclusive [from, to] display dates ending at [anchor].
     */
    fun presetRange(presetDays: Int, anchor: LocalDate, mondayFirst: Boolean): Pair<LocalDate, LocalDate> = when (presetDays) {
        1 -> anchor to anchor
        2 -> anchor.minusDays(1) to anchor.minusDays(1)
        else -> {
            val start = weekStart(anchor, mondayFirst).minusDays(((presetDays / 7) - 1L) * 7L)
            start to start.plusDays(presetDays - 1L)
        }
    }
}

/**
 * Challenge gate for editing limits / toggling blocking / removing the PIN.
 * Quote challenge: type the shown sentence (normalized comparison).
 * Math challenge: solve a small arithmetic problem.
 */
object ChallengeEngine {

    val defaultQuotes = listOf(
        "I am choosing to protect my time and attention.",
        "Small boundaries today build a calmer tomorrow.",
        "I decide when I pick up my phone, not the other way around.",
        "This limit exists because my future self asked for it.",
        "Discipline is remembering what I want most.",
        "Every minute I reclaim is a minute I get to keep."
    )

    fun pickQuote(rng: Random = Random.Default): String =
        defaultQuotes[rng.nextInt(defaultQuotes.size)]

    /** Lowercase, drop punctuation, collapse whitespace — forgiving but honest. */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[\\p{Punct}\\p{Sm}\\p{Sc}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun matchesQuote(input: String, quote: String): Boolean =
        normalize(input) == normalize(quote)

    data class MathProblem(val a: Int, val b: Int, val op: Char, val answer: Int) {
        fun prompt(): String = "$a ${symbol(op)} $b = ?"
        private fun symbol(op: Char) = when (op) {
            '+' -> "+"
            '-' -> "−"
            '×' -> "×"
            else -> op.toString()
        }
    }

    fun randomMath(rng: Random = Random.Default): MathProblem = when (rng.nextInt(4)) {
        0 -> {
            val a = rng.nextInt(11, 49); val b = rng.nextInt(11, 49)
            MathProblem(a, b, '+', a + b)
        }
        1 -> {
            val a = rng.nextInt(30, 90); val b = rng.nextInt(10, a)
            MathProblem(a, b, '-', a - b)
        }
        2 -> {
            val a = rng.nextInt(3, 13); val b = rng.nextInt(3, 13)
            MathProblem(a, b, '×', a * b)
        }
        else -> {
            val b = rng.nextInt(3, 12); val ans = rng.nextInt(3, 12)
            MathProblem(ans * b, b, '×', ans * b * b)
        }
    }

    fun checkMath(input: String, problem: MathProblem): Boolean =
        input.trim().toIntOrNull() == problem.answer
}

/**
 * Pure unlock-extra logic (Curbox ports): per-day unlock quota and state
 * persistence decisions. Unit-tested on the JVM.
 */
object UnlockEngine {

    /** Delay before unlock buttons become tappable, in 0..60 seconds. */
    fun clampDelaySeconds(seconds: Int): Int = seconds.coerceIn(0, 60)

    /**
     * May the prompt grant another unlock right now?
     * [quota] = 0 means unlimited; otherwise the count resets on the usage day.
     */
    fun canUnlock(
        now: Long,
        usedDay: String,
        usedCount: Int,
        quota: Int,
        resetHour: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Boolean {
        if (quota <= 0) return true
        val today = ResetEngine.dayKey(now, resetHour, zone)
        val used = if (today == usedDay) usedCount else 0
        return used < quota
    }

    /** The (dayKey, count) to persist after one more unlock is granted. */
    fun recordUnlock(
        now: Long,
        usedDay: String,
        usedCount: Int,
        quota: Int,
        resetHour: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Pair<String, Int> {
        val today = ResetEngine.dayKey(now, resetHour, zone)
        val used = if (today == usedDay) usedCount else 0
        return today to (used + 1)
    }
}

/**
 * Pure focus-session accrual: completed session minutes land on the usage
 * day the session started on; only time actually spent is counted when a
 * session ends early. Unit-tested on the JVM.
 */
object FocusSessionEngine {

    data class Session(val startMs: Long, val endMs: Long)
    data class Accrual(val dayKey: String, val minutes: Int)

    val empty = Accrual("", 0)

    /** Minutes accrued into [accrual] today, per the usage-day clock. */
    fun accruedToday(
        accrual: Accrual,
        now: Long,
        resetHour: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int =
        if (accrual.dayKey == ResetEngine.dayKey(now, resetHour, zone)) accrual.minutes else 0

    /** Fold a finished session (or an ended-early one) into [accrual]. */
    fun accrue(
        accrual: Accrual,
        session: Session,
        now: Long,
        resetHour: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Accrual {
        val effectiveEnd = minOf(session.endMs, now)
        val minutes = ((effectiveEnd - session.startMs) / 60_000L).coerceAtLeast(0L).toInt()
        if (minutes <= 0) return accrual
        val day = ResetEngine.dayKey(session.startMs, resetHour, zone)
        return if (accrual.dayKey == day) accrual.copy(minutes = accrual.minutes + minutes)
        else Accrual(day, minutes)
    }
}

/** Block-keyword matching against page URLs / titles (website blocking). */
object KeywordMatcher {
    /** Returns the keyword hit inside [text], case-insensitively, else null. */
    fun match(text: String, keywords: Collection<String>): String? {
        if (text.isEmpty()) return null
        val hay = text.lowercase()
        for (raw in keywords) {
            val k = raw.trim().lowercase()
            if (k.isNotEmpty() && hay.contains(k)) return k
        }
        return null
    }
}

/** Pure schedule-window logic, unit-tested on the JVM. */
object ScheduleEngine {

    /**
     * @param dayOfWeekMon0 0 = Monday … 6 = Sunday
     * @param minuteOfDay 0..1439
     * Overnight schedules (end < start) run from the selected day past midnight
     * into the following morning.
     */
    fun isActive(dayBitSelected: Boolean, prevDayBitSelected: Boolean, startMinute: Int, endMinute: Int, minuteOfDay: Int): Boolean =
        if (endMinute > startMinute) {
            dayBitSelected && minuteOfDay in startMinute until endMinute
        } else {
            (dayBitSelected && minuteOfDay >= startMinute) || (prevDayBitSelected && minuteOfDay < endMinute)
        }

    fun daySelected(daysMask: Int, dayOfWeekMon0: Int): Boolean =
        (daysMask shr dayOfWeekMon0) and 1 == 1
}

/** Pure URL/host logic, unit-tested on the JVM. */
object UrlMatcher {

    /**
     * Accepts anything address-like ("https://www.x.com/watch?v=1", "x.com",
     * "https://user:pass@x.com:443/a b") and returns a canonical host, or null.
     */
    fun normalizeDomain(input: String): String? {
        var s = input.trim().lowercase()
        if (s.isEmpty()) return null
        s = s.substringAfter("://", s)
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        s = s.substringBefore('#')
        s = s.substringAfter('@')
        s = s.substringBefore(':') // drop port
        while (s.startsWith("www.")) s = s.removePrefix("www.")
        s = s.trim('.')
        if (!s.contains('.')) return null
        if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        if (s.startsWith(".") || s.endsWith(".")) return null
        return s
    }

    fun looksLikeWebAddress(input: String): Boolean = normalizeDomain(input) != null

    /** Returns the blocklist entry that matches the host (exact or parent domain), else null. */
    fun matches(host: String, blocklist: Collection<String>): String? {
        val h = host.lowercase().removePrefix("www.")
        for (raw in blocklist) {
            val b = normalizeDomain(raw)?.removePrefix("www.") ?: continue
            if (h == b || h.endsWith(".$b")) return b
        }
        return null
    }
}
