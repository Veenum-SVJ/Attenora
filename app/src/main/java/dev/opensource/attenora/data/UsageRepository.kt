package dev.opensource.attenora.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dev.opensource.attenora.domain.ResetEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Turns Android's UsageStats event stream into small daily rollup rows.
 *
 * Design notes (battery):
 *  - We never poll. Reconcile runs only when the app is opened, when the
 *    dashboard refreshes, or at most every 30s while app-switching triggers
 *    a limit decision.
 *  - Each reconcile reads only the event delta since a persisted checkpoint,
 *    then advances the checkpoint. No full-history scans, ever.
 *  - A daily reset is computed lazily from the date on the rollup rows —
 *    there is no midnight alarm.
 */
class UsageRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Usage-day reset hour (0..23); supplied from settings, default midnight. */
    private val resetHourProvider: () -> Int = { 0 }
) {

    companion object {
        private const val KEY_CURSOR = "usage_cursor"
        private const val MAX_INTERVAL_MS = 30L * 60 * 1000 // ignore screen-off gaps
        private const val MIN_INTERVAL_MS = 500
        private const val FIRST_RUN_LOOKBACK_MS = 24L * 60 * 60 * 1000
        private const val RETENTION_DAYS = 90L

        /** element_usage key under which website visits are stored (per domain). */
        const val WEB_ELEMENT = "__site__"
    }

    private val mutex = Mutex()

    @Volatile
    private var currentPkg: String? = null

    @Volatile
    private var currentSinceMs: Long = 0L

    fun currentForeground(): String? = currentPkg

    /**
     * Called from the accessibility monitor on every foreground transition.
     * A null pkg means "nothing is on screen" (screen off): the live segment
     * stops there, so time with the display dark never accrues as usage.
     */
    fun noteForeground(pkg: String?, now: Long = clock()) {
        if (pkg == currentPkg) return
        currentPkg = pkg
        currentSinceMs = now
    }

    /** Live in-progress session time for [pkg] on top of the persisted rollup. */
    fun liveMs(pkg: String, now: Long = clock()): Long =
        if (currentPkg == pkg) (now - currentSinceMs).coerceAtLeast(0L) else 0L

    suspend fun usedMsToday(pkg: String, now: Long = clock()): Long {
        val day = ResetEngine.dayKey(now, resetHourProvider())
        val stored = db.dailyUsageDao().usedMs(day, pkg) ?: 0L
        return stored + liveMs(pkg, now)
    }

    /** Usage-day key for "now" honoring the configured reset hour. */
    fun todayKey(now: Long = clock()): String = ResetEngine.dayKey(now, resetHourProvider())

    /**
     * Website visits are attributed in element_usage under this element key so
     * they never double-count inside app totals (rows keyed by domain).
     */
    suspend fun websiteMsToday(domain: String, now: Long = clock()): Long {
        val day = ResetEngine.dayKey(now, resetHourProvider())
        return db.elementUsageDao().sum(domain, WEB_ELEMENT, day, day) ?: 0L
    }

    /** Credits attributed website foreground time (called by the accessibility service). */
    suspend fun creditWebsite(domain: String, ms: Long, now: Long = clock()) {
        if (domain.isBlank() || ms <= 0L) return
        val day = ResetEngine.dayKey(now, resetHourProvider())
        db.elementUsageDao().add(day, domain, WEB_ELEMENT, ms)
    }

    fun todayFlow(): Flow<List<DailyUsage>> =
        db.dailyUsageDao().forDayFlow(todayKey())

    fun last7DaysFlow(): Flow<List<DailyUsage>> {
        val end = LocalDate.now()
        return db.dailyUsageDao().rangeFlow(end.minusDays(6).toString(), end.toString())
    }

    suspend fun exportableRows(): List<DailyUsage> {
        val end = LocalDate.now()
        return db.dailyUsageDao().range(end.minusDays(RETENTION_DAYS).toString(), end.toString())
    }

    /**
     * Consumes new UsageStats events since the checkpoint into daily rollups.
     * Dispatched off the main thread: queryEvents() is a Binder IPC that can
     * return thousands of events, and scanning it on the main thread makes the
     * dashboard feel laggy on open.
     */
    suspend fun reconcileNow() = withContext(Dispatchers.IO) {
        mutex.withLock {
        val now = clock()
        val rawCursor = db.metaDao().get(KEY_CURSOR)?.toLongOrNull() ?: (now - FIRST_RUN_LOOKBACK_MS)
        val start = rawCursor.coerceIn(0L, now)
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return@withLock
        val events = usm.queryEvents(start, now)
        val event = UsageEvents.Event()
        val zone = ZoneId.systemDefault()
        val resetHour = resetHourProvider()

        // "pkg|day" -> [durationMs, launches]
        val totals = HashMap<String, LongArray>(64)
        // "pkg|day|hour" -> durationMs (hourly chart)
        val hourly = HashMap<String, Long>(128)
        fun bucket(pkg: String, stamp: Long): LongArray {
            val day = ResetEngine.dayKey(stamp, resetHour, zone)
            return totals.getOrPut("$pkg|$day") { LongArray(2) }
        }
        fun creditHourly(pkg: String, stamp: Long, ms: Long) {
            if (ms <= 0L) return
            val zdt = Instant.ofEpochMilli(stamp).atZone(zone)
            val day = ResetEngine.dayKey(stamp, resetHour, zone)
            val key = "$pkg|$day|${zdt.hour}"
            hourly.merge(key, ms, Long::plus)
        }

        var openPkg: String? = null
        var openAt = start

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val stamp = event.timeStamp
            val pkg = event.packageName?.toString() ?: continue
            if (pkg == context.packageName) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val prev = openPkg
                    if (prev != null) {
                        val dur = (stamp - openAt).coerceIn(0L, MAX_INTERVAL_MS)
                        if (dur >= MIN_INTERVAL_MS) {
                            bucket(prev, openAt)[0] += dur
                            creditHourly(prev, openAt, dur)
                        }
                    }
                    openPkg = pkg
                    openAt = stamp
                    bucket(pkg, stamp)[1] += 1
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (pkg == openPkg) {
                        val dur = (stamp - openAt).coerceIn(0L, MAX_INTERVAL_MS)
                        if (dur >= MIN_INTERVAL_MS) {
                            bucket(pkg, openAt)[0] += dur
                            creditHourly(pkg, openAt, dur)
                        }
                        openPkg = null
                    }
                }
            }
        }

        for ((key, arr) in totals) {
            val sep = key.lastIndexOf('|')
            val pkg = key.substring(0, sep)
            val day = key.substring(sep + 1)
            db.dailyUsageDao().add(day, pkg, arr[0], arr[1].toInt())
        }
        for ((key, ms) in hourly) {
            val p1 = key.indexOf('|')
            val p2 = key.indexOf('|', p1 + 1)
            val pkg = key.substring(0, p1)
            val day = key.substring(p1 + 1, p2)
            val hour = key.substring(p2 + 1).toIntOrNull() ?: continue
            db.hourlyUsageDao().add(day, pkg, hour, ms)
        }

        // Advance only past what was actually accounted for. Leaving a
        // session open means its RESUME is still the anchor for the PAUSE
        // that has not arrived yet; moving the cursor to `now` would strand
        // it and the whole session would never be counted (a one-minute
        // daily limit could then be reset just by reopening the app).
        val nextCursor = if (openPkg != null) openAt else now
        db.metaDao().put(MetaEntry(KEY_CURSOR, nextCursor.toString()))
        db.dailyUsageDao().pruneOlderThan(LocalDate.now().minusDays(RETENTION_DAYS).toString())
        db.hourlyUsageDao().pruneOlderThan(LocalDate.now().minusDays(RETENTION_DAYS).toString())
        db.reelCountDao().pruneOlderThan(LocalDate.now().minusDays(RETENTION_DAYS).toString())
        }
    }
}
