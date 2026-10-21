package dev.opensource.focusguard.ui.common

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.height
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
import dev.opensource.focusguard.enforcement.Reels
import java.util.concurrent.ConcurrentHashMap

/** App-wide composition access to the manual DI container. */
val LocalAppContainer = androidx.compose.runtime.compositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}

/** Accent color for nav/drawer icon chips and their icons. */
val LocalAccentColor = compositionLocalOf { dev.opensource.focusguard.ui.theme.FgColors.Lavender }

/**
 * Per-app icon mask. Fixed by the design system — the old user-selectable
 * app-icon/tab-chip shapes were retired in v1.2, so every list renders app
 * icons the same way.
 */
fun appIconShape(size: Dp): Shape = RoundedCornerShape(size * 0.28f)

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

/**
 * Human names for the packages we reference in UI (in-app blocking, limits)
 * but that are not necessarily installed on the device, so
 * PackageManager has no label to give us and we'd otherwise show
 * "com.instagram.android".
 */
private val knownAppLabels = mapOf(
    "com.google.android.youtube" to "YouTube",
    "app.revanced.android.youtube" to "YouTube",
    "com.vanced.android.youtube" to "YouTube",
    "com.google.android.apps.youtube.music" to "YouTube Music",
    "com.instagram.android" to "Instagram",
    "com.instagram.lite" to "Instagram Lite",
    "com.facebook.katana" to "Facebook",
    "com.facebook.lite" to "Facebook Lite",
    "com.facebook.orca" to "Messenger",
    "com.snapchat.android" to "Snapchat",
    "com.twitter.android" to "X",
    "com.zhiliaoapp.musically" to "TikTok",
    "com.ss.android.ugc.trill" to "TikTok",
    "com.whatsapp" to "WhatsApp",
    "com.whatsapp.w4b" to "WhatsApp Business",
    "org.telegram.messenger" to "Telegram",
    "com.reddit.frontpage" to "Reddit",
    "com.spotify.music" to "Spotify",
    "com.netflix.mediaclient" to "Netflix",
    "com.discord" to "Discord",
    "com.pinterest" to "Pinterest",
    "com.linkedin.android" to "LinkedIn",
    "com.google.android.apps.maps" to "Maps",
    "com.android.chrome" to "Chrome"
)

fun appLabel(context: Context, pkg: String): String = labelCache.getOrPut(pkg) {
    val resolved = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
    resolved?.takeIf { it.isNotBlank() && it != pkg } ?: knownAppLabels[pkg] ?: pkg
}

/** Process-wide icon cache: decoding a Drawable on every recomposition is a
 *  reliable source of scroll jank (allocation + GPU upload per frame). */
private val iconCache = ConcurrentHashMap<String, android.graphics.Bitmap?>()

fun appIconBitmap(context: Context, pkg: String): android.graphics.Bitmap? {
    if (iconCache.size > 256) iconCache.clear()
    return iconCache.computeIfAbsent(pkg) {
        // The catalogue is keyed on canonical packages, but plenty of people
        // run a Lite build or a patched client — a different package with its
        // own icon. Without the fallback those installs show as a letter tile
        // even though the app is right there on the phone.
        val candidates = listOf(pkg) + Reels.alternates(pkg)
        candidates.firstNotNullOfOrNull { candidate ->
            runCatching {
                val bitmap = context.packageManager.getApplicationIcon(candidate).toBitmap()
                // Some apps hand back a drawable with no intrinsic size, which
                // decodes to a 1x1 plate that reads as a broken logo. Anything
                // that small is treated as "no icon" so the initial tile shows.
                if (bitmap.width < 8 || bitmap.height < 8) null else bitmap
            }.getOrNull()
        }
    }
}

@Composable
fun AppIcon(packageName: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon: android.graphics.Bitmap? = remember(packageName) {
        appIconBitmap(context, packageName)
    }
    val shape = appIconShape(size)
    if (icon != null) {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size).clip(shape)
        )
    } else {
        // Not installed, so PackageManager has no drawable to give us (this is
        // the common case on the Usage Limits and In-App Blocking screens,
        // which list apps you may not have). Draw the same initial-tile idea
        // the launcher uses: a tinted square that is stable per package, so
        // Instagram is always the same tile, and the initial is legible
        // instead of a flat gray box.
        val tint = dev.opensource.focusguard.ui.theme.FgColors
            .chartColor(kotlin.math.abs(packageName.hashCode()))
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(tint.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                appLabel(context, packageName).take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF16161A)
            )
        }
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
                .background(
                    if (granted) dev.opensource.focusguard.ui.theme.LocalFgSemantic.current.success
                    else dev.opensource.focusguard.ui.theme.LocalFgSemantic.current.danger
                )
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
                color = dev.opensource.focusguard.ui.theme.LocalFgSemantic.current.success,
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

