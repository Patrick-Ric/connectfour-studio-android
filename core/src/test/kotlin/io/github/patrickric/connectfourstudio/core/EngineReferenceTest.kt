package io.github.patrickric.connectfourstudio.core

import io.github.patrickric.connectfourstudio.core.engine.BitBully
import io.github.patrickric.connectfourstudio.core.engine.Board
import io.github.patrickric.connectfourstudio.core.engine.OpeningBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin engine must give the same results as the Python engine
 * (cfs_core + bitbully 0.0.79 C++ core) for fixed positions: scores AND node
 * counts of every iteration depth, MTD(f), book values, scoreToMovesLeft.
 * Reference data: core/src/test/resources/engine_reference.json, generated
 * by scripts/gen_engine_reference.py.
 */
class EngineReferenceTest {
    private val ref = Json.parse(TestSupport.resource("engine_reference.json")).obj()

    private fun scoresOf(raw: List<Any?>): List<Int?> = raw.map { (it as Long?)?.toInt() }

    @Test
    fun iterativeScoresAndNodeCountsMatchPython() {
        val engine = Engine(book = TestSupport.book)
        val depths = ref["iter_depths"].ints().toIntArray()
        assertTrue(depths.contentEquals(Engine.ITER_DEPTHS))
        var positions = 0
        for (item in ref["iterative"].list()) {
            val o = item.obj()
            val moves = o["moves"].ints()
            val b = Board.fromMoves(moves)
            val stages = o["stages"].list().map { it.obj() }
            // Same calls as iterative_scores: TT reset once, then all depths.
            val agent = engine.agent
            agent.resetNodeCounter()
            agent.resetTranspositionTable()
            for ((i, depth) in depths.withIndex()) {
                val st = stages[i]
                assertEquals(depth, (st["depth"] as Long).toInt())
                val sc = agent.scoreMoves(b, depth).map { if (it == BitBully.ILLEGAL) null else it }
                assertEquals("moves=$moves depth=$depth", scoresOf(st["scores"].list()), sc)
                assertEquals("nodes moves=$moves depth=$depth", st["nodes"] as Long, agent.nodeCounter)
            }
            // The public API gives the same final result as the full search of
            // the Python engine. Below 12 stones it stops at the first depth
            // whose lines all reach the book (12 - stones): same scores, and
            // exactly the node count of the Python search up to that depth.
            val (scores, nodes) = engine.iterativeScores(b)
            val finalScores = scoresOf(stages.last()["scores"].list())
            assertEquals("final $moves", finalScores, (0 until 7).map { scores[it] })
            val n = moves.size
            if (n < 12) {
                val stop = stages.first { (it["depth"] as Long).toInt().let { d -> d != -1 && d >= 12 - n } }
                assertEquals("book stage exact $moves", finalScores, scoresOf(stop["scores"].list()))
                assertEquals("nodes up to book $moves", stop["nodes"] as Long, nodes)
                assertEquals(Engine.DEPTH_BOOK, engine.lastDepth)
            } else {
                assertEquals(stages.last()["nodes"] as Long, nodes)
                assertEquals(-1, engine.lastDepth)
            }
            assertEquals("mtdf $moves", (o["mtdf"] as Long).toInt(), engine.mtdf(b))
            for ((s, ml) in o["moves_left"].obj()) {
                assertEquals((ml as Long).toInt(), BitBully.scoreToMovesLeft(s.toInt(), b))
            }
            positions++
        }
        assertTrue(positions >= 30)
    }

    @Test
    fun bookValuesMatchPython() {
        val book = TestSupport.book
        assertEquals(4_200_899, book.size)
        val solver = BitBully(16, book)
        for (item in ref["book"].list()) {
            val o = item.obj()
            val b = Board.fromMoves(o["moves"].ints())
            assertEquals(12, b.countTokens())
            assertEquals("book ${o["moves"]}", (o["value"] as Long).toInt(), solver.negamax(b))
        }
    }

    @Test
    fun documentedBookExamples() {
        // Examples from the bitbully-databases documentation (raw distance values).
        val book = TestSupport.book
        for (item in ref["book_examples"].list()) {
            val o = item.obj()
            val rows = o["rows"].list().map { it.ints() } // rows top -> bottom
            val b = boardFromRows(rows)
            val raw = book.rawValue(b) ?: book.rawValue(b.mirror())
            assertEquals((o["value"] as Long).toInt(), raw)
        }
        assertEquals(OpeningBook.NONE_VALUE, book.getBoardValue(Board.fromMoves(listOf(3))))
    }

    /** Board from a 6x7 matrix (top row first, 1 = Yellow, 2 = Red). */
    private fun boardFromRows(rows: List<List<Int>>): Board =
        Board.fromArray(Array(7) { c -> IntArray(6) { r -> rows[5 - r][c] } })!!
}
