package io.github.patrickric.connectfourstudio.core

import io.github.patrickric.connectfourstudio.core.TestSupport.board
import io.github.patrickric.connectfourstudio.core.TestSupport.engine
import io.github.patrickric.connectfourstudio.core.engine.BitBully
import io.github.patrickric.connectfourstudio.core.engine.Board
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** AI/levels: move choice, guards, analysis, random positions, match/Elo (port of tests/test_engine.py). */
class EngineTest {
    private val de = TestSupport.texts("de")

    // ---------- levels
    @Test
    fun levelTable() {
        assertEquals(15, Levels.STUFEN_ORDER.size)
        assertEquals("verlierer", Levels.STUFEN_ORDER.first())
        assertEquals("perfekt", Levels.STUFEN_ORDER.last())
        assertEquals(Stufe("perfekt", 0.0, null, null), Levels.stufenWerte("perfekt"))
        assertEquals(Stufe("mittel", 0.5, 1, 1), Levels.stufenWerte("mittel"))
        val sm = Levels.stufenWerte("starker_meister")
        assertEquals(0.08, sm.err, 1e-9)
        assertEquals(4, sm.s)
        assertEquals(4, sm.w)
        assertEquals(Stufe("verlierer", 1.0, 0, 0), Levels.stufenWerte("verlierer"))
        assertEquals(Stufe("mensch", 0.0, null, null), Levels.stufenWerte("mensch"))
        assertEquals("perfekt", Levels.stufenWerte("unbekannt").key)
    }

    @Test
    fun userPswAndLabels() {
        Levels.setUserPsw("user1", Triple(120, 12, -3))
        assertEquals(Triple(100, 9, 0), Levels.userPsw("user1"))
        assertEquals(Triple(70, 1, 2), Levels.parsePsw("70", "x", "2"))
        Levels.setUserPsw("user1", Triple(60, 2, 1))
        val u = Levels.stufenWerte("user1")
        assertEquals(0.4, u.err, 1e-9)
        assertEquals(2, u.s)
        assertEquals(1, u.w)
        assertEquals("User (1) (60,2,1)", Levels.stufeLabelFor("user1", de, mitPsw = true))
        assertEquals("7 Mittel (50,1,1)", Levels.stufeLabelFor("mittel", de, mitPsw = true))
        assertEquals("14 Perfekt", Levels.stufeLabelFor("perfekt", de, mitPsw = true))
        Levels.setUserPsw("user1", Levels.USER_PSW_DEFAULT)
    }

    @Test
    fun matchElo() {
        assertNull(Levels.matchElo(0.0, 0))
        assertEquals(-2000.0, Levels.matchElo(0.0, 10)!!, 0.0)
        assertEquals(2000.0, Levels.matchElo(10.0, 10)!!, 0.0)
        assertEquals(0.0, Levels.matchElo(5.0, 10)!!, 0.0)
        assertEquals(191.0, Levels.matchElo(15.0, 20)!!, 0.0)
    }

    // ---------- engine
    @Test
    fun perfectPlaysImmediateWin() {
        val b = board(0, 1, 0, 1, 0, 1) // Yellow wins with column 1
        repeat(5) {
            val m = engine.pickMove(b, Levels.stufenWerte("perfekt"))
            assertEquals(0, m.col)
            assertTrue(m.score > 0)
        }
    }

    @Test
    fun perfectBlocksThreat() {
        val b = board(0, 1, 0, 1, 0) // Red must block column 1
        assertEquals(0, engine.pickMove(b, Levels.stufenWerte("perfekt")).col)
    }

    @Test
    fun everyLevelReturnsLegalMove() {
        val b = board(3, 3, 2, 4)
        for (key in Levels.STUFEN_ORDER + Levels.USER_KEYS) {
            val m = engine.pickMove(b, Levels.stufenWerte(key), rng = Random(1))
            assertTrue("$key -> ${m.col}", m.col in b.legalMoves())
            assertTrue(m.nodes >= 0)
        }
    }

    @Test
    fun winProtectionTakesShortWin() {
        val b = board(0, 1, 0, 1, 0, 1)
        val rng = Random(3)
        // Mittel (w=1): the short win (ml 1) always comes first, despite 50 % blunders
        repeat(10) { assertEquals(0, engine.pickMove(b, Levels.stufenWerte("mittel"), rng = rng).col) }
    }

