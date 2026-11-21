package tel.misa.mxpocket.workflow

import java.net.URI
import java.time.LocalTime
import java.util.UUID
import kotlinx.serialization.Serializable

interface WorkflowHost {
    fun actions(): List<WorkflowActionSpec>
    fun snapshot(): WorkflowContext
    suspend fun execute(id: String, params: Map<String, String>): Boolean
}

data class WorkflowActionSpec(
    val id: String,
    val label: String,
    val fields: List<WorkflowField> = emptyList(),
    val requiresConfirmation: Boolean = false,
)
data class WorkflowField(val key: String, val label: String, val choices: List<String> = emptyList())
data class WorkflowContext(
    val connected: Boolean = false,
    val appContext: String = "",
    val pluginState: Map<String, String> = emptyMap(),
)
enum class WorkflowOrigin { MANUAL, PHYSICAL, VIRTUAL, SCHEDULE, CONTEXT, TEST }
enum class WorkflowPhase { RUNNING, CONFIRMATION, SUCCESS, FAILED, CANCELLED }
enum class FailureKind(val description: String) {
    INVALID("Check the workflow settings"), UNAVAILABLE("Action unavailable or not permitted"),
    NETWORK("Endpoint request failed"), TIMEOUT("Time limit reached"), LIMIT("Execution limit reached"),
    DECLINED("Confirmation declined"), BUSY("Another workflow is running"),
}

@Serializable
data class WorkflowStatus(
    val workflowId: String,
    val name: String,
    val origin: WorkflowOrigin,
    val phase: WorkflowPhase,
    val message: String,
    val step: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val error: FailureKind? = null,
)

enum class TriggerKind(val label: String) {
    ON_DEMAND("Manual or assigned key"), PHYSICAL("Physical key only"), VIRTUAL("Virtual key only"),
    DAILY("Daily schedule"), CONNECTED("Keypad connects"), DISCONNECTED("Keypad disconnects"),
    CONTEXT("App context becomes"),
}

@Serializable
data class WorkflowTrigger(val kind: TriggerKind = TriggerKind.ON_DEMAND, val value: String = "")

enum class StepKind(val label: String) {
    ACTION("Keypad or plugin action"), WAIT("Wait"), IF("If / else"), CONFIRM("Ask for confirmation"),
    SET("Set a variable"), CALL("Run a workflow"), REQUEST("Request an endpoint"),
    OPEN_URL("Open a web page"), NOTIFY("Show a notification"), SPEAK("Speak text"),
}

enum class TestKind(val label: String) {
    CONNECTED("Keypad connected"), CONTEXT("App context"), PLUGIN("Plugin state"),
    VARIABLE("Variable value"), PREVIOUS("Previous action succeeded"), BOOLEAN("Boolean value"),
}

@Serializable
data class WorkflowTest(
    val kind: TestKind = TestKind.CONNECTED,
    val key: String = "",
    val value: String = "true",
    val negate: Boolean = false,
) {
    internal fun matches(context: WorkflowContext, vars: Map<String, String>, previous: Boolean): Boolean {
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
}

@Serializable
data class WorkflowStep(
    val kind: StepKind,
    val target: String = "",
    val value: String = "",
    val params: Map<String, String> = emptyMap(),
    val test: WorkflowTest = WorkflowTest(),
    val yes: List<WorkflowStep> = emptyList(),
    val no: List<WorkflowStep> = emptyList(),
    val retries: Int = 0,
    val continueOnError: Boolean = false,
)

@Serializable
data class WorkflowDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled workflow",
    val trigger: WorkflowTrigger = WorkflowTrigger(),
    val steps: List<WorkflowStep> = emptyList(),
) {
    companion object {
        val demo = WorkflowDefinition("keypad-check", "Keypad check", steps = listOf(
            WorkflowStep(StepKind.IF, test = WorkflowTest(), yes = listOf(status("Connected")),
                no = listOf(status("Virtual keypad"))),
            WorkflowStep(StepKind.WAIT, value = "2"),
            WorkflowStep(StepKind.ACTION, target = "sound.play"),
            status("Ready"),
        ))
        private fun status(text: String) = WorkflowStep(StepKind.ACTION, target = "keypad.status",
            params = mapOf("key" to "0", "text" to text))
    }
}

@Serializable
data class WorkflowEndpoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val method: String = "GET",
)

internal fun safeHttps(value: String, endpoint: Boolean): Boolean = runCatching {
    val uri = URI(value)
    value.length <= 1024 && uri.scheme.equals("https", true) && !uri.host.isNullOrEmpty() &&
        uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0 &&
        (!endpoint || (uri.rawQuery == null && uri.rawFragment == null))
}.getOrDefault(false)

internal const val MAX_DEFINITIONS = 50
internal const val MAX_STEPS = 64
internal const val MAX_DEPTH = 4
internal val identifier = Regex("[a-zA-Z0-9_.-]{1,80}")
internal fun WorkflowEndpoint.valid() = identifier.matches(id) && name.isNotBlank() && name.length <= 60 &&
    method in setOf("GET", "POST") && safeHttps(url, endpoint = true)

internal fun validateWorkflow(
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
