package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.media.MediaMetadata
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tel.misa.mxpocket.decks.*
import tel.misa.mxpocket.driver.HidSession
import tel.misa.mxpocket.driver.KeypadProtocol
import tel.misa.mxpocket.driver.MxKeypad
import tel.misa.mxpocket.driver.ReportTransport
import tel.misa.mxpocket.plugins.*
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w640dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerRenderTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @Test fun lcdSymbolsAndWrappedTitleSurviveJpegAndPhoneMirrorsTheSameKeys() = runTest {
        val board = ComposeMirror(ui)
        val app = RuntimeEnvironment.getApplication()
        val state = MutableStateFlow(DeckState(media = MediaDeckState(access = true, status = "Paused", playing = false,
            title = "Night train through the mountains", volume = 6, maximum = 15,
            supported = setOf(DeckCommand.PREVIOUS, DeckCommand.PLAY, DeckCommand.NEXT, DeckCommand.BACK_10,
                DeckCommand.FORWARD_10))))
        val engine = PluginEngine(BuiltInPlugins.create("Focus","Audio","Phone"), this, { 0 }, { AudioSnapshot(6,15) },
            { _, _ -> error("Render requested input") }, {}, { _, e -> throw e }, state)
        engine.select("audio"); engine.setEnabled(true)
        val tiles = engine.render().keys
        engine.close()
        val images = KeyImages { null }
        val art = Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        val c = Canvas(art)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f,0f,480f,480f,
            intArrayOf(0xffede2c9.toInt(),0xffde6949.toInt(),0xff283e56.toInt()),null,Shader.TileMode.CLAMP) })
        // An original, patterned test cover with both bright and dark detail under the readout.
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
        repeat(18) {
            stripe.color = if (it % 3 == 0) 0xff263a50.toInt() else 0xfff3d1a2.toInt()
            c.drawCircle(452f, 466f, 9f + it * 23, stripe)
        }
        val cover = SessionArtwork.from(MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART, art).build())!!.load(app)!!
        val session = HidSession(object : ReportTransport {
            override suspend fun send(report: ByteArray) = error("Rendering must not send USB reports")
            override fun close() {}
        }) {}
        val encoder = MxKeypad::class.java.getDeclaredConstructor(HidSession::class.java, Function1::class.java)
            .apply { isAccessible = true }.newInstance(session, { _: String -> })
        fun flat(color: Int) = Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        fun checkCaption(tile: KeyTile, jpeg: Bitmap, offsetX: Int = 0, offsetY: Int = 0) {
            val plain = images.image(7,tile.copy(label = ""))
            val labeled = images.image(7,tile)
            val center = if (tile.glyph == PlayerGlyph.TRACK) 94 else 70
            for (band in listOf(center-19 until center, center until center+19)) {
                var solid = 0; var legible = 0
                for (y in band) for (x in 7..110) {
                    val ink = labeled.getPixel(x,y)
                    if (ink != Color.BLACK && ink != Color.WHITE) continue
                    if (abs(Color.red(plain.getPixel(x,y)) - Color.red(ink)) < 100) continue
                    solid++
                    if (abs(Color.red(jpeg.getPixel(offsetX+x,offsetY+y)) - Color.red(ink)) < 70) legible++
                }
                assertTrue("Caption lost ink in rows $band ($legible/$solid)", solid > 30 && legible >= solid * .9)
            }
        }
        for (back in listOf(null, flat(Color.WHITE), flat(Color.BLACK), cover)) {
            images.useArtwork(back)
            val native = tiles.mapIndexed(images::image)
            val decoded = native.mapIndexed { i, src ->
                val bytes = encoder.encodeKeyJpeg(i,src,back != null)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            checkCaption(tiles[7],decoded[7])
            val normalTitle = KeyTile("Long ordinary key caption", "", Color.DKGRAY)
            val ordinary = encoder.encodeKeyJpeg(7,images.image(7,normalTitle),back != null)
            checkCaption(normalTitle,BitmapFactory.decodeByteArray(ordinary,0,ordinary.size))
            // Flat player keys have no numeric fallback or captions; changing only the physical
            // index cannot change any pixels. Normal plugin tiles still have distinct key numbers.
            if (back == null) {
                assertTrue(native[0].sameAs(images.image(8, tiles[0])))
                val normal = KeyTile("Camera", "Phone", Color.DKGRAY)
                assertFalse(images.image(0, normal).sameAs(images.image(8, normal)))
                val changed = tiles[0].copy(label = "Custom", showLabel = true, icon = "X")
                assertFalse(native[0].sameAs(images.image(0, changed)))
                assertFalse(native[6].sameAs(images.image(6, tiles[6].copy(enabled = false))))
                assertEquals(tiles.size, native.map { b -> IntArray(118*118).also { b.getPixels(it,0,118,0,0,118,118) }.contentHashCode() }.distinct().size)
                val tops = mutableListOf<Int>(); val bottoms = mutableListOf<Int>()
                val variants = listOf(1 to PlayerGlyph.UNMUTE, 4 to PlayerGlyph.PAUSE, 4 to PlayerGlyph.PLAY_PAUSE)
                    .map { (i,glyph) ->
                        val bytes = encoder.encodeKeyJpeg(i,images.image(i,tiles[i].copy(glyph = glyph)),false)
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                    }
                for (b in decoded + variants) {
                    val rows = (10..73).filter { y -> (25..93).any { x -> Color.red(b.getPixel(x,y)) > 175 } }
                    tops += rows.first(); bottoms += rows.last()
                }
                assertTrue("Glyph tops: $tops", tops.max() - tops.min() <= 1)
                assertTrue("Glyph bottoms: $bottoms", bottoms.max() - bottoms.min() <= 1)
                suspend fun fill(level: VolumeLevel): Int {
                    val bytes = encoder.encodeKeyJpeg(1,images.image(1,tiles[1].copy(volumeLevel = level)))
                    val b = BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                    return (30..88).count { Color.red(b.getPixel(it,85)) > 160 }
                }
                assertEquals(0,fill(VolumeLevel(10,15,true)))
                assertEquals(0,fill(VolumeLevel(0,15,false)))
                assertEquals(0,fill(VolumeLevel(6,0,false)))
                assertTrue(fill(VolumeLevel(6,15,false)) in 20..26)
                assertEquals(59,fill(VolumeLevel(15,15,false)))
            }
            // Strokes keep their contrasting ink on plain, bright, dark and textured regions.
            for (i in native.indices) {
                val r = KeypadProtocol.keyRegion(i)
                var count = 0; var retained = 0
                for (y in 32..70) for (x in 36..82) {
                    val pixel = native[i].getPixel(x,y)
                    if (pixel != Color.WHITE && pixel != Color.BLACK) continue
                    if (pixel == back?.getPixel(r.x+x,r.y+y)) continue
                    val ink = Color.red(pixel)
                    count++
                    if (abs(Color.red(decoded[i].getPixel(x,y)) - ink) < 70) retained++
                }
                assertTrue("Key ${i+1} lost substantial strokes ($retained/$count)", count > 30 && retained >= count * .94)
            }
            val mirror = board.render(tiles, decoded, back)
            decoded.forEachIndexed { i,b ->
                val r = KeypadProtocol.keyRegion(i)
                val expected = IntArray(r.width * r.height)
                val actual = IntArray(expected.size)
                b.getPixels(expected,0,r.width,0,0,r.width,r.height)
                mirror.getPixels(actual,0,r.width,r.x,r.y,r.width,r.height)
                assertArrayEquals("Mirror region $i", expected, actual)
            }
            if (back != null && back !== cover) continue
            if (back != null) {
                val g = KeypadProtocol.keyGrid
                val sink = RenderedKeypad(encoder::encodeKeyJpeg, { _,_ -> error("Unexpected tile write") }, {},
                    { encoder.encodeRegionJpeg(g,it) }, { bytes ->
                        val actual = BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                        val r = KeypadProtocol.keyRegion(7)
                        checkCaption(tiles[7],actual,r.x-g.x,r.y-g.y)
                        Canvas(mirror).drawBitmap(actual,null,Rect(g.x,g.y,g.x+g.width,g.y+g.height),null)
                    })
                assertEquals(GridWrite.ACKNOWLEDGED,sink.writeGrid(back,native,false) { true })
            }
            val name = if (back == null) "flat-player" else "flat-player-cover"
            File("build/reports/player-preview/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { mirror.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
        encoder.close()
    }
}
