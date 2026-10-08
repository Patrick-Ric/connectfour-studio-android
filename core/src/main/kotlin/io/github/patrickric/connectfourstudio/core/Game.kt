package io.github.patrickric.connectfourstudio.core

import io.github.patrickric.connectfourstudio.core.engine.Board

/** Cell in picture coordinates: rTop 0 = top row, c = column 0..6. */
data class Cell(val rTop: Int, val c: Int)

/** `.4gp` format: the played columns as digits 1-7, ASCII, no line end. */
object Gp4 {
    /** Digit string -> columns 0..6 (all other characters are ignored). */
    fun parse(text: String): List<Int> = text.filter { it in '1'..'7' }.map { it - '1' }

    fun parse(bytes: ByteArray): List<Int> =
        bytes.filter { it in '1'.code.toByte()..'7'.code.toByte() }.map { it - '1'.code.toByte() }

    /** Columns 0..6 -> "4433221". */
    fun format(history: List<Int>): String = history.joinToString("") { (it + 1).toString() }

    fun bytes(history: List<Int>): ByteArray = format(history).toByteArray(Charsets.US_ASCII)

    /**
     * Legal prefix up to the end of the game: illegal moves are skipped,
     * after a four-in-a-row everything is ignored (like the Tk version).
     */
    fun legalPrefix(moves: List<Int>): List<Int> {
        val b = Board()
        val out = ArrayList<Int>()
        for (m in moves) {
            if (b.isGameOver()) break
            if (m in 0 until Game.COLS && b.isLegalMove(m)) {
                b.play(m)
                out.add(m)
            }
        }
        return out
    }
}

/** Board + history + redo stack (port of `cfs_core.game.Game`). */
class Game {
    var board: Board = Board()
        private set
    val history: MutableList<Int> = ArrayList()
    val future: MutableList<Int> = ArrayList()

    fun rebuild() {
        board = boardFromMoves(history)
    }

    fun reset() {
        board = Board()
        history.clear()
        future.clear()
    }

    /** Position from a move list (legal prefix up to the end of the game). */
    fun setMoves(moves: List<Int>) {
        reset()
        history.addAll(Gp4.legalPrefix(moves))
        rebuild()
    }

    /** Restores history and redo stack, e.g. after the process was killed. */
    fun restore(hist: List<Int>, fut: List<Int>) {
        reset()
        val legal = Gp4.legalPrefix(hist)
        history.addAll(legal)
        if (legal.size == hist.size) {
            val all = Gp4.legalPrefix(legal + fut.reversed())
            if (all.size == legal.size + fut.size) future.addAll(fut)
        }
        rebuild()
    }

    fun isGameOver(): Boolean = board.isGameOver()
    fun isLegal(col: Int): Boolean = board.isLegalMove(col)

    /** 1 = Yellow, 2 = Red, null = nobody (yet) / draw. */
    fun winner(): Int? = board.winner()

    fun currentPlayerNo(): Int = if (history.size % 2 == 0) 1 else 2
    fun stoneToMove(): Stone = stoneForMoveNo(history.size)
    fun columnHeight(col: Int): Int = board.columnHeight(col)
    fun toArray(): Array<IntArray> = board.toArray()

    /** Cell of the last stone played or null. */
    val lastMove: Cell?
        get() {
            if (history.isEmpty()) return null
            val col = history.last()
            val h = board.columnHeight(col)
            if (h <= 0) return null
            return Cell(ROWS - h, col)
        }

    fun winCells(): Set<Cell> = if (!board.isGameOver()) emptySet() else winCells(board.toArray())

    fun copyBoard(): Board = boardFromMoves(history)

    fun play(col: Int) {
        history.add(col)
        future.clear()
        rebuild()
    }

    fun undo(): Boolean {
        if (history.isEmpty()) return false
        future.add(history.removeAt(history.size - 1))
        rebuild()
        return true
    }

    fun redo(): Boolean {
        if (future.isEmpty()) return false
        history.add(future.removeAt(future.size - 1))
        rebuild()
        return true
    }

    fun gotoFirst() {
        while (history.isNotEmpty()) future.add(history.removeAt(history.size - 1))
        rebuild()
    }

    fun gotoLast() {
        while (future.isNotEmpty()) history.add(future.removeAt(future.size - 1))
        rebuild()
    }

    companion object {
        const val COLS = Board.COLUMNS
        const val ROWS = Board.ROWS

        fun boardFromMoves(moves: List<Int>): Board = Board.fromMoves(moves)

        /** Move number n (0-based): Yellow starts, so even = Yellow. */
        fun stoneForMoveNo(n: Int): Stone = if (n % 2 == 0) Stone.YELLOW else Stone.RED

        /** All cells belonging to a line of four or more; arr[col][row], row 0 = bottom. */
        fun winCells(arr: Array<IntArray>): Set<Cell> {
            val grid = Array(ROWS) { r -> IntArray(COLS) { c -> arr[c][r] } }
            val found = LinkedHashSet<Cell>()
            val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))
            for (r in 0 until ROWS) {
                for (c in 0 until COLS) {
                    val v = grid[r][c]
                    if (v == 0) continue
                    for ((dc, dr) in dirs.map { it[0] to it[1] }) {
                        val cells = ArrayList<IntArray>()
                        cells.add(intArrayOf(r, c))
                        var nr = r + dr
                        var nc = c + dc
                        while (nr in 0 until ROWS && nc in 0 until COLS && grid[nr][nc] == v) {
                            cells.add(intArrayOf(nr, nc))
                            nr += dr
                            nc += dc
                        }
                        nr = r - dr
                        nc = c - dc
                        while (nr in 0 until ROWS && nc in 0 until COLS && grid[nr][nc] == v) {
                            cells.add(intArrayOf(nr, nc))
                            nr -= dr
                            nc -= dc
                        }
                        if (cells.size >= 4) {
                            for (cell in cells) found.add(Cell(ROWS - 1 - cell[0], cell[1]))
                        }
                    }
                }
            }
            return found
        }
    }
}

enum class Stone(val id: String) {
    YELLOW("yellow"),
    RED("red"),
}
