package tel.misa.mxpocket

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.view.View
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
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.focusDirection
import tel.misa.mxpocket.decks.directionalScore
import tel.misa.mxpocket.shared.ui.toGridTile
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w640dp-h800dp-mdpi", application = KeypadSessionTest.FakeApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationPadTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    @Test fun arrowKeysUseSpatialFocusDirections() {
        assertEquals(View.FOCUS_UP, focusDirection(DeckCommand.DIRECTION_UP))
        assertEquals(View.FOCUS_DOWN, focusDirection(DeckCommand.DIRECTION_DOWN))
        assertEquals(View.FOCUS_LEFT, focusDirection(DeckCommand.DIRECTION_LEFT))
        assertEquals(View.FOCUS_RIGHT, focusDirection(DeckCommand.DIRECTION_RIGHT))
        val origin = Rect(100, 100, 150, 150)
        val right = Rect(160, 100, 210, 150)
        assertNotNull(directionalScore(origin, right, DeckCommand.DIRECTION_RIGHT))
        assertNull(directionalScore(origin, right, DeckCommand.DIRECTION_LEFT))
        assertTrue(directionalScore(origin, right, DeckCommand.DIRECTION_RIGHT)!! <
            directionalScore(origin, Rect(160, 200, 210, 250), DeckCommand.DIRECTION_RIGHT)!!)
    }
    @Test fun readOnlyPanelMirrorsLcdWritesAndPhysicalBottomRightOpensAndReturns() {
        val app = RuntimeEnvironment.getApplication() as KeypadSessionTest.FakeApp
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val session = app.session
        val main = shadowOf(Looper.getMainLooper())
        val activity = ui.activity
        session.selectProfile("navigation")
        app.connectKeypad()
        try {
            val board = ComposeMirror(ui)
            fun key(name: String) = ui.onNodeWithContentDescription(name, substring = true)
            fun render(): Bitmap {
                val frame = session.frame()
                val bitmaps = frame.keys.mapIndexed(session::keyImage)
                val panel = board.render(frame.keys, bitmaps)
                for (i in frame.keys.indices) {
                    assertAperturePixels(panel, i, app.lcd.getValue(i))
                    val gridTile = frame.keys[i].toGridTile(true)
                    val description = gridTile.accessibleName ?: listOf("Key ${i + 1}", gridTile.label, gridTile.footer)
                        .filter(String::isNotBlank).joinToString(", ")
                    ui.onNodeWithContentDescription(description)
                        .assertHasNoClickAction().assertHeightIsAtLeast(84.dp)
                }
                return panel
            }
            val off = render()
            key("Back in MX Pocket").assertExists()
            key("Move focus backward").assertExists()
            key("Activate focused item").assertExists()
            key("Recents, Needs access").assertExists()
            assertFalse(session.frame().keys[2].enabled)
            key("More").assertExists()
            fun bottomRight(down: Boolean) {
                app.emit(tel.misa.mxpocket.driver.KeyEvent(9, down)); main.idle()
            }
            val previousLaunch = shadowOf(activity).peekNextStartedActivity()
            bottomRight(true)
            assertEquals("accessibility", session.deck.state.value.activePage.id)
            bottomRight(false)
            assertEquals("accessibility", session.deck.state.value.activePage.id)
            assertSame(previousLaunch, shadowOf(activity).peekNextStartedActivity())
            render()
            val more = session.frame().keys
            assertEquals(listOf("Notifications", "Apps list", "Long press"), more.take(3).map { it.label })
            assertTrue(more.take(3).all { it.enabled && !it.showFooter })
            assertTrue(more.slice(3..5).none { it.enabled })
            key("TalkBack settings").assertExists()
            key("Access info, System nav off").assertExists()
            key("Back to Navigation").assertExists()
            app.press(8)
            val opened = shadowOf(activity).nextStartedActivity
            assertEquals(DeckAccessActivity::class.java.name, opened.component?.className)
            assertEquals("navigation.access", opened.getStringExtra("access"))
            assertNull(NavigationService.connected)
            app.press(9)
            assertEquals("root", session.deck.state.value.activePage.id)
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
                key("More").assertExists()
                assertTrue(session.frame().keys[2].enabled)
                app.press(3)
                assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_RECENTS), shadowOf(service).globalActionsPerformed)
                app.press(9)
                assertTrue(session.frame().keys.take(3).all { it.enabled })
                app.press(1)
                assertEquals(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, shadowOf(service).globalActionsPerformed.last())
                app.press(9)
            } finally { connected.destroy() }
            main.idleFor(Duration.ofSeconds(1)); render()
            key("Recents, Needs access").assertExists()
            assertFalse(session.frame().keys[2].enabled)
            val custom = DeckKey(label = "My select", icon = "X", color = 0xff344879.toInt(),
                action = ActionCall("navigation.activate"))
            session.deck.setKey("navigation", "root", 3, custom).getOrThrow()
            main.idle()
            render()
            key("My select").assertExists()
            val tile = session.frame().keys[3]
            assertEquals(custom.color, tile.background)
            assertEquals("X", tile.icon)
            val image = session.keyImage(3, tile)
            assertFalse(image.sameAs(session.keyImage(3, tile.copy(icon = ""))))
            assertTrue(image.sameAs(session.keyImage(3, tile.copy(glyph = tel.misa.mxpocket.plugins.NavigationGlyph.HOME))))
        } finally { session.disconnect() }
    }
}
