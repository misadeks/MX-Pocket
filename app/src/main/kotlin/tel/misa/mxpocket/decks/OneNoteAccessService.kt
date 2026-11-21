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
}
