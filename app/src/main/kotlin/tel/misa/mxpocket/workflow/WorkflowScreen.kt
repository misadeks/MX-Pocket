package tel.misa.mxpocket.workflow

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import tel.misa.mxpocket.R
import tel.misa.mxpocket.ui.PocketButton
import tel.misa.mxpocket.ui.PocketCard
import tel.misa.mxpocket.ui.PocketColors
import tel.misa.mxpocket.ui.PocketDivider
import tel.misa.mxpocket.ui.PocketDot
import tel.misa.mxpocket.ui.PocketIcon
import tel.misa.mxpocket.ui.PocketInner
import tel.misa.mxpocket.ui.PocketLabel
import tel.misa.mxpocket.ui.PocketTone
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
            }, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            error?.let { ErrorLine(it) }
            status?.let { state ->
                if (running || view == "edit") {
                    Surface(color = PocketColors.card, shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, if (running) PocketColors.accentStroke else PocketColors.cardStroke)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = PocketColors.accent)
                                Column(Modifier.weight(1f)) {
                                    Text(state.name, color = Color.White, style = MaterialTheme.typography.titleSmall)
                                    Text("${if (state.step > 0) "Step ${state.step}: " else ""}${state.message}",
                                        color = PocketColors.muted, style = MaterialTheme.typography.bodyMedium)
                                }
                                if (running) PocketButton("Cancel run", controller::cancel, tone = PocketTone.DANGER)
                            }
                            if (running) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PocketIcon(R.drawable.ic_pocket_warning, PocketColors.warning, 14.dp)
                                Text("Cancelling does not undo completed actions.", color = PocketColors.muted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
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
                    Text("Build a sequence, test it here, then assign it to a keypad key.", color = PocketColors.muted,
                        style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PocketButton("New workflow", { open(WorkflowDefinition()) }, Modifier.weight(1.2f),
                            icon = R.drawable.ic_pocket_plus, tone = PocketTone.SUCCESS, compact = true)
                        PocketButton("Run history", { section = "history"; message = null }, Modifier.weight(1f),
                            icon = R.drawable.ic_pocket_history, compact = true)
                        PocketButton("Endpoints", { section = "endpoints"; message = null }, Modifier.weight(1f),
                            icon = R.drawable.ic_pocket_list, compact = true)
                    }
                    PocketLabel("Configured workflows (${saved.size})", Modifier.padding(top = 8.dp))
                    if (saved.isEmpty()) Text("No saved workflows.", color = PocketColors.muted)
                    val actionLabels = controller.actions().associate { it.id to it.label }
                    for (workflow in saved) {
                        val active = status?.workflowId == workflow.id
                        PocketCard {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.clickable(onClickLabel = "Edit ${workflow.name}") { open(workflow) }) {
                                PocketDot(if (active && running) PocketColors.accent else PocketColors.faint)
                                Column(Modifier.weight(1f)) {
                                    Text(workflow.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                    Text("${workflow.steps.size} steps · ${workflow.trigger.kind.label}",
                                        color = PocketColors.muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (workflow.steps.isNotEmpty()) PocketInner {
                                workflow.steps.take(4).forEachIndexed { index, step ->
                                    val name = if (step.kind == StepKind.ACTION) actionLabels[step.target] ?: step.kind.label
                                        else step.kind.label
                                    Text("${index + 1}. $name", color = PocketColors.text, maxLines = 1,
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                                if (workflow.steps.size > 4) Text("+${workflow.steps.size - 4} more", color = PocketColors.faint,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (!running && active) Text(status!!.message,
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = PocketColors.muted,
                                style = MaterialTheme.typography.labelLarge)
                            PocketDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PocketButton("Run", {
                                    message = controller.validate(workflow)
                                    if (message == null && !controller.run(workflow.id, WorkflowOrigin.MANUAL))
                                        message = "The workflow could not start."
                                }, Modifier.weight(1f), icon = R.drawable.ic_pocket_play, tone = PocketTone.SUCCESS,
                                    enabled = !running, compact = true)
                                PocketButton("Edit", { open(workflow) }, Modifier.weight(1f), icon = R.drawable.ic_pocket_edit,
                                    compact = true)
                                PocketButton("Copy", { open(controller.duplicate(workflow)) }, Modifier.weight(1f),
                                    icon = R.drawable.ic_pocket_copy, compact = true)
                                PocketButton("Delete", { remove = workflow }, Modifier.weight(1.1f),
                                    icon = R.drawable.ic_pocket_delete, tone = PocketTone.DANGER, compact = true)
                            }
                        }
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content)
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
