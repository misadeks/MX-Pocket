package tel.misa.mxpocket.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tel.misa.mxpocket.PocketSession
import tel.misa.mxpocket.R
import tel.misa.mxpocket.platformBindings
import tel.misa.mxpocket.pluginDetails
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.workflow.WorkflowScreen

internal enum class WorkspaceTab(val label: String, @DrawableRes val icon: Int) {
    HOME("Home", R.drawable.ic_pocket_home), KEYPAD("Keypad", R.drawable.ic_pocket_keypad),
    PLUGINS("Plugins", R.drawable.ic_pocket_plugins), WORKFLOWS("Flows", R.drawable.ic_pocket_flows),
    SETTINGS("Settings", R.drawable.ic_pocket_settings)
}

@Composable internal fun WorkspaceTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = PocketColors.accent, onPrimary = PocketColors.onAccent,
        primaryContainer = PocketColors.accentContainer, onPrimaryContainer = Color.White,
        secondary = PocketColors.muted, secondaryContainer = PocketColors.navIndicator, onSecondaryContainer = Color.White,
        tertiary = PocketColors.success,
        background = PocketColors.background, onBackground = PocketColors.text,
        surface = PocketColors.background, onSurface = PocketColors.text,
        surfaceVariant = PocketColors.chip, onSurfaceVariant = PocketColors.muted,
        surfaceContainerLowest = PocketColors.inner, surfaceContainerLow = PocketColors.inner,
        surfaceContainer = PocketColors.card, surfaceContainerHigh = PocketColors.card,
        surfaceContainerHighest = PocketColors.control,
        outline = PocketColors.buttonStroke, outlineVariant = PocketColors.cardStroke,
        error = Color(0xfff87171), errorContainer = PocketColors.dangerContainer, onErrorContainer = PocketColors.danger,
    )
    MaterialTheme(colorScheme = colors, typography = PocketTypography) { Surface(content = content) }
}

@Composable internal fun WorkspaceNav(tab: WorkspaceTab, select: (WorkspaceTab) -> Unit) {
    Column {
        PocketDivider()
        NavigationBar(containerColor = PocketColors.nav, tonalElevation = 0.dp) {
            WorkspaceTab.entries.forEach {
                NavigationBarItem(selected = it == tab, onClick = { select(it) },
                    icon = { PocketIcon(it.icon, LocalContentColor.current, 22.dp) },
                    label = { Text(it.label, maxLines = 1, style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (it == tab) FontWeight.SemiBold else FontWeight.Normal) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = PocketColors.accent, selectedTextColor = PocketColors.accent,
                        indicatorColor = PocketColors.navIndicator,
                        unselectedIconColor = PocketColors.muted, unselectedTextColor = PocketColors.muted))
            }
        }
    }
}

