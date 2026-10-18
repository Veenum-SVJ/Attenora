package dev.opensource.focusguard.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Theme modes for Settings → Appearance. */
object ThemeMode {
    const val SYSTEM = "system"
    const val DARK = "dark"
    const val LIGHT = "light"
}

/** Challenge modes for Lock Usage Limits. */
object ChallengeMode {
    const val NONE = "none"
    const val PIN = "pin"
    const val CHALLENGE = "challenge"
}

/** Per-app icon mask used across the usage lists. */
object AppIconShape {
    const val ROUNDED = "rounded"
    const val SQUIRCLE = "squircle"
    const val CIRCLE = "circle"
    const val SQUARE = "square"
}

/** Bottom-nav icon chip shape. */
object TabChipShape {
    const val ROUNDED = "rounded"
    const val CIRCLE = "circle"
    const val PILL = "pill"
}

/**
 * What the block prompt requires before granting more time
 * (Curbox-style unlock challenges).
 */
object UnlockChallenge {
    const val OFF = "off"      // grant freely — original behavior
    const val NONE = "none"    // stay blocked: never grant
    const val MATH = "math"    // solve arithmetic
    const val QUOTE = "quote"  // retype the shown sentence
    const val INTENT = "intent" // state why you are unlocking
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val BIOMETRIC = booleanPreferencesKey("biometric_enabled")
        val BLOCK_ALL_UNTIL = longPreferencesKey("block_all_until")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ONBOARDING_STEP = intPreferencesKey("onboarding_step")
        val REMINDER_MINUTE = intPreferencesKey("reminder_minute")
        val WEB_BLOCKING = booleanPreferencesKey("web_blocking_on")

        // v1.1
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val WEEK_MONDAY_FIRST = booleanPreferencesKey("week_monday_first")
        val RESET_HOUR = intPreferencesKey("reset_hour")
        val CHALLENGE_MODE = stringPreferencesKey("challenge_mode")
        val CHALLENGE_TYPE = stringPreferencesKey("challenge_type") // quote | math
        val CHALLENGE_TEXT = stringPreferencesKey("challenge_text")
        val IGNORE_WEBSITES = booleanPreferencesKey("ignore_websites")
        val COUNTDOWN_ON = booleanPreferencesKey("countdown_on")
        val LAUNCHER_ICON = intPreferencesKey("launcher_icon")
        val ICON_ACCENT = intPreferencesKey("icon_accent")
        val APP_ICON_SHAPE = stringPreferencesKey("app_icon_shape")
        val TAB_CHIP_SHAPE = stringPreferencesKey("tab_chip_shape")

        val ASSISTANT_ON = booleanPreferencesKey("assistant_on")
        val TOTAL10_ON = booleanPreferencesKey("total10_on")
        val FIVE_MIN_WARN = booleanPreferencesKey("five_min_warn")
        val BLOCK_HIT_ON = booleanPreferencesKey("block_hit_on")
        val SCHEDULE_ALERTS = booleanPreferencesKey("schedule_alerts")
        val WEEKLY_ON = booleanPreferencesKey("weekly_on")
        val WEEKLY_DAY = intPreferencesKey("weekly_day")   // Mon0
        val WEEKLY_MINUTE = intPreferencesKey("weekly_minute")
        val MORNING_ON = booleanPreferencesKey("morning_on")
        val MORNING_MINUTE = intPreferencesKey("morning_minute")
        val NIGHTOWL_ON = booleanPreferencesKey("nightowl_on")
        val NIGHTOWL_MINUTE = intPreferencesKey("nightowl_minute")
        val MUTE_BLOCKED = booleanPreferencesKey("mute_blocked")

