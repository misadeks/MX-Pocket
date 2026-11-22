package tel.misa.mxpocket.workflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tel.misa.mxpocket.i18n.WorkflowCopy

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
            Text(WorkflowCopy.Choice.text(label, value.ifBlank { WorkflowCopy.Choose.text() }))
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
    endpoints: List<WorkflowEndpoint>, canApproveEndpoints: Boolean, edit: (WorkflowDefinition) -> Unit,
) {
    Field(WorkflowCopy.WorkflowName.text(), draft.name) { edit(draft.copy(name = it.take(60))) }
    Choice(WorkflowCopy.Trigger.text(), draft.trigger.kind.label, TriggerKind.entries, { it.label }) {
        edit(draft.copy(trigger = WorkflowTrigger(it, if (it == TriggerKind.DAILY) "09:00" else "")))
    }
    if (draft.trigger.kind in listOf(TriggerKind.DAILY, TriggerKind.CONTEXT)) {
        Field(if (draft.trigger.kind == TriggerKind.DAILY) WorkflowCopy.LocalTime.text() else WorkflowCopy.AppContext.text(),
            draft.trigger.value) { edit(draft.copy(trigger = draft.trigger.copy(value = it))) }
        if (draft.trigger.kind == TriggerKind.DAILY) Text(WorkflowCopy.ScheduleHint.text(),
            style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    Text(WorkflowCopy.Sequence.text(), style = MaterialTheme.typography.titleLarge)
    val options = EditorOptions(actions, saved.filterNot { it.id == draft.id }, endpoints, canApproveEndpoints)
    StepList(draft.steps, options, 1) { edit(draft.copy(steps = it)) }
}

private data class EditorOptions(
    val actions: List<WorkflowActionSpec>, val workflows: List<WorkflowDefinition>, val endpoints: List<WorkflowEndpoint>,
    val canApproveEndpoints: Boolean,
)

private fun StepKind.make(options: EditorOptions): WorkflowStep = when (this) {
    StepKind.ACTION -> WorkflowStep(this).selectAction(options.actions.firstOrNull())
    StepKind.WAIT -> WorkflowStep(this, value = "1")
    StepKind.CONFIRM -> WorkflowStep(this, value = WorkflowCopy.DefaultPrompt.text())
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
        val action = if (step.kind == StepKind.ACTION) options.actions.find { it.id == step.target } else null
        val detail = when (step.kind) {
            StepKind.IF -> {
                val test = step.test
                val value = if (test.kind.booleanOperand) booleanLabel(test.value) else test.value
                "${test.kind.label} ${if (test.negate) "≠" else "="} $value"
            }
            StepKind.CALL -> options.workflows.find { it.id == step.target }?.name
            StepKind.REQUEST -> options.endpoints.find { it.id == step.target }?.name
            StepKind.WAIT -> step.value.toIntOrNull()?.takeIf { it.toString() == step.value }
                ?.let { WorkflowCopy.Seconds.text(it) } ?: WorkflowCopy.RawSeconds.text(step.value)
            StepKind.ACTION -> step.params.entries.joinToString(" · ") { (key, value) ->
                action?.fields?.firstOrNull { it.key == key }?.choiceLabel(value) ?: value
            }
            StepKind.SET -> "${step.target} = ${step.value}"
            else -> step.value
        }
        Row(Modifier.fillMaxWidth().clickable { expanded = if (active) -1 else index }
            .heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(action?.displayLabel ?: step.kind.label, style = MaterialTheme.typography.titleMedium)
                if (!detail.isNullOrEmpty()) Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(if (active) WorkflowCopy.Close.text() else WorkflowCopy.Edit.text(), style = MaterialTheme.typography.labelLarge)
        }
        if (active) key(index, step.kind) {
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StepSettings(step, options, depth) { updated -> edit(steps.toMutableList().apply { this[index] = updated }) }
                ActionRow {
                    TextButton(onClick = { move(index, index - 1) }, enabled = index > 0) { Text(WorkflowCopy.MoveUp.text()) }
                    TextButton(onClick = { move(index, index + 1) }, enabled = index < steps.lastIndex) { Text(WorkflowCopy.MoveDown.text()) }
                    TextButton(onClick = {
                        expanded = -1
                        edit(steps.toMutableList().apply { removeAt(index) })
                    }) { Text(WorkflowCopy.RemoveStep.text()) }
                }
            }
        }
        HorizontalDivider()
    }
    Box {
        OutlinedButton(onClick = { adding = true }, enabled = steps.size < MAX_STEPS) { Text(WorkflowCopy.AddStep.text()) }
        DropdownMenu(adding, onDismissRequest = { adding = false }) {
            StepKind.entries.filterNot { depth >= MAX_DEPTH && it == StepKind.IF }.forEach { kind ->
                DropdownMenuItem(text = { Text(kind.label) }, onClick = {
                    adding = false; expanded = steps.size; edit(steps + kind.make(options))
                })
            }
        }
    }
}

private val TestKind.booleanOperand get() = this == TestKind.CONNECTED || this == TestKind.BOOLEAN || this == TestKind.PREVIOUS
private fun booleanLabel(token: String) = when (token) {
    "true" -> WorkflowCopy.True.text()
    "false" -> WorkflowCopy.False.text()
    else -> token
}

