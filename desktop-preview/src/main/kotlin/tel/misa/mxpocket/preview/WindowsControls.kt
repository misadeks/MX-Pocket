package tel.misa.mxpocket.preview

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.WORD
import com.sun.jna.platform.win32.WinUser.INPUT
import com.sun.jna.platform.win32.WinUser.KEYBDINPUT
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.DeckCommand

enum class MediaKey(val vk: Int, val label: String) {
    QUIETER(0xae, "Volume down"), LOUDER(0xaf, "Volume up"), TOGGLE_MUTE(0xad, "Mute / unmute"),
    PREVIOUS(0xb1, "Previous track"), NEXT(0xb0, "Next track"), PLAY_PAUSE(0xb3, "Play / pause");

    companion object {
        fun forSlot(index: Int) = listOf(QUIETER, TOGGLE_MUTE, LOUDER, PREVIOUS, PLAY_PAUSE, NEXT).getOrNull(index)
    }
}

/** Sends only multimedia keys, as a down/up pair. No foreground-window or keyboard monitoring. */
internal fun sendWindowsMedia(key: MediaKey): String {
    @Suppress("UNCHECKED_CAST")
    val input = INPUT().toArray(2) as Array<INPUT>
    input.forEachIndexed { index, event ->
        event.type = DWORD(INPUT.INPUT_KEYBOARD.toLong())
        event.input.setType(KEYBDINPUT::class.java)
        event.input.ki.wVk = WORD(key.vk.toLong())
        event.input.ki.dwFlags = DWORD(if (index == 0) 1L else 3L) // extended key, then extended | KEYUP
    }
    return try {
        when (User32.INSTANCE.SendInput(DWORD(2), input, input[0].size()).toInt()) {
            2 -> "Sent ${key.label} to Windows."
            1 -> {
                val released = User32.INSTANCE.SendInput(DWORD(1), arrayOf(input[1]), input[1].size()).toInt() == 1
                "Windows accepted only the press. " + if (released) "Key released on retry." else "Key release failed."
            }
            else -> "Windows did not accept ${key.label}. Check the foreground application's permissions."
        }
    } catch (_: LinkageError) { "Windows media controls could not load. No command confirmed." }
}

internal fun pluginReason(id: String): String? = when (id) {
    "focus", "audio", "game.xo" -> null
    "phone" -> "Needs Android phone services."
    "launcher" -> "Needs installed Android apps."
    "studio" -> "Needs the Android camera."
    "navigation" -> "Needs Android accessibility access."
    "teams" -> "Needs the Android Teams integration."
    else -> "No Windows implementation."
}

/** Profile IDs outside the built-in catalog belong to the user, not to an unknown plugin. */
private val builtIns = defaultDeck().profiles.map { it.id }.toSet()
internal fun profileReason(id: String) = if (id in builtIns) pluginReason(id) else null

internal fun bindingReason(call: ActionCall, simulator: Boolean): String? {
    val command = DeckCommand.fromId(call.id)
    if (command != null && call.commandBinding().params.isNotEmpty()) return "Invalid action parameters."
    val plugin = if (call.id == "plugin.key") call.params["pluginId"] else command?.deck
    if (plugin != null) {
        pluginReason(plugin)?.let { return it }
        if (plugin != "audio") return null
        val slot = call.params["index"]?.toIntOrNull()
        if (slot in 0..5 || command in setOf(DeckCommand.LOUDER, DeckCommand.QUIETER, DeckCommand.PLAY,
                DeckCommand.PREVIOUS, DeckCommand.NEXT)) return null
        if (simulator && (slot == 7 || command in setOf(DeckCommand.MUTE, DeckCommand.UNMUTE, DeckCommand.VOLUME_LEVEL))) return null
        return when (command) {
            DeckCommand.MUTE, DeckCommand.UNMUTE -> "Windows exposes a mute toggle, not an absolute mute command."
            DeckCommand.MEDIA_ACCESS -> "Needs Android media access."
            DeckCommand.VOLUME_LEVEL -> "Windows volume level is not queried."
            else -> if (slot == 7) "Track metadata is not queried." else "Seeking is not available."
        }
    }
    return when (call.id) {
        DeckActions.BACK, DeckActions.ROOT, DeckActions.PAGE -> null
        DeckActions.PROFILE -> profileReason(call.params[DeckActions.PROFILE_ID].orEmpty())
        "workflow.run" -> "Workflow execution needs Android."
        else -> "No Windows implementation."
    }
}
