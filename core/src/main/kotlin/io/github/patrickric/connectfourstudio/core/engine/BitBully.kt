package io.github.patrickric.connectfourstudio.core.engine

/** Thrown inside the search when the abort callback asks to stop. */
class SearchAborted : RuntimeException("search aborted", null, false, false)

/**
 * Perfect-play Connect-Four solver – a 1:1 port of `BitBully::BitBully`
 * (bitbully 0.0.79 by Markus Thill, AGPL v3): negamax with alpha-beta,
 * move ordering, transposition table (EXACT/LOWER/UPPER + ETC + mirror
 * lookups), 12-ply opening book, MTD(f) driver and depth-limited rollouts.
 *
 * Scores follow Pascal Pons' convention: positive = the side to move wins
 * (larger = sooner), negative = it loses, 0 = draw; [scoreToMovesLeft]
 * converts a score into the number of stones until the game ends.
 *
 * The node counter, the transposition table (same size, hash and replacement
 * scheme as the C++ code) and every search decision are identical, so scores
 * and node counts match the Python/C++ engine exactly (checked by the tests).
 *
 * Not thread-safe; [io.github.patrickric.connectfourstudio.core.Engine]
 * serialises all calls.
 *
 * @param logTtSize log2 of the transposition-table size (C++ default 22).
 */
class BitBully(logTtSize: Int = DEFAULT_LOG_TT_SIZE, var book: OpeningBook? = null) {
    private val ttSize = 1 shl logTtSize
    private val ttMask = (ttSize - 1).toLong()
    private val ttKey = LongArray(ttSize)

    /** value (16 bit, signed) | flag << 16 | searchDepth (int8) << 24 */
    private val ttData = IntArray(ttSize)

    var nodeCounter: Long = 0L
        private set

    /** Polled every 1024 nodes; returning true aborts with [SearchAborted]. */
    var abort: (() -> Boolean)? = null

    // Per-depth move lists (MoveList of the C++ code), depth <= 42.
    private val mlMoves = Array(MAX_PLY) { LongArray(Bits.N_COLUMNS) }
    private val mlScores = Array(MAX_PLY) { IntArray(Bits.N_COLUMNS) }
    private val mlSize = IntArray(MAX_PLY)

    val isBookLoaded: Boolean get() = book != null

    fun resetTranspositionTable() {
        ttKey.fill(0L)
        ttData.fill(0)
    }

    fun resetNodeCounter() {
        nodeCounter = 0L
    }

    // ---------------------------------------------------------------- TT
    private fun ttIndex(all: Long, active: Long): Int = (Bits.hash(all, active) and ttMask).toInt()

    private fun ttValue(d: Int): Int = (d shl 16) shr 16
    private fun ttFlag(d: Int): Int = (d ushr 16) and 3
    private fun ttDepth(d: Int): Int = d shr 24

    private fun ttPack(value: Int, flag: Int, depth: Int): Int =
        (value and 0xffff) or (flag shl 16) or ((depth and 0xff) shl 24)

    // ---------------------------------------------------------------- search
    /** Cheap evaluation: both sides play safe centre-first moves to the end. */
    private fun rollout(all0: Long, active0: Long, movesLeft0: Int): Int {
        var all = all0
        var active = active0
        var movesLeft = movesLeft0
        var ply = 0
        while (true) {
            if (Bits.canWin(all, active)) {
                val score = (movesLeft + 1) / 2
                return if (ply % 2 == 0) score else -score
            }
            if (movesLeft == 0) return 0
            val moves = Bits.generateNonLosingMoves(all, active)
            if (moves == 0L) {
                val score = -(movesLeft / 2)
                return if (ply % 2 == 0) score else -score
            }
            val mv = Bits.nextMove(moves)
            active = active xor all
            all = all xor mv
            movesLeft--
            ply++
        }
    }

