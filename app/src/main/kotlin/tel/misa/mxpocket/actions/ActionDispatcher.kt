package tel.misa.mxpocket.actions

data class ActionResult(val success: Boolean, val message: String)

fun interface ActionDispatcher {
    suspend fun dispatch(call: ActionCall): ActionResult
}
