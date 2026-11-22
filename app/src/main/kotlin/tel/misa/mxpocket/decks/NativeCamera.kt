package tel.misa.mxpocket.decks

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo as Node

internal enum class CameraMode(val action: String) {
    PHOTO(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), VIDEO(MediaStore.INTENT_ACTION_VIDEO_CAMERA)
}

private const val SAMSUNG_CAMERA = "com.sec.android.app.camera"

private fun ActivityInfo.isFrameworkResolver() =
    packageName == "android" && name == "com.android.internal.app.ResolverActivity"

private fun ActivityInfo.availableComponent(): ComponentName? =
    if (exported && enabled && applicationInfo?.enabled == true && !packageName.isNullOrEmpty() && !name.isNullOrEmpty())
        ComponentName(packageName, name) else null

@Suppress("DEPRECATION")
private fun PackageManager.uniqueSamsungHandler(action: String): Intent? {
    val intent = Intent(action).setPackage(SAMSUNG_CAMERA)
    val target = queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .mapNotNull { it.activityInfo?.availableComponent() }
        .filter { it.packageName == SAMSUNG_CAMERA }.singleOrNull() ?: return null
    return intent.setComponent(target)
}

/** Resolve the default camera and only use an exported handler that declares the requested action. */
@Suppress("DEPRECATION")
internal fun Context.viewfinder(mode: CameraMode): Intent? {
    val intent = Intent(mode.action)
    val flags = PackageManager.MATCH_DEFAULT_ONLY
    val info = packageManager.resolveActivity(intent, flags)?.activityInfo ?: return null
    if (mode == CameraMode.VIDEO && info.isFrameworkResolver()) {
        // Some Galaxy builds have a concrete Photo default but no Video default. Never pick from the chooser.
        if (viewfinder(CameraMode.PHOTO)?.component?.packageName != SAMSUNG_CAMERA ||
            packageManager.uniqueSamsungHandler(mode.action) == null) return null
        val capture = packageManager.uniqueSamsungHandler(MediaStore.ACTION_VIDEO_CAPTURE) ?: return null
        val captureDefault = packageManager.resolveActivity(Intent(MediaStore.ACTION_VIDEO_CAPTURE), flags)?.activityInfo
            ?: return null
        return capture.takeIf { captureDefault.isFrameworkResolver() || captureDefault.availableComponent() == it.component }
    }
    val target = info.availableComponent() ?: return null
    val pkg = target.packageName
    if (pkg in setOf(packageName, "android", "com.android.systemui", "com.android.settings") ||
        pkg.endsWith(".permissioncontroller") || pkg.endsWith(".packageinstaller") || pkg.endsWith(".intentresolver")) return null
    val handlesViewfinder = packageManager.queryIntentActivities(intent, flags).any { candidate ->
        candidate.activityInfo?.availableComponent() == target
    }
    if (!handlesViewfinder) return null
    if (mode == CameraMode.VIDEO && pkg == SAMSUNG_CAMERA) {
        // Samsung's video-viewfinder action can open Photo; its video capture handler opens Video.
        packageManager.uniqueSamsungHandler(MediaStore.ACTION_VIDEO_CAPTURE)?.let { return it }
    }
    return intent.setComponent(target)
}

internal class CameraControlsAccess(context: Context) {
    private val prefs = context.getSharedPreferences("native-camera-controls", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("physical-keys", false)
        set(value) { prefs.edit().putBoolean("physical-keys", value).apply() }
}

/** Exact, mode-specific accessibility descriptions only. Never match screen text, positions or a generic toggle. */
private fun Node.cameraCommand() = when (contentDescription?.toString()?.trim()?.lowercase(java.util.Locale.ROOT)) {
    "take picture", "take photo", "take a picture", "take a photo" -> DeckCommand.SHUTTER
    "record video", "start recording", "start video recording" -> DeckCommand.START_VIDEO
    "stop recording", "stop video recording" -> DeckCommand.STOP_VIDEO
    else -> null
}

@Suppress("DEPRECATION")
internal fun clickCameraControl(
    pkg: String, command: DeckCommand, activeRoot: () -> Node?, authorized: () -> Boolean
): Boolean {
    if (!command.nativeControl || !authorized()) return false
    val root = activeRoot() ?: return false
    var candidate: Node? = null
    var budget = 256
    fun actionable(node: Node) = node.isVisibleToUser && node.isEnabled && node.isClickable &&
        (node.className == "android.widget.Button" || node.className == "android.widget.ImageButton" ||
            (pkg == SAMSUNG_CAMERA && node.className == "android.view.ViewGroup")) &&
        Node.AccessibilityAction.ACTION_CLICK in node.actionList
    fun inspect(node: Node, depth: Int): Boolean {
        if (--budget < 0 || depth > 24) return false // An incomplete scan cannot establish uniqueness.
        if (!node.isVisibleToUser) return true
        if (node.packageName?.toString() != pkg || node.windowId != root.windowId || node.isPassword ||
            node.isEditable || node.className?.toString()?.contains("Dialog", ignoreCase = true) == true) return false
        // Disabled containers cannot establish an actionable path through their descendants.
        if (!node.isEnabled && node.childCount > 0) return false
        val semantic = if (node.isClickable) node.cameraCommand() else null
        if (semantic != null) {
            if (semantic == command) {
                if (candidate != null || !actionable(node)) return false
                candidate = Node.obtain(node)
            } else if (command != DeckCommand.STOP_VIDEO || semantic != DeckCommand.SHUTTER) {
                return false
            } // A separate still-snapshot button can coexist with Stop while filming. Never click it here.
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: return false
            try { if (!inspect(child, depth + 1)) return false } finally { child.recycle() }
        }
        return true
    }
    try {
        if (!root.isVisibleToUser || !inspect(root, 0)) return false
        val button = candidate ?: return false
        val current = activeRoot() ?: return false
        try {
            return current == root && current.packageName?.toString() == pkg && current.isVisibleToUser &&
                button.refresh() && button.packageName?.toString() == pkg && button.windowId == current.windowId &&
                actionable(button) && button.cameraCommand() == command && authorized() && button.performAction(Node.ACTION_CLICK)
        } finally { current.recycle() }
    } finally {
        candidate?.recycle()
        root.recycle()
    }
}
