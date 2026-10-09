package io.github.patrickric.connectfourstudio

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.patrickric.connectfourstudio.core.Stone
import org.json.JSONArray

/**
 * Help (desktop: HelpDialog): contents of cfs_core.help_content in the app
 * language with table of contents, cross links, search (case-insensitive,
 * wrap-around, yellow marking, "not found") and text zoom 70-180 %.
 */
class HelpActivity : Activity() {
    private lateinit var c: Controller
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var findEdit: EditText
    private lateinit var findInfo: TextView
    private lateinit var zoomInfo: TextView
    private val items = ArrayList<Item>()
    private val blocks = ArrayList<Block>()
    private val anchorIndex = HashMap<String, Int>()
    private var factor = 1.0f
    private var hitBlock = -1
    private var hitEnd = 0
    private var hitSpan: BackgroundColorSpan? = null
    private val t get() = c.tx

    private class Item(val kind: String, val text: Spanned, val hasLink: Boolean)
    private class Block(val view: TextView, val kind: String, val text: Spanned, val holder: View)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtil.wrap(newBase, CfsApp.of(newBase).lang()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        c = CfsApp.of(this).controller
        title = t.t("help_ui_help_title")
        factor = savedInstanceState?.getFloat("zoom", 1f) ?: 1f
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val p = Math.round(6 * dp)
            setPadding(p, p, p, 0)
        }
        findEdit = EditText(this).apply {
            hint = t.t("help_ui_find_label").trimEnd(':', ' ')
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, ev ->
                when {
                    // Hardware Enter: search on key down, also consume key up
                    // (otherwise the focus jumps to the next paragraph).
                    ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER -> {
                        if (ev.action == KeyEvent.ACTION_DOWN) findNext()
                        true
                    }
                    actionId == EditorInfo.IME_ACTION_SEARCH -> {
                        findNext()
                        true
                    }
                    else -> false
                }
            }
        }
        bar.addView(findEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(smallButton(t.t("help_ui_find_btn")) { findNext() })
        bar.addView(smallButton("A+") { zoom(+1) })
        bar.addView(smallButton("A−") { zoom(-1) })
        zoomInfo = TextView(this).apply { setPadding(Math.round(4 * dp), 0, 0, 0) }
        bar.addView(zoomInfo)
        root.addView(bar)
        findInfo = TextView(this).apply {
            setTextColor(Color.rgb(176, 0, 0))
            setPadding(Math.round(10 * dp), 0, 0, 0)
        }
        root.addView(findInfo)
        scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Math.round(12 * dp)
            setPadding(p, Math.round(4 * dp), p, p)
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        build()
        applyZoom()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat("zoom", factor)
    }

    private fun smallButton(label: String, fn: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        val p = Math.round(8 * resources.displayMetrics.density)
        setPadding(p, 0, p, 0)
        setOnClickListener { fn() }
    }

    private fun build() {
        val dp = resources.displayMetrics.density
        // Stone icons of the current set (desktop: yellow and red at the top).
        val icons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val px = Math.round(22 * dp)
        for (s in listOf(Stone.YELLOW, Stone.RED)) {
            icons.addView(
                ImageView(this).apply {
                    setImageBitmap(c.sets.stoneTile(c.setNo, s, px))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(px, px).apply { marginEnd = Math.round(8 * dp) },
            )
        }
        content.addView(icons)

        val json = resources.openRawResource(R.raw.help).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            when (val kind = o.getString("t")) {
                "head", "sub" -> {
                    anchorIndex[o.getString("id")] = items.size
                    items.add(Item(kind, SpannableString(o.getString("text")), false))
                }
                "para" -> {
                    val sb = SpannableStringBuilder()
                    val parts = o.getJSONArray("parts")
                    var hasLink = false
                    for (k in 0 until parts.length()) {
                        val p = parts.getJSONObject(k)
                        val start = sb.length
                        when {
                            p.has("s") -> sb.append(p.getString("s"))
                            p.has("b") -> {
                                sb.append(p.getString("b"))
                                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            p.has("l") -> {
                                sb.append(p.getString("l"))
                                sb.setSpan(LinkSpan(p.getString("to")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                hasLink = true
                            }
                        }
                    }
                    items.add(Item(kind, sb, hasLink))
                }
                "mono" -> items.add(Item(kind, SpannableString(o.getString("text")), false))
            }
        }
        // The first screen at once, the rest in small batches per frame, so the
        // UI thread never blocks for long (slow devices).
        buildUpTo(INITIAL_BLOCKS - 1)
        content.post(object : Runnable {
            override fun run() {
                if (isFinishing || blocks.size >= items.size) return
                buildUpTo(blocks.size + BATCH_BLOCKS - 1)
                content.post(this)
            }
        })
    }

    /** Creates the views of all items up to [last] (inclusive). */
    private fun buildUpTo(last: Int) {
        val dp = resources.displayMetrics.density
        while (blocks.size <= minOf(last, items.size - 1)) {
            val it = items[blocks.size]
            val tv = TextView(this)
            tv.setTextColor(if (it.kind == "head" || it.kind == "sub") COLOR_HEAD else COLOR_TEXT)
            tv.setText(it.text, TextView.BufferType.SPANNABLE)
            var holder: View = tv
            when (it.kind) {
                "head", "sub" -> {
                    tv.setTypeface(null, Typeface.BOLD)
                    tv.setPadding(0, Math.round((if (it.kind == "head") 14 else 10) * dp), 0, Math.round(4 * dp))
                }
                "para" -> {
                    tv.setPadding(0, Math.round(3 * dp), 0, Math.round(3 * dp))
                    tv.setTextIsSelectable(true)
                    if (it.hasLink) tv.movementMethod = LinkMovementMethod.getInstance()
                }
                "mono" -> {
                    tv.typeface = Typeface.MONOSPACE
                    tv.setTextIsSelectable(true)
                    val hs = HorizontalScrollView(this)
                    hs.addView(tv)
                    hs.setPadding(0, Math.round(4 * dp), 0, Math.round(4 * dp))
                    holder = hs
                }
            }
            content.addView(holder)
            val b = Block(tv, it.kind, it.text, holder)
            blocks.add(b)
            sizeBlock(b)
        }
    }

    /** Runs [fn] once [v] has been laid out. */
    private fun afterLayout(v: View, fn: () -> Unit) {
        if (v.isLaidOut && !v.isLayoutRequested) {
            fn()
            return
        }
        v.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                fn()
            }
        })
    }

    private inner class LinkSpan(private val target: String) : ClickableSpan() {
        override fun onClick(widget: View) = goto(target)

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.color = Color.BLUE
            ds.isUnderlineText = true
        }
    }

    /** Jumps to an anchor (heading) of the help text. */
    fun goto(name: String) {
        val idx = anchorIndex[name] ?: return
        buildUpTo(idx)
        val v = blocks[idx].holder
        afterLayout(v) { scroll.smoothScrollTo(0, v.top) }
    }

    private fun sizeBlock(b: Block) {
        val base = 15f * factor
        b.view.textSize = when (b.kind) {
            "head" -> base * 1.3f
            "sub" -> base * 1.1f
            "mono" -> base * 0.85f
            else -> base
        }
    }

    @SuppressLint("SetTextI18n") // "110%" as on the desktop
    private fun applyZoom() {
        for (b in blocks) sizeBlock(b)
        zoomInfo.text = "${Math.round(factor * 100)}%"
    }

    /** +1 / -1 in 10 % steps (70-180 %), 0 = 100 %. */
    fun zoom(direction: Int) {
        val frac = if (content.height > 0) scroll.scrollY.toFloat() / content.height else 0f
        factor = when {
            direction > 0 -> minOf(1.8f, Math.round((factor + 0.1f) * 100) / 100f)
            direction < 0 -> maxOf(0.7f, Math.round((factor - 0.1f) * 100) / 100f)
            else -> 1f
        }
        applyZoom()
        scroll.post { scroll.scrollTo(0, (frac * content.height).toInt()) }
    }

    /** Searches forward from the last hit (case-insensitive), wraps, marks yellow. */
    fun findNext() {
        val needle = findEdit.text.toString().lowercase()
        clearHit()
        if (needle.isEmpty()) {
            findInfo.text = ""
            return
        }
        val startBlock = if (hitBlock < 0) 0 else hitBlock
        val n = items.size
        for (step in 0..n) {
            val bi = (startBlock + step) % n
            val from = if (step == 0 && hitBlock >= 0) hitEnd else 0
            if (step == n && from == 0) break
            val txt = items[bi].text.toString().lowercase()
            val idx = txt.indexOf(needle, from)
            if (idx >= 0) {
                showHit(bi, idx, idx + needle.length)
                findInfo.text = ""
                return
            }
        }
        hitBlock = -1
        hitEnd = 0
        findInfo.text = t.t("help_ui_not_found")
    }

    private fun clearHit() {
        val s = hitSpan ?: return
        if (hitBlock in blocks.indices) (blocks[hitBlock].view.text as? android.text.Spannable)?.removeSpan(s)
        hitSpan = null
    }

    private fun showHit(bi: Int, start: Int, end: Int) {
        buildUpTo(bi)
        val b = blocks[bi]
        val span = BackgroundColorSpan(Color.YELLOW)
        (b.view.text as? android.text.Spannable)?.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        hitSpan = span
        hitBlock = bi
        hitEnd = end
        afterLayout(b.view) {
            val layout = b.view.layout ?: return@afterLayout
            val line = layout.getLineForOffset(start)
            val inner = if (b.holder !== b.view) b.view.top else 0 // TextView inside a HorizontalScrollView
            val y = b.holder.top + inner + layout.getLineTop(line) - Math.round(24 * resources.displayMetrics.density)
            scroll.smoothScrollTo(0, maxOf(0, y))
            if (b.holder is HorizontalScrollView) {
                b.holder.smoothScrollTo(maxOf(0, layout.getPrimaryHorizontal(start).toInt() - 40), 0)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_F -> {
                    findEdit.requestFocus()
                    findEdit.selectAll()
                    return true
                }
                KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                    zoom(+1)
                    return true
                }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                    zoom(-1)
                    return true
                }
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> {
                    zoom(0)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val INITIAL_BLOCKS = 12
        private const val BATCH_BLOCKS = 6
        private const val COLOR_HEAD = 0xff1e3a8a.toInt()
        private const val COLOR_TEXT = 0xff202020.toInt()
    }
}
