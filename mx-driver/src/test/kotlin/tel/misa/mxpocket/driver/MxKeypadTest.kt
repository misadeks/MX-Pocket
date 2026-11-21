package tel.misa.mxpocket.driver

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
    private fun keypad(session: HidSession) = MxKeypad::class.java
        .getDeclaredConstructor(HidSession::class.java, Function1::class.java)
        .apply { isAccessible = true }.newInstance(session, { _: String -> })

    @Test fun compactPhotosSaveReportsWithoutResizingOrBlurringTheLabel() = runBlocking {
        val keypad = keypad(HidSession(object : ReportTransport {
            override suspend fun send(report: ByteArray) = error("Encoding must not upload")
            override fun close() {}
        }) {})
        for (detail in listOf(false, true)) {
            val bitmap = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.DKGRAY)
            if (detail) {
                val random = Random(42)
                for (y in 0 until 88) for (x in 0 until 118) {
                    val grain = random.nextInt(96)
                    bitmap.setPixel(x, y, Color.rgb(x + grain, y + grain, (x + y) / 2 + grain))
                }
            }
            Canvas(bitmap).drawText("PLAY", 30f, 110f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
            })
            val normal = keypad.encodeKeyJpeg(0, bitmap)
            val compact = keypad.encodeKeyJpeg(0, bitmap, preferFewerReports = true)
            fun reports(jpeg: ByteArray) = KeypadProtocol.imageReports(KeypadProtocol.keyRegion(0), jpeg).size
            println("detail=$detail normal=${normal.size}/${reports(normal)} compact=${compact.size}/${reports(compact)}")
            if (detail) assertTrue(reports(compact) < reports(normal)) else {
                assertEquals(1, reports(normal))
                assertArrayEquals(normal, compact)
            }
            val decoded = BitmapFactory.decodeByteArray(compact, 0, compact.size)
            assertEquals(118, decoded.width)
            assertEquals(118, decoded.height)
            var strokePixels = 0
            for (y in 88 until 118) for (x in 0 until 118) {
                val original = Color.red(bitmap.getPixel(x, y))
                val result = Color.red(decoded.getPixel(x, y))
                if (original >= 224) {
                    strokePixels++
                    assertTrue("Lost a light letter stroke", result > 160)
                } else if (original <= 96) assertTrue("Filled a dark label gap", result < 160)
            }
            assertTrue(strokePixels > 100)
        }
        keypad.close()
    }

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
        val keypad = keypad(session)
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
