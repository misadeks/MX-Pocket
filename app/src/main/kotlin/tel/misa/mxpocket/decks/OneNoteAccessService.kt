package tel.misa.mxpocket.decks

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import tel.misa.mxpocket.plugins.OneNoteSettings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node

/**
 * Optional, user-enabled service scoped to the OneNote package. The ink toolbar exposes one node
 * per tool, identified by its content description ("Red color, thickness 10.00 percent, pen
 * options", "Stroke Eraser", ...); the active tool is the selected one. No event subscriptions,
 * no note content extraction, no persistent node references.
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
     * Click the toolbar tool with this resource-id suffix. If it is already active, long-press it
     * instead: that is how OneNote opens the tool's settings in the app. In home view the ink
     * toolbar is hidden, so open the Draw tab first when the tool is not found.
     */
    internal fun selectToolById(id: String): Boolean = withRoot { root ->
        var target = findNode(root) { it.contentDescription?.toString() == id }
        if (target == null && clickTab(root, "draw")) {
            target = findNode(root) { it.contentDescription?.toString() == id }
        }
        if (target == null) return@withRoot false
        val active = findNode(root, ::isActiveTool)
        val isActive = active?.contentDescription?.toString() == id
        active?.recycle()
        clickNode(target, if (isActive) Node.ACTION_LONG_CLICK else Node.ACTION_CLICK)
    } ?: false

    /** The ink toolbar's tools as (description, description); the description is the stable identity. */
    internal fun toolBar(): List<Pair<String, String>> = withRoot { root ->
        findNodes(root) { isToolDesc(it.contentDescription?.toString()) }.map {
            val desc = it.contentDescription.toString()
            it.recycle()
            desc to desc
        }
    } ?: emptyList()

    internal fun settings(): OneNoteSettings? = withRoot { root ->
        val slider = findNode(root) { it.viewIdResourceName?.endsWith(":id/stroke_width_modern") == true }
            ?: return@withRoot null
        val range = slider.rangeInfo
        val current = range?.current
        val min = range?.min
        val max = range?.max
        slider.recycle()
        if (current == null || min == null || max == null || max <= min) return@withRoot null
        val nodes = findNodes(root) { it.viewIdResourceName?.endsWith(":id/color_preview_holder") == true }
        val colors = nodes.mapNotNull { it.text?.toString() }
        val selected = nodes.firstOrNull { it.isSelected }?.text?.toString()
        nodes.forEach { it.recycle() }
        OneNoteSettings(colors, selected, current, min, max)
    }

    internal fun changeSetting(color: String?, delta: Int, close: Boolean): Boolean = withRoot { root ->
        val slider = findNode(root) { it.viewIdResourceName?.endsWith(":id/stroke_width_modern") == true }
            ?: return@withRoot false
        if (close) {
            slider.recycle()
            return@withRoot performGlobalAction(GLOBAL_ACTION_BACK)
        }
        if (color != null) {
            slider.recycle()
            val node = findNode(root) { it.viewIdResourceName?.endsWith(":id/color_preview_holder") == true && it.text?.toString() == color }
                ?: return@withRoot false
            return@withRoot clickNode(node, Node.ACTION_CLICK)
        }
        if (delta == 0) { slider.recycle(); return@withRoot false }
        val scroll = if (delta > 0) Node.ACTION_SCROLL_FORWARD else Node.ACTION_SCROLL_BACKWARD
        if (slider.performAction(scroll)) { slider.recycle(); return@withRoot true }
        val range = slider.rangeInfo
        if (range == null) { slider.recycle(); return@withRoot false }
        val value = (range.current + delta * (range.max - range.min) / 20f).coerceIn(range.min, range.max)
        val args = Bundle().apply { putFloat(Node.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
        val success = slider.performAction(Node.AccessibilityAction.ACTION_SET_PROGRESS.id, args)
        slider.recycle()
        success
    } ?: false

    /** Content description of the currently selected draw tool, for the keypad display. */
    internal fun activeTool(): String? = withRoot { root ->
        val node = findNode(root, ::isActiveTool)
        val desc = node?.contentDescription?.toString()
        node?.recycle()
        desc
    }

    /** The active ribbon tab is also selected=true; only tool previews carry a tool description. */
    private fun isActiveTool(node: Node): Boolean = node.isSelected && isToolDesc(node.contentDescription?.toString())

    private fun isToolDesc(desc: String?): Boolean {
        val d = desc?.lowercase().orEmpty()
        return "options" in d || "eraser" in d || "lasso" in d || "shape select" in d
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

    /** Exact tab match: a keyword like "draw" would also hit "Stop Drawing Button". */
    private fun clickTab(root: Node, name: String): Boolean {
        val found = findNode(root) {
            (it.contentDescription?.toString() ?: it.text?.toString())?.equals(name, ignoreCase = true) == true
        } ?: return false
        return clickNode(found, Node.ACTION_CLICK)
    }

    private fun clickNode(node: Node, action: Int): Boolean {
        var current: Node? = node
        while (current != null && !current.isClickable) {
            val parent = current.parent
            current.recycle()
            current = parent
        }
        val clicked = current?.performAction(action) == true
        current?.recycle()
        return clicked
    }

    @Suppress("DEPRECATION")
    private fun findNodes(root: Node, predicate: (Node) -> Boolean): List<Node> {
        val found = mutableListOf<Node>()
        var remaining = 128
        fun visit(node: Node, depth: Int) {
            if (--remaining < 0 || depth > 12 || !node.isVisibleToUser) return
            if (predicate(node)) {
                found += Node.obtain(node)
                return
            }
            val count = node.childCount
            for (i in 0 until minOf(count, remaining.coerceAtLeast(0))) {
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
                if (remaining <= 0) break
            }
        }
        visit(root, 0)
        return found
    }

    @Suppress("DEPRECATION")
    private fun findNode(root: Node, predicate: (Node) -> Boolean): Node? {
        var remaining = 128
        fun visit(node: Node, depth: Int): Node? {
            if (--remaining < 0 || depth > 12 || !node.isVisibleToUser) return null
            // Return a copy: the caller's finally recycles the original node.
            if (predicate(node)) return Node.obtain(node)
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
