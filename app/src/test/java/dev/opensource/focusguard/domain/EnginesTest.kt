package dev.opensource.focusguard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class LimitEngineTest {

    private val app = "com.some.app"

    @Test
    fun `no limit means never blocked`() {
        val decision = LimitEngine.evaluate(usedMs = 10 * 60_000, dailyLimitMs = null, blockAllActive = false, pkg = app)
        assertFalse(decision.blocked)
    }

    @Test
    fun `under limit is not blocked and reports remaining`() {
        val decision = LimitEngine.evaluate(usedMs = 30_000, dailyLimitMs = 60_000, blockAllActive = false, pkg = app)
        assertFalse(decision.blocked)
        assertEquals(30_000, decision.remainingMs)
    }

    @Test
    fun `exactly at limit blocks`() {
        val decision = LimitEngine.evaluate(usedMs = 60_000, dailyLimitMs = 60_000, blockAllActive = false, pkg = app)
        assertTrue(decision.blocked)
    }

    @Test
    fun `over limit blocks`() {
        val decision = LimitEngine.evaluate(usedMs = 90_000, dailyLimitMs = 60_000, blockAllActive = false, pkg = app)
        assertTrue(decision.blocked)
    }

    @Test
    fun `block-all blocks an unlimited app`() {
        val decision = LimitEngine.evaluate(usedMs = 1, dailyLimitMs = null, blockAllActive = true, pkg = app)
        assertTrue(decision.blocked)
    }

    @Test
    fun `allowlisted packages are never blocked`() {
        val decision = LimitEngine.evaluate(usedMs = 10_000_000, dailyLimitMs = 1_000, blockAllActive = true, pkg = "com.android.dialer")
        assertFalse(decision.blocked)
    }

    @Test
    fun `focusguard itself is never blocked`() {
        val decision = LimitEngine.evaluate(usedMs = 10_000_000, dailyLimitMs = 1_000, blockAllActive = true, pkg = "dev.opensource.focusguard")
        assertFalse(decision.blocked)
    }
}

class ScheduleEngineTest {

    private val monday = 0b0000001
    private val weekdays = 0b0011111

    @Test
    fun `day bits map to monday-first indices`() {
        assertTrue(ScheduleEngine.daySelected(monday, 0))
        assertFalse(ScheduleEngine.daySelected(monday, 1))
        assertTrue(ScheduleEngine.daySelected(weekdays, 4)) // Friday
        assertFalse(ScheduleEngine.daySelected(weekdays, 5)) // Saturday
    }

    @Test
    fun `daytime schedule window is half-open`() {
        // Mon 09:00 - 17:00
        val start = 9 * 60
        val end = 17 * 60
        assertTrue(ScheduleEngine.isActive(true, false, start, end, 9 * 60))      // 09:00 start inclusive
        assertTrue(ScheduleEngine.isActive(true, false, start, end, 16 * 60 + 59)) // just before end
        assertFalse(ScheduleEngine.isActive(true, false, start, end, 17 * 60))    // end exclusive
        assertFalse(ScheduleEngine.isActive(true, false, start, end, 8 * 60 + 59))
    }

    @Test
    fun `schedule inactive on non-selected day`() {
        val start = 9 * 60
        val end = 17 * 60
        assertFalse(ScheduleEngine.isActive(false, false, start, end, 12 * 60))
    }

    @Test
    fun `overnight schedule is active before midnight on the selected day`() {
        // Mon 23:00 - 07:00
        val start = 23 * 60
        val end = 7 * 60
        assertTrue(ScheduleEngine.isActive(true, false, start, end, 23 * 60 + 30))
        assertFalse(ScheduleEngine.isActive(true, false, start, end, 12 * 60))
    }

    @Test
    fun `overnight schedule carries into the next unselected day`() {
        // Mon 23:00 - 07:00, observed on Tuesday 05:00
        val start = 23 * 60
        val end = 7 * 60
        assertTrue(ScheduleEngine.isActive(false, true, start, end, 5 * 60))
        // ...and ends at 07:00
        assertFalse(ScheduleEngine.isActive(false, true, start, end, 7 * 60))
    }

