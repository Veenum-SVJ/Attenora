package dev.opensource.focusguard.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first

/**
 * Local JSON backup/restore — no network, no accounts. The file lives wherever
 * the user picks (SAF), so nothing ever leaves the device unless they move it.
 */
class BackupRepository(private val db: AppDatabase) {

    companion object {
        const val FORMAT_VERSION = 1
    }

    suspend fun exportJson(): String {
        val root = JSONObject()
        root.put("format", FORMAT_VERSION)
        root.put("app", "dev.opensource.focusguard")
        root.put("exportedAt", System.currentTimeMillis())

        root.put("dailyUsage", JSONArray().apply {
            db.dailyUsageDao().range("0000-01-01", "9999-12-31").forEach { r ->
                put(JSONObject().put("day", r.day).put("pkg", r.packageName).put("ms", r.usedMs))
            }
        })
        root.put("hourlyUsage", JSONArray().apply {
            db.hourlyUsageDao().range("0000-01-01", "9999-12-31").forEach { r ->
                put(JSONObject().put("day", r.day).put("pkg", r.packageName)
                    .put("hour", r.hour).put("ms", r.ms))
            }
        })
        root.put("elementUsage", JSONArray().apply {
            db.elementUsageDao().all().forEach { r ->
                put(JSONObject().put("day", r.day).put("pkg", r.packageName)
                    .put("element", r.element).put("ms", r.ms))
            }
        })
        root.put("limits", JSONArray().apply {
            db.usageLimitDao().all().forEach { l ->
                put(JSONObject()
                    .put("id", l.id).put("type", l.type).put("kind", l.targetKind)
                    .put("target", l.targetId).put("limitMs", l.dailyLimitMs)
                    .put("cooldownMs", l.cooldownMs)
                    .put("choices", l.sessionChoices).put("days", l.daysMask)
                    .put("start", l.startMinute).put("end", l.endMinute)
                    .put("name", l.name).put("enabled", l.enabled).put("order", l.sortOrder))
            }
        })
        root.put("schedules", JSONArray().apply {
            db.focusScheduleDao().all().forEach { s ->
                put(JSONObject()
                    .put("id", s.id).put("name", s.name).put("start", s.startMinute)
                    .put("end", s.endMinute).put("days", s.daysMask)
                    .put("blockAll", s.blockAll).put("enabled", s.enabled))
            }
        })
        root.put("blockedSites", JSONArray().apply {
            db.blockedSiteDao().all().forEach { put(it.domain) }
        })
        root.put("keywords", JSONArray().apply {
            db.blockKeywordDao().all().forEach { put(it.keyword) }
        })
        root.put("categories", JSONArray().apply {
            db.categoryDao().all().forEach { c ->
                put(JSONObject().put("id", c.id).put("name", c.name)
                    .put("color", c.color).put("order", c.sortOrder).put("builtin", c.isBuiltin))
            }
        })
        root.put("members", JSONArray().apply {
            db.categoryMemberDao().all().forEach {
                put(JSONObject().put("key", it.key).put("categoryId", it.categoryId))
            }
        })
        root.put("hidden", JSONArray().apply {
            db.hiddenItemDao().all().forEach {
                put(JSONObject().put("kind", it.kind).put("id", it.id))
            }
        })
        root.put("elementRules", JSONArray().apply {
            // suspend context: read the flow's current value once.
            db.elementRuleDao().observeAll().first().forEach { r ->
                put(JSONObject().put("pkg", r.packageName).put("element", r.element).put("enabled", r.enabled))
            }
        })
        return root.toString(2)
    }

