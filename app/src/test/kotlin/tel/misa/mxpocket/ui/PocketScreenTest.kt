package tel.misa.mxpocket.ui

import android.content.Intent
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tel.misa.mxpocket.R

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class PocketScreenTest {
    @Test fun renderingUpdatesExistingViewsWithoutEmittingActionsOrLosingSelection() {
        Robolectric.buildActivity(UiPreviewActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val scenes = UiPreviewScenes(activity)
            val events = mutableListOf<PocketScreenEvent>()
            val screen = PocketScreen(activity, scenes.descriptors, events::add)
            activity.setContentView(screen.view)
            screen.render(scenes.state("disconnected"))
            val diagnostics = screen.view.findViewById<TextView>(R.id.pocket_diagnostics)
            val key = screen.view.findViewById<MaterialButton>(R.id.pocket_key_1)
            val selectable = diagnostics.text as android.text.Spannable
            android.text.Selection.setSelection(selectable, 2, 5)
            screen.render(scenes.state("audio").copy(connectionActive = true))
            assertSame(key, screen.view.findViewById(R.id.pocket_key_1))
            assertEquals("Volume -", key.text.toString())
            assertEquals("Disconnect", screen.view.findViewById<TextView>(R.id.pocket_connect).text.toString())
            assertEquals(2, diagnostics.selectionStart)
            assertEquals(5, diagnostics.selectionEnd)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                screen.view.findViewById<View>(R.id.pocket_status).accessibilityLiveRegion)
            assertTrue(events.isEmpty())
            key.performClick()
            assertEquals(listOf(PocketScreenEvent.TapKey(0)), events)
        }
    }

    @Test fun debugPreviewSwitchesScenesButCannotExecutePhoneControls() {
        val intent = Intent().putExtra("scene", "audio")
        Robolectric.buildActivity(UiPreviewActivity::class.java, intent).setup().use { controller ->
            val activity = controller.get()
            val audio = activity.getSystemService(AudioManager::class.java)
            val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            activity.findViewById<MaterialButton>(R.id.pocket_key_1).performClick()
            assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            activity.findViewById<View>(android.R.id.content)
                .findViewWithTag<MaterialButton>("plugin:phone").performClick()
            activity.findViewById<MaterialButton>(R.id.pocket_key_1).performClick()
            assertNull(shadowOf(activity).nextStartedActivity)
            click(activity.window.decorView, activity.getString(R.string.import_image))
            click(activity.window.decorView, activity.getString(R.string.export_log))
            click(activity.window.decorView, activity.getString(R.string.connect))
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertTrue(activity.findViewById<TextView>(R.id.pocket_log).text.contains("No action executed"))
            activity.findViewById<View>(android.R.id.content)
                .findViewWithTag<MaterialButton>("plugin:focus").performClick()
            activity.findViewById<MaterialButton>(R.id.pocket_key_1).performClick()
            assertEquals("Start", activity.findViewById<TextView>(R.id.pocket_key_1).text.toString())
            controller.recreate()
            assertEquals("Focus", controller.get().findViewById<TextView>(R.id.pocket_plugin_title).text.toString())
        }
    }

    @Test fun previewFixturesAreRepeatableAndRejectUnknownScenes() {
        Robolectric.buildActivity(UiPreviewActivity::class.java).setup().use { controller ->
            val scenes = UiPreviewScenes(controller.get())
            for (id in scenes.ids) {
                assertEquals(scenes.state(id), scenes.state(id))
                assertEquals(9, scenes.state(id).frame.keys.size)
            }
            assertThrows(IllegalArgumentException::class.java) { scenes.state("unregistered") }
        }
    }

    private fun click(root: View, label: String) {
        views(root).filterIsInstance<MaterialButton>().single { it.text.toString() == label }.performClick()
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }
}
