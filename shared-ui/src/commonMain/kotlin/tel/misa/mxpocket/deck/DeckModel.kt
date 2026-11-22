package tel.misa.mxpocket.deck

import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.plugins.PhoneSlot
import tel.misa.mxpocket.plugins.legacyPhoneSlot
import tel.misa.mxpocket.decks.DeckCommand

/** Stable IDs for host-dispatched navigation. Page IDs are local to a profile. */
object DeckActions {
    const val PAGE = "page.open"
    const val BACK = "page.back"
    const val ROOT = "page.root"
    const val PROFILE = "profile.switch"
    const val PAGE_ID = "pageId"
    const val PROFILE_ID = "profileId"
    // The longest migrated media action, including this field, must not exceed the v1 slot's UTF-8 size.
    const val LEGACY_SLOT_OWNER = "slot"
}

// Not a legal saved profile ID. The launcher is an operating view, not part of a layout backup.
const val PROFILE_HOME = "@home"
const val PHONE_CALL_VIEW = "@phone-call"
const val GAME_PROFILE = "game.xo"
const val MORE_PROFILES = "home.more"

// A migrated Audio slot still participates in the original page's single-plugin selection.
// An explicitly bound media command has no such constraint.
fun ActionCall.slotOwner(): String? = when {
    id == "plugin.key" -> params["pluginId"]
    id.startsWith("media.") && params[DeckActions.LEGACY_SLOT_OWNER] == "audio" -> "audio"
    else -> null
}

fun ActionCall.commandBinding() =
    if (id.startsWith("media.") && slotOwner() == "audio") copy(params = params - DeckActions.LEGACY_SLOT_OWNER) else this

fun phoneShortcutProfile(slot: Int?) = (slot?.let(::legacyPhoneSlot) as? PhoneSlot.SelectProfile)?.id

fun ActionCall.targetProfileId(): String? = when {
    id == DeckActions.PROFILE -> params[DeckActions.PROFILE_ID]
    id == "plugin.key" && params["pluginId"] == "phone" -> phoneShortcutProfile(params["index"]?.toIntOrNull())
    else -> null
}

enum class SelectionSource { USER, AUTOMATIC }

data class DeckKey(
    val icon: String = "",
    val label: String = "",
    val color: Int = 0xff284454.toInt(),
    val action: ActionCall? = null,
    val enabled: Boolean = true,
    val holdAction: ActionCall? = null,
)

internal fun DeckKey.mapActions(transform: (ActionCall) -> ActionCall?) =
    copy(action = action?.let(transform), holdAction = holdAction?.let(transform))

data class DeckPage(val id: String, val name: String, val keys: List<DeckKey> = List(9) { DeckKey() })

/** A Hold slot cannot displace an existing Tap slot's plugin on the page. */
fun DeckPage.selectedSlotOwner(registered: (String) -> Boolean): String? =
    keys.firstNotNullOfOrNull { it.action?.slotOwner()?.takeIf(registered) }
        ?: keys.firstNotNullOfOrNull { it.holdAction?.slotOwner()?.takeIf(registered) }

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
    val histories: Map<String, List<String>>,
    val selectionReason: String = "Default profile",
    val xoProfileRemoved: Boolean = false,
    val teamsProfileRemoved: Boolean = false,
    /** Local host preference, never part of a layout backup. Factory profiles use their plugin ID. */
    val disabledPluginIds: Set<String> = emptySet(),
) {
    val visibleProfiles get() = profiles.filterNot { it.id in disabledPluginIds || it.id in setOf(GAME_PROFILE, PROFILE_HOME, PHONE_CALL_VIEW) }
    // Keep the legacy target available to editors, explicit bindings and workflow validation.
    val navigationProfiles get() = visibleProfiles.filterNot { it == stockPhoneProfile }
    val availableDefaultId get() = visibleProfiles.firstOrNull { it.id == defaultProfileId }?.id ?: navigationProfiles.first().id
    val activeProfile: DeckProfile get() = profiles.first { it.id == activeProfileId }
    val activePage: DeckPage get() = activeProfile.pages.first { it.id == histories.getValue(activeProfileId).last() }
}

