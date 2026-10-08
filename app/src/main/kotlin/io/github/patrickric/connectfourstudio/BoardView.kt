package io.github.patrickric.connectfourstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import io.github.patrickric.connectfourstudio.core.Game

/**
 * Board + evaluation row drawn on a Canvas (port of `cfs_qt.board.BoardCanvas`):
 * tiles of the stone set, ghost stone, falling stone, last-move ring, double
 * rings on the winning line and the evaluation row exactly under the columns.
 *
 * Touch: the ghost stone follows the finger, lifting it drops the stone;
 * sliding off the board cancels. A mouse shows the ghost on hover and the
 * wheel changes the stone set. Taps in the 4-dp gap between board and
 * evaluation row are ignored (desktop: 4 px).
 */
class BoardView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var controller: Controller? = null

    var cell: Int = 40
        private set
    private val density = resources.displayMetrics.density
    val scoreH: Int = Math.round(SCORE_H_DP * density)
    private val scoreGap: Int = Math.round(SCORE_GAP_DP * density)

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint()
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        color = 0xff888888.toInt()
        strokeWidth = 1f
    }
    private val textBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val textVal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private val winGrid = BooleanArray(Game.ROWS * Game.COLS)
    private var touching = false

    fun boardW(): Int = Game.COLS * cell
    fun boardH(): Int = Game.ROWS * cell
    fun canvasH(): Int = boardH() + scoreH

    fun setCell(c: Int) {
        val n = maxOf(8, c)
        if (n != cell) {
            cell = n
            requestLayout()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(boardW(), canvasH())
    }

    private fun colFromX(x: Float): Int? {
        if (x < 0) return null
        val col = (x / cell).toInt()
        return if (col in 0 until Game.COLS) col else null
    }

    // ------------------------------------------------------------ input
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val c = controller ?: return false
        val inside = ev.x >= 0 && ev.x < width && ev.y >= 0 && ev.y < height
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touching = true
                if (ev.y < boardH()) c.setHover(colFromX(ev.x)) else c.setHover(null)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touching) return true
                if (!inside) {
                    touching = false // slid off the board: cancel
                    c.setHover(null)
                } else if (ev.y < boardH()) {
                    c.setHover(colFromX(ev.x))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasTouching = touching
                touching = false
                if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) c.setHover(null)
                if (!wasTouching || !inside) return true
                val bh = boardH()
                if (ev.y >= bh && ev.y < bh + scoreGap) return true // gap between board and row
                val col = colFromX(ev.x) ?: return true
                performClick()
                c.onCanvasClick(col)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touching = false
                c.setHover(null)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        val c = controller ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                c.setHover(if (ev.y < boardH()) colFromX(ev.x) else null)
            MotionEvent.ACTION_HOVER_EXIT -> c.setHover(null)
        }
        return true
    }

    private var wheelAcc = 0f

    override fun onGenericMotionEvent(ev: MotionEvent): Boolean {
        val c = controller
        if (c != null && ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER) &&
            ev.actionMasked == MotionEvent.ACTION_SCROLL
        ) {
            // Wheel up = previous set, down = next set (like the desktop).
            wheelAcc += ev.getAxisValue(MotionEvent.AXIS_VSCROLL)
            while (wheelAcc >= 1f) {
                wheelAcc -= 1f
                c.cycleSet(-1)
            }
            while (wheelAcc <= -1f) {
                wheelAcc += 1f
                c.cycleSet(+1)
            }
            return true
        }
        return super.onGenericMotionEvent(ev)
    }

    // ------------------------------------------------------------ drawing
    override fun onDraw(canvas: Canvas) {
        val c = controller ?: return
        val sets = c.sets
        val si = sets.info(c.setNo)
        val game = c.game
        fillPaint.color = si.edge
        canvas.drawRect(0f, 0f, boardW().toFloat(), boardH().toFloat(), fillPaint)
        val arr = game.toArray()
        val falling = c.falling
        winGrid.fill(false)
        if (falling == null) for (wc in game.winCells()) winGrid[wc.rTop * Game.COLS + wc.c] = true
        val last = if (c.showLast && falling == null) game.lastMove else null
        val lastIdx = if (last == null) -1 else last.rTop * Game.COLS + last.c
        // During the animation the stone is not yet at its target (A1).
        val hideIdx = if (falling == null) -1 else (Game.ROWS - game.columnHeight(falling.col)) * Game.COLS + falling.col
        val back = sets.tile(c.setNo, "back", cell)
        for (col in 0 until Game.COLS) {
            for (row in 0 until Game.ROWS) {
                val rTop = Game.ROWS - 1 - row
                val x0 = (col * cell).toFloat()
                val y0 = (rTop * cell).toFloat()
                val v = arr[col][row]
                val idx = rTop * Game.COLS + col
                if (v == 0 || idx == hideIdx) {
                    canvas.drawBitmap(back, x0, y0, bmpPaint)
                    continue
                }
                canvas.drawBitmap(sets.tile(c.setNo, if (v == 1) "yellow" else "red", cell), x0, y0, bmpPaint)
                if (winGrid[idx]) {
                    val sr = si.stoneR
                    val wGreen = maxOf(3, cell / 16)
                    val pad = cell * (0.5f - sr) - 2 - wGreen / 2
                    ring(canvas, x0, y0, pad, 0xff00ff00.toInt(), wGreen)
                    val wWhite = maxOf(1, cell / 40)
                    val pad2 = cell * (0.5f - sr) + 1 + wWhite / 2
                    ring(canvas, x0, y0, pad2, Color.WHITE, wWhite)
                } else if (idx == lastIdx) {
                    val sr = if (v == 2) si.stoneRR else si.stoneRY
                    val wLast = maxOf(2, cell / 25)
                    val pad = cell * (0.5f - sr) - 2 - wLast / 2
                    ring(canvas, x0, y0, pad, Color.WHITE, wLast)
                }
            }
        }
        // Ghost stone (preview) above the target column
        val hc = c.hoverCol
        if (c.ghost && hc != null && falling == null && !game.isGameOver() && game.isLegal(hc)) {
            val stone = game.stoneToMove()
            val rTop = Game.ROWS - 1 - game.columnHeight(hc)
            canvas.drawBitmap(sets.tile(c.setNo, stone.id + "_ghost", cell), (hc * cell).toFloat(), (rTop * cell).toFloat(), bmpPaint)
        }
        if (falling != null) {
            canvas.drawBitmap(
                sets.tile(c.setNo, falling.stone.id, cell),
                (falling.col * cell).toFloat(), falling.rows * cell, bmpPaint,
            )
        }
        paintScores(canvas, c)
    }

    private fun ring(canvas: Canvas, x0: Float, y0: Float, pad: Float, color: Int, width: Int) {
        ringPaint.color = color
        ringPaint.strokeWidth = width.toFloat()
        rect.set(x0 + pad, y0 + pad, x0 + cell - pad, y0 + cell - pad)
        canvas.drawOval(rect, ringPaint)
    }

    /** Evaluation row: column numbers, or +/=/- (bold) with the stone count below. */
    private fun paintScores(canvas: Canvas, c: Controller) {
        val y0 = boardH().toFloat()
        val h = scoreH.toFloat()
        textBig.textSize = h * 0.36f
        textVal.textSize = h * 0.30f
        val scores = c.lastScores
        for (col in 0 until Game.COLS) {
            val x0 = (col * cell).toFloat()
            val sc = scores?.get(col)
            val top = sc?.top ?: (col + 1).toString()
            val sub = sc?.sub ?: ""
            fillPaint.color = sc?.bg ?: Controller.C_BASE
            canvas.drawRect(x0, y0, x0 + cell, y0 + h, fillPaint)
            canvas.drawRect(x0 + 0.5f, y0 + 0.5f, x0 + cell - 0.5f, y0 + h - 0.5f, borderPaint)
            val cx = x0 + cell / 2f
            if (sub.isNotEmpty()) {
                centerText(canvas, cx, y0 + h * 0.30f, top, textBig)
                centerText(canvas, cx, y0 + h * 0.72f, sub, textVal)
            } else {
                centerText(canvas, cx, y0 + h / 2f, top, textVal)
            }
        }
    }

    private fun centerText(canvas: Canvas, cx: Float, cy: Float, text: String, p: Paint) {
        val fm = p.fontMetrics
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, p)
    }

    companion object {
        const val SCORE_H_DP = 44f
        const val SCORE_GAP_DP = 4f
    }
}
