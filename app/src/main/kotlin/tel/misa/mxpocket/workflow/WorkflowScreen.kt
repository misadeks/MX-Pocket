package tel.misa.mxpocket.workflow

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun WorkflowScreen(controller: WorkflowController) {
    val saved by controller.definitions.collectAsStateWithLifecycle()
    val history by controller.history.collectAsStateWithLifecycle()
    val status by controller.status.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val endpoints by controller.endpoints.collectAsStateWithLifecycle()
    val prompt by controller.effects.prompt.collectAsStateWithLifecycle()
    val activity = LocalContext.current.activity()
    var section by rememberSaveable { mutableStateOf("library") }
    var draft by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var initial by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun open(definition: WorkflowDefinition) {
        initial = definition; draft = definition; section = "edit"; message = null
    }
    fun close() {
        if (!saving) {
            if (section == "edit" && draft != initial) discard = true
            else { section = "library"; message = null }
        }
    }
    // Transient editor state can be discarded on process recreation; never turn it into a saved definition.
    val view = if (section == "edit" && draft == null) "library" else section
    BackHandler(enabled = view != "library") { close() }
    val running = status?.phase in setOf(WorkflowPhase.RUNNING, WorkflowPhase.CONFIRMATION)
    key(view) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view != "library") TextButton(onClick = ::close, enabled = !saving) { Text("Back to workflows") }
            Text(when (view) {
                "edit" -> "Workflow editor"; "history" -> "Run history"; "endpoints" -> "Approved endpoints"
                else -> "Workflows"
            }, style = MaterialTheme.typography.headlineSmall)
            error?.let { ErrorLine(it) }
            status?.let { state ->
                if (running || view == "edit") {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                            Text(state.name, style = MaterialTheme.typography.titleSmall)
                            Text("${if (state.step > 0) "Step ${state.step}: " else ""}${state.message}")
                            if (running) TextButton(onClick = controller::cancel) { Text("Cancel run") }
                        }
                    }
                }
            }
            message?.let { ErrorLine(it) }
            when (view) {
                "edit" -> draft?.let { current ->
                    WorkflowEditor(current, controller.actions(), saved, endpoints) { draft = it; message = null }
                    HorizontalDivider()
                    ActionRow {
                        Button(enabled = !saving, onClick = {
                            saving = true
                            scope.launch {
                                try {
                                    val failure = controller.save(current)
                                    message = failure
                                    if (failure == null) { initial = current; section = "library" }
                                } finally { saving = false }
                            }
                        }) { Text("Save workflow") }
                        OutlinedButton(enabled = !running && !saving, onClick = {
                            message = controller.validate(current)
                            if (message == null && !controller.test(current)) message = "A workflow is already running."
                        }) { Text("Test run") }
                    }
                    Text("Test runs execute the steps shown, including unsaved changes.",
                        style = MaterialTheme.typography.bodySmall)
                }
                "history" -> {
                    if (history.isEmpty()) Text("No runs yet. Test a workflow or run one from the keypad.")
                    for (entry in history) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text("${entry.message} · ${entry.step} steps", style = MaterialTheme.typography.bodyMedium)
                        Text("${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.timestamp))} · ${entry.origin.name.lowercase()}",
                            style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
                "endpoints" -> EndpointPanel(controller, endpoints)
                else -> {
                    Text("Build a sequence, test it here, then assign it to a keypad key.")
                    ActionRow {
                        Button(onClick = { open(WorkflowDefinition()) }) { Text("New workflow") }
                        TextButton(onClick = { section = "history"; message = null }) { Text("Run history") }
                        TextButton(onClick = { section = "endpoints"; message = null }) { Text("Endpoints") }
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
                            OutlinedButton(enabled = !running, onClick = {
                                message = controller.validate(workflow)
                                if (message == null && !controller.run(workflow.id, WorkflowOrigin.MANUAL))
                                    message = "The workflow could not start."
                            }) { Text("Run") }
                        }
                        ActionRow {
                            TextButton(onClick = { open(workflow) }) { Text("Edit") }
                            TextButton(onClick = { open(controller.duplicate(workflow)) }) { Text("Duplicate") }
                            TextButton(onClick = { remove = workflow }) { Text("Delete") }
                        }
                        if (!running && status?.workflowId == workflow.id) Text(status!!.message,
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (discard) DecisionDialog("Discard changes?", "Your saved workflow will not change.", "Discard",
        { discard = false; section = "library"; message = null }, { discard = false })
    remove?.let { target -> DecisionDialog("Delete ${target.name}?", "Keys assigned to this workflow will no longer run it.",
        "Delete", { remove = null; scope.launch { message = controller.delete(target.id) } }, { remove = null }) }
    prompt?.let { request -> DecisionDialog("Workflow confirmation", request.text, "Allow",
        { controller.effects.respond(request, true, activity) }, { controller.effects.respond(request, false, activity) }) }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
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
private fun EndpointPanel(controller: WorkflowController, endpoints: List<WorkflowEndpoint>) {
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
        TextButton(onClick = { scope.launch { message = controller.removeEndpoint(endpoint.id) } }) { Text("Revoke") }
    }
    HorizontalDivider()
    Text("Approve an endpoint", style = MaterialTheme.typography.titleMedium)
    Field("Endpoint name", name) { name = it }
    Field("Exact HTTPS address", url) { url = it }
    Choice("Method", method, listOf("GET", "POST"), { it }) { method = it }
    message?.let { ErrorLine(it) }
    Button(onClick = {
        val endpoint = WorkflowEndpoint(name = name.trim(), url = url.trim(), method = method)
        if (endpoint.valid()) pending = endpoint else message = "Enter a name and an exact HTTPS address without credentials or query."
    }) { Text("Review endpoint") }
    pending?.let { endpoint ->
        DecisionDialog("Approve ${endpoint.method} requests?", endpoint.url, "Approve", {
            pending = null
            scope.launch {
                message = controller.addEndpoint(endpoint)
                if (message == null) { name = ""; url = "" }
            }
        }, { pending = null })
    }
}
