package dev.opensource.focusguard.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.BlockKeyword
import dev.opensource.focusguard.data.BlockedSite
import dev.opensource.focusguard.data.Category
import dev.opensource.focusguard.data.CategoryMember
import dev.opensource.focusguard.data.HiddenItem
import dev.opensource.focusguard.data.HiddenKind
import dev.opensource.focusguard.ui.common.AppIcon
import dev.opensource.focusguard.ui.common.FgCard
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.NavRow
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.common.appLabel
import dev.opensource.focusguard.ui.icons.FgIcons
import kotlinx.coroutines.launch

/** Offline starter domain packs bundled with the app (no network needed). */
private object StarterPacks {
    val adult = listOf(
        "pornhub.com", "xvideos.com", "xnxx.com", "redtube.com", "youporn.com",
        "xhamster.com", "tube8.com", "spankbang.com", "brazzers.com", "onlyfans.com",
        "chaturbate.com", "sex.com"
    )
    val gambling = listOf(
        "bet365.com", "draftkings.com", "fanDuel.com".lowercase(), "betmgm.com",
        "pokerstars.com", "888casino.com", "williamhill.com", "betway.com",
        "stake.com", "jackpotcity.com", "royalvegascasino.com", "casino.com"
    )
}

@Composable
fun CategoriesScreen(onBack: () -> Unit, onOpenHidden: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val categories by container.categories.collectAsState()
    val members by container.db.categoryMemberDao().observeAll().collectAsState(initial = emptyList())
    val sites by container.blocklist.collectAsState()

    var expandedId by remember { mutableStateOf<Long?>(null) }
    var renaming by remember { mutableStateOf<Category?>(null) }
    var recoloring by remember { mutableStateOf<Category?>(null) }
    var creating by remember { mutableStateOf(false) }
    var addingMembers by remember { mutableStateOf<Category?>(null) }

    val membersByCategory = remember(members) { members.groupBy { it.categoryId } }
    val appsCount = remember(members) {
        members.count { it.key.startsWith("app:") }
    }
    val sitesCount = remember(members) {
        members.count { it.key.startsWith("web:") }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                PageHeader(
                    title = "Categories",
                    onBack = onBack,
                    subtitle = "Smart categories, always overridable"
                )
            }

            item {
                FgCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    NavRow(
                        title = "Hidden Apps",
                        subtitle = "Excluded from totals and Usage Stats",
                        icon = FgIcons.EyeOff,
                        onClick = onOpenHidden,
                        value = null
                    )
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Starter domain packs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Bundled offline lists — add them to a new Adult/Gambling category and limit it like any other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(onClick = {
                        scope.launch { installPack("Adult", StarterPacks.adult, container) }
                    }, modifier = Modifier.weight(1f)) { Text("Adult pack") }
                    OutlinedButton(onClick = {
                        scope.launch { installPack("Gambling", StarterPacks.gambling, container) }
                    }, modifier = Modifier.weight(1f)) { Text("Gambling pack") }
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Category Management",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "$appsCount apps · $sitesCount sites",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(categories, key = { it.id }) { cat ->
                val catMembers = membersByCategory[cat.id].orEmpty()
                val appMembers = catMembers.filter { it.key.startsWith("app:") }
                val webMembers = catMembers.filter { it.key.startsWith("web:") }
                val expanded = expandedId == cat.id

                FgCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expandedId = if (expanded) null else cat.id }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color(cat.color))
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(cat.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(
                                    "${appMembers.size} apps · ${webMembers.size} websites",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FgIcon(if (expanded) FgIcons.ChevronUp else FgIcons.ChevronDown, null, size = 20.dp)
                        }

                        if (expanded) {
                            Column(Modifier.heightIn(max = 340.dp)) {
                                // Members (scrollable slice).
                                Column(
                                    Modifier
                                        .weight(1f, fill = false)
                                        .heightIn(max = 260.dp)
                                ) {
                                    val shown = (appMembers + webMembers)
                                    if (shown.isEmpty()) {
                                        Text(
                                            "No members yet.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                        )
                                    }
                                    shown.forEach { member -> MemberRow(cat, member) }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(onClick = { addingMembers = cat }, modifier = Modifier.weight(1f)) {
                                        Text("Add members")
                                    }
                                    OutlinedButton(onClick = { renaming = cat }, modifier = Modifier.weight(1f)) {
                                        Text("Rename")
                                    }
                                    OutlinedButton(
                                        onClick = { recoloring = cat },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Recolor") }
                                }
                                if (!cat.isBuiltin) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                container.db.categoryMemberDao().deleteForCategory(cat.id)
                                                container.db.categoryDao().delete(cat.id)
                                            }
                                            expandedId = null
                                        },
                                        modifier = Modifier.padding(horizontal = 12.dp)
                                    ) {
                                        Text("Delete category", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(110.dp)) }
        }

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { creating = true }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FgIcon(FgIcons.Plus, null, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Add Category",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    // ---- dialogs ----
    renaming?.let { cat ->
        NameDialog(
            title = "Rename category",
            initial = cat.name,
            onCancel = { renaming = null },
            onConfirm = { newName ->
                scope.launch { container.db.categoryDao().upsert(cat.copy(name = newName)) }
                renaming = null
            }
        )
    }

    recoloring?.let { cat ->
        ColorDialog(
            current = cat.color,
            onCancel = { recoloring = null },
            onConfirm = { color ->
                scope.launch { container.db.categoryDao().upsert(cat.copy(color = color)) }
                recoloring = null
            }
        )
    }

    creating.let {
        if (creating) {
            NameDialog(
                title = "New category",
                initial = "",
                onCancel = { creating = false },
                onConfirm = { newName ->
                    scope.launch {
                        container.db.categoryDao().upsert(
                            Category(name = newName, color = 0xFF6FB3F2L, sortOrder = 100)
                        )
                    }
                    creating = false
                }
            )
        }
    }

    addingMembers?.let { cat ->
        AddMembersDialog(
            category = cat,
            onDone = { addingMembers = null }
        )
    }
}

@Composable
private fun MemberRow(cat: Category, member: CategoryMember) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isApp = member.key.startsWith("app:")
    val id = member.key.substringAfter(':')

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isApp) {
            AppIcon(id, size = 30.dp)
        } else {
            FgIcon(FgIcons.Globe, null, size = 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (isApp) appLabel(context, id) else id,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        androidx.compose.material3.IconButton(onClick = {
            scope.launch { container.db.categoryMemberDao().deleteByKey(member.key) }
        }) {
            FgIcon(FgIcons.Close, "Remove", size = 16.dp)
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(30) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

private val palette = listOf(
    0xFFC3A9F7L, 0xFF7FD1B9L, 0xFFF2A65AL, 0xFF6FB3F2L, 0xFFF27E89L,
    0xFFE8D05FL, 0xFF9B8AFBL, 0xFF7ED957L, 0xFFF25F5CL, 0xFF8A93A6L
)

@Composable
private fun ColorDialog(current: Long, onCancel: () -> Unit, onConfirm: (Long) -> Unit) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Pick a color") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    palette.take(5).forEach { c ->
                        ColorSwatch(c, selected == c) { selected = c }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    palette.drop(5).forEach { c ->
                        ColorSwatch(c, selected == c) { selected = c }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
private fun ColorSwatch(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(color))
            .then(
                if (selected) Modifier.border(3.dp, Color.White, RoundedCornerShape(50))
                else Modifier
            )
            .clickable(onClick = onClick)
    )
}

/** Dialog listing installed apps + current sites to add as category members. */
@Composable
private fun AddMembersDialog(category: Category, onDone: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val members by container.db.categoryMemberDao().observeForCategory(category.id)
        .collectAsState(initial = emptyList())
    val currentKeys = remember(members) { members.map { it.key }.toSet() }
    val sites by container.blocklist.collectAsState()
    var search by remember { mutableStateOf("") }

    val installedApps = remember {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        runCatching {
            pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.distinct()
                .filterNot { it == context.packageName }
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add to ${category.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search apps & sites…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                val filteredApps = installedApps
                    .filter { search.isBlank() || appLabel(context, it).contains(search, true) || it.contains(search, true) }
                    .sortedBy { appLabel(context, it).lowercase() }
                    .take(60)
                val filteredSites = sites
                    .filter { search.isBlank() || it.contains(search, true) }
                    .sorted()
                    .take(20)

                Column(Modifier.heightIn(max = 320.dp)) {
                    (filteredApps + filteredSites).forEach { value ->
                        val key = if (value in sites) "web:$value" else "app:$value"
                        val checked = key in currentKeys
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        if (checked) container.db.categoryMemberDao().deleteByKey(key)
                                        else container.db.categoryMemberDao().upsert(
                                            CategoryMember(key, category.id)
                                        )
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (key.startsWith("app:")) AppIcon(value, size = 28.dp)
                            else FgIcon(FgIcons.Globe, null, size = 20.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (key.startsWith("app:")) appLabel(context, value) else value,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    scope.launch {
                                        if (checked) container.db.categoryMemberDao().deleteByKey(key)
                                        else container.db.categoryMemberDao().upsert(
                                            CategoryMember(key, category.id)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } }
    )
}

/** Install a bundled starter pack: sites + a category holding them. */
private suspend fun installPack(name: String, domains: List<String>, container: dev.opensource.focusguard.AppContainer) {
    val now = System.currentTimeMillis()
    domains.forEach {
        container.db.blockedSiteDao().insert(BlockedSite(it, now))
    }
    val existing = container.db.categoryDao().all().firstOrNull { it.name == name }
    val catId = existing?.id ?: container.db.categoryDao().upsert(
        Category(name = name, color = 0xFFF25F5CL, sortOrder = 90)
    )
    domains.forEach { domain ->
        container.db.categoryMemberDao().upsert(CategoryMember("web:$domain", catId))
    }
}
