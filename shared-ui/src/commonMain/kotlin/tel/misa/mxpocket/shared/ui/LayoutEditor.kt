@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.present
import tel.misa.mxpocket.plugins.bindingPreview

private enum class Pane { LIBRARY, PREVIEW, SETTINGS, KEY }
internal enum class Command(val title: String) {
    CREATE_PROFILE("New profile"), COPY_PROFILE("Duplicate profile"), RENAME_PROFILE("Rename profile"),
    DELETE_PROFILE("Delete profile"), CREATE_PAGE("New page"), COPY_PAGE("Duplicate page"),
    RENAME_PAGE("Rename page"), DELETE_PAGE("Delete page"), IMPORT("Import layouts"), RESTORE("Restore factory layouts")
}

internal class LayoutWriter(private val scope: CoroutineScope, private val repo: DeckEditorStore) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    fun edit(change: DeckEdit, done: () -> Unit = {}) = run({ repo.edit(change) }) { done() }
    fun create(change: DeckCreate, done: (String) -> Unit) = run({ repo.create(change) }, done)
    private fun <T> run(action: suspend () -> Result<T>, done: (T) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                done(action().getOrThrow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Check the values and available storage, then try again."
            } finally {
                busy = false
            }
        }
    }
}

/**
 * [backHandler] installs the host's Back/Escape callback. When disabled the host should handle
 * navigation normally. Choosing a profile selects it on the keypad (matching the Profiles carousel);
 * choosing a page, editing and swapping never navigate the keypad or run an action.
 */
