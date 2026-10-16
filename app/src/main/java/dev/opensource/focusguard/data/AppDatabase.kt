package dev.opensource.focusguard.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One row per app per usage day ("yyyy-MM-dd", shifted by the reset hour) + package. */
@Entity(tableName = "daily_usage", primaryKeys = ["day", "packageName"])
data class DailyUsage(
    val day: String,
    val packageName: String,
    val usedMs: Long,
    val launches: Int = 0
)

/** One row per app per usage day per clock hour — powers the hourly chart. */
@Entity(tableName = "hourly_usage", primaryKeys = ["day", "packageName", "hour"])
data class HourlyUsage(
    val day: String,
    val packageName: String,
    /** 0..23 — the actual clock hour (labels are stable regardless of reset). */
    val hour: Int,
    val ms: Long
)

/** Type constants for [UsageLimit.type]. */
object LimitType {
    const val DAILY = "DAILY"
    const val SESSION = "SESSION"
    const val SCHEDULE = "SCHEDULE"
    const val PERMANENT = "PERMANENT"
}

/** Target constants for [UsageLimit.targetKind]. */
object TargetKind {
    const val APP = "APP"
    const val WEBSITE = "WEBSITE"
    const val CATEGORY = "CATEGORY"
    /** Focus/sleep style schedule that blocks everything except the allowlist. */
    const val ALL = "ALL"
}

/**
 * The single typed limit table: daily usage limits, variable session limits,
 * scheduled blocks and permanent blocks, targeting apps, websites, categories
 * or (schedules only) everything.
 */
@Entity(tableName = "usage_limits")
data class UsageLimit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val targetKind: String,
    /** package / domain-or-keyword / category row id (stringified) / "" for ALL. */
    val targetId: String,
    /** DAILY only. */
    val dailyLimitMs: Long = 0L,
    /** SESSION only: comma-separated minutes, e.g. "15,30,60,1440". */
    val sessionChoices: String = "",
    /** Bit 0 = Monday … bit 6 = Sunday; 127 = every day. */
    val daysMask: Int = 0x7F,
    /** SCHEDULE only: minutes after midnight; end < start means overnight. */
    val startMinute: Int = 0,
    val endMinute: Int = 1440,
    /** Optional user label ("No social after 9"). */
    val name: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0
)

/** Focus/sleep schedule that blocks everything except the allowlist (v1 feature). */
@Entity(tableName = "focus_schedules")
data class FocusSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Minutes after midnight, 0..1439. */
    val startMinute: Int,
    /** Exclusive end; may be smaller than start for overnight schedules. */
    val endMinute: Int,
    /** Bit 0 = Monday … bit 6 = Sunday. */
    val daysMask: Int,
    /** True: block everything except the allowlist; false: only apps with limits. */
    val blockAll: Boolean,
    val enabled: Boolean = true
)

@Entity(tableName = "blocked_sites")
data class BlockedSite(
    @PrimaryKey val domain: String,
    val addedAt: Long
)

@Entity(tableName = "block_keywords")
data class BlockKeyword(
    @PrimaryKey val keyword: String,
    val addedAt: Long
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB color for chips/donuts, e.g. 0xFFC3A9F7. */
    val color: Long,
    val sortOrder: Int = 0,
    /** Built-in defaults cannot be deleted, only renamed/recycled. */
    val isBuiltin: Boolean = false
)

/** key format: "app:<pkg>" or "web:<domain>". */
@Entity(tableName = "category_members", primaryKeys = ["key"])
data class CategoryMember(
    val key: String,
    val categoryId: Long
)

object HiddenKind {
    const val APP = "app"
    const val WEBSITE = "website"
}

/** Excluded from totals and Usage Stats (Hidden Apps screen). */
@Entity(tableName = "hidden_items", primaryKeys = ["kind", "id"])
data class HiddenItem(
    val kind: String,
    val id: String,
    val addedAt: Long
)

/** In-app element blocking toggle: pkg + element key (e.g. "shorts"). */
@Entity(tableName = "element_rules", primaryKeys = ["packageName", "element"])
data class ElementRule(
    val packageName: String,
    val element: String,
    val enabled: Boolean
)

/** Time attributed to an element while a detector saw it, per usage day. */
@Entity(tableName = "element_usage", primaryKeys = ["day", "packageName", "element"])
data class ElementUsage(
    val day: String,
    val packageName: String,
    val element: String,
    val ms: Long
)

/** Variable-session grant: target blocked-vs-free window. */
@Entity(tableName = "session_grants", primaryKeys = ["targetId"])
data class SessionGrant(
    val targetId: String,
    val untilMs: Long
)

/** Troubleshooting log of block hits. */
@Entity(tableName = "block_events")
data class BlockEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val target: String,
    val reason: String,
    val at: Long
)

