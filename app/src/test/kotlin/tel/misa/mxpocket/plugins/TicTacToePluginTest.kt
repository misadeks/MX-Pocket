package tel.misa.mxpocket.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicTacToePluginTest {
    @Test fun soloModePlaysAgainstComputerAndReturnsToModePickerAfterGame() = runTest {
        val engine = PluginEngine(listOf(TicTacToePlugin("Tic Tac Toe")), this, { testScheduler.currentTime },
            { AudioSnapshot(0, 0) }, { _, _ -> }, {}, { _, error -> throw AssertionError(error) })
        engine.setEnabled(true)
        assertEquals(listOf("Tic", "Tac", "Toe"), engine.render().keys.take(3).map { it.label })
        assertEquals("1 Player", engine.render().keys[6].label)
        assertEquals("2 Players", engine.render().keys[8].label)

        tap(engine, 0) // The title squares are inert.
        assertEquals("Tic", engine.render().keys[0].label)
        tap(engine, 6)
        tap(engine, 0)
        assertEquals("X", engine.render().keys[0].label)
        assertEquals("O", engine.render().keys[4].label)
        tap(engine, 1)
        assertEquals("O", engine.render().keys[2].label) // The computer blocks X's top row.
        tap(engine, 3)
        assertEquals("CPU wins", engine.render().keys[0].footer)

        tap(engine, 8)
        assertEquals("1 Player", engine.render().keys[6].label)
        engine.close()
    }

    @Test fun localTwoPlayerModeAlternatesMarksWithoutComputerMoves() = runTest {
        val engine = PluginEngine(listOf(TicTacToePlugin("Tic Tac Toe")), this, { testScheduler.currentTime },
            { AudioSnapshot(0, 0) }, { _, _ -> }, {}, { _, error -> throw AssertionError(error) })
        engine.setEnabled(true)

        tap(engine, 8)
        tap(engine, 0)
        var frame = engine.render()
        assertEquals("X", frame.keys[0].label)
        assertEquals("5", frame.keys[4].label)
        assertEquals("O to play", frame.keys[0].footer)
        tap(engine, 0) // An occupied square does not consume O's turn.
        assertEquals("O to play", engine.render().keys[0].footer)
        tap(engine, 4)
        tap(engine, 1)
        tap(engine, 5)
        tap(engine, 2)
        frame = engine.render()
        assertEquals("X", frame.keys[2].label)
        assertEquals("X wins", frame.keys[0].footer)

        tap(engine, 8)
        tap(engine, 6)
        assertEquals("Your turn", engine.render().keys[0].footer)
        engine.close()
    }

    private fun tap(engine: PluginEngine, index: Int) {
        engine.key(PluginKeyEvent(index, true))
        engine.key(PluginKeyEvent(index, false))
    }
}
