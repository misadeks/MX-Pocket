package tel.misa.mxpocket.preview

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import tel.misa.mxpocket.i18n.AppLanguage
import tel.misa.mxpocket.i18n.AppLocale
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

    @Test fun switchingLanguageDoesNotRewriteWorkflowDataOrActionValues() = withDirectory { dir ->
        val previous = AppLocale.current
        try {
            AppLocale.update(AppLanguage.FRENCH, emptyList())
            val store = DesktopWorkflowStore(dir)
            val action = store.state.value.actions.first { it.id == "keypad.status" }
            val payload = "Ready · {0} · {1}"
            val draft = WorkflowDefinition(id = "saved", name = "{0} copy", steps = listOf(
                WorkflowStep(StepKind.ACTION, action.id, params = mapOf("key" to "0", "text" to payload)),
                WorkflowStep(StepKind.CONFIRM, value = payload),
            ))
            runBlocking { assertNull(store.save(draft)) }
            val saved = store.state.value.definitions
            assertEquals("Définir l’état de la touche", action.displayLabel)
            assertEquals("Copie de {0} copy", draft.duplicate().name)
            val prompt = WorkflowPrompt.post("{0}")
            AppLocale.update(AppLanguage.SERBIAN_LATIN, emptyList())
            assertEquals("Postavi stanje tastera", action.displayLabel)
            assertEquals("Poslati POST zahtev na odredište {0}?", prompt.text)
            assertEquals(payload, WorkflowPrompt(payload).text)
            assertEquals(saved, DesktopWorkflowStore(dir).state.value.definitions)
            assertEquals(draft, saved.single { it.id == draft.id })
        } finally {
            AppLocale.update(previous, emptyList())
        }
    }

    @Test fun legacyHistoryUsesTheSelectedLanguageWithoutRewritingUnknownMessages() {
        val previous = AppLocale.current
        try {
            AppLocale.update(AppLanguage.SERBIAN_LATIN, emptyList())
            val entry = WorkflowStatus("saved", "Completed", WorkflowOrigin.VIRTUAL, WorkflowPhase.FAILED,
                "Finished with 22 handled errors", error = FailureKind.UNAVAILABLE)
            assertEquals("Završeno uz 22 obrađene greške", entry.displayMessage())
            assertEquals("Completed", entry.name)
            val unknown = "Finished with 22 handled errors {0}"
            assertEquals(unknown, entry.copy(message = unknown).displayMessage())
            assertEquals("VIRTUAL", entry.origin.name)
        } finally {
            AppLocale.update(previous, emptyList())
        }
    }
}