/**
 * Open the accessibility page for FocusGuard's own service.
 *
 * The detail screen exists only as a hidden action ("android.settings.
 * ACCESSIBILITY_DETAILS_SETTINGS") and stock Android 14 guards it with
 * OPEN_ACCESSIBILITY_DETAILS_SETTINGS (signature|installer), so a third-party
 * app gets SecurityException there. Try it anyway — some OEM builds allow it —
 * and fall back to the service list, which works everywhere.
 */
fun openAccessibilitySettings(context: Context, serviceClass: Class<*>) {
    val detail = Intent(ACCESSIBILITY_DETAILS_ACTION).apply {
        putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, serviceClass))
    }
    val opened = runCatching { context.startActivity(detail) }.isSuccess
    if (!opened) {
        runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }
}

private const val ACCESSIBILITY_DETAILS_ACTION = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

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
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
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
        FgIcon(
            dev.opensource.focusguard.ui.icons.FgIcons.ChevronRight,
            null,
            size = 18.dp,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

/** Grouped surface card. Depth comes from the surface step, never a shadow. */
@Composable
fun FgCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}

/**
 * Hairline separator. [inset] matches the leading padding of the rows above
 * and below it so the line starts where the text does, the way iOS insets
 * its separators past the row icon.
 */
@Composable
fun FgDivider(
    modifier: Modifier = Modifier,
    inset: Dp = 0.dp,
    color: Color = MaterialTheme.colorScheme.outlineVariant
) {
    Box(
        modifier
            .padding(start = inset)
            .fillMaxWidth()
            .height(1.dp)
            .background(color)
    )
}

/**
 * Grouped inset list: a raised card holding rows, separated by hairlines.
 * This is the primary structural unit of the design — screens are built from
 * a handful of these rather than from a grid of tiles.
 */
@Composable
fun FgGroup(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = color
    ) {
        androidx.compose.foundation.layout.Column(content = content)
    }
}

/**
 * The FocusGuard brand mark — the same shield-with-check as the launcher icon,
 * drawn flat (no gradient) so it sits correctly on the Quiet Instrument
 * surfaces. Two-tone, so it cannot be an ImageVector: [FgIcon] tints every
 * path in a vector the same colour.
 */
@Composable
fun FocusMark(
    size: Dp = 34.dp,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    mark: Color = MaterialTheme.colorScheme.background
) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 108f
        val shield = androidx.compose.ui.graphics.Path().apply {
            moveTo(54f * u, 24f * u)
            lineTo(80f * u, 34f * u)
            lineTo(80f * u, 54f * u)
            cubicTo(80f * u, 72f * u, 69f * u, 81f * u, 54f * u, 88f * u)
            cubicTo(39f * u, 81f * u, 28f * u, 72f * u, 28f * u, 54f * u)
            lineTo(28f * u, 34f * u)
            close()
        }
        drawPath(shield, tint)
        val check = androidx.compose.ui.graphics.Path().apply {
            moveTo(45f * u, 55f * u)
            lineTo(51.5f * u, 61.5f * u)
            lineTo(64f * u, 48.5f * u)
            lineTo(59.5f * u, 44f * u)
            lineTo(51.5f * u, 52.5f * u)
            lineTo(49.5f * u, 50.5f * u)
            close()
        }
        drawPath(check, mark)
    }
}

/**
 * Major version only, e.g. "V1". Point releases are deliberately invisible in
 * the UI — the app shows the generation, not the patch level.
 */
val FgVersionLabel: String =
    "V" + dev.opensource.focusguard.BuildConfig.VERSION_NAME.substringBefore('.')

/** Centred, dimmed app identity line used at the foot of Settings. */
@Composable
fun AboutFooter(
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "FocusGuard $FgVersionLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(2.dp))
        Text(
            "Fully offline · no ads · no accounts",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
