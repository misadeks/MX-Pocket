package tel.misa.mxpocket.actions

/** A reference to a registered action. Stored bindings are data, not executable code. */
data class ActionCall(val id: String, val params: Map<String, String> = emptyMap())
