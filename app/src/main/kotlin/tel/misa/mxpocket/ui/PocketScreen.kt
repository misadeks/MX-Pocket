package tel.misa.mxpocket.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DimenRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import tel.misa.mxpocket.R
import tel.misa.mxpocket.plugins.PluginDescriptor
import tel.misa.mxpocket.plugins.PluginFrame
import tel.misa.mxpocket.plugins.PlayerGlyph

data class PocketScreenState(
    val status: String,
    val pluginTitle: String,
    val frame: PluginFrame,
    val connectionActive: Boolean = false,
    val diagnostics: String = "",
    val log: String = "",
    val automatic: Boolean = false,
    val directShortcuts: Boolean = false,
    val profiles: List<Pair<String,String>> = emptyList(),
    val activeId: String = "",
    val reason: String = "",
    val canRunKeys: Boolean = true,
    val simulating: Boolean = false,
    val rendered: List<Bitmap> = emptyList(),
    val panelArt: Bitmap? = null,
)

sealed interface PocketScreenEvent {
    data object ToggleConnection : PocketScreenEvent
    data class SetAutomatic(val enabled: Boolean) : PocketScreenEvent
    data class SetDirectShortcuts(val enabled: Boolean) : PocketScreenEvent
    data object EnterPreview : PocketScreenEvent
    data object StopPreview : PocketScreenEvent
    data class SelectPlugin(val id: String) : PocketScreenEvent
    data class TapKey(val index: Int) : PocketScreenEvent
    data class ChangePage(val delta: Int) : PocketScreenEvent
    data object Repaint : PocketScreenEvent
    data object ChooseImage : PocketScreenEvent
    data object RefreshDiagnostics : PocketScreenEvent
    data object SaveLog : PocketScreenEvent
}

