package tel.misa.mxpocket.plugins

import tel.misa.mxpocket.decks.DeckCommand as C

/** Null access means there is no Android host; do not advertise in-app navigation on a simulator. */
fun C.navigationFace(systemAccess: Boolean?): KeyTile {
    val color = when (this) {
        C.ACTIVATE -> 0xffa6e4ce
        C.UP, C.DOWN, C.REVERSE, C.FORWARD -> 0xff245650
        else -> 0xff263943
    }.toInt()
    val title = when (this) { C.REVERSE -> "Prev focus"; C.NAV_ACCESS -> "Access info"; else -> label }
    val hint = when {
        this == C.NAV_ACCESS -> if (systemAccess == true) "System nav on" else "System nav off"
        this == C.RECENTS -> if (systemAccess == true) "Recent apps" else "Needs access"
        systemAccess == null -> "Android only"
        else -> when (this) {
            C.BACK -> if (systemAccess) "Android Back" else "Back in MX Pocket"
            C.HOME -> "Android Home"
            C.FORWARD -> "Move focus forward"
            C.REVERSE -> "Move focus backward"
            C.ACTIVATE -> "Activate focused item"
            else -> if (systemAccess) "" else "In MX Pocket"
        }
    }
    return KeyTile(title, hint, color, inputToken = id, glyph = requireNotNull(legend()),
        readOnly = systemAccess == null && this == C.NAV_ACCESS,
        showFooter = this == C.NAV_ACCESS || this == C.RECENTS && systemAccess != true,
        enabled = this == C.NAV_ACCESS || systemAccess != null && (this != C.RECENTS || systemAccess))
}
