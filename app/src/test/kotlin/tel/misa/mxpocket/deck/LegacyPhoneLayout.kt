package tel.misa.mxpocket.deck

import org.json.JSONObject
import tel.misa.mxpocket.actions.ActionCall

/** A v4 backup, not a lookup into the current factory layouts. */
internal fun DeckRepository.importLegacyPhone(name: String = "Phone") {
    val state = state.value
    val root = DeckPage("root", "Phone", (0..8).map {
        DeckKey(action = ActionCall("plugin.key", mapOf("pluginId" to "phone", "index" to it.toString())))
    })
    val profiles = state.profiles.toMutableList().apply { add(2, DeckProfile("phone", name, "root", listOf(root))) }
    val backup = DeckJson.encode(state.copy(profiles = profiles, histories = state.histories + ("phone" to listOf("root"))))
    importJson(JSONObject(backup).put("automatic", false).toString()).getOrThrow()
    showHome()
}
