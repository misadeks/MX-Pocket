package tel.misa.mxpocket.plugins

import tel.misa.mxpocket.i18n.CallCopy
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.i18n.KeypadCopy
import tel.misa.mxpocket.i18n.TeamsCallCopy
import tel.misa.mxpocket.i18n.WorkspaceCopy

enum class PhoneLine(private val caption: Copy) {
    RINGING(CallCopy.Incoming), OFF_HOOK(CallCopy.OffHook), MULTIPLE(CallCopy.Multiple), CALL(CallCopy.Call);

    val label: String get() = caption.text()
}

const val CALL_PHOTO = 1
const val CALL_IDENTITY = 4
const val CALL_ACCEPT = 6
const val CALL_DECLINE = 8

/** The app preview never takes a dialer label or photo, even when the physical keypad has details. */
fun phoneCallHomeFrame(line: PhoneLine, controls: Boolean, generation: Long): PluginFrame =
    phoneCallFrame(line, WorkspaceCopy.OnKeypad.text(), controls, generation)

private val empty = KeyTile("", "", 0xff15191c.toInt(), enabled = false, readOnly = true)

fun incomingTeamsFrame(caller: String? = null): PluginFrame {
    val keys = MutableList(9) { empty }
    keys[CALL_PHOTO] = empty.copy(glyph = IntegrationApp.TEAMS)
    keys[CALL_IDENTITY] = empty.copy(label = caller ?: KeypadCopy.Teams.text(),
        footer = (if (caller == null) CallCopy.Incoming else TeamsCallCopy.IncomingCall).text(), callIdentity = true)
    return PluginFrame(keys)
}

fun phoneCallFrame(line: PhoneLine, caller: String?, controls: Boolean, generation: Long): PluginFrame {
    val keys = MutableList(9) { empty }
    keys[CALL_PHOTO] = empty.copy(glyph = DeckGlyph.CALLER)
    keys[CALL_IDENTITY] = if (line == PhoneLine.CALL || line == PhoneLine.MULTIPLE) empty.copy(label = line.label)
        else empty.copy(label = caller ?: CallCopy.NoCallerDetails.text(), footer = line.label, callIdentity = true)
    if (controls && line == PhoneLine.RINGING) {
        keys[CALL_ACCEPT] = KeyTile(CallCopy.Accept.text(), "", 0xff205e47.toInt(), glyph = DeckGlyph.PHONE, inputToken = "answer:$generation")
        keys[CALL_DECLINE] = KeyTile(CallCopy.Decline.text(), "", 0xff873840.toInt(), glyph = DeckGlyph.DECLINE, inputToken = "decline:$generation")
    }
    return PluginFrame(keys)
}
