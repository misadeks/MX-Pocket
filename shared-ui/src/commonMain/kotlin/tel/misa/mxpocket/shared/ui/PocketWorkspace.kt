@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tel.misa.mxpocket.shared.ui

import tel.misa.mxpocket.i18n.CallCopy
import tel.misa.mxpocket.i18n.WorkspaceCopy as W
import tel.misa.mxpocket.i18n.AppLanguage
import androidx.compose.foundation.selection.selectable

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import tel.misa.mxpocket.deck.DeckPage
import tel.misa.mxpocket.deck.DeckState
import tel.misa.mxpocket.deck.GAME_PROFILE
import tel.misa.mxpocket.deck.PROFILE_HOME
import tel.misa.mxpocket.i18n.DesktopCopy as H
import tel.misa.mxpocket.i18n.LauncherCopy
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.PluginFrame
import tel.misa.mxpocket.workflow.WORKFLOWS_ENABLED

enum class WorkspaceTab(private val copy: tel.misa.mxpocket.i18n.Copy) {
    HOME(W.Home), PROFILES(W.Profiles), PLUGINS(W.Plugins), WORKFLOWS(W.Flows), SETTINGS(W.Settings);
    val label get() = copy.text()

    companion object {
        val available = entries.filter { it != WORKFLOWS || WORKFLOWS_ENABLED }
    }
}

data class ProfileChoice(val id: String, val name: String, val unavailableReason: String? = null)

/** Identifies the live input targets, not their paint (e.g. timer text or album art). */
data class VirtualGrid(
    val generation: Long, val profileId: String, val page: DeckPage, val pluginPage: Int,
    val taps: List<Target>, val holds: List<Target>?,
) {
    data class Target(val token: String?, val actionable: Boolean)
}

fun virtualGrid(generation: Long, deck: DeckState, frame: PluginFrame, holds: PluginFrame?): VirtualGrid {
    fun KeyTile.target() = VirtualGrid.Target(inputToken, enabled && !readOnly)
    return VirtualGrid(generation, deck.activeProfileId, deck.activePage, frame.page,
        frame.keys.map { it.target() }, holds?.keys?.map { it.target() })
}

/** Only the standalone desktop host supplies this. A null grid means it is disconnected. */
data class DesktopSimulator(val limitations: String, val grid: VirtualGrid? = null, val supportsUsb: Boolean = false)

data class PhoneCallSettings(val supported: Boolean, val enabled: Boolean, val phoneGranted: Boolean,
    val supportsDetails: Boolean, val details: Boolean, val listenerGranted: Boolean)

data class PocketScreenState(
    val status: String,
    val pluginTitle: String,
    val tiles: List<KeypadTile>,
    val profiles: List<ProfileChoice>,
    val activeId: String,
    val reason: String = "",
    val connectionLabel: String = "",
    val connectionActive: Boolean = false,
    val diagnostics: String = "",
    val log: String = "",
    val automatic: Boolean = false,
    val directShortcuts: Boolean = false,
    val simulator: DesktopSimulator? = null,
    val usbReady: Boolean = false,
    val activePageId: String = "root",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage? = null,
    val phoneCalls: PhoneCallSettings? = null,
)

data class PluginItem(
    val id: String, val title: String, val enabled: Boolean, val capabilities: String,
    val details: String, val profileAvailable: Boolean, val isDefault: Boolean,
    val settingsLabel: String?, val canToggle: Boolean = true,
    val unavailableReason: String? = null,
)

sealed interface PocketScreenEvent {
    data object ToggleConnection : PocketScreenEvent
    data class SetAutomatic(val enabled: Boolean) : PocketScreenEvent
    data class SetDirectShortcuts(val enabled: Boolean) : PocketScreenEvent
    sealed interface DesktopInput : PocketScreenEvent
    data object EnterPreview : DesktopInput
    data object StopPreview : DesktopInput
    data class SelectPlugin(val id: String) : PocketScreenEvent
    data class VirtualKey(val index: Int, val grid: VirtualGrid, val hold: Boolean = false) : DesktopInput
    data class ChangePage(val delta: Int) : PocketScreenEvent
    data object Repaint : PocketScreenEvent
    data object ChooseImage : PocketScreenEvent
    data object RefreshDiagnostics : PocketScreenEvent
    data object SaveLog : PocketScreenEvent
    data object FeatureSetup : PocketScreenEvent
    data class EnablePlugin(val id: String, val enabled: Boolean) : PocketScreenEvent
    data class DefaultProfile(val id: String) : PocketScreenEvent
    data class PluginSettings(val id: String) : PocketScreenEvent
    data class SetTheme(val mode: ThemeMode) : PocketScreenEvent
    data class SetLanguage(val language: AppLanguage?) : PocketScreenEvent
    data class SetPhoneCalls(val enabled: Boolean) : PocketScreenEvent
    data class SetCallerDetails(val enabled: Boolean) : PocketScreenEvent
    data object AllowPhoneState : PocketScreenEvent
    data object CallerNotificationAccess : PocketScreenEvent
}