    private fun sortMoves(all: Long, active: Long, movesIn: Long, depth: Int) {
        var moves = movesIn
        val ms = mlMoves[depth]
        val sc = mlScores[depth]
        var size = 0
        val ownThreats = Bits.winningPositions(active, false)
        while (moves != 0L) {
            val mv = Bits.nextMove(moves)
            val threats = Bits.winningPositions(active xor mv, true) and (all xor mv).inv()
            var numThreats = java.lang.Long.bitCount(threats)
            if ((ownThreats and (mv shl 1)) != 0L) numThreats--
            // MoveList::insert – ascending by score, FIFO for equal scores.
            var pos = size++
            while (pos > 0 && sc[pos - 1] >= numThreats) {
                ms[pos] = ms[pos - 1]
                sc[pos] = sc[pos - 1]
                pos--
            }
            ms[pos] = mv
            sc[pos] = numThreats
            moves = moves xor mv
        }
        mlSize[depth] = size
    }

    private fun popMove(depth: Int): Long {
        val size = mlSize[depth]
        if (size == 0) return 0L
        mlSize[depth] = size - 1
        return mlMoves[depth][size - 1]
    }

    private fun negamax(
        all: Long,
        active: Long,
        movesLeft: Int,
        alphaIn: Int,
        betaIn: Int,
        depth: Int,
        maxDepth: Int,
    ): Int {
        var alpha = alphaIn
        var beta = betaIn
        nodeCounter++
        if ((nodeCounter and 0x3ffL) == 0L) {
            val a = abort
            if (a != null && a()) throw SearchAborted()
        }

        if (maxDepth >= 0 && depth >= maxDepth) return rollout(all, active, movesLeft)

        val remainingBudget = if (maxDepth < 0) 127 else (maxDepth - depth).toByte().toInt()

        val bk = book
        if (bk != null && Board.N_CELLS - movesLeft == bk.nPly) {
            return bk.getBoardValue(all, active, movesLeft)
        }

        if (depth == 0 && Bits.canWin(all, active)) return (movesLeft + 1) / 2

        if (alpha >= (movesLeft + 1) / 2) return alpha

        val min = -movesLeft / 2
        if (alpha < min) {
            alpha = min
            if (alpha >= beta) return alpha
        }
        val max = (movesLeft - 1) / 2
        if (beta > max) {
            beta = max
            if (alpha >= beta) return beta
        }

        if (movesLeft == 0) return 0

        val oldAlpha = alpha

        var moves = Bits.generateNonLosingMoves(all, active)
        if (moves == 0L) return -movesLeft / 2

        if (depth < 20 && Bits.doubleThreat(all, active, moves) != 0L) return (movesLeft - 1) / 2

        var ttIdx = -1
        if (movesLeft > 6 && movesLeft % 2 == 0) {
            ttIdx = ttIndex(all, active)
            val d = ttData[ttIdx]
            if (ttKey[ttIdx] == Bits.uid(all, active) && ttDepth(d) >= remainingBudget) {
                val v = ttValue(d)
                when (ttFlag(d)) {
                    EXACT -> return v
                    LOWER -> alpha = maxOf(alpha, v)
                    UPPER -> beta = minOf(beta, v)
                }
                if (alpha >= beta) return v
            }
        } else if (depth < 22 && movesLeft % 2 != 0) {
            // Enhanced Transposition Cutoff
            var etcMoves = Bits.legalMovesMask(all)
            while (etcMoves != 0L) {
                val mv = Bits.nextMove(etcMoves)
                val eActive = active xor all
                val eAll = all xor mv
                val ei = ttIndex(eAll, eActive)
                val d = ttData[ei]
                if (ttKey[ei] == Bits.uid(eAll, eActive) &&
                    ttDepth(d) >= remainingBudget &&
                    ttFlag(d) != LOWER &&
                    -ttValue(d) >= beta
                ) {
                    return -ttValue(d)
                }
                etcMoves = etcMoves xor mv
            }
        }

        // Symmetric positions
        if (movesLeft > 20) {
            val mAll = Bits.mirrorBitBoard(all)
            val mActive = Bits.mirrorBitBoard(active)
            val mi = ttIndex(mAll, mActive)
            val d = ttData[mi]
            if (ttKey[mi] == Bits.uid(mAll, mActive) && ttDepth(d) >= remainingBudget) {
                val v = ttValue(d)
                when (ttFlag(d)) {
                    EXACT -> return v
                    LOWER -> alpha = maxOf(alpha, v)
                    UPPER -> beta = minOf(beta, v)
                }
                if (alpha >= beta) return v
            }
        }

        var value = -(1 shl 10)
        val nActive = active xor all
        if (depth < 20) {
            sortMoves(all, active, moves, depth)
            var mv = popMove(depth)
            while (mv != 0L && alpha < beta) {
                val moveValue = -negamax(all xor mv, nActive, movesLeft - 1, -beta, -alpha, depth + 1, maxDepth)
                value = maxOf(value, moveValue)
                alpha = maxOf(alpha, value)
                mv = popMove(depth)
            }
        } else {
            var threats = if (depth < 22) Bits.findThreats(all, active, moves) else 0L
            while (moves != 0L && alpha < beta) {
                val mv = if (threats != 0L) Bits.nextMove(threats) else Bits.nextMove(moves)
                val moveValue = -negamax(all xor mv, nActive, movesLeft - 1, -beta, -alpha, depth + 1, maxDepth)
                value = maxOf(value, moveValue)
                alpha = maxOf(alpha, value)
                threats = threats and mv.inv()
                moves = moves xor mv
            }
        }

        if (ttIdx < 0) return value
        val flag = when {
            value <= oldAlpha -> UPPER
            value >= beta -> LOWER
            else -> EXACT
        }
        ttKey[ttIdx] = Bits.uid(all, active)
        ttData[ttIdx] = ttPack(value, flag, remainingBudget)
        return value
    }

