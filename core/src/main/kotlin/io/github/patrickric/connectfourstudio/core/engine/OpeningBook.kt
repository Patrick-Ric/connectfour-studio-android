package io.github.patrickric.connectfourstudio.core.engine

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.InflaterInputStream

/** Sorted (Huffman key, value) table of the 12-ply opening book. */
interface BookStore {
    val size: Int
    fun key(index: Int): Int
    fun value(index: Int): Int
}

class ArrayBookStore(private val keys: IntArray, private val values: ByteArray) : BookStore {
    init {
        require(keys.size == values.size)
    }

    override val size: Int get() = keys.size
    override fun key(index: Int): Int = keys[index]
    override fun value(index: Int): Int = values[index].toInt()
}

/**
 * Book in the raw layout written by [BookCodec.decodeTo]: header, n big-endian
 * keys, n value bytes. Works on a memory-mapped file, so the 21 MB table does
 * not live on the Java heap.
 */
class BufferBookStore(private val buf: ByteBuffer) : BookStore {
    override val size: Int
    private val valueBase: Int

    init {
        require(buf.getInt(0) == BookCodec.RAW_MAGIC) { "Not a raw book file" }
        size = buf.getInt(4)
        valueBase = BookCodec.RAW_HEADER + 4 * size
        require(buf.capacity() >= valueBase + size) { "Truncated book file" }
    }

    override fun key(index: Int): Int = buf.getInt(BookCodec.RAW_HEADER + 4 * index)
    override fun value(index: Int): Int = buf.get(valueBase + index).toInt()
}

/**
 * Port of `BitBully::OpeningBook` for the "12-ply-dist" database
 * (bitbully-databases, MIT licence): 4 200 899 positions with exactly 12
 * stones and their distance-to-end values.
 */
class OpeningBook(private val store: BookStore) {
    val nPly: Int = 12
    val size: Int get() = store.size

    private fun binarySearch(huffmanCode: Int): Int {
        var l = 0
        var r = store.size - 1
        while (r >= l) {
            val mid = (l + r + 1) / 2
            val k = store.key(mid)
            if (k == huffmanCode) return store.value(mid)
            if (k > huffmanCode) r = mid - 1 else l = mid + 1
        }
        return NONE_VALUE
    }

    private fun convertValue(value: Int, movesLeft: Int): Int {
        val ml = Math.abs(value) - 100 + movesLeft
        return Integer.signum(value) * (ml / 2 + 1)
    }

    /** Engine score of a 12-stone position, [NONE_VALUE] for other positions. */
    internal fun getBoardValue(all: Long, active: Long, movesLeft: Int): Int {
        if (Board.N_CELLS - movesLeft != nPly) return NONE_VALUE
        var v = binarySearch(Bits.toHuffman(all, active, movesLeft))
        if (v != NONE_VALUE) return convertValue(v, movesLeft)
        v = binarySearch(Bits.toHuffman(Bits.mirrorBitBoard(all), Bits.mirrorBitBoard(active), movesLeft))
        if (v == NONE_VALUE) {
            // Positions where Yellow can win immediately are not stored.
            return (movesLeft + 1) / 2
        }
        return convertValue(v, movesLeft)
    }

    fun getBoardValue(b: Board): Int = getBoardValue(b.all, b.active, b.movesLeft)

    /** Raw stored value of exactly this (non-mirrored) position or null. */
    fun rawValue(b: Board): Int? = binarySearch(b.toHuffman()).takeIf { it != NONE_VALUE }

    companion object {
        const val NONE_VALUE = -128
    }
}

/**
 * Compact container for the book asset (".cfb"): magic "CFB1", entry count,
 * then a zlib stream with the key deltas as unsigned LEB128 varints (first
 * delta relative to Int.MIN_VALUE) followed by one value byte per entry.
 * About 5.4 MB instead of 21 MB; written by scripts/encode_book.py.
 */
object BookCodec {
    const val CFB_MAGIC = 0x43464231 // "CFB1"
    const val RAW_MAGIC = 0x43464252 // "CFBR"
    const val RAW_HEADER = 8

    private class Reader(input: InputStream) {
        val count: Int
        private val data: DataInputStream

        init {
            val head = DataInputStream(input)
            if (head.readInt() != CFB_MAGIC) throw IOException("Not a CFB1 book")
            count = head.readInt()
            if (count <= 0) throw IOException("Bad entry count $count")
            data = DataInputStream(InflaterInputStream(input, java.util.zip.Inflater(), 1 shl 16).buffered(1 shl 16))
        }

        private var prev = Int.MIN_VALUE.toLong()

        fun nextKey(): Int {
            var shift = 0
            var delta = 0L
            while (true) {
                val b = data.read()
                if (b < 0) throw EOFException("Truncated book")
                delta = delta or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 35) throw IOException("Bad varint")
            }
            if (delta <= 0L && prev != Int.MIN_VALUE.toLong()) throw IOException("Keys not ascending")
            prev += delta
            if (prev > Int.MAX_VALUE) throw IOException("Key overflow")
            return prev.toInt()
        }

        fun readValues(out: ByteArray) = data.readFully(out)
    }

    /** Decodes a .cfb stream into heap arrays (used by the JVM tests). */
    fun decode(input: InputStream): ArrayBookStore {
        val r = Reader(input)
        val keys = IntArray(r.count) { r.nextKey() }
        val values = ByteArray(r.count)
        r.readValues(values)
        return ArrayBookStore(keys, values)
    }

    /** Decodes a .cfb stream into the raw layout read by [BufferBookStore]. */
    fun decodeTo(input: InputStream, output: OutputStream): Int {
        val r = Reader(input)
        val out = java.io.DataOutputStream(output.buffered(1 shl 16))
        out.writeInt(RAW_MAGIC)
        out.writeInt(r.count)
        repeat(r.count) { out.writeInt(r.nextKey()) }
        val values = ByteArray(r.count)
        r.readValues(values)
        out.write(values)
        out.flush()
        return r.count
    }

    fun rawSize(count: Int): Long = RAW_HEADER + 5L * count
}