/** Both hosts supply only state and intent handlers. The active content retains its composition on resize. */
@Composable
fun PocketWorkspace(
    tab: WorkspaceTab, select: (WorkspaceTab) -> Unit, state: PocketScreenState,
    plugins: List<PluginItem>, event: (PocketScreenEvent) -> Unit,
    profiles: @Composable () -> Unit, workflows: @Composable () -> Unit,
    openDiagnosticsWindow: (() -> Unit)? = null,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
) {
    val activeTab = tab.takeIf { it in WorkspaceTab.available } ?: WorkspaceTab.HOME
    val holder = rememberSaveableStateHolder()
    var diagnosticsOpen by rememberSaveable(activeTab) { mutableStateOf(false) }
    val closeDiagnostics = { diagnosticsOpen = false }
    backHandler(diagnosticsOpen, closeDiagnostics)
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (diagnosticsOpen) {
            DiagnosticsScreen(state, closeDiagnostics, event)
            return@BoxWithConstraints
        }
        val rail = maxWidth >= 840.dp
        Column {
            Row(Modifier.weight(1f)) {
                if (rail) NavigationRail(containerColor = PocketColors.nav) {
                    WorkspaceTab.available.forEach { item ->
                        NavigationRailItem(item == activeTab, { select(item) }, icon = { TabIcon(item) },
                            label = { Text(item.label) }, colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = PocketColors.accent, selectedTextColor = PocketColors.accent,
                                indicatorColor = PocketColors.navIndicator))
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    holder.SaveableStateProvider(activeTab.name) {
                        when (activeTab) {
                            WorkspaceTab.HOME -> HomeScreen(state, event)
                            WorkspaceTab.PROFILES -> profiles()
                            WorkspaceTab.PLUGINS -> if (state.simulator == null) PluginSettingsList(plugins, event) else PluginsScreen(plugins, event)
                            WorkspaceTab.WORKFLOWS -> workflows()
                            WorkspaceTab.SETTINGS -> SettingsScreen(state, event, openDiagnosticsWindow ?: { diagnosticsOpen = true })
                        }
                    }
                }
            }
            if (!rail) NavigationBar(windowInsets = WindowInsets(0), containerColor = PocketColors.nav, tonalElevation = 0.dp) {
                WorkspaceTab.available.forEach { item ->
                    NavigationBarItem(item == activeTab, { select(item) }, icon = { TabIcon(item) },
                        label = { Text(item.label, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }, colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PocketColors.accent, selectedTextColor = PocketColors.accent,
                            indicatorColor = PocketColors.navIndicator))
                }
            }
        }
    }
}

/**
 * Home mirrors the physical keypad: the nine tiles are the same LCD images, updated as they change.
 * Browse saved profiles independently; using one on the keypad is explicit.
 */