@Composable
fun LayoutEditor(
    repo: DeckEditorStore,
    actions: List<BindingOption>,
    signals: Map<String, String>,
    onExport: () -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
    foreground: ForegroundControls? = null,
    live: PocketScreenState? = null,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    val deck by repo.state.collectAsState()
    val decision by repo.decision.collectAsState()
    val scope = rememberCoroutineScope()
    val writer = remember(repo, scope) { LayoutWriter(scope, repo) }
    // The editor always edits the profile on the keypad, so the Profiles carousel and this picker agree.
    val profile = deck.activeProfile
    // A profile created or copied here goes on the keypad once its write has finished.
    var pendingSelect by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(pendingSelect, writer.busy) {
        val id = pendingSelect
        if (id != null && !writer.busy) {
            pendingSelect = null
            if (id != repo.state.value.activeProfileId) writer.edit(DeckEdit.Select(id))
        }
    }
    var selectedPage by rememberSaveable(profile.id) { mutableStateOf(deck.histories.getValue(profile.id).last()) }
    // A page change on the keypad brings the editor along; picking a page here never changes the keypad.
    var seenPage by rememberSaveable(profile.id) { mutableStateOf(deck.activePage.id) }
    LaunchedEffect(profile.id, deck.activePage.id) {
        if (seenPage != deck.activePage.id) { seenPage = deck.activePage.id; selectedPage = deck.activePage.id }
    }
    val page = profile.pages.find { it.id == selectedPage } ?: profile.pages.first { it.id == profile.rootPageId }
    val bindings = remember(profile.pages, actions) { localBindings(actions, profile.pages) }
    key(profile.id, page.id) {
        var pane by rememberSaveable { mutableStateOf(Pane.PREVIEW) }
        var index by rememberSaveable { mutableStateOf<Int?>(null) }
        val draft = index?.let { i -> key(i) { rememberSaveable(saver = KeyDraft.saver) { KeyDraft(page.keys[i]) } } }
        var swapping by rememberSaveable { mutableStateOf(false) }
        var command by rememberSaveable { mutableStateOf<Command?>(null) }
        var rule by rememberSaveable { mutableStateOf<Int?>(null) }
        var removingRule by rememberSaveable { mutableStateOf(false) }
        val writable = !writer.busy && repo.loadError == null
        fun finishSwap() { swapping = false }
        fun show(p: Pane) { index = null; finishSwap(); pane = p }
        fun selectProfile(id: String) {
            if (id != deck.activeProfileId) writer.edit(DeckEdit.Select(id))
            show(Pane.PREVIEW)
        }
        fun selectPage(id: String) { selectedPage = id; show(Pane.PREVIEW) }
        fun pickKey(i: Int) { finishSwap(); index = i; pane = Pane.KEY }
        val active = live?.takeIf { it.activeId == profile.id && it.activePageId == page.id }
        /** The same faces as the physical LCDs; the active page shows the live images. */
        fun gridTiles(): List<KeypadTile> = page.keys.mapIndexed { i, saved ->
            val tile = if (index == i && draft != null) draft.preview() else saved
            val current = active?.tiles?.get(i)
            val visual = if (tile == saved && current != null) current else {
                val base = current?.face?.takeIf {
                    saved.action == tile.action && saved.icon == tile.icon &&
                        !(saved.label.isNotBlank() && tile.label.isBlank()) && saved.color == tile.color
                } ?: tile.bindingPreview()
                savedKeyFace(tile, bindings, base)
            }
            // Editing or swapping a readout or unavailable slot must select it, never execute it.
            visual.forEditing(i, tile, !writer.busy)
        }

        BoxWithConstraints(modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val libraryWidth = (maxWidth * .22f).coerceIn(150.dp, 256.dp)
            val bounded = constraints.hasBoundedHeight
            val canBack = writer.error != null || command != null || rule != null || draft?.picking != null ||
                swapping || index != null || (!wide && pane != Pane.PREVIEW)
            backHandler(canBack) {
                if (!writer.busy) when {
                    writer.error != null -> writer.error = null
                    command != null -> command = null
                    rule != null -> rule = null
                    draft?.picking != null -> draft.picking = null
                    swapping -> finishSwap()
                    else -> show(Pane.PREVIEW)
                }
            }

            val library: @Composable () -> Unit = {
                WorkspaceTitle("Layouts")
                EditorNote("Choosing a profile puts it on the keypad. Choosing a page only opens it here for editing.")
                LibraryGroup("Profile", deck.profiles.map { it.id to it.name }, profile.id, !writer.busy, ::selectProfile,
                    listOf(Command.CREATE_PROFILE, Command.COPY_PROFILE, Command.RENAME_PROFILE, Command.DELETE_PROFILE),
                    allowed = { cmd -> writable && when (cmd) {
                        Command.CREATE_PROFILE, Command.COPY_PROFILE -> deck.profiles.size < DeckLimits.PROFILES
                        Command.DELETE_PROFILE -> deck.profiles.size > 1
                        else -> true
                    } }, choose = { command = it })
                LibraryGroup("Page", profile.pages.map { it.id to it.name }, page.id, !writer.busy, ::selectPage,
                    listOf(Command.CREATE_PAGE, Command.COPY_PAGE, Command.RENAME_PAGE, Command.DELETE_PAGE),
                    allowed = { cmd -> writable && when (cmd) {
                        Command.CREATE_PAGE, Command.COPY_PAGE -> profile.pages.size < DeckLimits.PAGES
                        Command.DELETE_PAGE -> page.id != profile.rootPageId
                        else -> true
                    } }, choose = { command = it })
                if (!wide) TextButton(onClick = { show(Pane.PREVIEW) }) { Text("Back to preview") }
            }
            val preview: @Composable () -> Unit = {
                WorkspaceTitle("Keypad layout")
                PocketCard {
                    PocketLabel("Profile / Page")
                    if (!wide) PocketButton("${profile.name} / ${page.name} ▾", { show(Pane.LIBRARY) },
                        Modifier.fillMaxWidth(), PocketStyle.accent(profile.id).icon, enabled = !writer.busy)
                    else Text("${profile.name} / ${page.name}", style = MaterialTheme.typography.titleMedium)
                    PocketButton("Swap keys", { show(Pane.PREVIEW); swapping = true }, Modifier.fillMaxWidth(),
                        ShellIcon.SWAP, PocketTone.ACCENT, enabled = writable)
                }
                if (!wide || index != null) PocketButton("Profile settings and rules", { show(Pane.SETTINGS) },
                    Modifier.fillMaxWidth(), ShellIcon.SETTINGS, enabled = !writer.busy)
                repo.loadError?.let { EditorError("$it. Recover in Layout backups under profile settings.") }
                repo.cleanupWarning?.let {
                    EditorError(it)
                    TextButton(onClick = { writer.edit(DeckEdit.RetryCleanup) }, enabled = writable) { Text("Retry cleanup") }
                }
            }
            val inspector: @Composable () -> Unit = {
                if (index != null && draft != null) {
                    val i = index!!
                    KeyFields(i, draft, bindings, writable, { if (!writer.busy) show(Pane.PREVIEW) }) { updated ->
                        writer.edit(DeckEdit.UpdateKey(profile.id, page.id, i, draft.original, updated)) { show(Pane.PREVIEW) }
                    }
                } else {
                    if (!wide) TextButton(onClick = { show(Pane.PREVIEW) }) { Text("Back to preview") }
                    ProfileOptions(profile, deck, decision, signals, foreground, writer, writable,
                        edit = { removingRule = false; rule = it }, remove = { removingRule = true; rule = it })
                    LayoutBackups(writer, writable, onExport) { command = it }
                }
            }

            if (wide) Row(Modifier.fillMaxSize().testTag("wide-editor")) {
                EditorPane(Modifier.width(libraryWidth).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow), bounded, library)
                VerticalDivider()
                EditorPane(Modifier.weight(1f), bounded, preview)
                VerticalDivider()
                key(index) { EditorPane(Modifier.weight(1.1f), bounded, inspector) }
            } else key(pane) {
                EditorPane(Modifier.fillMaxSize().testTag("compact-editor"), bounded, when (pane) {
                    Pane.LIBRARY -> library
                    Pane.PREVIEW -> preview
                    Pane.SETTINGS, Pane.KEY -> inspector
                })
            }
        }
        if (draft?.picking != null) BindingDialog(bindings, draft)
        if (swapping) AlertDialog(onDismissRequest = { if (!writer.busy) finishSwap() },
            title = { Text("Swap keys") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditorNote("Drag a key onto another to swap them. Tap a key to edit it. Nothing here runs an action.")
                    // Taps and drags only edit the layout; key execution stays on Home and the keypad.
                    KeypadGrid(gridTiles(), { i, down -> if (!down && !writer.busy) pickKey(i) },
                        Modifier.widthIn(max = 360.dp).testTag("swap-grid"),
                        onSwap = { from, to -> if (writable) writer.edit(DeckEdit.Swap(profile.id, page.id, from, to)) })
                }
            },
            confirmButton = { TextButton(onClick = ::finishSwap, enabled = !writer.busy) { Text("Done") } })
        rule?.let { i ->
            ProfileRuleDialog(profile, signals, writer, writable, i, removingRule) { rule = null }
        }
        command?.let { cmd ->
            CommandDialog(cmd, profile, page, writer, writable, close = { command = null }, onImport,
                selectProfile = { pendingSelect = it; show(Pane.PREVIEW) }, selectPage = ::selectPage,
                activeProfile = { repo.state.value.activeProfileId })
        }
    }
    LayoutWriteError(writer)
}

