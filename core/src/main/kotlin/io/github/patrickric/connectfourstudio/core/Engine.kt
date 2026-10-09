package io.github.patrickric.connectfourstudio.core

import io.github.patrickric.connectfourstudio.core.engine.BitBully
import io.github.patrickric.connectfourstudio.core.engine.Board
import io.github.patrickric.connectfourstudio.core.engine.OpeningBook
import io.github.patrickric.connectfourstudio.core.engine.SearchAborted
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

/**
 * Scores of the seven columns (`score_all_moves`); full columns are absent.
 * Iteration order of the Python dict (sorted by value, descending, stable)
 * is reproduced by [best] (lowest column among the best values).
 */
class Scores(private val values: IntArray) {
    init {
        require(values.size == Game.COLS)
    }

    operator fun get(col: Int): Int? = values[col].takeIf { it != BitBully.ILLEGAL }

    /** Columns that have a score, ascending. */
    val cols: List<Int> get() = (0 until Game.COLS).filter { values[it] != BitBully.ILLEGAL }

    fun isEmpty(): Boolean = cols.isEmpty()

    fun max(): Int = cols.maxOf { values[it] }

    /** `max(scores, key=scores.get)`: first column with the maximum value. */
    fun best(): Int {
        val m = max()
        return cols.first { values[it] == m }
    }

    fun toArray(): IntArray = values.copyOf()

    fun toMap(): Map<Int, Int> = cols.associateWith { values[it] }

    override fun equals(other: Any?): Boolean = other is Scores && other.values.contentEquals(values)
    override fun hashCode(): Int = values.contentHashCode()
    override fun toString(): String = toMap().toString()

    companion object {
        val EMPTY = Scores(IntArray(Game.COLS) { BitBully.ILLEGAL })
    }
}

/** Result of an engine move: column, score, loss length (or null), nodes. */
data class EngineMove(val col: Int, val score: Int, val dist: Int?, val nodes: Long)

/** Progress callback: depth (-1 = full), scores, cumulative nodes, seconds. */
typealias Progress = (depth: Int, scores: Scores, nodes: Long, dt: Double) -> Unit

/**
 * BitBully engine: iterative evaluation, move choice per level, random
 * positions (port of `cfs_core.engine.Engine`, algorithms 1:1 so the
 * strength tables stay valid).
 *
 * Thread model: every search runs under [lock]. Unlike the Python version
 * (which collects progress callbacks and delivers them after the search),
 * progress is reported live from the worker thread; callbacks must not call
 * back into the engine. A search can be aborted inside a depth (the C++
 * engine could only stop between depths); the depth in progress is dropped.
 */
class Engine(logTtSize: Int = BitBully.DEFAULT_LOG_TT_SIZE, book: OpeningBook? = null) {
    val agent = BitBully(logTtSize, book)
    val lock = ReentrantLock()

    /** Last reached iteration depth (-1 = full) or null. */
    @Volatile
    var lastDepth: Int? = null

    var book: OpeningBook?
        get() = agent.book
        set(value) {
            lock.withLock { agent.book = value }
        }

    fun isBookLoaded(): Boolean = agent.isBookLoaded

    /** "Tiefe" display: last iteration depth or book horizon. */
    fun pliesText(nMoves: Int, tx: Texts): String {
        val d = lastDepth
        if (d != null) return depthLabel(d, tx)
        if (!isBookLoaded()) return "–"
        if (nMoves <= BOOK_HORIZON) return "${tx.t("depth_book")} $BOOK_SHORT"
        return "–"
    }

    /** "Quelle" display: "Buch 12d" up to 12 stones, then "berechnet". */
    fun bookText(nMoves: Int, tx: Texts): String {
        if (!isBookLoaded()) return "–"
        if (nMoves <= BOOK_HORIZON) return "${tx.t("book_from_book")} $BOOK_SHORT"
        return tx.t("book_computed")
    }

    fun reset() {
        lock.withLock {
            agent.resetTranspositionTable()
            agent.resetNodeCounter()
        }
    }

    /** Resets the TT only if no search is running (used by "New game"). */
    fun tryReset(): Boolean {
        if (!lock.tryLock()) return false
        try {
            agent.resetTranspositionTable()
            agent.resetNodeCounter()
        } finally {
            lock.unlock()
        }
        return true
    }

