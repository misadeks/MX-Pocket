package tel.misa.mxpocket.decks

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node
import tel.misa.mxpocket.isUnlockedAndInteractive

/** No event subscriptions, gesture injection, text extraction, window enumeration or persistent node references. */
class NavigationService : AccessibilityService() {
    companion object {
        internal var connected: NavigationService? = null
            private set
    }
    override fun onServiceConnected() { connected = this }
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
    internal fun press(command: DeckCommand): DeckResult {
        if (!isUnlockedAndInteractive()) return unavailable("Unlock the phone before navigating.")
        val accepted = when (command) {
            DeckCommand.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            DeckCommand.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            DeckCommand.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> element(command)
        }
        return if (accepted) ok("Requested ${command.label}.") else unavailable("This screen does not offer ${command.label}.")
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
}
