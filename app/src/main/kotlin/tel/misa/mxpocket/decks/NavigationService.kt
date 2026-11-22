package tel.misa.mxpocket.decks

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo as Node
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.KeypadCopy as K

/** No event subscriptions, text extraction, window enumeration or persistent node references. */
class NavigationService : AccessibilityService() {
    companion object {
        internal var connected: NavigationService? = null
            private set
    }
    internal var shortcutAvailable = false
        private set

    override fun onServiceConnected() {
        connected = this
        refreshShortcut()
    }
    override fun onSystemActionsChanged() = refreshShortcut()
    private fun refreshShortcut() {
        shortcutAvailable = Build.VERSION.SDK_INT >= 31 && systemActions.orEmpty().any { it.id == GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT }
    }
    internal fun runAccessibilityShortcut(): DeckResult {
        if (!isUnlockedAndInteractive()) return unavailable(K.UnlockNavigation.text())
        refreshShortcut()
        return if (Build.VERSION.SDK_INT >= 31 && shortcutAvailable && performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT))
            ok(K.RequestedControl.text(K.AccessibilityShortcut.text()))
        else unavailable(AccessCopy.ShortcutUnavailable.text())
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean {
        if (connected === this) connected = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (connected === this) connected = null
        super.onDestroy()
    }
    internal fun cameraPress(command: DeckCommand, authorized: () -> Boolean): DeckResult {
        val mode = when (command) {
            DeckCommand.SHUTTER -> CameraMode.PHOTO
            DeckCommand.START_VIDEO, DeckCommand.STOP_VIDEO -> CameraMode.VIDEO
            else -> return unavailable(K.CameraExpected.text())
        }
        val target = viewfinder(mode)?.component ?: return unavailable(K.ChooseCamera.text())
        val accepted = clickCameraControl(target.packageName, command, { rootInActiveWindow }) {
            authorized() && viewfinder(mode)?.component == target
        }
        return if (accepted) ok(K.CameraRequested.text(command.displayLabel))
        else unavailable(K.NoCameraControl.text(command.displayLabel))
    }
    internal fun press(command: DeckCommand): DeckResult {
        if (!isUnlockedAndInteractive()) return unavailable(K.UnlockNavigation.text())
        val accepted = when (command) {
            DeckCommand.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            DeckCommand.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            DeckCommand.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            DeckCommand.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            DeckCommand.ALL_APPS -> Build.VERSION.SDK_INT >= 31 && performGlobalAction(GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)
            else -> element(command)
        }
        return if (accepted) ok(K.RequestedControl.text(command.displayLabel)) else unavailable(K.NoScreenControl.text(command.displayLabel))
    }
    @Suppress("DEPRECATION")
    private fun element(command: DeckCommand): Boolean {
        val root = rootInActiveWindow ?: return false
        // Never use node actions to approve a grant, unlock a device, or drive our own setup UI.
        val pkg = root.packageName?.toString()
        if (pkg == null || pkg == packageName || pkg == "android" || pkg == "com.android.systemui" ||
            pkg == "com.android.settings" || pkg.endsWith(".permissioncontroller") || pkg.endsWith(".packageinstaller") ||
            pkg == packageManager.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName) {
            root.recycle()
            return false
        }
        val accessibilityFocused = root.findFocus(Node.FOCUS_ACCESSIBILITY)
        val focused = accessibilityFocused ?: root.findFocus(Node.FOCUS_INPUT)
        try {
            val source = focused ?: root
            if (source.isPassword) return false
            return when (command) {
                DeckCommand.ACTIVATE -> focused?.takeIf { it.isEnabled && it.isClickable }?.performAction(Node.ACTION_CLICK) == true
                DeckCommand.LONG_PRESS -> focused?.takeIf { it.isEnabled && it.isLongClickable }?.performAction(Node.ACTION_LONG_CLICK) == true
                DeckCommand.FORWARD, DeckCommand.REVERSE, DeckCommand.DIRECTION_UP, DeckCommand.DIRECTION_DOWN,
                DeckCommand.DIRECTION_LEFT, DeckCommand.DIRECTION_RIGHT -> {
                    if (command in setOf(DeckCommand.REVERSE, DeckCommand.FORWARD) &&
                        getSystemService(AccessibilityManager::class.java).isTouchExplorationEnabled)
                        return dispatchTalkBackSwipe(command == DeckCommand.FORWARD)
                    if (accessibilityFocused != null && command in directionalCommands)
                        return moveAccessibilityFocus(root, accessibilityFocused, command)
                    if (accessibilityFocused != null && command in setOf(DeckCommand.REVERSE, DeckCommand.FORWARD))
                        return moveReadingFocus(root, accessibilityFocused, command == DeckCommand.REVERSE)
                    val target = source.focusSearch(focusDirection(command))
                        ?: return focused == null && firstMatch(root, reverse = command == DeckCommand.REVERSE) {
                            it.isFocusable && it.performAction(Node.ACTION_FOCUS)
                        }
                    try { !target.isPassword && target.isEnabled && target.performAction(Node.ACTION_FOCUS) }
                    finally { target.recycle() }
                }
                DeckCommand.UP, DeckCommand.DOWN -> {
                    val action = if (command == DeckCommand.UP) Node.ACTION_SCROLL_BACKWARD else Node.ACTION_SCROLL_FORWARD
                    // Many touch-first screens do not give any item input or accessibility focus.
                    if (focused == null) return firstMatch(root) { it.isScrollable && it.performAction(action) }
                    var node: Node? = Node.obtain(source)
                    try {
                        repeat(32) {
                            val current = node ?: return false
                            if (current.isScrollable && current.performAction(action)) return true
                            node = current.parent
                            current.recycle()
                        }
                        false
                    } finally { node?.recycle() }
                }
                else -> false
            }
        } finally {
            focused?.recycle()
            root.recycle()
        }

    }

