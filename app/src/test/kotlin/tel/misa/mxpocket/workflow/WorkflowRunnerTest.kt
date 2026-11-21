package tel.misa.mxpocket.workflow

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowRunnerTest {
    private val seen = mutableListOf<String>()
    private val history = mutableListOf<WorkflowStatus>()
    private val context = WorkflowContext(false, "home", mapOf("media.playing" to "false"))
    private val host = object : WorkflowHost {
        override fun snapshot() = context
        override fun actions() = listOf(WorkflowActionSpec("label", "Label", listOf(WorkflowField("text", "Text"))),
            WorkflowActionSpec("protected", "Protected", requiresConfirmation = true))
        override suspend fun execute(id: String, params: Map<String, String>): Boolean {
            seen += params["text"] ?: id
            return true
        }
    }
    private val effects = object : WorkflowEffects {
        var accept = true
        var requests = 0
        var stall = false
        override suspend fun request(endpoint: WorkflowEndpoint): RequestResult { requests++; return RequestResult.TRANSIENT }
        override suspend fun confirm(prompt: String): Boolean { if (stall) awaitCancellation(); return accept }
        override suspend fun openUrl(url: String) = accept
        override fun notify(text: String): Boolean = throw IllegalStateException("private-address-or-token")
        override suspend fun speak(text: String) = true
    }
    private fun label(text: String) = WorkflowStep(StepKind.ACTION, "label", params = mapOf("text" to text))
    private fun workflow(vararg steps: WorkflowStep) = WorkflowDefinition(id = "root", steps = steps.toList())

    @Test fun nestedCallsShareVariablesAndRecoverByBranchingOnPreviousResult() = runTest {
        val child = workflow(WorkflowStep(StepKind.SET, target = "ready", value = "yes")).copy(id = "child")
        val branch = WorkflowStep(StepKind.IF, test = WorkflowTest(TestKind.VARIABLE, "ready", "yes"),
            yes = listOf(WorkflowStep(StepKind.NOTIFY, value = "message", continueOnError = true),
                WorkflowStep(StepKind.IF, test = WorkflowTest(TestKind.PREVIOUS, value = "false"),
                    yes = listOf(label("recovered")), no = listOf(label("wrong")))))
        val root = workflow(WorkflowStep(StepKind.CALL, target = child.id), branch)
        WorkflowRunner(this, host, effects, history::add).start(root, WorkflowOrigin.VIRTUAL, listOf(root, child), emptyList())!!.join()
        assertEquals(listOf("recovered"), seen)
        assertEquals(WorkflowPhase.FAILED, history.single().phase)
        assertFalse(history.single().toString().contains("private-address"))
    }

    @Test fun invalidRecursionIsRejectedBeforeAnyAction() = runTest {
        val child = workflow(WorkflowStep(StepKind.CALL, target = "root")).copy(id = "child")
        val root = workflow(label("first"), WorkflowStep(StepKind.CALL, target = "child"))
        WorkflowRunner(this, host, effects, history::add).start(root, WorkflowOrigin.PHYSICAL, listOf(root, child), emptyList())!!.join()
        assertTrue(seen.isEmpty())
        assertEquals(FailureKind.INVALID, history.single().error)
    }
    @Test fun denialCannotBeSwallowedAndTimeoutOrCancellationStopsLaterSteps() = runTest {
        val runner = WorkflowRunner(this, host, effects, history::add)
        val root = workflow(WorkflowStep(StepKind.ACTION, "protected", continueOnError = true), label("after"))
        effects.accept = false
        runner.start(root, WorkflowOrigin.SCHEDULE, listOf(root), emptyList())!!.join()
        assertEquals(FailureKind.DECLINED, history.last().error)
        effects.stall = true
        runner.start(root, WorkflowOrigin.MANUAL, listOf(root), emptyList())!!.join()
        assertEquals(FailureKind.TIMEOUT, history.last().error)
        val job = runner.start(root, WorkflowOrigin.TEST, listOf(root), emptyList())!!
        runCurrent()
        assertEquals(WorkflowPhase.CONFIRMATION, runner.status.value!!.phase)
        assertNull(runner.start(root, WorkflowOrigin.VIRTUAL, listOf(root), emptyList()))
        runner.cancel(); job.join()
        assertEquals(WorkflowPhase.CANCELLED, history.last().phase)
        assertTrue(seen.isEmpty())
    }
    @Test fun responseLostAfterPostIsNeverRetriedAndGetHasHardRetryBound() = runTest {
        val runner = WorkflowRunner(this, host, effects, history::add)
        val endpoint = WorkflowEndpoint("test", "Endpoint", "https://example.test/action", "POST")
        val step = WorkflowStep(StepKind.REQUEST, target = "test")
        suspend fun run(step: WorkflowStep, endpoint: WorkflowEndpoint) {
            val definition = workflow(step)
            runner.start(definition, WorkflowOrigin.TEST, listOf(definition), listOf(endpoint))!!.join()
        }
        run(step.copy(retries = 1), endpoint)
        assertEquals(FailureKind.INVALID, history.last().error)
        assertEquals(0, effects.requests)
        run(step, endpoint)
        assertEquals(FailureKind.NETWORK, history.last().error)
        assertEquals(1, effects.requests)
        run(step.copy(retries = 2), endpoint.copy(method = "GET"))
        assertEquals(4, effects.requests)
        run(step.copy(retries = 3), endpoint.copy(method = "GET"))
        assertEquals(FailureKind.INVALID, history.last().error)
        assertEquals(4, effects.requests)
    }
    @Test fun onlyExplicitlyApprovedHttpsEndpointsCanRun() = runTest {
        for (value in listOf("http://example.test", "file:///path", "https://example.test:0/a",
            "https://u:p@example.test", "https://example.test?token=x", "https://example.test#part")) {
            assertFalse(value, WorkflowEndpoint(name = "Endpoint", url = value).valid())
        }
        val root = workflow(WorkflowStep(StepKind.REQUEST, target = "https://example.test"))
        WorkflowRunner(this, host, effects, history::add).start(root, WorkflowOrigin.TEST, listOf(root), emptyList())!!.join()
        assertEquals(FailureKind.INVALID, history.last().error)
        assertEquals(0, effects.requests)
    }
    @Test fun localDailyTimeIsRecomputedAcrossDaylightSavingChanges() {
        val before = ZonedDateTime.of(2025, 3, 29, 20, 0, 0, 0, ZoneId.of("Europe/Prague"))
        val next = nextOccurrence(LocalTime.of(8, 30), before)
        assertEquals(8, next.hour)
        assertEquals(30, next.minute)
        assertNotEquals(before.offset, next.offset)
    }
}
