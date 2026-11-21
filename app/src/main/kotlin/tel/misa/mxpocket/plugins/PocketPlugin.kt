package tel.misa.mxpocket.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.DeckState

enum class PluginCapability { AUDIO, OPEN_APPS, NAVIGATION, TONE, STUDIO, SYSTEM_NAVIGATION }

data class PluginDescriptor(
    val id: String,
    val title: String,
    val capabilities: Set<PluginCapability> = emptySet(),
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9.-]{2,63}"))) { "Use a stable lowercase plugin ID" }
        require(title.isNotBlank()) { "Plugin title is required" }
    }
}

data class KeyTile(val label: String, val footer: String, val background: Int,
    val iconComponent: String? = null, val inputToken: String? = null,
    val icon: String = "", val enabled: Boolean = true)

data class PluginFrame(val keys: List<KeyTile>) {
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
    fun onDeactivate() {}
}

internal fun frame(labels: List<String>, footer: String, background: Int): PluginFrame =
    PluginFrame(labels.map { KeyTile(it, footer, background) })
