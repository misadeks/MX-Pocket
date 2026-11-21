package tel.misa.mxpocket.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Debug-only screen host. Intentionally does not construct MainActivity or PluginEngine. */
class UiPreviewActivity : AppCompatActivity() {
    private lateinit var scenes: UiPreviewScenes
    private lateinit var screen: PocketScreen
    private lateinit var scene: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scenes = UiPreviewScenes(this)
        scene = savedInstanceState?.getString("scene") ?: intent.getStringExtra("scene") ?: "disconnected"
        val initial = scenes.state(scene)
        screen = PocketScreen(this, scenes.descriptors, ::handleEvent)
        setContentView(screen.view)
        screen.render(initial)
    }

    private fun handleEvent(event: PocketScreenEvent) {
        when (event) {
            is PocketScreenEvent.SelectPlugin -> scene = event.id
            is PocketScreenEvent.ChangePage -> {
                val index = scenes.ids.indexOf(scene)
                scene = scenes.ids[Math.floorMod(index + event.delta, scenes.ids.size)]
            }
            PocketScreenEvent.EnterPreview -> scene = "disconnected"
            else -> {
                screen.render(scenes.state(scene).copy(log = "UI-only event: $event. No action executed."))
                return
            }
        }
        screen.render(scenes.state(scene))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("scene", scene)
        super.onSaveInstanceState(outState)
    }
}
