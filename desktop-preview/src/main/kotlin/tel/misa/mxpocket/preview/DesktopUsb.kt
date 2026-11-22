package tel.misa.mxpocket.preview

import tel.misa.mxpocket.i18n.WindowsHostCopy as H
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.preview.usb.WindowsKeypad
import tel.misa.mxpocket.shared.ui.VirtualGrid

internal fun monotonicMs() = System.nanoTime() / 1_000_000

internal class PhysicalKeys(private val deck: PreviewDeck) {
    private data class Down(val grid: VirtualGrid, val at: Long)
    private val held = mutableMapOf<Int, Down>()

    fun accept(key: KeyEvent, grid: VirtualGrid?, at: Long): String? {
        val id = key.controlId
        val slot = id - 1
        val down = if (!key.pressed || key.synthetic) held.remove(id) else null
        if (id == 0x01a1 || id == 0x01a2) {
            val current = !key.synthetic && !deck.simulator && grid != null && grid == deck.board(at).grid
            if (key.pressed && (!current || held.putIfAbsent(id, Down(grid!!, at)) != null)) return null
            var message: String? = null
            deck.pageButtons.input(if (id == 0x01a1) -1 else 1, key.pressed,
                !current || !key.pressed && down?.grid != grid, { delta ->
                    deck.cycle(delta)
                    message = tel.misa.mxpocket.i18n.WorkspaceCopy.Selected.text(deck.profileName)
                }, { deck.repository.enterGame().onFailure { message = it.message } })
            return message
        }
        if (grid == null || key.synthetic || deck.simulator || grid != deck.board(at).grid) return null
        if (key.pressed) {
            if (held.putIfAbsent(id, Down(grid, at)) != null) return null
            if (slot in 0..8 && grid.page.keys[slot].holdAction == null)
                return deck.physical(slot, grid, at, false)
        } else if (slot in 0..8 && down?.grid == grid && grid.page.keys[slot].holdAction != null) {
            return deck.physical(slot, grid, at, at - down.at >= 500)
        }
        return null
    }
}

/** One lifetime for enumeration, open/init, input and display. UI calls stay on the window dispatcher. */
internal class DesktopUsb(
    private val scope: CoroutineScope,
    private val deck: PreviewDeck,
    private val status: (String) -> Unit,
) {
    var active by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var diagnostics by mutableStateOf(H.NotScanned.text())
        private set
    private var job: Job? = null
    private data class Frame(val grid: VirtualGrid, val tiles: List<ImageBitmap>, val repaint: Int)
    private data class Edge(val key: KeyEvent, val grid: VirtualGrid?, val time: Long)
    private var frames: Channel<Frame>? = null

    fun submit(grid: VirtualGrid, tiles: List<ImageBitmap>, repaint: Int) {
        frames?.trySend(Frame(grid, tiles, repaint))
    }

    fun toggle() {
        if (active) { job?.cancel(); return }
        if (deck.simulator) return
        active = true
        deck.resetInput()
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var device: WindowsKeypad? = null
            val queue = Channel<Frame>(Channel.CONFLATED)
            val edges = Channel<Edge>(64)
            val painted = AtomicReference<VirtualGrid?>()
            val failed = CompletableDeferred<Throwable>()
            fun report(text: String) { diagnostics = text; status(text) }
            try {
                report(H.Looking.text())
                val found = WindowsKeypad.discover()
                when (found.size) {
                    0 -> { report(H.Missing.text()); return@launch }
                    1 -> Unit
                    else -> { report(H.Multiple.text()); return@launch }
                }
                report(H.Found.text())
                val session = WindowsKeypad.connect(found.single(), { key ->
                    if (edges.trySend(Edge(key, painted.get(), monotonicMs())).isFailure)
                        failed.complete(IOException(H.QueueFull.text()))
                }, { failed.complete(it) })
                device = session
                frames = queue
                ready = true
                report(H.Initialized.text())
                coroutineScope {
                    val input = PhysicalKeys(deck)
                    launch {
                        for (edge in edges) if (!failed.isCompleted) {
                            input.accept(edge.key, edge.grid, edge.time)?.let(status)
                        }
                    }
                    launch {
                        val cache = arrayOfNulls<ImageBitmap>(9)
                        var epoch = -1
                        var firstPaint = true
                        for (first in queue) {
                            var frame = first
                            while (true) {
                                if (frame.repaint != epoch) { cache.fill(null); epoch = frame.repaint }
                                if (painted.get() != frame.grid) painted.set(null)
                                val i = cache.indices.firstOrNull { cache[it] !== frame.tiles[it] }
                                if (i == null) {
                                    painted.set(frame.grid)
                                    diagnostics = H.Acknowledged.text()
                                    if (firstPaint) { status(H.Press.text()); firstPaint = false }
                                    break
                                }
                                val jpeg = withContext(Dispatchers.Default) {
                                    Image.makeFromBitmap(frame.tiles[i].asSkiaBitmap()).use { image ->
                                        image.encodeToData(EncodedImageFormat.JPEG, 90)?.use { it.bytes }
                                            ?: throw IOException(H.Encode.text())
                                    }
                                }
                                // Never interrupt an acknowledged fragment sequence on a layout change.
                                session.setKeyJpeg(i, jpeg)
                                cache[i] = frame.tiles[i]
                                frame = queue.tryReceive().getOrNull() ?: frame
                            }
                        }
                    }
                    throw failed.await()
                }
            } catch (e: CancellationException) {
                report(H.Disconnected.text())
                throw e
            } catch (e: Exception) {
                report(H.Failed.text(e.message ?: e.javaClass.simpleName))
            } finally {
                ready = false
                frames = null
                edges.close()
                queue.close()
                try { device?.disconnect() }
                catch (e: IOException) { report(H.Cleanup.text(e.message)) }
                finally { active = false; deck.resetInput() }
            }
        }
    }

    suspend fun close() { job?.cancelAndJoin() }
}
