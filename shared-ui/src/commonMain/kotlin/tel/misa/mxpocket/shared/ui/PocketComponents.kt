package tel.misa.mxpocket.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tel.misa.mxpocket.plugins.IntegrationApp

/** One workspace palette. Dark uses graphite greys with an orange accent; light uses white and pale grey with blue. */
@Immutable
internal data class PocketPalette(
    val dark: Boolean,
    val background: Color, val card: Color, val cardStroke: Color, val inner: Color, val innerStroke: Color,
    val chip: Color, val control: Color, val buttonStroke: Color, val nav: Color, val navIndicator: Color,
    val title: Color, val text: Color, val muted: Color, val faint: Color,
    val accent: Color, val onAccent: Color, val accentContainer: Color, val accentSoft: Color, val accentStroke: Color,
    val mono: Color, val success: Color, val successContainer: Color, val successStroke: Color,
    val warning: Color, val danger: Color, val dangerContainer: Color, val dangerStroke: Color,
)

internal val DarkPalette = PocketPalette(dark = true,
    background = Color(0xff111214), card = Color(0xff1a1b1f), cardStroke = Color(0xff2a2c31),
    inner = Color(0xff141518), innerStroke = Color(0xff26282d), chip = Color(0xff1d1f23), control = Color(0xff222429),
    buttonStroke = Color(0xff33363c), nav = Color(0xff141518), navIndicator = Color(0xff3a2414),
    title = Color(0xffffffff), text = Color(0xffe5e7eb), muted = Color(0xff9ca3af), faint = Color(0xff6b7280),
    accent = Color(0xfff97316), onAccent = Color(0xff1a0c02), accentContainer = Color(0xff7c2d12),
    accentSoft = Color(0xff2a1a10), accentStroke = Color(0xff6b3416), mono = Color(0xfffdba74),
    success = Color(0xff4ade80), successContainer = Color(0xff13301f), successStroke = Color(0xff1f5d36),
    warning = Color(0xfffbbf24), danger = Color(0xfffca5a5), dangerContainer = Color(0xff35191c), dangerStroke = Color(0xff522428))

internal val LightPalette = PocketPalette(dark = false,
    background = Color(0xfff3f4f6), card = Color(0xffffffff), cardStroke = Color(0xffe5e7eb),
    inner = Color(0xfff8fafc), innerStroke = Color(0xffe5e7eb), chip = Color(0xfff3f4f6), control = Color(0xffffffff),
    buttonStroke = Color(0xffd1d5db), nav = Color(0xffffffff), navIndicator = Color(0xffdbeafe),
    title = Color(0xff0b1220), text = Color(0xff111827), muted = Color(0xff6b7280), faint = Color(0xff9ca3af),
    accent = Color(0xff2563eb), onAccent = Color(0xffffffff), accentContainer = Color(0xff1d4ed8),
    accentSoft = Color(0xffeff6ff), accentStroke = Color(0xffbfdbfe), mono = Color(0xff1d4ed8),
    success = Color(0xff15803d), successContainer = Color(0xffdcfce7), successStroke = Color(0xff86efac),
    warning = Color(0xffb45309), danger = Color(0xffdc2626), dangerContainer = Color(0xfffef2f2), dangerStroke = Color(0xfffecaca))

internal val LocalPocketPalette = staticCompositionLocalOf { DarkPalette }

