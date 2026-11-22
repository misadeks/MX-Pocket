package tel.misa.mxpocket.shared.ui

import tel.misa.mxpocket.i18n.WorkspaceCopy as W
import tel.misa.mxpocket.i18n.DesktopCopy as H
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

/** The grant-managing host keeps its existing phone controls and their order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PluginSettingsList(plugins: List<PluginItem>, event: (PocketScreenEvent) -> Unit) {
    WorkspaceColumn(title = { WorkspaceTitle(W.Plugins.text()) }) {
        Text(W.PluginsAndroid.text(),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (plugin in plugins) {
            if (plugin.unavailableReason != null) PluginCard(plugin, event)
            else PocketCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PocketIconTile(PocketStyle.accent(plugin.id))
                    Column(Modifier.weight(1f)) {
                        Text(plugin.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        Text(W.Capabilities.text(plugin.capabilities.ifBlank { W.None.text() }), color = PocketColors.faint, style = PocketMono)
                    }
                    Switch(plugin.enabled, { event(PocketScreenEvent.EnablePlugin(plugin.id, it)) }, enabled = plugin.canToggle,
                        modifier = Modifier.semantics { contentDescription = plugin.title })
                }
                Text(plugin.details, style = MaterialTheme.typography.bodyLarge)
                if (plugin.profileAvailable || plugin.settingsLabel != null) {
                    PocketDivider()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        plugin.settingsLabel?.let { PocketButton(it, { event(PocketScreenEvent.PluginSettings(plugin.id)) }, enabled = plugin.enabled) }
                        if (plugin.profileAvailable) {
                            if (plugin.isDefault) PocketTag(W.Default.text(), PocketColors.accent)
                            else PocketTextLink(W.MakeDefault.text(), { event(PocketScreenEvent.DefaultProfile(plugin.id)) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PluginsScreen(plugins: List<PluginItem>, event: (PocketScreenEvent) -> Unit) {
    val (available, unavailable) = remember(plugins) { plugins.partition { it.unavailableReason == null } }
    val frame = LocalPageFrame.current
    Box(Modifier.fillMaxSize(), contentAlignment = frame.alignment()) {
        BoxWithConstraints(Modifier.widthIn(max = 1200.dp).fillMaxSize()) {
            val wide = maxWidth >= 760.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = frame?.let { PaddingValues(it.gutter) }
                ?: PaddingValues(horizontal = if (wide) 28.dp else 16.dp, vertical = if (wide) 24.dp else 12.dp)) {
                item("heading") {
                    WorkspaceTitle(W.Plugins.text())
                    if (available.any { it.canToggle }) Text(H.Enable.text(),
                        Modifier.padding(top = frame?.titleGap ?: 4.dp), style = MaterialTheme.typography.bodyMedium, color = PocketColors.muted)
                    if (plugins.isEmpty()) Text(H.NoPlugins.text(), Modifier.padding(top = frame?.titleGap ?: 12.dp))
                }
                for ((label, group) in listOf(H.Available.text() to available, tel.misa.mxpocket.i18n.KeypadCopy.Unavailable.text() to unavailable)) {
                    if (group.isEmpty()) continue
                    item("group:$label") {
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                            Text(group.size.toString(), color = PocketColors.muted, style = MaterialTheme.typography.labelMedium)
                        }
                        if (wide) PocketDivider()
                    }
                    items(group, key = { "plugin:${it.id}" }) { plugin ->
                        if (wide) {
                            PluginRow(plugin, event)
                            PocketDivider()
                        } else {
                            PluginCard(plugin, event)
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.pluginSemantics(item: PluginItem) = if (item.unavailableReason == null) this
    else semantics(mergeDescendants = true) { disabled() }

@Composable
private fun PluginIdentity(item: PluginItem, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PocketIconTile(PocketStyle.accent(item.id), 40.dp, muted = item.unavailableReason != null || !item.enabled)
        Column {
            Text(item.title, Modifier.semantics { heading() }, color = if (item.unavailableReason == null && item.enabled)
                PocketColors.title else PocketColors.muted, style = MaterialTheme.typography.titleMedium)
            if (item.isDefault) Text(W.Default.text(), style = MaterialTheme.typography.bodySmall,
                color = if (item.unavailableReason == null) PocketColors.accent else PocketColors.muted)
        }
    }
}

@Composable
private fun PluginDetails(item: PluginItem) {
    val reason = item.unavailableReason
    Text(reason ?: item.details, color = if (reason != null) PocketColors.muted else PocketColors.text,
        style = MaterialTheme.typography.bodyLarge)
    if (reason == null) Text(W.Capabilities.text(item.capabilities.ifBlank { W.None.text() }),
        color = PocketColors.muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PluginToggle(item: PluginItem, event: (PocketScreenEvent) -> Unit) {
    if (item.canToggle && item.unavailableReason == null) Switch(item.enabled,
        { event(PocketScreenEvent.EnablePlugin(item.id, it)) }, modifier = Modifier.semantics { contentDescription = item.title })
}

@Composable
private fun PluginActions(item: PluginItem, event: (PocketScreenEvent) -> Unit) {
    if (item.unavailableReason != null) return
    item.settingsLabel?.let { PocketButton(it, { event(PocketScreenEvent.PluginSettings(item.id)) }, enabled = item.enabled) }
    if (item.profileAvailable && !item.isDefault) {
        PocketButton(W.MakeDefault.text(), { event(PocketScreenEvent.DefaultProfile(item.id)) }, enabled = item.enabled)
    }
}

@Composable
private fun PluginRow(item: PluginItem, event: (PocketScreenEvent) -> Unit) {
    Row(Modifier.fillMaxWidth().pluginSemantics(item).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
        PluginIdentity(item, Modifier.weight(.85f))
        Column(Modifier.weight(1.45f), verticalArrangement = Arrangement.spacedBy(4.dp)) { PluginDetails(item) }
        Column(Modifier.width(176.dp), horizontalAlignment = Alignment.End) {
            PluginToggle(item, event)
            PluginActions(item, event)
        }
    }
}

@Composable
private fun PluginCard(item: PluginItem, event: (PocketScreenEvent) -> Unit) {
    Surface(Modifier.fillMaxWidth().pluginSemantics(item), shape = RoundedCornerShape(16.dp),
        color = if (item.unavailableReason == null) PocketColors.card else PocketColors.inner,
        border = BorderStroke(1.dp, PocketColors.cardStroke)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PluginIdentity(item, Modifier.weight(1f))
                PluginToggle(item, event)
            }
            PluginDetails(item)
            PluginActions(item, event)
        }
    }
}
