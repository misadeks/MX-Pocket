package tel.misa.mxpocket.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import tel.misa.mxpocket.shared.ui.*

/** Debug-only screen host. Intentionally does not construct MainActivity or PluginEngine. */
class UiPreviewActivity : AppCompatActivity() {
    private lateinit var scenes: UiPreviewScenes
    private var state by mutableStateOf<PocketScreenState?>(null)
    private lateinit var scene: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scenes = UiPreviewScenes(this)
        scene = savedInstanceState?.getString("scene") ?: intent.getStringExtra("scene") ?: "disconnected"
        state = scenes.state(scene)
        setContent { PocketTheme { state?.let { HomeScreen(it, ::handleEvent) } } }
    }

    private fun handleEvent(event: PocketScreenEvent) {
        when (event) {
            is PocketScreenEvent.SelectPlugin -> scene = if (event.id == tel.misa.mxpocket.deck.PROFILE_HOME) "disconnected" else event.id
            is PocketScreenEvent.ChangePage -> {
                val index = scenes.ids.indexOf(scene)
                scene = scenes.ids[Math.floorMod(index + event.delta, scenes.ids.size)]
            }
            PocketScreenEvent.EnterPreview -> scene = "disconnected"
            else -> {
                state = scenes.state(scene).copy(status = "UI-only event: $event. No action executed.")
                return
            }
        }
        state = scenes.state(scene)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("scene", scene)
        super.onSaveInstanceState(outState)
    }
}
