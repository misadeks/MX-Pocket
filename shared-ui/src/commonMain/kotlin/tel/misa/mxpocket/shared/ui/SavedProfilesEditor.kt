package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tel.misa.mxpocket.deck.*

/** Browse persisted layouts, never the live plugin frame or the keypad's page history. */
@Composable
fun SavedProfilesEditor(
    repo: DeckEditorStore, disabledProfiles: Set<String>, actions: List<BindingOption>, signals: Map<String, String>,
    onExport: () -> Unit, onImport: () -> Unit, foreground: ForegroundControls?,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    val deck by repo.state.collectAsState()
    val decision by repo.decision.collectAsState()
    val scope = rememberCoroutineScope()
    val writer = remember(repo, scope) { LayoutWriter(scope, repo) }
    val writable = !writer.busy && repo.loadError == null
    var profileId by rememberSaveable { mutableStateOf<String?>(null) }
    val profiles = deck.profiles.filterNot { it.id in disabledProfiles }
    val profile = profiles.find { it.id == profileId }
    val libraryScroll = rememberScrollState()

    // If a backup replaces or disables an open target, fall back to its nearest existing parent.
    LaunchedEffect(profileId, profile?.id) { if (profile == null) profileId = null }
    key(profile?.id) {
        var pageId by rememberSaveable { mutableStateOf<String?>(null) }
        val page = profile?.pages?.find { it.id == pageId }
        LaunchedEffect(pageId, page?.id) { if (page == null) pageId = null }
        val pagesScroll = rememberScrollState()
        var settings by rememberSaveable { mutableStateOf(false) }
        key(page?.id) {
            var index by rememberSaveable { mutableStateOf<Int?>(null) }
            val draft = index?.let { i -> page?.let { key(i) {
                rememberSaveable(saver = KeyDraft.saver) { KeyDraft(it.keys[i]) }
            } } }
            var command by rememberSaveable { mutableStateOf<Command?>(null) }
            var rule by rememberSaveable { mutableStateOf<Int?>(null) }
            var removing by rememberSaveable { mutableStateOf(false) }
            val layoutScroll = rememberScrollState()
            fun back() {
                if (writer.busy) return
                when {
                    writer.error != null -> writer.error = null
                    command != null -> command = null
                    rule != null -> rule = null
                    draft?.picking != null -> draft.picking = null
                    index != null -> index = null
                    page != null -> pageId = null
                    settings -> settings = false
                    else -> profileId = null
                }
            }
            backHandler(profile != null || command != null || writer.error != null || writer.busy, ::back)
            val bindings = remember(profile?.pages, actions) { localBindings(actions, profile?.pages.orEmpty()) }
            when {
                profile == null -> LibraryPane(libraryScroll) {
                    WorkspaceTitle("Profiles")
                    TextButton(onClick = { command = Command.CREATE_PROFILE },
                        enabled = writable && deck.profiles.size < DeckLimits.PROFILES) { Text("New profile") }
                    if (profiles.isEmpty()) EditorNote("No saved profiles to show. Create one or enable a plugin.")
                    for (item in profiles) {
                        val count = item.pages.size
                        LibraryRow(item.name, if (count == 1) "1 page" else "$count pages", !writer.busy,
                            leading = { PocketIconTile(PocketStyle.accent(item.id), size = 36.dp) }) { profileId = item.id }
                    }
                    repo.loadError?.let { EditorError(it) }
                    repo.cleanupWarning?.let {
                        EditorError(it)
                        TextButton(onClick = { writer.edit(DeckEdit.RetryCleanup) }, enabled = writable) { Text("Retry cleanup") }
                    }
                    LayoutBackups(writer, writable, onExport) { command = it }
                }
                settings -> LibraryPane(rememberScrollState()) {
                    UpLink("Back to ${profile.name}", !writer.busy, ::back)
                    WorkspaceTitle("Profile settings and rules")
                    PocketButton(if (deck.activeProfileId == profile.id) "Selected for keypad" else "Use on keypad",
                        { writer.edit(DeckEdit.Select(profile.id)) }, enabled = writable && deck.activeProfileId != profile.id)
                    ProfileOptions(profile, deck, decision, signals, foreground, writer, writable,
                        edit = { removing = false; rule = it }, remove = { removing = true; rule = it })
                }
                page == null -> LibraryPane(pagesScroll) {
                    UpLink("All profiles", !writer.busy, ::back)
                    WorkspaceTitle(profile.name)
                    EditorHeading("Pages")
                    for (item in profile.pages) {
                        LibraryRow(item.name, if (item.id == profile.rootPageId) "Root page" else null, !writer.busy) {
                            pageId = item.id
                        }
                    }
                    TextButton(onClick = { command = Command.CREATE_PAGE },
                        enabled = writable && profile.pages.size < DeckLimits.PAGES) { Text("New page") }
                    ManageMenu("Manage profile", listOf(Command.RENAME_PROFILE, Command.COPY_PROFILE, Command.DELETE_PROFILE),
                        writable, { when (it) {
                            Command.COPY_PROFILE -> deck.profiles.size < DeckLimits.PROFILES
                            Command.DELETE_PROFILE -> deck.profiles.size > 1
                            else -> true
                        } }) { command = it }
                    PocketButton("Profile settings and rules", { settings = true }, icon = ShellIcon.SETTINGS, enabled = !writer.busy)
                }
                draft?.picking != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().imePadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        UpLink("Back to key", !writer.busy, ::back)
                        WorkspaceTitle("Choose a ${draft.picking?.label.orEmpty().lowercase()} action")
                        BindingChoices(bindings, draft)
                    }
                }
                index != null && draft != null -> LibraryPane(rememberScrollState()) {
                    val i = requireNotNull(index)
                    UpLink("Back to ${page.name}", !writer.busy, ::back)
                    KeyFields(i, draft, bindings, writable, ::back) { updated ->
                        writer.edit(DeckEdit.UpdateKey(profile.id, page.id, i, draft.original, updated)) { index = null }
                    }
                }
                else -> LibraryPane(layoutScroll) {
                    UpLink("Back to ${profile.name} pages", !writer.busy, ::back)
                    WorkspaceTitle(page.name)
                    EditorNote("Tap a key to edit it. Drag one onto another to swap their positions.")
                    val tiles = remember(page.keys, bindings, writer.busy) { page.keys.mapIndexed { i, saved ->
                        savedKeyFace(saved, bindings).forEditing(i, saved, !writer.busy)
                    } }
                    KeypadGrid(tiles, { i, down -> if (!down && !writer.busy) index = i },
                        Modifier.widthIn(max = 420.dp).align(Alignment.CenterHorizontally),
                        onSwap = if (writable) { from, to -> writer.edit(DeckEdit.Swap(profile.id, page.id, from, to)) } else null)
                    ManageMenu("Manage page", listOf(Command.RENAME_PAGE, Command.COPY_PAGE, Command.DELETE_PAGE), writable,
                        { when (it) {
                            Command.COPY_PAGE -> profile.pages.size < DeckLimits.PAGES
                            Command.DELETE_PAGE -> page.id != profile.rootPageId
                            else -> true
                        } }) { command = it }
                }
            }
            rule?.let { i -> if (profile != null) ProfileRuleDialog(profile, signals, writer, writable, i, removing) { rule = null } }
            command?.let { cmd ->
                // Global commands (new, import, restore) need no selection.
                val target = profile ?: deck.activeProfile
                CommandDialog(cmd, target, page ?: target.pages.first { it.id == target.rootPageId },
                    writer, writable, { command = null }, onImport,
                    selectProfile = { profileId = if (cmd == Command.DELETE_PROFILE || cmd == Command.RESTORE) null else it },
                    selectPage = { pageId = if (cmd == Command.DELETE_PAGE) null else it },
                    activeProfile = { repo.state.value.activeProfileId })
            }
        }
    }
    LayoutWriteError(writer)
}

@Composable
private fun LibraryPane(scroll: ScrollState, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().imePadding().verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun UpLink(label: String, enabled: Boolean, back: () -> Unit) {
    TextButton(onClick = back, enabled = enabled) {
        PocketIcon(ShellIcon.CHEVRON_LEFT)
        Text(label)
    }
}

@Composable
private fun LibraryRow(name: String, detail: String?, enabled: Boolean, leading: (@Composable () -> Unit)? = null,
                       open: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = open)
        .padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.invoke()
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        detail?.let { Text(it, color = PocketColors.muted, style = MaterialTheme.typography.bodyMedium) }
        PocketIcon(ShellIcon.CHEVRON_RIGHT)
    }
    HorizontalDivider(color = PocketColors.cardStroke)
}
