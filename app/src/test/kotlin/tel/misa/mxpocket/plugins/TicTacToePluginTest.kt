package tel.misa.mxpocket.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicTacToePluginTest {
    @Test fun soloModePlaysAgainstComputerAndReturnsToModePickerAfterGame() = runTest {
        val engine = PluginEngine(listOf(TicTacToePlugin("X-O")), this, { testScheduler.currentTime },
            { AudioSnapshot(0, 0) }, { _, _ -> }, {}, { _, error -> throw AssertionError(error) })
        engine.setEnabled(true)
        assertEquals("1 PLAYER", engine.render().keys[0].label)
        assertEquals("2 PLAYERS", engine.render().keys[2].label)

        tap(engine, 0)
        tap(engine, 0)
        assertEquals("X", engine.render().keys[0].label)
        assertEquals("O", engine.render().keys[4].label)
        tap(engine, 1)
        assertEquals("O", engine.render().keys[2].label) // The computer blocks X's top row.
        tap(engine, 3)
        assertEquals("CPU WINS - ANY KEY FOR MODES", engine.render().keys[0].footer)

        tap(engine, 8)
        assertEquals("1 PLAYER", engine.render().keys[0].label)
        engine.close()
    }

    @Test fun localTwoPlayerModeAlternatesMarksWithoutComputerMoves() = runTest {
        val engine = PluginEngine(listOf(TicTacToePlugin("X-O")), this, { testScheduler.currentTime },
            { AudioSnapshot(0, 0) }, { _, _ -> }, {}, { _, error -> throw AssertionError(error) })
        engine.setEnabled(true)

        tap(engine, 2)
        tap(engine, 0)
        var frame = engine.render()
        assertEquals("X", frame.keys[0].label)
        assertEquals("5", frame.keys[4].label)
        assertEquals("O TURN - 1V1", frame.keys[0].footer)
        tap(engine, 0) // An occupied square does not consume O's turn.
        assertEquals("O TURN - 1V1", engine.render().keys[0].footer)
        tap(engine, 4)
        tap(engine, 1)
        tap(engine, 5)
        tap(engine, 2)
        frame = engine.render()
        assertEquals("X", frame.keys[2].label)
        assertEquals("X WINS - ANY KEY FOR MODES", frame.keys[0].footer)

        tap(engine, 8)
        tap(engine, 0)
        assertEquals("YOUR TURN", engine.render().keys[0].footer)
        engine.close()
    }

    private fun tap(engine: PluginEngine, index: Int) {
        engine.key(PluginKeyEvent(index, true))
        engine.key(PluginKeyEvent(index, false))
    }
}
