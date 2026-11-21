package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.view.View
import java.io.ByteArrayOutputStream
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
import tel.misa.mxpocket.plugins.*
import tel.misa.mxpocket.ui.KeypadPanel
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerRenderTest {
    @Test fun lcdSymbolsAndWrappedTitleSurviveJpegAndPhoneMirrorsTheSameKeys() = runTest {
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
        repeat(42) {
            stripe.color = if (it % 3 == 0) 0xff263a50.toInt() else 0xfff3d1a2.toInt()
            c.drawCircle(452f, 466f, 9f + it * 12, stripe)
        }
        val cover = SessionArtwork.from(MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART, art).build())!!.load(app)!!
        for (back in listOf(null, cover)) {
            images.useArtwork(back)
            val native = tiles.mapIndexed(images::image)
            val decoded = native.map { src ->
                val bytes = ByteArrayOutputStream().also {
                    assertTrue(src.compress(Bitmap.CompressFormat.JPEG, if (back == null) 90 else 50, it))
                }.toByteArray()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
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
            }
            // Solid glyph strokes and the two lines of title must keep their contrast after JPEG.
            for (i in native.indices) {
                val ys = if (i == 7) 42..83 else 37..78
                val ink = if (i == 4) 0 else 255
                var count = 0; var retained = 0
                for (y in ys) for (x in 39..79) {
                    if (native[i].getPixel(x,y) != Color.rgb(ink,ink,ink)) continue
                    count++
                    if (abs(Color.red(decoded[i].getPixel(x,y)) - ink) < 70) retained++
                }
                assertTrue("Key ${i+1} lost substantial strokes ($retained/$count)", count > 30 && retained >= count * .94)
            }
            val panel = KeypadPanel(app)
            panel.show(back, decoded)
            val exact = View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY)
            panel.measure(exact,exact); panel.layout(0,0,480,480)
            val mirror = Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
            panel.draw(Canvas(mirror))
            decoded.forEachIndexed { i,b ->
                val r = tel.misa.mxpocket.driver.KeypadProtocol.keyRegion(i)
                val expected = IntArray(r.width * r.height)
                val actual = IntArray(expected.size)
                b.getPixels(expected,0,r.width,0,0,r.width,r.height)
                mirror.getPixels(actual,0,r.width,r.x,r.y,r.width,r.height)
                assertArrayEquals("Mirror region $i", expected, actual)
            }
            val name = if (back == null) "without-cover" else "with-cover"
            File("build/reports/player-preview/$name.png").apply {
                parentFile?.mkdirs(); outputStream().use { mirror.compress(Bitmap.CompressFormat.PNG,100,it) }
            }
        }
    }
}
