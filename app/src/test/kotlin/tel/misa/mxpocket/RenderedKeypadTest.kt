package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tel.misa.mxpocket.plugins.KeyTile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RenderedKeypadTest {
    @Test fun revisitingAPageSkipsEncodingButNotUploadingAndNewImagesMiss() = runBlocking {
        val images = KeyImages { null }
        var encodes = 0
        val uploads = mutableListOf<Pair<Int, ByteArray>>()
        val keypad = RenderedKeypad({ _, _ -> byteArrayOf((++encodes).toByte()) },
            { index, bytes -> uploads += index to bytes }, {})
        suspend fun page(label: String) {
            for (i in 0..8) keypad.setKeyImage(i, images.image(i, KeyTile(label, "", Color.BLUE)))
        }

        page("A")
        val cold = uploads.toList()
        assertEquals(9, encodes)
        page("B")
        assertEquals(18, encodes)
        page("A")
        assertEquals(18, encodes)
        assertEquals(27, uploads.size)
        assertEquals(cold, uploads.takeLast(9))

        keypad.setKeyImage(0, images.image(0, KeyTile("Changed", "", Color.BLUE)))
        assertEquals(19, encodes)
        assertNotSame(cold[0].second, uploads.last().second)
        images.setBackdrop(Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888))
        page("A")
        assertEquals(28, encodes)
        images.clear() // Explicit repaint or icon invalidation.
        page("A")
        assertEquals(37, encodes)
        keypad.close()
    }

    @Test fun bytePressureEvictsOldKeysEvenIfTheRendererStillHasThem() = runBlocking {
        var encodes = 0
        // Each entry costs 55,696 pixel bytes plus 65,000 encoded bytes.
        val keypad = RenderedKeypad({ _, _ -> encodes++; ByteArray(65_000) }, { _, _ -> }, {})
        val keys = List(18) { Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888) }
        for ((i, key) in keys.withIndex()) keypad.setKeyImage(i % 9, key)
        keypad.setKeyImage(8, keys.last())
        assertEquals(18, encodes)
        keypad.setKeyImage(0, keys.first())
        assertEquals(19, encodes)
        keypad.close()
    }
}
