package tel.misa.mxpocket.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import tel.misa.mxpocket.R
import tel.misa.mxpocket.plugins.PluginDescriptor
import tel.misa.mxpocket.plugins.PluginFrame

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

/**
 * Presentation only: constructing/rendering the screen never invokes an event or a device action.
 *
 * The live Home screen summarizes the physical keypad instead of mirroring it: the nine keys are
 * laid out on the phone only while the virtual keypad stands in for hardware.
 */
class PocketScreen(
    private val context: Context,
    plugins: List<PluginDescriptor>,
    private val onEvent: (PocketScreenEvent) -> Unit,
    private val live: Boolean = false,
) {
    private enum class Connection(@StringRes val label: Int, @DrawableRes val icon: Int,
        @ColorRes val fg: Int, @ColorRes val bg: Int, @ColorRes val stroke: Int) {
        IDLE(R.string.state_not_connected, R.drawable.ic_pocket_link, R.color.pocket_text_muted, R.color.pocket_chip, R.color.pocket_chip_stroke),
        CONNECTING(R.string.state_connecting, R.drawable.ic_pocket_bolt, R.color.pocket_warning, R.color.pocket_warning_container, R.color.pocket_warning_stroke),
        CONNECTED(R.string.state_connected, R.drawable.ic_pocket_check, R.color.pocket_success, R.color.pocket_success_container, R.color.pocket_success_stroke),
        VIRTUAL(R.string.state_virtual, R.drawable.ic_pocket_eye, R.color.pocket_virtual, R.color.pocket_virtual_container, R.color.pocket_virtual_stroke),
    }

    private val page = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dimension(R.dimen.screen_padding_horizontal), dimension(R.dimen.screen_padding_top),
            dimension(R.dimen.screen_padding_horizontal), dimension(R.dimen.screen_padding_bottom))
    }
    /** Views are added here; [beginCard] temporarily redirects them into a grouped surface. */
    private var content = page
    val view = ScrollView(context).apply {
        isFillViewport = true
        setBackgroundColor(color(R.color.pocket_background))
        addView(page)
        ViewCompat.setOnApplyWindowInsetsListener(this) { target, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            target.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        requestApplyInsets()
    }
    private val badge: ImageView
    private val connection: TextView
    private val status: TextView
    private val connectButton: MaterialButton
    private val automatic: CheckBox
    private val directShortcuts: CheckBox
    private val pageTile: ImageView
    private val pageTitle: TextView
    private val summary: TextView
    private val keyGrid: ViewGroup
    private val keys = mutableListOf<MaterialButton>()
    private val diagnostics: TextView
    private val log: TextView
    private val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val why: TextView
    private var profileOptions = emptyList<Pair<String,String>>()
    private val simulated: MaterialButton
    private val lastImages = arrayOfNulls<Bitmap>(9)
    private val panel = if (live) KeypadPanel(context) else null
    private var hasArt = false
    private val normalText = mutableListOf<ColorStateList>()
    private val buttonColors = mutableListOf<ColorStateList?>()
    private var simulatorActive = false
    private var shownConnection: Connection? = null
    private var shownChips: String? = null
    private var shownAccent: String? = null
    private var connectStyled: Boolean? = null

    init {
        text(context.getString(R.string.app_name), R.dimen.screen_title_size, heading = true).apply {
            setTextColor(color(R.color.pocket_text_strong))
            typeface = Typeface.DEFAULT_BOLD
        }
        if (!live) text(context.getString(R.string.subtitle), R.dimen.screen_body_size)

        beginCard()
        val header = row()
        badge = icon(header, R.dimen.screen_badge_size).apply { id = R.id.pocket_connection_badge }
        val titles = column(header)
        connection = text(context.getString(R.string.state_not_connected), R.dimen.screen_body_size, parent = titles).apply {
            id = R.id.pocket_connection_state
            setTextColor(color(R.color.pocket_text_strong))
            typeface = Typeface.DEFAULT_BOLD
        }
        status = text(context.getString(R.string.not_connected), R.dimen.screen_small_size, parent = titles).apply {
            id = R.id.pocket_status
            setTextColor(color(R.color.pocket_text_muted))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val actions = row()
        connectButton = button(context.getString(R.string.connect), parent = actions, event = PocketScreenEvent.ToggleConnection).apply {
            id = R.id.pocket_connect
        }
        simulated = button(context.getString(R.string.preview), parent = actions, outlined = true, event = PocketScreenEvent.EnterPreview)
        if (live) simulated.setOnClickListener {
            onEvent(if (simulatorActive) PocketScreenEvent.StopPreview else PocketScreenEvent.EnterPreview)
        }
        // The longer virtual-keypad label gets more of the row so both stay on one line.
        (connectButton.layoutParams as LinearLayout.LayoutParams).weight = 0.8f
        (simulated.layoutParams as LinearLayout.LayoutParams).weight = 1.2f
        endCard()

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

        label(context.getString(R.string.profiles), page)
        content.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }, LinearLayout.LayoutParams(-1, -2))
        updateOptions(plugins.map { it.id to it.title })

        beginCard()
        val pageHeader = row()
        pageTile = icon(pageHeader, R.dimen.screen_badge_size)
        val pageTitles = column(pageHeader)
        pageTitle = text(context.getString(R.string.focus), R.dimen.screen_heading_size, parent = pageTitles).apply {
            id = R.id.pocket_plugin_title
            setTextColor(color(R.color.pocket_text_strong))
            typeface = Typeface.DEFAULT_BOLD
        }
        why = text("", R.dimen.screen_small_size, parent = pageTitles).apply {
            setTextColor(color(R.color.pocket_text_muted))
            if (!live) visibility = View.GONE
        }
        // The nine tiles live on the physical LCDs; the phone lists what they currently show.
        val inner = inner()
        val shows = row(inner)
        icon(shows, R.dimen.screen_small_icon, R.drawable.ic_pocket_keyboard, R.color.pocket_text_muted)
        label(context.getString(R.string.keypad_shows_label), shows).apply {
            (layoutParams as LinearLayout.LayoutParams).marginStart = dimension(R.dimen.screen_control_margin) * 2
        }
        summary = text("", R.dimen.screen_small_size, parent = inner).apply {
            id = R.id.pocket_keypad_summary
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.pocket_text))
            setLineSpacing(0f, 1.25f)
        }
        if (!live) inner.visibility = View.GONE
        val arrows = row()
        button(context.getString(R.string.previous), parent = arrows, outlined = true,
            event = PocketScreenEvent.ChangePage(-1)).apply { icon(R.drawable.ic_pocket_chevron_left) }
        button(context.getString(R.string.next), parent = arrows, outlined = true,
            event = PocketScreenEvent.ChangePage(1)).apply {
            icon(R.drawable.ic_pocket_chevron_right)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_END
        }

        val keyIds = listOf(R.id.pocket_key_1, R.id.pocket_key_2, R.id.pocket_key_3,
            R.id.pocket_key_4, R.id.pocket_key_5, R.id.pocket_key_6,
            R.id.pocket_key_7, R.id.pocket_key_8, R.id.pocket_key_9)
        keyGrid = panel ?: LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        keyGrid.id = R.id.pocket_key_grid
        // Live Home shows on-screen keys only while the virtual keypad stands in for hardware.
        if (live) keyGrid.visibility = View.GONE
        content.addView(keyGrid, LinearLayout.LayoutParams(-1, -2))
        repeat(3) { r ->
            val line = panel ?: row(keyGrid as LinearLayout)
            repeat(3) { c ->
                val index = r * 3 + c
                keys += button("", parent = line, outlined = true, event = PocketScreenEvent.TapKey(index)).apply {
                    id = keyIds[index]
                    minHeight = dimension(R.dimen.screen_key_min_height)
                    maxLines = 3
                    if (live) {
                        normalText += textColors
                        buttonColors += backgroundTintList
                        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
                        iconSize = (58 * context.resources.displayMetrics.density).toInt()
                        iconTint = null
                        val inset = dimension(R.dimen.screen_control_margin)
                        setPadding(inset, inset, inset, inset)
                    }
                }
            }
        }
        divider()
        button(context.getString(R.string.repaint), outlined = true, event = PocketScreenEvent.Repaint)
            .apply { icon(R.drawable.ic_pocket_refresh) }
        button(context.getString(R.string.import_image), outlined = true, event = PocketScreenEvent.ChooseImage)
            .apply { icon(R.drawable.ic_pocket_image) }
        endCard()

        text(context.getString(R.string.profile_note), R.dimen.screen_label_size).apply {
            setTextColor(color(R.color.pocket_text_faint))
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.3f)
        }
        if (!live) {
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
        shownChips = null
        tabs.removeAllViews()
        options.forEach { (id,name) ->
            button(name,parent = tabs,outlined = true,event = PocketScreenEvent.SelectPlugin(id)).apply {
                tag = "plugin:$id"
                icon(PocketStyle.accent(id).icon)
                layoutParams = margins(LinearLayout.LayoutParams(-2,-2))
            }
        }
    }

    fun render(state: PocketScreenState) {
        showConnection(when {
            state.simulating -> Connection.VIRTUAL
            state.connectionActive && state.canRunKeys -> Connection.CONNECTED
            state.connectionActive -> Connection.CONNECTING
            else -> Connection.IDLE
        })
        status.updateText(state.status)
        automatic.isChecked = state.automatic
        directShortcuts.isChecked = state.directShortcuts
        connectButton.updateText(context.getString(if (state.connectionActive) R.string.disconnect else R.string.connect))
        if (connectStyled != state.connectionActive) {
            connectStyled = state.connectionActive
            // Disconnect is a calm red button; Connect is the filled primary action.
            if (state.connectionActive) pill(connectButton, color(R.color.pocket_danger), color(R.color.pocket_danger_container), color(R.color.pocket_danger_stroke))
            else pill(connectButton, color(R.color.pocket_on_accent), color(R.color.pocket_accent), color(R.color.pocket_accent))
        }
        pageTitle.updateText(state.pluginTitle)
        if (shownAccent != state.activeId) {
            shownAccent = state.activeId
            val accent = PocketStyle.accent(state.activeId)
            pageTile.setImageResource(accent.icon)
            pageTile.imageTintList = ColorStateList.valueOf(accent.tint)
            pageTile.background = rounded(accent.tile, lighten(accent.tile), R.dimen.screen_inner_radius)
        }
        summary.updateText(state.frame.keys.joinToString(", ") { tile ->
            listOf(tile.icon, tile.label.ifBlank { context.getString(R.string.blank_key) })
                .filter { it.isNotBlank() }.joinToString(" ")
        })
        if (live) {
            updateOptions(state.profiles)
            why.updateText(state.reason)
            why.visibility = if (state.reason.isBlank()) View.GONE else View.VISIBLE
            simulatorActive = state.simulating
            simulated.updateText(context.getString(if (state.simulating) R.string.stop_simulator else R.string.preview))
            simulated.isEnabled = !state.connectionActive
            keyGrid.visibility = if (state.simulating) View.VISIBLE else View.GONE
            if (shownChips != state.activeId) {
                shownChips = state.activeId
                for (i in 0 until tabs.childCount) {
                    val chip = tabs.getChildAt(i) as MaterialButton
                    val accent = PocketStyle.accent(profileOptions[i].first)
                    val selected = profileOptions[i].first == state.activeId
                    // Selection is shown by the profile color, a lighter outline and the "selected" state.
                    chip.isSelected = selected
                    if (selected) {
                        pill(chip, Color.WHITE, accent.fill, lighten(accent.fill))
                        chip.iconTint = ColorStateList.valueOf(lighten(accent.tint))
                    } else pill(chip, color(R.color.pocket_text_muted), color(R.color.pocket_chip), color(R.color.pocket_chip_stroke))
                }
            }
        }
        val showArt = live && state.panelArt != null
        panel?.show(state.panelArt, state.rendered)
        keys.forEachIndexed { index, button ->
            val tile = state.frame.keys[index]
            button.updateText(tile.label)
            if (live) {
                if (showArt != hasArt) {
                    button.setTextColor(if (showArt) ColorStateList.valueOf(Color.TRANSPARENT) else normalText[index])
                    button.backgroundTintList = if (showArt) ColorStateList.valueOf(Color.TRANSPARENT) else buttonColors[index]
                }
                button.isEnabled = tile.enabled && state.canRunKeys
                button.contentDescription = listOf("Key ${index+1}",tile.label,tile.footer).filter { it.isNotBlank() }.joinToString(", ")
                state.rendered.getOrNull(index)?.let { image ->
                    if (image !== lastImages[index] || showArt != hasArt) {
                        lastImages[index] = image
                        button.icon = if (showArt) null else BitmapDrawable(context.resources,image)
                    }
                }
            }
        }
        hasArt = showArt
        diagnostics.updateText(state.diagnostics)
        log.updateText(state.log)
    }

    private fun showConnection(value: Connection) {
        if (value == shownConnection) return
        shownConnection = value
        connection.text = context.getString(value.label)
        // The badge repeats the state shown in words, so screen readers skip it.
        badge.setImageResource(value.icon)
        badge.imageTintList = ColorStateList.valueOf(color(value.fg))
        badge.background = circle(color(value.bg), color(value.stroke))
    }

    private fun TextView.updateText(value: String) {
        if (text.toString() != value) text = value
    }

    private fun row(parent: LinearLayout = content) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun column(parent: LinearLayout) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        parent.addView(this, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dimension(R.dimen.screen_card_gap)
        })
    }

    private fun beginCard() {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dimension(R.dimen.screen_card_padding)
            setPadding(padding, padding * 3 / 4, padding, padding * 3 / 4)
            background = rounded(color(R.color.pocket_card), color(R.color.pocket_card_stroke), R.dimen.screen_card_radius)
        }
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dimension(R.dimen.screen_card_gap) / 2
            bottomMargin = dimension(R.dimen.screen_card_gap) / 2
        })
        content = card
    }

    private fun endCard() {
        content = page
    }

    private fun inner() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = dimension(R.dimen.screen_card_gap)
        setPadding(padding, padding * 3 / 4, padding, padding * 3 / 4)
        background = rounded(color(R.color.pocket_inner), color(R.color.pocket_inner_stroke), R.dimen.screen_inner_radius)
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dimension(R.dimen.screen_card_gap)
            bottomMargin = dimension(R.dimen.screen_control_margin)
        })
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(color(R.color.pocket_card_stroke))
        content.addView(this, LinearLayout.LayoutParams(-1, dimension(R.dimen.screen_stroke)).apply {
            topMargin = dimension(R.dimen.screen_control_margin) * 2
            bottomMargin = dimension(R.dimen.screen_control_margin) * 2
        })
    }

    private fun label(value: String, parent: LinearLayout) = TextView(context).apply {
        text = value.uppercase()
        setTextColor(color(R.color.pocket_text_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.screen_label_size))
        letterSpacing = 0.08f
        typeface = Typeface.DEFAULT_BOLD
        if (parent === page) setPadding(dimension(R.dimen.screen_control_margin), dimension(R.dimen.screen_card_gap),
            0, dimension(R.dimen.screen_control_margin))
        parent.addView(this, LinearLayout.LayoutParams(if (parent.orientation == LinearLayout.VERTICAL) -1 else -2, -2))
    }

    private fun icon(parent: LinearLayout, @DimenRes size: Int, @DrawableRes res: Int? = null,
        @ColorRes tint: Int? = null) = ImageView(context).apply {
        res?.let { setImageResource(it) }
        tint?.let { imageTintList = ColorStateList.valueOf(color(it)) }
        val side = dimension(size)
        val inset = if (side >= dimension(R.dimen.screen_badge_size)) side / 4 else 0
        setPadding(inset, inset, inset, inset)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        parent.addView(this, LinearLayout.LayoutParams(side, side))
    }

    private fun text(value: String, @DimenRes size: Int, heading: Boolean = false,
        parent: LinearLayout = content) = TextView(context).apply {
        text = value
        setTextColor(color(R.color.pocket_text))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(size))
        val small = dimension(R.dimen.screen_text_padding) / 4
        setPadding(0, if (heading) dimension(R.dimen.screen_heading_padding_top) / 2 else small, 0, small)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(
        label: String,
        parent: ViewGroup = content,
        outlined: Boolean = false,
        event: PocketScreenEvent,
    ) = MaterialButton(context, null, if (outlined)
        com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle).apply {
        text = label
        minHeight = dimension(R.dimen.screen_control_min_height)
        isAllCaps = false
        letterSpacing = 0f
        setTextSize(TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.screen_button_size))
        cornerRadius = dimension(R.dimen.screen_inner_radius)
        iconSize = dimension(R.dimen.screen_small_icon)
        iconPadding = dimension(R.dimen.screen_control_margin) * 2
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        // Narrow side padding keeps two buttons per row on one line, as in the design.
        val side = dimension(R.dimen.screen_card_gap)
        setPaddingRelative(side, paddingTop, side, paddingBottom)
        if (outlined) pill(this, color(R.color.pocket_text), color(R.color.pocket_control), color(R.color.pocket_button_stroke))
        setOnClickListener { onEvent(event) }
        if (parent is LinearLayout) {
            val params = if (parent.orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1, -2)
                else LinearLayout.LayoutParams(0, -2, 1f)
            parent.addView(this, margins(params))
        } else parent.addView(this)
    }

    private fun MaterialButton.icon(@DrawableRes res: Int) {
        icon = ContextCompat.getDrawable(context, res)
    }

    private fun pill(button: MaterialButton, text: Int, fill: Int, stroke: Int) {
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        val textColors = ColorStateList(arrayOf(disabled, intArrayOf()), intArrayOf(color(R.color.pocket_text_faint), text))
        button.setTextColor(textColors)
        button.iconTint = textColors
        button.backgroundTintList = ColorStateList(arrayOf(disabled, intArrayOf()),
            intArrayOf(color(R.color.pocket_chip), fill))
        button.strokeColor = ColorStateList.valueOf(stroke)
        button.strokeWidth = dimension(R.dimen.screen_stroke)
    }

    private fun rounded(fill: Int, stroke: Int, @DimenRes radius: Int) = GradientDrawable().apply {
        setColor(fill)
        setStroke(dimension(R.dimen.screen_stroke), stroke)
        cornerRadius = context.resources.getDimension(radius)
    }

    private fun circle(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dimension(R.dimen.screen_stroke), stroke)
    }

    private fun lighten(value: Int): Int {
        fun mix(channel: Int) = channel + (255 - channel) * 3 / 10
        return Color.rgb(mix(Color.red(value)), mix(Color.green(value)), mix(Color.blue(value)))
    }

    private fun margins(params: LinearLayout.LayoutParams) = params.apply {
        val margin = dimension(R.dimen.screen_control_margin)
        setMargins(margin, margin / 2, margin, margin / 2)
    }

    private fun color(@ColorRes id: Int) = ContextCompat.getColor(context, id)

    private fun dimension(@DimenRes id: Int) = context.resources.getDimension(id).toInt()
}
