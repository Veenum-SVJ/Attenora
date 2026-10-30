package dev.opensource.attenora

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.opensource.attenora.data.AppDatabase
import dev.opensource.attenora.data.BackupRepository
import dev.opensource.attenora.data.Category
import dev.opensource.attenora.data.HabitReminders
import dev.opensource.attenora.data.HabitRepository
import dev.opensource.attenora.data.SettingsRepository
import dev.opensource.attenora.data.UsageRepository
import dev.opensource.attenora.domain.CategoryClassifier
import dev.opensource.attenora.enforcement.Alarms
import dev.opensource.attenora.enforcement.EnforcementManager
import dev.opensource.attenora.enforcement.FocusSession
import dev.opensource.attenora.enforcement.HabitStatusSync
import dev.opensource.attenora.enforcement.MonitorWatchdog
import dev.opensource.attenora.enforcement.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * v2 → v3: variable-session limits gained a per-limit cooldown. Adding the
 * column in place keeps the user's usage history instead of wiping the DB.
 */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE usage_limits ADD COLUMN cooldownMs INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v3 → v4: the reel counter gained per-app short-video counts. A new table
 * rather than a column, so the existing usage history is untouched.
 */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `reel_counts` (" +
                "`day` TEXT NOT NULL, `packageName` TEXT NOT NULL, " +
                "`count` INTEGER NOT NULL, PRIMARY KEY(`day`, `packageName`))"
        )
    }
}

/**
 * v4 → v5: session grants gained a pause marker, so a session counts only
 * while its app is on screen. One column with a default keeps every existing
 * grant (and all usage history) exactly as it was.
 */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE session_grants ADD COLUMN pausedAtMs INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v5 → v6: the habits tab. Four new tables, no changes to anything the
 * tracker already stores — usage history, limits and schedules are untouched.
 */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `habits` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`reason` TEXT NOT NULL, `mode` TEXT NOT NULL, `daysMask` INTEGER NOT NULL, " +
                "`createdAtMs` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `habit_entries` (" +
                "`habitId` INTEGER NOT NULL, `day` TEXT NOT NULL, `count` INTEGER NOT NULL, " +
                "PRIMARY KEY(`habitId`, `day`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `habit_steps` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `habitId` INTEGER NOT NULL, " +
                "`label` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_habit_steps_habitId` ON `habit_steps` (`habitId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `habit_step_checks` (" +
                "`stepId` INTEGER NOT NULL, `day` TEXT NOT NULL, " +
                "PRIMARY KEY(`stepId`, `day`))"
        )
    }
}

/**
 * v6 → v7: a per-habit reminder time. One added column, defaulted to -1 so
 * every existing habit keeps following the daily default exactly as before.
 */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `habits` ADD COLUMN `reminderMinute` INTEGER NOT NULL DEFAULT -1")
    }
}

class AttenoraApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val t0 = android.os.SystemClock.elapsedRealtime()
        container = AppContainer(this)
        android.util.Log.d("FGSTART", "app container init: ${android.os.SystemClock.elapsedRealtime() - t0}ms")

        // The App monitor can be "on" and dead at the same time - see
        // MonitorWatchdog. Every process start is a chance to notice and heal,
        // and arming the heartbeat here also restores it after a force-stop.
        MonitorWatchdog.markProcessStart()
        MonitorWatchdog.schedule(this)
        container.scope.launch {
            // The accessibility manager may be binding a healthy service right
            // now; checking before it settles would repair something that is
            // not broken.
            delay(MonitorWatchdog.settleRemainingMs())
            MonitorWatchdog.check(this@AttenoraApplication, "app")
        }

        // The ongoing habit notification follows the database, so a tick made
        // in the app is reflected in the shade without any timer.
        HabitStatusSync.start(this, container)
    }
}

/**
 * Manual dependency container. Deliberately tiny: this app has no cloud, no
 * analytics, no background service of its own — everything lives on-device.
 */
class AppContainer(val app: Context) {

