package tel.misa.mxpocket.driver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MxKeypadTest {
    @Test fun callerCanRedrawTheSameBitmapBetweenAcknowledgedUploads() = runBlocking {
        val colors = mutableListOf<Int>()
        lateinit var session: HidSession
        session = HidSession(object : ReportTransport {
            override suspend fun send(report: ByteArray) {
                // These solid tiles fit in one report.
                val tile = BitmapFactory.decodeByteArray(report, 20, report.u16(18))
                colors += tile.getPixel(59, 59)
                tile.recycle()
                session.receive(byteArrayOf(0x13, -1, 2, 0x2b, 0, 0).copyOf(32))
            }
            override fun close() {}
        }) {}
        val keypad = MxKeypad::class.java.getDeclaredConstructor(HidSession::class.java, Function1::class.java)
            .apply { isAccessible = true }.newInstance(session, { _: String -> })
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        for (color in listOf(Color.RED, Color.BLUE)) {
            bitmap.eraseColor(color)
            keypad.setKeyImage(0, bitmap)
        }
        keypad.close()
        assertEquals(2, colors.size)
        assertTrue(Color.red(colors[0]) > 240 && Color.blue(colors[0]) < 10)
        assertTrue(Color.blue(colors[1]) > 240 && Color.red(colors[1]) < 10)
    }
}
