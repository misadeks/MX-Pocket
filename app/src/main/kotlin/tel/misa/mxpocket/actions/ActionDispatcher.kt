package tel.misa.mxpocket.actions

/** Saved profiles and workflows reference reviewed, registered action IDs, never executable code. */
data class ActionCall(val id: String, val params: Map<String, String> = emptyMap())

data class ActionResult(val success: Boolean, val message: String)

fun interface ActionDispatcher {
    suspend fun dispatch(call: ActionCall): ActionResult
}
