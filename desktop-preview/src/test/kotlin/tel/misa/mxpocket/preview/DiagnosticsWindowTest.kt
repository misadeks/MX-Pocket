package tel.misa.mxpocket.preview

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.awaitApplication
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.WindowEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeFalse
import org.junit.Test
import kotlin.test.assertSame
import tel.misa.mxpocket.shared.ui.DesktopSimulator
import tel.misa.mxpocket.shared.ui.PocketScreenState

class DiagnosticsWindowTest {
    @Test fun openingAgainRestoresTheSameWindowAndClosingDisposesIt() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val screen = PocketScreenState("Disconnected", "Focus", emptyList(), emptyList(), "focus",
            simulator = DesktopSimulator("Simulator"))
        fun windows() = Window.getWindows().filterIsInstance<ComposeWindow>()
            .filter { it.title == "Diagnostics - MX Pocket" && it.isShowing }

        runBlocking {
            withTimeout(30_000) {
                awaitApplication {
                    var request by remember { mutableIntStateOf(1) }
                    if (request != 0) DiagnosticsWindow(screen, request) { request = 0 }
                    LaunchedEffect(Unit) {
                        while (windows().isEmpty()) delay(20)
                        val first = windows().single()
                        first.extendedState = Frame.ICONIFIED
                        while (first.extendedState and Frame.ICONIFIED == 0) delay(20)
                        request++
                        while (first.extendedState and Frame.ICONIFIED != 0) delay(20)
                        assertSame(first, windows().single())
                        first.dispatchEvent(WindowEvent(first, WindowEvent.WINDOW_CLOSING))
                        while (first.isDisplayable) delay(20)
                        exitApplication()
                    }
                }
            }
        }
    }
}
