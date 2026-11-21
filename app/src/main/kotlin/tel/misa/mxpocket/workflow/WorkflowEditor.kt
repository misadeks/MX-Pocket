package tel.misa.mxpocket.workflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
internal fun <T> Choice(label: String, value: String, choices: List<T>, title: (T) -> String, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = choices.isNotEmpty()) {
            Text("$label: ${value.ifBlank { "Choose" }}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { item ->
                DropdownMenuItem(text = { Text(title(item)) }, onClick = { expanded = false; select(item) })
            }
        }
    }
}

@Composable
internal fun WorkflowEditor(
    draft: WorkflowDefinition, actions: List<WorkflowActionSpec>, saved: List<WorkflowDefinition>,
    endpoints: List<WorkflowEndpoint>, edit: (WorkflowDefinition) -> Unit,
) {
    Field("Workflow name", draft.name) { edit(draft.copy(name = it.take(60))) }
    Choice("Trigger", draft.trigger.kind.label, TriggerKind.entries, { it.label }) {
        edit(draft.copy(trigger = WorkflowTrigger(it, if (it == TriggerKind.DAILY) "09:00" else "")))
    }
    if (draft.trigger.kind in listOf(TriggerKind.DAILY, TriggerKind.CONTEXT)) {
        Field(if (draft.trigger.kind == TriggerKind.DAILY) "Local time (HH:mm)" else "App context",
            draft.trigger.value) { edit(draft.copy(trigger = draft.trigger.copy(value = it))) }
        if (draft.trigger.kind == TriggerKind.DAILY) Text("Android may delay scheduled runs to save power.",
            style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    Text("Sequence", style = MaterialTheme.typography.titleLarge)
    val options = EditorOptions(actions, saved.filterNot { it.id == draft.id }, endpoints)
    StepList(draft.steps, options, 1) { edit(draft.copy(steps = it)) }
}

private data class EditorOptions(
    val actions: List<WorkflowActionSpec>, val workflows: List<WorkflowDefinition>, val endpoints: List<WorkflowEndpoint>,
)

private fun StepKind.make(options: EditorOptions): WorkflowStep = when (this) {
    StepKind.ACTION -> WorkflowStep(this).selectAction(options.actions.firstOrNull())
    StepKind.WAIT -> WorkflowStep(this, value = "1")
    StepKind.CONFIRM -> WorkflowStep(this, value = "Continue with this workflow?")
    StepKind.CALL -> WorkflowStep(this, target = options.workflows.firstOrNull()?.id.orEmpty())
    StepKind.REQUEST -> WorkflowStep(this, target = options.endpoints.firstOrNull()?.id.orEmpty())
    else -> WorkflowStep(this)
}

private fun WorkflowStep.selectAction(action: WorkflowActionSpec?) = copy(target = action?.id.orEmpty(),
    params = action?.fields.orEmpty().associate { it.key to it.choices.firstOrNull().orEmpty() })

@Composable
private fun StepList(steps: List<WorkflowStep>, options: EditorOptions, depth: Int, edit: (List<WorkflowStep>) -> Unit) {
    var expanded by remember { mutableIntStateOf(-1) }
    var adding by remember { mutableStateOf(false) }
    fun move(from: Int, to: Int) {
        edit(steps.toMutableList().apply { add(to, removeAt(from)) })
        expanded = to
    }
    steps.forEachIndexed { index, step ->
        val active = expanded == index
        val label = if (step.kind == StepKind.ACTION) options.actions.find { it.id == step.target }?.label else null
        val detail = when (step.kind) {
            StepKind.IF -> "${step.test.kind.label} ${if (step.test.negate) "≠" else "="} ${step.test.value}"
            StepKind.CALL -> options.workflows.find { it.id == step.target }?.name
            StepKind.REQUEST -> options.endpoints.find { it.id == step.target }?.name
            StepKind.WAIT -> "${step.value} seconds"
            StepKind.ACTION -> step.params.values.joinToString(" · ")
            StepKind.SET -> "${step.target} = ${step.value}"
            else -> step.value
        }
        Row(Modifier.fillMaxWidth().clickable { expanded = if (active) -1 else index }
            .heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(label ?: step.kind.label, style = MaterialTheme.typography.titleMedium)
                if (!detail.isNullOrEmpty()) Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(if (active) "Close" else "Edit", style = MaterialTheme.typography.labelLarge)
        }
        if (active) key(index, step.kind) {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StepSettings(step, options, depth) { updated -> edit(steps.toMutableList().apply { this[index] = updated }) }
                ActionRow {
                    TextButton(onClick = { move(index, index - 1) }, enabled = index > 0) { Text("Move up") }
                    TextButton(onClick = { move(index, index + 1) }, enabled = index < steps.lastIndex) { Text("Move down") }
                    TextButton(onClick = {
                        expanded = -1
                        edit(steps.toMutableList().apply { removeAt(index) })
                    }) { Text("Remove step") }
                }
            }
        }
        HorizontalDivider()
    }
    Box {
        OutlinedButton(onClick = { adding = true }, enabled = steps.size < MAX_STEPS) { Text("Add step") }
        DropdownMenu(adding, onDismissRequest = { adding = false }) {
            StepKind.entries.filterNot { depth >= MAX_DEPTH && it == StepKind.IF }.forEach { kind ->
                DropdownMenuItem(text = { Text(kind.label) }, onClick = {
                    adding = false; expanded = steps.size; edit(steps + kind.make(options))
                })
            }
        }
    }
}

@Composable
private fun StepSettings(step: WorkflowStep, opts: EditorOptions, depth: Int, edit: (WorkflowStep) -> Unit) {
    when (step.kind) {
        StepKind.IF -> {
            val test = step.test
            Choice("Compare", test.kind.label, TestKind.entries, { it.label }) {
                edit(step.copy(test = WorkflowTest(it, key = if (it == TestKind.BOOLEAN) "true" else "")))
            }
            if (test.kind in listOf(TestKind.PLUGIN, TestKind.VARIABLE))
                Field(if (test.kind == TestKind.PLUGIN) "Plugin state name" else "Variable name", test.key) {
                    edit(step.copy(test = test.copy(key = it)))
                }
            if (test.kind == TestKind.BOOLEAN)
                Choice("Boolean", test.key, listOf("true", "false"), { it }) { edit(step.copy(test = test.copy(key = it))) }
            Choice("Comparison", if (test.negate) "Not equal to" else "Equals", listOf("Equals", "Not equal to"), { it }) {
                edit(step.copy(test = test.copy(negate = it != "Equals")))
            }
            if (test.kind in listOf(TestKind.CONNECTED, TestKind.BOOLEAN, TestKind.PREVIOUS))
                Choice("Value", test.value, listOf("true", "false"), { it }) { edit(step.copy(test = test.copy(value = it))) }
            else Field("Value", test.value) { edit(step.copy(test = test.copy(value = it))) }
            if (depth < MAX_DEPTH) {
                Text("If true", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                StepList(step.yes, opts, depth + 1) { edit(step.copy(yes = it)) }
                Text("Otherwise", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                StepList(step.no, opts, depth + 1) { edit(step.copy(no = it)) }
            }
        }
        StepKind.ACTION -> {
            val action = opts.actions.firstOrNull { it.id == step.target }
            Choice("Action", action?.label.orEmpty(), opts.actions, { it.label }) { edit(step.selectAction(it)) }
            if (opts.actions.isEmpty()) Text("No registered actions are available.")
            action?.fields?.forEach { field ->
                val value = step.params[field.key].orEmpty()
                val set: (String) -> Unit = { edit(step.copy(params = step.params + (field.key to it))) }
                if (field.choices.isEmpty()) Field(field.label, value, set)
                else Choice(field.label, value, field.choices, { it }, set)
            }
            if (action?.requiresConfirmation == true) Text("Requires approval on the phone.")
        }
        StepKind.CALL -> {
            Choice("Workflow", opts.workflows.find { it.id == step.target }?.name.orEmpty(), opts.workflows, { it.name }) {
                edit(step.copy(target = it.id))
            }
            if (opts.workflows.isEmpty()) Text("Save another workflow first.")
        }
        StepKind.REQUEST -> {
            val endpoint = opts.endpoints.find { it.id == step.target }
            Choice("Endpoint", endpoint?.name.orEmpty(), opts.endpoints, { "${it.method} · ${it.name}" }) {
                edit(step.copy(target = it.id, retries = 0))
            }
            if (opts.endpoints.isEmpty()) Text("Approve an address in Workflows → Endpoints first.")
            if (endpoint?.method == "GET") Choice("Retries", "${step.retries}", listOf(0, 1, 2), { "$it" }) {
                edit(step.copy(retries = it))
            }
        }
        StepKind.SET -> {
            Field("Variable name", step.target) { edit(step.copy(target = it)) }
            Field("Value", step.value) { edit(step.copy(value = it)) }
        }
        else -> Field(when (step.kind) {
            StepKind.WAIT -> "Duration in seconds (1–120)"
            StepKind.CONFIRM -> "Confirmation prompt"
            StepKind.OPEN_URL -> "HTTPS web address"
            StepKind.SPEAK -> "Text to speak"
            else -> "Notification text"
        }, step.value) { edit(step.copy(value = it)) }
    }
    if (step.kind in listOf(StepKind.ACTION, StepKind.REQUEST, StepKind.NOTIFY, StepKind.SPEAK)) {
        Choice("On error", if (step.continueOnError) "Continue" else "Stop", listOf("Stop", "Continue"), { it }) {
            edit(step.copy(continueOnError = it == "Continue"))
        }
    }
}
