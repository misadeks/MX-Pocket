package tel.misa.mxpocket.workflow

data class WorkflowUiState(
    val definitions: List<WorkflowDefinition> = emptyList(),
    val actions: List<WorkflowActionSpec> = emptyList(),
    val endpoints: List<WorkflowEndpoint> = emptyList(),
    val history: List<WorkflowHistoryEntry> = emptyList(),
    val status: WorkflowStatus? = null,
    val error: String? = null,
    val prompt: WorkflowPrompt? = null,
)

data class WorkflowHistoryEntry(val status: WorkflowStatus, val timeLabel: String)
class WorkflowPrompt(val text: String, val openUrl: String? = null)

/**
 * Absent adapters mean the host cannot perform that operation. Draft editing is always local;
 * a host must validate before saving/starting, and publish successful writes in [WorkflowUiState].
 * Commands returning a string use null for success and a displayable message for failure.
 */
data class WorkflowCallbacks(
    val save: (suspend (WorkflowDefinition) -> String?)? = null,
    val delete: (suspend (String) -> String?)? = null,
    val run: ((WorkflowDefinition) -> String?)? = null,
    val test: ((WorkflowDefinition) -> String?)? = null,
    val cancel: (() -> Unit)? = null,
    val respond: ((WorkflowPrompt, Boolean) -> Unit)? = null,
    val endpoints: EndpointCallbacks? = null,
)

data class EndpointCallbacks(
    val validate: (WorkflowEndpoint) -> String?,
    val approve: suspend (WorkflowEndpoint) -> String?,
    val revoke: suspend (String) -> String?,
)
