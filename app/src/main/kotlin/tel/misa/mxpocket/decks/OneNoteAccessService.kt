package tel.misa.mxpocket.decks

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node

/**
 * Optional, user-enabled service scoped to the OneNote package. OneNote's draw toolbar exposes
 * tools via content descriptions ("pen options", "Stroke Eraser", ...), not text, and marks the
 * active tool with selected=true. No event subscriptions, no note content extraction, no
 * persistent node references.
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

    /**
     * Select a draw tool by content-description keyword. If the tool is already active, long-press
     * it instead: that is how OneNote opens the tool's settings. In home view the draw toolbar is
     * hidden, so open the Draw tab first when the tool is not visible.
     */
    internal fun selectTool(keyword: String): Boolean = withRoot { root ->
        val selected = findNode(root, ::isActiveTool)
        if (selected != null && label(selected).contains(keyword, ignoreCase = true)) {
            longClick(selected)
        } else {
            clickDesc(root, keyword) || (clickTab(root, "draw") && clickDesc(root, keyword))
        }
    } ?: false

    /** Content description of the currently selected draw tool, for the keypad display. */
    internal fun activeTool(): String? = withRoot { root ->
        findNode(root, ::isActiveTool)?.contentDescription?.toString()
    }

    /** The active ribbon tab is also selected=true; only tool previews carry a tool description. */
    private fun isActiveTool(node: Node): Boolean {
        if (!node.isSelected) return false
        val d = node.contentDescription?.toString()?.lowercase().orEmpty()
        return "options" in d || "eraser" in d || "lasso" in d || "shape" in d
    }

    @Suppress("DEPRECATION")
    private fun <T> withRoot(block: (Node) -> T): T? {
        val root = rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != ONENOTE_PACKAGE) return null
            return block(root)
        } finally {
            root.recycle()
        }
    }

    private fun label(node: Node): String =
        (node.contentDescription?.toString() ?: "") + " " + (node.text?.toString() ?: "")

    private fun clickDesc(root: Node, keyword: String): Boolean {
        val found = findNode(root) { label(it).contains(keyword, ignoreCase = true) } ?: return false
        val target = clickableAncestor(found) ?: return false
        val clicked = target.performAction(Node.ACTION_CLICK)
        target.recycle()
        return clicked
    }

    /** Exact tab match: a keyword like "draw" would also hit "Stop Drawing Button". */
    private fun clickTab(root: Node, name: String): Boolean {
        val found = findNode(root) {
            (it.contentDescription?.toString() ?: it.text?.toString())?.equals(name, ignoreCase = true) == true
        } ?: return false
        val target = clickableAncestor(found) ?: return false
        val clicked = target.performAction(Node.ACTION_CLICK)
        target.recycle()
        return clicked
    }

    private fun longClick(node: Node): Boolean {
        val target = clickableAncestor(node) ?: return false
        val clicked = target.performAction(Node.ACTION_LONG_CLICK)
        target.recycle()
        return clicked
    }

    private fun clickableAncestor(node: Node): Node? {
        var current: Node? = node
        while (current != null && !current.isClickable) {
            val parent = current.parent
            current.recycle()
            current = parent
        }
        return current
    }

    @Suppress("DEPRECATION")
    private fun findNode(root: Node, predicate: (Node) -> Boolean): Node? {
        var remaining = 128
        fun visit(node: Node, depth: Int): Node? {
            if (--remaining < 0 || depth > 12 || !node.isVisibleToUser) return null
            if (predicate(node)) return node
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