internal fun savedKeyFace(tile: DeckKey, bindings: List<BindingOption>,
                         base: KeyTile? = tile.bindingPreview()): KeypadTile =
    tile.present(base, tile.label.ifBlank { bindingName(tile.action, bindings) },
        if (!tile.enabled) "Disabled" else base?.footer.orEmpty(), tile.enabled && tile.action != null).toGridTile(true)

internal fun KeypadTile.forEditing(index: Int, key: DeckKey, selectable: Boolean) =
    copy(enabled = selectable, readOnly = false, selected = false, accessibleName = listOf(
        "Key ${index + 1}", key.icon, label, if (key.enabled) "" else "Off").filter(String::isNotBlank).joinToString(", "))

@Composable
internal fun LayoutWriteError(writer: LayoutWriter) {
    writer.error?.let { message ->
        AlertDialog(onDismissRequest = { writer.error = null }, title = { Text("Layout not changed") },
            text = { EditorError(message) }, confirmButton = { TextButton(onClick = { writer.error = null }) { Text("OK") } })
    }
}

@Composable
internal fun ProfileRuleDialog(profile: DeckProfile, signals: Map<String, String>, writer: LayoutWriter,
                               writable: Boolean, i: Int, removing: Boolean, close: () -> Unit) {
    // Keep the baseline captured on opening. Never apply an old array index to a new ruleset.
    val baseline = rememberSaveable(saver = rulesSaver) { profile.rules }
    val original = baseline.getOrNull(i)
    if (removing && original != null) EditorConfirm("Remove rule?",
        "${original.signal} = ${original.value} will no longer contribute to ${profile.name}’s score.",
        "Remove", writable, close) {
        writer.edit(DeckEdit.Rules(profile.id, baseline, baseline.filterIndexed { n, _ -> n != i }), close)
    } else RuleDialog(original, signals, writable, close) { updated ->
        val next = if (i == -1) baseline + updated else baseline.mapIndexed { n, old -> if (n == i) updated else old }
        writer.edit(DeckEdit.Rules(profile.id, baseline, next), close)
    }
}

