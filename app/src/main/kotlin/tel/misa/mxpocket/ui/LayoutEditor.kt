@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tel.misa.mxpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import tel.misa.mxpocket.R
import tel.misa.mxpocket.plugins.KeyTile
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*

internal data class BindingOption(val label: String, val call: ActionCall)

private enum class Command(val title: String) {
    CREATE_PROFILE("New profile"), COPY_PROFILE("Duplicate profile"), RENAME_PROFILE("Rename profile"),
    DELETE_PROFILE("Delete profile"), CREATE_PAGE("New page"), COPY_PAGE("Duplicate page"),
    RENAME_PAGE("Rename page"), DELETE_PAGE("Delete page"), IMPORT("Import layouts"),
    RESTORE("Restore factory layouts"),
}

/** File-backed edits run off the UI thread. A failed edit leaves its form open for correction. */
private class EditorWriter(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun <T> run(edit: () -> Result<T>, done: (T) -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                done(withContext(Dispatchers.IO) { edit().getOrThrow() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.localizedMessage ?: "Check the values and available storage, then try again."
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun LayoutEditor(
    repo: DeckRepository,
    actions: List<BindingOption>,
    signals: Map<String, String>,
    onExport: () -> Unit,
    onImport: () -> Unit,
    /** Tiles currently on the keypad; previews the active page's icons and live labels. */
    onDeck: List<KeyTile> = emptyList(),
) {
    val deck by repo.state.collectAsStateWithLifecycle()
    val decision by repo.decision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val writer = remember(repo, scope) { EditorWriter(scope) }
    var profileId by rememberSaveable { mutableStateOf(deck.activeProfileId) }
    val profile = deck.profiles.find { it.id == profileId } ?: deck.activeProfile
    var pageId by rememberSaveable(profile.id) { mutableStateOf(deck.histories.getValue(profile.id).last()) }
    val page = profile.pages.find { it.id == pageId } ?: profile.pages.first { it.id == profile.rootPageId }
    val bindings = remember(actions, profile.pages) { localBindings(actions, profile.pages) }

    // A host may already scroll its settings. Never put an unbounded vertical scroller inside it.
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val scroll = rememberScrollState()
        val column = Modifier.widthIn(max = 640.dp).fillMaxWidth()
        Column(
            (if (constraints.hasBoundedHeight) column.verticalScroll(scroll) else column)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text("Layout editor", Modifier.semantics { heading() }, color = Color.White,
                    style = MaterialTheme.typography.headlineSmall)
                EditorNote("On deck: ${deck.activeProfile.name} / ${deck.activePage.name}")
            }
            repo.loadError?.let { EditorError("$it. Use Layout backups below to recover.") }

            key(profile.id, page.id) {
                var command by rememberSaveable { mutableStateOf<Command?>(null) }
                var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
                var swapping by rememberSaveable { mutableStateOf(false) }
                var swapFrom by rememberSaveable { mutableStateOf<Int?>(null) }
                val active = profile.id == deck.activeProfileId
                val pinned = profile.id == deck.pinnedProfileId
                val writable = !writer.busy && repo.loadError == null
                fun closeSwap() { swapping = false; swapFrom = null }
                fun selectPage(id: String) { pageId = id }
                fun selectProfile(id: String) { profileId = id }
                fun navigate(action: () -> Result<Unit>) {
                    writer.run({
                        synchronized(repo) {
                            if (repo.state.value.activeProfileId != profile.id)
                                Result.failure(IllegalStateException("The active profile changed. Choose it again before navigating."))
                            else action()
                        }
                    }) { pageId = repo.state.value.activePage.id }
                }

                PocketCard {
                    SelectorRow("Profile", profile.name, PocketStyle.accent(profile.id), deck.profiles.map { it.id to it.name },
                        profile.id, ::selectProfile) {
                        EditorMenu("Manage profile") { close ->
                            MenuItem("New profile", writable && deck.profiles.size < DeckLimits.PROFILES, close) { command = Command.CREATE_PROFILE }
                            MenuItem("Duplicate profile", writable && deck.profiles.size < DeckLimits.PROFILES, close) { command = Command.COPY_PROFILE }
                            MenuItem("Rename profile", writable, close) { command = Command.RENAME_PROFILE }
                            MenuItem("Delete profile", writable && deck.profiles.size > 1, close) { command = Command.DELETE_PROFILE }
                        }
                    }
                    PocketDivider()
                    SelectorRow("Page", page.name, null, profile.pages.map { it.id to it.name }, page.id, ::selectPage) {
                        EditorMenu("Manage page") { close ->
                            MenuItem("New page", writable && profile.pages.size < DeckLimits.PAGES, close) { command = Command.CREATE_PAGE }
                            MenuItem("Duplicate page", writable && profile.pages.size < DeckLimits.PAGES, close) { command = Command.COPY_PAGE }
                            MenuItem("Rename page", writable, close) { command = Command.RENAME_PAGE }
                            MenuItem("Delete page", writable && page.id != profile.rootPageId, close) { command = Command.DELETE_PAGE }
                        }
                    }
                }

                PocketCard("Key positions", trailing = { PocketTag("3 × 3 grid", PocketColors.accent) }) {
                    EditorNote(when {
                        !swapping -> "Tap a position to edit it. Taps here never run an action."
                        swapFrom == null -> "Choose the first key to swap."
                        else -> "Choose a second key to swap with key ${swapFrom!! + 1}."
                    })
                    PocketInner {
                        repeat(3) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                repeat(3) { col ->
                                    val index = row * 3 + col
                                    val tile = page.keys[index]
                                    val live = onDeck.getOrNull(index)
                                        ?.takeIf { profile.id == deck.activeProfileId && page.id == deck.activePage.id }
                                    val title = tile.label.ifBlank { live?.label ?: bindingName(tile.action, bindings) }
                                    val icon = tile.icon.ifBlank { live?.icon.orEmpty() }
                                    // The tile keeps a hint of its LCD color so assignments stay recognizable.
                                    val color = Color(tile.color).copy(alpha = .35f).compositeOver(PocketColors.control)
                                    val selected = swapping && swapFrom == index
                                    Surface(
                                        onClick = {
                                            val from = swapFrom
                                            when {
                                                !swapping -> editingIndex = index
                                                from == null -> swapFrom = index
                                                from == index -> swapFrom = null
                                                else -> writer.run({ repo.swapKeys(profile.id, page.id, from, index) }) { closeSwap() }
                                            }
                                        },
                                        modifier = Modifier.weight(1f).aspectRatio(1f).semantics {
                                            contentDescription = buildString {
                                                append("Key ${index + 1}, $title")
                                                if (!tile.enabled) append(", disabled on deck")
                                                if (selected) append(", first key for swap")
                                            }
                                        },
                                        enabled = !writer.busy,
                                        color = color, contentColor = if (color.luminance() > .179f) Color.Black else Color.White,
                                        shape = RoundedCornerShape(12.dp),
                                        border = if (selected) BorderStroke(2.dp, PocketColors.accent)
                                            else BorderStroke(1.dp, PocketColors.buttonStroke),
                                    ) {
                                        Column(Modifier.fillMaxSize().padding(6.dp).clearAndSetSemantics {},
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                                            if (icon.isNotBlank()) Text(icon, maxLines = 1, style = MaterialTheme.typography.titleLarge)
                                            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                            Text(if (selected) "✓ k${index + 1}" else if (!tile.enabled) "k${index + 1} · off" else "k${index + 1}",
                                                color = LocalContentColor.current.copy(alpha = .6f),
                                                style = PocketMono.copy(fontSize = 10.sp, lineHeight = 12.sp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    EditorActions {
                        PocketButton(if (swapping) "Cancel swap" else "Swap keys", { if (swapping) closeSwap() else swapping = true },
                            Modifier.weight(1f), icon = R.drawable.ic_pocket_swap, enabled = writable)
                        if (active) {
                            PocketButton(if (page.id == deck.activePage.id) "Page on deck" else "Open page on deck",
                                { navigate { repo.openPage(page.id) } }, Modifier.weight(1f), icon = R.drawable.ic_pocket_layers,
                                enabled = writable && page.id != deck.activePage.id)
                            if (deck.activePage.id != profile.rootPageId) {
                                PocketButton("Back on deck", { navigate(repo::back) }, Modifier.weight(1f),
                                    icon = R.drawable.ic_pocket_chevron_left, enabled = writable)
                                PocketButton("Home on deck", { navigate(repo::root) }, Modifier.weight(1f),
                                    icon = R.drawable.ic_pocket_home, enabled = writable)
                            }
                        } else {
                            PocketButton(if (deck.pinnedProfileId == null) "Use this profile" else "Switch pinned profile",
                                { writer.run({ repo.selectProfile(profile.id) }) }, Modifier.weight(1f),
                                icon = R.drawable.ic_pocket_check, tone = PocketTone.ACCENT, enabled = writable)
                        }
                    }
                }

                PocketCard("Profile selection", trailing = { if (active) PocketTag("Active") }) {
                    val defaultName = deck.profiles.first { it.id == deck.defaultProfileId }.name
                    val pinnedName = deck.profiles.find { it.id == deck.pinnedProfileId }?.name
                    PocketInner {
                        Text("Default: $defaultName" + (pinnedName?.let { "  ·  Pinned: $it" } ?: "  ·  Not pinned"),
                            color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        EditorNote("Selection reason: ${decision.reason}")
                        decision.candidateId?.let { id ->
                            deck.profiles.find { it.id == id }?.let { EditorNote("Waiting for a stable match: ${it.name}") }
                        }
                        decision.scores[profile.id]?.let { EditorNote("${profile.name}’s current score: $it") }
                    }
                    EditorActions {
                        PocketButton(if (pinned) "Unpin profile" else "Pin this profile",
                            { writer.run({ repo.setPinned(if (pinned) null else profile.id) }) }, Modifier.weight(1f),
                            icon = R.drawable.ic_pocket_pin, enabled = writable)
                        PocketButton("Set as default", { writer.run({ repo.setDefault(profile.id) }) }, Modifier.weight(1f),
                            icon = R.drawable.ic_pocket_star, enabled = writable && profile.id != deck.defaultProfileId)
                    }
                    PocketSwitchRow("Automatic profile switching",
                        "Choose a profile from observed signals and its rules", deck.automatic, writable) {
                        writer.run({ repo.setAutomatic(it) }) }
                    PocketSwitchRow("Include ${profile.name} in automatic switching",
                        "Let automatic switching enter or leave ${profile.name}", profile.automatic, writable) {
                        writer.run({ repo.setProfileAutomatic(profile.id, it) })
                    }
                    if (!profile.automatic) EditorNote("Automatic switching won’t enter this profile or leave it while it’s active.")
                    PocketDivider()
                    RuleSection(profile, signals, writable) { rules, done ->
                        writer.run({
                            synchronized(repo) {
                                if (repo.state.value.profiles.find { it.id == profile.id }?.rules != profile.rules)
                                    Result.failure(IllegalStateException("The rules changed. Review them and try again."))
                                else repo.setRules(profile.id, rules)
                            }
                        }) { done() }
                    }
                }

                PocketCard("Layout backups") {
                    EditorNote("Export your profiles, pages and context rules together.")
                    EditorActions {
                        PocketButton("Export layouts", onExport, Modifier.weight(1f), icon = R.drawable.ic_pocket_download,
                            enabled = writable)
                        PocketButton("Import layouts", { command = Command.IMPORT }, Modifier.weight(1f),
                            icon = R.drawable.ic_pocket_upload, enabled = !writer.busy)
                    }
                    PocketButton("Restore factory layouts", { command = Command.RESTORE }, Modifier.fillMaxWidth(),
                        icon = R.drawable.ic_pocket_refresh, tone = PocketTone.DANGER, enabled = !writer.busy)
                }

                editingIndex?.let { index ->
                    KeyEditDialog(index, page.keys[index], bindings, writable, onClose = { editingIndex = null }) { updated ->
                        writer.run({ repo.setKey(profile.id, page.id, index, updated) }) { editingIndex = null }
                    }
                }
                command?.let { cmd ->
                    val close = { command = null }
                    when (cmd) {
                        Command.IMPORT -> EditorConfirm("Import layouts?", "A valid backup replaces all current layouts. Export first to keep a copy.",
                            "Choose file", !writer.busy, close) { close(); onImport() }
                        Command.RESTORE -> EditorConfirm("Restore factory layouts?", "This removes custom profiles, pages, key assignments and rules. Export first to keep a copy.",
                            "Restore", !writer.busy, close) { writer.run(repo::restoreDefaults) { close(); profileId = repo.state.value.activeProfileId; pageId = repo.state.value.activePage.id } }
                        Command.DELETE_PROFILE -> EditorConfirm("Delete ${profile.name}?", "Its pages and context rules will be removed. Keys that switch to it will be unassigned.",
                            "Delete profile", writable, close) {
                            writer.run({ repo.deleteProfile(profile.id) }) { close(); profileId = repo.state.value.activeProfileId }
                        }
                        Command.DELETE_PAGE -> EditorConfirm("Delete ${page.name}?", "Its nine keys will be removed. Keys that open it will be unassigned.",
                            "Delete page", writable, close) {
                            writer.run({ repo.deletePage(profile.id, page.id) }) { close(); pageId = profile.rootPageId }
                        }
                        else -> NameDialog(cmd.title, when (cmd) {
                            Command.COPY_PROFILE -> "${profile.name} copy"
                            Command.COPY_PAGE -> "${page.name} copy"
                            Command.RENAME_PROFILE -> profile.name
                            Command.RENAME_PAGE -> page.name
                            else -> ""
                        }, writable, close) { name ->
                            when (cmd) {
                                Command.CREATE_PROFILE -> writer.run({ repo.createProfile(name) }) { close(); selectProfile(it) }
                                Command.COPY_PROFILE -> writer.run({ repo.duplicateProfile(profile.id, name) }) { close(); selectProfile(it) }
                                Command.RENAME_PROFILE -> writer.run({ repo.renameProfile(profile.id, name) }) { close() }
                                Command.CREATE_PAGE -> writer.run({ repo.createPage(profile.id, name) }) { close(); selectPage(it) }
                                Command.COPY_PAGE -> writer.run({ repo.duplicatePage(profile.id, page.id, name) }) { close(); selectPage(it) }
                                Command.RENAME_PAGE -> writer.run({ repo.renamePage(profile.id, page.id, name) }) { close() }
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
    writer.error?.let { message ->
        AlertDialog(onDismissRequest = { writer.error = null }, title = { Text("Layout not changed") },
            text = { EditorError(message) },
            confirmButton = { TextButton(onClick = { writer.error = null }) { Text("OK") } })
    }
}

@Composable
private fun SelectorRow(label: String, name: String, accent: PocketAccent?, options: List<Pair<String, String>>, selected: String,
                        choose: (String) -> Unit, manage: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            var open by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Choose ${label.lowercase()}") { open = true }
                .semantics(mergeDescendants = true) { contentDescription = "$label: $name" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (accent != null) PocketIconTile(accent, 32.dp)
                else Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(PocketColors.control),
                    contentAlignment = Alignment.Center) { PocketIcon(R.drawable.ic_pocket_document, PocketColors.muted, 16.dp) }
                Column(Modifier.weight(1f)) {
                    PocketLabel(label)
                    Text(name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, item) ->
                    DropdownMenuItem(text = { Text(if (selected == id) "✓ $item" else item) }, onClick = { open = false; choose(id) })
                }
            }
        }
        manage()
    }
}

@Composable
private fun EditorMenu(label: String, content: @Composable ColumnScope.(() -> Unit) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        PocketButton(label, { open = true }, trailingIcon = R.drawable.ic_pocket_chevron_down)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { close(); action() })
}

@Composable
internal fun EditorHeading(text: String) {
    Text(text, Modifier.padding(top = 4.dp).semantics { heading() }, color = Color.White,
        style = MaterialTheme.typography.titleSmall)
}

@Composable
internal fun EditorNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun EditorError(text: String) {
    Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

@Composable
internal fun EditorActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2, content = content)
}

@Composable
internal fun EditorSwitch(label: String, checked: Boolean, enabled: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = set),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@Composable
internal fun EditorConfirm(title: String, detail: String, verb: String, enabled: Boolean, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(detail) } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        confirmButton = { Button(onClick = confirm, enabled = enabled) { Text(verb) } })
}

@Composable
private fun NameDialog(title: String, initial: String, enabled: Boolean, close: () -> Unit, submit: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val error = when {
        name.isBlank() -> "Enter a name."
        name.trim().length > DeckLimits.NAME -> "Use ${DeckLimits.NAME} characters or fewer."
        else -> null
    }
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, isError = attempted && error != null)
            if (attempted && error != null) EditorError(error)
        }
    }, confirmButton = {
        Button(onClick = { attempted = true; if (error == null) submit(name.trim()) }, enabled = enabled) { Text("Save") }
    }, dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
