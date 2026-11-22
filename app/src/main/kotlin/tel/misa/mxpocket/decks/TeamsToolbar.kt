package tel.misa.mxpocket.decks

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node

internal data class Toolbar(val muted: Boolean?, val window: Int)

/**
 * Microsoft's TalkBack guide identifies the Android meeting's Mic and Hang up buttons.
 * Inspect only button roles and their explicit descriptions/state, never text or event payloads.
 * A visible Mic can identify the control group even when it cannot be operated.
 * Never click Hang up or authorize a mic without a fresh action and explicit state.
 */
@Suppress("DEPRECATION")
internal fun inspectTeamsToolbar(root: Node, press: ((Toolbar) -> Boolean)? = null): Pair<Toolbar?, Boolean> {
    if (Build.VERSION.SDK_INT < 34 || root.packageName != TEAMS_PACKAGE) return null to false
    var budget = 160
    var incomplete = false
    var mic: Node? = null
    var leave: Node? = null
    var muted: Boolean? = null
    fun visit(node: Node, depth: Int) {
        if (--budget < 0 || depth > 20) { incomplete = true; return }
        if (node.packageName != TEAMS_PACKAGE || node.isPassword || !node.isVisibleToUser) return
        if (node.className in listOf("android.widget.Button", "android.widget.ImageButton", "android.widget.ToggleButton")) {
            val actionable = node.isEnabled && node.isClickable && node.actionList.any { it.id == Node.ACTION_CLICK }
            when (node.contentDescription?.toString()?.trim()?.lowercase(java.util.Locale.ROOT)) {
                "hang up" -> if (actionable && leave == null) leave = Node.obtain(node) else incomplete = true
                "mic" -> if (mic != null) incomplete = true else {
                    mic = Node.obtain(node)
                    muted = if (!actionable) null else when (node.stateDescription?.toString()?.trim()?.lowercase(java.util.Locale.ROOT)) {
                        "muted" -> true
                        "unmuted" -> false
                        else -> null
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            if (incomplete) return
            val child = node.getChild(i) ?: run { incomplete = true; return }
            try { visit(child, depth + 1) } finally { child.recycle() }
        }
    }
    try {
        visit(root, 0)
        val m = mic ?: return null to false
        val h = leave ?: return null to false
        if (incomplete || m.windowId != h.windowId) return null to false
        val mp = m.parent ?: return null to false
        val hp = h.parent
        val siblings = try { mp == hp } finally { mp.recycle(); hp?.recycle() }
        if (!siblings) return null to false
        val toolbar = Toolbar(muted, m.windowId)
        return toolbar to (muted != null && press?.invoke(toolbar) == true && m.performAction(Node.ACTION_CLICK))
    } finally { mic?.recycle(); leave?.recycle() }
}

class TeamsToolbarService : AccessibilityService() {
    companion object { internal var current: TeamsToolbarService? = null; private set }
    override fun onServiceConnected() { current = this; TeamsEvents.refresh?.invoke() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { TeamsEvents.refresh?.invoke() }
    override fun onInterrupt() { TeamsEvents.refresh?.invoke() }
    override fun onUnbind(intent: Intent?): Boolean { clear(); return super.onUnbind(intent) }
    override fun onDestroy() { clear(); super.onDestroy() }
    private fun clear() {
        if (current === this) { current = null; TeamsEvents.refresh?.invoke() }
    }
    @Suppress("DEPRECATION")
    internal fun inspect(press: ((Toolbar) -> Boolean)? = null): Pair<Toolbar?, Boolean> {
        if (current !== this) return null to false
        val root = rootInActiveWindow ?: return null to false
        return try { inspectTeamsToolbar(root, press) } finally { root.recycle() }
    }
}
