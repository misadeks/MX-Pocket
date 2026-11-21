package tel.misa.mxpocket.deck

import java.util.Collections
import tel.misa.mxpocket.actions.ActionCall

/** Stable IDs for host-dispatched navigation. Page IDs are local to a profile. */
object DeckActions {
    const val PAGE = "page.open"
    const val BACK = "page.back"
    const val ROOT = "page.root"
    const val PROFILE = "profile.switch"
    const val PAGE_ID = "pageId"
    const val PROFILE_ID = "profileId"
    const val LEGACY_SLOT_OWNER = "legacySlotOwner"
}

// A migrated Audio slot still participates in the original page's single-plugin selection.
// An explicitly bound media command has no such constraint.
internal fun ActionCall.slotOwner(): String? = when {
    id == "plugin.key" -> params["pluginId"]
    id.startsWith("media.") && params[DeckActions.LEGACY_SLOT_OWNER] == "audio" -> "audio"
    else -> null
}

internal fun ActionCall.commandBinding() =
    if (id.startsWith("media.") && slotOwner() == "audio") copy(params = params - DeckActions.LEGACY_SLOT_OWNER) else this

enum class SelectionSource { USER, AUTOMATIC }

data class DeckKey(
    val icon: String = "",
    val label: String = "",
    val color: Int = 0xff284454.toInt(),
    val action: ActionCall? = null,
    val enabled: Boolean = true,
)

data class DeckPage(val id: String, val name: String, val keys: List<DeckKey> = List(9) { DeckKey() })
data class DeckRule(val signal: String, val value: String, val weight: Int = 1)
data class DeckDecision(
    val reason: String,
    val scores: Map<String, Int> = emptyMap(),
    val matches: Map<String, List<DeckRule>> = emptyMap(),
    val candidateId: String? = null,
)
data class DeckProfile(
    val id: String,
    val name: String,
    val rootPageId: String,
    val pages: List<DeckPage>,
    val automatic: Boolean = true,
    val rules: List<DeckRule> = emptyList(),
)

/** Repository snapshots are deeply read-only. History contains the root through the current page. */
data class DeckState(
    val version: Int,
    val profiles: List<DeckProfile>,
    val activeProfileId: String,
    val defaultProfileId: String,
    val pinnedProfileId: String?,
    val automatic: Boolean,
    val histories: Map<String, List<String>>,
    val selectionReason: String = "Default profile",
) {
    val activeProfile: DeckProfile get() = profiles.first { it.id == activeProfileId }
    val activePage: DeckPage get() = activeProfile.pages.first { it.id == histories.getValue(activeProfileId).last() }
}

object DeckLimits {
    const val VERSION = 3
    const val MAX_BYTES = 512 * 1024
    const val PROFILES = 16
    const val PAGES = 32
    const val HISTORY = 32
    const val RULES = 32
    const val PARAMS = 16
    const val NAME = 80
    const val LABEL = 64
    const val ICON = 32
    const val PARAM_VALUE = 1024
    const val RULE_VALUE = 128
    const val MAX_WEIGHT = 100
}

internal fun <T> List<T>.locked(): List<T> = Collections.unmodifiableList(ArrayList(this))
internal fun <K, V> Map<K, V>.locked(): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(this))

internal fun DeckState.snapshot() = copy(
    profiles = profiles.map { profile ->
        profile.copy(
            rules = profile.rules.locked(),
            pages = profile.pages.map { page ->
                page.copy(keys = page.keys.map { key ->
                    key.copy(action = key.action?.let { it.copy(params = it.params.locked()) })
                }.locked())
            }.locked(),
        )
    }.locked(),
    histories = histories.mapValues { it.value.locked() }.locked(),
)

// plugin.key indices are persisted action identities; only their physical placement changes.
internal val navigationFactorySlots = listOf(
    0, 3, 1, // Back, Scroll up, Home
    6, 4, 8, // Previous, Select, Next
    2, 5, 7, // Recents, Scroll down, Access
).locked()

private fun pluginRoot(id: String, name: String, slots: Iterable<Int> = 0..8) = DeckPage("root", name, slots.map { index ->
    DeckKey(action = ActionCall("plugin.key", mapOf("pluginId" to id, "index" to index.toString())))
})

internal fun DeckState.upgradeStockNavigationRoot(): DeckState {
    val legacy = pluginRoot("navigation", "Navigation")
    val owner = profiles.find { it.id == "navigation" && it.rootPageId == legacy.id && legacy in it.pages } ?: return this
    val updated = owner.copy(pages = owner.pages.map { page ->
        if (page == legacy) page.copy(keys = navigationFactorySlots.map(page.keys::get)) else page
    })
    return copy(profiles = profiles.map { if (it.id == owner.id) updated else it })
}

internal fun defaultDeck(): DeckState {
    fun plugin(id: String, name: String, rules: List<DeckRule> = emptyList(), slots: Iterable<Int> = 0..8) =
        DeckProfile(id, name, "root", listOf(pluginRoot(id, name, slots)), rules = rules)
    val profiles = listOf(
        plugin("focus", "Focus"),
        plugin("audio", "Audio", listOf(DeckRule("mediaActive", "true", 3))),
        plugin("phone", "Phone"),
        plugin("launcher", "Launcher"),
        plugin("studio", "Studio"),
        plugin("navigation", "Navigation", slots = navigationFactorySlots),
    )
    return DeckState(
        DeckLimits.VERSION, profiles, "focus", "focus", null, false,
        profiles.associate { it.id to listOf(it.rootPageId) },
    )
}