@Composable
fun HomeScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    WorkspaceColumn(wide = true) {
        HomeHeader(state, event)
        Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = PocketColors.muted, style = MaterialTheme.typography.bodySmall)
        state.simulator?.takeUnless { it.supportsUsb }?.let { Note(it.limitations) }
        BoxWithConstraints {
            val split = maxWidth >= 720.dp
            val summary: @Composable () -> Unit = {
                NowOnKeypad(state, event)
                val launcher = ProfileChoice(PROFILE_HOME, LauncherCopy.Launcher.text())
                ProfilePicker(listOf(launcher) + state.profiles, state.activeId) {
                    event(PocketScreenEvent.SelectPlugin(it))
                }
                if (state.simulator?.supportsUsb == true) DesktopConnectionCard(state, event)
            }
            // With hardware the mirror is display-only: keys are pressed on the device, never twice.
            val grid = state.virtualInput?.takeIf { state.profiles.find { it.id == state.activeId }?.unavailableReason == null }
            val tiles = if (grid != null) state.tiles else state.tiles.map { it.copy(readOnly = true) }
            val keypad: @Composable () -> Unit = {
                PocketCard {
                    if (grid == null) KeypadGrid(tiles, { _, _ -> }, Modifier.widthIn(max = 480.dp).align(Alignment.CenterHorizontally))
                    else key(grid) {
                        // Replacing the clickable nodes cancels a pointer/key still down on the old board.
                        val holds = buildMap<Int, () -> Unit> {
                            grid.page.keys.forEachIndexed { index, binding ->
                                if (binding.holdAction != null) put(index) {
                                    if (grid.holds?.get(index)?.actionable == true)
                                        event(PocketScreenEvent.VirtualKey(index, grid, hold = true))
                                }
                            }
                        }
                        KeypadGrid(tiles, { index, pressed ->
                            if (!pressed && grid.page.keys[index].action != null)
                                event(PocketScreenEvent.VirtualKey(index, grid))
                        }, Modifier.widthIn(max = 480.dp).align(Alignment.CenterHorizontally), holds,
                            onPage = { delta -> event(PocketScreenEvent.ChangePage(delta)) })
                    }
                }
            }
            if (split) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(.85f), verticalArrangement = Arrangement.spacedBy(12.dp)) { summary() }
                Column(Modifier.weight(1.15f)) { keypad() }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { summary(); keypad() }
        }
    }
}

private data class ConnectionLook(val label: String, val color: Color)

private val PocketScreenState.wiredActive get() = usbReady || connectionActive
private val PocketScreenState.virtualInput get() = simulator?.grid?.takeUnless { wiredActive }

@Composable
private fun connectionLook(state: PocketScreenState) = when {
    state.usbReady -> ConnectionLook(W.UsbConnected.text(), PocketColors.success)
    state.connectionActive -> ConnectionLook(W.Connecting.text(), PocketColors.warning)
    state.virtualInput != null -> ConnectionLook(W.VirtualKeypad.text(), PocketColors.accent)
    else -> ConnectionLook(W.NotConnected.text(), PocketColors.muted)
}

@Composable
private fun HomeHeader(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    val look = connectionLook(state)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { WorkspaceTitle("MX Pocket") }
        // The state is spelled out next to the dot; color never carries it alone.
        Surface(shape = CircleShape, color = PocketColors.card, border = BorderStroke(1.dp, PocketColors.cardStroke)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PocketDot(look.color)
                Text(look.label, style = MaterialTheme.typography.labelLarge, color = PocketColors.text)
            }
        }
        if (state.simulator == null) Surface(onClick = { event(PocketScreenEvent.ToggleConnection) },
            shape = CircleShape, color = PocketColors.accent, modifier = Modifier.size(48.dp).semantics {
                role = Role.Button
                contentDescription = when {
                    state.usbReady -> W.DisconnectDevice.text()
                    state.connectionActive -> W.CancelConnection.text()
                    else -> W.ConnectDevice.text()
                }
            }) {
            Box(contentAlignment = Alignment.Center) { PocketIcon(ShellIcon.LINK, PocketColors.onAccent, 22.dp) }
        }
    }
}

