package tel.misa.mxpocket.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal interface WorkflowEffects {
    suspend fun request(endpoint: WorkflowEndpoint): RequestResult
    suspend fun speak(text: String): Boolean
    fun notify(text: String): Boolean
    suspend fun confirm(prompt: String): Boolean
    suspend fun openUrl(url: String): Boolean
}
internal enum class RequestResult { SUCCESS, REFUSED, TRANSIENT }
internal class StepFailure(val kind: FailureKind) : Exception(kind.description)

/** Confined to the same main scope as the host. Each run snapshots definitions and approved endpoints. */
internal class WorkflowRunner(
    private val scope: CoroutineScope,
    private val host: WorkflowHost,
    private val effects: WorkflowEffects,
    private val finished: (WorkflowStatus) -> Unit,
) {
    private val current = MutableStateFlow<WorkflowStatus?>(null)
    val status = current.asStateFlow()
    private var active: Job? = null
    val busy get() = active?.isCompleted == false
    fun cancel() { active?.cancel() }

    fun start(
        workflow: WorkflowDefinition, origin: WorkflowOrigin,
        definitions: List<WorkflowDefinition>, endpoints: List<WorkflowEndpoint>,
    ): Job? {
        if (busy || !scope.coroutineContext[Job].let { it == null || it.isActive }) return null
        val initial = WorkflowStatus(workflow.id, workflow.name, origin, WorkflowPhase.RUNNING, "Starting")
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var stepNumber = 0
            var handledErrors = 0
            fun progress(label: String, phase: WorkflowPhase = WorkflowPhase.RUNNING) {
                current.value = initial.copy(phase = phase, step = stepNumber, message = label)
            }
            suspend fun confirm(prompt: String) {
                progress("Waiting for confirmation", WorkflowPhase.CONFIRMATION)
                if (!effects.confirm(prompt)) throw StepFailure(FailureKind.DECLINED)
                progress("Confirmed")
            }
            val variables = mutableMapOf<String, String>()
            var previous = true
            val specs = host.actions().associateBy { it.id }
            val library = definitions.associateBy { it.id }
            suspend fun walk(steps: List<WorkflowStep>, chain: Set<String>) {
                for (step in steps) {
                    currentCoroutineContext().ensureActive()
                    if (++stepNumber > MAX_STEPS) throw StepFailure(FailureKind.LIMIT)
                    progress(specs[step.target]?.label.takeIf { step.kind == StepKind.ACTION } ?: step.kind.label)
                    when (step.kind) {
                        StepKind.IF -> {
                            val yes = step.test.matches(host.snapshot(), variables, previous)
                            walk(if (yes) step.yes else step.no, chain)
                        }
                        StepKind.CALL -> {
                            val next = library[step.target] ?: throw StepFailure(FailureKind.INVALID)
                            if (next.id in chain || chain.size >= MAX_DEPTH) throw StepFailure(FailureKind.LIMIT)
                            walk(next.steps, chain + next.id)
                        }
                        else -> {
                            try {
                                when (step.kind) {
                                    StepKind.ACTION -> {
                                        val spec = specs[step.target] ?: throw StepFailure(FailureKind.UNAVAILABLE)
                                        if (spec.requiresConfirmation) confirm("Allow ${spec.label}?")
                                        if (!withTimeout(30_000) { host.execute(step.target, step.params) })
                                            throw StepFailure(FailureKind.UNAVAILABLE)
                                    }
                                    StepKind.WAIT -> delay(step.value.toLong() * 1000)
                                    StepKind.CONFIRM -> confirm(step.value)
                                    StepKind.SET -> variables[step.target] = step.value
                                    StepKind.REQUEST -> {
                                        val endpoint = endpoints.first { it.id == step.target }
                                        if (endpoint.method == "POST") confirm("Send POST to ${endpoint.name}?")
                                        for (attempt in 0..step.retries) {
                                            when (withTimeout(10_000) { effects.request(endpoint) }) {
                                                RequestResult.SUCCESS -> break
                                                RequestResult.REFUSED -> throw StepFailure(FailureKind.NETWORK)
                                                RequestResult.TRANSIENT -> {
                                                    if (attempt == step.retries) throw StepFailure(FailureKind.NETWORK)
                                                    delay(250L shl attempt)
                                                }
                                            }
                                        }
                                    }
                                    StepKind.OPEN_URL -> if (!effects.openUrl(step.value))
                                        throw StepFailure(FailureKind.DECLINED)
                                    StepKind.SPEAK -> if (!withTimeout(30_000) { effects.speak(step.value) })
                                        throw StepFailure(FailureKind.UNAVAILABLE)
                                    StepKind.NOTIFY -> if (!effects.notify(step.value)) throw StepFailure(FailureKind.UNAVAILABLE)
                                    else -> error("Not a leaf")
                                }
                                previous = true
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                val kind = (e as? StepFailure)?.kind ?: FailureKind.UNAVAILABLE
                                if (!step.continueOnError || kind !in setOf(FailureKind.UNAVAILABLE, FailureKind.NETWORK))
                                    throw StepFailure(kind)
                                previous = false
                                handledErrors++
                            }
                        }
                    }
                }
            }
            var terminal = initial.copy(phase = WorkflowPhase.CANCELLED, message = "Cancelled")
            try {
                progress("Validating")
                val error = validateWorkflow(workflow, definitions, endpoints, specs.values.toList())
                if (error != null) throw StepFailure(FailureKind.INVALID)
                // An outer deadline is never restartable by a nested call, branch or retry.
                if (withTimeoutOrNull(180_000) { walk(workflow.steps, setOf(workflow.id)); true } != true)
                    throw StepFailure(FailureKind.TIMEOUT)
                terminal = if (handledErrors == 0) initial.copy(phase = WorkflowPhase.SUCCESS, message = "Completed")
                else initial.copy(phase = WorkflowPhase.FAILED, message = "Finished with $handledErrors handled errors",
                    error = FailureKind.UNAVAILABLE)
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val kind = if (e is TimeoutCancellationException) FailureKind.TIMEOUT
                    else (e as? StepFailure)?.kind ?: FailureKind.UNAVAILABLE
                terminal = initial.copy(phase = WorkflowPhase.FAILED, message = kind.description, error = kind)
            } finally {
                terminal = terminal.copy(step = stepNumber, timestamp = System.currentTimeMillis())
                current.value = terminal
                finished(terminal)
            }
        }
        active = job
        job.start()
        return job
    }
}
