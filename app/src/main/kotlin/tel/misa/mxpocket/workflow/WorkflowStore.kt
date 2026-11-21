package tel.misa.mxpocket.workflow

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.serialization.json.Json

internal class WorkflowStore(context: Context) {
    private val library = AtomicFile(File(context.filesDir, "workflow-library.json"))
    private val history = AtomicFile(File(context.filesDir, "workflow-history.json"))
    private val json = Json { encodeDefaults = true }
    fun load(): WorkflowLibrary {
        return read(library)?.let(WorkflowLibrary::decode) ?: WorkflowLibrary.initial()
    }

    fun save(data: WorkflowLibrary) = write(library, data.encode())
    fun loadHistory(): List<WorkflowStatus> =
        read(history)?.let { json.decodeFromString<List<WorkflowStatus>>(it).take(30) } ?: emptyList()
    fun saveHistory(entries: List<WorkflowStatus>) = write(history, json.encodeToString(entries.take(30)))

    private fun read(file: AtomicFile): String? {
        val stream = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists()) throw e
            return null
        }
        return stream.use { input ->
            if (input.channel.size() > WorkflowLibrary.MAX_BYTES) throw IOException("Workflow data exceeds size limit")
            input.readBytes().decodeToString()
        }
    }

    private fun write(file: AtomicFile, text: String) {
        val bytes = text.encodeToByteArray()
        if (bytes.size > WorkflowLibrary.MAX_BYTES) throw IOException("Workflow data exceeds size limit")
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