@Composable
private fun NowOnKeypad(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    val profileName = state.profiles.find { it.id == state.activeId }?.name ?: state.pluginTitle
    val pageName = state.pluginTitle.removePrefix("$profileName / ").takeIf { state.pluginTitle != profileName }
    val type = MaterialTheme.typography
    // Reserve all three text lines at the current font scale, including when optional lines are absent.
    val rowHeight = with(LocalDensity.current) {
        type.headlineMedium.lineHeight.toDp() + type.titleMedium.lineHeight.toDp() + type.bodyMedium.lineHeight.toDp() + 8.dp
    }.coerceAtLeast(52.dp)
    PocketCard {
        Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
            RoundPageButton(ShellIcon.CHEVRON_LEFT, W.PreviousPage.text()) { event(PocketScreenEvent.ChangePage(-1)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val accent = PocketStyle.accent(state.activeId)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PocketIconTile(accent, 28.dp)
                    Text(profileName, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = PocketColors.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!pageName.isNullOrBlank()) Text(pageName, color = accent.ink, style = type.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.activeId != PROFILE_HOME && state.reason.isNotBlank()) Text(state.reason, style = type.bodyMedium, color = PocketColors.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundPageButton(ShellIcon.CHEVRON_RIGHT, W.NextPage.text()) { event(PocketScreenEvent.ChangePage(1)) }
        }
    }
}

@Composable
private fun ProfilePicker(choices: List<ProfileChoice>, activeId: String, select: (String) -> Unit) {
    Box {
        val enabled = choices.any { it.id != activeId }
        var open by remember(enabled) { mutableStateOf(false) }
        PocketButton(H.Switch.text(), { open = true }, icon = ShellIcon.LAYERS, enabled = enabled)
        DropdownMenu(open, { open = false }, modifier = Modifier.widthIn(min = 224.dp, max = 360.dp)) {
            choices.forEach { profile ->
                if (profile.unavailableReason != null) UnavailableRow(profile.name, profile.unavailableReason)
                else DropdownMenuItem(text = { Text(profile.name) },
                    trailingIcon = if (profile.id == activeId) ({ PocketIcon(ShellIcon.CHECK, PocketColors.accent) }) else null,
                    modifier = Modifier.semantics { selected = profile.id == activeId }, onClick = {
                        open = false
                        if (profile.id != activeId) select(profile.id)
                    })
            }
        }
    }
}

@Composable
private fun RoundPageButton(icon: ShellIcon, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = PocketColors.control, border = BorderStroke(1.dp, PocketColors.cardStroke),
        modifier = Modifier.size(52.dp).semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) { PocketIcon(icon, PocketColors.text, 20.dp) }
    }
}

/** Profiles: the infinite carousel switches what is on the keypad; the editor below edits its layout. */
@Composable
fun SimulatorProfiles(state: PocketScreenState, select: (String) -> Unit, editor: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 720.dp
        Column(Modifier.widthIn(max = 1440.dp).fillMaxSize()) {
            WorkspaceTitle(W.Profiles.text())
            if (state.simulator == null) ProfileCarousel(state.profiles, state.activeId, select)
            else if (!wide || state.activeId == GAME_PROFILE) ProfilePicker(state.profiles, state.activeId, select)
            if (state.activeId != GAME_PROFILE) Box(Modifier.weight(1f).fillMaxWidth()) { editor() }
        }
    }
}

private const val CAROUSEL_LOOPS = 1000

/**
 * An endless, snapping profile strip. The centered card is sharp; the cards beside it are slightly
 * blurred and dimmed. Settling on a card, or tapping one, selects that profile.
 */
