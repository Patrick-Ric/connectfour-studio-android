package io.github.patrickric.connectfourstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import io.github.patrickric.connectfourstudio.core.Stone
import org.json.JSONObject

/** Measured data of one stone set (from cfs_core.sets.SetArt.load_all()). */
class SetInfo(val no: Int, val edge: Int, val stoneRY: Float, val stoneRR: Float, val stoneR: Float)

/**
 * Stone sets: 20 boards with matching stones (assets/sets/set<N>/{back,red,yellow}.webp,
 * 256 px). Tiles are scaled once per cell size (like the QPixmap cache of
 * the Qt version); only the current set is kept in memory.
 */
class StoneSets(private val context: Context) {
    val order: List<Int>
    private val info: Map<Int, SetInfo>

    init {
        val json = JSONObject(context.assets.open("sets/sets.json").bufferedReader().use { it.readText() })
        val ord = json.getJSONArray("order")
        order = (0 until ord.length()).map { ord.getInt(it) }
        val sets = json.getJSONObject("sets")
        info = order.associateWith { no ->
            val o = sets.getJSONObject(no.toString())
            SetInfo(
                no, Color.parseColor(o.getString("edge")),
                o.getDouble("stone_r_y").toFloat(), o.getDouble("stone_r_r").toFloat(),
                o.getDouble("stone_r").toFloat(),
            )
        }
    }

    fun info(no: Int): SetInfo = info[no] ?: info.getValue(order.first())

    fun contains(no: Int): Boolean = no in info

    private var rawSet = -1
    private var raw: Map<String, Bitmap> = emptyMap()

    private fun raw(no: Int): Map<String, Bitmap> {
        if (no != rawSet) {
            val opts = BitmapFactory.Options().apply { inScaled = false }
            raw = listOf("back", "red", "yellow").associateWith { name ->
                context.assets.open("sets/set$no/$name.webp").use { BitmapFactory.decodeStream(it, null, opts)!! }
            }
            rawSet = no
            tiles.clear()
        }
        return raw
    }

    private val tiles = HashMap<String, Bitmap>()

    /** Tile kinds: back, yellow, red, yellow_ghost, red_ghost (size x size). */
    fun tile(no: Int, kind: String, size: Int): Bitmap {
        val r = raw(no)
        val key = "$kind/$size"
        tiles[key]?.let { return it }
        if (tiles.size > 24) tiles.clear()
        val s = maxOf(1, size)
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val dst = Rect(0, 0, s, s)
        val base = kind.removeSuffix("_ghost")
        if (kind.endsWith("_ghost")) {
            c.drawBitmap(halved(r.getValue("back"), s), null, dst, p)
            p.alpha = 127 // desktop: alpha // 2
            c.drawBitmap(halved(r.getValue(base), s), null, dst, p)
        } else {
            c.drawBitmap(halved(r.getValue(base), s), null, dst, p)
        }
        tiles[key] = out
        return out
    }

    /** Halves with filtering while >= 2x the target (close to Lanczos quality). */
    private fun halved(src: Bitmap, size: Int): Bitmap {
        var b = src
        while (b.width >= 2 * size && b.width > 2) {
            b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        }
        return b
    }

    fun stoneTile(no: Int, stone: Stone, size: Int): Bitmap = tile(no, stone.id, size)
}