    @Test
    fun lossProtectionFilter() {
        val b = board(0, 1, 0, 1, 0)
        val (scores, _) = engine.iterativeScores(b)
        val rng = Random(7)
        // s=1: moves after which Red loses at once (ml <= 2) are taboo
        repeat(20) { assertEquals(0, engine.filteredBlunder(b, scores, 1, null, rng)) }
        // s=0: unfiltered, any legal move
        assertTrue(engine.filteredBlunder(b, scores, 0, null, rng) in b.legalMoves())
    }

    @Test
    fun loserLevelPrefersLosingMoves() {
        val b = board(0, 1, 0, 1, 0)
        val (scores, _) = engine.iterativeScores(b)
        val losing = scores.cols.filter { scores[it]!! < 0 }.toSet()
        assertTrue(losing.isNotEmpty())
        val rng = Random(5)
        repeat(10) { assertTrue(engine.pickMove(b, Levels.stufenWerte("verlierer"), rng = rng).col in losing) }
    }

    @Test
    fun iterativeScoresProgressAndAbort() {
        val seen = ArrayList<Int>()
        val (scores, nodes) = engine.iterativeScores(board(3), onProgress = { d, _, _, _ -> seen.add(d) })
        assertEquals((0 until 7).toList(), scores.cols)
        assertTrue(nodes > 0)
        assertTrue(seen.isNotEmpty())
        assertEquals(-1, seen.last()) // last stage = full search
        val (aborted, _) = engine.iterativeScores(board(3), abort = { true })
        assertTrue(aborted.isEmpty())
    }

    @Test
    fun abortInsideSearch() {
        // The Kotlin port can also stop inside a stage (C++: only between stages).
        var calls = 0
        val t0 = System.nanoTime()
        val (scores, _) = engine.iterativeScores(Board(), abort = { ++calls > 3 })
        assertTrue((System.nanoTime() - t0) / 1e9 < 5.0)
        assertTrue(calls > 3)
        assertTrue(scores.cols.size <= 7)
    }

    @Test
    fun bookAndLabels() {
        assertTrue(engine.isBookLoaded())
        assertTrue(engine.bookText(3, de).startsWith("Buch"))
        assertEquals("berechnet", engine.bookText(20, de))
    }

    @Test
    fun randomPositionMatchesWish() {
        for ((wish, sign) in listOf(Engine.WISH_WIN to 1, Engine.WISH_DRAW to 0, Engine.WISH_LOSS to -1)) {
            val r = engine.randomPosition(4, wish, rng = Random(11))
            assertTrue(r is Engine.RandomResult.Found)
            val seq = (r as Engine.RandomResult.Found).moves
            assertEquals(4, seq.size)
            val b = Board.fromMoves(seq)
            assertFalse(b.isGameOver())
            assertEquals(sign, Integer.signum(engine.mtdf(b)))
        }
    }

    @Test
    fun randomPositionStop() {
        assertEquals(Engine.RandomResult.Stopped, engine.randomPosition(3, Engine.WISH_WIN, stop = { true }))
        val r = engine.randomPosition(5, Engine.WISH_ANY)
        assertEquals(5, (r as Engine.RandomResult.Found).moves.size)
    }

    @Test
    fun textsOfEngine() {
        assertEquals("Gelb (31)", Engine.valueText(3, 31, true, de))
        assertEquals("Gelb (12)", Engine.valueText(-3, 12, false, de))
        assertEquals("Remis", Engine.valueText(0, null, true, de))
        assertEquals("2,3 M/s", Engine.knsText(2_300_000, 1.0, "de"))
        assertEquals("2.3 M/s", Engine.knsText(2_300_000, 1.0, "en"))
        assertEquals("12 k/s", Engine.knsText(12_000, 1.0, "de"))
        assertEquals("1.234.567", Fmt.thousands(1_234_567, "de"))
        assertEquals("1 234 567", Fmt.thousands(1_234_567, "fr"))
        assertEquals("1,234,567", Fmt.thousands(1_234_567, "en"))
        assertEquals("999", Fmt.thousands(999, "de"))
        assertEquals("1.000", Fmt.thousands(1000, "de"))
        assertEquals("0", Fmt.thousands(0, "en"))
        assertEquals("-12,345", Fmt.thousands(-12345, "en"))
        assertEquals("7.402.845", Fmt.thousands(7_402_845, "it"))
    }