@Composable internal fun WorkspacePanel(tab: WorkspaceTab, session: PocketSession, state: PocketScreenState?,
    event: (PocketScreenEvent) -> Unit, onSetup: () -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    if (tab == WorkspaceTab.HOME || state == null) return
    val deck by session.deck.state.collectAsStateWithLifecycle()
    val signals by session.signals.collectAsStateWithLifecycle()
    val flows by session.workflows.definitions.collectAsStateWithLifecycle()
    val disabled by session.disabledPlugins.collectAsStateWithLifecycle()
    val options = buildList {
        add(BindingOption("Back", ActionCall(DeckActions.BACK)))
        add(BindingOption("Return to root", ActionCall(DeckActions.ROOT)))
        deck.profiles.flatMap { it.pages }.forEach { add(BindingOption("Page: ${it.name}",ActionCall(DeckActions.PAGE,mapOf("pageId" to it.id)))) }
        deck.profiles.forEach { add(BindingOption("Profile: ${it.name}",ActionCall(DeckActions.PROFILE,mapOf("profileId" to it.id)))) }
        flows.forEach { add(BindingOption("Workflow: ${it.name}",ActionCall("workflow.run",mapOf("workflowId" to it.id)))) }
        add(BindingOption("Play a sound",ActionCall("sound.play")))
        session.platformBindings().forEach { (label,call) -> add(BindingOption(label,call)) }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        when (tab) {
            WorkspaceTab.KEYPAD -> LayoutEditor(session.deck, options, signals, onExport, onImport, state.frame.keys)
            WorkspaceTab.WORKFLOWS -> WorkflowScreen(session.workflows)
            WorkspaceTab.PLUGINS -> Section("Plugins", "Enable the controls you use. Android access is configured separately.") {
                for (plugin in session.plugins.descriptors) {
                    val accent = PocketStyle.accent(plugin.id)
                    val enabled = plugin.id !in disabled
                    PocketCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PocketIconTile(accent)
                            Column(Modifier.weight(1f)) {
                                Text(plugin.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                                Text("Capabilities: " + plugin.capabilities.joinToString { it.name.lowercase().replace('_',' ') }
                                    .ifEmpty { "none" }, color = PocketColors.faint, style = PocketMono)
                            }
                            Switch(enabled, { session.setPluginEnabled(plugin.id, it) },
                                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xff2563eb), checkedThumbColor = Color.White,
                                    uncheckedTrackColor = PocketColors.control, uncheckedThumbColor = PocketColors.muted,
                                    uncheckedBorderColor = PocketColors.buttonStroke))
                        }
                        Text(session.pluginDetails(plugin.id), color = PocketColors.text, style = MaterialTheme.typography.bodyLarge)
                        val isProfile = deck.profiles.any { it.id == plugin.id }
                        val settings = session.pluginSettings(plugin.id)
                        if (isProfile || settings != null) {
                            PocketDivider()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                settings?.let { (label, open) -> PocketButton(label, open, enabled = enabled) }
                                Spacer(Modifier.weight(1f))
                                if (isProfile) {
                                    if (deck.defaultProfileId == plugin.id)
                                        PocketButton("Default profile", {}, icon = R.drawable.ic_pocket_star, tone = PocketTone.ACCENT)
                                    else PocketTextLink("Make default profile", {
                                        session.deck.setDefault(plugin.id).onFailure { session.fail("Cannot change default",it) }
                                    })
                                }
                            }
                        }
                    }
                }
            }
            WorkspaceTab.SETTINGS -> Section("Settings") {
                PocketButton("Feature setup", onSetup, Modifier.fillMaxWidth(), icon = R.drawable.ic_pocket_settings,
                    tone = PocketTone.ACCENT)
                PocketCard("Connection", R.drawable.ic_pocket_link, caps = true) {
                    PocketDivider()
                    PocketLabel("Status")
                    Text(state.status, color = Color.White, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium)
                    if (state.connectionActive) PocketButton("Disconnect keypad", { event(PocketScreenEvent.ToggleConnection) },
                        Modifier.fillMaxWidth(), icon = R.drawable.ic_pocket_swap, tone = PocketTone.DANGER)
                    else PocketButton("Connect keypad", { event(PocketScreenEvent.ToggleConnection) },
                        Modifier.fillMaxWidth(), icon = R.drawable.ic_pocket_swap)
                    PocketDivider()
                    PocketSwitchRow("Connect automatically on USB attach",
                        "Start a session when Android reports this keypad attached", state.automatic) {
                        event(PocketScreenEvent.SetAutomatic(it)) }
                    PocketSwitchRow("Open apps directly from keypad",
                        "Needs Display over other apps; otherwise a notification is shown", state.directShortcuts) {
                        event(PocketScreenEvent.SetDirectShortcuts(it)) }
                }
                PocketCard("Display", R.drawable.ic_pocket_monitor, caps = true) {
                    PocketDivider()
                    PocketListRow(R.drawable.ic_pocket_image, "Choose keypad background",
                        "Image behind all nine keys for this session") { event(PocketScreenEvent.ChooseImage) }
                    PocketListRow(R.drawable.ic_pocket_refresh, "Repaint all keys",
                        "Redraw all nine 118 x 118 LCD regions") { event(PocketScreenEvent.Repaint) }
                }
                PocketCard("Diagnostics", R.drawable.ic_pocket_document, caps = true) {
                    PocketDivider()
                    PocketInner {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${deck.activeProfile.name} / ${deck.activePage.name}  |  ${session.connectionLabel}",
                                Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold)
                            PocketDot(if (state.connectionActive || state.simulating) PocketColors.success else PocketColors.faint)
                        }
                        if (state.reason.isNotBlank()) Text(state.reason, color = PocketColors.muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    PocketInner {
                        Text(buildAnnotatedString {
                            withStyle(SpanStyle(color = PocketColors.accent, fontWeight = FontWeight.SemiBold)) {
                                append("Display profile: ") }
                            append("480 x 480 panel; nine 118 x 118 visible regions. An upload acknowledgement does not verify LCD visibility.")
                        }, color = PocketColors.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    PocketButton("Refresh USB diagnostics", { event(PocketScreenEvent.RefreshDiagnostics) },
                        Modifier.fillMaxWidth(), icon = R.drawable.ic_pocket_refresh, tone = PocketTone.ACCENT)
                    PocketLabel("Device descriptor details")
                    PocketInner { SelectionContainer { Text(state.diagnostics.ifEmpty { "No USB devices reported." },
                        color = PocketColors.mono, style = PocketMono) } }
                    PocketButton("Export session log", { event(PocketScreenEvent.SaveLog) },
                        Modifier.fillMaxWidth(), icon = R.drawable.ic_pocket_download)
                    PocketLabel("Session log")
                    PocketInner { SelectionContainer { Text(state.log.ifEmpty { "No events yet." },
                        color = PocketColors.text, style = PocketMono) } }
                }
            }
            WorkspaceTab.HOME -> Unit
        }
    }
}

@Composable private fun Section(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        note?.let { Text(it, color = PocketColors.muted, style = MaterialTheme.typography.bodyLarge) }
        content()
    }
}