    @Test
    fun `disabled schedule logic is handled by caller via flag`() {
        // The engine is pure: enabled/disabled gating happens in EnforcementManager.
        // This test documents the boundary contract: anyActive picks first matching.
        val active = ScheduleEngine.isActive(true, false, 60, 120, 90)
        assertTrue(active)
    }
}

class UrlMatcherTest {

    @Test
    fun `normalizes full URLs to bare hosts`() {
        assertEquals("youtube.com", UrlMatcher.normalizeDomain("https://www.youtube.com/watch?v=abc"))
        assertEquals("reddit.com", UrlMatcher.normalizeDomain("reddit.com/r/android"))
        assertEquals("x.com", UrlMatcher.normalizeDomain("  HTTPS://X.COM/a"))
    }

    @Test
    fun `strips credentials ports and trailing dots`() {
        assertEquals("sub.example.com", UrlMatcher.normalizeDomain("https://user:pass@Sub.Example.com:8080/path"))
        assertEquals("example.com", UrlMatcher.normalizeDomain("www.example.com."))
    }

    @Test
    fun `rejects non-addresses`() {
        assertNull(UrlMatcher.normalizeDomain(""))
        assertNull(UrlMatcher.normalizeDomain("not a url"))
        assertNull(UrlMatcher.normalizeDomain("localhost"))
        assertNull(UrlMatcher.normalizeDomain("..."))
    }

    @Test
    fun `matches exact domain`() {
        val hit = UrlMatcher.matches("youtube.com", listOf("youtube.com"))
        assertEquals("youtube.com", hit)
    }

    @Test
    fun `matches subdomains but not lookalikes`() {
        assertEquals("youtube.com", UrlMatcher.matches("m.youtube.com", listOf("youtube.com")))
        assertEquals("youtube.com", UrlMatcher.matches("a.b.youtube.com", listOf("youtube.com")))
        assertNull(UrlMatcher.matches("notyoutube.com", listOf("youtube.com")))
        assertNull(UrlMatcher.matches("youtube.com.evil.net", listOf("youtube.com")))
    }

    @Test
    fun `blocklist entries may contain paths or schemes`() {
        val hit = UrlMatcher.matches("youtube.com", listOf("https://www.youtube.com/shorts"))
        assertEquals("youtube.com", hit)
    }

    @Test
    fun `looksLikeWebAddress gates candidate extraction`() {
        assertTrue(UrlMatcher.looksLikeWebAddress("https://example.com"))
        assertTrue(UrlMatcher.looksLikeWebAddress("example.com"))
        assertFalse(UrlMatcher.looksLikeWebAddress("just some text"))
        assertFalse(UrlMatcher.looksLikeWebAddress("42"))
    }
}

class TypedLimitEngineTest {

    @Test
    fun `daily decision blocks only at or over the limit`() {
        assertFalse(LimitEngine.dailyDecision(59_000, 60_000).blocked)
        assertTrue(LimitEngine.dailyDecision(60_000, 60_000).blocked)
        assertEquals(0, LimitEngine.dailyDecision(70_000, 60_000).remainingMs)
    }

    @Test
    fun `session grant expiry is strict`() {
        assertTrue(LimitEngine.sessionGrantActive(untilMs = 1_001, now = 1_000))
        assertFalse(LimitEngine.sessionGrantActive(untilMs = 1_000, now = 1_000))
        assertFalse(LimitEngine.sessionGrantActive(untilMs = 999, now = 1_000))
    }

    @Test
    fun `session choices parse to sorted valid minutes with fallback`() {
        assertEquals(listOf(15, 30, 60, 1440), LimitEngine.parseSessionChoices("30,15,60,1440"))
        assertEquals(listOf(5, 10), LimitEngine.parseSessionChoices("10, 5, 10"))
        // Garbage / out-of-range values fall back to the standard preset.
        assertEquals(listOf(15, 30, 60, 1440), LimitEngine.parseSessionChoices("0,9999,abc"))
        assertEquals(listOf(15, 30, 60, 1440), LimitEngine.parseSessionChoices(""))
    }