    // ------------------------------------------------------------ helpers
    /** Win guard (variant B): winning moves with ml <= 2*w-1. */
    fun shortWins(board: Board, scores: Scores, wschutz: Int?): List<Int> {
        val w = wschutz ?: 0
        if (w <= 0) return emptyList()
        val limit = 2 * w - 1
        val menge = ArrayList<Int>()
        for (c in board.legalMoves()) {
            val s = scores[c] ?: continue
            if (s <= 0) continue
            if (movesLeft(board, s) <= limit) menge.add(c)
        }
        return menge
    }

    /**
     * Filtered blunder: uniform among legal moves; moves after which the
     * opponent wins within [cutoff] moves (ml <= 2*cutoff) are taboo. Win
     * guard first; everything taboo -> longest resistance.
     */
    fun filteredBlunder(board: Board, scores: Scores, cutoffIn: Int?, wschutz: Int?, rng: Random): Int {
        val winMenge = shortWins(board, scores, wschutz)
        if (winMenge.isNotEmpty()) return winMenge[rng.nextInt(winMenge.size)]
        val legal = board.legalMoves()
        val cutoff = cutoffIn ?: 99
        if (cutoff <= 0) return legal[rng.nextInt(legal.size)]
        val limit = 2 * cutoff
        val ok = ArrayList<Int>()
        for (c in legal) {
            val s = scores[c]
            if (s == null || s >= 0) {
                ok.add(c)
                continue
            }
            if (movesLeft(board, s) <= limit) continue
            ok.add(c)
        }
        if (ok.isNotEmpty()) return ok[rng.nextInt(ok.size)]
        var bestC = legal[0]
        var bestMl = -1
        for (c in legal) {
            val s = scores[c]
            val ml = if (s != null && s < 0) movesLeft(board, s) else 1_000_000_000
            if (ml > bestMl) {
                bestC = c
                bestMl = ml
            }
        }
        return bestC
    }

    // ------------------------------------------------------------ evaluation
    /**
     * `score_all_moves` in stages (TT kept between stages). [onProgress] at
     * most every 200 ms plus the last stage; [abort] stops the search.
     */
    fun iterativeScores(
        board: Board,
        depths: IntArray = ITER_DEPTHS,
        onProgress: Progress? = null,
        abort: (() -> Boolean)? = null,
        keepTt: Boolean = false,
    ): Pair<Scores, Long> {
        val t0 = System.nanoTime()
        var lastCb = 0.0
        var scores = Scores.EMPTY
        var nodes = 0L
        val stop = abort ?: { false }
        // Below 12 stones every line ends in the 12-ply book once the depth
        // reaches 12 - stones: from then on the scores are exact and deeper
        // iterations only repeat the same search (Android only, see DECISIONS.md).
        val bookDepth = if (isBookLoaded() && board.countTokens() < BOOK_HORIZON) BOOK_HORIZON - board.countTokens() else null
        lock.withLock {
            agent.resetNodeCounter()
            if (!keepTt) agent.resetTranspositionTable()
            agent.abort = abort
            try {
                for (depth in depths) {
                    if (stop()) break
                    val part = try {
                        agent.scoreMoves(board, depth)
                    } catch (e: SearchAborted) {
                        break
                    }
                    scores = Scores(part)
                    nodes = agent.nodeCounter
                    val bookDone = bookDepth != null && depth != -1 && depth >= bookDepth
                    val shown = if (bookDone) DEPTH_BOOK else depth
                    lastDepth = shown
                    val dt = (System.nanoTime() - t0) / 1e9
                    if (onProgress != null && (depth == depths.last() || bookDone || dt - lastCb >= 0.2)) {
                        lastCb = dt
                        onProgress(shown, scores, nodes, dt)
                    }
                    if (depth == -1 || bookDone) break
                    if (stop()) break
                }
            } finally {
                agent.abort = null
            }
        }
        return scores to nodes
    }

    /** Exact score of a position (MTD(f)); [abort] -> [SearchAborted]. */
    fun mtdf(board: Board, abort: (() -> Boolean)? = null): Int =
        lock.withLock {
            agent.abort = abort
            try {
                agent.mtdf(board, 0, -1)
            } finally {
                agent.abort = null
            }
        }

