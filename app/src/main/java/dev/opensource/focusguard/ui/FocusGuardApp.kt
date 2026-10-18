package dev.opensource.focusguard.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opensource.focusguard.ui.blocking.InAppBlockingScreen
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAccentColor
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.LocalTabChipShape
import dev.opensource.focusguard.ui.common.tabChipShapeFor
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
    data object AddLimit : Pushed
    data object BlockKeywords : Pushed
    data class AppDetail(val pkg: String) : Pushed
}

private data class TabSpec(
    val line1: String,
    val line2: String,
    val icon: ImageVector
)

private val tabs = listOf(
    TabSpec("Usage", "Stats", FgIcons.ChartBar),
    TabSpec("Usage", "Limits", FgIcons.Gauge),
    TabSpec("In-App", "Blocking", FgIcons.Grid)
)

private data class DrawerItem(
    val label: String,
    val icon: ImageVector,
    val action: () -> Unit
)

@Composable
fun FocusGuardApp() {
    val container = LocalAppContainer.current
    val onboardingDone by container.settings.onboardingDone.collectAsState(initial = false)

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
    val version = remember { "v1.1.0" }

    fun goTo(targetTab: Int) {
        pushed = null
        tab = targetTab
    }

    fun push(dest: Pushed) {
        pushed = dest
    }

    val drawerItems = listOf(
        DrawerItem("Home", FgIcons.Home) { scope.launch { drawerState.close() }; goTo(0) },
        DrawerItem("Usage Limits", FgIcons.Gauge) { scope.launch { drawerState.close() }; goTo(1) },
        DrawerItem("In-App Blocking", FgIcons.Grid) { scope.launch { drawerState.close() }; goTo(2) },
        DrawerItem("Settings", FgIcons.Sliders) { scope.launch { drawerState.close() }; push(Pushed.Settings) }
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = pushed == null, // drawer only on tab roots
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                drawerContentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(28.dp))
                    val accent = LocalAccentColor.current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(accent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            FgIcon(FgIcons.ChartBar, null, size = 24.dp, tint = accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "FocusGuard",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    drawerItems.forEachIndexed { index, item ->
                        if (item.label == "Settings") {
                            HorizontalDivider(
                                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }
                        DrawerRow(item)
                    }
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider(
                        Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FgIcon(FgIcons.Info, null, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "FocusGuard $version",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Fully offline · no ads · no accounts",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                    SearchPill(
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
                        onPush = ::push
                    )
                    tab == 0 -> UsageStatsScreen(
                        query = searchQuery,
                        onOpenApp = { push(Pushed.AppDetail(it)) }
                    )
                    tab == 1 -> UsageLimitsScreen(
                        query = searchQuery,
                        onAddLimit = { push(Pushed.AddLimit) },
                        onBlockKeywords = { push(Pushed.BlockKeywords) }
                    )
                    else -> InAppBlockingScreen()
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(item: DrawerItem) {
    val accent = LocalAccentColor.current
    val colors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
        selectedContainerColor = accent.copy(alpha = 0.16f),
        selectedTextColor = accent
    )
    androidx.compose.material3.NavigationDrawerItem(
        label = { Text(item.label, fontWeight = FontWeight.Medium) },
        selected = false,
        onClick = item.action,
        icon = { FgIcon(item.icon, null, size = 22.dp) },
        colors = colors,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
}

@Composable
private fun SearchPill(
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
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface)
            .height(54.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.IconButton(onClick = onMenu) {
            FgIcon(FgIcons.Menu, "Open menu", size = 24.dp)
        }
        if (active) {
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccentColor.current),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
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
                FgIcon(FgIcons.Close, "Clear search", size = 20.dp)
            }
        } else {
            Text(
                "Search in FocusGuard",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .clickable { onActiveChange(true) }
            )
            val accent = LocalAccentColor.current
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                FgIcon(FgIcons.Search, null, size = 20.dp, tint = accent)
            }
        }
    }
}

@Composable
private fun AppBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val accent = LocalAccentColor.current
    val chipShape = tabChipShapeFor(LocalTabChipShape.current)
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        tabs.forEachIndexed { index, spec ->
            val progress by animateFloatAsState(
                targetValue = if (selected == index) 1f else 0f,
                animationSpec = tween(220),
                label = "tab-$index"
            )
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(chipShape)
                            .background(
                                accent.copy(
                                    alpha = (0.10f + 0.10f * progress)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        FgIcon(spec.icon, null, size = 24.dp, tint = accent)
                    }
                },
                label = {
                    Text(
                        "${spec.line1}\n${spec.line2}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        lineHeight = 14.sp
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accent,
                    selectedTextColor = accent,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    }
}

@Composable
private fun PushedContent(dest: Pushed, onPop: () -> Unit, onPush: (Pushed) -> Unit) {
    when (dest) {
        Pushed.Settings -> SettingsScreen(onBack = onPop)
        Pushed.AddLimit -> AddLimitScreen(onBack = onPop)
        Pushed.BlockKeywords -> BlockKeywordsScreen(onBack = onPop)
        is Pushed.AppDetail -> dev.opensource.focusguard.ui.stats.AppDetailScreen(
            pkg = dest.pkg,
            onBack = onPop,
            onSetLimit = { onPush(Pushed.AddLimit) }
        )
    }
}
