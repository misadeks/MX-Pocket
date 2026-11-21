package tel.misa.mxpocket.plugins

/** A keypad-only tic-tac-toe game. The player is X; the built-in opponent is O. */
class TicTacToePlugin(title: String) : PocketPlugin {
    override val descriptor = PluginDescriptor("game.xo", title)

    private enum class Mode { SINGLE_PLAYER, ONE_VS_ONE }
    private enum class Result { PLAYING, X_WON, O_WON, DRAW }

    private val board = CharArray(9) { EMPTY }
    private var mode: Mode? = null
    private var turn = PLAYER
    private var result = Result.PLAYING

    override fun render(nowMillis: Long): PluginFrame {
        if (mode == null) return PluginFrame(List(9) { index ->
            when (index) {
                0 -> KeyTile("1 PLAYER", "VS COMPUTER", 0xff194375.toInt())
                2 -> KeyTile("2 PLAYERS", "LOCAL 1V1", 0xff49345f.toInt())
                4 -> KeyTile("X-O", "CHOOSE A MODE", 0xff303d48.toInt())
                else -> KeyTile("", "CHOOSE A MODE", 0xff303d48.toInt())
            }
        })
        return PluginFrame(board.mapIndexed { index, mark ->
            val label = if (mark == EMPTY) (index + 1).toString() else mark.toString()
            KeyTile(label, footer(), when (mark) {
                PLAYER -> 0xff194375.toInt()
                COMPUTER -> 0xff79452c.toInt()
                else -> 0xff303d48.toInt()
            })
        })
    }

    override fun onKey(event: PluginKeyEvent, host: PluginHost) {
        if (!event.pressed || event.synthetic) return
        if (mode == null) {
            mode = when (event.index) {
                0 -> Mode.SINGLE_PLAYER
                2 -> Mode.ONE_VS_ONE
                else -> return
            }
            resetBoard()
            host.invalidate()
            return
        }
        if (result != Result.PLAYING) {
            mode = null
            resetBoard()
            host.invalidate()
            return
        }
        if (board[event.index] != EMPTY) return

        val activeMode = checkNotNull(mode)
        val mark = if (activeMode == Mode.SINGLE_PLAYER) PLAYER else turn
        board[event.index] = mark
        updateResult(mark)
        if (result == Result.PLAYING && activeMode == Mode.SINGLE_PLAYER) {
            board[computerMove()] = COMPUTER
            updateResult(COMPUTER)
        } else if (result == Result.PLAYING) {
            turn = if (mark == PLAYER) COMPUTER else PLAYER
        }
        host.invalidate()
    }

    private fun footer() = when (result) {
        Result.PLAYING -> if (mode == Mode.SINGLE_PLAYER) "YOUR TURN" else "$turn TURN - 1V1"
        Result.X_WON -> if (mode == Mode.SINGLE_PLAYER) "YOU WIN - ANY KEY FOR MODES" else "X WINS - ANY KEY FOR MODES"
        Result.O_WON -> if (mode == Mode.SINGLE_PLAYER) "CPU WINS - ANY KEY FOR MODES" else "O WINS - ANY KEY FOR MODES"
        Result.DRAW -> "DRAW - ANY KEY FOR MODES"
    }

    private fun resetBoard() {
        board.fill(EMPTY)
        turn = PLAYER
        result = Result.PLAYING
    }

    private fun updateResult(mark: Char) {
        result = when {
            hasWon(mark) -> if (mark == PLAYER) Result.X_WON else Result.O_WON
            board.none { it == EMPTY } -> Result.DRAW
            else -> Result.PLAYING
        }
    }

    private fun computerMove(): Int {
        winningMove(COMPUTER)?.let { return it }
        winningMove(PLAYER)?.let { return it }
        if (board[4] == EMPTY) return 4
        for (index in CORNERS) if (board[index] == EMPTY) return index
        return SIDES.first { board[it] == EMPTY }
    }

    private fun winningMove(mark: Char): Int? {
        for (index in board.indices) {
            if (board[index] != EMPTY) continue
            board[index] = mark
            val wins = hasWon(mark)
            board[index] = EMPTY
            if (wins) return index
        }
        return null
    }

    private fun hasWon(mark: Char) = WINS.any { line -> line.all { board[it] == mark } }

    private companion object {
        const val EMPTY = ' '
        const val PLAYER = 'X'
        const val COMPUTER = 'O'
        val WINS = listOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
        )
        val CORNERS = intArrayOf(0, 2, 6, 8)
        val SIDES = intArrayOf(1, 3, 5, 7)
    }
}