/** Theme-aware colors for the workspace; read inside composition only. */
internal object PocketColors {
    private val p: PocketPalette @Composable @ReadOnlyComposable get() = LocalPocketPalette.current
    val dark: Boolean @Composable @ReadOnlyComposable get() = p.dark
    val background: Color @Composable @ReadOnlyComposable get() = p.background
    val card: Color @Composable @ReadOnlyComposable get() = p.card
    val cardStroke: Color @Composable @ReadOnlyComposable get() = p.cardStroke
    val inner: Color @Composable @ReadOnlyComposable get() = p.inner
    val innerStroke: Color @Composable @ReadOnlyComposable get() = p.innerStroke
    val chip: Color @Composable @ReadOnlyComposable get() = p.chip
    val control: Color @Composable @ReadOnlyComposable get() = p.control
    val buttonStroke: Color @Composable @ReadOnlyComposable get() = p.buttonStroke
    val nav: Color @Composable @ReadOnlyComposable get() = p.nav
    val navIndicator: Color @Composable @ReadOnlyComposable get() = p.navIndicator
    val title: Color @Composable @ReadOnlyComposable get() = p.title
    val text: Color @Composable @ReadOnlyComposable get() = p.text
    val muted: Color @Composable @ReadOnlyComposable get() = p.muted
    val faint: Color @Composable @ReadOnlyComposable get() = p.faint
    val accent: Color @Composable @ReadOnlyComposable get() = p.accent
    val onAccent: Color @Composable @ReadOnlyComposable get() = p.onAccent
    val accentContainer: Color @Composable @ReadOnlyComposable get() = p.accentContainer
    val accentSoft: Color @Composable @ReadOnlyComposable get() = p.accentSoft
    val accentStroke: Color @Composable @ReadOnlyComposable get() = p.accentStroke
    val mono: Color @Composable @ReadOnlyComposable get() = p.mono
    val success: Color @Composable @ReadOnlyComposable get() = p.success
    val successContainer: Color @Composable @ReadOnlyComposable get() = p.successContainer
    val successStroke: Color @Composable @ReadOnlyComposable get() = p.successStroke
    val warning: Color @Composable @ReadOnlyComposable get() = p.warning
    val danger: Color @Composable @ReadOnlyComposable get() = p.danger
    val dangerContainer: Color @Composable @ReadOnlyComposable get() = p.dangerContainer
    val dangerStroke: Color @Composable @ReadOnlyComposable get() = p.dangerStroke
}

/** Compact type scale used by the Stitch screens; touch targets stay at least 48dp. */
internal val PocketTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 14.sp),
    )
}

internal val PocketMono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)

@Composable internal fun PocketIcon(id: ShellIcon, tint: Color = PocketColors.muted, size: Dp = 18.dp) {
    ShellSymbol(id, tint, size)
}

internal val PocketAccent.ink: Color @Composable get() = Color(if (PocketColors.dark) tint else fill)

@Composable private fun IdentityIcon(accent: PocketAccent, size: Dp) {
    val image = (accent.keyGlyph as? IntegrationApp)?.let { LocalIntegrationIcons.current[it] }
    if (image != null) Image(image, null, Modifier.size(size))
    else PocketIcon(accent.icon, accent.ink, size)
}