    @Test
    fun `a spent session cools down for exactly the configured window`() {
        val until = 10_000L
        val cooldown = 60_000L
        // Still inside the granted window: not cooling down.
        assertFalse(LimitEngine.sessionCoolingDown(until, cooldown, now = 9_999))
        // Window just ended: cooling down for the whole cooldown.
        assertTrue(LimitEngine.sessionCoolingDown(until, cooldown, now = 10_000))
        assertTrue(LimitEngine.sessionCoolingDown(until, cooldown, now = 69_999))
        // Cooldown over: a fresh session may start.
        assertFalse(LimitEngine.sessionCoolingDown(until, cooldown, now = 70_000))
        assertEquals(70_000L, LimitEngine.cooldownEnd(until, cooldown))
    }

    @Test
    fun `a session that never ran or has no cooldown never cools down`() {
        assertFalse(LimitEngine.sessionCoolingDown(grantUntil = 0L, cooldownMs = 60_000L, now = 5_000))
        assertFalse(LimitEngine.sessionCoolingDown(grantUntil = 1L, cooldownMs = 0L, now = 5_000))
        assertEquals(0L, LimitEngine.cooldownEnd(grantUntil = 0L, cooldownMs = 60_000L))
        assertEquals(0L, LimitEngine.cooldownEnd(grantUntil = 1L, cooldownMs = 0L))
    }

    @Test
    fun `session action grants again once the cooldown has elapsed`() {
        val until = 10_000L
        val cooldown = 60_000L
        assertEquals(LimitEngine.SessionAction.ACTIVE, LimitEngine.sessionAction(until, cooldown, now = 5_000))
        assertEquals(LimitEngine.SessionAction.COOLDOWN, LimitEngine.sessionAction(until, cooldown, now = 30_000))
        // The regression: after the cooldown the block must lift, not extend to midnight.
        assertEquals(LimitEngine.SessionAction.GRANT, LimitEngine.sessionAction(until, cooldown, now = 70_000))
    }

    @Test
    fun `session action with no cooldown holds the block and still opens a fresh day`() {
        // Never granted yet → grant the fixed session.
        assertEquals(LimitEngine.SessionAction.GRANT, LimitEngine.sessionAction(0L, 0L, now = 1_000))
        // Spent, no cooldown configured → hold the block (day-long fallback).
        assertEquals(LimitEngine.SessionAction.COOLDOWN, LimitEngine.sessionAction(10_000L, 0L, now = 5_000_000))
    }

    @Test
    fun `the no-cooldown fallback deadline is the next local midnight`() {
        val zone = ZoneId.of("UTC")
        val now = LocalDate.of(2026, 10, 18).atTime(22, 30)
            .atZone(zone).toInstant().toEpochMilli()
        val end = LimitEngine.dayEndMillis(now, zone)
        assertEquals(
            LocalDate.of(2026, 10, 19).atStartOfDay(zone).toInstant().toEpochMilli(),
            end
        )
        assertTrue(end > now)
    }
}

class ResetEngineTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    private fun at(date: String, hour: Int): Long =
        java.time.LocalDateTime.parse("${date}T${"%02d".format(hour)}:00:00").toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `usage day belongs to previous day before the reset hour`() {
        val reset = 3
        assertEquals("2026-10-15", ResetEngine.dayKey(at("2026-10-16", 2), reset, utc))
        assertEquals("2026-10-16", ResetEngine.dayKey(at("2026-10-16", 3), reset, utc))
        assertEquals("2026-10-16", ResetEngine.dayKey(at("2026-10-16", 23), reset, utc))
    }

    @Test
    fun `reset at midnight is plain calendar days`() {
        assertEquals("2026-10-16", ResetEngine.dayKey(at("2026-10-16", 0), 0, utc))
        assertEquals("2026-10-15", ResetEngine.dayKey(at("2026-10-15", 23), 0, utc))
    }

    @Test
    fun `day keys cover an inclusive range without gaps`() {
        val from = LocalDate.parse("2026-10-01")
        val to = LocalDate.parse("2026-10-04")
        assertEquals(
            listOf("2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04"),
            ResetEngine.dayKeys(from, to)
        )
        assertEquals(listOf("2026-10-01"), ResetEngine.dayKeys(from, from))
    }
}

class WeekEngineTest {

    private val wednesday = LocalDate.parse("2026-10-14") // 2026-10-14 is a Wednesday