/** Tiny key/value store; used for the UsageStats reconcile checkpoint. */
@Entity(tableName = "meta")
data class MetaEntry(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface DailyUsageDao {
    @Query("SELECT * FROM daily_usage WHERE day = :day")
    suspend fun forDay(day: String): List<DailyUsage>

    @Query("SELECT * FROM daily_usage WHERE day = :day")
    fun forDayFlow(day: String): Flow<List<DailyUsage>>

    @Query("SELECT * FROM daily_usage WHERE day BETWEEN :from AND :to")
    fun rangeFlow(from: String, to: String): Flow<List<DailyUsage>>

    @Query("SELECT * FROM daily_usage WHERE day BETWEEN :from AND :to")
    suspend fun range(from: String, to: String): List<DailyUsage>

    @Query("SELECT * FROM daily_usage ORDER BY day DESC")
    fun allFlow(): Flow<List<DailyUsage>>

    @Query("SELECT usedMs FROM daily_usage WHERE day = :day AND packageName = :pkg")
    suspend fun usedMs(day: String, pkg: String): Long?

    @Query(
        """
        INSERT INTO daily_usage(day, packageName, usedMs, launches)
        VALUES(:day, :pkg, :ms, :launch)
        ON CONFLICT(day, packageName) DO UPDATE SET
            usedMs = usedMs + :ms,
            launches = launches + :launch
        """
    )
    suspend fun add(day: String, pkg: String, ms: Long, launch: Int)

    @Query("DELETE FROM daily_usage WHERE day < :cutoffDay")
    suspend fun pruneOlderThan(cutoffDay: String)

    @Query("DELETE FROM daily_usage")
    suspend fun deleteAll()
}

@Dao
interface HourlyUsageDao {
    @Query(
        """
        INSERT INTO hourly_usage(day, packageName, hour, ms)
        VALUES(:day, :pkg, :hour, :ms)
        ON CONFLICT(day, packageName, hour) DO UPDATE SET ms = ms + :ms
        """
    )
    suspend fun add(day: String, pkg: String, hour: Int, ms: Long)

    @Query("SELECT * FROM hourly_usage WHERE day = :day")
    fun forDayFlow(day: String): Flow<List<HourlyUsage>>

    @Query("SELECT * FROM hourly_usage WHERE day BETWEEN :from AND :to")
    suspend fun range(from: String, to: String): List<HourlyUsage>

    @Query("SELECT hour, SUM(ms) AS ms FROM hourly_usage WHERE day = :day GROUP BY hour")
    suspend fun dayTotalsByHour(day: String): List<HourTotals>

    @Query("DELETE FROM hourly_usage WHERE day < :cutoffDay")
    suspend fun pruneOlderThan(cutoffDay: String)

    @Query("DELETE FROM hourly_usage")
    suspend fun deleteAll()
}

data class HourTotals(val hour: Int, val ms: Long)

@Dao
interface UsageLimitDao {
    @Query("SELECT * FROM usage_limits ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<UsageLimit>>

    @Query("SELECT * FROM usage_limits")
    suspend fun all(): List<UsageLimit>

    @Upsert
    suspend fun upsert(limit: UsageLimit): Long

    @Query("UPDATE usage_limits SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM usage_limits WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM usage_limits")
    suspend fun deleteAll()
}

@Dao
interface FocusScheduleDao {
    @Query("SELECT * FROM focus_schedules ORDER BY startMinute")
    fun observeAll(): Flow<List<FocusSchedule>>

    @Query("SELECT * FROM focus_schedules")
    suspend fun all(): List<FocusSchedule>

    @Upsert
    suspend fun upsert(schedule: FocusSchedule)

    @Query("DELETE FROM focus_schedules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE focus_schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM focus_schedules")
    suspend fun deleteAll()
}

@Dao
interface BlockedSiteDao {
    @Query("SELECT * FROM blocked_sites ORDER BY domain")
    fun observeAll(): Flow<List<BlockedSite>>

    @Query("SELECT * FROM blocked_sites")
    suspend fun all(): List<BlockedSite>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(site: BlockedSite)

    @Query("DELETE FROM blocked_sites WHERE domain = :domain")
    suspend fun delete(domain: String)

    @Query("DELETE FROM blocked_sites")
    suspend fun deleteAll()
}

@Dao
interface BlockKeywordDao {
    @Query("SELECT * FROM block_keywords ORDER BY keyword")
    fun observeAll(): Flow<List<BlockKeyword>>

    @Query("SELECT * FROM block_keywords")
    suspend fun all(): List<BlockKeyword>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(keyword: BlockKeyword)

    @Query("DELETE FROM block_keywords WHERE keyword = :keyword")
    suspend fun delete(keyword: String)

    @Query("DELETE FROM block_keywords")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories")
    suspend fun all(): List<Category>

    @Upsert
    suspend fun upsert(category: Category): Long

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}

@Dao
interface CategoryMemberDao {
    @Query("SELECT * FROM category_members")
    fun observeAll(): Flow<List<CategoryMember>>

    @Query("SELECT * FROM category_members")
    suspend fun all(): List<CategoryMember>

    @Query("SELECT * FROM category_members WHERE categoryId = :categoryId")
    fun observeForCategory(categoryId: Long): Flow<List<CategoryMember>>

    @Query("SELECT categoryId FROM category_members WHERE key = :key")
    suspend fun categoryIdFor(key: String): Long?

    @Upsert
    suspend fun upsert(member: CategoryMember)

    @Query("DELETE FROM category_members WHERE key = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM category_members WHERE categoryId = :categoryId")
    suspend fun deleteForCategory(categoryId: Long)

    @Query("DELETE FROM category_members")
    suspend fun deleteAll()
}

@Dao
interface HiddenItemDao {
    @Query("SELECT * FROM hidden_items")
    fun observeAll(): Flow<List<HiddenItem>>

    @Query("SELECT * FROM hidden_items")
    suspend fun all(): List<HiddenItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: HiddenItem)

    @Query("DELETE FROM hidden_items WHERE kind = :kind AND id = :id")
    suspend fun delete(kind: String, id: String)

    @Query("DELETE FROM hidden_items")
    suspend fun deleteAll()
}

@Dao
interface ElementRuleDao {
    @Query("SELECT * FROM element_rules")
    fun observeAll(): Flow<List<ElementRule>>

    @Query("SELECT * FROM element_rules WHERE packageName = :pkg")
    suspend fun forPackage(pkg: String): List<ElementRule>

    @Query("SELECT DISTINCT packageName FROM element_rules WHERE enabled = 1")
    fun observeActivePackages(): Flow<List<String>>

    @Upsert
    suspend fun upsert(rule: ElementRule)

    @Query("DELETE FROM element_rules WHERE packageName = :pkg AND element = :element")
    suspend fun delete(pkg: String, element: String)

    @Query("DELETE FROM element_rules")
    suspend fun deleteAll()
}

@Dao
interface ElementUsageDao {
    @Query(
        """
        INSERT INTO element_usage(day, packageName, element, ms)
        VALUES(:day, :pkg, :element, :ms)
        ON CONFLICT(day, packageName, element) DO UPDATE SET ms = ms + :ms
        """
    )
    suspend fun add(day: String, pkg: String, element: String, ms: Long)

    @Query("SELECT * FROM element_usage WHERE packageName = :pkg AND day BETWEEN :from AND :to")
    suspend fun range(pkg: String, from: String, to: String): List<ElementUsage>

    @Query("SELECT * FROM element_usage")
    suspend fun all(): List<ElementUsage>

    @Query(
        "SELECT SUM(ms) FROM element_usage WHERE packageName = :pkg " +
            "AND element = :element AND day BETWEEN :from AND :to"
    )
    suspend fun sum(pkg: String, element: String, from: String, to: String): Long?

    @Query("SELECT SUM(ms) FROM element_usage WHERE element = :element AND day BETWEEN :from AND :to")
    suspend fun sumAll(element: String, from: String, to: String): Long?

    @Query("DELETE FROM element_usage WHERE day < :cutoffDay")
    suspend fun pruneOlderThan(cutoffDay: String)

    @Query("DELETE FROM element_usage")
    suspend fun deleteAll()
}

@Dao
interface SessionGrantDao {
    @Query("SELECT * FROM session_grants")
    fun observeAll(): Flow<List<SessionGrant>>

    @Query("SELECT * FROM session_grants")
    suspend fun all(): List<SessionGrant>

    @Upsert
    suspend fun upsert(grant: SessionGrant)

    @Query("DELETE FROM session_grants WHERE targetId = :targetId")
    suspend fun delete(targetId: String)

    @Query("DELETE FROM session_grants")
    suspend fun deleteAll()
}

@Dao
interface BlockEventDao {
    @Query("SELECT * FROM block_events ORDER BY at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<BlockEvent>>

    @Insert
    suspend fun insert(event: BlockEvent)

    @Query("DELETE FROM block_events")
    suspend fun clear()
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: MetaEntry)

    @Query("DELETE FROM meta")
    suspend fun clear()
}

@Database(
    entities = [
        DailyUsage::class, HourlyUsage::class, UsageLimit::class, FocusSchedule::class,
        BlockedSite::class, BlockKeyword::class, Category::class, CategoryMember::class,
        HiddenItem::class, ElementRule::class, ElementUsage::class, SessionGrant::class,
        BlockEvent::class, MetaEntry::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dailyUsageDao(): DailyUsageDao
    abstract fun hourlyUsageDao(): HourlyUsageDao
    abstract fun usageLimitDao(): UsageLimitDao
    abstract fun focusScheduleDao(): FocusScheduleDao
    abstract fun blockedSiteDao(): BlockedSiteDao
    abstract fun blockKeywordDao(): BlockKeywordDao
    abstract fun categoryDao(): CategoryDao
    abstract fun categoryMemberDao(): CategoryMemberDao
    abstract fun hiddenItemDao(): HiddenItemDao
    abstract fun elementRuleDao(): ElementRuleDao
    abstract fun elementUsageDao(): ElementUsageDao
    abstract fun sessionGrantDao(): SessionGrantDao
    abstract fun blockEventDao(): BlockEventDao
    abstract fun metaDao(): MetaDao
}
