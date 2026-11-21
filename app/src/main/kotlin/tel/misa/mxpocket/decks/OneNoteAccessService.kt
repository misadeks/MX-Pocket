package tel.misa.mxpocket.decks

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node

/**
 * Optional, user-enabled service scoped to the OneNote package. A keypad key clicks the visible
 * toolbar control whose text matches (Pen, Eraser, ...). No event subscriptions, no text
 * extraction, no persistent node references.
 */
class OneNoteAccessService : AccessibilityService() {
    companion object {
        const val ONENOTE_PACKAGE = "com.microsoft.office.onenote"
        internal var connected: OneNoteAccessService? = null
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

    /** Click the first clickable ancestor of a visible node whose text contains [text]. */
    @Suppress("DEPRECATION")
    internal fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            if (root.packageName?.toString() != ONENOTE_PACKAGE) return false
            val matches = root.findAccessibilityNodeInfosByText(text)
            if (matches.isEmpty()) return false
            var node: Node? = matches[0]
            matches.drop(1).forEach { it.recycle() }
            while (node != null && !node.isClickable) {
                val parent = node.parent
                node.recycle()
                node = parent
            }
            val clicked = node?.performAction(Node.ACTION_CLICK) == true
            node?.recycle()
            return clicked
        } finally {
            root.recycle()
        }
    }

    /**
     * Select a draw tool. In OneNote's home view the draw toolbar is hidden, so open the Draw
     * tab first when the tool is not visible. Re-tapping the active tool is OneNote's native
     * gesture for opening that tool's settings.
     */
    internal fun selectTool(text: String): Boolean =
        clickText(text) || (clickText("Draw") && clickText(text))

    /** Text of the currently checked toolbar control, for the keypad display. */
    @Suppress("DEPRECATION")
    internal fun activeTool(): String? {
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != ONENOTE_PACKAGE) return null
            return firstChecked(root)?.text?.toString()
        } finally {
            root.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun firstChecked(root: Node): Node? {
        var remaining = 64
        fun visit(node: Node, depth: Int): Node? {
            if (--remaining < 0 || depth > 12 || !node.isVisibleToUser) return null
            val text = node.text?.toString()
            if (node.isChecked && !text.isNullOrBlank()) return node
            val count = node.childCount
            for (i in 0 until minOf(count, remaining.coerceAtLeast(0))) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1)?.let { return it } } finally { child.recycle() }
                if (remaining <= 0) break
            }
            return null
        }
        return visit(root, 0)
    }
}
