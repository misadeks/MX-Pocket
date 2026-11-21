package tel.misa.mxpocket.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
import tel.misa.mxpocket.R

/** Dark-first palette from the Stitch redesign; matches res/values/colors.xml used by the Home Views. */
internal object PocketColors {
    val background = Color(0xff111215)
    val card = Color(0xff18191d)
    val cardStroke = Color(0xff272930)
    val inner = Color(0xff121316)
    val innerStroke = Color(0xff24252b)
    val chip = Color(0xff1b1c20)
    val control = Color(0xff1e2027)
    val buttonStroke = Color(0xff30323d)
    val nav = Color(0xff131418)
    val navIndicator = Color(0xff1e3a5f)
    val text = Color(0xffe5e5e5)
    val muted = Color(0xffa3a3a3)
    val faint = Color(0xff8a8a8a)
    val accent = Color(0xff60a5fa)
    val onAccent = Color(0xff0b1d3a)
    val accentContainer = Color(0xff194375)
    val accentSoft = Color(0xff121c2b)
    val accentStroke = Color(0xff1f3b5c)
    val mono = Color(0xff7dd3fc)
    val success = Color(0xff4ade80)
    val successContainer = Color(0xff13301f)
    val successStroke = Color(0xff1f5d36)
    val warning = Color(0xfffbbf24)
    val danger = Color(0xfffca5a5)
    val dangerContainer = Color(0xff35191c)
    val dangerStroke = Color(0xff522428)
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

@Composable internal fun PocketIcon(@DrawableRes id: Int, tint: Color = PocketColors.muted, size: Dp = 18.dp) {
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/** Rounded square holding a plugin/profile icon in its accent color. */
@Composable internal fun PocketIconTile(accent: PocketAccent, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(Color(accent.tile))
        .border(1.dp, Color(accent.tint).copy(alpha = .25f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center) {
        PocketIcon(accent.icon, Color(accent.tint), size * .5f)
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
    @DrawableRes icon: Int? = null,
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
                    else Text(title, heading, color = Color.White, style = MaterialTheme.typography.titleMedium)
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

internal enum class PocketTone(val fill: Color, val stroke: Color, val text: Color) {
    NEUTRAL(PocketColors.control, PocketColors.buttonStroke, PocketColors.text),
    ACCENT(PocketColors.accentSoft, PocketColors.accentStroke, PocketColors.accent),
    PRIMARY(PocketColors.accent, PocketColors.accent, PocketColors.onAccent),
    SUCCESS(PocketColors.successContainer, PocketColors.successStroke, PocketColors.success),
    DANGER(PocketColors.dangerContainer, PocketColors.dangerStroke, PocketColors.danger),
}

/** Compact rounded button with an optional leading icon; the touch target remains 48dp. */
@Composable internal fun PocketButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    tone: PocketTone = PocketTone.NEUTRAL,
    enabled: Boolean = true,
    @DrawableRes trailingIcon: Int? = null,
    compact: Boolean = false,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (enabled) tone.stroke else PocketColors.cardStroke),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = tone.fill, contentColor = tone.text,
            disabledContainerColor = PocketColors.chip, disabledContentColor = PocketColors.faint),
        contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 14.dp, vertical = 8.dp)) {
        icon?.let { PocketIcon(it, LocalContentColor.current, if (compact) 14.dp else 16.dp)
            Spacer(Modifier.width(if (compact) 4.dp else 8.dp)) }
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
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xff2563eb), checkedThumbColor = Color.White,
                uncheckedTrackColor = PocketColors.control, uncheckedThumbColor = PocketColors.muted,
                uncheckedBorderColor = PocketColors.buttonStroke))
    }
}

/** Tappable settings row: icon tile, title, explanation and a chevron. */
@Composable internal fun PocketListRow(@DrawableRes icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
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
            PocketIcon(R.drawable.ic_pocket_chevron_right, PocketColors.faint)
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

@Composable internal fun PocketTextLink(text: String, onClick: () -> Unit, @DrawableRes icon: Int? = null) {
    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick, role = Role.Button)
        .padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { PocketIcon(it, PocketColors.accent, 14.dp); Spacer(Modifier.width(4.dp)) }
        Text(text, color = PocketColors.accent, style = MaterialTheme.typography.labelLarge)
    }
}
