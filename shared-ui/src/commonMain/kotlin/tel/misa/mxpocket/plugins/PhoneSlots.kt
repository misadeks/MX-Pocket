package tel.misa.mxpocket.plugins

import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.i18n.KeypadCopy as K
import tel.misa.mxpocket.i18n.OneNoteCopy
import tel.misa.mxpocket.i18n.builtInDeckName
import tel.misa.mxpocket.shared.ui.PocketStyle

/** What a legacy Phone slot does, independent of its position on a saved page. */
sealed interface PhoneSlot {
    data class SelectProfile(val id: String) : PhoneSlot
    data object ReadOnlyLabel : PhoneSlot
    data object Refresh : PhoneSlot
}

/** The enum names are the persisted `phone.open` app parameter, including WEB. */
enum class PhoneApp(internal val title: Copy, internal val actionTitle: Copy) : PhoneSlot {
    CAMERA(K.Camera, K.OpenCamera),
    SETTINGS(K.Settings, AccessCopy.OpenAndroidSettings),
    DIALER(K.Dialer, K.OpenDialer),
    WEB(K.LogitechSite, K.OpenLogitechSite),
    ALARMS(K.Alarms, K.ShowAlarms),
    ONENOTE(OneNoteCopy.Brand, OneNoteCopy.Open)
}

fun PhoneApp.displayName() = title.text()

/** Only `app` selects the destination. Other imported parameters never override it, even for WEB. */
fun ActionCall.phoneAppTarget(): PhoneApp? =
    if (id == "phone.open") PhoneApp.entries.find { it.name == params["app"] } else null

private data class PhoneKey(val action: PhoneSlot, val glyph: KeyGlyph?, val compactTitle: Copy? = null)

// Index is the wire identity of plugin.key(pluginId=phone, index=...). Do not reorder.
private val phoneKeys = listOf(
    PhoneKey(PhoneApp.CAMERA, DeckGlyph.CAMERA),
    PhoneKey(PhoneApp.SETTINGS, DeckGlyph.SETTINGS),
    PhoneKey(PhoneApp.DIALER, DeckGlyph.PHONE),
    PhoneKey(PhoneApp.WEB, DeckGlyph.WEB),
    PhoneKey(PhoneSlot.ReadOnlyLabel, null),
    PhoneKey(PhoneApp.ALARMS, DeckGlyph.TIMER, K.Alarm),
    PhoneKey(PhoneSlot.SelectProfile("focus"), DeckGlyph.TIMER),
    PhoneKey(PhoneSlot.SelectProfile("audio"), PlayerGlyph.TRACK),
    PhoneKey(PhoneSlot.Refresh, DeckGlyph.REFRESH),
)

fun legacyPhoneSlot(index: Int): PhoneSlot? = phoneKeys.getOrNull(index)?.action

fun phoneFrame() = PluginFrame(phoneKeys.map { key ->
    val action = key.action
    val label = key.compactTitle?.text() ?: when (action) {
        is PhoneApp -> action.displayName()
        is PhoneSlot.SelectProfile -> builtInDeckName(action.id)
        PhoneSlot.ReadOnlyLabel -> K.Phone.text()
        PhoneSlot.Refresh -> K.Repaint.text()
    }
    KeyTile(label, K.Phone.text(), PocketStyle.accent((action as? PhoneSlot.SelectProfile)?.id ?: "phone").fill,
        glyph = key.glyph, readOnly = action == PhoneSlot.ReadOnlyLabel, showFooter = false)
})

/** Localized editor description; unknown app names, slots and unrelated calls get no Phone label. */
fun ActionCall.phoneActionLabel(): String? {
    val action = when (id) {
        "phone.open" -> phoneAppTarget()
        "plugin.key" -> if (params.size == 2 && params["pluginId"] == "phone")
            params["index"]?.toIntOrNull()?.let(::legacyPhoneSlot) else null
        else -> null
    }
    return when (action) {
        is PhoneApp -> action.actionTitle.text()
        is PhoneSlot.SelectProfile -> K.SelectPhoneProfile.text(builtInDeckName(action.id))
        PhoneSlot.ReadOnlyLabel -> K.PhoneLabelOnly.text()
        PhoneSlot.Refresh -> K.RefreshKeypad.text()
        null -> null
    }
}

/** For new assignments only. Camera duplicates studio.open; WEB is the fixed Logitech demo. */
fun newPhoneAppActions(): List<ActionCall> = listOf(PhoneApp.SETTINGS, PhoneApp.DIALER, PhoneApp.ALARMS, PhoneApp.ONENOTE)
    .map { ActionCall("phone.open", mapOf("app" to it.name)) }
