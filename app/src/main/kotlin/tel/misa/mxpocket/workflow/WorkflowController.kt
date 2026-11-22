package tel.misa.mxpocket.workflow

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import tel.misa.mxpocket.i18n.Copy
import tel.misa.mxpocket.i18n.WorkflowCopy

interface WorkflowProvider { val workflows: WorkflowController? }

/** One application-owned instance, called on the main thread, including scheduled deliveries. */
class WorkflowController internal constructor(context: Context, private val scope: CoroutineScope, private val host: WorkflowHost) {
    private val app = context.applicationContext
    private val store = WorkflowStore(app)
    private val alerts = MutableStateFlow<Copy?>(null)
    val error = alerts.asStateFlow()
    private fun load(): WorkflowLibrary? = try {
        store.load()
    } catch (_: IOException) { null } catch (_: SerializationException) { null }
    private var library = load()
    private val saved = MutableStateFlow(library?.definitions.orEmpty())
    val definitions = saved.asStateFlow()
    private val allowed = MutableStateFlow(library?.endpoints.orEmpty())
    val endpoints = allowed.asStateFlow()
    internal val effects = AndroidWorkflowEffects(app)
    private val historyState = MutableStateFlow(try {
        store.loadHistory()
    } catch (_: IOException) { historyLoadError() } catch (_: SerializationException) { historyLoadError() })
    val history = historyState.asStateFlow()
    private val historyWrites = Channel<List<WorkflowStatus>>(Channel.CONFLATED)
    private val writer = Mutex()
    private val runner = WorkflowRunner(scope, host, effects, ::record)
    val status = runner.status
    private var lastContext: WorkflowContext? = null
    private fun historyLoadError(): List<WorkflowStatus> {
        alerts.value = WorkflowCopy.HistoryRead
        return emptyList()
    }
    init {
        if (library == null) alerts.value = WorkflowCopy.ReadFailed
        scope.launch {
            for (entries in historyWrites) {
                try { withContext(Dispatchers.IO) { store.saveHistory(entries) } }
                catch (_: IOException) { alerts.value = WorkflowCopy.HistoryWrite }
            }
        }
        saved.value.filter { it.trigger.kind == TriggerKind.DAILY }.forEach { queueDaily(app, it) }
    }

    private fun record(entry: WorkflowStatus) {
        val entries = (listOf(entry) + historyState.value).take(30)
        historyState.value = entries
        historyWrites.trySend(entries)
    }
    fun actions() = host.actions().filter { it.id != "workflow.run" }
    fun validate(draft: WorkflowDefinition) = validateWorkflow(draft, saved.value, allowed.value, actions())
    private suspend fun persist(next: WorkflowLibrary): String? {
        try { withContext(Dispatchers.IO) { store.save(next) } }
        catch (_: IOException) { return WorkflowCopy.SaveStorage.text() }
        library = next
        saved.value = next.definitions
        allowed.value = next.endpoints
        return null
    }
    suspend fun save(draft: WorkflowDefinition): String? = writer.withLock {
        val data = library ?: return@withLock WorkflowCopy.Unreadable.text()
        data.saveDefinition(draft, actions(), ::persist)?.let { return@withLock it }
        if (draft.trigger.kind == TriggerKind.DAILY) queueDaily(app, draft)
        null
    }
    suspend fun delete(id: String): String? = writer.withLock {
        val data = library ?: return@withLock WorkflowCopy.Unreadable.text()
        data.deleteDefinition(id, ::persist)
    }
    fun duplicate(draft: WorkflowDefinition) = draft.duplicate()
    suspend fun addEndpoint(endpoint: WorkflowEndpoint): String? = writer.withLock {
        val data = library ?: return@withLock WorkflowCopy.Unreadable.text()
        if (!endpoint.valid()) return@withLock WorkflowCopy.EndpointInvalid.text()
        if (data.endpoints.size >= 32) return@withLock WorkflowCopy.EndpointFull.text()
        if (data.endpoints.any { it.id == endpoint.id || it.name.equals(endpoint.name, true) })
            return@withLock WorkflowCopy.EndpointDuplicate.text()
        persist(data.copy(endpoints = data.endpoints + endpoint))
    }
    suspend fun removeEndpoint(id: String): String? = writer.withLock {
        val data = library ?: return@withLock WorkflowCopy.Unreadable.text()
        persist(data.copy(endpoints = data.endpoints.filterNot { it.id == id })).also {
            if (it == null && runner.busy) runner.cancel() // A running snapshot may still carry the revoked endpoint.
        }
    }

    fun run(id: String, origin: WorkflowOrigin): Boolean {
        val definition = saved.value.find { it.id == id } ?: return false
        val trigger = definition.trigger.kind
        // Old libraries may contain virtual-only bindings; do not turn them into physical triggers.
        if (origin == WorkflowOrigin.PHYSICAL && trigger == TriggerKind.MANUAL_ONLY) return false
        return start(definition, origin) != null
    }
    internal fun test(draft: WorkflowDefinition) = start(draft, WorkflowOrigin.TEST) != null
    internal fun start(definition: WorkflowDefinition, origin: WorkflowOrigin): Job? {
        if (runner.busy) {
            record(WorkflowStatus(definition.id, definition.name, origin, WorkflowPhase.FAILED, "").failed(FailureKind.BUSY))
            return null
        }
        return runner.start(definition, origin, saved.value, allowed.value)
    }
    fun cancel() { runner.cancel() }

    fun onContextChanged(context: WorkflowContext) {
        val before = lastContext
        lastContext = context
        if (before == null || before == context || runner.busy) return // Never feed a run's own changes back into itself.
        val matching = saved.value.filter {
            when (it.trigger.kind) {
                TriggerKind.CONNECTED -> !before.connected && context.connected
                TriggerKind.DISCONNECTED -> before.connected && !context.connected
                TriggerKind.CONTEXT -> before.appContext != context.appContext && context.appContext == it.trigger.value
                else -> false
            }
        }
        if (matching.isNotEmpty()) scope.launch {
            matching.forEach { matched ->
                if (matched == saved.value.find { it.id == matched.id }) start(matched, WorkflowOrigin.CONTEXT)?.join()
            }
        }
    }
}
