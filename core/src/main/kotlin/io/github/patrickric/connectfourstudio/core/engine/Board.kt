package io.github.patrickric.connectfourstudio.core.engine

/**
 * Connect-Four position (port of `BitBully::Board` plus the helpers of the
 * Python wrapper `bitbully.Board` that ConnectFour Studio uses).
 *
 * Columns are 0..6, rows 0..5 with row 0 at the bottom. Player 1 = Yellow
 * (moves first), player 2 = Red.
 */
class Board private constructor(
    internal var all: Long,
    internal var active: Long,
    internal var movesLeft: Int,
) {
    constructor() : this(0L, 0L, N_CELLS)

    fun copy(): Board = Board(all, active, movesLeft)

    fun movesLeft(): Int = movesLeft

    fun countTokens(): Int = N_CELLS - movesLeft

    fun isLegalMove(column: Int): Boolean = Bits.isLegalMove(all, column)

    /** Plays [column]; returns false (and changes nothing) if illegal. */
    fun play(column: Int): Boolean {
        if (!isLegalMove(column)) return false
        val mv = (all + Bits.BB_BOTTOM_ROW) and Bits.columnMask(column)
        playMask(mv)
        return true
    }

    internal fun playMask(mv: Long) {
        active = active xor all
        all = all xor mv
        movesLeft--
    }

    fun playOnCopy(column: Int): Board {
        val b = copy()
        require(b.play(column)) { "Illegal move: column $column" }
        return b
    }

    /** Legal columns in ascending order (like `Board.legal_moves()`). */
    fun legalMoves(): List<Int> = (0 until COLUMNS).filter { isLegalMove(it) }

    fun hasWin(): Boolean = Bits.hasWin(all, active)

    fun canWin(): Boolean = Bits.canWin(all, active)

    fun canWin(column: Int): Boolean = Bits.canWinColumn(all, active, column)

    fun isFull(): Boolean = movesLeft == 0

    fun isGameOver(): Boolean = hasWin() || isFull()

    /** 1 = Yellow (player who moved first), 2 = Red; null if nobody has won. */
    fun winner(): Int? {
        if (!hasWin()) return null
        return if (currentPlayer() == 1) 2 else 1
    }

    /** 1 = Yellow to move, 2 = Red to move. */
    fun currentPlayer(): Int = if (movesLeft % 2 == 0) 1 else 2

    fun columnHeight(column: Int): Int {
        require(column in 0 until COLUMNS) { "Column index must be between 0 and 6, got $column." }
        return java.lang.Long.bitCount(all and Bits.columnMask(column))
    }

    /** `to_array()`: [column][row], row 0 = bottom; 0 empty, 1 yellow, 2 red. */
    fun toArray(): Array<IntArray> {
        val activePlayer = if ((movesLeft and 1) != 0) 2 else 1
        val inactivePlayer = 3 - activePlayer
        return Array(COLUMNS) { c ->
            IntArray(ROWS) { r ->
                val m = Bits.cellMask(c, r)
                when {
                    (active and m) != 0L -> activePlayer
                    (all and m) != 0L -> inactivePlayer
                    else -> 0
                }
            }
        }
    }

    fun mirror(): Board = Board(Bits.mirrorBitBoard(all), Bits.mirrorBitBoard(active), movesLeft)

    fun toHuffman(): Int = Bits.toHuffman(all, active, movesLeft)

    override fun equals(other: Any?): Boolean =
        other is Board && other.all == all && other.active == active

    override fun hashCode(): Int = (all * 31 + active).hashCode()

    override fun toString(): String {
        val arr = toArray()
        val sb = StringBuilder()
        for (r in ROWS - 1 downTo 0) {
            for (c in 0 until COLUMNS) {
                sb.append(
                    when (arr[c][r]) {
                        1 -> "X  "
                        2 -> "O  "
                        else -> "_  "
                    },
                )
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        const val COLUMNS = Bits.N_COLUMNS
        const val ROWS = Bits.N_ROWS
        const val N_CELLS = COLUMNS * ROWS

        /**
         * Board from arr[column][row] (row 0 = bottom; 0 empty, 1 Yellow,
         * 2 Red) like `Board::setBoard(TBoardArray)`; null if invalid.
         */
        fun fromArray(arr: Array<IntArray>): Board? {
            var allTokens = 0L
            var yellow = 0L
            var nYellow = 0
            var nRed = 0
            for (c in 0 until COLUMNS) {
                var complete = false
                for (r in 0 until ROWS) {
                    val v = arr[c][r]
                    if (v !in 0..2) return null
                    if (v == 0) {
                        complete = true
                        continue
                    }
                    if (complete) return null
                    val m = Bits.cellMask(c, r)
                    if (v == 1) {
                        yellow = yellow xor m
                        nYellow++
                    } else {
                        nRed++
                    }
                    allTokens = allTokens xor m
                }
            }
            if (nYellow - nRed !in 0..1) return null
            val movesLeft = N_CELLS - java.lang.Long.bitCount(allTokens)
            val active = yellow xor (if ((movesLeft and 1) != 0) allTokens else 0L)
            return Board(allTokens, active, movesLeft)
        }

        /** Board after [moves] (columns 0..6); throws on an illegal move. */
        fun fromMoves(moves: List<Int>): Board {
            val b = Board()
            for (m in moves) require(b.play(m)) { "Illegal move: column $m" }
            return b
        }
    }
}
