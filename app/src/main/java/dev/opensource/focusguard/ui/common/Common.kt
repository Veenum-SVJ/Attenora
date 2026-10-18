package dev.opensource.focusguard.ui.common

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.opensource.focusguard.AppContainer
import java.util.concurrent.ConcurrentHashMap

/** App-wide composition access to the manual DI container. */
val LocalAppContainer = androidx.compose.runtime.compositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

/** Accent color for nav/drawer icon chips and their icons. */
val LocalAccentColor = compositionLocalOf { dev.opensource.focusguard.ui.theme.FgColors.Lavender }

/** Mask applied to per-app icons in lists. */
val LocalAppIconShape = compositionLocalOf { dev.opensource.focusguard.data.AppIconShape.ROUNDED }

/** Shape of the bottom-nav icon chips. */
val LocalTabChipShape = compositionLocalOf { dev.opensource.focusguard.data.TabChipShape.ROUNDED }

fun appIconShapeFor(shape: String, size: Dp): Shape = when (shape) {
    dev.opensource.focusguard.data.AppIconShape.CIRCLE -> CircleShape
    dev.opensource.focusguard.data.AppIconShape.SQUARE -> RoundedCornerShape(0.dp)
    dev.opensource.focusguard.data.AppIconShape.SQUIRCLE -> RoundedCornerShape(size * 0.30f)
    else -> RoundedCornerShape(size * 0.22f)
}

fun tabChipShapeFor(shape: String): Shape = when (shape) {
    dev.opensource.focusguard.data.TabChipShape.CIRCLE -> CircleShape
    dev.opensource.focusguard.data.TabChipShape.PILL -> RoundedCornerShape(50)
    else -> RoundedCornerShape(14.dp)
}

/** Custom outline icon, tinted with the current content color. */
@Composable
fun FgIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = androidx.compose.material3.LocalContentColor.current
) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = tint
    )
}

/** "1h 23m" / "45m" / "30s" — compact duration for the UI. */
fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${(ms / 1000).coerceAtLeast(0)}s"
    }
}

/** "08:30" from minutes-after-midnight. */
fun formatClock(minuteOfDay: Int): String {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

/** "Mon Wed Fri" from a day mask (bit 0 = Monday). */
fun daysSummary(mask: Int): String {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val selected = names.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
    return if (selected.isEmpty()) "No days selected" else selected.joinToString(" ")
}

private val labelCache = ConcurrentHashMap<String, String>()

fun appLabel(context: Context, pkg: String): String = labelCache.getOrPut(pkg) {
    runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

/** Process-wide icon cache: decoding a Drawable on every recomposition is a
 *  reliable source of scroll jank (allocation + GPU upload per frame). */
private val iconCache = ConcurrentHashMap<String, android.graphics.Bitmap?>()

fun appIconBitmap(context: Context, pkg: String): android.graphics.Bitmap? {
    if (iconCache.size > 256) iconCache.clear()
    return iconCache.computeIfAbsent(pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap()
        }.getOrNull()
    }
}

@Composable
fun AppIcon(packageName: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon: android.graphics.Bitmap? = remember(packageName) {
        appIconBitmap(context, packageName)
    }
    val shape = appIconShapeFor(LocalAppIconShape.current, size)
    if (icon != null) {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size).clip(shape)
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.material3.CardDefaults.outlinedCardBorder()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(12.dp))
            content()
        }
    }
}

/**
 * SectionCard whose title row toggles the content in place. Border kept so
 * every block reads as its own rectangle.
 */
@Composable
fun CollapsibleSectionCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.material3.CardDefaults.outlinedCardBorder()
    ) {
        androidx.compose.foundation.layout.Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
                Spacer(Modifier.width(8.dp))
                FgIcon(
                    if (expanded) dev.opensource.focusguard.ui.icons.FgIcons.ChevronUp
                    else dev.opensource.focusguard.ui.icons.FgIcons.ChevronDown,
                    if (expanded) "Collapse" else "Expand",
                    size = 20.dp
                )
            }
            if (expanded) {
                androidx.compose.material3.HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (granted) Color(0xFF34C77B) else Color(0xFFFF6B6B))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (granted) {
            Text(
                "Granted",
                color = Color(0xFF34C77B),
                style = MaterialTheme.typography.labelMedium
            )
        } else {
            OutlinedButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

// ---- Permission probes -----------------------------------------------------

fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expected = "${context.packageName}/${serviceClass.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private const val NOTIF_PERMISSION_REQ_CODE = 41

/** Walks the wrapper chain to find the hosting Activity (if any). */
fun Context.findActivity(): android.app.Activity? {
    var ctx: Context = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Ask for POST_NOTIFICATIONS with an explicit, small request code.
 *
 * `rememberLauncherForActivityResult` on a FragmentActivity can hand the
 * permission request a code outside the lower 16 bits, which makes Android
 * throw "Can only use lower 16 bits for requestCode" and kill the app — this
 * is the crash behind the old onboarding "Allow notifications" error.
 * Callers re-read [hasNotificationPermission] on ON_RESUME, which is when the
 * system dialog has finished.
 */
fun requestNotificationPermission(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val activity = context.findActivity()
    if (activity != null) {
        runCatching {
            androidx.core.app.ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIF_PERMISSION_REQ_CODE
            )
        }
    } else {
        runCatching {
            context.startActivity(android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
        }
    }
}

// ---- v1.1 shared row components -------------------------------------------

/** Large left-aligned title with optional back arrow (pushed screens). */
@Composable
fun PageHeader(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                FgIcon(dev.opensource.focusguard.ui.icons.FgIcons.ArrowBack, "Back", size = 24.dp)
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

/** Icon + title + subtitle row with a switch on the right. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    enabled: Boolean = true
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            FgIcon(icon, null, size = 22.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Navigational settings row: icon + title + optional value + chevron. */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            FgIcon(icon, null, size = 22.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
        }
        FgIcon(dev.opensource.focusguard.ui.icons.FgIcons.ChevronRight, null, size = 18.dp)
    }
}

/** Rounded card container matching the screenshots' 20dp slate cards. */
@Composable
fun FgCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}
