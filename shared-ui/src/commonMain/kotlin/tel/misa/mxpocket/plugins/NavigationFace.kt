package tel.misa.mxpocket.plugins

import tel.misa.mxpocket.decks.DeckCommand as C
import tel.misa.mxpocket.i18n.KeypadCopy as K

/** Null access means there is no Android host; do not advertise in-app navigation on a simulator. */
fun C.navigationFace(systemAccess: Boolean?): KeyTile {
    val color = when (this) {
        C.ACTIVATE -> 0xffa6e4ce
        C.UP, C.DOWN, C.REVERSE, C.FORWARD -> 0xff245650
        else -> 0xff263943
    }.toInt()
    val title = when (this) { C.REVERSE -> K.PrevFocus.text(); C.NAV_ACCESS -> K.AccessInfo.text(); else -> displayLabel }
    val hint = when {
        this == C.NAV_ACCESS -> if (systemAccess == true) K.NavOn else K.NavOff
        this == C.RECENTS -> if (systemAccess == true) K.RecentApps else K.NeedsAccess
        systemAccess == null -> K.AndroidOnly
        else -> when (this) {
            C.BACK -> if (systemAccess) K.AndroidBack else K.PocketBack
            C.HOME -> K.AndroidHome
            C.FORWARD -> K.MoveForward
            C.REVERSE -> K.MoveBackward
            C.ACTIVATE -> K.ActivateItem
            else -> if (systemAccess) null else K.InPocket
        }
    }?.text().orEmpty()
    return KeyTile(title, hint, color, inputToken = id, glyph = requireNotNull(legend()),
        readOnly = systemAccess == null && this == C.NAV_ACCESS,
        showFooter = this == C.NAV_ACCESS || this == C.RECENTS && systemAccess != true,
        enabled = this == C.NAV_ACCESS || systemAccess != null && (this != C.RECENTS || systemAccess))
}
