package tel.misa.mxpocket.plugins

import android.content.res.Resources
import tel.misa.mxpocket.R
import java.text.NumberFormat
import java.util.Locale

private const val PURPLE = 0xff7719aa.toInt()

// These are display approximations for named inks, not OneNote RGB measurements or key backgrounds.
private val inks = mapOf(
    "black" to 0x000000, "white" to 0xffffff, "off white" to 0xf6f5ee, "gray" to 0x808080,
    "dark gray" to 0x484848, "yellow" to 0xffff00, "light yellow" to 0xffff99, "gold" to 0xffc000,
    "red" to 0xff0000, "dark red" to 0x8b0000, "orange" to 0xff8000, "pink" to 0xff69b4,
    "green" to 0x008000, "dark green" to 0x006400, "light green" to 0x90ee90, "lime" to 0x92d050,
    "lemon lime" to 0x00ff00, "aqua" to 0x00ffff, "turquoise" to 0x40e0d0, "teal" to 0x008080,
    "blue" to 0x0000ff, "dark blue" to 0x00008b, "navy blue" to 0x002060, "sky blue" to 0x87ceeb,
    "light blue" to 0xadd8e6, "pale blue" to 0x99ccff, "purple" to 0x800080, "dark purple" to 0x7030a0,
    "indigo" to 0x4b0082, "plum" to 0xdda0dd, "lavender" to 0xcc99ff, "rose" to 0xff99cc,
)
private fun namedInk(name: String?) = inks[name?.trim()?.lowercase()]?.or(0xff000000.toInt())
private val optionWidth = Regex("""(?:^|,)\s*thickness\s+(\d+(?:[.,]\d+)?)\s+percent(?:\s*,|$)""", RegexOption.IGNORE_CASE)
private val optionInk = Regex("""^([^,]+?)\s+color\s*(?:,|$)""", RegexOption.IGNORE_CASE)
private val ribbonMarks = mapOf(
    "button_fontsize" to OneNoteMark.SIZE, "button_bold" to OneNoteMark.BOLD, "button_italic" to OneNoteMark.ITALIC,
    "button_underline" to OneNoteMark.UNDERLINE, "button_strikethrough" to OneNoteMark.STRIKE,
    "button_fontcolor" to OneNoteMark.FONT_COLOR, "button_highlight" to OneNoteMark.TEXT_HIGHLIGHT,
    "button_bulletlist" to OneNoteMark.BULLETS, "button_numberlist" to OneNoteMark.NUMBERING,
    "button_decreaseindent" to OneNoteMark.OUTDENT, "button_increaseindent" to OneNoteMark.INDENT,
    "button_leftalign" to OneNoteMark.ALIGN_LEFT, "button_centeralign" to OneNoteMark.ALIGN_CENTER,
    "button_rightalign" to OneNoteMark.ALIGN_RIGHT, "button_styles" to OneNoteMark.STYLES
)

