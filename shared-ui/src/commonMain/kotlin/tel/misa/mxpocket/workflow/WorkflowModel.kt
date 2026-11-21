package tel.misa.mxpocket.workflow

import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

data class WorkflowActionSpec(
    val id: String,
    val label: String,
    val fields: List<WorkflowField> = emptyList(),
    val requiresConfirmation: Boolean = false,
)
data class WorkflowField(val key: String, val label: String, val choices: List<String> = emptyList())

enum class WorkflowOrigin { MANUAL, PHYSICAL, VIRTUAL, SCHEDULE, CONTEXT, TEST }
enum class WorkflowPhase { RUNNING, CONFIRMATION, SUCCESS, FAILED, CANCELLED }
enum class FailureKind(val description: String) {
    INVALID("Check the workflow settings"), UNAVAILABLE("Action unavailable or not permitted"),
    NETWORK("Endpoint request failed"), TIMEOUT("Time limit reached"), LIMIT("Execution limit reached"),
    DECLINED("Confirmation declined"), BUSY("Another workflow is running"),
}

@Serializable
@OptIn(kotlin.time.ExperimentalTime::class)
data class WorkflowStatus(
    val workflowId: String,
    val name: String,
    val origin: WorkflowOrigin,
    val phase: WorkflowPhase,
    val message: String,
    val step: Int = 0,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
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
)

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

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun newId() = Uuid.random().toString()

@Serializable
data class WorkflowDefinition(
    val id: String = newId(),
    val name: String = "Untitled workflow",
    val trigger: WorkflowTrigger = WorkflowTrigger(),
    val steps: List<WorkflowStep> = emptyList(),
) {
    fun duplicate() = copy(id = newId(), name = "${name.take(55)} copy", trigger = WorkflowTrigger())

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
    val id: String = newId(),
    val name: String,
    val url: String,
    val method: String = "GET",
)

const val MAX_STEPS = 64
const val MAX_DEPTH = 4
