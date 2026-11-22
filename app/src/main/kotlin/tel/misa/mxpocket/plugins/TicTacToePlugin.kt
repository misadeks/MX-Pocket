package tel.misa.mxpocket.plugins

class TicTacToePlugin(title: String) : PocketPlugin {
    override val descriptor = PluginDescriptor("game.xo", title)
    private val game = TicTacToeGame()

    override fun render(nowMillis: Long) = game.frame()

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (event.pressed && !event.synthetic && game.press(event.index)) host.invalidate()
    }
}