    @Test
    fun `week start honors monday-first`() {
        assertEquals(LocalDate.parse("2026-10-12"), WeekEngine.weekStart(wednesday, mondayFirst = true))
        assertEquals(LocalDate.parse("2026-10-11"), WeekEngine.weekStart(wednesday, mondayFirst = false))
    }

    @Test
    fun `7-day preset starts on the configured week start and spans 7 days`() {
        val (from, to) = WeekEngine.presetRange(7, wednesday, mondayFirst = true)
        assertEquals(LocalDate.parse("2026-10-12"), from)
        assertEquals(LocalDate.parse("2026-10-18"), to)
        assertEquals(7, to.toEpochDay() - from.toEpochDay() + 1)
    }

    @Test
    fun `14 and 28 day presets are whole weeks`() {
        val (f14, t14) = WeekEngine.presetRange(14, wednesday, mondayFirst = true)
        assertEquals(14, t14.toEpochDay() - f14.toEpochDay() + 1)
        assertEquals(LocalDate.parse("2026-10-05"), f14)
        val (f28, t28) = WeekEngine.presetRange(28, wednesday, mondayFirst = false)
        assertEquals(28, t28.toEpochDay() - f28.toEpochDay() + 1)
        assertEquals(LocalDate.parse("2026-09-20"), f28)
        assertEquals(LocalDate.parse("2026-10-17"), t28)
    }

    @Test
    fun `today and yesterday presets`() {
        val (f1, t1) = WeekEngine.presetRange(1, wednesday, mondayFirst = true)
        assertEquals(wednesday, f1); assertEquals(wednesday, t1)
        val (f2, t2) = WeekEngine.presetRange(2, wednesday, mondayFirst = true)
        assertEquals(LocalDate.parse("2026-10-13"), f2); assertEquals(f2, t2)
    }

    @Test
    fun `day bits are monday-first`() {
        assertEquals(1, WeekEngine.dayBit(0))
        assertEquals(0x40, WeekEngine.dayBit(6))
        assertEquals(0x7F, WeekEngine.allDaysMask())
    }
}

class ChallengeEngineTest {

    @Test
    fun `quote matching is exact about case and punctuation`() {
        val quote = "Discipline is remembering what I want most."
        assertTrue(ChallengeEngine.matchesQuote(quote, quote))
        // A capital that isn't there, and one that is, are different answers.
        assertFalse(ChallengeEngine.matchesQuote("discipline is remembering what I want most.", quote))
        assertFalse(ChallengeEngine.matchesQuote("Discipline is remembering what I want most", quote))
        assertFalse(ChallengeEngine.matchesQuote("Discipline is remembering what I want most!", quote))
        // Only the whitespace around and between the words is forgiven.
        assertTrue(
            ChallengeEngine.matchesQuote("  Discipline   is remembering what I want most.  ", quote)
        )
        assertFalse(ChallengeEngine.matchesQuote("", quote))
    }

    @Test
    fun `normalize maps look-alikes onto keyboard characters`() {
        assertEquals("It's fine - really...", ChallengeEngine.normalize("It\u2019s fine \u2013 really\u2026"))
        assertEquals("a b", ChallengeEngine.normalize("a\u00A0b"))
        assertEquals("\"quoted\"", ChallengeEngine.normalize("\u201Cquoted\u201D"))
        assertEquals("As typed.", ChallengeEngine.normalize("As typed."))
    }

    @Test
    fun `every quote is typable and inside the length band`() {
        assertTrue(ChallengeEngine.quotes.size >= 60)
        ChallengeEngine.quotes.forEach { q ->
            assertTrue("length ${q.text.length}: ${q.text}", q.text.length in 70..100)
            assertTrue("non-ascii: ${q.text}", q.text.all { it.code in 0x20..0x7E })
            assertTrue("no credit: ${q.text}", q.source.isNotBlank())
            assertTrue(ChallengeEngine.matchesQuote(q.text, q.text))
        }
    }

    @Test
    fun `pick quote always returns a configured quote`() {
        val rng = Random(42)
        repeat(20) {
            assertTrue(ChallengeEngine.pickQuote(rng) in ChallengeEngine.quotes)
        }
    }

