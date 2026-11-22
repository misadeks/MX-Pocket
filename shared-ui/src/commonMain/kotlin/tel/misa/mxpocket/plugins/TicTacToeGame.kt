package tel.misa.mxpocket.plugins

const val TIC_TAC_TOE_TITLE = "Tic Tac Toe"

/** A keypad-only tic-tac-toe game. The player is X; the built-in opponent is O. */
class TicTacToeGame {
    data class Cell(val label: String, val footer: String, val background: Int)

    private enum class Mode { SINGLE_PLAYER, ONE_VS_ONE }
    private enum class Result { PLAYING, X_WON, O_WON, DRAW }

    private val board = CharArray(9) { EMPTY }
    private var mode: Mode? = null
    private var turn = PLAYER
    private var result = Result.PLAYING

    val interactionPage: Int get() = when {
        mode == null -> 0
        result == Result.PLAYING -> 1
        else -> 2 // Any square now returns to the mode picker.
    }

    fun cells(): List<Cell> {
        if (mode == null) return List(9) { index ->
            when (index) {
                0 -> Cell("1 Player", "vs computer", 0xff194375.toInt())
                2 -> Cell("2 Players", "Local 1v1", 0xff49345f.toInt())
                4 -> Cell(TIC_TAC_TOE_TITLE, "Choose a mode", 0xff303d48.toInt())
                else -> Cell("", "", 0xff303d48.toInt())
            }
        }
        return board.mapIndexed { index, mark ->
            val label = if (mark == EMPTY) (index + 1).toString() else mark.toString()
            Cell(label, footer(), when (mark) {
                PLAYER -> 0xff194375.toInt()
                COMPUTER -> 0xff79452c.toInt()
                else -> 0xff303d48.toInt()
            })
        }
    }

    fun frame() = PluginFrame(cells().mapIndexed { index, cell ->
        val picking = mode == null
        val inert = if (picking) index != 0 && index != 2 else result == Result.PLAYING && board[index] != EMPTY
        val restart = result != Result.PLAYING && index == 7
        KeyTile(cell.label, if (restart) "Tap for modes" else cell.footer, cell.background, readOnly = inert, quietFace = true,
            showFooter = index == 4 || picking && !inert || restart, showLabel = cell.label.isNotBlank())
    }, interactionPage)

    fun press(index: Int): Boolean {
        if (mode == null) {
            mode = when (index) {
                0 -> Mode.SINGLE_PLAYER
                2 -> Mode.ONE_VS_ONE
                else -> return false
            }
            resetBoard()
            return true
        }
        if (result != Result.PLAYING) {
            mode = null
            resetBoard()
            return true
        }
        if (board[index] != EMPTY) return false

        val activeMode = checkNotNull(mode)
        val mark = if (activeMode == Mode.SINGLE_PLAYER) PLAYER else turn
        board[index] = mark
        updateResult(mark)
        if (result == Result.PLAYING && activeMode == Mode.SINGLE_PLAYER) {
            board[computerMove()] = COMPUTER
            updateResult(COMPUTER)
        } else if (result == Result.PLAYING) {
            turn = if (mark == PLAYER) COMPUTER else PLAYER
        }
        return true
    }

    private fun footer() = when (result) {
        Result.PLAYING -> if (mode == Mode.SINGLE_PLAYER) "Your turn" else "$turn to play"
        Result.X_WON -> if (mode == Mode.SINGLE_PLAYER) "You win" else "X wins"
        Result.O_WON -> if (mode == Mode.SINGLE_PLAYER) "CPU wins" else "O wins"
        Result.DRAW -> "Draw"
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
