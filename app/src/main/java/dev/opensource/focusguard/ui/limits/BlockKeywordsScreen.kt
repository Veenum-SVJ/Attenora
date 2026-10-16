package dev.opensource.focusguard.ui.limits

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opensource.focusguard.data.BlockKeyword
import dev.opensource.focusguard.ui.common.FgIcon
import dev.opensource.focusguard.ui.common.LocalAppContainer
import dev.opensource.focusguard.ui.common.PageHeader
import dev.opensource.focusguard.ui.icons.FgIcons
import kotlinx.coroutines.launch

/**
 * Block Keywords: while website blocking is on, page addresses/titles that
 * contain a keyword are blocked too (checked in WebBlockService).
 */
@Composable
fun BlockKeywordsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberChallengeGate()

    val keywords by container.enforcement.keywords.collectAsState()
    val webOn by container.webBlockingActive.collectAsState()
    var input by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            title = "Block Keywords",
            onBack = onBack,
            subtitle = "Blocked when they appear in a page address or title"
        )

        if (!webOn) {
            Text(
                "Website blocking is off — keywords take effect once you enable it in Settings → Website blocking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(40) },
                placeholder = { Text("e.g. gambling") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = input.isNotBlank(), onClick = {
                val word = input.trim()
                gate.request {
                    scope.launch { container.db.blockKeywordDao().insert(BlockKeyword(word, System.currentTimeMillis())) }
                }
                input = ""
            }) { Text("Add") }
        }

        if (keywords.isEmpty()) {
            Text(
                "No keywords yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        LazyColumn {
            items(keywords, key = { it }) { keyword ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 5.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FgIcon(FgIcons.Filter, null, size = 18.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(keyword, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        androidx.compose.material3.IconButton(onClick = {
                            gate.request {
                                scope.launch { container.db.blockKeywordDao().delete(keyword) }
                            }
                        }) {
                            FgIcon(FgIcons.Trash, "Remove keyword", size = 18.dp)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
