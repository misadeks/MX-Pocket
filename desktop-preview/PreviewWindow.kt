package tel.misa.mxpocket.preview

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.*

private val paper = Color(0xF4F6FA)
private val ink = Color(0x182435)
private val mutedInk = Color(0x43576E)
private fun now() = System.nanoTime() / 1_000_000

private class PreviewWindow : JFrame() {
    private val deck = PreviewDeck()
    private val heading = JLabel().apply { font = font.deriveFont(Font.BOLD, 32f); foreground = ink }
    private val position = JLabel().apply { foreground = mutedInk }
    private val status = JLabel("Choose a key or switch profiles.").apply { foreground = ink }
    private val keys = List(9) { slot ->
        JButton().apply {
            font = font.deriveFont(Font.BOLD, 17f)
            foreground = Color.WHITE
            addActionListener { status.text = deck.press(slot, now()); render() }
        }
    }

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        val panel = JPanel(BorderLayout(0, 14)).apply {
            background = paper
            border = BorderFactory.createEmptyBorder(18, 22, 18, 22)
        }
        contentPane = panel
        val header = JPanel(BorderLayout()).apply { isOpaque = false }
        header.add(heading, BorderLayout.WEST)
        header.add(position, BorderLayout.EAST)
        panel.add(header, BorderLayout.NORTH)

        val grid = JPanel(GridLayout(3, 3, 8, 8)).apply {
            background = Color(0x162131)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            preferredSize = Dimension(480, 318)
        }
        keys.forEach(grid::add)
        panel.add(grid)

        val footer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        val nav = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
        fun navigate(delta: Int) {
            deck.cycle(delta)
            status.text = "Switched to ${deck.profileName}."
            render()
        }
        for ((delta, label) in listOf(-1 to "Previous profile", 1 to "Next profile")) {
            if (delta == 1) nav.add(Box.createHorizontalStrut(8))
            nav.add(JButton(label).apply { addActionListener { navigate(delta) } })
        }
        nav.alignmentX = 0f
        footer.add(nav)
        footer.add(Box.createVerticalStrut(12))
        footer.add(status)
        footer.add(Box.createVerticalStrut(4))
        footer.add(JLabel("Simulation only  |  No USB, media, camera or accessibility access.").apply {
            foreground = mutedInk
        })
        panel.add(footer, BorderLayout.SOUTH)

        fun bind(code: Int, action: () -> Unit) {
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(code, 0), code)
            rootPane.actionMap.put(code, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) = action()
            })
        }
        bind(KeyEvent.VK_LEFT) { navigate(-1) }
        bind(KeyEvent.VK_RIGHT) { navigate(1) }
        keys.forEachIndexed { i, button -> bind(KeyEvent.VK_1 + i) { button.doClick() } }
        render()
        pack()
        minimumSize = size
        setLocationRelativeTo(null)
        Timer(1000) {
            if (deck.tick(now())) status.text = "Focus timer finished. No tone played."
            render()
        }.start()
    }

    private fun render() {
        title = "${deck.profileName} - MX Pocket desktop preview"
        heading.text = deck.profileName
        position.text = "Profile ${deck.profileIndex + 1} of ${deck.profileCount}"
        val color = when (deck.profileName) {
            "Focus" -> 0x194375
            "Audio" -> 0x303438
            "Phone", "Studio" -> 0x49345F
            "Launcher" -> 0x384674
            else -> 0x254C69
        }
        deck.tiles(now()).forEachIndexed { i, tile ->
            keys[i].apply {
                val caption = "<html><center><small>${i + 1}</small><br>${tile.label}</center></html>"
                if (text != caption) {
                    text = caption
                    accessibleContext.accessibleName = "Key ${i + 1}: ${tile.label}"
                }
                background = Color(color)
                isEnabled = tile.enabled
            }
        }
    }
}

fun main() = SwingUtilities.invokeLater { PreviewWindow().isVisible = true }
