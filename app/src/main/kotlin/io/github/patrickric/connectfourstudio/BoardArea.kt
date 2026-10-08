package io.github.patrickric.connectfourstudio

import android.content.Context
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
    private val barH = Math.round(48 * density)

    var widthFraction = 1f
    var heightFraction = 1f

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

    private fun extraH(): Int = 2 * margin + board.scoreH + barGap + barH

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wMode = MeasureSpec.getMode(widthMeasureSpec)
        val hMode = MeasureSpec.getMode(heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val availW = if (wMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else (w * widthFraction).toInt()
        val availH = if (hMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4 else (h * heightFraction).toInt()
        val cell = maxOf(8, minOf((availW - 2 * margin) / Game.COLS, (availH - extraH()) / Game.ROWS))
        board.setCell(cell)
        board.measure(
            MeasureSpec.makeMeasureSpec(board.boardW(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(board.canvasH(), MeasureSpec.EXACTLY),
        )
        for (b in buttons) {
            b.measure(
                MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY),
            )
        }
        val needW = board.boardW() + 2 * margin
        val needH = board.canvasH() + extraH() - board.scoreH
        val mw = if (wMode == MeasureSpec.EXACTLY) w else minOf(needW, if (wMode == MeasureSpec.AT_MOST) w else needW)
        val mh = if (hMode == MeasureSpec.EXACTLY) h else minOf(needH, if (hMode == MeasureSpec.AT_MOST) h else needH)
        setMeasuredDimension(mw, mh)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cell = board.cell
        val left = maxOf(0, (r - l - board.boardW()) / 2)
        val top = margin
        board.layout(left, top, left + board.boardW(), top + board.canvasH())
        val y = top + board.canvasH() + barGap
        for ((c, btn) in buttons.withIndex()) {
            btn.layout(left + c * cell, y, left + (c + 1) * cell, y + barH)
        }
    }
}

/** Button whose single-line text shrinks to fit its width (no AppCompat autosize on API 21). */
class FitButton(context: Context) : Button(context) {
    private val maxSp = 15f
    private val minSp = 8f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        fit()
    }

    private fun fit() {
        val avail = width - paddingLeft - paddingRight - Math.round(8 * resources.displayMetrics.density)
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