@Composable
private fun StepSettings(step: WorkflowStep, opts: EditorOptions, depth: Int, edit: (WorkflowStep) -> Unit) {
    when (step.kind) {
        StepKind.IF -> {
            val test = step.test
            Choice(WorkflowCopy.Compare.text(), test.kind.label, TestKind.entries, { it.label }) {
                edit(step.copy(test = WorkflowTest(it, key = if (it == TestKind.BOOLEAN) "true" else "")))
            }
            if (test.kind in listOf(TestKind.PLUGIN, TestKind.VARIABLE))
                Field(if (test.kind == TestKind.PLUGIN) WorkflowCopy.PluginStateName.text() else WorkflowCopy.VariableName.text(), test.key) {
                    edit(step.copy(test = test.copy(key = it)))
                }
            if (test.kind == TestKind.BOOLEAN)
                Choice(WorkflowCopy.BooleanLabel.text(), booleanLabel(test.key), listOf("true", "false"), ::booleanLabel) {
                    edit(step.copy(test = test.copy(key = it)))
                }
            val comparison: (Boolean) -> String = { if (it) WorkflowCopy.NotEqual.text() else WorkflowCopy.Equals.text() }
            Choice(WorkflowCopy.Comparison.text(), comparison(test.negate), listOf(false, true), comparison) {
                edit(step.copy(test = test.copy(negate = it)))
            }
            if (test.kind.booleanOperand)
                Choice(WorkflowCopy.Value.text(), booleanLabel(test.value), listOf("true", "false"), ::booleanLabel) {
                    edit(step.copy(test = test.copy(value = it)))
                }
            else Field(WorkflowCopy.Value.text(), test.value) { edit(step.copy(test = test.copy(value = it))) }
            if (depth < MAX_DEPTH) {
                Text(WorkflowCopy.IfTrue.text(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                StepList(step.yes, opts, depth + 1) { edit(step.copy(yes = it)) }
                Text(WorkflowCopy.Otherwise.text(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                StepList(step.no, opts, depth + 1) { edit(step.copy(no = it)) }
            }
        }
        StepKind.ACTION -> {
            val action = opts.actions.firstOrNull { it.id == step.target }
            Choice(WorkflowCopy.Action.text(), action?.displayLabel.orEmpty(), opts.actions, { it.displayLabel }) { edit(step.selectAction(it)) }
            if (opts.actions.isEmpty()) Text(WorkflowCopy.NoActions.text())
            action?.fields?.forEach { field ->
                val value = step.params[field.key].orEmpty()
                val set: (String) -> Unit = { edit(step.copy(params = step.params + (field.key to it))) }
                if (field.choices.isEmpty()) Field(field.displayLabel, value, set)
                else Choice(field.displayLabel, field.choiceLabel(value), field.choices, field::choiceLabel, set)
            }
            if (action?.requiresConfirmation == true) Text(WorkflowCopy.RequiresApproval.text())
        }
        StepKind.CALL -> {
            Choice(WorkflowCopy.Workflow.text(), opts.workflows.find { it.id == step.target }?.name.orEmpty(), opts.workflows, { it.name }) {
                edit(step.copy(target = it.id))
            }
            if (opts.workflows.isEmpty()) Text(WorkflowCopy.SaveAnother.text())
        }
        StepKind.REQUEST -> {
            val endpoint = opts.endpoints.find { it.id == step.target }
            Choice(WorkflowCopy.Endpoint.text(), endpoint?.name.orEmpty(), opts.endpoints, { "${it.method} · ${it.name}" }) {
                edit(step.copy(target = it.id, retries = 0))
            }
            if (opts.endpoints.isEmpty()) Text(if (opts.canApproveEndpoints) WorkflowCopy.ApproveFirst.text()
                else WorkflowCopy.NoEndpoints.text())
            if (endpoint?.method == "GET") Choice(WorkflowCopy.Retries.text(), "${step.retries}", listOf(0, 1, 2), { "$it" }) {
                edit(step.copy(retries = it))
            }
        }
        StepKind.SET -> {
            Field(WorkflowCopy.VariableName.text(), step.target) { edit(step.copy(target = it)) }
            Field(WorkflowCopy.Value.text(), step.value) { edit(step.copy(value = it)) }
        }
        else -> Field(when (step.kind) {
            StepKind.WAIT -> WorkflowCopy.Duration.text()
            StepKind.CONFIRM -> WorkflowCopy.Prompt.text()
            StepKind.OPEN_URL -> WorkflowCopy.WebAddress.text()
            StepKind.SPEAK -> WorkflowCopy.SpeakText.text()
            else -> WorkflowCopy.NotificationText.text()
        }, step.value) { edit(step.copy(value = it)) }
    }
    if (step.kind in listOf(StepKind.ACTION, StepKind.REQUEST, StepKind.NOTIFY, StepKind.SPEAK)) {
        val behavior: (Boolean) -> String = { if (it) WorkflowCopy.Continue.text() else WorkflowCopy.Stop.text() }
        Choice(WorkflowCopy.OnError.text(), behavior(step.continueOnError), listOf(false, true), behavior) {
            edit(step.copy(continueOnError = it))
        }
    }
}
