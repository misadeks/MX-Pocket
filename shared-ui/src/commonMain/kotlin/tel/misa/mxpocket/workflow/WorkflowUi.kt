package tel.misa.mxpocket.workflow

import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.i18n.WorkflowCopy

data class WorkflowUiState(
    val definitions: List<WorkflowDefinition> = emptyList(),
    val actions: List<WorkflowActionSpec> = emptyList(),
    val endpoints: List<WorkflowEndpoint> = emptyList(),
    val history: List<WorkflowHistoryEntry> = emptyList(),
    val status: WorkflowStatus? = null,
    val error: Copy? = null,
    val prompt: WorkflowPrompt? = null,
)

data class WorkflowHistoryEntry(val status: WorkflowStatus, val timeLabel: String)
class WorkflowPrompt private constructor(val openUrl: String?, private val render: (List<WorkflowActionSpec>) -> String) {
    constructor(text: String) : this(null, { text })
    val text get() = render(emptyList())
    fun displayText(actions: List<WorkflowActionSpec>) = render(actions)

    companion object {
        fun action(spec: WorkflowActionSpec) = WorkflowPrompt(null) { actions ->
            WorkflowCopy.AllowAction.text(actions.firstOrNull { it.id == spec.id }?.displayLabel ?: spec.displayLabel)
        }
        fun post(name: String) = WorkflowPrompt(null) { WorkflowCopy.SendPost.text(name) }
        fun browser(url: String) = WorkflowPrompt(url) { WorkflowCopy.OpenBrowser.text(url) }
    }
}

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
