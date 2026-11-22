package tel.misa.mxpocket.decks

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node
import tel.misa.mxpocket.isUnlockedAndInteractive
import tel.misa.mxpocket.i18n.AccessCopy
import tel.misa.mxpocket.i18n.KeypadCopy as K

/** No event subscriptions, gesture injection, text extraction, window enumeration or persistent node references. */
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
        val focused = root.findFocus(Node.FOCUS_INPUT) ?: root.findFocus(Node.FOCUS_ACCESSIBILITY)
        try {
            val source = focused ?: root
            if (source.isPassword) return false
            return when (command) {
                DeckCommand.ACTIVATE -> focused?.takeIf { it.isEnabled && it.isClickable }?.performAction(Node.ACTION_CLICK) == true
                DeckCommand.FORWARD, DeckCommand.REVERSE -> {
                    val target = source.focusSearch(if (command == DeckCommand.FORWARD) View.FOCUS_FORWARD else View.FOCUS_BACKWARD)
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
