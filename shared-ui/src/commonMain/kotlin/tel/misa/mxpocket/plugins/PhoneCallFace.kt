package tel.misa.mxpocket.plugins

enum class PhoneLine(val label: String) { RINGING("Incoming call"), OFF_HOOK("Dialing or active"), MULTIPLE("Multiple calls") }

const val CALL_PHOTO = 1
const val CALL_ACCEPT = 6
const val CALL_DECLINE = 8

fun phoneCallFrame(line: PhoneLine, caller: String?, controls: Boolean, generation: Long): PluginFrame {
    fun face(label: String = "", footer: String = "", glyph: KeyGlyph? = null) =
        KeyTile(label, footer, 0xff233039.toInt(), readOnly = true, glyph = glyph)
    val keys = MutableList(9) { face() }
    keys[0] = face(line.label, "Phone", DeckGlyph.PHONE)
    keys[4] = face(caller ?: "Private / unknown", "Caller")
    keys[7] = face("Page keys", "Leave view")
    if (controls && line == PhoneLine.RINGING) {
        keys[CALL_ACCEPT] = KeyTile("Accept", "", 0xff205e47.toInt(), glyph = DeckGlyph.PHONE, inputToken = "answer:$generation")
        keys[CALL_DECLINE] = KeyTile("Decline", "", 0xff873840.toInt(), glyph = DeckGlyph.DECLINE, inputToken = "decline:$generation")
    } else keys[5] = face("Use Phone", "Call controls")
    return PluginFrame(keys)
}
