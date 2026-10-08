package io.github.patrickric.connectfourstudio.core.engine

/**
 * Bitboard primitives of the BitBully engine (Markus Thill, AGPL v3),
 * ported 1:1 from `Board.h`/`Board.cpp` of bitbully 0.0.79.
 *
 * Layout (bit index per cell, column-major, 9 bits per column, row 0 = bottom):
 * ```
 *  [ 5, 14, 23, 32, 41, 50, 59]
 *  [ 4, 13, 22, 31, 40, 49, 58]
 *  [ 3, 12, 21, 30, 39, 48, 57]
 *  [ 2, 11, 20, 29, 38, 47, 56]
 *  [ 1, 10, 19, 28, 37, 46, 55]
 *  [ 0,  9, 18, 27, 36, 45, 54]
 * ```
 * A position is (all, active, movesLeft): all occupied cells, the cells of
 * the side to move, and the number of empty cells.
 */
internal object Bits {
    const val N_COLUMNS = 7
    const val N_ROWS = 6
    const val COLUMN_BIT_OFFSET = 9

    private fun mask(vararg bits: Int): Long {
        var bb = 0L
        for (i in bits) bb = bb or (1L shl i)
        return bb
    }

    private fun isIllegalBit(bitIdx: Int): Boolean =
        bitIdx >= COLUMN_BIT_OFFSET * N_COLUMNS || (bitIdx % COLUMN_BIT_OFFSET) / N_ROWS != 0

    val BB_ILLEGAL: Long = run {
        var bb = 0L
        for (i in 0 until 64) if (isIllegalBit(i)) bb = bb xor (1L shl i)
        bb
    }
    val BB_ALL_LEGAL_TOKENS: Long = BB_ILLEGAL.inv()
    val BB_BOTTOM_ROW: Long = mask(54, 45, 36, 27, 18, 9, 0)
    val BB_TOP_ROW: Long = mask(59, 50, 41, 32, 23, 14, 5)

    private val BB_MOVES_PRIO_LIST: LongArray = longArrayOf(
        mask(29, 30),
        mask(31, 21, 20, 28, 38, 39),
        mask(40, 32, 22, 19, 27, 37),
        mask(47, 48, 11, 12),
        mask(49, 41, 23, 13, 10, 18, 36, 46),
        mask(45, 50, 14, 9),
    )

    fun columnMask(column: Int): Long =
        (1L shl (column * COLUMN_BIT_OFFSET + N_ROWS)) - (1L shl (column * COLUMN_BIT_OFFSET))

    fun cellMask(column: Int, row: Int): Long = 1L shl (column * COLUMN_BIT_OFFSET + row)

    fun lsb(x: Long): Long = (x - 1L).inv() and x

    /** Centre-first move picker (`Board::nextMove`). */
    fun nextMove(allMoves: Long): Long {
        var moves = allMoves
        for (p in BB_MOVES_PRIO_LIST) {
            val pvMv = moves and p
            if (pvMv != 0L) {
                moves = pvMv
                break
            }
        }
        return lsb(moves)
    }

    fun mix(x0: Long): Long {
        var x = x0
        x = (x xor (x ushr 30)) * 0xbf58476d1ce4e5b9uL.toLong()
        x = (x xor (x ushr 27)) * 0x94d049bb133111ebuL.toLong()
        return x xor (x ushr 31)
    }

    fun hash(all: Long, active: Long): Long = mix(mix(active) xor (mix(all) shl 1))

    fun uid(all: Long, active: Long): Long = active + all

    /** Cells that would complete a line of four for the stones in [x]. */
    fun winningPositions(x: Long, verticals: Boolean): Long {
        var wins = if (verticals) (x shl 1) and (x shl 2) and (x shl 3) else 0L
        for (b in COLUMN_BIT_OFFSET - 1..COLUMN_BIT_OFFSET + 1) {
            var tmp = (x shl b) and (x shl 2 * b)
            wins = wins or (tmp and (x shl 3 * b))
            wins = wins or (tmp and (x ushr b))
            tmp = (x ushr b) and (x ushr 2 * b)
            wins = wins or (tmp and (x shl b))
            wins = wins or (tmp and (x ushr 3 * b))
        }
        return wins and BB_ALL_LEGAL_TOKENS
    }

