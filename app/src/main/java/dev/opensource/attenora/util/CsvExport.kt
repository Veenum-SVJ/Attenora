package dev.opensource.attenora.util

import android.content.Context
import android.net.Uri
import dev.opensource.attenora.data.AppDatabase
import java.time.LocalDate

/** Writes the last 90 days of rollups as CSV to a user-chosen document. */
suspend fun exportCsv(context: Context, db: AppDatabase, uri: Uri) {
    val end = LocalDate.now()
    val rows = db.dailyUsageDao().range(end.minusDays(90).toString(), end.toString())
    context.contentResolver.openOutputStream(uri)?.use { out ->
        out.bufferedWriter().use { w ->
            w.appendLine("date,package,minutes,used_ms")
            rows.sortedWith(compareBy({ it.day }, { it.packageName })).forEach { row ->
                val minutes = "%.1f".format(row.usedMs / 60000.0)
                w.appendLine("${row.day},${row.packageName},$minutes,${row.usedMs}")
            }
        }
    }
}
