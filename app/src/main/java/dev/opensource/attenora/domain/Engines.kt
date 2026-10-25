package dev.opensource.attenora.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/**
 * Packages that are never blocked: Attenora itself, the launcher, system UI,
 * phone, messaging, contacts, camera and settings — you need these even while
 * focusing (and you need Settings to change anything).
 */
object Allowlist {
    val packages = setOf(
        "dev.opensource.attenora",
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
     * End of a session window as the user experiences it: the stored end,
     * pushed forward by the time the target has spent away from the
     * foreground. A session is meant to be used time — leaving the app pauses
     * it (marked by [pausedAtMs]) rather than letting the window run out on
     * the wall clock. While paused the end keeps moving with `now`, so the
     * remaining time reads as frozen; [pausedAtMs] in the future (clock skew)
     * never extends backwards, and a marker written at or after the end of the
     * window (which pausing never does — a spent window is left to its cooldown)
     * extends nothing.
     */
    fun effectiveGrantEnd(grantUntilMs: Long, pausedAtMs: Long, now: Long): Long =
        if (pausedAtMs > 0L && pausedAtMs < grantUntilMs) {
            grantUntilMs + (now - pausedAtMs).coerceAtLeast(0L)
        } else {
            grantUntilMs
        }

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
 * Challenge gate for editing limits / toggling blocking / erasing data.
 *
 * Awareness-text challenge (StayFree-style): retype the shown passage EXACTLY.
 * Case and punctuation are part of the test — [normalize] only forgives
 * characters a phone keyboard cannot easily produce (curly quotes, en/em
 * dashes, the ellipsis, a non-breaking space), never the words, the capitals
 * or the commas themselves.
 * Math challenge: solve a small arithmetic problem.
 */
object ChallengeEngine {

    /** One passage of the awareness set, with an optional credit line. */
    data class Quote(val text: String, val source: String)

    /**
     * StayFree's own awareness pool: the 104 English texts of StayFree 20.11.0's
     * `alarm_screen_blocked_text` string set, extracted from that APK, with the
     * decorative curly quotes stripped and the remaining typography converted to
     * plain ASCII (straight apostrophes, hyphens) so every character is
     * reachable on a normal keyboard. 14-216 characters, no attribution —
     * StayFree shows no credit line either, so [Quote.source] stays blank.
     */
    val quotes: List<Quote> = listOf(
        Quote("Productivity is never an accident. It is always the result of a commitment to excellence, intelligent planning, and focused effort.", ""),
        Quote("The bad news is time flies. The good news is you are the pilot.", ""),
        Quote("Those, who make the worse use of their time, are the first to complain of its shortness.", ""),
        Quote("Time is what we want most, but what we use worst.", ""),
        Quote("It's not enough to be busy, so are the ants. The question is, what are we busy about?", ""),
        Quote("Until you value yourself, you will not value your time. Until you value your time, you will not do anything with it.", ""),
        Quote("Time is really the only capital that any human being has, and the only thing he can't afford to lose.", ""),
        Quote("Your future is created by what you do today, not tomorrow.", ""),
        Quote("You may delay, but time will not.", ""),
        Quote("Ordinary people think merely of spending time. Great people think of using it.", ""),
        Quote("Lost wealth may be replaced by industry, lost knowledge by study, lost health by temperance or medicine, but lost time is gone forever.", ""),
        Quote("He who know most grieves most for wasted time.", ""),
        Quote("Don't say you don't have enough time. You have exactly the same number of hours per day that were given to Helen Keller, Pasteur, Michelangelo, Mother Teresa, Leonardo da Vinci, Thomas Jefferson, and Albert Einstein.", ""),
        Quote("A year from now you will wish you had started today.", ""),
        Quote("The surest way to be late is to have plenty of time.", ""),
        Quote("You can't get much done in life if you only work on days when you feel good.", ""),
        Quote("Change your 24 hours and you will change your life.", ""),
        Quote("Procrastination is the foundation of all disasters.", ""),
        Quote("Better three hours too soon than a minute too late.", ""),
        Quote("The secret of your future is hidden in your daily routine.", ""),
        Quote("Time is the wisest counselor of all.", ""),
        Quote("Lost time is never found again.", ""),
        Quote("The best time to plant a tree is twenty years ago. The second best time is now.", ""),
        Quote("Until we can manage time, we can manage nothing else.", ""),
        Quote("The way we spend our time defines who we are.", ""),
        Quote("Time is the most valuable thing a man can spend.", ""),
        Quote("The trouble is, you think you have time.", ""),
        Quote("Efficiency is doing things right; effectiveness is doing the right things.", ""),
        Quote("Time management is life management.", ""),
        Quote("Time has no meaning in itself unless we choose to give it significance.", ""),
        Quote("Time flies over us, but leaves its shadow behind.", ""),
        Quote("You can't make up for lost time. You can only do better in the future.", ""),
        Quote("There's only one thing more precious than our time and that's who we spend it on.", ""),
        Quote("If you love life, don't waste time, for time is what life is made up of.", ""),
        Quote("Time slips away like grains of sand never to return again.", ""),
        Quote("A man who dares to waste one hour of time has not discovered the value of life.", ""),
        Quote("The time I kill is killing me.", ""),
        Quote("Time waits for no one.", ""),
        Quote("There's never enough time to do all the nothing you want.", ""),
        Quote("It's not that we have little time, but more that we waste a good deal of it.", ""),
        Quote("Either you run the day, or the day runs you.", ""),
        Quote("All we have to decide is what to do with the time that is given us.", ""),
        Quote("The time for action is now. It's never too late to do something.", ""),
        Quote("We must use time as a tool, not as a crutch.", ""),
        Quote("The common man is not concerned about the passage of time, the man of talent is driven by it.", ""),
        Quote("We must use time wisely and forever realize that the time is always ripe to do right.", ""),
        Quote("This is the key to time management - to see the value of every moment.", ""),
        Quote("Time is money.", ""),
        Quote("Time management requires self-discipline, self-mastery and self-control more than anything else.", ""),
        Quote("You will never 'find' time for anything. If you want time, you must make it.", ""),
        Quote("In truth, people can generally make time for what they choose to do; it is not really the time but the will that is lacking.", ""),
        Quote("Time = life; therefore, waste your time and waste of your life, or master your time and master your life.", ""),
        Quote("The two most powerful warriors are patience and time.", ""),
        Quote("Don't wait for desperate times! Take action now, only if only baby steps.", ""),
        Quote("You're becoming the type of person who chooses meaningful activities over mindless scrolling.", ""),
        Quote("Look how far you've come-every minute you spend away from unnecessary apps strengthens your new habits.", ""),
        Quote("Remember, you control your screen time, not the other way around.", ""),
        Quote("Before tapping the next app, take a breath. Is this helping you become who you want to be?", ""),
        Quote("You've committed to a healthier digital balance. Every positive choice you make today matters.", ""),
        Quote("Your goal: more focus, less distraction. You're showing yourself it's possible.", ""),
        Quote("Your new habits are taking root. Keep nurturing them and watch them grow.", ""),
        Quote("You have the power to shape your digital experience into something productive and meaningful.", ""),
        Quote("Every small improvement you make today brings you closer to your ideal balance.", ""),
        Quote("You're in control of your choices-stay mindful and keep going.", ""),
        Quote("What you focus on grows. Keep building positive habits.", ""),
        Quote("You're creating more time for the things that truly matter.", ""),
        Quote("Small steps lead to big change. You're on the right track.", ""),
        Quote("You're becoming more intentional with every mindful decision.", ""),
        Quote("Each moment you spend away from distractions is a win for your future self.", ""),
        Quote("Keep going-you're proving that you can master your screen time.", ""),
        Quote("Every mindful action is a step toward your goals.", ""),
        Quote("You're stronger than any urge to mindlessly scroll. Stay focused.", ""),
        Quote("Your future self will thank you for the choices you're making today.", ""),
        Quote("You're redefining your relationship with your phone, one habit at a time.", ""),
        Quote("Less scrolling, more living-you're doing great.", ""),
        Quote("You've started a powerful shift. Keep it up.", ""),
        Quote("You're staying present, and that's a real strength.", ""),
        Quote("Real change comes from consistency, and you're showing up.", ""),
        Quote("You're training your brain to focus better every day.", ""),
        Quote("You're showing discipline, and that's impressive.", ""),
        Quote("Is opening this app helping you right now-or just filling space?", ""),
        Quote("Take a second. Breathe. Is this the best use of your time?", ""),
        Quote("Pause and ask yourself: what do I really want to do right now?", ""),
        Quote("You have the power to stop, reflect, and choose differently.", ""),
        Quote("Your next choice can shift your whole day.", ""),
        Quote("What you do now creates the momentum for what's next.", ""),
        Quote("One breath. One pause. One better decision.", ""),
        Quote("Awareness begins with a single moment of reflection.", ""),
        Quote("Not everything requires your attention-choose wisely.", ""),
        Quote("You are creating a life you're proud of.", ""),
        Quote("You're proving that discipline can be gentle and powerful.", ""),
        Quote("The change you seek is already within you.", ""),
        Quote("You're writing a better story with each small decision.", ""),
        Quote("You're becoming the focused, intentional person you dreamed of being.", ""),
        Quote("Your habits shape your destiny-and you're shaping them.", ""),
        Quote("Less distraction, more depth-that's your path.", ""),
        Quote("You're here because you wanted change. And it's working.", ""),
        Quote("Your goals matter. Keep choosing what aligns with them.", ""),
        Quote("You have the final say in how you spend your time.", ""),
        Quote("You're powerful enough to pause, think, and act mindfully.", ""),
        Quote("Each moment is a new chance to choose better.", ""),
        Quote("You are behind the wheel of your habits.", ""),
        Quote("You're becoming the kind of person who uses technology with intention.", ""),
        Quote("You are not your habits-you're the one who creates them.", "")
    )

    fun pickQuote(rng: Random = Random.Default): Quote =
        quotes[rng.nextInt(quotes.size)]

    /**
     * One sentence of the awareness set, for the block screen. The gate needs a
     * full passage to retype; a cover only needs a line to read while it is up,
     * so this returns the first sentence of a random passage instead of the
     * whole paragraph.
     */
    fun pickShortLine(rng: Random = Random.Default): String {
        val text = pickQuote(rng).text
        val stop = text.indexOf(". ")
        return if (stop > 0) text.substring(0, stop + 1) else text
    }

    /**
     * Maps what a keyboard produces onto what a page displays. This is the only
     * forgiveness in the comparison: capitalisation, punctuation and spacing
     * inside the line are all compared exactly as typed.
     */
    fun normalize(text: String): String =
        text
            .replace('\u2018', '\'').replace('\u2019', '\'')
            .replace('\u201C', '"').replace('\u201D', '"')
            .replace('\u2013', '-').replace('\u2014', '-')
            .replace("\u2026", "...")
            .replace('\u00A0', ' ')
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
