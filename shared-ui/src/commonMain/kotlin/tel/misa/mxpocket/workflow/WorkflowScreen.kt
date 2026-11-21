package tel.misa.mxpocket.workflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private enum class Section(val title: String) {
    Library("Workflows"), Editor("Workflow editor"), History("Run history"), Endpoints("Approved endpoints")
}

@Composable
fun WorkflowScreen(
    state: WorkflowUiState,
    callbacks: WorkflowCallbacks = WorkflowCallbacks(),
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    val saved = state.definitions
    val status = state.status
    var section by rememberSaveable(stateSaver = Saver(save = { it.name }, restore = { Section.valueOf(it) })) {
        mutableStateOf(Section.Library)
    }
    var draft by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var initial by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun open(definition: WorkflowDefinition) {
        initial = definition; draft = definition; section = Section.Editor; message = null
    }
    fun close() {
        if (!saving) {
            if (section == Section.Editor && draft != initial) discard = true
            else { section = Section.Library; message = null }
        }
    }
    // Transient editor state can be discarded on process recreation; never turn it into a saved definition.
    val view = if (section == Section.Editor && draft == null) Section.Library else section
    backHandler(view != Section.Library, ::close)
    val running = status?.phase in setOf(WorkflowPhase.RUNNING, WorkflowPhase.CONFIRMATION)
    key(view) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view != Section.Library) TextButton(onClick = ::close, enabled = !saving) { Text("Back to workflows") }
            Text(view.title, style = MaterialTheme.typography.headlineSmall)
            state.error?.let { ErrorLine(it) }
            if (callbacks.run == null && callbacks.test == null)
                Text("Workflow execution is unavailable on this host.", style = MaterialTheme.typography.bodySmall)
            status?.let { progress ->
                if (running || view == Section.Editor) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(progress.name, style = MaterialTheme.typography.titleSmall)
                            Text("${if (progress.step > 0) "Step ${progress.step}: " else ""}${progress.message}")
                            if (running) TextButton(enabled = callbacks.cancel != null,
                                onClick = { callbacks.cancel?.invoke() }) { Text("Cancel run") }
                        }
                    }
                }
            }
            message?.let { ErrorLine(it) }
            when (view) {
                Section.Editor -> draft?.let { current ->
                    WorkflowEditor(current, state.actions, saved, state.endpoints, callbacks.endpoints != null) {
                        draft = it; message = null
                    }
                    HorizontalDivider()
                    ActionRow {
                        Button(enabled = !saving && callbacks.save != null, onClick = {
                            val save = callbacks.save ?: return@Button
                            saving = true
                            scope.launch {
                                try {
                                    val failure = save(current)
                                    message = failure
                                    if (failure == null) { initial = current; section = Section.Library }
                                } finally { saving = false }
                            }
                        }) { Text("Save workflow") }
                        OutlinedButton(enabled = !running && !saving && callbacks.test != null, onClick = {
                            message = callbacks.test?.invoke(current)
                        }) { Text("Test run") }
                    }
                    if (callbacks.save == null) Text("Draft edits cannot be saved on this host.",
                        style = MaterialTheme.typography.bodySmall)
                    if (callbacks.test != null) Text("Test runs execute the steps shown, including unsaved changes.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Section.History -> {
                    if (state.history.isEmpty()) Text(if (callbacks.test == null) "No runs yet."
                        else "No runs yet. Test a workflow or run one from the keypad.")
                    for ((entry, timeLabel) in state.history) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text("${entry.message} · ${entry.step} steps", style = MaterialTheme.typography.bodyMedium)
                        Text("$timeLabel · ${entry.origin.name.lowercase()}",
                            style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
                Section.Endpoints -> EndpointPanel(callbacks.endpoints, state.endpoints)
                Section.Library -> {
                    Text(if (callbacks.test != null) "Build a sequence, test it here, then assign it to a keypad key."
                        else "Build and edit workflow sequences.")
                    ActionRow {
                        Button(onClick = { open(WorkflowDefinition()) }) { Text("New workflow") }
                        TextButton(onClick = { section = Section.History; message = null }) { Text("Run history") }
                        TextButton(onClick = { section = Section.Endpoints; message = null }) { Text("Endpoints") }
                    }
                    HorizontalDivider()
                    if (saved.isEmpty()) Text("No saved workflows.")
                    for (workflow in saved) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { open(workflow) }.padding(vertical = 8.dp)) {
                                Text(workflow.name, style = MaterialTheme.typography.titleMedium)
                                Text("${workflow.steps.size} steps · ${workflow.trigger.kind.label}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(enabled = !running && callbacks.run != null, onClick = {
                                message = callbacks.run?.invoke(workflow)
                            }) { Text("Run") }
                        }
                        ActionRow {
                            TextButton(onClick = { open(workflow) }) { Text("Edit") }
                            TextButton(onClick = { open(workflow.duplicate()) }) { Text("Duplicate") }
                            TextButton(enabled = callbacks.delete != null, onClick = { remove = workflow }) { Text("Delete") }
                        }
                        if (!running && status?.workflowId == workflow.id) Text(status.message,
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (discard) DecisionDialog("Discard changes?", "Your saved workflow will not change.", "Discard",
        { discard = false; section = Section.Library; message = null }, { discard = false })
    remove?.let { target -> DecisionDialog("Delete ${target.name}?", "Keys assigned to this workflow will no longer run it.",
        "Delete", { remove = null; scope.launch { message = callbacks.delete?.invoke(target.id) } }, { remove = null }) }
    state.prompt?.let { request ->
        callbacks.respond?.let { respond ->
            DecisionDialog("Workflow confirmation", request.text, "Allow",
                { respond(request, true) }, { respond(request, false) })
        }
    }
}

@Composable
internal fun DecisionDialog(title: String, body: String, action: String, accept: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) },
        confirmButton = { Button(onClick = accept) { Text(action) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
internal fun ErrorLine(text: String) = Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun EndpointPanel(callbacks: EndpointCallbacks?, endpoints: List<WorkflowEndpoint>) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf("GET") }
    var pending by remember { mutableStateOf<WorkflowEndpoint?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    Text("Requests can only use addresses you approve here. Redirects, credentials and query strings are not supported.")
    endpoints.forEach { endpoint ->
        HorizontalDivider()
        Text(endpoint.name, style = MaterialTheme.typography.titleMedium)
        Text("${endpoint.method} ${endpoint.url}", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = callbacks != null, onClick = {
            scope.launch { message = callbacks?.revoke?.invoke(endpoint.id) }
        }) { Text("Revoke") }
    }
    HorizontalDivider()
    if (callbacks == null) {
        Text("Endpoint approval is unavailable on this host.")
        return
    }
    Text("Approve an endpoint", style = MaterialTheme.typography.titleMedium)
    Field("Endpoint name", name) { name = it }
    Field("Exact HTTPS address", url) { url = it }
    Choice("Method", method, listOf("GET", "POST"), { it }) { method = it }
    message?.let { ErrorLine(it) }
    Button(onClick = {
        val endpoint = WorkflowEndpoint(name = name.trim(), url = url.trim(), method = method)
        message = callbacks.validate(endpoint)
        if (message == null) pending = endpoint
    }) { Text("Review endpoint") }
    pending?.let { endpoint ->
        DecisionDialog("Approve ${endpoint.method} requests?", endpoint.url, "Approve", {
            pending = null
            scope.launch {
                message = callbacks.approve(endpoint)
                if (message == null) { name = ""; url = "" }
            }
        }, { pending = null })
    }
}
