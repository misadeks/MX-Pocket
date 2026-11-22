package tel.misa.mxpocket

import tel.misa.mxpocket.actions.*
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.pluginDetails
import tel.misa.mxpocket.i18n.LayoutCopy
import tel.misa.mxpocket.i18n.builtInDeckName
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.displayName
import tel.misa.mxpocket.workflow.WorkflowActionSpec
import tel.misa.mxpocket.workflow.WorkflowField
import tel.misa.mxpocket.workflow.WorkflowOrigin

internal fun PocketSession.platformBindings(): List<Pair<String,ActionCall>> = buildList {
    for (command in DeckCommand.entries.filterNot { it.legacyCapture || it.nativeControl }) {
        if (command == DeckCommand.OPEN) deckState.value.favorites.forEach {
            add(LayoutCopy.Launch.text(it.label) to ActionCall(command.id,mapOf("component" to it.component)))
        } else add(LayoutCopy.CategoryAction.text(builtInDeckName(command.deck), command.displayLabel) to ActionCall(command.id))
    }
    for (choice in tel.misa.mxpocket.plugins.PhoneApp.entries) {
        add(LayoutCopy.PhoneAction.text(choice.displayName()) to
            ActionCall("phone.open",mapOf("app" to choice.name)))
    }
}

internal fun PocketSession.pluginDetails(id: String) = deckState.value.pluginDetails(id)

internal fun PocketSession.platformWorkflowActions(): List<WorkflowActionSpec> = buildList {
    addAll(DeckCommand.entries.filter { it.workflowSafe && it.deck !in disabledPlugins.value }
        .map { WorkflowActionSpec(it.id,it.displayLabel) }
    )
    if ("navigation" !in disabledPlugins.value) add(WorkflowActionSpec(DeckCommand.HOME.id,
        LayoutCopy.AndroidHome.text(),requiresConfirmation = true))
    val favorites = deckState.value.favorites
    if ("launcher" !in disabledPlugins.value && favorites.isNotEmpty())
        add(WorkflowActionSpec(DeckCommand.OPEN.id,LayoutCopy.OpenFavorite.text(),listOf(
            WorkflowField("component",LayoutCopy.FavoriteActivity.text(),favorites.map { it.component })), requiresConfirmation = true))
}

internal fun PocketSession.platformAction(call: ActionCall, origin: WorkflowOrigin?): ActionResult {
    val command = DeckCommand.fromId(call.id) ?: return ActionResult(false,LayoutCopy.UnknownAction.text(call.id))
    if (origin == null && !command.workflowSafe) return ActionResult(false,LayoutCopy.RequiresKey.text())
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
