package io.github.patrickric.connectfourstudio

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import io.github.patrickric.connectfourstudio.core.Engine
import io.github.patrickric.connectfourstudio.core.Game
import io.github.patrickric.connectfourstudio.core.Levels
import io.github.patrickric.connectfourstudio.core.tf

/** Main window: board, buttons, info boxes, status line, menus. */
class MainActivity : Activity(), Controller.Listener {
    private lateinit var c: Controller
    private lateinit var area: BoardArea
    private lateinit var status: TextView
    private lateinit var turnIcon: ImageView
    private lateinit var turnText: TextView
    private val infoLabels = HashMap<String, TextView>()
    private val infoValues = HashMap<String, TextView>()
    private var dialog: AlertDialog? = null
    private var dialogKey: String? = null
    private var randomN = 3
    private var randomWish = 1

    private val t get() = c.tx

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtil.wrap(newBase, CfsApp.of(newBase).lang()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = CfsApp.of(this)
        val fresh = !app.hasController
        c = app.controller
        if (fresh && savedInstanceState?.containsKey("history") == true) c.restoreState(savedInstanceState)
        title = getString(R.string.app_name)
        setContentView(R.layout.activity_main)
        bindViews()
        setupCompactLandscape()
        c.addListener(this)
        onUpdate(ALL)
        savedInstanceState?.let {
            randomN = it.getInt("random_n", 3)
            randomWish = it.getInt("random_wish", 1)
            when (it.getString("dialog")) {
                "level" -> showLevelDialog()
                "set" -> showSetDialog()
                "lang" -> showLanguageDialog()
                "random" -> showRandomDialog()
                "info" -> showInfo()
            }
        }
    }

    override fun onDestroy() {
        c.removeListener(this)
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        c.saveState(outState)
        dialogKey?.let { outState.putString("dialog", it) }
        outState.putInt("random_n", randomN)
        outState.putInt("random_wish", randomWish)
    }

    /**
     * Landscape on phones: the action bar would take a sixth of the height, so
     * it is hidden and the menu opens from a button in the right column.
     */
    private fun setupCompactLandscape() {
        val cfg = resources.configuration
        val btn = findViewById<ImageButton>(R.id.menu_button) ?: return
        val compact = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE && cfg.screenHeightDp < COMPACT_HEIGHT_DP
        if (!compact) return
        actionBar?.hide()
        btn.visibility = View.VISIBLE
        btn.setOnClickListener {
            val popup = PopupMenu(this, btn)
            buildMenu(popup.menu)
            popup.setOnMenuItemClickListener { onOptionsItemSelected(it) }
            popup.show()
        }
    }

    private fun bindViews() {
        area = findViewById(R.id.board_area)
        area.board.controller = c
        status = findViewById(R.id.status)
        turnIcon = findViewById(R.id.turn_icon)
        turnText = findViewById(R.id.turn_text)
        val cmds = listOf<() -> Unit>(
            c::newGame, c::gotoFirst, c::undo, c::redo, c::gotoLast,
            { c.engineMove() }, c::toggleAutoAnalyzeBtn,
        )
        area.buttons.forEachIndexed { i, b -> b.setOnClickListener { cmds[i]() } }
        findViewById<Button>(R.id.stand_toggle).setOnClickListener { c.standToggle() }
        findViewById<Button>(R.id.stand_reset).setOnClickListener { c.standReset() }
        val table = findViewById<TableLayout>(R.id.info_rows)
        table.removeAllViews()
        for (key in Controller.INFO_KEYS) {
            val row = TableRow(this)
            val lab = TextView(this, null, 0, R.style.InfoLabel)
            val v = TextView(this, null, 0, R.style.InfoValue)
            row.addView(lab)
            row.addView(v)
            table.addView(row)
            infoLabels[key] = lab
            infoValues[key] = v
        }
    }

