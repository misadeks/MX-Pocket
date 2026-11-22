@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package tel.misa.mxpocket.shared.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.PluginFrame

enum class WorkspaceTab(val label: String) {
    HOME("Home"), PROFILES("Profiles"), PLUGINS("Plugins"), WORKFLOWS("Flows"), SETTINGS("Settings"),
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

/** Desktop input capabilities. A non-null grid authorizes simulation, never physical input. */
data class DesktopSimulator(val limitations: String, val grid: VirtualGrid? = null, val supportsUsb: Boolean = false)

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
}

/** Both hosts supply only state and intent handlers. The active content retains its composition on resize. */
@Composable
fun PocketWorkspace(
    tab: WorkspaceTab, select: (WorkspaceTab) -> Unit, state: PocketScreenState,
    plugins: List<PluginItem>, event: (PocketScreenEvent) -> Unit,
    editor: @Composable () -> Unit, workflows: @Composable () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val rail = maxWidth >= 840.dp
        Column {
            Row(Modifier.weight(1f)) {
                if (rail) NavigationRail(containerColor = PocketColors.nav) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        WorkspaceTab.entries.forEach { item ->
                            NavigationRailItem(item == tab, { select(item) }, icon = { TabIcon(item) },
                                label = { Text(item.label) }, colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = PocketColors.accent, selectedTextColor = PocketColors.accent,
                                    indicatorColor = PocketColors.navIndicator))
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    holder.SaveableStateProvider(tab.name) {
                        when (tab) {
                            WorkspaceTab.HOME -> HomeScreen(state, event)
                            WorkspaceTab.PROFILES -> ProfilesScreen(state, event, editor)
                            WorkspaceTab.PLUGINS -> if (state.simulator == null) PluginSettingsList(plugins, event)
                                else PluginsScreen(plugins, event)
                            WorkspaceTab.WORKFLOWS -> workflows()
                            WorkspaceTab.SETTINGS -> SettingsScreen(state, event)
                        }
                    }
                }
            }
            if (!rail) NavigationBar(windowInsets = WindowInsets(0), containerColor = PocketColors.nav, tonalElevation = 0.dp) {
                WorkspaceTab.entries.forEach { item ->
                    NavigationBarItem(item == tab, { select(item) }, icon = { TabIcon(item) },
                        label = { Text(item.label, maxLines = 1) }, colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PocketColors.accent, selectedTextColor = PocketColors.accent,
                            indicatorColor = PocketColors.navIndicator))
                }
            }
        }
    }
}

/**
 * Home mirrors the physical keypad: the nine tiles are the same LCD images, updated as they change.
 * Home switches layouts; Profiles is the workshop. Only an explicitly connected virtual keypad takes UI key input.
 */
@Composable
fun HomeScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    WorkspaceColumn(wide = true) {
        HomeHeader(state, event)
        Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = PocketColors.muted, style = MaterialTheme.typography.bodySmall)
        state.simulator?.takeUnless { it.supportsUsb }?.limitations?.takeIf { it.isNotBlank() }?.let { Note(it) }
        BoxWithConstraints {
            val split = maxWidth >= 720.dp
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
                        }, Modifier.widthIn(max = 480.dp).align(Alignment.CenterHorizontally), holds)
                    }
                }
            }
            if (split) Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(.85f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NowOnKeypad(state, event, showPicker = state.simulator != null)
                    if (state.simulator?.supportsUsb == true) DesktopConnectionCard(state, event)
                }
                Column(Modifier.weight(1.15f)) { keypad() }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NowOnKeypad(state, event, showPicker = state.simulator != null)
                if (state.simulator?.supportsUsb == true) DesktopConnectionCard(state, event)
                keypad()
            }
        }
    }
}

private data class ConnectionLook(val label: String, val color: Color)

private val PocketScreenState.wiredActive get() = usbReady || connectionActive
private val PocketScreenState.virtualInput get() = simulator?.grid?.takeUnless { wiredActive }

@Composable
private fun connectionLook(state: PocketScreenState) = when {
    state.usbReady -> ConnectionLook("USB connected", PocketColors.success)
    state.connectionActive -> ConnectionLook("Connecting…", PocketColors.warning)
    state.virtualInput != null -> ConnectionLook("Virtual keypad", PocketColors.accent)
    else -> ConnectionLook("Not connected", PocketColors.muted)
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
                    state.usbReady -> "Disconnect device"
                    state.connectionActive -> "Cancel connection"
                    else -> "Connect to device"
                }
            }) {
            Box(contentAlignment = Alignment.Center) { PocketIcon(ShellIcon.LINK, PocketColors.onAccent, 22.dp) }
        }
    }
}

