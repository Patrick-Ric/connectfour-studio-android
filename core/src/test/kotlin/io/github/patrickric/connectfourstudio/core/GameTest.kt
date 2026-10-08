package io.github.patrickric.connectfourstudio.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Game logic: win detection, legal moves, navigation, .4gp format (port of tests/test_game.py). */
class GameTest {
    private fun playAll(vararg moves: Int): Game {
        val g = Game()
        for (m in moves) g.play(m)
        return g
    }

    private fun dataFile(name: String): ByteArray =
        GameTest::class.java.classLoader.getResourceAsStream("data/$name")!!.readBytes()

    // ---------- win detection
    @Test
    fun winnerDetection() {
        val cases = listOf(
            intArrayOf(0, 1, 0, 1, 0, 1, 0) to 1, // Yellow vertical
            intArrayOf(0, 0, 1, 1, 2, 2, 3) to 1, // Yellow horizontal
            intArrayOf(6, 0, 6, 1, 5, 2, 5, 3) to 2, // Red horizontal
            intArrayOf(0, 1, 1, 2, 2, 3, 2, 3, 3, 6, 3) to 1, // Yellow diagonal /
            intArrayOf(3, 2, 2, 1, 1, 0, 1, 0, 0, 6, 0) to 1, // Yellow diagonal \
        )
        for ((moves, winner) in cases) {
            val g = playAll(*moves)
            assertTrue(g.isGameOver())
            assertEquals(winner, g.winner())
            val cells = g.winCells()
            assertTrue(cells.size >= 4)
            val arr = g.toArray()
            for (cell in cells) assertEquals(winner, arr[cell.c][Game.ROWS - 1 - cell.rTop])
        }
    }

    @Test
    fun noWinnerMidgame() {
        val g = playAll(3, 3, 2, 4)
        assertFalse(g.isGameOver())
        assertEquals(emptySet<Cell>(), g.winCells())
        assertNull(g.winner())
    }

    @Test
    fun winCellsLongRow() {
        val arr = Array(7) { IntArray(6) }
        for (c in 0 until 5) arr[c][0] = 2
        assertEquals((0 until 5).map { Cell(5, it) }.toSet(), Game.winCells(arr))
    }

    @Test
    fun drawFullBoard() {
        val moves = Gp4.parse("455714637617614767242476316455122212535333")
        assertEquals(moves, Gp4.legalPrefix(moves))
        val g = Game()
        g.setMoves(moves)
        assertEquals(42, g.history.size)
        assertTrue(g.isGameOver())
        assertNull(g.winner())
        assertEquals(emptySet<Cell>(), g.winCells())
        assertFalse((0 until 7).any { g.isLegal(it) })
    }

    // ---------- legal moves
    @Test
    fun legalMovesAndFullColumn() {
        val g = Game()
        for (c in 0 until 7) assertTrue(g.isLegal(c))
        repeat(6) { g.play(0) }
        assertFalse(g.isLegal(0))
        assertEquals(6, g.columnHeight(0))
        assertTrue((1 until 7).all { g.isLegal(it) })
    }

    @Test
    fun currentPlayerAndStone() {
        val g = Game()
        assertEquals(1, g.currentPlayerNo())
        assertEquals(Stone.YELLOW, g.stoneToMove())
        g.play(3)
        assertEquals(2, g.currentPlayerNo())
        assertEquals(Stone.RED, g.stoneToMove())
    }

    @Test
    fun lastMovePosition() {
        assertEquals(Cell(4, 3), playAll(3, 3).lastMove)
        assertNull(Game().lastMove)
    }

    @Test
    fun navigationUndoRedoFirstLast() {
        val g = playAll(3, 2, 4)
        assertTrue(g.undo())
        assertEquals(listOf(3, 2), g.history)
        assertEquals(listOf(4), g.future)
        assertTrue(g.redo())
        assertEquals(listOf(3, 2, 4), g.history)
        assertEquals(emptyList<Int>(), g.future)
        g.gotoFirst()
        assertEquals(emptyList<Int>(), g.history)
        assertEquals(listOf(4, 2, 3), g.future)
        g.gotoLast()
        assertEquals(listOf(3, 2, 4), g.history)
        g.undo()
        g.play(6) // a new move clears the redo stack
        assertEquals(emptyList<Int>(), g.future)
        assertEquals(listOf(3, 2, 6), g.history)
        assertFalse(Game().undo())
    }

    @Test
    fun restoreKeepsRedoStack() {
        val g = Game()
        g.restore(listOf(3, 2), listOf(4, 5))
        assertEquals(listOf(3, 2), g.history)
        assertEquals(listOf(4, 5), g.future)
        g.gotoLast()
        assertEquals(listOf(3, 2, 5, 4), g.history)
    }

    // ---------- .4gp format
    @Test
    fun parseAndFormatRoundtrip() {
        assertEquals(listOf(3, 3, 2, 2, 1, 1, 0), Gp4.parse("4433221"))
        assertEquals("4433221", Gp4.format(listOf(3, 3, 2, 2, 1, 1, 0)))
        assertEquals(emptyList<Int>(), Gp4.parse(""))
    }

    @Test
    fun writeMatchesOldFormat() {
        // like Tk: digits only, no line end
        assertArrayEquals("4437".toByteArray(), Gp4.bytes(listOf(3, 3, 2, 6)))
    }

    @Test
    fun readLegacyFiles() {
        assertEquals(listOf(3, 3, 2, 2, 1, 1, 0), Gp4.parse(dataFile("legacy_simple.4gp")))
        assertEquals(listOf(3, 3, 4, 4), Gp4.parse(dataFile("legacy_crlf.4gp")))
        assertEquals(listOf(0, 1, 2, 3), Gp4.parse(dataFile("legacy_noise.4gp")))
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), Gp4.parse(dataFile("legacy_digits.4gp")))
    }

    @Test
    fun legacyLoadStopsAfterWinAndSkipsIllegal() {
        assertEquals(listOf(0, 1, 0, 1, 0, 1, 0), Gp4.legalPrefix(Gp4.parse(dataFile("legacy_after_win.4gp"))))
        assertEquals(List(6) { 0 }, Gp4.legalPrefix(Gp4.parse(dataFile("legacy_illegal.4gp"))))
    }

    @Test
    fun saveLoadRoundtrip() {
        val g = playAll(3, 3, 4, 2, 5)
        val bytes = Gp4.bytes(g.history)
        val g2 = Game()
        g2.setMoves(Gp4.parse(bytes))
        assertEquals(g.history, g2.history)
        assertTrue(g.toArray().contentDeepEquals(g2.toArray()))
    }

    // ---------- same results as the Python implementation
    @Test
    fun gamesMatchPythonReference() {
        val ref = Json.parse(TestSupport.resource("engine_reference.json")).obj()
        for (item in ref["games"].list()) {
            val o = item.obj()
            val g = Game()
            g.setMoves(o["moves"].ints())
            assertEquals(o["over"], g.isGameOver())
            assertEquals((o["winner"] as Long?)?.toInt(), g.winner())
            val cells = o["win_cells"].list().map { it.ints() }.map { Cell(it[0], it[1]) }.toSet()
            assertEquals(cells, g.winCells())
            val lm = o["last_move"]?.ints()
            assertEquals(lm?.let { Cell(it[0], it[1]) }, g.lastMove)
        }
        for (item in ref["prefixes"].list()) {
            val o = item.obj()
            assertEquals(o["prefix"].ints(), Gp4.legalPrefix(o["raw"].ints()))
        }
    }
}
