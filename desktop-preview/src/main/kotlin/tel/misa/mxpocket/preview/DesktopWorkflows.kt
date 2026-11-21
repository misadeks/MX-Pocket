package tel.misa.mxpocket.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import tel.misa.mxpocket.workflow.*

/**
 * Own one per desktop window and collect [state] for both the workflow tab and keypad binding catalog.
 * The binding for each saved definition is `workflow.run` with `workflowId` set to its stable ID.
 * The action catalog describes fields for editing/validation only; desktop does not dispatch actions,
 * schedule triggers, start runs, respond to confirmation, or grant endpoint access.
 */
class DesktopWorkflowStore(
    directory: File,
    actions: List<WorkflowActionSpec> = listOf(
        WorkflowActionSpec("sound.play", "Play a sound"),
        WorkflowActionSpec("keypad.status", "Set key status", listOf(
            WorkflowField("key", "Key index (0-8)", (0..8).map(Int::toString)),
            WorkflowField("text", "Status text"))),
    ),
) {
    private val file = directory.toPath().resolve("workflow-library.json")
    private val writer = Mutex()
    private var library: WorkflowLibrary? = try {
        val bytes = Files.newInputStream(file).use { it.readNBytes(WorkflowLibrary.MAX_BYTES + 1) }
        if (bytes.size > WorkflowLibrary.MAX_BYTES) throw IOException("Workflow data exceeds size limit")
        WorkflowLibrary.decode(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (_: NoSuchFileException) {
        WorkflowLibrary.initial()
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: SecurityException) {
        null
    }
    private val snapshot = MutableStateFlow(WorkflowUiState(
        definitions = library?.definitions.orEmpty(), endpoints = library?.endpoints.orEmpty(), actions = actions,
        error = if (library == null) "Cannot read this workflow library. Existing data has not been replaced." else null,
    ))
    val state = snapshot.asStateFlow()

    suspend fun save(draft: WorkflowDefinition): String? = writer.withLock {
        val data = library ?: return@withLock "The workflow library is unreadable."
        data.saveDefinition(draft, snapshot.value.actions, ::persist)
    }

    suspend fun delete(id: String): String? = writer.withLock {
        val data = library ?: return@withLock "The workflow library is unreadable."
        data.deleteDefinition(id, ::persist)
    }

    private suspend fun persist(next: WorkflowLibrary): String? = withContext(Dispatchers.IO) {
        val bytes = next.encode().encodeToByteArray()
        if (bytes.size > WorkflowLibrary.MAX_BYTES) return@withContext "Workflow data exceeds size limit."
        try {
            val parent = Files.createDirectories(file.toAbsolutePath().parent)
            val staged = Files.createTempFile(parent, "workflow-library-", ".new")
            try {
                Files.write(staged, bytes)
                Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(staged)
            }
        } catch (_: IOException) {
            return@withContext "Could not save the library. Check available storage."
        } catch (_: SecurityException) {
            return@withContext "Could not save the library. Check directory permissions."
        }
        library = next
        snapshot.value = snapshot.value.copy(definitions = next.definitions, endpoints = next.endpoints)
        null
    }
}

@Composable
fun DesktopWorkflows(directory: File) = DesktopWorkflows(remember(directory) { DesktopWorkflowStore(directory) })

@Composable
fun DesktopWorkflows(
    store: DesktopWorkflowStore,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    val state by store.state.collectAsState()
    val callbacks = remember(store) { WorkflowCallbacks(save = store::save, delete = store::delete) }
    WorkflowScreen(state, callbacks, backHandler)
}