        // Curbox-port: unlock extras, grayscale, focus sessions
        val UNLOCK_CHALLENGE = stringPreferencesKey("unlock_challenge") // off|none|math|quote|intent
        val UNLOCK_DELAY_SEC = intPreferencesKey("unlock_delay_sec")
        val UNLOCK_QUOTA = intPreferencesKey("unlock_quota")
        val UNLOCK_USED_DAY = stringPreferencesKey("unlock_used_day")
        val UNLOCK_USED_COUNT = intPreferencesKey("unlock_used_count")
        val INTENT_LAST = stringPreferencesKey("intent_last")
        val INTENT_AT = longPreferencesKey("intent_at")
        val GRAYSCALE_APPS = stringSetPreferencesKey("grayscale_apps")
        val GRAY_ACTIVE = booleanPreferencesKey("gray_active") // we applied the filter
        val FOCUS_START = longPreferencesKey("focus_start_ms")
        val FOCUS_END = longPreferencesKey("focus_end_ms")
        val FOCUS_DAY = stringPreferencesKey("focus_day")
        val FOCUS_MIN = intPreferencesKey("focus_min")
    }

    // ---- Existing (v1) ----
    /** null = no PIN set; "" is used as a "still loading" sentinel by callers. */
    val pinHash: Flow<String?> = context.dataStore.data.map { it[Keys.PIN_HASH] }
    val biometricEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.BIOMETRIC] ?: true }
    val blockAllUntil: Flow<Long> = context.dataStore.data.map { it[Keys.BLOCK_ALL_UNTIL] ?: 0L }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    /** Last onboarding step the user reached, so setup survives process death. */
    val onboardingStep: Flow<Int> = context.dataStore.data.map { it[Keys.ONBOARDING_STEP] ?: 0 }
    /** -1 = disabled; otherwise minutes after midnight. */
    val reminderMinute: Flow<Int> = context.dataStore.data.map { it[Keys.REMINDER_MINUTE] ?: -1 }
    val webBlockingOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEB_BLOCKING] ?: false }

    // ---- v1.1 ----
    val themeMode: Flow<String> = context.dataStore.data.map { it[Keys.THEME_MODE] ?: ThemeMode.SYSTEM }
    val weekMondayFirst: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEEK_MONDAY_FIRST] ?: true }
    /** Usage-day reset hour, 0..23 (0 = midnight). */
    val resetHour: Flow<Int> = context.dataStore.data.map { (it[Keys.RESET_HOUR] ?: 0).coerceIn(0, 23) }
    val challengeMode: Flow<String> = context.dataStore.data.map { it[Keys.CHALLENGE_MODE] ?: ChallengeMode.NONE }
    val challengeType: Flow<String> = context.dataStore.data.map { it[Keys.CHALLENGE_TYPE] ?: "quote" }
    val challengeText: Flow<String> = context.dataStore.data.map { it[Keys.CHALLENGE_TEXT] ?: "" }
    val ignoreWebsites: Flow<Boolean> = context.dataStore.data.map { it[Keys.IGNORE_WEBSITES] ?: false }
    val countdownOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.COUNTDOWN_ON] ?: true }
    val launcherIcon: Flow<Int> = context.dataStore.data.map { it[Keys.LAUNCHER_ICON] ?: 0 }
    val iconAccent: Flow<Int> = context.dataStore.data.map { it[Keys.ICON_ACCENT] ?: 0 }
    val appIconShape: Flow<String> = context.dataStore.data.map { it[Keys.APP_ICON_SHAPE] ?: AppIconShape.ROUNDED }
    val tabChipShape: Flow<String> = context.dataStore.data.map { it[Keys.TAB_CHIP_SHAPE] ?: TabChipShape.ROUNDED }

    val assistantOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.ASSISTANT_ON] ?: false }
    val total10On: Flow<Boolean> = context.dataStore.data.map { it[Keys.TOTAL10_ON] ?: false }
    val fiveMinWarn: Flow<Boolean> = context.dataStore.data.map { it[Keys.FIVE_MIN_WARN] ?: true }
    val blockHitOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.BLOCK_HIT_ON] ?: true }
    val scheduleAlerts: Flow<Boolean> = context.dataStore.data.map { it[Keys.SCHEDULE_ALERTS] ?: true }
    val weeklyOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.WEEKLY_ON] ?: true }
    val weeklyDay: Flow<Int> = context.dataStore.data.map { it[Keys.WEEKLY_DAY] ?: 0 }
    val weeklyMinute: Flow<Int> = context.dataStore.data.map { it[Keys.WEEKLY_MINUTE] ?: 20 * 60 }
    val morningOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.MORNING_ON] ?: false }
    val morningMinute: Flow<Int> = context.dataStore.data.map { it[Keys.MORNING_MINUTE] ?: 8 * 60 }
    val nightOwlOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.NIGHTOWL_ON] ?: false }
    val nightOwlMinute: Flow<Int> = context.dataStore.data.map { it[Keys.NIGHTOWL_MINUTE] ?: 22 * 60 }
    val muteBlocked: Flow<Boolean> = context.dataStore.data.map { it[Keys.MUTE_BLOCKED] ?: false }

    // ---- Curbox-port: unlock extras, grayscale, focus sessions ----
    val unlockChallenge: Flow<String> = context.dataStore.data.map { it[Keys.UNLOCK_CHALLENGE] ?: UnlockChallenge.OFF }
    /** Seconds the unlock buttons stay disabled after the prompt appears (0..60). */
    val unlockDelaySec: Flow<Int> = context.dataStore.data.map { (it[Keys.UNLOCK_DELAY_SEC] ?: 0).coerceIn(0, 60) }
    /** Unlocks allowed per usage day; 0 = unlimited. */
    val unlockQuota: Flow<Int> = context.dataStore.data.map { (it[Keys.UNLOCK_QUOTA] ?: 3).coerceIn(0, 20) }
    val unlockUsedDay: Flow<String> = context.dataStore.data.map { it[Keys.UNLOCK_USED_DAY] ?: "" }
    val unlockUsedCount: Flow<Int> = context.dataStore.data.map { it[Keys.UNLOCK_USED_COUNT] ?: 0 }
    val intentLast: Flow<String> = context.dataStore.data.map { it[Keys.INTENT_LAST] ?: "" }
    val intentAt: Flow<Long> = context.dataStore.data.map { it[Keys.INTENT_AT] ?: 0L }
    val grayscaleApps: Flow<Set<String>> = context.dataStore.data.map { it[Keys.GRAYSCALE_APPS] ?: emptySet() }
    /** True while FocusGuard itself has the grayscale filter applied. */
    val grayActive: Flow<Boolean> = context.dataStore.data.map { it[Keys.GRAY_ACTIVE] ?: false }
    val focusSessionStart: Flow<Long> = context.dataStore.data.map { it[Keys.FOCUS_START] ?: 0L }
    val focusSessionEnd: Flow<Long> = context.dataStore.data.map { it[Keys.FOCUS_END] ?: 0L }
    val focusDay: Flow<String> = context.dataStore.data.map { it[Keys.FOCUS_DAY] ?: "" }
    /** Minutes accrued into [focusDay]. */
    val focusMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.FOCUS_MIN] ?: 0 }

    suspend fun setPin(hash: String?, salt: String?) = context.dataStore.edit { p ->
        if (hash == null) {
            p.remove(Keys.PIN_HASH)
            p.remove(Keys.PIN_SALT)
        } else {
            p[Keys.PIN_HASH] = hash
            p[Keys.PIN_SALT] = salt ?: ""
        }
    }

    suspend fun getPinSalt(): String? = context.dataStore.data.first()[Keys.PIN_SALT]

    suspend fun setBiometric(on: Boolean) = context.dataStore.edit { it[Keys.BIOMETRIC] = on }
    suspend fun setBlockAllUntil(time: Long) = context.dataStore.edit { it[Keys.BLOCK_ALL_UNTIL] = time }
    suspend fun setOnboardingDone(on: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING_DONE] = on }
    suspend fun setOnboardingStep(step: Int) =
        context.dataStore.edit { it[Keys.ONBOARDING_STEP] = step.coerceAtLeast(0) }
    suspend fun setReminderMinute(minute: Int) = context.dataStore.edit { it[Keys.REMINDER_MINUTE] = minute }
    suspend fun setWebBlocking(on: Boolean) = context.dataStore.edit { it[Keys.WEB_BLOCKING] = on }

    suspend fun setThemeMode(mode: String) = context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    suspend fun setWeekMondayFirst(on: Boolean) = context.dataStore.edit { it[Keys.WEEK_MONDAY_FIRST] = on }
    suspend fun setResetHour(hour: Int) = context.dataStore.edit { it[Keys.RESET_HOUR] = hour.coerceIn(0, 23) }
    suspend fun setChallengeMode(mode: String) = context.dataStore.edit { it[Keys.CHALLENGE_MODE] = mode }
    suspend fun setChallengeType(type: String) = context.dataStore.edit { it[Keys.CHALLENGE_TYPE] = type }
    suspend fun setChallengeText(text: String) = context.dataStore.edit { it[Keys.CHALLENGE_TEXT] = text }
    suspend fun setIgnoreWebsites(on: Boolean) = context.dataStore.edit { it[Keys.IGNORE_WEBSITES] = on }
    suspend fun setCountdownOn(on: Boolean) = context.dataStore.edit { it[Keys.COUNTDOWN_ON] = on }
    suspend fun setLauncherIcon(index: Int) = context.dataStore.edit { it[Keys.LAUNCHER_ICON] = index.coerceIn(0, 5) }
    suspend fun setIconAccent(index: Int) = context.dataStore.edit { it[Keys.ICON_ACCENT] = index.coerceIn(0, 5) }
    suspend fun setAppIconShape(shape: String) = context.dataStore.edit { it[Keys.APP_ICON_SHAPE] = shape }
    suspend fun setTabChipShape(shape: String) = context.dataStore.edit { it[Keys.TAB_CHIP_SHAPE] = shape }

    suspend fun setAssistantOn(on: Boolean) = context.dataStore.edit { it[Keys.ASSISTANT_ON] = on }
    suspend fun setTotal10On(on: Boolean) = context.dataStore.edit { it[Keys.TOTAL10_ON] = on }
    suspend fun setFiveMinWarn(on: Boolean) = context.dataStore.edit { it[Keys.FIVE_MIN_WARN] = on }
    suspend fun setBlockHitOn(on: Boolean) = context.dataStore.edit { it[Keys.BLOCK_HIT_ON] = on }
    suspend fun setScheduleAlerts(on: Boolean) = context.dataStore.edit { it[Keys.SCHEDULE_ALERTS] = on }
    suspend fun setWeekly(on: Boolean, day: Int, minute: Int) = context.dataStore.edit {
        it[Keys.WEEKLY_ON] = on
        it[Keys.WEEKLY_DAY] = day.coerceIn(0, 6)
        it[Keys.WEEKLY_MINUTE] = minute.coerceIn(0, 1439)
    }
    suspend fun setMorning(on: Boolean, minute: Int) = context.dataStore.edit {
        it[Keys.MORNING_ON] = on
        it[Keys.MORNING_MINUTE] = minute.coerceIn(0, 1439)
    }
    suspend fun setNightOwl(on: Boolean, minute: Int) = context.dataStore.edit {
        it[Keys.NIGHTOWL_ON] = on
        it[Keys.NIGHTOWL_MINUTE] = minute.coerceIn(0, 1439)
    }
    suspend fun setMuteBlocked(on: Boolean) = context.dataStore.edit { it[Keys.MUTE_BLOCKED] = on }

    suspend fun setUnlockChallenge(type: String) = context.dataStore.edit { p ->
        val valid = setOf(
            UnlockChallenge.OFF, UnlockChallenge.NONE, UnlockChallenge.MATH,
            UnlockChallenge.QUOTE, UnlockChallenge.INTENT
        )
        p[Keys.UNLOCK_CHALLENGE] = if (type in valid) type else UnlockChallenge.OFF
    }
    suspend fun setUnlockDelay(seconds: Int) =
        context.dataStore.edit { it[Keys.UNLOCK_DELAY_SEC] = seconds.coerceIn(0, 60) }
    suspend fun setUnlockQuota(quota: Int) =
        context.dataStore.edit { it[Keys.UNLOCK_QUOTA] = quota.coerceIn(0, 20) }
    /** Persist one consumed unlock (day-key + running count for that day). */
    suspend fun recordUnlock(day: String, count: Int) = context.dataStore.edit {
        it[Keys.UNLOCK_USED_DAY] = day
        it[Keys.UNLOCK_USED_COUNT] = count
    }
    suspend fun setIntent(text: String, at: Long) = context.dataStore.edit {
        it[Keys.INTENT_LAST] = text
        it[Keys.INTENT_AT] = at
    }
    suspend fun setGrayscaleApp(pkg: String, on: Boolean) = context.dataStore.edit { p ->
        val apps = p[Keys.GRAYSCALE_APPS] ?: emptySet()
        p[Keys.GRAYSCALE_APPS] = if (on) apps + pkg else apps - pkg
    }
    suspend fun setGrayActive(on: Boolean) = context.dataStore.edit { it[Keys.GRAY_ACTIVE] = on }
    suspend fun startFocusSession(startMs: Long, endMs: Long) = context.dataStore.edit {
        it[Keys.FOCUS_START] = startMs
        it[Keys.FOCUS_END] = endMs
    }
    suspend fun clearFocusSession() = context.dataStore.edit {
        it.remove(Keys.FOCUS_START)
        it.remove(Keys.FOCUS_END)
    }
    suspend fun setFocusAccrual(day: String, minutes: Int) = context.dataStore.edit {
        it[Keys.FOCUS_DAY] = day
        it[Keys.FOCUS_MIN] = minutes.coerceAtLeast(0)
    }

    /** Erase every preference (used by "wipe all data"). */
    suspend fun wipe() = context.dataStore.edit { it.clear() }
}