    @Test
    fun `math problems are solvable and strictly checked`() {
        val rng = Random(7)
        repeat(50) {
            val p = ChallengeEngine.randomMath(rng)
            val expected = when (p.op) {
                '+' -> p.a + p.b
                '-' -> p.a - p.b
                else -> p.a * p.b
            }
            assertEquals(expected, p.answer)
            assertTrue(ChallengeEngine.checkMath("${p.answer}", p))
            assertFalse(ChallengeEngine.checkMath("${p.answer + 1}", p))
            assertFalse(ChallengeEngine.checkMath("abc", p))
        }
    }

    @Test
    fun `subtraction never goes negative`() {
        val rng = Random(11)
        repeat(100) {
            val p = ChallengeEngine.randomMath(rng)
            if (p.op == '-') assertTrue(p.answer >= 0)
        }
    }
}

class KeywordMatcherTest {

    @Test
    fun `matches keyword anywhere case-insensitively`() {
        assertEquals("crypto", KeywordMatcher.match("https://site.com/news/Crypto-Pump", listOf("stocks", "CRYPTO")))
        assertNull(KeywordMatcher.match("https://site.com/news", listOf("stocks")))
    }

    @Test
    fun `blank keywords are ignored`() {
        assertNull(KeywordMatcher.match("anything", listOf("", "   ")))
        assertNull(KeywordMatcher.match("", listOf("x")))
    }
}

class ClassifierTest {

    @Test
    fun `known packages land in the right category`() {
        assertEquals("Social", CategoryClassifier.classify("com.instagram.android", "Instagram"))
        assertEquals("Social", CategoryClassifier.classify("com.zhiliaoapp.musically", "TikTok"))
        assertEquals("Video", CategoryClassifier.classify("com.google.android.youtube", "YouTube"))
        assertEquals("Messaging", CategoryClassifier.classify("com.whatsapp", "WhatsApp"))
        assertEquals("Games", CategoryClassifier.classify("com.mojang.minecraftpe", "Minecraft"))
        assertEquals("Shopping", CategoryClassifier.classify("com.amazon.mShop.android.shopping", "Amazon"))
        assertEquals("Productivity", CategoryClassifier.classify("com.notion.id", "Notion"))
    }

    @Test
    fun `label heuristics catch renamed packages`() {
        assertEquals("Social", CategoryClassifier.classify("com.example.weirdid", "My Social App"))
        assertEquals("News", CategoryClassifier.classify("com.example.reader", "Daily News Reader"))
        assertEquals("Video", CategoryClassifier.classify("com.example.player", "Video Player X"))
    }

    @Test
    fun `android system packages fall into tools`() {
        assertEquals("Tools", CategoryClassifier.classify("com.android.chrome", "Chrome"))
        assertEquals("Tools", CategoryClassifier.classify("com.miui.calculator", "Calculator"))
    }

    @Test
    fun `unknown apps land in not specified`() {
        assertEquals("Not Specified", CategoryClassifier.classify("com.acme.corp.thingamajig", "Thingamajig"))
    }

    @Test
    fun `result is always a default category`() {
        assertNotNull(CategoryClassifier.classify("x.y.z", "q"))
        assertTrue(CategoryClassifier.classify("x.y.z", "q") in CategoryClassifier.defaultOrder)
    }
}

class UnlockEngineTest {

    private val utc = ZoneId.of("UTC")
    private val now = 1_760_000_000_000L // fixed instant for deterministic day keys

    @Test
    fun `delay is clamped between zero and sixty`() {
        assertEquals(0, UnlockEngine.clampDelaySeconds(-5))
        assertEquals(60, UnlockEngine.clampDelaySeconds(500))
        assertEquals(15, UnlockEngine.clampDelaySeconds(15))
    }

    @Test
    fun `quota 0 means unlimited`() {
        assertTrue(
            UnlockEngine.canUnlock(now, usedDay = "whatever", usedCount = 999, quota = 0, resetHour = 0, zone = utc)
        )
    }

    @Test
    fun `quota blocks at the limit on the same day`() {
        val today = ResetEngine.dayKey(now, 0, utc)
        assertTrue(UnlockEngine.canUnlock(now, today, 2, 3, 0, utc))
        assertFalse(UnlockEngine.canUnlock(now, today, 3, 3, 0, utc))
    }