    private fun dispatchTalkBackSwipe(forward: Boolean): Boolean {
        val display = resources.displayMetrics
        val y = display.heightPixels * 0.5f
        val left = display.widthPixels * 0.35f
        val right = display.widthPixels * 0.65f
        if (left >= right || y <= 0f) return false
        val path = Path().apply {
            moveTo(if (forward) left else right, y)
            lineTo(if (forward) right else left, y)
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 200)).build()
        return dispatchGesture(gesture, null, null)
    }

    @Suppress("DEPRECATION")
    private fun moveReadingFocus(root: Node, source: Node, reverse: Boolean): Boolean {
        val nodes = mutableListOf<Node>()
        var remaining = 256
        fun visit(node: Node, depth: Int) {
            if (--remaining < 0 || depth > 16 || !node.isVisibleToUser || node.isPassword) return
            if (node.isEnabled && node.isImportantForAccessibility &&
                (node.isAccessibilityFocused || node.actionList.any { it.id == Node.ACTION_ACCESSIBILITY_FOCUS }))
                nodes += Node.obtain(node)
            for (i in 0 until node.childCount) {
                if (remaining <= 0) break
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        visit(root, 0)
        return try {
            val index = nodes.indexOfFirst { it == source || it.isAccessibilityFocused }
            val next = if (reverse) index - 1 else index + 1
            index >= 0 && nodes.getOrNull(next)?.performAction(Node.ACTION_ACCESSIBILITY_FOCUS) == true
        } finally { nodes.forEach { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun moveAccessibilityFocus(root: Node, source: Node, command: DeckCommand): Boolean {
        val origin = Rect().also(source::getBoundsInScreen)
        var remaining = 256
        var best: Node? = null
        var bestScore = Long.MAX_VALUE
        fun visit(node: Node, depth: Int) {
            if (--remaining < 0 || depth > 16 || !node.isVisibleToUser || node.isPassword) return
            if (node.isEnabled && !node.isAccessibilityFocused && node.isImportantForAccessibility &&
                node.actionList.any { it.id == Node.ACTION_ACCESSIBILITY_FOCUS }) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val score = directionalScore(origin, bounds, command)
                if (score != null && score < bestScore) {
                    best?.recycle()
                    best = Node.obtain(node)
                    bestScore = score
                }
            }
            for (i in 0 until node.childCount) {
                if (remaining <= 0) break
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        visit(root, 0)
        return try { best?.performAction(Node.ACTION_ACCESSIBILITY_FOCUS) == true } finally { best?.recycle() }
    }

    /** Only on an explicit key press, and only when Android cannot identify a focus anchor. */
    @Suppress("DEPRECATION")
    private fun firstMatch(root: Node, reverse: Boolean = false, act: (Node) -> Boolean): Boolean {
        var remaining = 64
        fun visit(node: Node, depth: Int): Boolean {
            if (--remaining < 0 || depth > 12 || node.isPassword || !node.isVisibleToUser) return false
            if (node.isEnabled && act(node)) return true
            val count = node.childCount
            for (i in 0 until minOf(count, remaining.coerceAtLeast(0))) {
                val child = node.getChild(if (reverse) count - 1 - i else i) ?: continue
                try { if (visit(child, depth + 1)) return true } finally { child.recycle() }
                if (remaining <= 0) break
            }
            return false
        }
        return visit(root, 0)
    }
}

private val directionalCommands = setOf(DeckCommand.DIRECTION_UP, DeckCommand.DIRECTION_DOWN,
    DeckCommand.DIRECTION_LEFT, DeckCommand.DIRECTION_RIGHT)

internal fun directionalScore(origin: Rect, target: Rect, command: DeckCommand): Long? {
    if (origin.isEmpty || target.isEmpty) return null
    val dx = target.centerX().toLong() - origin.centerX()
    val dy = target.centerY().toLong() - origin.centerY()
    val (forward, sideways) = when (command) {
        DeckCommand.DIRECTION_UP -> -dy to kotlin.math.abs(dx)
        DeckCommand.DIRECTION_DOWN -> dy to kotlin.math.abs(dx)
        DeckCommand.DIRECTION_LEFT -> -dx to kotlin.math.abs(dy)
        DeckCommand.DIRECTION_RIGHT -> dx to kotlin.math.abs(dy)
        else -> return null
    }
    if (forward <= 0) return null
    return 13 * forward * forward + sideways * sideways
}

internal fun focusDirection(command: DeckCommand): Int = when (command) {
    DeckCommand.DIRECTION_UP -> View.FOCUS_UP
    DeckCommand.DIRECTION_DOWN -> View.FOCUS_DOWN
    DeckCommand.DIRECTION_LEFT -> View.FOCUS_LEFT
    DeckCommand.DIRECTION_RIGHT -> View.FOCUS_RIGHT
    DeckCommand.REVERSE -> View.FOCUS_BACKWARD
    DeckCommand.FORWARD -> View.FOCUS_FORWARD
    else -> error("Not a focus direction")
}
