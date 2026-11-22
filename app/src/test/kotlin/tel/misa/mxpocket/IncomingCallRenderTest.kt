package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tel.misa.mxpocket.calls.CallStamp
import tel.misa.mxpocket.calls.DialerNotice
import tel.misa.mxpocket.calls.photo
import tel.misa.mxpocket.plugins.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w640dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IncomingCallRenderTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun syntheticAndUnavailableCallFacesFitTheNinePhysicalApertures() {
        val mirror = ComposeMirror(ui)
        val images = KeyImages { null }
        val portrait = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        Canvas(portrait).apply {
            drawColor(0xff688e8b.toInt())
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xffecceae.toInt() }
            drawOval(59f, 21f, 101f, 76f, ink)
            ink.color = 0xff39312f.toInt()
            drawArc(57f, 14f, 103f, 67f, 180f, 192f, false, ink)
            ink.color = 0xff304c55.toInt()
            drawOval(28f, 79f, 132f, 169f, ink)
        }
        val notice = DialerNotice(CallStamp("fixture", 0, 0, null, null, "fixture"), "Alex Vale", Icon.createWithBitmap(portrait))
        for ((state, sample) in listOf("details" to notice, "unavailable" to null)) {
            val keys = phoneCallFrame(PhoneLine.RINGING, sample?.name, sample != null, 1).keys
            val native = keys.mapIndexed { i, face ->
                (if (i == CALL_PHOTO) sample?.photo(ui.activity) else null) ?: images.image(i, face)
            }
            val panel = mirror.render(keys, native)
            native.forEachIndexed { i, bitmap -> assertAperturePixels(panel, i, bitmap) }
            val occupied = native.indices.filterNot { native[it].sameAs(native[0]) }
            assertEquals(if (sample == null) listOf(1, 4) else listOf(1, 4, 6, 8), occupied)
            val out = File("build/reports/incoming-call/$state.png")
            out.parentFile.mkdirs()
            out.outputStream().use { assertTrue(panel.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
