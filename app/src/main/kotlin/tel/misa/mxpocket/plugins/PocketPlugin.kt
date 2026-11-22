package tel.misa.mxpocket.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.DeckState

enum class PluginCapability { AUDIO, OPEN_APPS, NAVIGATION, TONE, STUDIO, SYSTEM_NAVIGATION, TEAMS, ACCESSIBILITY }

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

/** LCD indices are 0..8. The host reserves the two physical page buttons for navigation. */
data class PluginKeyEvent(val index: Int, val pressed: Boolean, val synthetic: Boolean = false) {
    init { require(index in 0..8) }
}

data class AudioSnapshot(val volume: Int, val maximum: Int)
enum class VolumeChange { LOWER, RAISE, MUTE, UNMUTE }
enum class MediaCommand { PLAY_PAUSE, PREVIOUS, NEXT }
enum class PhoneApp { CAMERA, SETTINGS, DIALER, WEB, ALARMS, ONENOTE }

data class OneNoteSettings(val colors: List<String>, val selectedColor: String?, val thickness: Float, val min: Float, val max: Float, val textColor: Boolean = false, val colorIds: List<String> = colors,
    val colorValues: List<Int?> = emptyList(), val selectedIndex: Int = -1)

sealed interface PluginAction {
    data class Deck(val command: DeckCommand, val component: String? = null) : PluginAction
    data class Volume(val change: VolumeChange) : PluginAction
    data class Media(val command: MediaCommand) : PluginAction
    data class OpenApp(val app: PhoneApp) : PluginAction
    /** ACTION_VIEW for an external URI scheme/host, e.g. a `onenote:` deep link. */
    data class OpenUri(val uri: String) : PluginAction
    /** ACTION_SEND text/plain, e.g. OneNote's share target creating a quick note. */
    data class ShareText(val text: String) : PluginAction
    /** Click the visible control with this text via the opt-in accessibility service. */
    data class AccessibilityClick(val text: String) : PluginAction
    data class OneNoteSetting(val color: String? = null, val thicknessDelta: Int = 0, val close: Boolean = false) : PluginAction
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
    /** Description of the selected tool in the foreground app, via the opt-in accessibility service. */
    val activeTool: String?
        get() = error("No accessibility state installed")
    /** Toolbar tools of the foreground app as (stable id, description). */
    val toolBar: List<Pair<String, String>>
        get() = error("No accessibility state installed")
    val oneNoteSettings: OneNoteSettings? get() = null
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
