package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.decks.ok
import tel.misa.mxpocket.driver.KeyEvent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeypadPaintTest {
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication
    private val main get() = shadowOf(Looper.getMainLooper())

    /** One ACK per release; keep native pixels to detect visually stale but logically equal tiles. */
    private inner class Panel {
        val pixels = arrayOfNulls<Bitmap>(9)
        val writes = mutableListOf<Int>()
        var pause = false
        var inFlight: Bitmap? = null
        var outstanding: CompletableDeferred<Unit>? = null
        lateinit var event: (KeyEvent) -> Unit
        var shortcuts = 0
        val session = PocketSession(app) { _, _, onEvent, _, _ ->
            event = onEvent
            object : KeypadOutput {
                override fun close() {}
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
                    check(outstanding == null) { "Concurrent transfer" }
                    writes += index
                    if (pause) {
                        inFlight = bitmap
                        val ack = CompletableDeferred<Unit>()
                        outstanding = ack
                        ack.await()
                        outstanding = null
                    }
                    pixels[index] = bitmap
                }
            }
        }
        init {
            shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            val device = ReflectionHelpers.newInstance(UsbDevice::class.java)
            ReflectionHelpers.setField(device, "mName", "test-panel")
            session.connect(session.reserve(device)!!, app.getSystemService(UsbManager::class.java), device, {}, {
                shortcuts++; ok("Offered")
            })
            main.idle()
        }
        fun ack() { checkNotNull(outstanding).complete(Unit); main.idle() }
        fun settle() { pause = false; ack() }
        fun expectAll() {
            val frame = session.frame()
            for (i in pixels.indices)
                assertTrue("Wrong pixels on key ${i + 1}", pixels[i]?.sameAs(session.keyImage(i, frame.keys[i])) == true)
        }
    }

    @Test fun interruptedBackdropRestoresOnlyKeysThatAcknowledgedDifferentPixels() {
        val p = Panel()
        fun backdrop(color: Int) = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val a = backdrop(Color.RED)
        val b = backdrop(Color.BLUE)
        try {
            p.session.setImage(a); main.idle(); p.expectAll()
            val keys = p.session.frame().keys
            p.writes.clear()
            p.pause = true
            p.session.setImage(b); main.idle(); p.ack(); p.ack()
            assertEquals(listOf(0, 1, 2), p.writes)
            p.session.setImage(a); main.idle()
            p.settle()
            assertEquals(keys, p.session.frame().keys)
            p.expectAll()
            assertEquals("Restore only the three overwritten keys", listOf(0, 0, 1, 1, 2, 2), p.writes.sorted())

            p.writes.clear()
            p.session.repaint(); main.idle()
            assertEquals((0..8).toList(), p.writes.sorted())
        } finally { p.session.disconnect() }
    }

    @Test fun newerVolumeReachesLaterKeysWhileTheFirstKeyKeepsChanging() {
        val p = Panel()
        val audio = app.getSystemService(AudioManager::class.java)
        fun volume(n: Int) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, n, 0)
            app.contentResolver.notifyChange(Settings.System.getUriFor("volume_music"), null)
            main.idle()
        }
        try {
            p.session.selectProfile("audio"); volume(1)
            val original = p.session.frame()
            p.writes.clear(); p.pause = true
            volume(2)
            val dirty = (0..8).filter { original.keys[it] != p.session.frame().keys[it] }
            assertTrue(dirty.size > 1)
            for (i in dirty.indices) {
                volume(3 + i % 2)
                val desired = p.session.frame().keys.mapIndexed(p.session::keyImage)
                p.ack()
                val next = p.writes.last()
                assertTrue("Key ${next + 1} started a superseded volume after the previous ACK",
                    p.inFlight!!.sameAs(desired[next]))
            }
            p.settle()
            p.expectAll()
            // Every changed key gets a turn; unchanged transport keys never use the wire.
            assertEquals(dirty, p.writes.take(dirty.size).sorted())
            assertTrue(p.writes.all { it in dirty })
        } finally { p.session.disconnect() }
    }

    @Test fun profileRoundTripDoesNotAuthorizeInputDuringMixedPage() {
        val p = Panel()
        try {
            p.session.selectProfile("phone"); main.idle()
            p.pause = true; p.writes.clear()
            p.session.selectProfile("audio"); main.idle(); p.ack()
            p.session.selectProfile("phone"); main.idle()
            p.event(KeyEvent(1, true)); p.event(KeyEvent(1, false)); main.idle()
            assertEquals(0, p.shortcuts)
            p.settle(); p.expectAll()
            assertEquals(listOf(0, 0, 1, 1), p.writes.sorted())
            p.event(KeyEvent(1, true)); p.event(KeyEvent(1, false)); main.idle()
            assertEquals(1, p.shortcuts)
        } finally { p.session.disconnect() }
    }
}
