package tel.misa.mxpocket

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import tel.misa.mxpocket.plugins.clipstudio.ClipStudioCommand

/**
 * Optional bridge that lets keypad plugins drive an app running on this phone (Clip Studio
 * Paint). Android requires the user to enable this service in system accessibility settings;
 * the app only opens that screen and never toggles the setting itself.
 *
 * Only Clip Studio windows are observed (see `res/xml/accessibility_service_config.xml`), and
 * commands are performed by clicking the matching on-screen control. A command that matches
 * no node returns false so the session log reports a real failure instead of a fake success.
 * Node labels below are best-effort English/Japanese candidates: they need on-device
 * verification against the installed Clip Studio build, which JVM tests cannot provide.
 */
class KeypadAccessibilityService : AccessibilityService() {
    private var lastTool: ClipStudioCommand.Tool? = null

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Commands inspect the active window directly; no event state is needed.
    }

    override fun onInterrupt() {}

    /** @return true only when the command was performed on a foreground Clip Studio window. */
    fun performCommand(command: String): Boolean {
        val parsed = ClipStudioCommand.parse(command) ?: return false
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() !in CLIP_STUDIO_PACKAGES) return false
        return when (parsed) {
            is ClipStudioCommand.Parsed.SelectTool -> {
                lastTool = parsed.tool
                clickMatching(root, TOOL_LABELS[parsed.tool].orEmpty())
            }
            is ClipStudioCommand.Parsed.Step -> applyStep(root, parsed.field, parsed.delta)
        }
    }

    private fun applyStep(root: AccessibilityNodeInfo, field: ClipStudioCommand.Field, delta: Int): Boolean {
        val slider = findSlider(root, fieldLabels(field))
        if (slider != null) {
            val action = if (delta > 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            var ok = true
            repeat(minOf(kotlin.math.abs(delta), MAX_NODE_STEPS)) { if (!slider.performAction(action)) ok = false }
            return ok
        }
        val buttons = if (delta > 0) PLUS_LABELS else MINUS_LABELS
        val button = findByText(root, buttons) ?: return false
        var ok = true
        repeat(minOf(kotlin.math.abs(delta), MAX_NODE_STEPS)) { if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ok = false }
        return ok
    }

    private fun clickMatching(root: AccessibilityNodeInfo, labels: Set<String>): Boolean {
        if (labels.isEmpty()) return false
        val node = findByText(root, labels) ?: return false
        val target = generateSequence(node) { it.parent }.firstOrNull { it.isClickable } ?: node
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return ok
    }

    private fun findByText(root: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
        val wanted = labels.map { it.lowercase() }.toSet()
        if (matches(root, wanted)) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = findByText(child, labels)
            if (found != null) return found
        }
        return null
    }

    private fun findSlider(root: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
        val wanted = labels.map { it.lowercase() }.toSet()
        if (root.rangeInfo != null && matches(root, wanted)) return root
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            val found = findSlider(child, labels)
            if (found != null) return found
        }
        return null
    }

    private fun matches(node: AccessibilityNodeInfo, wanted: Set<String>): Boolean =
        node.text?.toString()?.lowercase() in wanted || node.contentDescription?.toString()?.lowercase() in wanted

    private fun fieldLabels(field: ClipStudioCommand.Field): Set<String> = when (field) {
        ClipStudioCommand.Field.SIZE -> setOf("brush size", "ブラシサイズ", "size", "サイズ")
        ClipStudioCommand.Field.OPACITY -> setOf("opacity", "不透明度")
        ClipStudioCommand.Field.EXTRA -> when (lastTool) {
            ClipStudioCommand.Tool.PENCIL -> setOf("stabilization", "手ブレ補正", "stabilizer")
            ClipStudioCommand.Tool.ERASER -> setOf("hardness", "硬さ")
            ClipStudioCommand.Tool.BUCKET -> setOf("tolerance", "許容範囲", "色の許容範囲")
            ClipStudioCommand.Tool.LASSO -> setOf("expand", "拡張", "拡大・縮小")
            null -> EXTRA_LABELS
        }
    }

    companion object {
        /** Known Clip Studio Paint Android package IDs (Google Play and direct builds). */
        val CLIP_STUDIO_PACKAGES = setOf("jp.co.celsys.clipstudiopaint.googleplay", "com.celsys.clipstudiopaint")
        private const val MAX_NODE_STEPS = 20
        private val PLUS_LABELS = setOf("+", "＋", "increase", "大きく")
        private val MINUS_LABELS = setOf("-", "−", "－", "decrease", "小さく")
        private val EXTRA_LABELS = setOf("stabilization", "手ブレ補正", "hardness", "硬さ",
            "tolerance", "許容範囲", "expand", "拡張")
        private val TOOL_LABELS = mapOf(
            ClipStudioCommand.Tool.PENCIL to setOf("pencil", "鉛筆", "pen", "ペン"),
            ClipStudioCommand.Tool.LASSO to setOf("lasso select", "lasso", "なげなわ選択", "なげなわ"),
            ClipStudioCommand.Tool.BUCKET to setOf("paint bucket", "fill", "塗りつぶし"),
            ClipStudioCommand.Tool.ERASER to setOf("eraser", "消しゴム"),
        )

        @Volatile private var instance: KeypadAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val component = ComponentName(context, KeypadAccessibilityService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(component, ignoreCase = true) }
        }

        /** @return true only when a connected service actually performed the command. */
        fun perform(command: String): Boolean = instance?.performCommand(command) ?: false
    }
}