    @Test
    fun `a stale usage-day key resets the count`() {
        assertTrue(UnlockEngine.canUnlock(now, "2000-01-01", 50, 1, 0, utc))
    }

    @Test
    fun `recordUnlock increments within the day`() {
        val today = ResetEngine.dayKey(now, 0, utc)
        val (day, count) = UnlockEngine.recordUnlock(now, today, 2, 3, 0, utc)
        assertEquals(today, day)
        assertEquals(3, count)
    }

    @Test
    fun `recordUnlock rolls a stale counter onto today`() {
        val (day, count) = UnlockEngine.recordUnlock(now, "2000-01-01", 50, 3, 0, utc)
        assertEquals(ResetEngine.dayKey(now, 0, utc), day)
        assertEquals(1, count)
    }

    @Test
    fun `reset hour shifts the day boundary`() {
        val twoAm = java.time.LocalDateTime.of(2026, 10, 17, 2, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val tenAm = java.time.LocalDateTime.of(2026, 10, 17, 10, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        // With a 03:00 reset, 02:00 still belongs to the previous usage day.
        assertEquals("2026-10-16", ResetEngine.dayKey(twoAm, 3, utc))
        assertEquals("2026-10-17", ResetEngine.dayKey(tenAm, 3, utc))
    }
}

class FocusSessionEngineTest {

    private val utc = ZoneId.of("UTC")

    private fun at(h: Int, m: Int): Long =
        java.time.LocalDateTime.of(2026, 10, 17, h, m)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `accrued today is zero after a day rollover`() {
        val accrual = FocusSessionEngine.Accrual("2026-10-16", 45)
        val sameDay = java.time.LocalDateTime.of(2026, 10, 16, 23, 59)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val nextDay = java.time.LocalDateTime.of(2026, 10, 18, 10, 0)
            .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        assertEquals(0, FocusSessionEngine.accruedToday(accrual, at(12, 0), 0, utc))
        assertEquals(45, FocusSessionEngine.accruedToday(accrual, sameDay, 0, utc))
        assertEquals(0, FocusSessionEngine.accruedToday(accrual, nextDay, 0, utc))
    }

    @Test
    fun `finished session accrues whole minutes on its start day`() {
        val start = at(9, 0)
        val end = start + 25 * 60_000L + 30_000L
        val next = FocusSessionEngine.accrue(
            FocusSessionEngine.empty, FocusSessionEngine.Session(start, end), end, 0, utc
        )
        assertEquals("2026-10-17", next.dayKey)
        assertEquals(25, next.minutes)
    }

    @Test
    fun `early end accrues only time actually spent`() {
        val start = at(9, 0)
        val end = start + 30 * 60_000L
        val now = start + 12 * 60_000L
        val next = FocusSessionEngine.accrue(
            FocusSessionEngine.empty, FocusSessionEngine.Session(start, end), now, 0, utc
        )
        assertEquals(12, next.minutes)
    }

    @Test
    fun `same-day sessions add up`() {
        val start = at(9, 0)
        val first = FocusSessionEngine.accrue(
            FocusSessionEngine.empty, FocusSessionEngine.Session(start, start + 10 * 60_000L),
            start + 10 * 60_000L, 0, utc
        )
        val second = FocusSessionEngine.accrue(
            first, FocusSessionEngine.Session(start + 60 * 60_000L, start + 75 * 60_000L),
            start + 75 * 60_000L, 0, utc
        )
        assertEquals(25, second.minutes)
    }

    @Test
    fun `a session landing on a different day replaces the stale total`() {
        val start = at(9, 0)
        val stale = FocusSessionEngine.Accrual("2020-01-01", 999)
        val next = FocusSessionEngine.accrue(
            stale, FocusSessionEngine.Session(start, start + 5 * 60_000L),
            start + 5 * 60_000L, 0, utc
        )
        assertEquals("2026-10-17", next.dayKey)
        assertEquals(5, next.minutes)
    }

    @Test
    fun `zero length sessions change nothing`() {
        val start = at(9, 0)
        val next = FocusSessionEngine.accrue(
            FocusSessionEngine.empty, FocusSessionEngine.Session(start, start), start, 0, utc
        )
        assertEquals(FocusSessionEngine.empty, next)
    }
}
