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
import tel.misa.mxpocket.i18n.WorkflowCopy
import tel.misa.mxpocket.workflow.*

/**
 * The workflow editor and keypad binding catalog share [state] from one store per window. Bindings use
 * `workflow.run` with the saved definition's stable `workflowId`. The desktop catalog supports
 * editing and validation only; it cannot run or schedule workflows or approve endpoints.
 */
class DesktopWorkflowStore(
    directory: File,
    actions: List<WorkflowActionSpec> = listOf(
        WorkflowActionSpec("sound.play", WorkflowCopy.PlaySound),
        WorkflowActionSpec("keypad.status", WorkflowCopy.SetKeyStatus, listOf(
            WorkflowField("key", WorkflowCopy.KeyIndex, (0..8).map(Int::toString)),
            WorkflowField("text", WorkflowCopy.StatusText))),
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
        error = if (library == null) WorkflowCopy.ReadFailed else null,
    ))
    val state = snapshot.asStateFlow()

    suspend fun save(draft: WorkflowDefinition): String? = writer.withLock {
        val data = library ?: return@withLock WorkflowCopy.Unreadable.text()
        data.saveDefinition(draft, snapshot.value.actions, ::persist)
    }

    suspend fun delete(id: String): String? = writer.withLock {
        val data = library ?: return@withLock WorkflowCopy.Unreadable.text()
        data.deleteDefinition(id, ::persist)
    }

    private suspend fun persist(next: WorkflowLibrary): String? = withContext(Dispatchers.IO) {
        val bytes = next.encode().encodeToByteArray()
        if (bytes.size > WorkflowLibrary.MAX_BYTES) return@withContext WorkflowCopy.SizeLimit.text()
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
            return@withContext WorkflowCopy.SaveStorage.text()
        } catch (_: SecurityException) {
            return@withContext WorkflowCopy.SavePermissions.text()
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