@Composable
internal fun ProfileCarousel(profiles: List<ProfileChoice>, activeId: String, select: (String) -> Unit) {
    if (profiles.isEmpty()) return
    val count = profiles.size
    val activeIndex = profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    val list = key(count) { rememberLazyListState(CAROUSEL_LOOPS / 2 * count + activeIndex) }
    val scope = rememberCoroutineScope()
    val currentActive by rememberUpdatedState(activeId)
    val currentSelect by rememberUpdatedState(select)
    val currentProfiles by rememberUpdatedState(profiles)
    val centered by remember(list) {
        derivedStateOf {
            val info = list.layoutInfo
            val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - middle) }?.index ?: list.firstVisibleItemIndex
        }
    }
    LaunchedEffect(list) {
        var moved = false
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling) moved = true
            else if (moved) {
                moved = false
                currentProfiles.getOrNull(centered % currentProfiles.size)?.let {
                    if (it.id != currentActive) currentSelect(it.id)
                }
            }
        }
    }
    // A switch made elsewhere (keypad page buttons, rules) brings that profile to the middle.
    LaunchedEffect(activeId, count) {
        val target = profiles.indexOfFirst { it.id == activeId }
        val current = centered
        if (target >= 0 && current % count != target && !list.isScrollInProgress) {
            var index = current - current % count + target
            if (index - current > count / 2) index -= count
            if (current - index > count / 2) index += count
            list.animateScrollToItem(index)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth * .42f).coerceIn(132.dp, 200.dp)
        val side = ((maxWidth - cardWidth) / 2).coerceAtLeast(0.dp)
        LazyRow(state = list, flingBehavior = rememberSnapFlingBehavior(list),
            contentPadding = PaddingValues(horizontal = side, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            items(count * CAROUSEL_LOOPS) { index ->
                val profile = profiles[index % count]
                val focused = index == centered
                val selected = profile.id == activeId
                val accent = PocketStyle.accent(profile.id)
                val fade by animateFloatAsState(if (focused) 1f else .55f)
                val scale by animateFloatAsState(if (focused) 1f else .9f)
                Surface(onClick = {
                    scope.launch { list.animateScrollToItem(index) }
                    if (!selected) select(profile.id)
                }, shape = RoundedCornerShape(16.dp),
                    color = if (selected && PocketColors.dark) Color(accent.tile) else PocketColors.card,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent.ink else PocketColors.cardStroke),
                    modifier = Modifier.width(cardWidth)
                        .graphicsLayer { alpha = fade; scaleX = scale; scaleY = scale }
                        .blur(if (focused) 0.dp else 2.dp, BlurredEdgeTreatment.Unbounded)
                        .semantics { this.selected = selected }) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PocketIconTile(accent, 40.dp)
                        Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) accent.ink else PocketColors.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit, openDiagnostics: () -> Unit) {
    Section(W.Settings.text()) {
        if (state.simulator?.supportsUsb != true) PocketButton(W.Setup.text(), { event(PocketScreenEvent.FeatureSetup) }, Modifier.fillMaxWidth(), ShellIcon.SETTINGS, PocketTone.ACCENT)
        LanguageSelector(state.language) { event(PocketScreenEvent.SetLanguage(it)) }
        PocketCard(W.Appearance.text(), if (PocketColors.dark) ShellIcon.MOON else ShellIcon.SUN, caps = true) {
            PocketDivider()
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val chosen = mode == state.themeMode
                    PocketButton(mode.label, { event(PocketScreenEvent.SetTheme(mode)) },
                        Modifier.weight(1f).semantics { selected = chosen; role = Role.RadioButton },
                        icon = if (chosen) ShellIcon.CHECK else null, tone = if (chosen) PocketTone.ACCENT else PocketTone.NEUTRAL)
                }

            }
            Note(W.SystemHint.text())
        }
        if (state.simulator != null) DesktopConnectionCard(state, event, showStatus = true)
        else PocketCard(W.Connection.text(), ShellIcon.LINK, caps = true) {
            PocketDivider()
            PocketLabel(W.Status.text())
            Text(state.status)
            PocketButton(if (state.connectionActive) W.DisconnectKeypad.text() else W.ConnectKeypad.text(),
                { event(PocketScreenEvent.ToggleConnection) }, Modifier.fillMaxWidth(), ShellIcon.SWAP,
                if (state.connectionActive) PocketTone.DANGER else PocketTone.NEUTRAL)
            PocketDivider()
            PocketSwitchRow(W.Auto.text(), W.AutoHint.text(),
                state.automatic) { event(PocketScreenEvent.SetAutomatic(it)) }
            PocketSwitchRow(W.Shortcuts.text(), W.ShortcutHint.text(),
                state.directShortcuts) { event(PocketScreenEvent.SetDirectShortcuts(it)) }
        }
        state.phoneCalls?.let { calls -> PocketCard(CallCopy.SettingsTitle.text(), ShellIcon.PHONE, caps = true) {
            PocketSwitchRow(CallCopy.ShowCalls.text(),
                if (calls.supported) CallCopy.SwitchHint.text()
                else CallCopy.RequiresTelephony.text(), calls.enabled, calls.supported) {
                event(PocketScreenEvent.SetPhoneCalls(it))
            }
            if (calls.enabled) {
                Note(CallCopy.PhonePermissionHint.text())
                if (!calls.phoneGranted) PocketButton(CallCopy.AllowPhoneState.text(), { event(PocketScreenEvent.AllowPhoneState) })
                PocketDivider()
                PocketSwitchRow(CallCopy.ShowDetails.text(),
                    if (calls.supportsDetails) CallCopy.DetailsHint.text()
                    else CallCopy.DetailsUnavailable.text(),
                    calls.details, calls.supportsDetails) { event(PocketScreenEvent.SetCallerDetails(it)) }
                if (calls.details) {
                    Note(CallCopy.NotificationHint.text())
                    PocketButton(if (calls.listenerGranted) CallCopy.ManageAccess.text() else CallCopy.AllowAccess.text(),
                        { event(PocketScreenEvent.CallerNotificationAccess) })
                }
            }
        } }
        PocketListRow(ShellIcon.DOCUMENT, W.Diagnostics.text(), W.DiagnosticsSummary.text(), openDiagnostics)
    }
}

