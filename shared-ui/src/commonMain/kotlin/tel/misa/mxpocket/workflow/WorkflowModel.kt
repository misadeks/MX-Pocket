package tel.misa.mxpocket.workflow

import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.i18n.WorkflowCopy

/** Release policy for both hosts. This is intentionally not a user preference. */
const val WORKFLOWS_ENABLED = false

data class WorkflowActionSpec(
    val id: String,
    val label: String,
    val fields: List<WorkflowField> = emptyList(),
    val requiresConfirmation: Boolean = false,
    private val labelCopy: Copy? = null,
) {
    constructor(id: String, label: Copy, fields: List<WorkflowField> = emptyList(), requiresConfirmation: Boolean = false) :
        this(id, label.en, fields, requiresConfirmation, label)

    val displayLabel get() = labelCopy?.text() ?: label
}
data class WorkflowField(
    val key: String, val label: String, val choices: List<String> = emptyList(),
    val choiceLabels: Map<String, String> = emptyMap(),
    private val labelCopy: Copy? = null,
) {
    constructor(key: String, label: Copy, choices: List<String> = emptyList(), choiceLabels: Map<String, String> = emptyMap()) :
        this(key, label.en, choices, choiceLabels, label)
    val displayLabel get() = labelCopy?.text() ?: label
    fun choiceLabel(value: String) = if (value in choices) choiceLabels[value] ?: value else value
}

enum class WorkflowOrigin(private val copy: Copy) {
    MANUAL(WorkflowCopy.OriginManual), PHYSICAL(WorkflowCopy.OriginPhysical), VIRTUAL(WorkflowCopy.OriginVirtual),
    SCHEDULE(WorkflowCopy.OriginSchedule), CONTEXT(WorkflowCopy.OriginContext), TEST(WorkflowCopy.OriginTest);
    val label: String get() = copy.text()
}
enum class WorkflowPhase(private val copy: Copy) {
    RUNNING(WorkflowCopy.PhaseRunning), CONFIRMATION(WorkflowCopy.PhaseConfirmation), SUCCESS(WorkflowCopy.PhaseSuccess),
    FAILED(WorkflowCopy.PhaseFailed), CANCELLED(WorkflowCopy.PhaseCancelled);
    val label: String get() = copy.text()
}
enum class FailureKind(internal val copy: Copy) {
    INVALID(WorkflowCopy.Invalid), UNAVAILABLE(WorkflowCopy.Unavailable), NETWORK(WorkflowCopy.Network),
    TIMEOUT(WorkflowCopy.Timeout), LIMIT(WorkflowCopy.ExecutionLimit), DECLINED(WorkflowCopy.Declined), BUSY(WorkflowCopy.Busy);
    val description: String get() = copy.text()
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
    @Transient val liveCopy: Copy? = null,
    @Transient val stepKind: StepKind? = null,
    @Transient val actionId: String? = null,
) {
    /** History keeps its existing on-disk format. Only recognize terminal messages the runner actually writes. */
    fun displayMessage(actions: List<WorkflowActionSpec> = emptyList()): String {
        liveCopy?.let { return it.text() }
        stepKind?.let { kind ->
            return if (kind == StepKind.ACTION && actionId != null)
                actions.firstOrNull { it.id == actionId }?.displayLabel ?: message
            else kind.label
        }
        return when (phase) {
            WorkflowPhase.CANCELLED -> if (message == WorkflowCopy.Cancelled.en) WorkflowCopy.Cancelled.text() else message
            WorkflowPhase.SUCCESS -> if (message == WorkflowCopy.Completed.en) WorkflowCopy.Completed.text() else message
            WorkflowPhase.FAILED -> when {
                error == null -> message
                message == error.copy.en -> error.description
                error == FailureKind.UNAVAILABLE -> handledErrorCount(message)?.let { WorkflowCopy.HandledErrors.text(it) } ?: message
                else -> message
            }
            else -> message
        }
    }

    fun withCopy(copy: Copy, phase: WorkflowPhase = this.phase, error: FailureKind? = null) =
        this.copy(phase = phase, message = copy.en, liveCopy = copy.takeIf {
            phase == WorkflowPhase.RUNNING || phase == WorkflowPhase.CONFIRMATION
        }, error = error, stepKind = null, actionId = null)

    fun failed(kind: FailureKind) = withCopy(kind.copy, WorkflowPhase.FAILED, kind)

    fun completed(handledErrors: Int) = if (handledErrors == 0) withCopy(WorkflowCopy.Completed, WorkflowPhase.SUCCESS)
        else copy(phase = WorkflowPhase.FAILED, error = FailureKind.UNAVAILABLE,
            message = "Finished with $handledErrors handled errors", liveCopy = null, stepKind = null, actionId = null)

    private fun handledErrorCount(stored: String): Int? {
        // Old history uses the plural English suffix even for one error. Never translate arbitrary history text.
        val n = stored.substringAfter("Finished with ", "").substringBefore(' ').toIntOrNull() ?: return null
        return n.takeIf { it in 1..MAX_STEPS && stored == "Finished with $it handled errors" }
    }
}

enum class TriggerKind(private val copy: Copy) {
    ON_DEMAND(WorkflowCopy.OnDemand), PHYSICAL(WorkflowCopy.PhysicalKey),
    @SerialName("VIRTUAL") MANUAL_ONLY(WorkflowCopy.ManualOnly),
    DAILY(WorkflowCopy.Daily), CONNECTED(WorkflowCopy.OnConnect), DISCONNECTED(WorkflowCopy.OnDisconnect),
    CONTEXT(WorkflowCopy.OnContext);
    val label: String get() = copy.text()
}

@Serializable
data class WorkflowTrigger(val kind: TriggerKind = TriggerKind.ON_DEMAND, val value: String = "")

enum class StepKind(private val copy: Copy) {
    ACTION(WorkflowCopy.StepAction), WAIT(WorkflowCopy.Wait), IF(WorkflowCopy.IfElse), CONFIRM(WorkflowCopy.Ask),
    SET(WorkflowCopy.SetVariable), CALL(WorkflowCopy.Call), REQUEST(WorkflowCopy.Request), OPEN_URL(WorkflowCopy.OpenPage),
    NOTIFY(WorkflowCopy.Notify), SPEAK(WorkflowCopy.Speak);
    val label: String get() = copy.text()
}

enum class TestKind(private val copy: Copy) {
    CONNECTED(WorkflowCopy.KeypadConnected), CONTEXT(WorkflowCopy.AppContext), PLUGIN(WorkflowCopy.PluginState),
    VARIABLE(WorkflowCopy.VariableValue), PREVIOUS(WorkflowCopy.PreviousSucceeded), BOOLEAN(WorkflowCopy.BooleanValue);
    val label: String get() = copy.text()
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
    fun duplicate(): WorkflowDefinition {
        val available = 60 - WorkflowCopy.CopyName.text("").length
        return copy(id = newId(), name = WorkflowCopy.CopyName.text(name.take(available)), trigger = WorkflowTrigger())
    }

    companion object {
        // Seed copy only when a new library is created. Loaded names and step payloads are always user data.
        val demo get() = WorkflowDefinition("keypad-check", WorkflowCopy.KeypadCheck.text(), steps = listOf(
            WorkflowStep(StepKind.IF, test = WorkflowTest(), yes = listOf(status(WorkflowCopy.Connected.text())),
                no = listOf(status(WorkflowCopy.NotConnected.text()))),
            WorkflowStep(StepKind.WAIT, value = "2"),
            WorkflowStep(StepKind.ACTION, target = "sound.play"),
            status(WorkflowCopy.Ready.text()),
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
