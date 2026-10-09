package io.github.patrickric.connectfourstudio.core.engine

/**
 * "Buch 2d" (same data in the Qt and Tk versions): exact scores of all moves for the 57 positions
 * with up to two stones, so the start of a game needs no search at all.
 * Data from the original engine (scripts/gen_minibook.py); the unit tests
 * check them against the full search of this port.
 */
object MiniBook {
    const val MAX_STONES = 2

    private val table: Map<Long, IntArray> = HashMap<Long, IntArray>().apply {
        val seqs = listOf(emptyList<Int>()) +
            (0 until Board.COLUMNS).map { listOf(it) } +
            (0 until Board.COLUMNS).flatMap { a -> (0 until Board.COLUMNS).map { b -> listOf(a, b) } }
        for ((i, seq) in seqs.withIndex()) {
            val b = Board.fromMoves(seq)
            put(key(b), MiniBookData.SCORES.copyOfRange(i * Board.COLUMNS, (i + 1) * Board.COLUMNS))
        }
    }

    // Unique position id of the engine (active stones + all stones).
    private fun key(b: Board): Long = Bits.uid(b.all, b.active)

    /** Scores of the seven columns (copy) or null if [b] is not in the mini book. */
    fun scores(b: Board): IntArray? {
        if (b.countTokens() > MAX_STONES) return null
        val s = table[key(b)] ?: return null
        return s.copyOf()
    }

    val size: Int get() = table.size
}
