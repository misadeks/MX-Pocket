package tel.misa.mxpocket.preview.usb

import com.sun.jna.Native
import com.sun.jna.platform.win32.Guid.GUID
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.driver.KeypadProtocol

class WindowsKeypadTest {
    private fun collections(parent: String) = Route.entries.map {
        // Deliberately different wire and collection maxima, and non-identifying paths.
        HidCollection(parent, "opaque-${it.ordinal}", it, when (it) {
            Route.COMMAND -> 24; Route.KEYS -> 40; Route.LCD -> 0
        }, when (it) { Route.COMMAND -> 24; Route.LCD -> 4096; else -> 0 })
    }

    private class Port(override val collection: HidCollection, val send: (ByteArray) -> Unit) : HidPort {
        val queue = LinkedBlockingQueue<ByteArray>()
        val closed = AtomicBoolean()
        override fun read(): ByteArray = queue.take().also { if (it.isEmpty()) throw IOException("Unplugged") }
        override fun write(bytes: ByteArray) = send(bytes)
        override fun close() { if (closed.compareAndSet(false, true)) queue.add(byteArrayOf()) }
        override suspend fun awaitClosed() { check(closed.get()) }
    }

    private class Backend(var found: List<HidCollection>) : WindowsHid {
        val opened = CopyOnWriteArrayList<Port>()
        val sent = LinkedBlockingQueue<Pair<Route, ByteArray>>()
        var failOpen: Route? = null
        var ackInit = true
        var pageFeature = 7
        override fun enumerate() = found
        override fun open(collection: HidCollection): Port {
            if (collection.route == failOpen) throw IOException("Access denied")
            return Port(collection) { frame ->
                sent.add(collection.route to frame.copyOf())
                if (collection.route == Route.COMMAND && ackInit) {
                    val ack = frame.copyOf()
                    if (frame[2] == 0.toByte()) ack[4] = pageFeature.toByte()
                    port(Route.COMMAND).queue.add(ack)
                }
            }.also { opened += it }
        }
        fun port(route: Route) = opened.single { it.collection.route == route }
        fun next() = assertNotNull(sent.poll(4, TimeUnit.SECONDS), "Expected write")
        fun input(route: Route, vararg bytes: Int) = port(route).let { port ->
            port.queue.add(bytes.map(Int::toByte).toByteArray().copyOf(port.collection.inputBytes))
        }
    }

    private fun jpeg() = ByteArray(5000).apply {
        this[0] = -1; this[1] = 0xd8.toByte(); this[lastIndex - 1] = -1; this[lastIndex] = 0xd9.toByte()
    }

    @Test fun groupByPhysicalParentAndRefuseAmbiguousOrPartialSetsBeforeOpening() = runBlocking {
        val backend = Backend(collections("a") + collections("b"))
        val list = WindowsKeypad.discover(backend)
        assertEquals(2, list.size)
        val selected = list.first()
        backend.found = backend.found - backend.found.first()
        assertFailsWith<IOException> { WindowsKeypad.connect(backend, selected, {}, {}) }
        backend.found = collections("a") + collections("a").last()
        assertFailsWith<IOException> { WindowsKeypad.connect(backend, selected, {}, {}) }
        assertTrue(backend.opened.isEmpty())
        backend.found = collections("a")
        backend.failOpen = Route.LCD
        assertFailsWith<IOException> { WindowsKeypad.connect(backend, selected, {}, {}) }
        assertEquals(2, backend.opened.size)
        assertTrue(backend.opened.all { it.closed.get() })
    }

    @Test fun routePhysicalEdgesAndAckEachPaddedLcdFragment() = runBlocking {
        val backend = Backend(collections("a").reversed())
        val events = LinkedBlockingQueue<KeyEvent>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val session = WindowsKeypad.connect(backend, WindowsKeypad.discover(backend).single(), events::add, errors::add)
        try {
            val init = List(3) { backend.next() }
            assertTrue(init.all { (route, bytes) -> route == Route.COMMAND && bytes.size == 24 })
            val expected = listOf(
                KeypadProtocol.command(0, 0, 0x1b, 4),
                KeypadProtocol.command(7, 3, 1, 0xa1, 3),
                KeypadProtocol.command(7, 3, 1, 0xa2, 3),
            )
            init.zip(expected).forEach { (actual, frame) -> assertContentEquals(frame.copyOf(24), actual.second) }
            backend.input(Route.KEYS, 0x13, 255, 2, 0, 0, 1, 4, 9)
            backend.input(Route.KEYS, 0x13, 255, 2, 0, 0, 1, 4, 9)
            backend.input(Route.KEYS, 0x13, 255, 2, 0, 0, 1, 9)
            assertEquals(listOf(KeyEvent(4, true), KeyEvent(9, true), KeyEvent(4, false)),
                List(3) { events.poll(2, TimeUnit.SECONDS) })
            backend.input(Route.COMMAND, 0x11, 255, 7, 0, 1, 0xa2)
            assertEquals(KeyEvent(0x1a2, true), events.poll(2, TimeUnit.SECONDS))

            val image = jpeg()
            val frames = KeypadProtocol.imageReports(KeypadProtocol.keyRegion(8), image)
            val upload = async(Dispatchers.Default) { session.setKeyJpeg(8, image) }
            for ((index, frame) in frames.withIndex()) {
                val (route, padded) = backend.next()
                assertEquals(Route.LCD, route)
                assertContentEquals(frame.copyOf(4096), padded)
                if (index == 0) {
                    // Right header but wrong collection, and a short packet on the right one.
                    backend.input(Route.COMMAND, 0x13, 255, 2, 0x2b)
                    backend.port(Route.KEYS).queue.add(byteArrayOf(0x13, -1, 2, 0x2b, 0, 0))
                    assertNull(backend.sent.poll(80, TimeUnit.MILLISECONDS))
                }
                backend.input(Route.KEYS, 0x13, 255, 2, 0x2b)
            }
            upload.await()
        } finally {
            session.disconnect()
        }
        assertEquals(listOf(KeyEvent(9, false, true), KeyEvent(0x1a2, false, true)), events.toList())
        assertTrue(errors.isEmpty())
        assertTrue(backend.opened.all { it.closed.get() })
    }