    private val containerInitT0 = android.os.SystemClock.elapsedRealtime()

    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "attenora.db")
        .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
        .fallbackToDestructiveMigration()
        .build()

    val settings = SettingsRepository(app)

    val usage = UsageRepository(app, db, resetHourProvider = { resetHourCache })

    @Volatile
    var resetHourCache: Int = 0
        private set

    val backup = BackupRepository(db)

    /** Habits tab: the only user-typed data in the app. */
    val habits = HabitRepository(db, todayKey = { usage.todayKey() })

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val enforcement = EnforcementManager(app, settings, db, usage, scope)

    /** True while the single Attenora accessibility service is connected. */
    @Volatile
    var a11yConnected = false

    /** True while the notification listener for "Block Notifications" is connected. */
    @Volatile
    var muteListenerConnected = false

    val blocklist: StateFlow<Set<String>> =
        db.blockedSiteDao().observeAll()
            .map { list -> list.map { it.domain }.toSet() }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val webBlockingActive: StateFlow<Boolean> =
        settings.webBlockingOn.stateIn(scope, SharingStarted.Eagerly, false)

    val categories: StateFlow<List<Category>> =
        db.categoryDao().observeAll()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val hiddenKeys: StateFlow<Set<String>> =
        db.hiddenItemDao().observeAll()
            .map { list -> list.map { "${it.kind}:${it.id}" }.toSet() }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Apps that open in grayscale (Curbox-style monochrome filter). */
    val grayscaleApps: StateFlow<Set<String>> =
        settings.grayscaleApps.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** True while Attenora itself has the grayscale filter applied. */
    val grayActive: StateFlow<Boolean> =
        settings.grayActive.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Reel counter switch. Cached as a StateFlow because the accessibility
     * service reads it on every scroll event; a DataStore read per swipe would
     * be a disk hit on the main thread's critical path.
     */
    val reelCounterOn: StateFlow<Boolean> =
        settings.reelCounterOn.stateIn(scope, SharingStarted.Eagerly, true)

    val reelOverlayOpacity: StateFlow<Float> =
        settings.reelOverlayOpacity.stateIn(
            scope, SharingStarted.Eagerly, 1f
        )

    /**
     * Warm cache of the onboarding flag. `collectAsState()` on a StateFlow uses
     * its current value as the initial, so a returning user's first frame
     * already shows the real screen instead of flashing the onboarding flow
     * for one frame and then recomposing the whole app tree.
     */
    val onboardingDone: StateFlow<Boolean> =
        settings.onboardingDone.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        // Notification channels are binder IPC to system_server; they are not
        // needed until the first notification is posted (and every `post`
        // re-creates them anyway), so this stays off the startup critical path.
        scope.launch { Notifier.ensureChannels(app) }

        // Keep alarms in sync with schedule edits; Flow collection also
        // re-arms everything when the app process starts.
        scope.launch {
            db.focusScheduleDao().observeAll()
                .distinctUntilChanged()
                .collect { Alarms.rearmAll(app, it) }
        }
        scope.launch {
            settings.reminderMinute
                .distinctUntilChanged()
                .collect { Alarms.setReminderAlarm(app, it) }
        }
        scope.launch {
            // Arm the next nudge whenever the default time or the habits
            // themselves change — a flow collection runs on every process
            // start, so a force-stop (or MIUI clearing the alarm queue) is
            // repaired the next time the app runs. Room re-emits this only on
            // add/edit/delete, so ticking a habit never touches the alarm path.
            combine(
                settings.habitReminderMinute,
                db.habitDao().observeAll()
            ) { default, habits -> HabitReminders.slots(habits, default) }
                .distinctUntilChanged()
                .collect { slots -> Alarms.armNextHabitReminder(app, slots) }
        }
        scope.launch {
            // The midnight re-label follows both of its inputs: the toggle and
            // the hour the usage day rolls over.
            combine(settings.habitNotifyOn, settings.resetHour) { on, hour -> on to hour }
                .distinctUntilChanged()
                .collect { (on, hour) -> Alarms.setHabitRefreshAlarm(app, on, hour) }
        }
        scope.launch {
            // Weekly summary and reminders (all inexact).
            settings.weeklyOn.distinctUntilChanged().collect {
                Alarms.rearmWeekly(
                    app,
                    settings.weeklyOn.first(),
                    settings.weeklyDay.first(),
                    settings.weeklyMinute.first()
                )
            }
        }
        scope.launch {
            settings.weeklyDay.distinctUntilChanged().collect {
                Alarms.rearmWeekly(
                    app,
                    settings.weeklyOn.first(),
                    settings.weeklyDay.first(),
                    settings.weeklyMinute.first()
                )
            }
        }
        scope.launch {
            // Cache the reset hour for the usage repository, then keep it fresh.
            settings.resetHour.distinctUntilChanged().collect { resetHourCache = it }
        }
        scope.launch {
            // Seed the built-in categories exactly once (offline classifier).
            seedCategories()
        }
        scope.launch {
            // Fold any focus session that ended while the process was dead.
            runCatching { FocusSession.finalize(this@AppContainer) }
        }
        android.util.Log.d("FGSTART", "container init done: ${android.os.SystemClock.elapsedRealtime() - containerInitT0}ms")
    }

    private suspend fun seedCategories() {
        if (db.categoryDao().all().isNotEmpty()) return
        val palette = listOf(
            0xFF5FA7A1L, 0xFF7FB3AEL, 0xFF4E9C7BL, 0xFF6FA8C4L,
            0xFF8E9BA8L, 0xFFC9A66BL, 0xFFB07C88L, 0xFF9AA58EL, 0xFF6E7684L
        )
        CategoryClassifier.defaultOrder.forEachIndexed { index, name ->
            db.categoryDao().upsert(
                Category(
                    name = name,
                    color = palette.getOrElse(index) { 0xFF8A93A6L },
                    sortOrder = index,
                    isBuiltin = true
                )
            )
        }
    }
}
