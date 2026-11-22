package tel.misa.mxpocket

import tel.misa.mxpocket.actions.*
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.workflow.WorkflowActionSpec
import tel.misa.mxpocket.workflow.WorkflowField
import tel.misa.mxpocket.workflow.WorkflowOrigin

internal fun PocketSession.platformBindings(): List<Pair<String,ActionCall>> = buildList {
    for (command in DeckCommand.entries.filterNot { it.legacyCapture || it.nativeControl }) {
        if (command == DeckCommand.OPEN) deckState.value.favorites.forEach {
            add("Launch ${it.label}" to ActionCall(command.id,mapOf("component" to it.component)))
        } else add("${command.deck}: ${command.label}" to ActionCall(command.id))
    }
    for (choice in tel.misa.mxpocket.plugins.PhoneApp.entries) {
        add("Phone: ${choice.name.lowercase().replaceFirstChar(Char::uppercaseChar)}" to
            ActionCall("phone.open",mapOf("app" to choice.name)))
    }
}

internal fun PocketSession.pluginDetails(id: String): String {
    val state = deckState.value
    val access = when (id) {
        "launcher" -> "${state.favorites.size} favorites. Only launchable apps are listed."
        "studio" -> "Phone camera viewfinder. Keypad camera controls: ${state.cameraStatus}."
        "audio" -> if (state.media.access) "Media access on. ${state.media.status}" else "Media access off. Basic media keys available."
        "navigation" -> if (state.navigationEnabled) "System navigation enabled." else "Basic in-app navigation; system access off."
        "teams" -> "Work opens Teams on this phone. Call tools keep the meeting mic on the center key. Automatic switching is optional."
        "game.xo" -> "Choose single player against the computer or local 1v1. Play and change modes on the keypad."
        "focus" -> "Timer, pause, reset, and duration presets. No permission required."
        "phone" -> "Camera, settings, dialer, browser and alarms. Direct shortcuts are optional."
        else -> ""
    }
    val commands = DeckCommand.entries.filter { it.deck == id && !it.legacyCapture }.joinToString { it.label }
    return listOf(access,commands).filter(String::isNotBlank).joinToString("\n")
}

internal fun PocketSession.platformWorkflowActions(): List<WorkflowActionSpec> = buildList {
    addAll(DeckCommand.entries.filter { it.workflowSafe && it.deck !in disabledPlugins.value }
        .map { WorkflowActionSpec(it.id,it.label) }
    )
    if ("navigation" !in disabledPlugins.value) add(WorkflowActionSpec(DeckCommand.HOME.id,
        "Open Android Home",requiresConfirmation = true))
    val favorites = deckState.value.favorites
    if ("launcher" !in disabledPlugins.value && favorites.isNotEmpty())
        add(WorkflowActionSpec(DeckCommand.OPEN.id,"Open a favorite app",listOf(
            WorkflowField("component","Favorite activity",favorites.map { it.component })), requiresConfirmation = true))
}

internal fun PocketSession.platformAction(call: ActionCall, origin: WorkflowOrigin?): ActionResult {
    val command = DeckCommand.fromId(call.id) ?: return ActionResult(false,"Unknown action: ${call.id}")
    if (origin == null && !command.workflowSafe) return ActionResult(false,"This control requires a fresh keypad press.")
    val result = runDeckAction(call.id, call.params, fromPhysicalKey = origin == WorkflowOrigin.PHYSICAL)
    return ActionResult(result.success,result.message)
}

internal fun PocketSession.platformSignals(): Map<String,String> = deckState.value.let { state ->
    buildMap {
        state.media.playing?.let { put("mediaActive", it.toString()) }
        put("navigationAvailable",state.navigationEnabled.toString())
        put("mediaAccess",state.media.access.toString())
    }
}

internal fun PocketSession.platformTile(call: ActionCall?): KeyTile? =
    call?.let { action -> DeckCommand.fromId(action.id)?.let { deckState.value.tile(it,action.params["component"]) } }
