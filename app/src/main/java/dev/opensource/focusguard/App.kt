package dev.opensource.focusguard

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.opensource.focusguard.data.AppDatabase
import dev.opensource.focusguard.data.BackupRepository
import dev.opensource.focusguard.data.Category
import dev.opensource.focusguard.data.SettingsRepository
import dev.opensource.focusguard.data.UsageRepository
import dev.opensource.focusguard.domain.CategoryClassifier
import dev.opensource.focusguard.enforcement.Alarms
import dev.opensource.focusguard.enforcement.EnforcementManager
import dev.opensource.focusguard.enforcement.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FocusApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/**
 * Manual dependency container. Deliberately tiny: this app has no cloud, no
 * analytics, no background service of its own — everything lives on-device.
 */
class AppContainer(val app: Context) {

    val db: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "focusguard.db")
        .fallbackToDestructiveMigration()
        .build()

    val settings = SettingsRepository(app)

    val usage = UsageRepository(app, db, resetHourProvider = { resetHourCache })

    @Volatile
    var resetHourCache: Int = 0
        private set

    val backup = BackupRepository(db)

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val enforcement = EnforcementManager(app, settings, db, usage, scope)

    /** True while the lightweight foreground-monitor accessibility service is connected. */
    @Volatile
    var monitorConnected = false

    /** True while the (optional, heavier) website-blocking accessibility service is connected. */
    @Volatile
    var webMonitorConnected = false

    /** True while the element-block/countdown accessibility service is connected. */
    @Volatile
    var elementConnected = false

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

    init {
        Notifier.ensureChannels(app)

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
            // Weekly summary + morning routine + night owl (all inexact).
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
            settings.morningMinute.distinctUntilChanged().collect {
                Alarms.rearmMorning(app, settings.morningOn.first(), it)
            }
        }
        scope.launch {
            settings.nightOwlMinute.distinctUntilChanged().collect {
                Alarms.rearmNightOwl(app, settings.nightOwlOn.first(), it)
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
    }

    private suspend fun seedCategories() {
        if (db.categoryDao().all().isNotEmpty()) return
        val palette = listOf(
            0xFFC3A9F7L, 0xFF7FD1B9L, 0xFFF2A65AL, 0xFF6FB3F2L,
            0xFFF27E89L, 0xFFE8D05FL, 0xFF9B8AFBL, 0xFF8A93A6L, 0xFF6E7684L
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
