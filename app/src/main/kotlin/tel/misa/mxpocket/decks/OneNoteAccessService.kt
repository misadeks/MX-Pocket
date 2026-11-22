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
        if (id.startsWith("style:") || id.startsWith("size:")) {
            val prefix = id.substringBefore(':')
            val listId = if (prefix == "style") "stylesList" else "fontSizeList"
            val list = findNode(root) { it.viewIdResourceName?.endsWith(":id/$listId") == true }
                ?: return@withRoot false
            val result = when (id) {
                "$prefix:previous" -> list.performAction(Node.ACTION_SCROLL_BACKWARD)
                "$prefix:next" -> list.performAction(Node.ACTION_SCROLL_FORWARD)
                "$prefix:cancel" -> performGlobalAction(GLOBAL_ACTION_BACK)
                else -> {
                    val item = findNode(list) { it.viewIdResourceName?.endsWith(":id/textView") == true &&
                        it.text?.toString() == id.removePrefix("$prefix:") }
                    item?.let { clickNode(it, Node.ACTION_CLICK) } ?: false
                }
            }
            list.recycle()
            return@withRoot result
        }
        if (id == "text:draw") return@withRoot clickTab(root, "draw")
        if (id.startsWith("text:")) {
            val ribbon = findNode(root) { it.viewIdResourceName?.endsWith(":id/home_ribbon") == true }
                ?: return@withRoot false
            val control = findNode(ribbon) { it.viewIdResourceName == id.removePrefix("text:").removePrefix("selected:") && it.isEnabled }
            ribbon.recycle()
            return@withRoot control?.let { clickNode(it, Node.ACTION_CLICK) } ?: false
        }
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
        val styles = findNode(root) { it.viewIdResourceName?.endsWith(":id/stylesList") == true ||
            it.viewIdResourceName?.endsWith(":id/fontSizeList") == true }
        if (styles != null) {
            val prefix = if (styles.viewIdResourceName?.endsWith(":id/fontSizeList") == true) "size" else "style"
            val entries = findNodes(styles) { it.viewIdResourceName?.endsWith(":id/textView") == true }
            val items = entries.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) }.take(6)
                .map { "$prefix:$it" to it }.toMutableList()
            entries.forEach { it.recycle() }
            while (items.size < 6) items.add("" to "")
            val actions = styles.actionList.map { it.id }
            items.add(if (Node.ACTION_SCROLL_BACKWARD in actions) "$prefix:previous" to "Previous" else "" to "")
            items.add("$prefix:cancel" to "Cancel")
            items.add(if (Node.ACTION_SCROLL_FORWARD in actions) "$prefix:next" to "Next" else "" to "")
            styles.recycle()
            return@withRoot items
        }
        val ribbon = findNode(root) { it.viewIdResourceName?.endsWith(":id/home_ribbon") == true }
        if (ribbon != null) {
            val ids = listOf("button_fontsize", "button_bold", "button_italic", "button_underline", "button_strikethrough",
                "button_fontcolor", "button_highlight", "button_bulletlist", "button_numberlist", "button_decreaseindent",
                "button_increaseindent", "button_leftalign", "button_centeralign", "button_rightalign", "button_styles")
            val labels = listOf("Font size", "Bold", "Italic", "Underline", "Strikethrough", "Font color", "Highlight",
                "Bullets", "Numbering", "Indent −", "Indent +", "Align left", "Align center", "Align right", "Styles")
            val nodes = findNodes(ribbon) { node -> ids.any { node.viewIdResourceName?.endsWith(":id/$it") == true } }
            val result = ids.mapIndexed { index, id ->
                val node = nodes.firstOrNull { it.viewIdResourceName?.endsWith(":id/$id") == true && it.isEnabled }
                if (node == null) "" to "" else "text:${if (node.isSelected || node.isChecked) "selected:" else ""}${node.viewIdResourceName}" to labels[index]
            }
            nodes.forEach { it.recycle() }
            ribbon.recycle()
            return@withRoot result
        }
        findNodes(root) { isToolDesc(it.contentDescription?.toString()) }.map {
            val desc = it.contentDescription.toString()
            it.recycle()
            desc to desc
        }
    } ?: emptyList()

    internal fun settings(): OneNoteSettings? = withRoot { root ->
        val slider = findNode(root) { it.viewIdResourceName?.endsWith(":id/stroke_width_modern") == true }
        if (slider == null) {
            val nodes = findNodes(root) { it.viewIdResourceName?.endsWith(":id/color_preview_holder") == true }
            val named = nodes.filter { !it.text.isNullOrBlank() }
            val colors = named.map { it.text.toString() }
            val selectedIndex = named.indexOfFirst { it.isSelected }
            val highlight = colors.any { it in setOf("Lemon Lime", "Rose", "Pale Blue", "Lavender") }
            val ids = colors.mapIndexed { index, name -> "color:$index:$name" }
            val values = if (highlight) colors.mapIndexed { index, name ->
                val occurrence = colors.take(index).count { it == name }
                when (name) {
                    "Yellow" -> 0xffffff00; "Lemon Lime" -> 0xff00ff00; "Aqua" -> 0xff00ffff
                    "Pink" -> 0xffff00ff; "Blue" -> if (occurrence == 0) 0xff0000ff else 0xff000080
                    "Red" -> 0xffff0000; "Green" -> 0xff008000; "Dark Purple" -> 0xff800080
                    "Light Yellow" -> 0xffffff99; "Light Green" -> if (occurrence == 0) 0xffccffcc else 0xff92d050
                    "Sky Blue" -> 0xffccffff; "Rose" -> 0xffff99cc; "Pale Blue" -> 0xff99ccff
                    "Teal" -> 0xff33cccc; "Lavender" -> 0xffcc99ff; else -> null
                }?.toInt()
            } else emptyList()
            nodes.forEach { it.recycle() }
            return@withRoot if (colors.isEmpty()) null else OneNoteSettings(colors, colors.getOrNull(selectedIndex),
                0f, 0f, 1f, textColor = true, colorIds = ids, colorValues = values, selectedIndex = selectedIndex)
        }
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
        if (slider == null) {
            val nodes = findNodes(root) { it.viewIdResourceName?.endsWith(":id/color_preview_holder") == true }
            if (nodes.isEmpty()) return@withRoot false
            val parts = color?.split(':', limit = 3)
            val target = if (parts?.size == 3 && parts[0] == "color") {
                nodes.filter { !it.text.isNullOrBlank() }.getOrNull(parts[1].toIntOrNull() ?: -1)
                    ?.takeIf { it.text?.toString() == parts[2] }
            } else nodes.firstOrNull { it.text?.toString() == color }
            val result = if (close) performGlobalAction(GLOBAL_ACTION_BACK)
                else if (target != null) target.performAction(Node.ACTION_CLICK) else false
            nodes.forEach { it.recycle() }
            return@withRoot result
        }
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