    // ------------------------------------------------------------ move choice
    /** Engine move for [stufe] (from [Levels.stufenWerte]). */
    fun pickMove(
        board: Board,
        stufe: Stufe,
        onProgress: Progress? = null,
        abort: (() -> Boolean)? = null,
        keepTt: Boolean = false,
        rng: Random = newRng(),
    ): EngineMove {
        var (scores, nodes) = iterativeScores(board, ITER_DEPTHS, onProgress, abort, keepTt)
        if (scores.isEmpty()) {
            // Aborted before the first stage: depth 4 (milliseconds), so a move always comes.
            lock.withLock {
                agent.resetNodeCounter()
                scores = Scores(agent.scoreMoves(board, 4))
                nodes = agent.nodeCounter
            }
        }
        require(!scores.isEmpty()) { "no legal move" }
        val key = stufe.key
        val err = stufe.err
        val schutz = stufe.s
        val wschutz = stufe.w

        fun result(c: Int, dist: Int? = null) = EngineMove(c, scores[c] ?: scores.max(), dist, nodes)

        if (key == "verlierer") {
            val legal = board.legalMoves()
            val verl = legal.filter { scores[it] != null && scores[it]!! < 0 }
            if (verl.isNotEmpty()) return result(verl[rng.nextInt(verl.size)])
            val rem = legal.filter { scores[it] == 0 }
            if (rem.isNotEmpty()) return result(rem[rng.nextInt(rem.size)])
            return result(filteredBlunder(board, scores, 0, null, rng))
        }
        if (key == "zufall") return result(filteredBlunder(board, scores, 0, null, rng))
        val win = shortWins(board, scores, wschutz)
        if (win.isNotEmpty()) return result(win[rng.nextInt(win.size)])
        if (err > 0.0 && rng.nextDouble() < err) {
            return result(filteredBlunder(board, scores, schutz, wschutz, rng))
        }
        val best = scores.max()
        if (best >= 0) {
            val legal = board.legalMoves().toSet()
            var cands = scores.cols.filter { scores[it] == best }.filter { it in legal }
            if (cands.isEmpty()) cands = legal.toList()
            var pool = cands
            if (best > 0) {
                val ml = cands.associateWith { c -> scores[c]?.let { movesLeft(board, it) } }
                val fast = cands.filter { ml[it] != null && ml[it]!! <= 10 }
                if (fast.isNotEmpty()) {
                    val mn = fast.minOf { ml[it]!! }
                    pool = fast.filter { ml[it] == mn }
                }
            }
            val col = pool[rng.nextInt(pool.size)]
            return EngineMove(col, scores[col]!!, null, nodes)
        }
        if (key == "perfekt") {
            val allMl = board.legalMoves().filter { scores[it] != null }
                .associateWith { movesLeft(board, scores[it]!!) }
            if (allMl.isNotEmpty() && allMl.values.all { it > 10 }) {
                val ks = allMl.keys.toList()
                return result(ks[rng.nextInt(ks.size)])
            }
            return result(filteredBlunder(board, scores, 5, null, rng))
        }
        var dist = scores.cols.associateWith { movesLeft(board, scores[it]!!) }
        if (dist.values.toSet().size <= 1) {
            try {
                val exact = lock.withLock {
                    agent.abort = abort
                    try {
                        Scores(agent.scoreMoves(board, -1))
                    } finally {
                        agent.abort = null
                    }
                }
                if (!exact.isEmpty() && exact.max() < 0) {
                    dist = exact.cols.associateWith { movesLeft(board, exact[it]!!) }
                    scores = exact
                }
            } catch (_: SearchAborted) {
                // keep the iterative result
            }
        }
        // Python dict order of `dist`: by score descending (stable).
        val order = scores.cols.sortedByDescending { scores[it]!! }.filter { it in dist }
        val weights = order.associateWith { c -> Math.pow(maxOf(1, dist[c]!!).toDouble(), LOSS_POWER.toDouble()) }
        var r = rng.nextDouble() * weights.values.sum()
        for (c in weights.keys.sorted()) {
            r -= weights[c]!!
            if (r <= 0) return EngineMove(c, scores[c]!!, dist[c], nodes)
        }
        val c = weights.maxByOrNull { it.value }!!.key
        return EngineMove(c, scores[c]!!, dist[c], nodes)
    }

