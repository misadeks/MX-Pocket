package tel.misa.mxpocket.workflow

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Version 1 on-disk format used by the Android and desktop host adapters. */
@Serializable
data class WorkflowLibrary(
    val version: Int,
    val definitions: List<WorkflowDefinition>,
    val endpoints: List<WorkflowEndpoint> = emptyList(),
) {
    fun encode() = json.encodeToString(this)

    suspend fun saveDefinition(
        draft: WorkflowDefinition,
        actions: List<WorkflowActionSpec>,
        persist: suspend (WorkflowLibrary) -> String?,
    ): String? {
        validateWorkflow(draft, definitions, endpoints, actions)?.let { return it }
        val new = definitions.none { it.id == draft.id }
        if (new && definitions.size >= MAX_DEFINITIONS) return "The library is full."
        return persist(copy(definitions = if (new) definitions + draft
            else definitions.map { if (it.id == draft.id) draft else it }))
    }

    suspend fun deleteDefinition(id: String, persist: suspend (WorkflowLibrary) -> String?): String? {
        fun uses(step: WorkflowStep): Boolean = (step.kind == StepKind.CALL && step.target == id) ||
            step.yes.any(::uses) || step.no.any(::uses)
        if (definitions.any { it.id != id && it.steps.any(::uses) }) return "Another workflow uses this one."
        return persist(copy(definitions = definitions.filterNot { it.id == id }))
    }

    companion object {
        const val MAX_BYTES = 1_048_576
        private val json = Json { encodeDefaults = true }
        fun initial() = WorkflowLibrary(1, listOf(WorkflowDefinition.demo))

        fun decode(text: String): WorkflowLibrary {
            val result = json.decodeFromString<WorkflowLibrary>(text)
            if (result.version != 1 || result.definitions.size > MAX_DEFINITIONS ||
                result.definitions.distinctBy { it.id }.size != result.definitions.size ||
                result.endpoints.size > 32 || result.endpoints.any { !it.valid() } ||
                result.endpoints.distinctBy { it.id }.size != result.endpoints.size)
                throw SerializationException("Unsupported workflow library")
            return result
        }
    }
}
