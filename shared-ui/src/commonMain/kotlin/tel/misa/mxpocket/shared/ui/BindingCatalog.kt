package tel.misa.mxpocket.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.decks.DeckCommand as C
import tel.misa.mxpocket.i18n.*
import tel.misa.mxpocket.plugins.bindingPreview
import tel.misa.mxpocket.plugins.newPhoneAppActions
import tel.misa.mxpocket.plugins.phoneActionLabel
import tel.misa.mxpocket.plugins.PhoneApp

enum class ActionCategory(val title: Copy) {
    LAYOUT(BindingCopy.Layout), APPS(BindingCopy.Apps), MEDIA(BindingCopy.Media),
    TIMER(KeypadCopy.FocusTimer), CAMERA(BindingCopy.Camera), NAVIGATION(BindingCopy.Navigation),
    SETTINGS(BindingCopy.Settings)
}

data class BindingOption(val category: ActionCategory, val label: String, val call: ActionCall, val unavailableReason: String? = null)

/** Only commands that can be freely assigned. New runtime commands aren't automatically editor actions. */
private val C.pickerCategory: ActionCategory? get() = when (this) {
    C.OPEN, C.TEAMS_OPEN -> ActionCategory.APPS
    C.PLAY, C.PREVIOUS, C.NEXT, C.QUIETER, C.LOUDER, C.MUTE, C.UNMUTE, C.BACK_10, C.FORWARD_10 -> ActionCategory.MEDIA
    C.STUDIO, C.VIDEO -> ActionCategory.CAMERA
    C.BACK, C.HOME, C.RECENTS, C.UP, C.DOWN, C.FORWARD, C.REVERSE, C.ACTIVATE -> ActionCategory.NAVIGATION
    C.EDIT, C.MEDIA_ACCESS, C.CAMERA_ACCESS, C.NAV_ACCESS, C.TEAMS_ACCESS,
    C.TALKBACK_SETTINGS, C.CAPTION_SETTINGS, C.NIGHT_LIGHT_SETTINGS, C.ACCESSIBILITY_SHORTCUT -> ActionCategory.SETTINGS
    else -> null
}

/** Host data supplies real favorite components and availability, never another profile's key list. */
fun globalBindings(profiles: List<DeckProfile>, favorites: List<Pair<String, String>> = emptyList()): List<BindingOption> = buildList {
    for (profile in profiles) {
        add(BindingOption(ActionCategory.LAYOUT, BindingCopy.SwitchProfile.text(profile.displayName()),
            ActionCall(DeckActions.PROFILE, mapOf(DeckActions.PROFILE_ID to profile.id))))
    }
    for (command in C.entries) {
        val category = command.pickerCategory ?: continue
        if (command == C.OPEN) {
            for ((component, label) in favorites) add(BindingOption(category, LayoutCopy.Launch.text(label),
                ActionCall(command.id, mapOf("component" to component))))
        } else add(BindingOption(category, command.displayLabel, ActionCall(command.id)))
    }
    for (call in newPhoneAppActions()) add(BindingOption(
        if (call.params["app"] == PhoneApp.SETTINGS.name) ActionCategory.SETTINGS else ActionCategory.APPS,
        requireNotNull(call.phoneActionLabel()), call))
}

private fun ActionCall.blockedInEditor(disabled: Set<String>): Boolean {
    val owner = slotOwner() ?: C.fromId(id)?.deck ?: id.substringBefore('.')
    val target = targetProfileId()
    return owner == GAME_PROFILE || owner in disabled || target == GAME_PROFILE || target in disabled
}

private fun slot(owner: String, index: Int) =
    ActionCall("plugin.key", mapOf("pluginId" to owner, "index" to index.toString()))

