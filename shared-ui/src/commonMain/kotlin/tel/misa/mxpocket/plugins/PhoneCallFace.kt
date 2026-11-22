package tel.misa.mxpocket.plugins

import tel.misa.mxpocket.i18n.CallCopy
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.i18n.KeypadCopy
import tel.misa.mxpocket.i18n.WorkspaceCopy

enum class PhoneLine(private val caption: Copy) {
    RINGING(CallCopy.Incoming), OFF_HOOK(CallCopy.OffHook), MULTIPLE(CallCopy.Multiple);

    val label: String get() = caption.text()
}

const val CALL_PHOTO = 1
const val CALL_ACCEPT = 6
const val CALL_DECLINE = 8

/** The app preview never takes a dialer label or photo, even when the physical keypad has details. */
fun phoneCallHomeFrame(line: PhoneLine, controls: Boolean, generation: Long): PluginFrame =
    phoneCallFrame(line, WorkspaceCopy.OnKeypad.text(), controls, generation)

fun phoneCallFrame(line: PhoneLine, caller: String?, controls: Boolean, generation: Long): PluginFrame {
    fun face(label: String = "", footer: String = "", glyph: KeyGlyph? = null) =
        KeyTile(label, footer, 0xff233039.toInt(), readOnly = true, glyph = glyph)
    val keys = MutableList(9) { face() }
    keys[0] = face(line.label, KeypadCopy.Phone.text(), DeckGlyph.PHONE)
    keys[4] = face(caller ?: CallCopy.UnknownCaller.text(), CallCopy.Caller.text())
    keys[7] = face(CallCopy.PageKeys.text(), CallCopy.LeaveView.text())
    if (controls && line == PhoneLine.RINGING) {
        keys[CALL_ACCEPT] = KeyTile(CallCopy.Accept.text(), "", 0xff205e47.toInt(), glyph = DeckGlyph.PHONE, inputToken = "answer:$generation")
        keys[CALL_DECLINE] = KeyTile(CallCopy.Decline.text(), "", 0xff873840.toInt(), glyph = DeckGlyph.DECLINE, inputToken = "decline:$generation")
    } else keys[5] = face(CallCopy.UsePhone.text(), CallCopy.CallControls.text())
    return PluginFrame(keys)
}
