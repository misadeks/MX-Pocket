package tel.misa.mxpocket.workflow

interface WorkflowHost {
    fun actions(): List<WorkflowActionSpec>
    fun snapshot(): WorkflowContext
    suspend fun execute(id: String, params: Map<String, String>): Boolean
}

data class WorkflowContext(
    val connected: Boolean = false,
    val appContext: String = "",
    val pluginState: Map<String, String> = emptyMap(),
)

internal fun WorkflowTest.matches(context: WorkflowContext, vars: Map<String, String>, previous: Boolean): Boolean {
    val actual = when (kind) {
        TestKind.CONNECTED -> context.connected.toString()
        TestKind.CONTEXT -> context.appContext
        TestKind.PLUGIN -> context.pluginState[key]
        TestKind.VARIABLE -> vars[key]
        TestKind.PREVIOUS -> previous.toString()
        TestKind.BOOLEAN -> key
    } ?: return false
    return (actual == value) != negate
}
