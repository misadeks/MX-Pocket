package tel.misa.mxpocket.workflow

import java.net.URI
import java.time.LocalTime

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
        return "Give the workflow a name of up to 60 characters."
    val trigger = workflow.trigger
    if (trigger.kind == TriggerKind.DAILY && (trigger.value.length != 5 ||
            runCatching { LocalTime.parse(trigger.value) }.isFailure)) return "Enter a daily time as HH:mm."
    if (trigger.kind == TriggerKind.CONTEXT && (trigger.value.isBlank() || trigger.value.length > 100))
        return "Enter the context to watch."
    if (workflow.steps.isEmpty()) return "Add at least one step."
    val library = definitions.associateBy { it.id } + (workflow.id to workflow)
    var count = 0
    fun check(steps: List<WorkflowStep>, depth: Int, chain: Set<String>, path: String): String? {
        if (depth > MAX_DEPTH || chain.size > MAX_DEPTH) return "Use at most four levels of branches and workflow calls."
        for ((i, step) in steps.withIndex()) {
            if (++count > MAX_STEPS) return "Use at most $MAX_STEPS steps, including branches and called workflows."
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
                    if (step.target in chain) return "Step $position would call a workflow in a loop."
                    val callee = library[step.target] ?: return "Choose a saved workflow at step $position."
                    check(callee.steps, depth, chain + step.target, "$position / ")?.let { return it }
                    callee.steps.isNotEmpty()
                }
                StepKind.IF -> {
                    val t = step.test
                    check(step.yes, depth + 1, chain, "$position yes / ")?.let { return it }
                    check(step.no, depth + 1, chain, "$position no / ")?.let { return it }
                    (step.yes.isNotEmpty() || step.no.isNotEmpty()) && when (t.kind) {
                        TestKind.PREVIOUS, TestKind.CONNECTED -> t.value in setOf("true", "false")
                        TestKind.BOOLEAN -> t.key in setOf("true", "false") && t.value in setOf("true", "false")
                        TestKind.CONTEXT -> t.value.isNotBlank() && t.value.length <= 100
                        TestKind.VARIABLE, TestKind.PLUGIN -> identifier.matches(t.key) && t.value.length <= 240
                    }
                }
            }
            if (!valid) return "Check step $position: ${step.kind.label}."
        }
        return null
    }
    return check(workflow.steps, 1, setOf(workflow.id), "")
}
