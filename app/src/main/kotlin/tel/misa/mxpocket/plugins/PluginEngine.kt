package tel.misa.mxpocket.plugins

import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tel.misa.mxpocket.decks.DeckState

/** Call every method on the owner's UI thread; plugin scope inherits that same dispatcher. */
class PluginEngine(
    plugins: List<PocketPlugin>,
    private val ownerScope: CoroutineScope,
    private val nowMillis: () -> Long,
    private val readAudio: () -> AudioSnapshot,
    private val perform: (PluginAction, CoroutineScope) -> Unit,
    private val invalidateUi: () -> Unit,
    private val reportError: (String, Throwable) -> Unit,
    private val observeDecks: StateFlow<DeckState> = MutableStateFlow(DeckState()),
    private val navigate: ((String) -> Unit)? = null,
) : Closeable {
    private val entries = plugins.toList()
    val descriptors: List<PluginDescriptor> = entries.map { it.descriptor }
    private val registeredChords = descriptors.associate { it.id to it.chords.toSet() }
    internal val chords: Set<KeyChord> get() = if (host == null) emptySet() else registeredChords.getValue(selectedId)
    var selectedId: String = descriptors.firstOrNull()?.id ?: error("Register at least one plugin")
        private set
    var generation = 0
        private set
    private var host: Activation? = null
    private val held = mutableSetOf<Int>()
    private var enabled = false
    private var closed = false
    private var failed = false
    private val selected get() = entries.single { it.descriptor.id == selectedId }

    init {
        require(descriptors.map { it.id }.distinct().size == descriptors.size) { "Duplicate plugin ID" }
    }

    fun setEnabled(value: Boolean) {
        check(!closed)
        if (enabled == value) return
        enabled = value
        deactivate()
        if (enabled) activate()
    }

    fun select(id: String) {
        require(entries.any { it.descriptor.id == id }) { "Unknown plugin: $id" }
        check(!closed)
        if (id == selectedId && !failed) return
        deactivate()
        selectedId = id
        failed = false
        if (enabled) activate()
        invalidateUi()
    }

    fun next(delta: Int) {
        val index = descriptors.indexOfFirst { it.id == selectedId }
        select(descriptors[Math.floorMod(index + delta, descriptors.size)].id)
    }

    fun render(): PluginFrame {
        if (failed) return frame(List(9) { "Plugin error" }, selected.descriptor.title, 0xff792e2e.toInt())
        return try {
            val result = selected.render(nowMillis())
            // Snapshot mutable lists so a coroutine cannot change a frame while it is uploading.
            result.copy(keys = result.keys.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(selectedId, e)
            frame(List(9) { "Plugin error" }, selected.descriptor.title, 0xff792e2e.toInt())
        }
    }

    fun key(event: PluginKeyEvent) {
        val activation = host ?: return
        if (event.synthetic) {
            if (!held.remove(event.index)) return
        } else if (event.pressed) {
            if (!held.add(event.index)) return
        } else if (!held.remove(event.index)) return // A release belonging to the previous page is not a new-page event.
        call(activation) { selected.onKey(event, activation) }
        if (activation.current()) invalidateUi()
    }

    internal fun chord(binding: KeyChord) {
        val activation = host ?: return
        require(binding in chords)
        call(activation) { selected.onChord(binding, activation) }
        if (activation.current()) invalidateUi()
    }

    private fun activate() {
        failed = false
        generation++
        val activation = Activation(selected.descriptor, generation)
        host = activation
        call(activation) { selected.onActivate(activation) }
        invalidateUi()
    }

    private fun deactivate() {
        val activation = host ?: return
        val plugin = selected
        host = null
        generation++
        activation.scope.cancel()
        val releases = held.toList()
        held.clear()
        // Invalidate the host before cleanup: a release cannot start a new action on the departing page.
        try {
            releases.forEach { plugin.onKey(PluginKeyEvent(it, false, synthetic = true), activation) }
        } catch (e: Exception) {
            reportError(plugin.descriptor.id, e)
        }
        try {
            plugin.onDeactivate()
        } catch (e: Exception) {
            reportError(plugin.descriptor.id, e)
        }
    }

    private fun call(activation: Activation, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (activation.current()) fail(activation.descriptor.id, e)
        }
    }

    private fun fail(id: String, error: Throwable) {
        failed = true
        deactivate()
        reportError(id, error)
        invalidateUi()
    }

    override fun close() {
        if (closed) return
        enabled = false
        deactivate()
        closed = true
    }

    private inner class Activation(val descriptor: PluginDescriptor, val epoch: Int) : PluginHost {
        private val job = SupervisorJob(ownerScope.coroutineContext[Job])
        override val scope = CoroutineScope(ownerScope.coroutineContext + job +
            CoroutineExceptionHandler { _, error ->
                if (current()) fail(descriptor.id, error)
            })

        fun current(): Boolean = !closed && !failed && host === this && generation == epoch
        override fun nowMillis(): Long = nowMillis.invoke()
        override val deckState: StateFlow<DeckState> get() {
            check(current()) { "Plugin activation expired" }
            return observeDecks
        }
        override fun invalidate() { if (current()) invalidateUi() }
        override fun audioSnapshot(): AudioSnapshot {
            check(current()) { "Plugin activation expired" }
            require(PluginCapability.AUDIO in descriptor.capabilities) { "Plugin did not declare AUDIO capability" }
            return readAudio()
        }
        override fun dispatch(action: PluginAction) {
            if (!current()) return // Old coroutine continuations and cleanup hooks may not dispatch actions.
            val capability = when (action) {
                is PluginAction.Deck -> action.command.capability()
                is PluginAction.Volume, is PluginAction.Media -> PluginCapability.AUDIO
                is PluginAction.OpenApp -> PluginCapability.OPEN_APPS
                is PluginAction.SelectPlugin -> PluginCapability.NAVIGATION
                PluginAction.CompletionTone -> PluginCapability.TONE
                PluginAction.Repaint -> null
            }
            require(capability == null || capability in descriptor.capabilities) {
                "Plugin ${descriptor.id} did not declare $capability capability"
            }
            if (action is PluginAction.SelectPlugin) (navigate ?: ::select)(action.id) else perform(action, scope)
        }
    }
}
