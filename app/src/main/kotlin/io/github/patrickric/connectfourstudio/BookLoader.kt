package io.github.patrickric.connectfourstudio

import android.content.Context
import io.github.patrickric.connectfourstudio.core.engine.BookCodec
import io.github.patrickric.connectfourstudio.core.engine.BufferBookStore
import io.github.patrickric.connectfourstudio.core.engine.OpeningBook
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Makes the 12-ply book available without putting 21 MB on the Java heap:
 * the compressed asset is decoded once into the no-backup files directory
 * and then memory-mapped read-only.
 */
object BookLoader {
    private const val ASSET = "book/book_12ply_dist.cfb"
    private const val FILE = "book_12ply_dist.v1.bin"

    fun load(context: Context): OpeningBook {
        val dir = context.noBackupFilesDir
        val file = File(dir, FILE)
        if (!isValid(file)) {
            val tmp = File(dir, "$FILE.tmp")
            context.assets.open(ASSET).use { input ->
                tmp.outputStream().use { out -> BookCodec.decodeTo(input, out) }
            }
            if (!tmp.renameTo(file)) throw java.io.IOException("cannot rename $tmp")
        }
        RandomAccessFile(file, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
            return OpeningBook(BufferBookStore(buf))
        }
    }

    private fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() < BookCodec.RAW_HEADER) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = raf.readInt()
                val count = raf.readInt()
                magic == BookCodec.RAW_MAGIC && count > 0 && file.length() == BookCodec.rawSize(count)
            }
        } catch (_: java.io.IOException) {
            false
        }
    }
}