    // ------------------------------------------------------------ updates from the controller
    override fun onUpdate(flags: Int) {
        if (flags and Controller.BOARD != 0) area.board.invalidate()
        if (flags and Controller.PANEL != 0) updatePanel()
        if (flags and Controller.MENU != 0) invalidateOptionsMenu()
        if (flags and Controller.MATCH_FRONT != 0 && c.matchWinOpen) {
            startActivity(Intent(this, MatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        if (flags and Controller.ERROR != 0) {
            c.pendingError?.let { (title, msg) ->
                c.pendingError = null
                showError(title, msg)
            }
        }
    }

    // Labels "Zug:" etc. are built like on the desktop (text + ":").
    @SuppressLint("SetTextI18n")
    private fun updatePanel() {
        val labels = listOf("btn_new", "btn_first", "btn_back", "btn_forward", "btn_last", "btn_move", "btn_analyze")
        area.buttons.forEachIndexed { i, b ->
            val txt = t.t(labels[i])
            if (b.text.toString() != txt) b.text = txt
        }
        findViewById<TextView>(R.id.turn_title).text = t.t("info_turn")
        findViewById<TextView>(R.id.info_title).text = t.t("info_box")
        findViewById<TextView>(R.id.score_title).text = t.t("score_box")
        findViewById<Button>(R.id.stand_toggle).text = t.t("score_on")
        findViewById<Button>(R.id.stand_reset).text = t.t("score_reset_btn")
        val iconPx = Math.round(36 * resources.displayMetrics.density)
        turnIcon.setImageBitmap(c.sets.stoneTile(c.setNo, c.turnStone(), iconPx))
        turnText.text = c.currentPlayerLabel()
        for (key in Controller.INFO_KEYS) {
            infoLabels[key]?.text = t.t(key) + ":"
            infoValues[key]?.text = c.info[key]
        }
        val sd = c.standDisplay
        // Empty lines are hidden, so the box stays small while the score is off.
        for ((id, txt) in listOf(R.id.stand_head to sd.head, R.id.stand_big to sd.big, R.id.stand_sub to sd.sub, R.id.stand_elo to sd.elo)) {
            val tv = findViewById<TextView>(id)
            tv.text = txt
            tv.visibility = if (txt.isEmpty()) View.GONE else View.VISIBLE
        }
        status.text = c.status
    }

    // ------------------------------------------------------------ options menu
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        buildMenu(menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.clear()
        buildMenu(menu)
        return true
    }

    private fun buildMenu(menu: Menu) {
        var m = menu.addSubMenu(0, 0, 0, t.t("menu_file"))
        m.add(0, ID_NEW, 0, t.t("new_game"))
        m.add(0, ID_NEW_RANDOM, 0, t.t("new_random"))
        m.add(0, ID_LOAD, 0, t.t("load_position"))
        m.add(0, ID_SAVE, 0, t.t("save_position"))
        m.add(0, ID_QUICK_SAVE, 0, t.t("quick_save"))
        m.add(0, ID_QUICK_LOAD, 0, t.t("quick_load"))
        m.add(0, ID_QUIT, 0, t.t("quit"))

        m = menu.addSubMenu(0, 0, 1, t.t("menu_view"))
        m.add(0, ID_GHOST, 0, t.t("ghost_stone")).setCheckable(true).isChecked = c.ghost
        m.add(0, ID_ANIM, 0, t.t("drop_animation")).setCheckable(true).isChecked = c.anim
        m.add(0, ID_SHOW_LAST, 0, t.t("show_last_move")).setCheckable(true).isChecked = c.showLast
        m.add(0, ID_STAND, 0, t.t("score_onoff")).setCheckable(true).isChecked = c.stand.enabled
        m.add(0, ID_STAND_RESET, 0, t.t("score_reset"))

        m = menu.addSubMenu(0, 0, 2, t.t("menu_settings"))
        m.add(0, ID_LEVEL, 0, t.t("computer_level") + " …")
        m.add(GROUP_MODE, ID_MODE_HC, 1, t.t("human_computer")).isChecked = c.mode == Controller.MODE_COMPUTER
        m.add(GROUP_MODE, ID_MODE_TWO, 2, t.t("two_player")).isChecked = c.mode == Controller.MODE_TWO
        m.add(GROUP_MODE, ID_MODE_SELF, 3, t.t("selfplay")).isChecked = c.mode == Controller.MODE_SELFPLAY
        m.setGroupCheckable(GROUP_MODE, true, true)
        m.add(0, ID_MATCH, 4, t.t("match"))
        m.add(0, ID_STOP, 5, t.t("stop_autoplay"))
        m.add(0, ID_PREV_SET, 6, t.t("prev_set"))
        m.add(0, ID_NEXT_SET, 7, t.t("next_set"))
        m.add(0, ID_SET, 8, t.t("stone_set") + " …")

        m = menu.addSubMenu(0, 0, 3, t.t("menu_commands"))
        m.add(0, ID_FIRST, 0, t.t("first_move"))
        m.add(0, ID_BACK, 0, t.t("move_back"))
        m.add(0, ID_FORWARD, 0, t.t("move_forward"))
        m.add(0, ID_LAST, 0, t.t("last_move"))
        m.add(0, ID_ENGINE, 0, t.t("engine_move"))
        m.add(0, ID_SCORE_ALL, 0, t.t("score_all")).setCheckable(true).isChecked = c.scoresVisible && !c.autoAnalyze
        m.add(0, ID_AUTO, 0, t.t("permanent_analysis")).setCheckable(true).isChecked = c.autoAnalyze

        m = menu.addSubMenu(0, 0, 4, t.t("menu_help"))
        m.add(0, ID_HELP, 0, t.t("help_contents"))
        m.add(0, ID_INFO, 0, t.t("help_info"))
        m.add(0, ID_LANG, 0, t.t("lang_menu") + " …")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            ID_NEW -> c.newGame()
            ID_NEW_RANDOM -> if (!c.thinking) showRandomDialog()
            ID_LOAD -> openFile()
            ID_SAVE -> saveFile()
            ID_QUICK_SAVE -> c.quickSave()
            ID_QUICK_LOAD -> c.quickLoad()
            ID_QUIT -> {
                c.quit()
                finish()
            }
            ID_GHOST -> c.setGhost(!c.ghost)
            ID_ANIM -> c.setAnim(!c.anim)
            ID_SHOW_LAST -> c.setShowLast(!c.showLast)
            ID_STAND -> c.standToggle()
            ID_STAND_RESET -> c.standReset()
            ID_LEVEL -> showLevelDialog()
            ID_MODE_HC -> c.selectEngine()
            ID_MODE_TWO -> c.toggleTwoPlayer()
            ID_MODE_SELF -> c.selectSelfplay()
            ID_MATCH -> if (c.canOpenMatch()) startActivity(Intent(this, MatchActivity::class.java))
            ID_STOP -> c.stop()
            ID_PREV_SET -> c.cycleSet(-1)
            ID_NEXT_SET -> c.cycleSet(+1)
            ID_SET -> showSetDialog()
            ID_FIRST -> c.gotoFirst()
            ID_BACK -> c.undo()
            ID_FORWARD -> c.redo()
            ID_LAST -> c.gotoLast()
            ID_ENGINE -> c.engineMove()
            ID_SCORE_ALL -> c.toggleScores()
            ID_AUTO -> c.toggleAutoAnalyzeBtn()
            ID_HELP -> showHelp()
            ID_INFO -> showInfo()
            ID_LANG -> showLanguageDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------ keyboard (desktop shortcuts)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val col = when (keyCode) {
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_7 -> keyCode - KeyEvent.KEYCODE_1
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_7 -> keyCode - KeyEvent.KEYCODE_NUMPAD_1
            else -> -1
        }
        if (col in 0 until Game.COLS) {
            c.humanMove(col)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> c.undo()
            KeyEvent.KEYCODE_DPAD_RIGHT -> c.redo()
            KeyEvent.KEYCODE_DPAD_UP -> c.gotoFirst()
            KeyEvent.KEYCODE_DPAD_DOWN -> c.gotoLast()
            KeyEvent.KEYCODE_PAGE_UP -> c.cycleSet(-1)
            KeyEvent.KEYCODE_PAGE_DOWN -> c.cycleSet(+1)
            KeyEvent.KEYCODE_F1 -> showHelp()
            KeyEvent.KEYCODE_F3 -> c.quickSave()
            KeyEvent.KEYCODE_F4 -> c.quickLoad()
            KeyEvent.KEYCODE_F5 -> c.engineMove()
            KeyEvent.KEYCODE_F6 -> c.toggleScores()
            KeyEvent.KEYCODE_F7 -> c.toggleAutoAnalyzeBtn()
            KeyEvent.KEYCODE_F10 -> Unit // neutralised like on the desktop
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // ------------------------------------------------------------ dialogs
    private fun show(key: String, d: AlertDialog) {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = d
        dialogKey = key
        d.setOnDismissListener {
            if (dialog === d) {
                dialog = null
                dialogKey = null
            }
        }
        d.show()
    }

    private fun showLevelDialog() {
        val keys = Levels.STUFEN_ORDER
        val items = keys.map { Levels.levelLabel(it, t) }.toTypedArray()
        show(
            "level",
            AlertDialog.Builder(this)
                .setTitle(t.t("computer_level"))
                .setSingleChoiceItems(items, keys.indexOf(c.level)) { d, which ->
                    d.dismiss()
                    c.switchDepth(keys[which])
                }
                .setNegativeButton(t.t("btn_cancel"), null)
                .create(),
        )
    }

    private fun showSetDialog() {
        val ids = c.sets.order
        val items = ids.map { t.tf("set_menu_item", "no" to it, "name" to t.setName(it)) }.toTypedArray()
        show(
            "set",
            AlertDialog.Builder(this)
                .setTitle(t.t("stone_set"))
                .setSingleChoiceItems(items, ids.indexOf(c.setNo)) { d, which ->
                    d.dismiss()
                    c.switchSet(ids[which])
                }
                .setNegativeButton(t.t("btn_cancel"), null)
                .create(),
        )
    }

    private fun showLanguageDialog() {
        val codes = listOf("") + LANG_ORDER
        val items = (listOf(t.t("a_lang_system")) + LANG_ORDER.map { t.t("lang_$it") }).toTypedArray()
        val cur = codes.indexOf(c.prefs.lang).coerceAtLeast(0)
        show(
            "lang",
            AlertDialog.Builder(this)
                .setTitle(t.t("lang_menu"))
                .setSingleChoiceItems(items, cur) { d, which ->
                    d.dismiss()
                    val code = codes[which]
                    if (code != c.prefs.lang) {
                        c.setLanguage(code)
                        recreate()
                    }
                }
                .setNegativeButton(t.t("btn_cancel"), null)
                .create(),
        )
    }

    private fun showRandomDialog() {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = Math.round(20 * dp)
            setPadding(p, Math.round(12 * dp), p, 0)
        }
        box.addView(TextView(this).apply { text = t.t("new_random_stones") })
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 9
            value = randomN
            wrapSelectorWheel = false
            setOnValueChangedListener { _, _, v -> randomN = v }
        }
        box.addView(picker, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(TextView(this).apply { text = t.t("new_random_result") })
        val wishes = listOf(Engine.WISH_ANY, Engine.WISH_WIN, Engine.WISH_DRAW, Engine.WISH_LOSS)
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, wishes.map { c.wishLabel(it) })
        spinner.setSelection(randomWish)
        box.addView(spinner)
        box.addView(
            TextView(this).apply {
                text = t.t("new_random_hint")
                setTextColor(Color.GRAY)
                setPadding(0, Math.round(8 * dp), 0, 0)
            },
        )
        show(
            "random",
            AlertDialog.Builder(this)
                .setTitle(t.t("new_random_title"))
                .setView(ScrollView(this).apply { addView(box) })
                .setPositiveButton(t.t("btn_ok")) { _, _ ->
                    randomN = picker.value
                    randomWish = spinner.selectedItemPosition
                    c.startRandom(randomN, wishes[randomWish])
                }
                .setNegativeButton(t.t("btn_cancel"), null)
                .create(),
        )
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                randomWish = pos
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) = Unit
        }
    }

    private fun showInfo() {
        val dp = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = t.t("info_text")
            setTextIsSelectable(true)
            val p = Math.round(20 * dp)
            setPadding(p, Math.round(12 * dp), p, 0)
            textSize = 14f
        }
        show(
            "info",
            AlertDialog.Builder(this)
                .setTitle(t.t("info_title"))
                .setView(ScrollView(this).apply { addView(tv) })
                .setNeutralButton(t.t("help_ui_copy")) { _, _ -> copyToClipboard(t.t("info_text")) }
                .setPositiveButton(t.t("help_ui_close"), null)
                .create(),
        )
    }

