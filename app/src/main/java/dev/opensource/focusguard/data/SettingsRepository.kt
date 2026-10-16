package dev.opensource.focusguard.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val BIOMETRIC = booleanPreferencesKey("biometric_enabled")
        val BLOCK_ALL_UNTIL = longPreferencesKey("block_all_until")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
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
    }

    // ---- Existing (v1) ----
    /** null = no PIN set; "" is used as a "still loading" sentinel by callers. */
    val pinHash: Flow<String?> = context.dataStore.data.map { it[Keys.PIN_HASH] }
    val biometricEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.BIOMETRIC] ?: true }
    val blockAllUntil: Flow<Long> = context.dataStore.data.map { it[Keys.BLOCK_ALL_UNTIL] ?: 0L }
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
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

    /** Erase every preference (used by "wipe all data"). */
    suspend fun wipe() = context.dataStore.edit { it.clear() }
}