/** Only controls whose *behavior* depends on the local plugin have a reason to use a slot. */
private fun profileControls(profile: DeckProfile, options: List<BindingOption>) = buildList {
    when (profile.id) {
        "focus" -> for (index in 0..8) {
            val call = slot(profile.id, index)
            val face = DeckKey(action = call).bindingPreview() ?: continue
            if (!face.readOnly) add(BindingOption(ActionCategory.TIMER, face.label, call))
        }
        "audio" -> add(BindingOption(ActionCategory.MEDIA, BindingCopy.ToggleMute.text(), slot(profile.id, 1)))
        "launcher" -> if (options.count { it.call.id == C.OPEN.id } > 7)
            add(BindingOption(ActionCategory.APPS, KeypadCopy.NextApps.text(), slot(profile.id, 8)))
    }
}

internal fun bindingsFor(profile: DeckProfile, options: List<BindingOption>, disabled: Set<String>): List<BindingOption> = buildList {
    if (profile.pages.size > 1) {
        add(BindingOption(ActionCategory.LAYOUT, BindingCopy.PreviousPage.text(), ActionCall(DeckActions.BACK)))
        add(BindingOption(ActionCategory.LAYOUT, LayoutCopy.Root.text(), ActionCall(DeckActions.ROOT)))
        for (page in profile.pages.filterNot { it.id == profile.rootPageId }) add(BindingOption(ActionCategory.LAYOUT,
            LayoutCopy.OpenPage.text(profile.displayPageName(page)), ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to page.id))))
    }
    if (profile.id !in disabled) addAll(profileControls(profile, options))
    addAll(options.filter { it.call.slotOwner() == null && it.call.id != DeckActions.PAGE &&
        it.call.targetProfileId() != profile.id && !it.call.blockedInEditor(disabled) })
}

@Composable
internal fun editorBindings(profile: DeckProfile, options: List<BindingOption>, disabled: Set<String>) =
    remember(profile, options, disabled, AppLocale.current) { bindingsFor(profile, options, disabled) }

internal fun bindingName(call: ActionCall?, options: List<BindingOption>): String {
    if (call == null) return LayoutCopy.Unassigned.text()
    options.firstOrNull { it.call == call }?.let {
        return if (it.unavailableReason == null) it.label else LayoutCopy.Unavailable.text(it.label)
    }
    call.phoneActionLabel()?.let { return BindingCopy.Legacy.text(it) }
    val owner = call.slotOwner()
    if (owner != null) {
        val index = call.params["index"]?.toIntOrNull()
        val label = when {
            owner == "launcher" && index in 0..6 -> BindingCopy.FavoritePosition.text(index!! + 1)
            owner == "launcher" && index == 8 -> KeypadCopy.NextApps.text()
            owner == "onenote" && index in 0..8 -> BindingCopy.LiveTool.text(index!! + 1)
            owner == "audio" && index == 1 -> BindingCopy.ToggleMute.text()
            else -> DeckKey(action = call).bindingPreview()?.label
        }
        val name = builtInDeckName(owner)
        return BindingCopy.Legacy.text(if (label == null) LayoutCopy.PluginSlot.text(name, index?.plus(1) ?: "?")
            else LayoutCopy.CategoryAction.text(name, label))
    }
    val label = when (call.id) {
        DeckActions.PAGE -> LayoutCopy.OpenPage.text(call.params[DeckActions.PAGE_ID].orEmpty())
        DeckActions.PROFILE -> BindingCopy.SwitchProfile.text(builtInDeckName(call.params[DeckActions.PROFILE_ID].orEmpty()))
        DeckActions.BACK -> BindingCopy.PreviousPage.text()
        DeckActions.ROOT -> LayoutCopy.Root.text()
        "workflow.run" -> LayoutCopy.BindWorkflow.text(call.params["workflowId"].orEmpty())
        "sound.play" -> BindingCopy.Tone.text()
        C.OPEN.id -> LayoutCopy.Launch.text(call.params["component"].orEmpty())
        else -> C.fromId(call.id)?.displayLabel ?: call.id
    }
    return BindingCopy.Legacy.text(label)
}