    /** Replace all local data with the backup's contents. */
    suspend fun importJson(raw: String): Result<Unit> = runCatching {
        val root = JSONObject(raw)
        require(root.optInt("format", -1) == FORMAT_VERSION) { "Unsupported backup format" }

        // Wipe first so restore is a true snapshot.
        db.elementUsageDao().deleteAll()
        db.dailyUsageDao().deleteAll()
        db.hourlyUsageDao().deleteAll()
        db.usageLimitDao().deleteAll()
        db.focusScheduleDao().deleteAll()
        db.blockedSiteDao().deleteAll()
        db.blockKeywordDao().deleteAll()
        db.categoryMemberDao().deleteAll()
        db.categoryDao().deleteAll()
        db.hiddenItemDao().deleteAll()
        db.elementRuleDao().deleteAll()
        db.reelCountDao().deleteAll()
        db.sessionGrantDao().deleteAll()
        db.blockEventDao().clear()
        db.metaDao().clear()

        root.optJSONArray("dailyUsage")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.dailyUsageDao().add(o.getString("day"), o.getString("pkg"), o.getLong("ms"), 0)
            }
        }
        root.optJSONArray("limits")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.usageLimitDao().upsert(
                    UsageLimit(
                        id = o.optLong("id"),
                        type = o.getString("type"), targetKind = o.getString("kind"),
                        targetId = o.optString("target"), dailyLimitMs = o.optLong("limitMs"),
                        cooldownMs = o.optLong("cooldownMs"),
                        sessionChoices = o.optString("choices"), daysMask = o.optInt("days", 0x7F),
                        startMinute = o.optInt("start"), endMinute = o.optInt("end", 1440),
                        name = o.optString("name"), enabled = o.optBoolean("enabled", true),
                        sortOrder = o.optInt("order")
                    )
                )
            }
        }
        root.optJSONArray("schedules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.focusScheduleDao().upsert(
                    FocusSchedule(
                        id = o.optLong("id"), name = o.getString("name"),
                        startMinute = o.optInt("start"), endMinute = o.optInt("end"),
                        daysMask = o.optInt("days"), blockAll = o.optBoolean("blockAll"),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
        }
        root.optJSONArray("blockedSites")?.let { arr ->
            for (i in 0 until arr.length()) {
                db.blockedSiteDao().insert(BlockedSite(arr.getString(i), System.currentTimeMillis()))
            }
        }
        root.optJSONArray("keywords")?.let { arr ->
            for (i in 0 until arr.length()) {
                db.blockKeywordDao().insert(BlockKeyword(arr.getString(i), System.currentTimeMillis()))
            }
        }
        root.optJSONArray("categories")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.categoryDao().upsert(
                    Category(
                        id = o.optLong("id"), name = o.getString("name"),
                        color = o.optLong("color"), sortOrder = o.optInt("order"),
                        isBuiltin = o.optBoolean("builtin")
                    )
                )
            }
        }
        root.optJSONArray("members")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.categoryMemberDao().upsert(CategoryMember(o.getString("key"), o.getLong("categoryId")))
            }
        }
        root.optJSONArray("hidden")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.hiddenItemDao().insert(HiddenItem(o.getString("kind"), o.getString("id"), System.currentTimeMillis()))
            }
        }
        root.optJSONArray("hourlyUsage")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.hourlyUsageDao().add(o.getString("day"), o.getString("pkg"), o.getInt("hour"), o.getLong("ms"))
            }
        }
        root.optJSONArray("elementUsage")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.elementUsageDao().add(o.getString("day"), o.getString("pkg"), o.getString("element"), o.getLong("ms"))
            }
        }
        root.optJSONArray("elementRules")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.elementRuleDao().upsert(
                    ElementRule(o.getString("pkg"), o.getString("element"), o.optBoolean("enabled", true))
                )
            }
        }
        Unit
    }

    /** CSV rows for the usage stats export (day, package, minutes). */
    suspend fun csvRows(): List<String> {
        val rows = ArrayList<String>("day,package,minutes".split("\n"))
        db.dailyUsageDao().range("0000-01-01", "9999-12-31").sortedBy { it.day }.forEach {
            rows.add("${it.day},${it.packageName},${it.usedMs / 60_000}")
        }
        return rows
    }
}

/** Not used directly here; kept for the caller that streams CSV via SAF. */
fun writeTextToUri(context: Context, uri: Uri, text: String): Result<Unit> = runCatching {
    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
    } ?: error("Could not open $uri")
}
