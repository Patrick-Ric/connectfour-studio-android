package io.github.patrickric.connectfourstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import io.github.patrickric.connectfourstudio.core.Game

/**
 * Board + evaluation row + the seven buttons, each button exactly one column
 * wide (desktop: "genau 7 gleich breite Buttons, exakt Brettbreite").
 * The cell size follows the available space (auto zoom); [widthFraction] /
 * [heightFraction] leave room for the info panel.
 */
class BoardArea @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    val board = BoardView(context)
    val buttons: List<FitButton> = List(Game.COLS) { FitButton(context) }

    private val density = resources.displayMetrics.density
    private val margin = Math.round(8 * density)
    private val barGap = Math.round(2 * density)
    private var barH = Math.round(48 * density)

    var widthFraction = 1f
    var heightFraction = 1f

    /** "Large board": no side margin, board may take more of the height. */
    var large = false
        set(v) {
            if (v != field) {
                field = v
                requestLayout()
            }
        }

    private val sideMargin: Int get() = if (large) 0 else margin
    private val vMargin: Int get() = margin // top/bottom margin also with the large board
    private val effHeightFraction: Float get() = if (large) maxOf(heightFraction, LARGE_HEIGHT_FRACTION) else heightFraction

    init {
        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, R.styleable.BoardArea)
            widthFraction = a.getFloat(R.styleable.BoardArea_widthFraction, 1f)
            heightFraction = a.getFloat(R.styleable.BoardArea_heightFraction, 1f)
            a.recycle()
        }
        addView(board)
        for (b in buttons) {
            b.isFocusable = false // arrow keys belong to the board (desktop: NoFocus)
            b.minWidth = 0
            b.minimumWidth = 0
            b.minHeight = 0
            b.minimumHeight = 0
            b.isAllCaps = false
            b.setSingleLine(true)
            val pad = Math.round(2 * density)
            b.setPadding(pad, 0, pad, 0)
            addView(b)
        }
    }

    private val scoreMin = 26 * density
    private val scoreMax = 44 * density
    private val barMin = 32 * density
    private val barMax = 48 * density

    /** Evaluation row and button heights for a cell size (full size unless space is tight). */
    private fun scoreFor(cell: Int): Int = Math.round((cell * 0.5f).coerceIn(scoreMin, scoreMax))
    private fun barFor(cell: Int): Int = Math.round((cell * 0.6f).coerceIn(barMin, barMax))
    private fun needH(cell: Int, scale: Float = 1f): Int =
        Math.round((Game.ROWS * cell + scoreFor(cell)) * scale) + barFor(cell) + 2 * vMargin + barGap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val availW = if (wMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else (w * widthFraction).toInt()
        val availH = if (hMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else (h * effHeightFraction).toInt()
        val widthCell = maxOf(8, (availW - 2 * sideMargin) / Game.COLS)
        var cell = widthCell
        while (cell > 8 && needH(cell) > availH) cell--
        // Large board: zoom the integer cells a little so the board fills the
        // width exactly (no rest of up to 6 px).
        var scale = 1f
        if (large && cell == widthCell && wMode == MeasureSpec.EXACTLY) {
            val s = (availW - 2 * sideMargin).toFloat() / (Game.COLS * cell)
            if (s > 1f && needH(cell, s) <= availH) scale = s
        }
        barH = barFor(cell)
        board.setScoreHeight(scoreFor(cell))
        board.setCell(cell)
        board.scale = scale
        board.measure(
            MeasureSpec.makeMeasureSpec(board.scaledW(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(board.scaledH(), MeasureSpec.EXACTLY),
        )
        for ((c, b) in buttons.withIndex()) {
            b.measure(
                MeasureSpec.makeMeasureSpec(colEdge(c + 1) - colEdge(c), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY),
            )
        }
        val needW = board.scaledW() + 2 * sideMargin
        val needH = needH(cell, scale)
        val mw = if (wMode == MeasureSpec.EXACTLY) w else minOf(needW, if (wMode == MeasureSpec.AT_MOST) w else needW)
        val mh = if (hMode == MeasureSpec.EXACTLY) h else minOf(needH, if (hMode == MeasureSpec.AT_MOST) h else needH)
        setMeasuredDimension(mw, mh)
    }

    /** Left edge of column [c] (0..7) in pixels relative to the board. */
    private fun colEdge(c: Int): Int = Math.round(c * board.cell * board.scale)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left = maxOf(0, (r - l - board.scaledW()) / 2)
        val top = vMargin
        board.layout(left, top, left + board.scaledW(), top + board.scaledH())
        val y = top + board.scaledH() + barGap
        for ((c, btn) in buttons.withIndex()) {
            btn.layout(left + colEdge(c), y, left + colEdge(c + 1), y + barH)
        }
    }

    companion object {
        private const val LARGE_HEIGHT_FRACTION = 0.82f
    }
}

/**
 * Button whose single-line text shrinks to fit its width (no AppCompat autosize
 * on API 21), or that shows a centred [icon] in the text colour instead.
 */
class FitButton(context: Context) : Button(context) {
    var icon: Drawable? = null
        set(v) {
            field = v?.mutate()
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = icon ?: return
        val s = Math.round(minOf(24 * resources.displayMetrics.density, height * 0.6f))
        val l = (width - s) / 2
        val t = (height - s) / 2
        d.setTint(currentTextColor)
        d.setBounds(l, t, l + s, t + s)
        d.draw(canvas)
    }

    private val maxSp = 15f
    private val minSp = 7f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        fit()
    }

    private fun fit() {
        val avail = width - paddingLeft - paddingRight - Math.round(4 * resources.displayMetrics.density)
        if (avail <= 0) return
        var sp = maxSp
        val p = android.graphics.Paint(paint)
        val dm = resources.displayMetrics
        while (sp > minSp) {
            p.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
            if (p.measureText(text.toString()) <= avail) break
            sp -= 0.5f
        }
        if (textSize != TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
    }
}
