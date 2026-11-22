package tel.misa.mxpocket.shared.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import tel.misa.mxpocket.plugins.*

val LocalIntegrationIcons = staticCompositionLocalOf<Map<IntegrationApp, ImageBitmap>> { emptyMap() }

/**
 * Visual identity of a profile or plugin in the workspace: its icon, the filled chip color,
 * the dark icon-tile background and the bright icon color. Unknown/custom IDs use a neutral style.
 */
internal data class PocketAccent(val icon: ShellIcon, val fill: Int, val tile: Int, val tint: Int, val keyGlyph: KeyGlyph)

internal object PocketStyle {
    private val neutral = PocketAccent(ShellIcon.LAYERS, 0xff2d323c.toInt(), 0xff1f2229.toInt(), 0xff94a3b8.toInt(), DeckGlyph.PROFILE)
    private val accents = mapOf(
        "focus" to PocketAccent(ShellIcon.CLOCK, 0xff194375.toInt(), 0xff16283f.toInt(), 0xff60a5fa.toInt(), DeckGlyph.TIMER),
        "audio" to PocketAccent(ShellIcon.MUSIC, 0xff274740.toInt(), 0xff13302a.toInt(), 0xff34d399.toInt(), PlayerGlyph.TRACK),
        "phone" to PocketAccent(ShellIcon.PHONE, 0xff49345f.toInt(), 0xff2a1f3a.toInt(), 0xffc084fc.toInt(), DeckGlyph.PHONE),
        "launcher" to PocketAccent(ShellIcon.LAUNCHER, 0xff5c4718.toInt(), 0xff3a2a12.toInt(), 0xfffbbf24.toInt(), DeckGlyph.GRID),
        "studio" to PocketAccent(ShellIcon.STUDIO, 0xff5b2440.toInt(), 0xff3a1a2c.toInt(), 0xfff472b6.toInt(), DeckGlyph.CAMERA),
        "navigation" to PocketAccent(ShellIcon.NAVIGATION, 0xff134e4a.toInt(), 0xff0f2f2e.toInt(), 0xff2dd4bf.toInt(), DeckGlyph.COMPASS),
        "game.xo" to PocketAccent(ShellIcon.GAME, 0xff793c21.toInt(), 0xff392318.toInt(), 0xfffb923c.toInt(), DeckGlyph.GAME),
        "teams" to PocketAccent(ShellIcon.CHAT, 0xff393968.toInt(), 0xff23233f.toInt(), 0xffa5b4fc.toInt(), IntegrationApp.TEAMS),
        "onenote" to PocketAccent(ShellIcon.NOTEBOOK, 0xff77196f.toInt(), 0xff381831.toInt(), 0xffed8ce0.toInt(), IntegrationApp.ONENOTE),
    )

    fun accent(id: String): PocketAccent = accents[id] ?: neutral
}
