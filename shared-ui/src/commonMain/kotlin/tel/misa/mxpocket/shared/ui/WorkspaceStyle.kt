package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val graphite = darkColorScheme(
    primary = PocketColors.accent, onPrimary = PocketColors.onAccent,
    primaryContainer = PocketColors.accentContainer, onPrimaryContainer = Color.White,
    secondary = PocketColors.muted, secondaryContainer = PocketColors.navIndicator, onSecondaryContainer = Color.White,
    tertiary = PocketColors.success, background = PocketColors.background, onBackground = PocketColors.text,
    surface = PocketColors.background, onSurface = PocketColors.text,
    surfaceVariant = PocketColors.chip, onSurfaceVariant = PocketColors.muted,
    surfaceContainerLowest = PocketColors.inner, surfaceContainerLow = PocketColors.inner,
    surfaceContainer = PocketColors.card, surfaceContainerHigh = PocketColors.card,
    surfaceContainerHighest = PocketColors.control, outline = PocketColors.buttonStroke, outlineVariant = PocketColors.cardStroke,
    error = Color(0xfff87171), errorContainer = PocketColors.dangerContainer, onErrorContainer = PocketColors.danger)

@Composable fun PocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = graphite, typography = PocketTypography) { Surface(content = content) }
}

@Composable internal fun WorkspaceTitle(text: String) = Text(text, Modifier.semantics { heading() },
    color = Color.White, style = MaterialTheme.typography.headlineSmall)

@Composable internal fun WorkspaceColumn(wide: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = if (wide) 1152.dp else 800.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable internal fun TabIcon(tab: WorkspaceTab) {
    val icon = when (tab) {
        WorkspaceTab.HOME -> ShellIcon.HOME
        WorkspaceTab.KEYPAD -> ShellIcon.KEYPAD
        WorkspaceTab.PLUGINS -> ShellIcon.PLUGINS
        WorkspaceTab.WORKFLOWS -> ShellIcon.FLOWS
        WorkspaceTab.SETTINGS -> ShellIcon.SETTINGS
    }
    PocketIcon(icon, LocalContentColor.current, 22.dp)
}
