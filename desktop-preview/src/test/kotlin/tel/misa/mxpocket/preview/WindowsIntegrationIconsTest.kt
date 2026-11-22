package tel.misa.mxpocket.preview

import androidx.compose.ui.graphics.toPixelMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tel.misa.mxpocket.plugins.IntegrationApp

class WindowsIntegrationIconsTest {
    @Test fun installedStartIconsHaveRealPixels() {
        val icons = windowsIntegrationIcons()
        // Opt in on a host with both Start entries; ordinary test machines may have neither.
        if (System.getenv("MX_TEST_INSTALLED_OFFICE_ICONS") == "1") assertEquals(IntegrationApp.entries.toSet(), icons.keys)
        for (icon in icons.values) {
            val pixels = icon.toPixelMap()
            assertTrue((0 until pixels.height).any { y -> (0 until pixels.width).any { x -> pixels[x, y].alpha > .5f } })
        }
    }
}