    // ---------- match / session score
    @Test
    fun matchScoringWithColorSwap() {
        val m = Match("leicht", "mittel", 4, wechsel = true)
        assertTrue(m.gelbBeginnt())
        assertEquals("leicht", m.sideStufe(0))
        assertEquals("mittel", m.sideStufe(1))
        assertEquals("leicht", m.recordResult(1)) // game 1: Yellow side wins as Yellow
        assertTrue(m.advance())
        assertFalse(m.gelbBeginnt())
        assertEquals("mittel", m.sideStufe(0))
        assertEquals("leicht", m.recordResult(2)) // game 2: Yellow side wins as Red
        assertTrue(m.advance())
        assertEquals("leicht", m.recordResult(1))
        assertTrue(m.advance())
        assertNull(m.recordResult(null))
        assertFalse(m.advance())
        assertTrue(m.fertig)
        assertEquals(4, m.done())
        assertEquals(3, m.punkteGelb)
        assertEquals(1, m.remis)
        assertEquals(3.5 to 0.5, m.points())
        assertEquals("3,5-0,5 (+3/=1/-0)", m.scoreStr("de", fixed = true))
        assertTrue("Elo" in m.statusLine(de))
        assertTrue("beendet" in m.resultText(de))
        // Texts identical to the Python implementation (values taken from cfs_core).
        assertEquals(
            "Computer (3 Leicht (40,0,0)) - Computer (7 Mittel (50,1,1)) = 3.5-0.5 (+3/=1/-0)  " +
                "Elo-Differenz = +338\nPartien: 4/4 (beendet), Farbwechsel: an",
            m.resultText(de),
        )
        assertEquals(
            "Computer-Computer Match 4/4 | Gelb (3 Leicht) - Rot (7 Mittel) | 3,5-0,5 (+3/=1/-0) | Elo +338",
            m.statusLine(de),
        )
        assertEquals("Partie 4/4 – Beendet.\nGelb (3 Leicht) – Rot (7 Mittel): 3.5 – 0.5 (+3/=1/-0)  Elo +338", m.liveText(de))
    }

    @Test
    fun matchHumanDisablesTurbo() {
        val m = Match("mensch", "perfekt", 2, blind = true)
        assertFalse(m.blind)
        assertTrue(m.pauseBetweenGames())
        assertEquals(0, Match("leicht", "mittel", 2, schnell = true).delayMs())
        assertEquals(350, Match("leicht", "mittel", 2).delayMs())
    }

    @Test
    fun humanWonNormalRule() {
        assertNull(Match.humanWonNormal(null, 42, true))
        assertEquals(true, Match.humanWonNormal(1, 7, true)) // human Yellow wins
        assertEquals(false, Match.humanWonNormal(1, 7, false)) // computer started (Yellow) and won
        assertEquals(true, Match.humanWonNormal(2, 8, false)) // human Red wins
    }

    @Test
    fun sessionScore() {
        val st = SessionScore()
        assertNull(st.bookNormal("mittel", true)) // off -> nothing booked
        st.toggle("mittel")
        assertTrue(st.enabled)
        assertEquals(listOf("mittel"), st.pairs.keys.toList())
        st.bookNormal("mittel", true)
        var d = st.display(de)
        assertEquals("1-0", d.big)
        assertTrue("Mittel" in d.head)
        assertEquals("", d.elo) // Elo only from 0.5 each
        st.bookNormal("mittel", null)
        d = st.display(de)
        assertEquals("1,5-0,5", d.big)
        assertEquals("Elo +191", d.elo)
        st.bookNormal("mittel", false)
        assertEquals("Elo ±0", st.display(de).elo)
        st.addMatchResult("mensch", "schwer", "schwer")
        assertEquals(listOf(0, 1, 0), st.pairs["schwer"]!!.toList())
        assertNull(st.addMatchResult("leicht", "mittel", "leicht"))
        st.resetTo("experte")
        assertEquals(listOf(0, 0, 0), st.pairs["experte"]!!.toList())
        st.toggle("mittel")
        assertEquals(ScoreDisplay.EMPTY, st.display(de))
    }

    @Test
    fun scoreToMovesLeftExamples() {
        assertEquals(42, BitBully.scoreToMovesLeft(0, Board()))
        assertEquals(1, BitBully.scoreToMovesLeft(21, Board()))
        assertNotNull(engine.book)
    }
}