internal class OneNoteFaces(private val resources: Resources?) {
    private val locale get() = resources?.configuration?.locales?.get(0) ?: Locale.getDefault()
    private fun text(id: Int, otherwise: String, vararg args: Any) =
        resources?.getString(id, *args) ?: String.format(locale, otherwise, *args)
    private fun percent(value: Float) = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 2 }
        .format(value / 100.0)
    private fun blank() = KeyTile("", "", PURPLE, enabled = false, quietFace = true, showLabel = false, showFooter = false)
    private fun stateDescription(state: KeySelection?) = when (state) {
        KeySelection.ON -> text(R.string.note_selected, "Selected")
        KeySelection.UNKNOWN -> text(R.string.note_unknown_selection, "Selection unknown")
        else -> ""
    }
    private fun face(label: String, glyph: KeyGlyph, footer: String = "", selected: KeySelection? = null,
                     enabled: Boolean = true, detail: String = "") =
        KeyTile(label, footer, PURPLE, glyph = glyph, enabled = enabled, showFooter = footer.isNotBlank(),
            selection = selected, detail = listOf(detail, stateDescription(selected)).filter(String::isNotBlank).joinToString(", "))

    fun draw(entries: List<Pair<String, String>>, active: String?): PluginFrame {
        val unique = active != null && entries.count { it.second == active } == 1
        return PluginFrame(List(9) { i ->
            val desc = entries.getOrNull(i)?.second?.takeIf(String::isNotBlank) ?: return@List blank()
            val state = when { !unique -> KeySelection.UNKNOWN; desc == active -> KeySelection.ON; else -> KeySelection.OFF }
            val kind = when (desc.substringAfterLast(',').trim().lowercase()) {
                "pen options" -> OneNoteMark.PEN
                "highlighter options" -> OneNoteMark.HIGHLIGHTER
                "stroke eraser" -> OneNoteMark.ERASER
                "shape select" -> OneNoteMark.SHAPE
                "lasso select" -> OneNoteMark.LASSO
                else -> OneNoteMark.TOOL
            }
            if (kind != OneNoteMark.PEN && kind != OneNoteMark.HIGHLIGHTER)
                return@List face(desc, OneNoteLegend(kind), selected = state)
            val name = optionInk.find(desc)?.groupValues?.get(1)?.trim()
            val ink = namedInk(name)
            val width = optionWidth.find(desc)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull()?.takeIf { it in 0f..100f }
            val label = if (kind == OneNoteMark.PEN) {
                if (name == null) text(R.string.note_pen, "Pen") else text(R.string.note_named_pen, "%s pen", name)
            } else {
                if (name == null) text(R.string.note_highlighter, "Highlighter") else text(R.string.note_named_highlighter, "%s highlighter", name)
            }
            val unknown = if (ink == null) text(R.string.note_unknown_ink, "Ink unknown") else ""
            face(label, OneNoteLegend(kind, ink, width), unknown, state, detail = width?.let(::percent).orEmpty())
        })
    }

    fun ribbon(item: Pair<String, String>?): KeyTile {
        val id = item?.first?.takeIf(String::isNotBlank) ?: return blank()
        val mark = if (id == "text:draw") OneNoteMark.DRAW else ribbonMarks[id.substringAfterLast(":id/")] ?: OneNoteMark.TOOL
        val state = when {
            id.startsWith("text:selected:") -> KeySelection.ON
            else -> null
        }
        return face(item.second, OneNoteLegend(mark), selected = state)
    }

    fun option(item: Pair<String,String>?, size: Boolean): KeyTile {
        val id = item?.first?.takeIf(String::isNotBlank) ?: return blank()
        val mark = when (id.substringAfter(':')) {
            "previous" -> OneNoteMark.PREVIOUS; "next" -> OneNoteMark.NEXT; "cancel" -> OneNoteMark.CANCEL
            else -> if (size) OneNoteMark.SIZE else OneNoteMark.STYLES
        }
        return face(item.second, OneNoteLegend(mark), if (size) text(R.string.note_size, "Font size") else text(R.string.note_styles, "Styles"))
    }

    fun pageKey(next: Boolean, enabled: Boolean = true, footer: String = "") = face(
        if (next) text(R.string.note_next, "Next") else text(R.string.note_previous, "Previous"),
        OneNoteLegend(if (next) OneNoteMark.NEXT else OneNoteMark.PREVIOUS), footer, enabled = enabled)

    private fun OneNoteSettings.selectedOption(): Int? = selectedIndex.takeIf { it in colors.indices }
        ?: selectedColor?.let { name -> colors.indices.singleOrNull { colors[it] == name } }

    private fun OneNoteSettings.ink(index: Int): Int? {
        val name = colors.getOrNull(index) ?: return null
        return if (index in colorValues.indices) colorValues[index] else namedInk(name)
    }

    fun palette(current: OneNoteSettings, page: Int): PluginFrame {
        val pages = ((current.colors.size + 5) / 6).coerceAtLeast(1)
        val selected = current.selectedOption()
        return PluginFrame(List(9) { i ->
            val index = page * 6 + i
            when {
                i < 6 -> {
                    val name = current.colors.getOrNull(index) ?: return@List blank()
                    val state = when (selected) { null -> KeySelection.UNKNOWN; index -> KeySelection.ON; else -> KeySelection.OFF }
                    val ink = current.ink(index)
                    face(name, OneNoteLegend(OneNoteMark.SWATCH, ink), selected = state,
                        detail = if (ink == null) text(R.string.note_unknown_ink, "Ink unknown") else "")
                }
                i == 6 -> pageKey(false, page > 0)
                i == 7 -> face("${page + 1} / $pages", OneNoteLegend(OneNoteMark.SWATCH,
                    if (selected != null) current.ink(selected) else namedInk(current.selectedColor?.takeIf { it !in current.colors })),
                    text(R.string.note_cancel, "Cancel"), detail = current.selectedColor.orEmpty())
                else -> pageKey(true, page + 1 < pages)
            }
        })
    }

    fun settings(current: OneNoteSettings): PluginFrame {
        val fraction = ((current.thickness - current.min) / (current.max - current.min)).takeIf {
            current.max > current.min && it.isFinite() && it in 0f..1f
        }
        val thickness = text(R.string.note_thickness, "Thickness")
        return PluginFrame(List(9) { i -> when (i) {
            0 -> face("$thickness −", DeckGlyph.MINUS)
            1 -> KeyTile(thickness, "", PURPLE, readOnly = true, levelFraction = fraction,
                detail = fraction?.let { percent(it * 100) }.orEmpty())
            2 -> face("$thickness +", DeckGlyph.PLUS)
            4 -> face(text(R.string.note_color, "Color"), OneNoteLegend(OneNoteMark.SWATCH, namedInk(current.selectedColor)),
                current.selectedColor ?: text(R.string.note_unknown_ink, "Ink unknown"))
            7 -> face(text(R.string.note_back, "Back"), NavigationGlyph.BACK)
            else -> blank()
        } })
    }
}