@Composable
internal fun ProfileOptions(profile: DeckProfile, deck: DeckState, decision: DeckDecision, signals: Map<String, String>,
                            foreground: ForegroundControls?, writer: LayoutWriter, writable: Boolean,
                            edit: (Int) -> Unit, remove: (Int) -> Unit) {
    PocketCard { ProfileSettings(profile, deck, decision, writable) { writer.edit(it) } }
    PocketCard { RuleSection(profile, deck.profiles, signals, writable, foreground, addApp = { pkg, done ->
        writer.edit(DeckEdit.Rules(profile.id, profile.rules, profile.rules +
            DeckRule(tel.misa.mxpocket.FOREGROUND_APP, pkg, tel.misa.mxpocket.APP_RULE_WEIGHT)), done)
    }, edit = edit, remove = remove) }
}

@Composable
internal fun LayoutBackups(writer: LayoutWriter, writable: Boolean, onExport: () -> Unit, choose: (Command) -> Unit) {
    PocketCard("Layout backups", ShellIcon.DOCUMENT) {
        EditorNote("Export profiles, pages and context rules together.")
        EditorActions {
            PocketButton("Export layouts", onExport, icon = ShellIcon.DOWNLOAD, enabled = writable)
            PocketButton("Import layouts", { choose(Command.IMPORT) }, enabled = !writer.busy)
            PocketButton("Restore factory layouts", { choose(Command.RESTORE) }, tone = PocketTone.DANGER, enabled = !writer.busy)
        }
    }
}

private val rulesSaver = listSaver<List<DeckRule>, String>(
    save = { rules -> rules.flatMap { listOf(it.signal, it.value, it.weight.toString()) } },
    restore = { parts -> parts.chunked(3).map { (signal, value, weight) -> DeckRule(signal, value, weight.toInt()) } },
)

