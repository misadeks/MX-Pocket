package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tel.misa.mxpocket.driver.HidSession
import tel.misa.mxpocket.driver.KeypadProtocol
import tel.misa.mxpocket.driver.MxKeypad
import tel.misa.mxpocket.driver.ReportTransport
import tel.misa.mxpocket.plugins.KeySelection
import tel.misa.mxpocket.plugins.OneNoteLegend
import tel.misa.mxpocket.plugins.OneNoteMark
import tel.misa.mxpocket.plugins.OneNotePlugin
import tel.misa.mxpocket.plugins.PluginEngine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w640dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class OneNoteRenderTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun observedDrawToolbarSurvivesKeyAndGridJpegAndMatchesTheMaskedPreview() = runTest {
        val observed = listOf("More options",
            "Green color, thickness 10.00 percent, pen options",
            "Red color, thickness 10.00 percent, pen options",
            "Light Green color, thickness 14.00 percent, pen options",
            "Dark Blue color, thickness 20.00 percent, pen options",
            "Yellow color, thickness 20.00 percent, highlighter options",
            "Aqua color, thickness 20.00 percent, highlighter options",
            "Stroke Eraser", "Shape select", "Lasso select")
        fun frame(toolbar: List<String>) = PluginEngine(
            listOf(OneNotePlugin("OneNote", ui.activity.resources)), this, { testScheduler.currentTime },
            { error("Render requested audio") }, { _, _ -> error("Render dispatched an action") },
            {}, { _, error -> throw error }, readActiveTool = { "Stroke Eraser" },
            readToolBar = { toolbar.map { it to it } }
        ).use { engine ->
            engine.setEnabled(true)
            runCurrent()
            engine.render().keys
        }

        val tiles = frame(observed)
        val marks = tiles.map { (it.glyph as OneNoteLegend).mark }
        assertEquals(List(4) { OneNoteMark.PEN } + List(2) { OneNoteMark.HIGHLIGHTER } +
            listOf(OneNoteMark.ERASER, OneNoteMark.SHAPE, OneNoteMark.LASSO), marks)
        assertEquals(List(9) { if (it == 6) KeySelection.ON else KeySelection.OFF }, tiles.map { it.selection })
        val images = KeyImages { null }
        val native = tiles.mapIndexed(images::image)
        val mirror = ComposeMirror(ui).render(tiles, native)
        native.forEachIndexed { i, image ->
            assertEquals(118, image.width)
            assertEquals(118, image.height)
            assertAperturePixels(mirror, i, image)
        }

        // The encoder is real. Its transport cannot send; the sink's upload callbacks only decode in memory.
        val session = HidSession(object : ReportTransport {
            override suspend fun send(report: ByteArray) = error("Render attempted a USB report")
            override fun close() {}
        }) { error("Render received HID input") }
        val encoder = MxKeypad::class.java.getDeclaredConstructor(HidSession::class.java, Function1::class.java)
            .apply { isAccessible = true }.newInstance(session, { _: String -> })
        val grid = KeypadProtocol.keyGrid
        val keys = mutableMapOf<Int, Bitmap>()
        lateinit var decodedGrid: Bitmap
        RenderedKeypad(encoder::encodeKeyJpeg, { i, bytes -> keys[i] = decode(bytes) }, encoder::close,
            { encoder.encodeRegionJpeg(grid, it) }, { decodedGrid = decode(it) }).use { sink ->
            native.forEachIndexed { i, bitmap -> sink.setKeyImage(i, bitmap) }
            assertEquals(GridWrite.ACKNOWLEDGED, sink.writeGrid(null, native, false) { true })
            assertEquals(grid.width, decodedGrid.width)
            assertEquals(grid.height, decodedGrid.height)
            val gridTiles = native.mapIndexed { i, original ->
                val region = KeypadProtocol.keyRegion(i)
                val crop = Bitmap.createBitmap(decodedGrid, region.x - grid.x, region.y - grid.y, region.width, region.height)
                assertJpegMatches("key ${i + 1}", original, keys.getValue(i))
                assertJpegMatches("grid key ${i + 1}", original, crop)
                crop
            }

            for (faces in listOf(native, native.indices.map(keys::getValue), gridTiles)) {
                fun inkArea(i: Int, matches: (Int) -> Boolean) =
                    (10..65).sumOf { y -> (27..90).count { x -> matches(faces[i].getPixel(x, y)) } }
                val green = inkArea(0) { Color.green(it) > maxOf(Color.red(it), Color.blue(it)) + 40 }
                val yellow = inkArea(4) { minOf(Color.red(it), Color.green(it)) > maxOf(145, Color.blue(it) + 60) }
                assertTrue("Expected fine green ink and broader yellow ink ($green, $yellow pixels)",
                    green > 25 && yellow > green * 1.5)
                for ((i, face) in faces.withIndex()) {
                    val badgeInk = (7..25).sumOf { y -> (92..110).count { x -> bright(face.getPixel(x, y)) } }
                    assertEquals("Selection badge on key ${i + 1}", i == 6, badgeInk > 12)
                }
            }

            // Probe the fallback separately. Never substitute an invented tool into the observed preview.
            val unknown = frame(listOf("Uncatalogued color, thickness 10.00 percent, pen options")).first()
            assertEquals(OneNoteLegend(OneNoteMark.PEN, ink = null, widthPercent = 10f), unknown.glyph)
            sink.setKeyImage(0, images.image(0, unknown))
            val fallback = keys.getValue(0)
            val stripedRows = (10..60).count { y ->
                var last = 0
                var transitions = 0
                for (x in 27..90) {
                    val c = fallback.getPixel(x, y)
                    val tone = when {
                        bright(c) -> 1
                        maxOf(Color.red(c), Color.green(c), Color.blue(c)) < 70 -> -1
                        else -> continue
                    }
                    if (last == -tone) transitions++
                    last = tone
                }
                transitions >= 5
            }
            assertTrue("Unknown ink lost its alternating light/dark stripes", stripedRows >= 2)
        }

        val output = File("build/reports/onenote-preview/observed-draw-toolbar.png")
        output.parentFile.mkdirs()
        output.outputStream().use { assertTrue(mirror.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun decode(bytes: ByteArray) = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    private fun bright(c: Int) = minOf(Color.red(c), Color.green(c), Color.blue(c)) > 185

    private fun assertJpegMatches(label: String, native: Bitmap, decoded: Bitmap) {
        assertEquals(label, native.width, decoded.width)
        assertEquals(label, native.height, decoded.height)
        fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        fun distance(a: Int, b: Int) = abs(Color.red(a) - Color.red(b)) +
            abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))
        val expected = pixels(native)
        val actual = pixels(decoded)
        // Check the marks and lettering separately so the flat background cannot hide a missing face.
        var total = 0
        var foreground = 0
        var count = 0
        for (i in expected.indices) {
            val error = distance(expected[i], actual[i])
            total += error
            if (distance(expected[i], expected[0]) > 90) {
                foreground += error
                count++
            }
        }
        assertTrue("$label JPEG RGB error: $total overall, $foreground across $count foreground pixels",
            total <= 3 * 12 * expected.size && count > 100 && foreground <= 3 * 35 * count)
    }
}