    fun negamax(b: Board, alpha: Int = -1000, beta: Int = 1000, depth: Int = 0, maxDepth: Int = -1): Int =
        negamax(b.all, b.active, b.movesLeft, alpha, beta, depth, maxDepth)

    /** MTD(f) driver (Plaat et al. 1996). */
    fun mtdf(b: Board, firstGuess: Int = 0, maxDepth: Int = -1): Int {
        var g = firstGuess
        var upperBound = Int.MAX_VALUE
        var lowerBound = Int.MIN_VALUE
        while (lowerBound < upperBound) {
            val beta = maxOf(g, lowerBound + 1)
            g = negamax(b.all, b.active, b.movesLeft, beta - 1, beta, 0, maxDepth)
            if (g < beta) upperBound = g else lowerBound = g
        }
        return g
    }

    /** Score of dropping a stone in [column], -1000 if the column is full. */
    fun scoreMove(b: Board, column: Int, firstGuess: Int, maxDepth: Int = -1): Int {
        var score = ILLEGAL
        val after = b.copy()
        if (after.play(column)) {
            if (after.hasWin()) return after.movesLeft / 2 + 1
            score = -mtdf(after, firstGuess, maxDepth)
        }
        return score
    }

    /** Scores of all seven columns (C++ `scoreMoves`); full columns = [ILLEGAL]. */
    fun scoreMoves(b: Board, maxDepth: Int = -1): IntArray {
        val scores = IntArray(Bits.N_COLUMNS) { ILLEGAL }
        for (col in 0 until Bits.N_COLUMNS) {
            scores[col] = scoreMove(b, col, if (col == 0) 0 else scores[col - 1], maxDepth)
        }
        return scores
    }

    companion object {
        const val DEFAULT_LOG_TT_SIZE = 22
        const val ILLEGAL = -1000
        private const val MAX_PLY = 64
        private const val EXACT = 1
        private const val LOWER = 2
        private const val UPPER = 3

        /** Number of stones still to be played until the game ends (`scoreToMovesLeft`). */
        fun scoreToMovesLeft(score: Int, b: Board): Int {
            val movesLeft = b.movesLeft
            if (score == 0) return movesLeft
            val p = (movesLeft + 1) % 2
            val sgnScore = if (score < 0) 1 else 0
            val absScore = if (score < 0) -score else score
            val mvFinalLeft = 2 * (absScore - 1) + (sgnScore xor p)
            return movesLeft - mvFinalLeft
        }
    }
}
