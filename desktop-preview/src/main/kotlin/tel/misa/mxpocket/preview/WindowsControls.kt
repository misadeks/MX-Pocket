package tel.misa.mxpocket.preview

import tel.misa.mxpocket.i18n.WindowsHostCopy as H
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinDef.WORD
import com.sun.jna.platform.win32.WinUser.INPUT
import com.sun.jna.platform.win32.WinUser.KEYBDINPUT
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.i18n.KeypadCopy
import tel.misa.mxpocket.workflow.WORKFLOWS_ENABLED

enum class MediaKey(val vk: Int, private val caption: tel.misa.mxpocket.i18n.Copy) {
    QUIETER(0xae, H.Quieter), LOUDER(0xaf, H.Louder), TOGGLE_MUTE(0xad, H.Mute),
    PREVIOUS(0xb1, H.Previous), NEXT(0xb0, H.Next), PLAY_PAUSE(0xb3, H.Play);

    val label get() = caption.text()

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
            2 -> H.Sent.text(key.label)
            1 -> {
                val released = User32.INSTANCE.SendInput(DWORD(1), arrayOf(input[1]), input[1].size()).toInt() == 1
                if (released) H.RetryOk.text() else H.RetryFailed.text()
            }
            else -> H.Declined.text(key.label)
        }
    } catch (_: LinkageError) { H.Unavailable.text() }
}

internal fun pluginReason(id: String): String? = when (id) {
    "focus", "audio", "game.xo" -> null
    "phone" -> H.Phone.text()
    "launcher" -> H.Launcher.text()
    "studio" -> H.Studio.text()
    "navigation" -> H.Nav.text()
    "teams" -> H.Teams.text()
    else -> H.NotImplemented.text()
}

/** Profile IDs outside the built-in catalog belong to the user, not to an unknown plugin. */
private val builtIns = factoryProfiles().map { it.id }.toSet()
internal fun profileReason(id: String) = if (id in builtIns) pluginReason(id) else null

internal fun bindingReason(call: ActionCall, simulator: Boolean): String? {
    val command = DeckCommand.fromId(call.id)
    if (command != null && call.commandBinding().params.isNotEmpty()) return H.Invalid.text()
    if (command?.accessibilityKey == true) return tel.misa.mxpocket.i18n.WorkspaceCopy.AndroidOnly.text()
    val plugin = if (call.id == "plugin.key") call.params["pluginId"] else command?.deck
    if (plugin != null) {
        pluginReason(plugin)?.let { return it }
        if (plugin != "audio") return null
        val slot = call.params["index"]?.toIntOrNull()
        if (slot in 0..5 || command in setOf(DeckCommand.LOUDER, DeckCommand.QUIETER, DeckCommand.PLAY,
                DeckCommand.PREVIOUS, DeckCommand.NEXT)) return null
        if (simulator && (slot == 7 || command in setOf(DeckCommand.MUTE, DeckCommand.UNMUTE, DeckCommand.VOLUME_LEVEL))) return null
        return when (command) {
            DeckCommand.MUTE, DeckCommand.UNMUTE -> H.Absolute.text()
            DeckCommand.MEDIA_ACCESS -> H.Access.text()
            DeckCommand.VOLUME_LEVEL -> H.Level.text()
            else -> if (slot == 7) H.Metadata.text() else H.Seek.text()
        }
    }
    return when (call.id) {
        DeckActions.BACK, DeckActions.ROOT, DeckActions.PAGE, MORE_PROFILES -> null
        DeckActions.PROFILE -> profileReason(call.params[DeckActions.PROFILE_ID].orEmpty())
        "workflow.run" -> if (WORKFLOWS_ENABLED) H.Workflow.text() else KeypadCopy.Unavailable.text()
        else -> H.NotImplemented.text()
    }
}
