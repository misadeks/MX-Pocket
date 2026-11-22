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
import tel.misa.mxpocket.i18n.*

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
    val disabled = deck.disabledPluginIds + disabledProfiles
    // A legacy pin must still be manageable even when its factory layout is no longer a choice.
    val choices = deck.navigationProfiles
    val profiles = deck.visibleProfiles.filter { it.id !in disabledProfiles && (it.id == deck.pinnedProfileId || it in choices) }
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
            val keyIndex = index
            val draft = keyIndex?.let { i -> page?.keys?.getOrNull(i)?.let { saved -> key(i) {
                rememberSaveable(saver = KeyDraft.saver) { KeyDraft(saved) }
            } } }
            val picking = draft?.picking
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
            val bindings = if (profile != null) editorBindings(profile, actions, disabled) else emptyList()
            when {
                profile == null -> LibraryPane(libraryScroll) {
                    WorkspaceTitle(WorkspaceCopy.Profiles.text())
                    TextButton(onClick = { command = Command.CREATE_PROFILE },
                        enabled = writable && deck.profiles.size < DeckLimits.PROFILES) { Text(LayoutCopy.NewProfile.text()) }
                    if (profiles.isEmpty()) EditorNote(SavedEditorCopy.NoProfiles.text())
                    for (item in profiles) {
                        LibraryRow(item.displayName(), SavedEditorCopy.PageCount.text(item.pages.size), !writer.busy,
                            leading = { PocketIconTile(PocketStyle.accent(item.id), size = 36.dp) }) { profileId = item.id }
                    }
                    repo.loadError?.let { EditorError(it) }
                    repo.cleanupWarning?.let {
                        EditorError(it)
                        TextButton(onClick = { writer.edit(DeckEdit.RetryCleanup) }, enabled = writable) { Text(LayoutCopy.RetryCleanup.text()) }
                    }
                    LayoutBackups(writer, writable, onExport) { command = it }
                }
                settings -> LibraryPane(rememberScrollState()) {
                    UpLink(SavedEditorCopy.BackTo.text(profile.displayName()), !writer.busy, ::back)
                    WorkspaceTitle(LayoutCopy.ProfileSettings.text())
                    PocketButton((if (deck.activeProfileId == profile.id) SavedEditorCopy.Selected else SavedEditorCopy.UseOnKeypad).text(),
                        { writer.edit(DeckEdit.Select(profile.id)) }, enabled = writable && deck.activeProfileId != profile.id)
                    ProfileOptions(profile, deck, decision, signals, foreground, writer, writable,
                        edit = { removing = false; rule = it }, remove = { removing = true; rule = it }, disabledIds = disabled)
                }
                page == null -> LibraryPane(pagesScroll) {
                    UpLink(SavedEditorCopy.AllProfiles.text(), !writer.busy, ::back)
                    WorkspaceTitle(profile.displayName())
                    EditorHeading(SavedEditorCopy.Pages.text())
                    for (item in profile.pages) {
                        LibraryRow(profile.displayPageName(item), if (item.id == profile.rootPageId) SavedEditorCopy.RootPage.text() else null, !writer.busy) {
                            pageId = item.id
                        }
                    }
                    TextButton(onClick = { command = Command.CREATE_PAGE },
                        enabled = writable && profile.pages.size < DeckLimits.PAGES) { Text(LayoutCopy.NewPage.text()) }
                    ManageMenu(LayoutCopy.ManageProfile.text(), listOf(Command.RENAME_PROFILE, Command.COPY_PROFILE, Command.DELETE_PROFILE),
                        writable, { when (it) {
                            Command.COPY_PROFILE -> deck.profiles.size < DeckLimits.PROFILES
                            Command.DELETE_PROFILE -> profiles.size > 1
                            else -> true
                        } }) { command = it }
                    PocketButton(LayoutCopy.ProfileSettings.text(), { settings = true }, icon = ShellIcon.SETTINGS, enabled = !writer.busy)
                }
                picking != null && draft != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().imePadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        UpLink(LayoutCopy.BackToKey.text(), !writer.busy, ::back)
                        WorkspaceTitle(picking.picker.text())
                        BindingChoices(bindings, draft)
                    }
                }
                keyIndex != null && draft != null -> LibraryPane(rememberScrollState()) {
                    UpLink(SavedEditorCopy.BackTo.text(profile.displayPageName(page)), !writer.busy, ::back)
                    KeyFields(keyIndex, draft, bindings, writable, ::back) { updated ->
                        writer.edit(DeckEdit.UpdateKey(profile.id, page.id, keyIndex, draft.original, updated)) { index = null }
                    }
                }
                else -> LibraryPane(layoutScroll) {
                    UpLink(SavedEditorCopy.BackToPages.text(profile.displayName()), !writer.busy, ::back)
                    WorkspaceTitle(profile.displayPageName(page))
                    EditorNote(LayoutCopy.SwapHint.text())
                    val tiles = remember(profile, page, bindings, writer.busy, AppLocale.current) { page.keys.mapIndexed { i, saved ->
                        savedKeyFace(saved, profile.displayKeyLabel(page, saved), bindings).forEditing(i, saved, !writer.busy)
                    } }
                    KeypadGrid(tiles, { i, down -> if (!down && !writer.busy) index = i },
                        Modifier.widthIn(max = 420.dp).align(Alignment.CenterHorizontally),
                        onSwap = if (writable) { from, to -> writer.edit(DeckEdit.Swap(profile.id, page.id, from, to)) } else null)
                    ManageMenu(LayoutCopy.ManagePage.text(), listOf(Command.RENAME_PAGE, Command.COPY_PAGE, Command.DELETE_PAGE), writable,
                        { when (it) {
                            Command.COPY_PAGE -> profile.pages.size < DeckLimits.PAGES
                            Command.DELETE_PAGE -> page.id != profile.rootPageId
                            else -> true
                        } }) { command = it }
                }
            }
            rule?.let { i -> if (profile != null) ProfileRuleDialog(profile, signals, writer, writable, i, removing) { rule = null } }
            command?.let { cmd ->
                CommandDialog(cmd, profile, page, writer, writable, { command = null }, onImport,
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
