package tel.misa.mxpocket.plugins

class TicTacToePlugin(title: String) : PocketPlugin {
    override val descriptor = PluginDescriptor("game.xo", title)
    private val game = TicTacToeGame()

    override fun render(nowMillis: Long) = PluginFrame(game.cells().map { KeyTile(it.label, it.footer, it.background) })

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (event.pressed && !event.synthetic && game.press(event.index)) host.invalidate()
    }
}
