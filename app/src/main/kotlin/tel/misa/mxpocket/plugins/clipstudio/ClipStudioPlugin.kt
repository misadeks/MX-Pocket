package tel.misa.mxpocket.plugins.clipstudio

import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.plugins.PluginAction
import tel.misa.mxpocket.plugins.PluginCapability
import tel.misa.mxpocket.plugins.PluginDescriptor
import tel.misa.mxpocket.plugins.PluginFrame
import tel.misa.mxpocket.plugins.PluginHost
import tel.misa.mxpocket.plugins.PluginKeyEvent
import tel.misa.mxpocket.plugins.PocketPlugin
import tel.misa.mxpocket.plugins.clipstudio.ClipStudioCommand.Field
import tel.misa.mxpocket.plugins.clipstudio.ClipStudioCommand.Tool

/**
 * Clip Studio Paint page. The first screen picks one of four tools and sends the selection to
 * Clip Studio through the accessibility bridge; each tool then has a settings submenu (size,
 * opacity and a tool-specific extra) whose keys send step commands. The plugin only holds
 * logical state and dispatches [PluginAction.AccessibilityCommand] values — it never touches
 * windows, nodes, coordinates or USB.
 */
class ClipStudioPlugin(title: String) : PocketPlugin {
    override val descriptor = PluginDescriptor("clipstudio", title,
        setOf(PluginCapability.ACCESSIBILITY, PluginCapability.NAVIGATION))

    private enum class Screen { TOOLS, SETTINGS }

    private class ToolUi(val label: String, val extraLabel: String)
    private val toolUi = mapOf(
        Tool.PENCIL to ToolUi("Pencil", "Stabilizer"),
        Tool.LASSO to ToolUi("Lasso", "Expand"),
        Tool.BUCKET to ToolUi("Bucket", "Tolerance"),
        Tool.ERASER to ToolUi("Eraser", "Hardness"),
    )

    private class Settings(var size: Int = 20, var opacity: Int = 100, var extra: Int = 0)
    private val settings = Tool.entries.associateWith { Settings() }

    private var screen = Screen.TOOLS
    private var selected = Tool.PENCIL

    override fun render(nowMillis: Long): PluginFrame = when (screen) {
        Screen.TOOLS -> PluginFrame(List(9) { index -> toolsTile(index) })
        Screen.SETTINGS -> PluginFrame(List(9) { index -> settingsTile(index) })
    }

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        when (screen) {
            Screen.TOOLS -> when (event.index) {
                in 0..3 -> {
                    selected = Tool.entries[event.index]
                    host.dispatch(PluginAction.AccessibilityCommand(ClipStudioCommand.tool(selected)))
                    screen = Screen.SETTINGS
                }
                4 -> screen = Screen.SETTINGS
                5 -> host.dispatch(PluginAction.AccessibilityCommand(ClipStudioCommand.tool(selected)))
                6 -> host.dispatch(PluginAction.SelectPlugin("focus"))
                7 -> host.dispatch(PluginAction.SelectPlugin("phone"))
                8 -> host.dispatch(PluginAction.Repaint)
            }
            Screen.SETTINGS -> when (event.index) {
                0 -> screen = Screen.TOOLS
                1 -> step(host, Field.SIZE, -SIZE_STEP)
                2 -> step(host, Field.SIZE, SIZE_STEP)
                3 -> step(host, Field.OPACITY, -OPACITY_STEP)
                4 -> step(host, Field.OPACITY, OPACITY_STEP)
                5 -> step(host, Field.EXTRA, -EXTRA_STEP)
                6 -> step(host, Field.EXTRA, EXTRA_STEP)
                8 -> host.dispatch(PluginAction.AccessibilityCommand(ClipStudioCommand.tool(selected)))
                7 -> {} // Readout key: no command.
            }
        }
        host.invalidate()
    }

    override fun onDeactivate() {
        // Re-enter on the tool page; selected tool and settings survive page switches.
        screen = Screen.TOOLS
    }

    private fun step(host: PluginHost, field: Field, delta: Int) {
        val current = settings.getValue(selected)
        val old = when (field) {
            Field.SIZE -> current.size
            Field.OPACITY -> current.opacity
            Field.EXTRA -> current.extra
        }
        val range = if (field == Field.SIZE) SIZE_MIN..SIZE_MAX else 0..100
        val updated = (old + delta).coerceIn(range)
        if (updated == old) return // Already at the limit; no command is sent for a change that cannot happen.
        when (field) {
            Field.SIZE -> current.size = updated
            Field.OPACITY -> current.opacity = updated
            Field.EXTRA -> current.extra = updated
        }
        host.dispatch(PluginAction.AccessibilityCommand(ClipStudioCommand.step(field, delta)))
    }

    private fun toolsTile(index: Int): KeyTile {
        val tile = when (index) {
            in 0..3 -> Tool.entries[index] to toolUi.getValue(Tool.entries[index]).label
            4 -> null to "Settings"
            5 -> null to toolUi.getValue(selected).label
            6 -> null to "Focus"
            7 -> null to "Phone"
            else -> null to "Repaint"
        }
        val isSelectedTool = index in 0..3 && Tool.entries[index] == selected
        return KeyTile(tile.second, "CLIP STUDIO",
            if (isSelectedTool) COLOR_SELECTED else COLOR_TOOLS)
    }

    private fun settingsTile(index: Int): KeyTile {
        val current = settings.getValue(selected)
        val extra = toolUi.getValue(selected).extraLabel
        val label = when (index) {
            0 -> "‹ Tools"
            1 -> "Size −"
            2 -> "Size +"
            3 -> "Op −"
            4 -> "Op +"
            5 -> "$extra −"
            6 -> "$extra +"
            7 -> "S${current.size} O${current.opacity} E${current.extra}"
            else -> toolUi.getValue(selected).label
        }
        return KeyTile(label, toolUi.getValue(selected).label,
            if (index == 7) COLOR_READOUT else COLOR_SETTINGS)
    }

    private companion object {
        const val SIZE_STEP = 2
        const val SIZE_MIN = 1
        const val SIZE_MAX = 200
        const val OPACITY_STEP = 5
        const val EXTRA_STEP = 5
        val COLOR_TOOLS = 0xff14424f.toInt()
        val COLOR_SETTINGS = 0xff1b3a4b.toInt()
        val COLOR_SELECTED = 0xff2e7d6b.toInt()
        val COLOR_READOUT = 0xff274b3a.toInt()
    }
}
