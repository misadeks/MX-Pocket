package tel.misa.mxpocket.preview

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import tel.misa.mxpocket.workflow.*

class DesktopWorkflowStoreTest {
    private fun withDirectory(block: (File) -> Unit) {
        val parent = File("build/tests/workflows").toPath()
        val dir = Files.createTempDirectory(Files.createDirectories(parent), "library-").toFile()
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    @Test fun editorPersistsValidatedDraftsAndProtectsCalledWorkflows() = withDirectory { dir ->
        runBlocking {
            val store = DesktopWorkflowStore(dir)
            val child = WorkflowDefinition.demo.duplicate()
            assertNull(store.save(child))
            val parent = WorkflowDefinition(steps = listOf(WorkflowStep(StepKind.IF,
                yes = listOf(WorkflowStep(StepKind.CALL, target = child.id)))))
            assertNull(store.save(parent))
            val persisted = store.state.value.definitions
            assertNotNull(store.save(child.copy(steps = listOf(WorkflowStep(StepKind.CALL, target = parent.id)))))
            assertNotNull(store.delete(child.id))
            assertEquals(persisted, DesktopWorkflowStore(dir).state.value.definitions)
            assertNull(store.delete(parent.id))
            assertNull(store.delete(child.id))
            assertEquals(listOf(WorkflowDefinition.demo), DesktopWorkflowStore(dir).state.value.definitions)
        }
    }

    @Test fun futureFormatIsNeverOverwrittenAndNoEndpointGrantIsInvented() = withDirectory { dir ->
        runBlocking {
            val store = DesktopWorkflowStore(dir)
            val draft = WorkflowDefinition(steps = listOf(WorkflowStep(StepKind.REQUEST, target = "unknown")))
            assertNotNull(store.save(draft))
            val file = File(dir, "workflow-library.json")
            val future = """{"version":2,"definitions":[]}"""
            file.writeText(future)
            val unreadable = DesktopWorkflowStore(dir)
            assertNotNull(unreadable.state.value.error)
            assertNotNull(unreadable.save(WorkflowDefinition.demo))
            assertNotNull(unreadable.delete(WorkflowDefinition.demo.id))
            assertEquals(future, file.readText())
        }
    }
}
