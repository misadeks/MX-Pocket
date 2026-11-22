package tel.misa.mxpocket.plugins

/** Ink is an approximation of an observed named color, never the key's background. */
data class OneNoteLegend(val mark: OneNoteMark, val ink: Int? = null, val widthPercent: Float? = null) : KeyGlyph

enum class OneNoteMark {
    PEN, HIGHLIGHTER, ERASER, SHAPE, LASSO, TOOL, SWATCH, UNAVAILABLE,
    SIZE, BOLD, ITALIC, UNDERLINE, STRIKE, FONT_COLOR, TEXT_HIGHLIGHT, BULLETS, NUMBERING,
    OUTDENT, INDENT, ALIGN_LEFT, ALIGN_CENTER, ALIGN_RIGHT, STYLES, DRAW, PREVIOUS, NEXT, CANCEL
}

enum class KeySelection { ON, OFF, UNKNOWN }