    /** Did the side that just moved (the inactive one) complete four? */
    fun hasWin(all: Long, active: Long): Boolean {
        val y = active xor all
        var x = y and (y shl 2)
        if ((x and (x shl 1)) != 0L) return true
        x = y and (y shl 2 * COLUMN_BIT_OFFSET)
        if ((x and (x shl COLUMN_BIT_OFFSET)) != 0L) return true
        x = y and (y shl 2 * (COLUMN_BIT_OFFSET - 1))
        if ((x and (x shl (COLUMN_BIT_OFFSET - 1))) != 0L) return true
        x = y and (y shl 2 * (COLUMN_BIT_OFFSET + 1))
        if ((x and (x shl (COLUMN_BIT_OFFSET + 1))) != 0L) return true
        return false
    }

    fun legalMovesMask(all: Long): Long = (all + BB_BOTTOM_ROW) and BB_ALL_LEGAL_TOKENS

    fun isLegalMove(all: Long, column: Int): Boolean {
        if (column < 0 || column >= N_COLUMNS) return false
        val m = columnMask(column) - (1L shl (column * COLUMN_BIT_OFFSET))
        return (all and m and BB_TOP_ROW) == 0L
    }

    fun canWin(all: Long, active: Long): Boolean =
        (winningPositions(active, true) and (all + BB_BOTTOM_ROW)) != 0L

    fun canWinColumn(all: Long, active: Long, column: Int): Boolean =
        isLegalMove(all, column) &&
            (winningPositions(active, true) and (all + BB_BOTTOM_ROW) and columnMask(column)) != 0L

    fun generateNonLosingMoves(all: Long, active: Long): Long {
        var moves = legalMovesMask(all)
        val threats = winningPositions(active xor all, true)
        val directThreats = threats and moves
        if (directThreats != 0L) {
            moves = if ((directThreats and (directThreats - 1L)) != 0L) 0L else directThreats
        }
        return moves and (threats ushr 1).inv()
    }

    fun doubleThreat(all: Long, active: Long, moves: Long): Long {
        val ownThreats = winningPositions(active, false)
        val otherThreats = winningPositions(active xor all, true)
        return moves and (ownThreats ushr 1) and (ownThreats ushr 2) and (otherThreats ushr 1).inv()
    }

    fun findThreats(all: Long, active: Long, movesIn: Long): Long {
        var moves = movesIn
        var threats = winningPositions(active, true) and all.inv()
        val curNumThreats = java.lang.Long.bitCount(threats)
        var threatMoves = 0L
        while (moves != 0L) {
            val mv = lsb(moves)
            threats = winningPositions(active xor mv, true) and (all xor mv).inv()
            if (java.lang.Long.bitCount(threats and (moves xor mv)) > 1) return mv
            if (java.lang.Long.bitCount(threats) > curNumThreats) threatMoves = threatMoves xor mv
            moves = moves xor mv
        }
        return threatMoves
    }

    fun mirrorBitBoard(x: Long): Long {
        var y = 0L
        y = y or ((x and columnMask(6)) ushr 6 * COLUMN_BIT_OFFSET)
        y = y or ((x and columnMask(0)) shl 6 * COLUMN_BIT_OFFSET)
        y = y or ((x and columnMask(5)) ushr 4 * COLUMN_BIT_OFFSET)
        y = y or ((x and columnMask(1)) shl 4 * COLUMN_BIT_OFFSET)
        y = y or ((x and columnMask(4)) ushr 2 * COLUMN_BIT_OFFSET)
        y = y or ((x and columnMask(2)) shl 2 * COLUMN_BIT_OFFSET)
        return y or (x and columnMask(3))
    }

    /** Huffman key of a 12-ply position as used by the opening books. */
    fun toHuffman(all: Long, active: Long, movesLeft: Int): Int {
        if (movesLeft < 30 || (movesLeft and 1) != 0) return 0
        var huff = 0
        for (i in 0 until N_COLUMNS) {
            var a = all ushr (i * COLUMN_BIT_OFFSET)
            var p = active ushr (i * COLUMN_BIT_OFFSET)
            var j = 0
            while (j < N_ROWS && (a and 1L) != 0L) {
                huff = huff shl 2
                huff = huff or (if ((p and 1L) != 0L) 2 else 3)
                a = a ushr 1
                p = p ushr 1
                j++
            }
            huff = huff shl 1
        }
        return huff shl 1
    }
}