    @Test fun readerFailureWhileWaitingForAckClosesAllHandlesAndReportsOneFailure() = runBlocking {
        val backend = Backend(collections("a"))
        val errors = LinkedBlockingQueue<Throwable>()
        val session = WindowsKeypad.connect(backend, WindowsKeypad.discover(backend).single(), {}, errors::add)
        try {
            backend.sent.clear()
            val upload = async(Dispatchers.Default) { runCatching { session.setKeyJpeg(0, jpeg()) } }
            assertEquals(Route.LCD, backend.next().first)
            backend.port(Route.COMMAND).queue.add(byteArrayOf()) // read fails without an output completion
            assertIs<IOException>(withTimeout(2000) { upload.await() }.exceptionOrNull())
            assertEquals("Unplugged", errors.poll(2, TimeUnit.SECONDS)?.message)
        } finally {
            session.disconnect()
            session.disconnect()
        }
        assertTrue(backend.opened.all { it.closed.get() })
        assertTrue(errors.isEmpty())
    }

    @Test fun cancelledOrRejectedInitializationNeverLeaksAReader() = runBlocking {
        val backend = Backend(collections("a"))
        backend.ackInit = false
        val device = WindowsKeypad.discover(backend).single()
        val job = launch(Dispatchers.Default) { WindowsKeypad.connect(backend, device, {}, {}) }
        backend.next()
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(backend.opened.all { it.closed.get() })

        val noFeature = Backend(collections("a")).apply { pageFeature = 0 }
        assertFailsWith<IOException> { WindowsKeypad.connect(noFeature, device, {}, {}) }
        assertEquals(1, noFeature.sent.size)
        assertTrue(noFeature.opened.all { it.closed.get() })
    }

    @Test fun silentKeypadTimesOutRatherThanReportingASuccessfulUpload() = runBlocking {
        val backend = Backend(collections("a"))
        val errors = LinkedBlockingQueue<Throwable>()
        val session = WindowsKeypad.connect(backend, WindowsKeypad.discover(backend).single(), {}, errors::add)
        try {
            assertFailsWith<IOException> { withTimeout(4000) { session.setKeyJpeg(1, jpeg()) } }
            assertContains(assertNotNull(errors.poll()).message!!, "acknowledgement timed out")
        } finally {
            session.disconnect()
        }
        assertTrue(backend.opened.all { it.closed.get() })
    }

    @Test fun architectureGateUsesJvmNotHostCpu() {
        requireWindowsRuntime("Windows 11", "amd64")
        assertFailsWith<UnsupportedOperationException> { requireWindowsRuntime("Windows 11", "aarch64") }
        assertFailsWith<UnsupportedOperationException> { requireWindowsRuntime("Windows 10", "x86") }
        assertFailsWith<UnsupportedOperationException> { requireWindowsRuntime("Linux", "amd64") }
    }

    @Test fun x64JnaCanLoadWindowsHidClassWithoutQueryingDevices() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "amd64")
        assertEquals(8, Native.POINTER_SIZE)
        assertEquals(GUID.fromString("{4D1E55B2-F16F-11CF-88CB-001111000030}"), Win32Hid.classGuid())
        assertTrue(Win32Hid.isKeypadParent("usb\\vid_046d&pid_c354\\test"))
        assertFalse(Win32Hid.isKeypadParent("BTHENUM\\VID_046D&PID_C354\\test"))
        assertFalse(Win32Hid.isKeypadParent("USB\\VID_046D&PID_C354&MI_00\\test"))
        assertFalse(Win32Hid.isKeypadParent("USB\\VID_046D&PID_C3541\\test"))
    }
}