@Composable
private fun NowOnKeypad(state: PocketScreenState, event: (PocketScreenEvent) -> Unit, showPicker: Boolean) {
    val profile = state.profiles.find { it.id == state.activeId }
    val profileName = profile?.name ?: state.pluginTitle
    val pageName = state.pluginTitle.removePrefix("$profileName / ").takeIf { state.pluginTitle != profileName }
    val type = MaterialTheme.typography
    // Reserve all three text lines at the current font scale, including when optional lines are absent.
    val rowHeight = with(LocalDensity.current) {
        type.headlineMedium.lineHeight.toDp() + type.titleMedium.lineHeight.toDp() + type.bodyMedium.lineHeight.toDp() + 8.dp
    }.coerceAtLeast(52.dp)
    PocketCard {
        Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
            RoundPageButton(ShellIcon.CHEVRON_LEFT, "Previous page") { event(PocketScreenEvent.ChangePage(-1)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(profileName, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold, color = PocketColors.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!pageName.isNullOrBlank()) Text(pageName, color = PocketColors.accent, style = type.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.reason.isNotBlank()) Text(state.reason, style = type.bodyMedium, color = PocketColors.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            RoundPageButton(ShellIcon.CHEVRON_RIGHT, "Next page") { event(PocketScreenEvent.ChangePage(1)) }
        }
        profile?.unavailableReason?.let { Note(it) }
        if (showPicker && state.profiles.size > 1) ProfilePicker(state, event)
    }
}

@Composable
private fun ProfilePicker(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        PocketButton("Switch layout", { open = true }, icon = ShellIcon.LAYERS)
        DropdownMenu(open, { open = false }, modifier = Modifier.widthIn(min = 224.dp, max = 360.dp)) {
            state.profiles.forEach { profile ->
                if (profile.unavailableReason != null) UnavailableRow(profile.name, profile.unavailableReason)
                else DropdownMenuItem(text = { Text(profile.name) },
                    trailingIcon = if (profile.id == state.activeId) ({ PocketIcon(ShellIcon.CHECK, PocketColors.accent) }) else null,
                    modifier = Modifier.semantics { selected = profile.id == state.activeId }, onClick = {
                        open = false
                        if (profile.id != state.activeId) event(PocketScreenEvent.SelectPlugin(profile.id))
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
private fun ProfilesScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit, editor: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val desktop = state.simulator != null
        val wide = maxWidth >= 720.dp
        val shell = if (desktop) Modifier.widthIn(max = 1440.dp) else Modifier
        Column(shell.fillMaxSize().align(Alignment.TopCenter)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 1152.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceTitle("Profiles")
                    if (!desktop) Row(Modifier.fillMaxWidth()) {
                        PocketLabel("On keypad", Modifier.weight(1f))
                        Text("Swipe to switch", style = MaterialTheme.typography.labelMedium, color = PocketColors.faint)
                    }
                    else if (!wide) ProfilePicker(state, event)
                }
            }
            // Wide workshops already have a profile library alongside the page settings.
            if (!desktop) ProfileCarousel(state.profiles, state.activeId) { event(PocketScreenEvent.SelectPlugin(it)) }
            Box(Modifier.weight(1f).fillMaxWidth()) { editor() }
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
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) currentProfiles.getOrNull(centered % currentProfiles.size)?.let {
                if (it.id != currentActive) currentSelect(it.id)
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
                    color = if (selected) PocketColors.accentSoft else PocketColors.card,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) PocketColors.accent else PocketColors.cardStroke),
                    modifier = Modifier.width(cardWidth)
                        .graphicsLayer { alpha = fade; scaleX = scale; scaleY = scale }
                        .blur(if (focused) 0.dp else 2.dp, BlurredEdgeTreatment.Unbounded)
                        .semantics { this.selected = selected }) {
                    Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PocketIconTile(accent, 40.dp)
                        Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) PocketColors.accent else PocketColors.title)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    WorkspaceColumn(wide = state.simulator != null) {
        WorkspaceTitle("Settings")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = state.simulator != null && maxWidth >= 840.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val preferences: @Composable () -> Unit = {
                if (state.simulator == null) PocketButton("Feature setup", { event(PocketScreenEvent.FeatureSetup) },
                    Modifier.fillMaxWidth(), ShellIcon.SETTINGS, PocketTone.ACCENT)
                AppearanceCard(state, event)
                if (state.simulator == null) AndroidConnectionCard(state, event) else DesktopConnectionCard(state, event, showStatus = true)
                if (state.simulator == null) PocketCard("Display", ShellIcon.MONITOR, caps = true) {
                    PocketDivider()
                    PocketListRow(ShellIcon.IMAGE, "Choose keypad background", "Image behind all nine keys for this session") { event(PocketScreenEvent.ChooseImage) }
                    PocketListRow(ShellIcon.REFRESH, "Repaint all keys", "Redraw all nine 118 × 118 LCD regions") { event(PocketScreenEvent.Repaint) }
                }
            }
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(.9f), verticalArrangement = Arrangement.spacedBy(12.dp)) { preferences() }
                Column(Modifier.weight(1.1f)) { DiagnosticsCard(state, event) }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                preferences()
                DiagnosticsCard(state, event)
            }
        }
    }
}

