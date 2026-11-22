package tel.misa.mxpocket.shared.ui

import tel.misa.mxpocket.i18n.WorkspaceCopy as W

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The user's appearance choice; [SYSTEM] follows the platform's dark setting. */
enum class ThemeMode(private val copy: tel.misa.mxpocket.i18n.Copy) { SYSTEM(W.System), LIGHT(W.Light), DARK(W.Dark);
    val label get() = copy.text()
}

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

/**
 * Page spacing for the navigation-rail layout. Every tab places its title [gutter] from the rail and from the
 * window top, and starts its content [titleGap] below the title. Both follow the window width at a fixed ratio.
 * Null in the compact layout, which keeps its own spacing.
 */
internal class PageFrame(val gutter: Dp) {
    val titleGap: Dp get() = gutter * TITLE_GAP_RATIO

    companion object {
        private const val GUTTER_RATIO = .025f
        private const val TITLE_GAP_RATIO = .5f
        fun of(contentWidth: Dp) = PageFrame((contentWidth * GUTTER_RATIO).coerceIn(16.dp, 64.dp))
    }
}

internal val LocalPageFrame = compositionLocalOf<PageFrame?> { null }

private val ContentSpacing = 12.dp

/** A framed page starts at the rail's side; otherwise the column is centered as before. */
internal fun PageFrame?.alignment() = if (this == null) Alignment.TopCenter else Alignment.TopStart

@Composable internal fun WorkspaceColumn(
    wide: Boolean = false,
    title: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val frame = LocalPageFrame.current
    Box(Modifier.fillMaxSize(), contentAlignment = frame.alignment()) {
        val padding = if (frame == null) PaddingValues(horizontal = 16.dp, vertical = 12.dp) else PaddingValues(frame.gutter)
        Column(Modifier.widthIn(max = if (wide) 1152.dp else 800.dp).fillMaxWidth()
            .verticalScroll(rememberScrollState()).imePadding().padding(padding)) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(ContentSpacing), content = title)
                Spacer(Modifier.height(frame?.titleGap ?: ContentSpacing))
            }
            Column(verticalArrangement = Arrangement.spacedBy(ContentSpacing), content = content)
        }
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
