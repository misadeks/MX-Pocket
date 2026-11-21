package tel.misa.mxpocket.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.DeckState

enum class PluginCapability { AUDIO, OPEN_APPS, NAVIGATION, TONE, STUDIO, SYSTEM_NAVIGATION }

/** Two distinct logical plugin slots in ascending order (not press order). */
data class KeyChord(val first: Int, val second: Int) {
    init { require(first in 0..7 && second in first + 1..8) { "Use two distinct LCD indices in ascending order" } }
    internal fun partner(index: Int) = when (index) { first -> second; second -> first; else -> null }
}

data class PluginDescriptor(
    val id: String,
    val title: String,
    val capabilities: Set<PluginCapability> = emptySet(),
    val chords: Set<KeyChord> = emptySet(),
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9.-]{2,63}"))) { "Use a stable lowercase plugin ID" }
        require(title.isNotBlank()) { "Plugin title is required" }
    }
}

sealed interface KeyGlyph
enum class PlayerGlyph : KeyGlyph {
    VOLUME_DOWN, MUTE, UNMUTE, VOLUME_UP, PREVIOUS, PLAY, PAUSE, PLAY_PAUSE, NEXT, BACK_10, TRACK, FORWARD_10
}

data class VolumeLevel(val current: Int, val maximum: Int, val muted: Boolean) {
    val fraction: Float? get() = if (maximum <= 0) null else if (muted) 0f else current.coerceIn(0, maximum).toFloat() / maximum
    val description: String get() = if (maximum > 0) "$current / $maximum" else "Volume unknown"
}

data class KeyTile(val label: String, val footer: String, val background: Int,
    val iconComponent: String? = null, val inputToken: String? = null,
    val icon: String = "", val enabled: Boolean = true, val glyph: KeyGlyph? = null,
    val readOnly: Boolean = false, val showLabel: Boolean = true, val showFooter: Boolean = true,
    val primary: Boolean = false, val volumeLevel: VolumeLevel? = null)

data class PluginFrame(val keys: List<KeyTile>, val page: Int = 0) {
    init { require(keys.size == 9) { "A plugin frame must contain exactly nine keys" } }
}

/** LCD indices are 0..8. The host reserves the two physical page buttons for navigation. */
data class PluginKeyEvent(val index: Int, val pressed: Boolean, val synthetic: Boolean = false) {
    init { require(index in 0..8) }
}

data class AudioSnapshot(val volume: Int, val maximum: Int)
enum class VolumeChange { LOWER, RAISE, MUTE, UNMUTE }
enum class MediaCommand { PLAY_PAUSE, PREVIOUS, NEXT }
enum class PhoneApp { CAMERA, SETTINGS, DIALER, WEB, ALARMS }

sealed interface PluginAction {
    data class Deck(val command: DeckCommand, val component: String? = null) : PluginAction
    data class Volume(val change: VolumeChange) : PluginAction
    data class Media(val command: MediaCommand) : PluginAction
    data class OpenApp(val app: PhoneApp) : PluginAction
    data class SelectPlugin(val id: String) : PluginAction
    data object CompletionTone : PluginAction
    data object Repaint : PluginAction
}

/** Capabilities are checked by the engine, not an Android security sandbox for compiled-in code. */
interface PluginHost {
    val scope: CoroutineScope
    fun nowMillis(): Long
    fun invalidate()
    fun audioSnapshot(): AudioSnapshot
    val deckState: StateFlow<DeckState>
        get() = error("No Android deck state installed")
    fun dispatch(action: PluginAction)
}

interface PocketPlugin {
    val descriptor: PluginDescriptor
    fun onActivate(host: PluginHost) {}
    /** Pure, bounded rendering. Never launch work, perform USB I/O or allocate Bitmaps here. */
    fun render(nowMillis: Long): PluginFrame
    fun onKey(event: PluginKeyEvent, host: PluginHost)
    /** A matched physical pair replaces both onKey sequences; it is not a direct-launch/capture grant. */
    fun onChord(chord: KeyChord, host: PluginHost) {}
    fun onDeactivate() {}
}

internal fun frame(labels: List<String>, footer: String, background: Int): PluginFrame =
    PluginFrame(labels.map { KeyTile(it, footer, background) })