    private fun showError(title: String, msg: String) {
        show(
            "error",
            AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(t.t("btn_ok"), null).create(),
        )
        dialogKey = null // not restored after recreation
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ConnectFour Studio", text))
        Toast.makeText(this, t.t("a_copied"), Toast.LENGTH_SHORT).show()
    }

    private fun showHelp() = startActivity(Intent(this, HelpActivity::class.java))

    // ------------------------------------------------------------ files (Storage Access Framework)
    private fun openFile() {
        if (c.refuseLoadWhileThinking()) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        launch(i, REQ_OPEN)
    }

    private fun saveFile() {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "connectfour.4gp")
        launch(i, REQ_SAVE)
    }

    private fun launch(i: Intent, req: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, req)
        } catch (_: ActivityNotFoundException) {
            showError(t.t("error_title"), t.t("a_no_file_app"))
        }
    }

    @Deprecated("Framework Activity API (no AndroidX)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        try {
            when (requestCode) {
                REQ_OPEN -> {
                    val bytes = contentResolver.openInputStream(uri)?.use { readLimited(it) }
                        ?: throw java.io.IOException(uri.toString())
                    c.loadBytes(bytes, displayName(uri))
                }
                REQ_SAVE -> {
                    val out = try {
                        contentResolver.openOutputStream(uri, "wt")
                    } catch (_: IllegalArgumentException) {
                        contentResolver.openOutputStream(uri, "w")
                    } ?: throw java.io.IOException(uri.toString())
                    out.use { it.write(c.positionBytes()) }
                    c.savedTo(displayName(uri))
                }
            }
        } catch (e: Exception) {
            showError(t.t("error_title"), e.message ?: e.toString())
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val b = ByteArray(8192)
        while (true) {
            val n = input.read(b)
            if (n < 0) break
            buf.write(b, 0, n)
            if (buf.size() > MAX_FILE) throw java.io.IOException("file too large")
        }
        return buf.toByteArray()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cur.getString(idx)?.let { return it }
                }
            }
        } catch (_: RuntimeException) {
            // fall back to the URI
        }
        return uri.lastPathSegment ?: uri.toString()
    }

    companion object {
        private const val COMPACT_HEIGHT_DP = 480
        private const val ALL = Controller.BOARD or Controller.PANEL or Controller.MENU
        private const val REQ_OPEN = 1
        private const val REQ_SAVE = 2
        private const val MAX_FILE = 1 shl 20
        private const val GROUP_MODE = 1

        private const val ID_NEW = 101
        private const val ID_NEW_RANDOM = 102
        private const val ID_LOAD = 103
        private const val ID_SAVE = 104
        private const val ID_QUICK_SAVE = 105
        private const val ID_QUICK_LOAD = 106
        private const val ID_QUIT = 107
        private const val ID_GHOST = 201
        private const val ID_ANIM = 202
        private const val ID_SHOW_LAST = 203
        private const val ID_STAND = 204
        private const val ID_STAND_RESET = 205
        private const val ID_LEVEL = 301
        private const val ID_MODE_HC = 302
        private const val ID_MODE_TWO = 303
        private const val ID_MODE_SELF = 304
        private const val ID_MATCH = 305
        private const val ID_STOP = 306
        private const val ID_PREV_SET = 307
        private const val ID_NEXT_SET = 308
        private const val ID_SET = 309
        private const val ID_FIRST = 401
        private const val ID_BACK = 402
        private const val ID_FORWARD = 403
        private const val ID_LAST = 404
        private const val ID_ENGINE = 405
        private const val ID_SCORE_ALL = 406
        private const val ID_AUTO = 407
        private const val ID_HELP = 501
        private const val ID_INFO = 502
        private const val ID_LANG = 503
    }
}