@Composable internal fun PocketIconTile(accent: PocketAccent, size: Dp = 44.dp) {
    val tile = if (PocketColors.dark) Color(accent.tile) else Color(accent.tint).copy(alpha = .12f)
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(tile)
        .border(1.dp, Color(accent.tint).copy(alpha = .25f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        IdentityIcon(accent, size * .62f)
    }
}

/** Uppercase section label, e.g. "PROFILES" or "SESSION LOG". */
@Composable internal fun PocketLabel(text: String, modifier: Modifier = Modifier, color: Color = PocketColors.muted) {
    Text(text.uppercase(), modifier, color = color, style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

/** Rounded, outlined surface shared by the workspace tabs. */
@Composable internal fun PocketCard(
    title: String? = null,
    icon: ShellIcon? = null,
    caps: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = PocketColors.card, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, PocketColors.cardStroke), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || trailing != null) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                icon?.let { PocketIcon(it, PocketColors.accent) }
                if (title != null) {
                    val heading = Modifier.weight(1f).semantics { heading() }
                    if (caps) Text(title.uppercase(), heading, color = PocketColors.muted,
                        style = MaterialTheme.typography.titleSmall, letterSpacing = 1.sp)
                    else Text(title, heading, color = PocketColors.title, style = MaterialTheme.typography.titleMedium)
                } else Spacer(Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

/** Recessed panel for monospace or secondary detail inside a [PocketCard]. */
@Composable internal fun PocketInner(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = PocketColors.inner, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, PocketColors.innerStroke), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable internal fun PocketDivider() = HorizontalDivider(color = PocketColors.cardStroke)

internal enum class PocketTone {
    NEUTRAL, ACCENT, PRIMARY, SUCCESS, DANGER;

    val fill: Color @Composable @ReadOnlyComposable get() = when (this) {
        NEUTRAL -> PocketColors.control
        ACCENT -> PocketColors.accentSoft
        PRIMARY -> PocketColors.accent
        SUCCESS -> PocketColors.successContainer
        DANGER -> PocketColors.dangerContainer
    }
    val stroke: Color @Composable @ReadOnlyComposable get() = when (this) {
        NEUTRAL -> PocketColors.buttonStroke
        ACCENT -> PocketColors.accentStroke
        PRIMARY -> PocketColors.accent
        SUCCESS -> PocketColors.successStroke
        DANGER -> PocketColors.dangerStroke
    }
    val text: Color @Composable @ReadOnlyComposable get() = when (this) {
        NEUTRAL -> PocketColors.text
        ACCENT -> PocketColors.accent
        PRIMARY -> PocketColors.onAccent
        SUCCESS -> PocketColors.success
        DANGER -> PocketColors.danger
    }
}

/** Compact rounded button with an optional leading icon; the touch target remains 48dp. */
@Composable internal fun PocketButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ShellIcon? = null,
    tone: PocketTone = PocketTone.NEUTRAL,
    enabled: Boolean = true,
    trailingIcon: ShellIcon? = null,
    compact: Boolean = false,
    identity: PocketAccent? = null,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (enabled) tone.stroke else PocketColors.cardStroke),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = tone.fill, contentColor = tone.text,
            disabledContainerColor = PocketColors.chip, disabledContentColor = PocketColors.faint),
        contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 14.dp, vertical = 8.dp)) {
        if (identity != null || icon != null) {
            val side = if (compact) 14.dp else 18.dp
            if (identity != null) IdentityIcon(identity, side) else icon?.let { PocketIcon(it, LocalContentColor.current, side) }
            Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
        trailingIcon?.let { Spacer(Modifier.width(6.dp)); PocketIcon(it, LocalContentColor.current, 16.dp) }
    }
}

/** Switch row with an optional explanation under the title. */
@Composable internal fun PocketSwitchRow(title: String, subtitle: String? = null, checked: Boolean,
    enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PocketColors.text, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, color = PocketColors.muted, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = PocketColors.accent, checkedThumbColor = PocketColors.onAccent,
                uncheckedTrackColor = PocketColors.control, uncheckedThumbColor = PocketColors.muted,
                uncheckedBorderColor = PocketColors.buttonStroke))
    }
}

/** Tappable settings row: icon tile, title, explanation and a chevron. */
@Composable internal fun PocketListRow(icon: ShellIcon, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = PocketColors.inner, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, PocketColors.innerStroke), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(PocketColors.control),
                contentAlignment = Alignment.Center) { PocketIcon(icon, PocketColors.muted) }
            Column(Modifier.weight(1f)) {
                Text(title, color = PocketColors.text, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, color = PocketColors.muted, style = MaterialTheme.typography.bodySmall)
            }
            PocketIcon(ShellIcon.CHEVRON_RIGHT, PocketColors.faint)
        }
    }
}

/** Small monospace tag such as a version, trigger or rule number. */
@Composable internal fun PocketTag(text: String, color: Color = PocketColors.muted) {
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(PocketColors.control)
        .border(1.dp, PocketColors.buttonStroke, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = color, style = PocketMono.copy(fontSize = 11.sp, lineHeight = 14.sp))
}

/** Status dot that always sits next to words describing the same state. */
@Composable internal fun PocketDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable internal fun PocketTextLink(text: String, onClick: () -> Unit, icon: ShellIcon? = null) {
    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick, role = Role.Button)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { PocketIcon(it, PocketColors.accent, 14.dp); Spacer(Modifier.width(4.dp)) }
        Text(text, color = PocketColors.accent, style = MaterialTheme.typography.labelLarge)
    }
}
