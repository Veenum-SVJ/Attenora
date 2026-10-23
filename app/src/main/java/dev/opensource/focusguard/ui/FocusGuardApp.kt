package dev.opensource.focusguard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.ui.blocking.InAppBlockingScreen
import dev.opensource.focusguard.ui.common.FgDivider
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.FocusMark
import dev.opensource.focusguard.ui.common.LocalAccentColor
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.icons.FgIcons
import dev.opensource.focusguard.ui.limits.AddLimitScreen
import dev.opensource.focusguard.ui.limits.BlockKeywordsScreen
import dev.opensource.focusguard.ui.limits.UsageLimitsScreen
import dev.opensource.focusguard.ui.onboarding.OnboardingScreen
import dev.opensource.focusguard.ui.settings.SettingsScreen
import dev.opensource.focusguard.ui.stats.UsageStatsScreen
import kotlinx.coroutines.launch

/** Pushed (full-screen) destinations reachable from the drawer or lists. */
sealed interface Pushed {
    data object Settings : Pushed
    data class AddLimit(val pkg: String? = null) : Pushed
    data object BlockKeywords : Pushed
    data class AppDetail(val pkg: String) : Pushed
}

private data class TabSpec(
    val label: String,
    val icon: ImageVector
)

private val tabs = listOf(
    TabSpec("Usage Stats", FgIcons.ChartBar),
    TabSpec("Usage Limits", FgIcons.Gauge),
    TabSpec("In-App Blocking", FgIcons.Grid)
)

private data class DrawerItem(
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit
)

@Composable
fun FocusGuardApp() {
    val container = LocalAppContainer.current
    // Warm StateFlow: its current value seeds `collectAsState`, so a returning
    // user's first frame is already the real screen (no onboarding flash).
    val onboardingDone by container.onboardingDone.collectAsState()

    if (!onboardingDone) {
        OnboardingScreen()
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pushed by remember { mutableStateOf<Pushed?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun goTo(targetTab: Int) {
        pushed = null
        tab = targetTab
    }

    fun push(dest: Pushed) {
        pushed = dest
    }

    // System back closes a pushed screen, and only a tab root is allowed to
    // exit the app. Without this, hardware back from Settings killed the app.
    BackHandler(enabled = pushed != null) { pushed = null }

    val drawerItems = listOf(
        DrawerItem("Home", FgIcons.Home) { scope.launch { drawerState.close() }; goTo(0) },
        DrawerItem("Usage Limits", FgIcons.Gauge) { scope.launch { drawerState.close() }; goTo(1) },
        DrawerItem("In-App Blocking", FgIcons.Grid) { scope.launch { drawerState.close() }; goTo(2) },
        DrawerItem("Settings", FgIcons.Gear) { scope.launch { drawerState.close() }; push(Pushed.Settings) }
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = pushed == null, // drawer only on tab roots
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerContentColor = MaterialTheme.colorScheme.onBackground
            ) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(28.dp))
                    // The app's own mark, not the Usage Stats glyph — the
                    // drawer is the app identity, not a screen.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FocusMark(size = 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "FocusGuard",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    drawerItems.forEachIndexed { index, item ->
                        val isSettings = item.label == "Settings"
                        if (isSettings) {
                            FgDivider(Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                        }
                        DrawerRow(
                            item = item,
                            selected = if (isSettings) pushed == Pushed.Settings
                            else pushed == null && tab == index
                        )
                    }
                }
            }
        }
    ) {
        val current = pushed
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (current == null) {
                    TopRow(
                        query = searchQuery,
                        active = searchActive,
                        onActiveChange = { searchActive = it },
                        onQueryChange = { searchQuery = it },
                        onMenu = { scope.launch { drawerState.open() } }
                    )
                }
            },
            bottomBar = {
                // Pushed screens are full-screen with a back arrow — no tab bar.
                if (current == null) {
                    AppBottomBar(
                        selected = tab,
                        onSelect = { tab = it; pushed = null; searchActive = false; searchQuery = "" }
                    )
                }
            }
        ) { padding ->
            Box(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                when {
                    current != null -> PushedContent(
                        dest = current,
                        onPop = { pushed = null },
                        onPush = ::push,
                        onOpenInAppBlocking = { pushed = null; tab = 2 }
                    )
                    tab == 0 -> UsageStatsScreen(
                        query = searchQuery,
                        onOpenApp = { push(Pushed.AppDetail(it)) }
                    )
                    tab == 1 -> UsageLimitsScreen(
                        query = searchQuery,
                        onAddLimit = { push(Pushed.AddLimit()) },
                        onBlockKeywords = { push(Pushed.BlockKeywords) }
                    )
                    else -> InAppBlockingScreen()
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(item: DrawerItem, selected: Boolean) {
    val accent = LocalAccentColor.current
    androidx.compose.material3.NavigationDrawerItem(
        label = {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        selected = selected,
        onClick = item.action,
        icon = { FgIcon(item.icon, null, size = 22.dp) },
        colors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
            selectedContainerColor = accent.copy(alpha = 0.16f),
            selectedTextColor = accent,
            selectedIconColor = accent,
            unselectedTextColor = MaterialTheme.colorScheme.onBackground,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

/**
 * Slim top row: menu on the left, search on the right. Screens own their large
 * left-aligned titles, so nothing competes with them — the old full-width
 * search pill sat above every title and pushed it down the page.
 */
@Composable
private fun TopRow(
    query: String,
    active: Boolean,
    onActiveChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onMenu: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (active) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .height(40.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FgIcon(
                    FgIcons.Search, null, size = 18.dp,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccentColor.current),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    "Search in FocusGuard",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            inner()
                        }
                    }
                )
                androidx.compose.material3.IconButton(onClick = {
                    if (query.isNotEmpty()) onQueryChange("") else onActiveChange(false)
                }) {
                    FgIcon(FgIcons.Close, "Clear search", size = 18.dp)
                }
            }
        } else {
            androidx.compose.material3.IconButton(onClick = onMenu) {
                FgIcon(FgIcons.Menu, "Open menu", size = 24.dp)
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = { onActiveChange(true) }) {
                FgIcon(FgIcons.Search, "Search", size = 22.dp)
            }
        }
    }
}

@Composable
private fun AppBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val accent = LocalAccentColor.current
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp
    ) {
        tabs.forEachIndexed { index, spec ->
            val isSelected = selected == index
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(index) },
                icon = { FgIcon(spec.icon, null, size = 23.dp) },
                label = {
                    Text(
                        spec.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accent,
                    selectedTextColor = accent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
        }
    }
}

@Composable
private fun PushedContent(
    dest: Pushed,
    onPop: () -> Unit,
    onPush: (Pushed) -> Unit,
    onOpenInAppBlocking: () -> Unit
) {
    when (dest) {
        Pushed.Settings -> SettingsScreen(onBack = onPop)
        is Pushed.AddLimit -> AddLimitScreen(onBack = onPop, initialPkg = dest.pkg)
        Pushed.BlockKeywords -> BlockKeywordsScreen(onBack = onPop)
        is Pushed.AppDetail -> dev.opensource.focusguard.ui.stats.AppDetailScreen(
            pkg = dest.pkg,
            onBack = onPop,
            onSetLimit = { onPush(Pushed.AddLimit(dest.pkg)) },
            onBlockInApp = onOpenInAppBlocking
        )
    }
}