@Composable
private fun AppearanceCard(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    PocketCard("Appearance", if (PocketColors.dark) ShellIcon.MOON else ShellIcon.SUN, caps = true) {
        PocketDivider()
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                val chosen = mode == state.themeMode
                PocketButton(mode.label, { event(PocketScreenEvent.SetTheme(mode)) },
                    Modifier.weight(1f).semantics { selected = chosen; role = Role.RadioButton },
                    icon = if (chosen) ShellIcon.CHECK else null, tone = if (chosen) PocketTone.ACCENT else PocketTone.NEUTRAL)
            }
        }
        Note("System follows your device's dark theme setting.")
    }
}

@Composable
private fun AndroidConnectionCard(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    PocketCard("Connection", ShellIcon.LINK, caps = true) {
        PocketDivider()
        PocketLabel("Status")
        Text(state.status)
        PocketButton(if (state.connectionActive) "Disconnect keypad" else "Connect keypad",
            { event(PocketScreenEvent.ToggleConnection) }, Modifier.fillMaxWidth(), ShellIcon.SWAP,
            if (state.connectionActive) PocketTone.DANGER else PocketTone.NEUTRAL)
        PocketDivider()
        PocketSwitchRow("Connect automatically on USB attach", "Start a session when Android reports this keypad attached",
            state.automatic) { event(PocketScreenEvent.SetAutomatic(it)) }
        PocketSwitchRow("Open apps directly from keypad", "Needs Display over other apps; otherwise a notification is shown",
            state.directShortcuts) { event(PocketScreenEvent.SetDirectShortcuts(it)) }
    }
}

@Composable
private fun DesktopConnectionCard(state: PocketScreenState, event: (PocketScreenEvent) -> Unit, showStatus: Boolean = false) {
    val desktop = state.simulator ?: return
    val virtual = state.virtualInput != null
    PocketCard("Connection", ShellIcon.LINK) {
        if (showStatus && state.status.isNotBlank()) Text(state.status, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = PocketColors.muted, style = MaterialTheme.typography.bodyMedium)
        if (desktop.supportsUsb) {
            PocketButton(when {
                state.usbReady -> "Disconnect USB keypad"
                state.connectionActive -> "Cancel USB connection"
                else -> "Connect USB keypad"
            }, { event(PocketScreenEvent.ToggleConnection) }, icon = ShellIcon.LINK, enabled = !virtual,
                tone = if (!state.wiredActive) PocketTone.PRIMARY else PocketTone.NEUTRAL)
            if (virtual) Note("Disconnect the virtual keypad to use USB.")
            if (state.usbReady) PocketButton("Repaint LCD", { event(PocketScreenEvent.Repaint) }, icon = ShellIcon.REFRESH)
            PocketDivider()
        }
        PocketButton(if (virtual) "Disconnect virtual keypad" else "Connect virtual keypad", {
            event(if (virtual) PocketScreenEvent.StopPreview else PocketScreenEvent.EnterPreview)
        }, icon = ShellIcon.EYE, enabled = !state.wiredActive)
        if (state.wiredActive) Note("Disconnect USB to use the virtual keypad.")
        else if (desktop.limitations.isNotBlank()) Note(desktop.limitations)
    }
}

@Composable
private fun DiagnosticsCard(state: PocketScreenState, event: (PocketScreenEvent) -> Unit) {
    PocketCard("Diagnostics", ShellIcon.DOCUMENT, caps = true) {
        PocketDivider()
        PocketInner {
            Text(listOf(state.pluginTitle, state.connectionLabel).filter { it.isNotBlank() }.joinToString("  |  "))
            if (state.reason.isNotBlank()) Note(state.reason)
        }
        PocketInner { Text("Display profile: 480 x 480 panel; nine 118 x 118 visible regions. An upload acknowledgement does not verify LCD visibility.") }
        if (state.simulator == null) PocketButton("Refresh USB diagnostics", { event(PocketScreenEvent.RefreshDiagnostics) },
            Modifier.fillMaxWidth(), ShellIcon.REFRESH, PocketTone.ACCENT)
        PocketLabel("Device descriptor details")
        PocketInner { SelectableReadout(state.diagnostics) }
        if (state.simulator == null) PocketButton("Export session log", { event(PocketScreenEvent.SaveLog) },
            Modifier.fillMaxWidth(), ShellIcon.DOWNLOAD)
        PocketLabel("Session log")
        PocketInner { SelectableReadout(state.log.ifEmpty { "No events yet." }) }
    }
}

@Composable
private fun SelectableReadout(text: String) {
    BasicTextField(text, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = LocalContentColor.current))
}

@Composable private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
