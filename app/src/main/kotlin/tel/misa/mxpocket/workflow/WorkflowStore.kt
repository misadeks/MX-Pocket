package tel.misa.mxpocket.workflow

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal data class WorkflowLibrary(
    val version: Int,
    val definitions: List<WorkflowDefinition>,
    val endpoints: List<WorkflowEndpoint> = emptyList(),
)

internal class WorkflowStore(context: Context) {
    private val library = AtomicFile(File(context.filesDir, "workflow-library.json"))
    private val history = AtomicFile(File(context.filesDir, "workflow-history.json"))
    private val json = Json { encodeDefaults = true }
    fun load(): WorkflowLibrary {
        val text = read(library) ?: return WorkflowLibrary(1, listOf(WorkflowDefinition.demo))
        val result = json.decodeFromString<WorkflowLibrary>(text)
        if (result.version != 1 || result.definitions.size > MAX_DEFINITIONS ||
            result.definitions.distinctBy { it.id }.size != result.definitions.size ||
            result.endpoints.size > 32 || result.endpoints.any { !it.valid() } ||
            result.endpoints.distinctBy { it.id }.size != result.endpoints.size)
            throw SerializationException("Unsupported workflow library")
        return result
    }

    fun save(data: WorkflowLibrary) = write(library, json.encodeToString(data))
    fun loadHistory(): List<WorkflowStatus> =
        read(history)?.let { json.decodeFromString<List<WorkflowStatus>>(it).take(30) } ?: emptyList()
    fun saveHistory(entries: List<WorkflowStatus>) = write(history, json.encodeToString(entries.take(30)))

    private fun read(file: AtomicFile): String? {
        val stream = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists()) throw e
            return null
        }
        return stream.use { input ->
            if (input.channel.size() > 1_048_576) throw IOException("Workflow data exceeds size limit")
            input.readBytes().decodeToString()
        }
    }

    private fun write(file: AtomicFile, text: String) {
        val bytes = text.encodeToByteArray()
        if (bytes.size > 1_048_576) throw IOException("Workflow data exceeds size limit")
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }
}
