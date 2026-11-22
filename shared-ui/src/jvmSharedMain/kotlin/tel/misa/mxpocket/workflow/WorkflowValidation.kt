package tel.misa.mxpocket.workflow

import java.net.URI
import java.time.LocalTime
import tel.misa.mxpocket.i18n.WorkflowCopy

fun safeHttps(value: String, endpoint: Boolean): Boolean = runCatching {
    val uri = URI(value)
    value.length <= 1024 && uri.scheme.equals("https", true) && !uri.host.isNullOrEmpty() &&
        uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0 &&
        (!endpoint || (uri.rawQuery == null && uri.rawFragment == null))
}.getOrDefault(false)

internal const val MAX_DEFINITIONS = 50
private val identifier = Regex("[a-zA-Z0-9_.-]{1,80}")
fun WorkflowEndpoint.valid() = identifier.matches(id) && name.isNotBlank() && name.length <= 60 &&
    method in setOf("GET", "POST") && safeHttps(url, endpoint = true)

fun validateWorkflow(
    workflow: WorkflowDefinition, definitions: List<WorkflowDefinition>, endpoints: List<WorkflowEndpoint>,
    actions: List<WorkflowActionSpec>,
): String? {
    if (!identifier.matches(workflow.id) || workflow.name.isBlank() || workflow.name.length > 60)
        return WorkflowCopy.NameRequired.text()
    val trigger = workflow.trigger
    if (trigger.kind == TriggerKind.DAILY && (trigger.value.length != 5 ||
            runCatching { LocalTime.parse(trigger.value) }.isFailure)) return WorkflowCopy.TimeRequired.text()
    if (trigger.kind == TriggerKind.CONTEXT && (trigger.value.isBlank() || trigger.value.length > 100))
        return WorkflowCopy.ContextRequired.text()
    if (workflow.steps.isEmpty()) return WorkflowCopy.StepRequired.text()
    val library = definitions.associateBy { it.id } + (workflow.id to workflow)
    var count = 0
    fun check(steps: List<WorkflowStep>, depth: Int, chain: Set<String>, path: String): String? {
        if (depth > MAX_DEPTH || chain.size > MAX_DEPTH) return WorkflowCopy.DepthLimit.text()
        for ((i, step) in steps.withIndex()) {
            if (++count > MAX_STEPS) return WorkflowCopy.StepLimit.text(MAX_STEPS)
            val position = "$path${i+1}"
            val valid = when (step.kind) {
                StepKind.WAIT -> step.value.toLongOrNull() in 1L..120L
                StepKind.CONFIRM, StepKind.SPEAK, StepKind.NOTIFY -> step.value.isNotBlank() && step.value.length <= 240
                StepKind.SET -> identifier.matches(step.target) && step.value.length <= 240
                StepKind.OPEN_URL -> safeHttps(step.value, endpoint = false)
                StepKind.REQUEST -> endpoints.any { it.valid() && it.id == step.target &&
                    step.retries in 0..2 && (it.method == "GET" || step.retries == 0) }
                StepKind.ACTION -> {
                    val spec = actions.firstOrNull { it.id == step.target && it.id != "workflow.run" }
                    spec != null && step.params.keys == spec.fields.map { it.key }.toSet() && spec.fields.all {
                        val value = step.params[it.key].orEmpty()
                        value.isNotBlank() && value.length <= 240 && (it.choices.isEmpty() || value in it.choices)
                    }
                }
                StepKind.CALL -> {
                    if (step.target in chain) return WorkflowCopy.LoopAt.text(position)
                    val callee = library[step.target] ?: return WorkflowCopy.ChooseAt.text(position)
                    check(callee.steps, depth, chain + step.target, "$position / ")?.let { return it }
                    callee.steps.isNotEmpty()
                }
                StepKind.IF -> {
                    val t = step.test
                    check(step.yes, depth + 1, chain, WorkflowCopy.YesPath.text(position))?.let { return it }
                    check(step.no, depth + 1, chain, WorkflowCopy.NoPath.text(position))?.let { return it }
                    (step.yes.isNotEmpty() || step.no.isNotEmpty()) && when (t.kind) {
                        TestKind.PREVIOUS, TestKind.CONNECTED -> t.value in setOf("true", "false")
                        TestKind.BOOLEAN -> t.key in setOf("true", "false") && t.value in setOf("true", "false")
                        TestKind.CONTEXT -> t.value.isNotBlank() && t.value.length <= 100
                        TestKind.VARIABLE, TestKind.PLUGIN -> identifier.matches(t.key) && t.value.length <= 240
                    }
                }
            }
            if (!valid) return WorkflowCopy.CheckStep.text(position, step.kind.label)
        }
        return null
    }
    return check(workflow.steps, 1, setOf(workflow.id), "")
}