@Composable
private fun EditorPane(modifier: Modifier, bounded: Boolean, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        val panel = Modifier.widthIn(max = 620.dp).fillMaxWidth()
        Column((if (bounded) panel.verticalScroll(rememberScrollState()) else panel).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun LibraryGroup(
    title: String, items: List<Pair<String, String>>, selected: String, enabled: Boolean, select: (String) -> Unit,
    commands: List<Command>, allowed: (Command) -> Boolean, choose: (Command) -> Unit,
) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
    items.forEach { (id, name) ->
        NavigationDrawerItem(label = { Text(name) }, selected = id == selected, onClick = { if (enabled) select(id) },
            shape = MaterialTheme.shapes.small)
    }
    ManageMenu("Manage ${title.lowercase()}", commands, enabled, allowed, choose)
}

@Composable
internal fun ManageMenu(label: String, commands: List<Command>, enabled: Boolean, allowed: (Command) -> Boolean,
                        choose: (Command) -> Unit) {
    Box {
        var menu by remember { mutableStateOf(false) }
        TextButton(onClick = { menu = true }, enabled = enabled) { Text(label) }
        DropdownMenu(menu, { menu = false }) {
            commands.forEach { command ->
                DropdownMenuItem(text = { Text(command.title) }, enabled = allowed(command),
                    onClick = { menu = false; choose(command) })
            }
        }
    }
}

@Composable
private fun ProfileSettings(profile: DeckProfile, deck: DeckState, decision: DeckDecision,
                            writable: Boolean, edit: (DeckEdit) -> Unit) {
    EditorHeading("Profile selection")
    val pinned = profile.id == deck.pinnedProfileId
    val defaultName = deck.profiles.first { it.id == deck.defaultProfileId }.name
    val pinnedName = deck.profiles.find { it.id == deck.pinnedProfileId }?.name
    EditorNote("Default: $defaultName · " + (pinnedName?.let { "Pinned: $it" } ?: "Not pinned"))
    EditorActions {
        PocketButton(if (pinned) "Unpin profile" else "Pin this profile", { edit(DeckEdit.Pin(if (pinned) null else profile.id)) },
            icon = ShellIcon.PIN, tone = PocketTone.ACCENT, enabled = writable)
        PocketButton("Set as default", { edit(DeckEdit.Default(profile.id)) }, icon = ShellIcon.STAR,
            enabled = writable && profile.id != deck.defaultProfileId)
    }
    EditorSwitch("Automatic profile switching", deck.automatic, writable) { edit(DeckEdit.Automatic(it)) }
    EditorSwitch("Include ${profile.name} in automatic switching", profile.automatic, writable) {
        edit(DeckEdit.ProfileAutomatic(profile.id, it))
    }
    if (!profile.automatic) EditorNote("Automatic switching won’t enter this profile or leave it while it’s active.")
    EditorNote("Selection reason: ${decision.reason}")
    decision.candidateId?.let { id ->
        deck.profiles.find { it.id == id }?.let { EditorNote("Waiting for a stable match: ${it.name}") }
    }
    decision.scores[profile.id]?.let { EditorNote("${profile.name}’s current score: $it") }
}

@Composable
internal fun CommandDialog(cmd: Command, profile: DeckProfile, page: DeckPage, writer: LayoutWriter, writable: Boolean,
                          close: () -> Unit, onImport: () -> Unit, selectProfile: (String) -> Unit,
                          selectPage: (String) -> Unit, activeProfile: () -> String) {
    when (cmd) {
        Command.IMPORT -> EditorConfirm("Import layouts?", "A valid backup replaces all current layouts. Export first to keep a copy.",
            "Choose file", !writer.busy, close) { close(); onImport() }
        Command.RESTORE -> EditorConfirm("Restore factory layouts?", "This removes custom profiles, pages, key assignments and rules. Export first to keep a copy.",
            "Restore", !writer.busy, close) { writer.edit(DeckEdit.Restore) { close(); selectProfile(activeProfile()) } }
        Command.DELETE_PROFILE -> EditorConfirm("Delete ${profile.name}?", "Its pages and context rules will be removed. Keys that switch to it will be unassigned.",
            "Delete profile", writable, close) { writer.edit(DeckEdit.DeleteProfile(profile.id)) { close(); selectProfile(activeProfile()) } }
        Command.DELETE_PAGE -> EditorConfirm("Delete ${page.name}?", "Its nine keys will be removed. Keys that open it will be unassigned.",
            "Delete page", writable, close) { writer.edit(DeckEdit.DeletePage(profile.id, page.id)) { close(); selectPage(profile.rootPageId) } }
        else -> NameDialog(cmd.title, when (cmd) {
            Command.COPY_PROFILE -> "${profile.name} copy"
            Command.COPY_PAGE -> "${page.name} copy"
            Command.RENAME_PROFILE -> profile.name
            Command.RENAME_PAGE -> page.name
            else -> ""
        }, writable, close) { name ->
            when (cmd) {
                Command.CREATE_PROFILE -> writer.create(DeckCreate.Profile(name)) { close(); selectProfile(it) }
                Command.COPY_PROFILE -> writer.create(DeckCreate.CopyProfile(profile.id, name)) { close(); selectProfile(it) }
                Command.RENAME_PROFILE -> writer.edit(DeckEdit.RenameProfile(profile.id, name), close)
                Command.CREATE_PAGE -> writer.create(DeckCreate.Page(profile.id, name)) { close(); selectPage(it) }
                Command.COPY_PAGE -> writer.create(DeckCreate.CopyPage(profile.id, page.id, name)) { close(); selectPage(it) }
                Command.RENAME_PAGE -> writer.edit(DeckEdit.RenamePage(profile.id, page.id, name), close)
                else -> Unit
            }
        }
    }
}

@Composable
internal fun EditorHeading(text: String) = Text(text, Modifier.padding(top = 8.dp).semantics { heading() },
    style = MaterialTheme.typography.titleLarge)

@Composable
internal fun EditorNote(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun EditorError(text: String) = Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)

@Composable
internal fun EditorActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun EditorSwitch(label: String, checked: Boolean, enabled: Boolean, set: (Boolean) -> Unit) {
    PocketSwitchRow(label, checked = checked, enabled = enabled, onChange = set)
}

@Composable
private fun EditorConfirm(title: String, detail: String, verb: String, enabled: Boolean, close: () -> Unit, confirm: () -> Unit) {
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
