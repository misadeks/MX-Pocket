package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The user's appearance choice; [SYSTEM] follows the platform's dark setting. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

private fun PocketPalette.scheme(): ColorScheme {
    val onContainer = if (dark) Color.White else Color(0xff0b1220)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent, onPrimary = onAccent, primaryContainer = accentContainer, onPrimaryContainer = Color.White,
        secondary = muted, secondaryContainer = navIndicator, onSecondaryContainer = onContainer,
        tertiary = success, background = background, onBackground = text, surface = background, onSurface = text,
        surfaceVariant = chip, onSurfaceVariant = muted, surfaceContainerLowest = inner, surfaceContainerLow = inner,
        surfaceContainer = card, surfaceContainerHigh = card, surfaceContainerHighest = control,
        outline = buttonStroke, outlineVariant = cardStroke,
        error = danger, errorContainer = dangerContainer, onErrorContainer = danger)
}

/** Resolves [mode] against the platform setting; true means the dark palette is in use. */
@Composable fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable fun PocketTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val palette = if (mode.isDark()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPocketPalette provides palette) {
        MaterialTheme(colorScheme = palette.scheme(), typography = PocketTypography) { Surface(content = content) }
    }
}

@Composable internal fun WorkspaceTitle(text: String) = Text(text, Modifier.semantics { heading() },
    color = PocketColors.title, style = MaterialTheme.typography.headlineSmall)

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
        WorkspaceTab.PROFILES -> ShellIcon.LAYERS
        WorkspaceTab.PLUGINS -> ShellIcon.PLUGINS
        WorkspaceTab.WORKFLOWS -> ShellIcon.FLOWS
        WorkspaceTab.SETTINGS -> ShellIcon.SETTINGS
    }
    PocketIcon(icon, LocalContentColor.current, 22.dp)
}