/** Presentation only: constructing/rendering the screen never invokes an event or a device action. */
class PocketScreen(
    private val context: Context,
    plugins: List<PluginDescriptor>,
    private val onEvent: (PocketScreenEvent) -> Unit,
    private val live: Boolean = false,
) {
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dimension(R.dimen.screen_padding_horizontal), dimension(R.dimen.screen_padding_top),
            dimension(R.dimen.screen_padding_horizontal), dimension(R.dimen.screen_padding_bottom))
    }
    val view = ScrollView(context).apply {
        isFillViewport = true
        addView(content)
        ViewCompat.setOnApplyWindowInsetsListener(this) { target, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            target.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        requestApplyInsets()
    }
    private val status: TextView
    private val connectButton: MaterialButton
    private val automatic: CheckBox
    private val directShortcuts: CheckBox
    private val pageTitle: TextView
    private val keys = mutableListOf<MaterialButton>()
    private val diagnostics: TextView
    private val log: TextView
    private val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val why: TextView
    private var profileOptions = emptyList<Pair<String,String>>()
    private val simulated: MaterialButton
    private val panel = if (live) KeypadPanel(context) else null
    private var simulatorActive = false

    init {
        text(context.getString(R.string.app_name), R.dimen.screen_title_size, heading = true)
        if (!live) text(context.getString(R.string.subtitle), R.dimen.screen_body_size)
        status = text(context.getString(R.string.not_connected), R.dimen.screen_body_size).apply {
            id = R.id.pocket_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        connectButton = button(context.getString(R.string.connect), event = PocketScreenEvent.ToggleConnection).apply {
            id = R.id.pocket_connect
        }
        automatic = CheckBox(context).apply {
            if (live) visibility = View.GONE
            setText(R.string.auto_connect)
            minHeight = dimension(R.dimen.screen_control_min_height)
            setOnClickListener { onEvent(PocketScreenEvent.SetAutomatic(isChecked)) }
            content.addView(this)
        }
        directShortcuts = CheckBox(context).apply {
            if (live) visibility = View.GONE
            setText(R.string.direct_shortcuts)
            minHeight = dimension(R.dimen.screen_control_min_height)
            setOnClickListener { onEvent(PocketScreenEvent.SetDirectShortcuts(isChecked)) }
            content.addView(this)
        }
        simulated = button(context.getString(R.string.preview), outlined = true, event = PocketScreenEvent.EnterPreview)
        if (live) simulated.setOnClickListener {
            onEvent(if (simulatorActive) PocketScreenEvent.StopPreview else PocketScreenEvent.EnterPreview)
        }
        pageTitle = text(context.getString(R.string.focus), R.dimen.screen_heading_size, heading = true).apply {
            id = R.id.pocket_plugin_title
        }
        why = text("", R.dimen.screen_body_size).apply { if (!live) visibility = View.GONE }
        content.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }, LinearLayout.LayoutParams(-1, -2))
        updateOptions(plugins.map { it.id to it.title })
        val keyIds = listOf(R.id.pocket_key_1, R.id.pocket_key_2, R.id.pocket_key_3,
            R.id.pocket_key_4, R.id.pocket_key_5, R.id.pocket_key_6,
            R.id.pocket_key_7, R.id.pocket_key_8, R.id.pocket_key_9)
        panel?.let { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        repeat(3) { r ->
            val line = panel ?: row()
            repeat(3) { c ->
                val index = r * 3 + c
                keys += button("", parent = line, outlined = true, event = PocketScreenEvent.TapKey(index)).apply {
                    id = keyIds[index]
                    minHeight = dimension(R.dimen.screen_key_min_height)
                    maxLines = 3
                    if (live) {
                        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                        strokeWidth = 0
                    }
                }
            }
        }
        val arrows = row()
        button(context.getString(R.string.previous), parent = arrows, outlined = true,
            event = PocketScreenEvent.ChangePage(-1))
        button(context.getString(R.string.next), parent = arrows, outlined = true,
            event = PocketScreenEvent.ChangePage(1))
        if (!live) {
            button(context.getString(R.string.repaint), outlined = true, event = PocketScreenEvent.Repaint)
            button(context.getString(R.string.import_image), outlined = true, event = PocketScreenEvent.ChooseImage)
            text(context.getString(R.string.profile_note), R.dimen.screen_note_size)
            text(context.getString(R.string.diagnostics), R.dimen.screen_heading_size, heading = true)
            button(context.getString(R.string.refresh), outlined = true, event = PocketScreenEvent.RefreshDiagnostics)
        }
        diagnostics = text("", R.dimen.screen_note_size).apply {
            if (live) visibility = View.GONE
            id = R.id.pocket_diagnostics
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
        }
        if (!live) {
            button(context.getString(R.string.export_log), outlined = true, event = PocketScreenEvent.SaveLog)
            text(context.getString(R.string.log), R.dimen.screen_heading_size, heading = true)
        }
        log = text("", R.dimen.screen_log_size).apply {
            id = R.id.pocket_log
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            if (live) visibility = View.GONE
        }
    }

    private fun updateOptions(options: List<Pair<String,String>>) {
        if (options == profileOptions) return
        profileOptions = options
        tabs.removeAllViews()
        options.forEach { (id,name) ->
            button(name,parent = tabs,outlined = true,event = PocketScreenEvent.SelectPlugin(id)).apply {
                tag = "plugin:$id"
                layoutParams = margins(LinearLayout.LayoutParams(-2,-2))
            }
        }
    }

    fun render(state: PocketScreenState) {
        status.updateText(state.status)
        automatic.isChecked = state.automatic
        directShortcuts.isChecked = state.directShortcuts
        connectButton.updateText(context.getString(if (state.connectionActive) R.string.disconnect else R.string.connect))
        pageTitle.updateText(state.pluginTitle)
        if (live) {
            updateOptions(state.profiles)
            why.updateText(state.reason)
            simulatorActive = state.simulating
            simulated.updateText(context.getString(if (state.simulating) R.string.stop_simulator else R.string.preview))
            simulated.isEnabled = !state.connectionActive
            for (i in 0 until tabs.childCount) {
                tabs.getChildAt(i).isSelected = profileOptions[i].first == state.activeId
            }
        }
        panel?.show(state.panelArt, state.rendered)
        keys.forEachIndexed { index, button ->
            val tile = state.frame.keys[index]
            button.updateText(if (live) "" else tile.label)
            if (live) {
                (button as KeyControl).readOnly = tile.readOnly
                val actionable = !tile.readOnly && tile.enabled && state.canRunKeys
                button.isEnabled = actionable || tile.readOnly
                if (button.isClickable != actionable) {
                    button.setOnClickListener(if (actionable) View.OnClickListener { onEvent(PocketScreenEvent.TapKey(index)) } else null)
                    button.isClickable = actionable
                }
                val name = when {
                    tile.glyph == PlayerGlyph.TRACK -> listOf("Now playing", tile.label, tile.footer)
                    tile.glyph != null -> listOf(tile.label, tile.footer)
                    else -> listOf("Key ${index + 1}", tile.label, tile.footer)
                }
                button.contentDescription = name.filter(String::isNotBlank).distinct().joinToString(", ")
            }
        }
        diagnostics.updateText(state.diagnostics)
        log.updateText(state.log)
    }

    private fun TextView.updateText(value: String) {
        if (text.toString() != value) text = value
    }

    private fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun text(value: String, @DimenRes size: Int, heading: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(size))
        setPadding(0, dimension(if (heading) R.dimen.screen_heading_padding_top else R.dimen.screen_text_padding),
            0, dimension(R.dimen.screen_text_padding))
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(
        label: String,
        parent: ViewGroup = content,
        outlined: Boolean = false,
        event: PocketScreenEvent,
    ) = (if (parent is KeypadPanel) KeyControl(context) else MaterialButton(context, null, if (outlined)
        com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle)).apply {
        text = label
        minHeight = dimension(R.dimen.screen_control_min_height)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.screen_note_size))
        setOnClickListener { onEvent(event) }
        if (parent is LinearLayout) {
            val params = if (parent === content) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(0, -2, 1f)
            parent.addView(this, margins(params))
        } else parent.addView(this)
    }

    private fun margins(params: LinearLayout.LayoutParams) = params.apply {
        val margin = dimension(R.dimen.screen_control_margin)
        setMargins(margin, margin, margin, margin)
    }

    private fun dimension(@DimenRes id: Int) = context.resources.getDimension(id).toInt()
}

/** Same native hit target across profile switches; a track readout is text, never a button. */
private class KeyControl(context: Context) : MaterialButton(context) {
    var readOnly = false
    override fun getAccessibilityClassName(): CharSequence =
        if (readOnly) TextView::class.java.name else android.widget.Button::class.java.name
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = accessibilityClassName
    }
}
