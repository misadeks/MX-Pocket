package tel.misa.mxpocket.driver

import android.graphics.Bitmap
import android.graphics.Color
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimulatedKeypadTest {
    @Test fun rendersIndependentImagesAndIsolatesReconnectedHandle() = runBlocking {
        val events = mutableListOf<KeyEvent>()
        val old = SimulatedKeypad(events::add)
        val sink: KeypadSink = old
        val bitmap = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        sink.setKeyImage(0, bitmap)
        bitmap.eraseColor(Color.BLUE)
        bitmap.recycle()
        val image = old.renderedImage(0)!!
        assertEquals(Color.RED, image.getPixel(20, 20))
        image.eraseColor(Color.GREEN)
        image.recycle()
        assertEquals(Color.RED, old.renderedImage(0)!!.getPixel(20, 20))

        old.button(0x01a1, true)
        old.button(1, true)
        old.button(1, true)
        old.button(1, false)
        old.disconnect()
        val fresh = old.reconnect()
        old.button(2, true)
        fresh.button(2, true)
        assertEquals(listOf(KeyEvent(0x01a1, true), KeyEvent(1, true), KeyEvent(1, false),
            KeyEvent(0x01a1, false, true), KeyEvent(2, true)), events)
        assertNull(fresh.renderedImage(0))
        assertNull(old.renderedImage(0))
        val small = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888)
        small.eraseColor(Color.BLUE)
        assertTrue(runCatching { sink.setKeyImage(8, small) }.exceptionOrNull() is IOException)
        fresh.setKeyImage(8, small)
        small.recycle()
        val scaled = fresh.renderedImage(8)!!
        assertEquals(118, scaled.width)
        assertEquals(118, scaled.height)
        assertEquals(Color.BLUE, scaled.getPixel(117, 117))
        fresh.close()
        assertFalse(scaled.isRecycled)
        scaled.recycle()
    }
}
