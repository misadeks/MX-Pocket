@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tel.misa.mxpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import tel.misa.mxpocket.decks.Favorite

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
    discoverApps: () -> List<Favorite>,
    detectionControls: @Composable () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Layout editor", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            EditorNote("On deck: ${deck.activeProfile.name} / ${deck.activePage.name}")
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

                Spacer(Modifier.height(4.dp))
                SelectorRow("Profile", profile.name, deck.profiles.map { it.id to it.name }, profile.id, ::selectProfile) {
                    EditorMenu("Manage profile") { close ->
                        MenuItem("New profile", writable && deck.profiles.size < DeckLimits.PROFILES, close) { command = Command.CREATE_PROFILE }
                        MenuItem("Duplicate profile", writable && deck.profiles.size < DeckLimits.PROFILES, close) { command = Command.COPY_PROFILE }
                        MenuItem("Rename profile", writable, close) { command = Command.RENAME_PROFILE }
                        MenuItem("Delete profile", writable && deck.profiles.size > 1, close) { command = Command.DELETE_PROFILE }
                    }
                }
                SelectorRow("Page", page.name, profile.pages.map { it.id to it.name }, page.id, ::selectPage) {
                    EditorMenu("Manage page") { close ->
                        MenuItem("New page", writable && profile.pages.size < DeckLimits.PAGES, close) { command = Command.CREATE_PAGE }
                        MenuItem("Duplicate page", writable && profile.pages.size < DeckLimits.PAGES, close) { command = Command.COPY_PAGE }
                        MenuItem("Rename page", writable, close) { command = Command.RENAME_PAGE }
                        MenuItem("Delete page", writable && page.id != profile.rootPageId, close) { command = Command.DELETE_PAGE }
                    }
                }

                EditorHeading("Key positions")
                EditorNote(when {
                    !swapping -> "Tap a position to edit it. Taps here never run an action."
                    swapFrom == null -> "Choose the first key to swap."
                    else -> "Choose a second key to swap with key ${swapFrom!! + 1}."
                })
                repeat(3) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        repeat(3) { col ->
                            val index = row * 3 + col
                            val tile = page.keys[index]
                            val title = tile.label.ifBlank { bindingName(tile.action, bindings) }
                            val color = Color(tile.color).compositeOver(MaterialTheme.colorScheme.surface)
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
                                modifier = Modifier.weight(1f).semantics {
                                    contentDescription = buildString {
                                        append("Key ${index + 1}, $title")
                                        if (!tile.enabled) append(", disabled on deck")
                                        if (selected) append(", first key for swap")
                                    }
                                },
                                enabled = !writer.busy,
                                color = color, contentColor = if (color.luminance() > .179f) Color.Black else Color.White,
                                shape = MaterialTheme.shapes.medium,
                                border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
                            ) {
                                Column(Modifier.heightIn(min = 86.dp).padding(9.dp).clearAndSetSemantics {},
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (selected) "✓ ${index + 1}" else "${index + 1}  ${tile.icon}",
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                    if (!tile.enabled) Text("Off", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
                EditorActions {
                    TextButton(onClick = { if (swapping) closeSwap() else swapping = true }, enabled = writable) {
                        Text(if (swapping) "Cancel swap" else "Swap keys")
                    }
                    if (active) {
                        TextButton(onClick = { navigate { repo.openPage(page.id) } }, enabled = writable && page.id != deck.activePage.id) {
                            Text(if (page.id == deck.activePage.id) "Page on deck" else "Open page on deck")
                        }
                        if (deck.activePage.id != profile.rootPageId) {
                            TextButton(onClick = { navigate(repo::back) }, enabled = writable) { Text("Back on deck") }
                            TextButton(onClick = { navigate(repo::root) }, enabled = writable) { Text("Home on deck") }
                        }
                    } else {
                        TextButton(onClick = { writer.run({ repo.selectProfile(profile.id) }) }, enabled = writable) {
                            Text(if (deck.pinnedProfileId == null) "Use this profile" else "Switch pinned profile")
                        }
                    }
                }
                HorizontalDivider()

                EditorHeading("Profile selection")
                val defaultName = deck.profiles.first { it.id == deck.defaultProfileId }.name
                val pinnedName = deck.profiles.find { it.id == deck.pinnedProfileId }?.name
                EditorNote("Default: $defaultName" + (pinnedName?.let { "  ·  Pinned: $it" } ?: "  ·  Not pinned"))
                EditorActions {
                    OutlinedButton(onClick = { writer.run({ repo.setPinned(if (pinned) null else profile.id) }) }, enabled = writable) {
                        Text(if (pinned) "Unpin profile" else "Pin this profile")
                    }
                    TextButton(onClick = { writer.run({ repo.setDefault(profile.id) }) },
                        enabled = writable && profile.id != deck.defaultProfileId) { Text("Set as default") }
                }
                EditorSwitch("Automatic profile switching", deck.automatic, writable) { writer.run({ repo.setAutomatic(it) }) }
                EditorSwitch("Include ${profile.name} in automatic switching", profile.automatic, writable) {
                    writer.run({ repo.setProfileAutomatic(profile.id, it) })
                }
                if (!profile.automatic) EditorNote("Automatic switching won’t enter this profile or leave it while it’s active.")
                EditorNote("Selection reason: ${decision.reason}")
                decision.candidateId?.let { id ->
                    deck.profiles.find { it.id == id }?.let { EditorNote("Waiting for a stable match: ${it.name}") }
                }
                decision.scores[profile.id]?.let { EditorNote("${profile.name}’s current score: $it") }
                RuleSection(profile, deck.profiles, signals, writable, discoverApps, detectionControls) { rules, done ->
                    writer.run({
                        synchronized(repo) {
                            if (repo.state.value.profiles.find { it.id == profile.id }?.rules != profile.rules)
                                Result.failure(IllegalStateException("The rules changed. Review them and try again."))
                            else repo.setRules(profile.id, rules)
                        }
                    }) { done() }
                }

                HorizontalDivider()
                EditorHeading("Layout backups")
                EditorNote("Export your profiles, pages and context rules together.")
                EditorActions {
                    OutlinedButton(onClick = onExport, enabled = writable) { Text("Export layouts") }
                    OutlinedButton(onClick = { command = Command.IMPORT }, enabled = !writer.busy) { Text("Import layouts") }
                }
                TextButton(onClick = { command = Command.RESTORE }, enabled = !writer.busy) { Text("Restore factory layouts") }

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
private fun SelectorRow(label: String, name: String, options: List<Pair<String, String>>, selected: String,
                        choose: (String) -> Unit, manage: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            var open by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text("$label: $name ▾", maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

@Composable
private fun MenuItem(label: String, enabled: Boolean, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, enabled = enabled, onClick = { close(); action() })
}

@Composable
internal fun EditorHeading(text: String) {
    Text(text, Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
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
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
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
