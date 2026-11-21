package tel.misa.mxpocket.plugins.clipstudio

/**
 * Wire vocabulary for [tel.misa.mxpocket.plugins.PluginAction.AccessibilityCommand] values
 * exchanged between the Clip Studio page and the app-layer accessibility bridge. This object
 * is the only place that builds or parses the strings, so the plugin and the
 * [tel.misa.mxpocket.KeypadAccessibilityService] cannot drift apart. Pure Kotlin: no Android
 * types, so the format is unit-testable on the JVM.
 */
object ClipStudioCommand {
    const val PREFIX = "clipstudio."

    /** Tools the keypad can select in Clip Studio Paint. [wire] is the stable command token. */
    enum class Tool(val wire: String) {
        PENCIL("pencil"), LASSO("lasso"), BUCKET("bucket"), ERASER("eraser");
    }

    /** Adjustable tool setting. The name lowercased is the command token. */
    enum class Field { SIZE, OPACITY, EXTRA }

    fun tool(tool: Tool): String = PREFIX + "tool." + tool.wire

    /** [delta] must be non-zero; positive steps up, negative steps down. */
    fun step(field: Field, delta: Int): String {
        require(delta != 0) { "A step of zero is not a command" }
        return PREFIX + field.name.lowercase() + ".step:" + (if (delta > 0) "+" else "") + delta
    }

    sealed interface Parsed {
        data class SelectTool(val tool: Tool) : Parsed
        data class Step(val field: Field, val delta: Int) : Parsed
    }

    fun parse(command: String): Parsed? {
        if (!command.startsWith(PREFIX)) return null
        val body = command.removePrefix(PREFIX)
        val dot = body.indexOf('.')
        if (dot <= 0) return null
        return when (body.substring(0, dot)) {
            "tool" -> Tool.entries.firstOrNull { it.wire == body.substring(dot + 1) }
                ?.let { Parsed.SelectTool(it) }
            "size", "opacity", "extra" -> {
                val field = Field.entries.first { it.name.lowercase() == body.substring(0, dot) }
                val rest = body.substring(dot + 1)
                if (!rest.startsWith("step:")) return null
                val delta = rest.removePrefix("step:").toIntOrNull() ?: return null
                if (delta == 0) null else Parsed.Step(field, delta)
            }
            else -> null
        }
    }
}
