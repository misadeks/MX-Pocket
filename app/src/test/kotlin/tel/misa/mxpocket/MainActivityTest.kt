package tel.misa.mxpocket

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityTest {
    @Before fun grantAppOwnedBroadcastPermission() {
        // Android grants the app its own merged signature permission; Robolectric API 28 does not.
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            "tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @Test fun startsWithoutUsbDeviceAndConnectReportsMissingHardware() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            assertEquals("tel.misa.mxpocket", activity.packageName)
            val button = views(activity.window.decorView).filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.connect) }
            button.performClick()
            assertTrue(views(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().startsWith("No MX Creative Keypad") })
        }
    }

    @Test fun previewRunsTimerAndChangesPageWithoutClaimingUsbSuccess() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            fun click(label: String) {
                views(activity.window.decorView).filterIsInstance<MaterialButton>()
                    .first { it.text.toString() == label }.performClick()
            }
            click(activity.getString(R.string.preview))
            click("Start")
            assertTrue(views(activity.window.decorView).filterIsInstance<MaterialButton>().any { it.text == "Pause" })
            click(activity.getString(R.string.next))
            assertTrue(views(activity.window.decorView).filterIsInstance<MaterialButton>().any { it.text == "Play/Pause" })
            click(activity.getString(R.string.next))
            assertTrue(views(activity.window.decorView).filterIsInstance<MaterialButton>().any { it.text == "Camera" })
            assertTrue(views(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().contains("No USB messages are sent") })
            assertFalse(views(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().startsWith("Nine LCD image uploads acknowledged") })
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun homeSummarizesLcdKeysAndShowsGridOnlyForVirtualKeypad() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val grid = activity.findViewById<View>(R.id.pocket_key_grid)
            val state = activity.findViewById<TextView>(R.id.pocket_connection_state)
            assertEquals(View.GONE, grid.visibility)
            assertEquals(activity.getString(R.string.state_not_connected), state.text.toString())
            assertTrue(activity.findViewById<TextView>(R.id.pocket_keypad_summary).text
                .startsWith("▶️ Start, 25:00, 🔄 Reset"))
            views(activity.window.decorView).filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.preview) }.performClick()
            assertEquals(View.VISIBLE, grid.visibility)
            assertEquals(activity.getString(R.string.state_virtual), state.text.toString())
            views(activity.window.decorView).filterIsInstance<MaterialButton>()
                .single { it.text == activity.getString(R.string.stop_simulator) }.performClick()
            assertEquals(View.GONE, grid.visibility)
            assertEquals(activity.getString(R.string.state_not_connected), state.text.toString())
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test fun backgroundLifecycleNeverStartsServiceForAnIdleScreen() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            controller.pause().stop()
            controller.start().resume()
            val activity = controller.get()
            assertTrue(views(activity.window.decorView).filterIsInstance<TextView>()
                .any { it.text == activity.getString(R.string.not_connected) })
            assertNull(shadowOf(activity.application).nextStartedService)
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
    }
}
