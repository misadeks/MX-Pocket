package tel.misa.mxpocket.workflow

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WorkflowLibraryTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val host = object : WorkflowHost {
        override fun snapshot() = WorkflowContext()
        override fun actions() = emptyList<WorkflowActionSpec>()
        override suspend fun execute(id: String, params: Map<String, String>) = false
    }

    @Test fun libraryRoundTripsBranchesEndpointsAndHistoryAndDoesNotOverwriteNewerVersions() {
        val store = WorkflowStore(app)
        val data = WorkflowLibrary(1, listOf(WorkflowDefinition.demo),
            listOf(WorkflowEndpoint("local", "Local", "https://example.test/check")))
        store.save(data)
        assertEquals(data, store.load())
        val entry = WorkflowStatus("keypad-check", "Keypad check", WorkflowOrigin.VIRTUAL, WorkflowPhase.SUCCESS, "Completed")
        store.saveHistory(List(35) { entry })
        assertEquals(List(30) { entry }, store.loadHistory())
        val library = File(app.filesDir, "workflow-library.json")
        val future = """{"version":42,"definitions":[]}"""
        library.writeText(future)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = WorkflowController(app, scope, host)
            assertNotNull(controller.error.value)
            assertNotNull(runBlocking { controller.save(WorkflowDefinition.demo) })
            assertEquals(future, library.readText())
        } finally { scope.cancel() }
    }

    @Test fun physicalAndVirtualBindingsAndContextEdgesUseTheSameRunner() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = WorkflowController(app, scope, host)
            val step = WorkflowStep(StepKind.SET, "flag", "on")
            val root = WorkflowDefinition("physical", "Example", WorkflowTrigger(TriggerKind.PHYSICAL), listOf(step))
            val edge = root.copy(id = "edge", trigger = WorkflowTrigger(TriggerKind.CONNECTED))
            runBlocking { assertNull(controller.save(root)); assertNull(controller.save(edge)) }
            assertFalse(controller.run(root.id, WorkflowOrigin.VIRTUAL))
            assertTrue(controller.run(root.id, WorkflowOrigin.PHYSICAL))
            assertEquals(WorkflowPhase.SUCCESS, controller.status.value?.phase)
            assertTrue(controller.run(root.id, WorkflowOrigin.MANUAL))
            controller.onContextChanged(WorkflowContext())
            controller.onContextChanged(WorkflowContext(connected = true))
            controller.onContextChanged(WorkflowContext(connected = true))
            assertEquals(listOf(WorkflowOrigin.CONTEXT, WorkflowOrigin.MANUAL, WorkflowOrigin.PHYSICAL),
                controller.history.value.map { it.origin })
            assertEquals(WorkflowTrigger(), controller.duplicate(edge).trigger)
        } finally { scope.cancel() }
    }
}