object DeckLimits {
    const val VERSION = 5
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

// plugin.key indices are persisted action identities; only their physical placement changes.
val navigationFactorySlots get() = listOf(
    0, 3, 1, // Back, Scroll up, Home
    6, 4, 8, // Previous, Select, Next
    2, 5, 7, // Recents, Scroll down, Access
)

private fun pluginRoot(id: String, name: String, slots: Iterable<Int> = 0..8) = DeckPage("root", name, slots.map { index ->
    DeckKey(action = ActionCall("plugin.key", mapOf("pluginId" to id, "index" to index.toString())))
})

const val ACCESSIBILITY_PAGE = "accessibility"
private fun navigationPages(): List<DeckPage> {
    val old = pluginRoot("navigation", "Navigation", navigationFactorySlots)
    val open = DeckKey(action = ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to ACCESSIBILITY_PAGE)))
    val tools = listOf(DeckCommand.TALKBACK_SETTINGS, DeckCommand.CAPTION_SETTINGS, DeckCommand.NIGHT_LIGHT_SETTINGS,
        DeckCommand.ACCESSIBILITY_SHORTCUT, DeckCommand.NAV_ACCESS).map { DeckKey(action = ActionCall(it.id)) }
    return listOf(old.copy(keys = old.keys.dropLast(1) + open), DeckPage(ACCESSIBILITY_PAGE, "Accessibility",
        tools + List(3) { DeckKey() } + DeckKey(label = "Back to Navigation", action = ActionCall(DeckActions.ROOT))))
}

internal fun DeckState.withAccessibilityPage(): DeckState {
    val oldRoot = pluginRoot("navigation", "Navigation", navigationFactorySlots)
    val owner = profiles.find { it.id == "navigation" && it.rootPageId == oldRoot.id && oldRoot in it.pages } ?: return this
    if (owner.pages.size >= DeckLimits.PAGES || owner.pages.any { it.id == ACCESSIBILITY_PAGE }) return this
    val (root, tools) = navigationPages()
    val updated = owner.copy(pages = owner.pages.map { if (it == oldRoot) root else it } + tools)
    return copy(profiles = profiles.map { if (it == owner) updated else it })
}

internal fun DeckState.upgradeStockNavigationRoot(): DeckState {
    val legacy = pluginRoot("navigation", "Navigation")
    val owner = profiles.find { it.id == "navigation" && it.rootPageId == legacy.id && legacy in it.pages } ?: return this
    val updated = owner.copy(pages = owner.pages.map { page ->
        if (page == legacy) page.copy(keys = navigationFactorySlots.map(page.keys::get)) else page
    })
    return copy(profiles = profiles.map { if (it.id == owner.id) updated else it })
}

private val stockPhoneProfile = DeckProfile("phone", "Phone", "root", listOf(pluginRoot("phone", "Phone")))

/** Slot templates are also the catalog of built-in plugins, independently of saved layouts. */
fun factoryProfiles(): List<DeckProfile> {
    fun plugin(id: String, name: String, rules: List<DeckRule> = emptyList(), slots: Iterable<Int> = 0..8) =
        DeckProfile(id, name, "root", listOf(pluginRoot(id, name, slots)), rules = rules)
    return listOf(
        plugin("focus", "Focus"),
        plugin("audio", "Audio", listOf(DeckRule("mediaActive", "true", 3))),
        stockPhoneProfile,
        plugin("launcher", "Launcher"),
        plugin("studio", "Studio").copy(automatic = false),
        DeckProfile("navigation", "Navigation", "root", navigationPages()),
        plugin("game.xo", tel.misa.mxpocket.plugins.TIC_TAC_TOE_TITLE),
        tel.misa.mxpocket.decks.teamsProfile(),
        plugin("onenote", "OneNote"),
    )
}

fun defaultDeck(): DeckState {
    val profiles = factoryProfiles().filterNot { it == stockPhoneProfile }
    return DeckState(
        DeckLimits.VERSION, profiles, "launcher", "launcher", null,
        profiles.associate { it.id to listOf(it.rootPageId) },
    )
}
