package tel.misa.mxpocket

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tel.misa.mxpocket.driver.KeypadProtocol
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.IntegrationApp
import tel.misa.mxpocket.decks.FavoriteApps
import tel.misa.mxpocket.decks.TEAMS_PACKAGE
import tel.misa.mxpocket.decks.OneNoteAccessService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RenderedKeypadTest {
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun integrationFacesUseTheInstalledPackageUnlessTheKeyHasItsOwnIcon() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        val apps = FavoriteApps(context)
        val colors = mapOf(IntegrationApp.TEAMS to Color.CYAN, IntegrationApp.ONENOTE to Color.MAGENTA)
        for ((id, color) in colors) {
            val pkg = if (id == IntegrationApp.TEAMS) TEAMS_PACKAGE else OneNoteAccessService.ONENOTE_PACKAGE
            pm.installPackage(android.content.pm.PackageInfo().apply { packageName = pkg })
            pm.setApplicationIcon(pkg, android.graphics.drawable.ColorDrawable(color))
        }
        val icons = apps.integrationIcons()
        val renderer = KeyImages(icons::get) { icons.getValue(IntegrationApp.TEAMS) }
        for ((id, color) in colors) {
            val face = KeyTile("My label", "", Color.BLACK, glyph = id)
            val image = renderer.image(0, face)
            val top = if (id == IntegrationApp.TEAMS) 9 else 5
            assertEquals(Color.BLACK, image.getPixel(59, top - 1))
            assertEquals(color, image.getPixel(59, top))
            assertEquals(color, image.getPixel(59, top + 37))
            val custom = face.copy(icon = "*")
            assertTrue(renderer.image(0, custom).sameAs(KeyImages { null }.image(0, custom)))
        }
        val favorite = KeyTile("My label", "", Color.BLACK, iconComponent = "$TEAMS_PACKAGE/.Main")
        val teams = renderer.image(0, favorite)
        val namesake = renderer.image(0, favorite.copy(iconComponent = "$TEAMS_PACKAGE.other/.Main"))
        assertEquals(Color.BLACK, teams.getPixel(59, 5))
        assertEquals(Color.CYAN, teams.getPixel(59, 9))
        assertEquals(Color.CYAN, namesake.getPixel(59, 5))
        assertTrue(Bitmap.createBitmap(teams, 0, 48, 118, 70).sameAs(Bitmap.createBitmap(namesake, 0, 48, 118, 70)))
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun gridCarriesTheSameLabeledTilesAndCoverGapsAsTheMirror() = runBlocking {
        val art = Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val keys = KeyImages { null }.also { it.useArtwork(art) }
        val tiles = (0..8).map { keys.image(it, KeyTile("Key $it","Footer",Color.BLUE)) }
        val bounds = KeypadProtocol.keyGrid
        var tooLarge = false
        val sent = mutableListOf<Int>()
        val sink = RenderedKeypad({ _,_,_ -> error("Not a sparse transfer") }, { _,_ -> error("Unexpected key") }, {},
            encodeGrid = { bitmap ->
                val gap = bitmap.getPixel(137,137)
                if (gap != Color.BLACK) {
                    assertEquals(Color.BLUE, gap)
                    for (i in tiles.indices) {
                        val r = KeypadProtocol.keyRegion(i)
                        val actual = Bitmap.createBitmap(bitmap,r.x-bounds.x,r.y-bounds.y,r.width,r.height)
                        assertTrue("Key $i differs from the phone tile", actual.sameAs(tiles[i]))
                    }
                }
                if (tooLarge && gap != Color.BLACK) null else byteArrayOf(if (gap == Color.BLACK) 0 else 1)
            }, uploadGrid = { sent += it.single().toInt() })
        assertEquals(GridWrite.SUPERSEDED, sink.writeGrid(art, tiles, false) { false })
        assertTrue(sent.isEmpty())
        assertEquals(GridWrite.ACKNOWLEDGED, sink.writeGrid(art,tiles,false) { true })
        tooLarge = true
        assertEquals(GridWrite.TOO_LARGE, sink.writeGrid(art,tiles,false) { true })
        assertEquals(GridWrite.CLEARED, sink.writeGrid(art,tiles,true) { true })
        assertEquals(listOf(1,0),sent)
        sink.close()
    }
    @Test fun revisitingAPageSkipsEncodingButNotUploadingAndNewImagesMiss() = runBlocking {
        val images = KeyImages { null }
        var encodes = 0
        val uploads = mutableListOf<Pair<Int, ByteArray>>()
        val keypad = RenderedKeypad({ _, _, _ -> byteArrayOf((++encodes).toByte()) },
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
        val keypad = RenderedKeypad({ _, _, _ -> encodes++; ByteArray(65_000) }, { _, _ -> }, {})
        val keys = List(18) { Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888) }
        for ((i, key) in keys.withIndex()) keypad.setKeyImage(i % 9, key)
        keypad.setKeyImage(8, keys.last())
        assertEquals(18, encodes)
        keypad.setKeyImage(0, keys.first())
        assertEquals(19, encodes)
        keypad.close()
    }

    @Test fun sameRenderCachesOrdinaryAndCoverEncodingsSeparately() = runBlocking {
        val encodedModes = mutableListOf<Boolean>()
        val writes = mutableListOf<Byte>()
        val keypad = RenderedKeypad({ _, _, cover ->
            encodedModes += cover
            byteArrayOf(if (cover) 1 else 0)
        }, { _, jpeg -> writes += jpeg.single() }, {})
        val image = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888)
        repeat(2) {
            keypad.setKeyImage(0, image, coverArt = true)
            keypad.setKeyImage(0, image)
        }
        assertEquals(listOf(true, false), encodedModes)
        assertEquals(listOf<Byte>(1, 0, 1, 0), writes)
        keypad.close()
    }

    @Test fun invalidatedEncodeIsNotAcknowledgedOrCached() = runBlocking {
        var encodes = 0
        var uploads = 0
        lateinit var keypad: RenderedKeypad
        keypad = RenderedKeypad({ _, _, _ ->
            if (++encodes == 1) keypad.clearImages()
            byteArrayOf(1)
        }, { _, _ -> uploads++ }, {})
        val image = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888)
        assertFalse(keypad.setKeyImage(2, image, coverArt = true))
        assertTrue(keypad.setKeyImage(2, image, coverArt = true))
        assertEquals(2, encodes)
        assertEquals(1, uploads)
        keypad.close()
    }
}