@Composable
private fun DesktopConnectionCard(state: PocketScreenState, event: (PocketScreenEvent) -> Unit, showStatus: Boolean = false) {
    val desktop = state.simulator ?: return
    val virtual = state.virtualInput != null
    PocketCard(W.Connection.text(), ShellIcon.LINK) {
        if (showStatus && state.status.isNotBlank()) Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = PocketColors.muted, style = MaterialTheme.typography.bodyMedium)
        if (desktop.supportsUsb) {
            PocketButton(when {
                state.usbReady -> H.DisconnectUsb.text()
                state.connectionActive -> H.CancelUsb.text()
                else -> H.ConnectUsb.text()
            }, { event(PocketScreenEvent.ToggleConnection) }, icon = ShellIcon.LINK, enabled = !virtual,
                tone = if (!state.wiredActive) PocketTone.PRIMARY else PocketTone.NEUTRAL)
            if (virtual) Note(H.DisableVirtual.text())
            if (state.usbReady) PocketButton(H.Repaint.text(), { event(PocketScreenEvent.Repaint) }, icon = ShellIcon.REFRESH)
            PocketDivider()
        }
        PocketButton(if (virtual) W.DisconnectVirtual.text() else W.ConnectVirtual.text(), {
            event(if (virtual) PocketScreenEvent.StopPreview else PocketScreenEvent.EnterPreview)
        }, icon = ShellIcon.EYE, enabled = !state.wiredActive)
        if (state.wiredActive) Note(H.DisableUsb.text())
        else if (desktop.limitations.isNotBlank()) Note(desktop.limitations)
    }
}

@Composable
fun DiagnosticsScreen(state: PocketScreenState, onClose: () -> Unit, event: (PocketScreenEvent) -> Unit) {
    WorkspaceColumn {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            WorkspaceTitle(W.Diagnostics.text())
            if (state.simulator == null) PocketTextLink(W.BackToSettings.text(), onClose, ShellIcon.CHEVRON_LEFT)
            else PocketTextLink(W.Close.text(), onClose)
        }
        PocketInner {
            Text(listOf(state.pluginTitle, state.connectionLabel).filter { it.isNotBlank() }.joinToString("  |  "))
            if (state.status.isNotBlank()) Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (state.reason.isNotBlank()) Note(state.reason)
        }
        Note(W.DisplayProfile.text())
        if (state.simulator == null) PocketButton(W.Refresh.text(), { event(PocketScreenEvent.RefreshDiagnostics) },
            Modifier.fillMaxWidth(), ShellIcon.REFRESH, PocketTone.ACCENT)
        PocketLabel(W.Descriptor.text())
        PocketInner { SelectableReadout(state.diagnostics) }
        if (state.simulator == null) PocketButton(W.ExportLog.text(), { event(PocketScreenEvent.SaveLog) },
            Modifier.fillMaxWidth(), ShellIcon.DOWNLOAD)
        PocketLabel(W.Log.text())
        PocketInner { SelectableReadout(state.log.ifEmpty { W.NoEvents.text() }) }
    }
}

@Composable
private fun SelectableReadout(text: String) {
    BasicTextField(text, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = LocalContentColor.current))
}

@Composable
private fun LanguageSelector(selected: AppLanguage?, change: (AppLanguage?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val system = W.SystemLanguage.text()
    Box {
        PocketListRow(ShellIcon.SETTINGS, W.Language.text(), selected?.nativeName ?: system) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Column(Modifier.selectableGroup()) {
                (listOf(null) + AppLanguage.entries).forEach { option ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected == option, role = Role.RadioButton) {
                            expanded = false
                            if (selected != option) change(option)
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        RadioButton(selected == option, onClick = null)
                        Text(option?.nativeName ?: system)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    WorkspaceColumn(wide) {
        WorkspaceTitle(title)
        content()
    }
}

@Composable private fun Heading(text: String) = Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
@Composable private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
