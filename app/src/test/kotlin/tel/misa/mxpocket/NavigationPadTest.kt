package tel.misa.mxpocket

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.decks.DeckAccessActivity
import tel.misa.mxpocket.decks.NavigationService
import tel.misa.mxpocket.driver.KeypadProtocol
import tel.misa.mxpocket.shared.ui.toGridTile
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w640dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationPadTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun livePadMirrorsEachWindowAndAccessInfoNeverTogglesTheService() {
        val app = RuntimeEnvironment.getApplication() as PocketApplication
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val session = app.session
        val main = shadowOf(Looper.getMainLooper())
        val activity = ui.activity
        run {
            session.startPreview(); session.selectProfile("navigation"); main.idle()
            val board = ComposeMirror(ui, session::phoneKey)
            fun key(name: String) = ui.onNodeWithContentDescription(name, substring = true)
            fun render(): Bitmap {
                val frame = session.frame()
                val bitmaps = frame.keys.mapIndexed(session::keyImage)
                val preview = board.render(frame.keys, bitmaps)
                bitmaps.forEachIndexed { i, tile ->
                    val region = KeypadProtocol.keyRegion(i)
                    assertTrue("Mirror differed from LCD window ${i + 1}",
                        sameThroughKeyWindow(tile, preview, region.x, region.y))
                    val gridTile = frame.keys[i].toGridTile(true)
                    ui.onNodeWithContentDescription(requireNotNull(gridTile.accessibleName)).assertHeightIsAtLeast(84.dp)
                }
                return preview
            }
            val off = render()
            key("Back in MX Pocket").assertExists()
            key("Move focus backward").assertExists()
            key("Activate focused item").assertExists()
            key("Recents, Needs access").assertIsNotEnabled()
            key("Access info, System nav off").performClick()
            val opened = shadowOf(activity).nextStartedActivity
            assertEquals(DeckAccessActivity::class.java.name, opened.component?.className)
            assertEquals("navigation.access", opened.getStringExtra("access"))
            assertNull(NavigationService.connected)
            val connected = Robolectric.buildService(NavigationService::class.java).create()
            val service = connected.get()
            try {
                ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
                main.idleFor(Duration.ofSeconds(1))
                val on = render()
                for ((name, panel) in mapOf("navigation-off" to off, "navigation-on" to on)) {
                    java.io.File("build/reports/player-preview/$name.png").apply {
                        parentFile.mkdirs()
                        outputStream().use { panel.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
                assertFalse(off.sameAs(on))
                key("Access info, System nav on").assertExists()
                key("Recents").assertIsEnabled().performClick()
                assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_RECENTS), shadowOf(service).globalActionsPerformed)
            } finally { connected.destroy() }
            main.idleFor(Duration.ofSeconds(1)); render()
            key("Recents").assertIsNotEnabled()
            val custom = DeckKey(label = "My select", icon = "X", color = 0xff344879.toInt(),
                action = ActionCall("navigation.activate"))
            session.deck.setKey("navigation", "root", 3, custom).getOrThrow()
            render()
            key("My select").assertExists()
            val tile = session.frame().keys[3]
            assertEquals(custom.color, tile.background)
            assertEquals("X", tile.icon)
            val image = session.keyImage(3, tile)
            assertFalse(image.sameAs(session.keyImage(3, tile.copy(icon = ""))))
            assertTrue(image.sameAs(session.keyImage(3, tile.copy(glyph = tel.misa.mxpocket.plugins.NavigationGlyph.HOME))))
        }
    }
}
