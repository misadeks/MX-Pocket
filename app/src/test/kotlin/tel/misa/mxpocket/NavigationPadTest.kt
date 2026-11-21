package tel.misa.mxpocket

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.google.android.material.button.MaterialButton
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
import tel.misa.mxpocket.ui.KeypadPanel
import tel.misa.mxpocket.ui.PocketScreen
import tel.misa.mxpocket.ui.PocketScreenEvent
import tel.misa.mxpocket.ui.PocketScreenState
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationPadTest {
    @Test fun livePadMirrorsEachWindowAndAccessInfoNeverTogglesTheService() {
        val app = RuntimeEnvironment.getApplication() as PocketApplication
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val session = app.session
        val main = shadowOf(Looper.getMainLooper())
        Robolectric.buildActivity(MainActivity::class.java).setup().use { host ->
            val activity = host.get()
            session.startPreview(); session.selectProfile("navigation"); main.idle()
            val ui = PocketScreen(activity, session.plugins.descriptors, {
                if (it is PocketScreenEvent.TapKey) session.phoneKey(it.index)
            }, live = true)
            val back = ui.view.findViewById<MaterialButton>(R.id.pocket_key_1)
            val panel = back.parent as KeypadPanel
            fun key(i: Int) = panel.getChildAt(i) as MaterialButton
            fun render(): Bitmap {
                val frame = session.frame()
                val bitmaps = frame.keys.mapIndexed(session::keyImage)
                ui.render(PocketScreenState("", "Navigation", frame, rendered = bitmaps))
                val exact = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
                panel.measure(exact, exact); panel.layout(0, 0, 480, panel.measuredHeight)
                val preview = Bitmap.createBitmap(480, panel.height, Bitmap.Config.ARGB_8888)
                panel.draw(Canvas(preview))
                bitmaps.forEachIndexed { i, tile ->
                    val region = KeypadProtocol.keyRegion(i)
                    val mirrored = Bitmap.createBitmap(preview, region.x, region.y + (panel.height - 480) / 2, 118, 118)
                    assertTrue("Mirror differed from LCD window ${i + 1}", tile.sameAs(mirrored))
                    assertTrue(key(i).height >= activity.resources.getDimensionPixelSize(R.dimen.screen_key_min_height))
                }
                return preview
            }
            val off = render()
            assertTrue(back.contentDescription.contains("Back in MX Pocket"))
            assertTrue(key(3).contentDescription.contains("Move focus backward"))
            assertTrue(key(4).contentDescription.contains("Activate focused item"))
            assertFalse(key(6).isEnabled)
            assertTrue(key(6).contentDescription.contains("Needs access"))
            assertTrue(key(8).contentDescription.contains("System nav off"))
            key(8).performClick()
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
                assertFalse(off.sameAs(on))
                assertTrue(key(6).isEnabled)
                assertTrue(key(8).contentDescription.contains("System nav on"))
                key(6).performClick()
                assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_RECENTS), shadowOf(service).globalActionsPerformed)
            } finally { connected.destroy() }
            main.idleFor(Duration.ofSeconds(1)); render()
            assertFalse(key(6).isEnabled)
            val custom = DeckKey(label = "My select", icon = "X", color = 0xff344879.toInt(),
                action = ActionCall("navigation.activate"))
            session.deck.setKey("navigation", "root", 3, custom).getOrThrow()
            render()
            assertTrue(key(3).contentDescription.startsWith("My select"))
            val tile = session.frame().keys[3]
            assertEquals(custom.color, tile.background)
            assertEquals("X", tile.icon)
            val image = session.keyImage(3, tile)
            assertFalse(image.sameAs(session.keyImage(3, tile.copy(icon = ""))))
            assertTrue(image.sameAs(session.keyImage(3, tile.copy(glyph = tel.misa.mxpocket.plugins.NavigationGlyph.HOME))))
        }
    }
}
