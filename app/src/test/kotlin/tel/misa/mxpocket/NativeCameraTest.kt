package tel.misa.mxpocket

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo as Node
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import tel.misa.mxpocket.decks.CameraMode
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.clickCameraControl
import tel.misa.mxpocket.decks.viewfinder

// Synthetic handlers/nodes: no device inspection, launch or capture.
internal const val TEST_CAMERA = "sample.nativecamera"
internal fun installTestCamera(context: Context, pkg: String = TEST_CAMERA): ComponentName {
    val target = ComponentName(pkg, "$pkg.Viewfinder")
    CameraMode.entries.forEach { mode ->
        val info = ResolveInfo().apply {
            isDefault = true
            activityInfo = ActivityInfo().apply {
                packageName = pkg; name = target.className; exported = true; enabled = true
                applicationInfo = ApplicationInfo().apply { packageName = pkg; enabled = true }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(mode.action), info)
    }
    return target
}

@Suppress("DEPRECATION")
internal fun cameraNode(description: String? = null, pkg: String = TEST_CAMERA) = Node.obtain(View(RuntimeEnvironment.getApplication())).apply {
    packageName = pkg
    isEnabled = true
    isVisibleToUser = true
    if (description != null) {
        contentDescription = description
        className = "android.widget.ImageButton"
        isClickable = true
        addAction(Node.AccessibilityAction.ACTION_CLICK)
    }
}

@Implements(AccessibilityService::class)
class CameraWindowFixture : ShadowAccessibilityService() {
    var root: Node? = null
    @Suppress("DEPRECATION")
    @Implementation fun getRootInActiveWindow(): Node? = root?.let { Node.obtain(it) }
}

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class NativeCameraTest {
    @Test fun onlyExactVisibleEnabledModeSpecificActionsAreClicked() {
        var clicks = 0
        fun button(label: String) = cameraNode(label).also {
            shadowOf(it).setOnPerformActionListener { action, _ -> assertEquals(Node.ACTION_CLICK, action); clicks++; true }
        }
        fun click(root: Node, command: DeckCommand = DeckCommand.SHUTTER, vararg children: Node): Boolean {
            children.forEach { shadowOf(root).addChild(it) }
            return clickCameraControl(TEST_CAMERA, command, { Node.obtain(root) }) { true }
        }
        for ((label, command) in listOf("Take picture" to DeckCommand.SHUTTER,
            "Record video" to DeckCommand.START_VIDEO, "Stop recording" to DeckCommand.STOP_VIDEO)) {
            assertTrue(click(cameraNode(), command, button(label)))
        }
        assertFalse(click(cameraNode("Take picture", "com.android.permissioncontroller")))
        assertFalse(click(cameraNode(), DeckCommand.SHUTTER, button("Take picture"), button("Take photo")))
        assertFalse(click(cameraNode(), DeckCommand.START_VIDEO, button("Stop recording")))
        assertFalse(click(cameraNode(), DeckCommand.SHUTTER, button("Take picture"), button("Stop recording")))
        assertFalse(click(button("Shutter"))) // Ambiguous photo/video toggle.
        assertFalse(click(button("Take picture").apply { isVisibleToUser = false }))
        assertFalse(click(button("Take picture").apply { isEnabled = false }))
        assertFalse(click(button("Take picture").apply { className = "android.widget.CheckedTextView" }))
        assertFalse(click(button("Take picture").apply { removeAction(Node.AccessibilityAction.ACTION_CLICK) }))
        assertFalse(click(cameraNode().apply { className = "android.app.AlertDialog" },
            DeckCommand.SHUTTER, button("Take picture")))
        val stale = button("Take picture")
        var reads = 0
        assertFalse(clickCameraControl(TEST_CAMERA, DeckCommand.SHUTTER, {
            if (++reads == 1) Node.obtain(stale) else cameraNode(pkg = "android")
        }) { true })
        assertTrue(click(cameraNode(), DeckCommand.STOP_VIDEO, button("Stop recording"), cameraNode("Take picture").also {
            shadowOf(it).setOnPerformActionListener { _, _ -> fail("Stop must not press the snapshot button"); false }
        }))
        assertEquals(4, clicks)
    }

    @Test fun missingOrSystemResolverNeverBecomesAStudioTarget() {
        val app = RuntimeEnvironment.getApplication()
        assertNull(app.viewfinder(CameraMode.VIDEO))
        installTestCamera(app, "android")
        assertNull(app.viewfinder(CameraMode.PHOTO))
    }
}