    // ------------------------------------------------------------ random position
    /** [n] random legal moves; the game must not end before. Returns null on failure. */
    fun randomLegalSeq(n: Int, rng: Random): List<Int>? {
        repeat(60) {
            val seq = ArrayList<Int>()
            var b = Board()
            var ok = true
            for (i in 0 until n) {
                val legal = b.legalMoves()
                if (legal.isEmpty()) {
                    ok = false
                    break
                }
                val c = legal[rng.nextInt(legal.size)]
                val b2 = b.playOnCopy(c)
                if (b2.isGameOver() && seq.size + 1 < n) {
                    ok = false
                    break
                }
                b = b2
                seq.add(c)
            }
            if (ok && !b.isGameOver()) return seq
        }
        return null
    }

    sealed class RandomResult {
        data class Found(val moves: List<Int>) : RandomResult()
        object NotFound : RandomResult()
        object Stopped : RandomResult()
    }

    /**
     * Move sequence with [n] stones and the wished result for the side to
     * move: [WISH_ANY] / [WISH_WIN] / [WISH_DRAW] / [WISH_LOSS].
     */
    fun randomPosition(n: Int, wunsch: String, stop: (() -> Boolean)? = null, rng: Random = newRng()): RandomResult {
        val want = when (wunsch) {
            WISH_WIN -> 1
            WISH_DRAW -> 0
            WISH_LOSS -> -1
            else -> null
        }
        repeat(400) {
            if (stop != null && stop()) return RandomResult.Stopped
            val cand = randomLegalSeq(n, rng) ?: return@repeat
            if (want == null) return RandomResult.Found(cand)
            val s = try {
                mtdf(Board.fromMoves(cand), stop)
            } catch (_: SearchAborted) {
                return RandomResult.Stopped
            }
            if (Integer.signum(s) == want) return RandomResult.Found(cand)
        }
        return RandomResult.NotFound
    }

    companion object {
        private val seedCounter = java.util.concurrent.atomic.AtomicLong()

        /** Fresh pseudo random generator per call (Kotlin XorWow, independent of the platform RNG). */
        fun newRng(): Random = Random(System.nanoTime() xor (seedCounter.incrementAndGet() * -0x61c8864680b583ebL))

        const val LOSS_POWER = 8
        val ITER_DEPTHS = intArrayOf(4, 6, 8, 10, 12, 14, 16, 18, 20, -1)
        const val BOOK_NAME = "12-ply-dist"
        const val BOOK_SHORT = "12d"
        const val BOOK_HORIZON = 12

        /** Pseudo depth: iteration stopped because every line reached the book. */
        const val DEPTH_BOOK = -2

        /** "Tiefe" text: number, "Voll" (-1) or "Buch 12d" ([DEPTH_BOOK]). */
        fun depthLabel(depth: Int, tx: Texts): String = when (depth) {
            -1 -> tx.t("depth_full")
            DEPTH_BOOK -> "${tx.t("depth_book")} $BOOK_SHORT"
            else -> depth.toString()
        }

        // Internal (German) keys of the random-position wish, as in the Python code.
        const val WISH_ANY = "Egal"
        const val WISH_WIN = "Gewinn"
        const val WISH_DRAW = "Unentschieden"
        const val WISH_LOSS = "Verlust"

        /** Stones until the end (lower number of the evaluation row). */
        fun movesLeft(board: Board, score: Int): Int = BitBully.scoreToMovesLeft(score, board)

        /** Nodes per second, decimal separator per language. */
        fun knsText(nodes: Long, dt: Double, lang: String): String {
            val kns = nodes / maxOf(dt, 1e-9)
            if (kns >= 1_000_000) return Fmt.decimal(Fmt.fixed(kns / 1_000_000, 1) + " M/s", lang)
            if (kns >= 1_000) return Fmt.fixed(kns / 1_000, 0) + " k/s"
            return Fmt.fixed(kns, 0) + " /s"
        }

        /** "Gelb (31)" / "Rot (12)" / "Remis (42)" from the mover's view. */
        fun valueText(score: Int, ml: Int?, moverIsYellow: Boolean, tx: Texts): String {
            val gelb = tx.t("color_yellow")
            val rot = tx.t("color_red")
            val w = when {
                score > 0 -> if (moverIsYellow) gelb else rot
                score < 0 -> if (moverIsYellow) rot else gelb
                else -> tx.t("value_draw")
            }
            return if (ml == null) w else "$w ($ml)"
        }
    }
}
