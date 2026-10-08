package io.github.patrickric.connectfourstudio

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import io.github.patrickric.connectfourstudio.core.Levels

/**
 * Computer-computer match (desktop: non-modal window). Settings, live
 * score and copyable result; "Close"/Back keeps the match running.
 */
class MatchActivity : Activity(), Controller.Listener {
    private lateinit var c: Controller
    private lateinit var keys: List<String>
    private lateinit var spinYellow: Spinner
    private lateinit var spinRed: Spinner
    private lateinit var psw1: List<EditText>
    private lateinit var psw2: List<EditText>
    private val t get() = c.tx

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleUtil.wrap(newBase, CfsApp.of(newBase).lang()))
    }

    @SuppressLint("SetTextI18n") // plain numbers (p,s,w, games) as on the desktop
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        c = CfsApp.of(this).controller
        setContentView(R.layout.activity_match)
        title = t.t("match_title")
        c.matchWinOpen = true

        keys = Levels.MATCH_STUFEN
        val names = keys.map {
            when (it) {
                "mensch" -> t.t("level_human")
                "user1" -> Levels.USER_LABEL_1 + t.t("user_own_psw")
                "user2" -> Levels.USER_LABEL_2 + t.t("user_own_psw")
                else -> Levels.stufeLabelFor(it, t)
            }
        }
        spinYellow = findViewById(R.id.spin_yellow)
        spinRed = findViewById(R.id.spin_red)
        for (sp in listOf(spinYellow, spinRed)) {
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        }
        text(R.id.lab_yellow, t.t("label_yellow"))
        text(R.id.lab_red, t.t("label_red"))
        text(R.id.lab_games, t.t("match_games"))
        findViewById<CheckBox>(R.id.check_swap).text = t.t("match_swap")
        text(R.id.lab_tempo, t.t("match_tempo"))
        findViewById<RadioButton>(R.id.rb_normal).text = t.t("tempo_normal")
        findViewById<RadioButton>(R.id.rb_fast).text = t.t("tempo_fast")
        findViewById<RadioButton>(R.id.rb_turbo).text = t.t("tempo_turbo")
        text(R.id.hint, t.t("match_hint"))
        text(R.id.lab_result, t.t("match_result_label"))
        psw1 = listOf(R.id.u1_p, R.id.u1_s, R.id.u1_w).map { findViewById(it) }
        psw2 = listOf(R.id.u2_p, R.id.u2_s, R.id.u2_w).map { findViewById(it) }

        if (savedInstanceState == null) {
            var preG = "leicht"
            var preR = "mittel"
            var n = "20"
            var wechsel = true
            var tempo = R.id.rb_normal
            val m = c.match
            if (m != null && m.running) {
                preG = m.gelb
                preR = m.rot
                n = m.spiele.toString()
                wechsel = m.wechsel
                tempo = if (m.blind) R.id.rb_turbo else if (m.schnell) R.id.rb_fast else R.id.rb_normal
            }
            spinYellow.setSelection(keys.indexOf(preG).coerceAtLeast(0))
            spinRed.setSelection(keys.indexOf(preR).coerceAtLeast(0))
            findViewById<EditText>(R.id.edit_games).setText(n)
            findViewById<CheckBox>(R.id.check_swap).isChecked = wechsel
            findViewById<RadioButton>(tempo).isChecked = true
            for ((fields, key) in listOf(psw1 to "user1", psw2 to "user2")) {
                val (p, s, w) = Levels.userPsw(key)
                fields[0].setText(p.toString())
                fields[1].setText(s.toString())
                fields[2].setText(w.toString())
            }
        }
        val flip = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = umodeFlip()
            override fun onNothingSelected(parent: AdapterView<*>?) = umodeFlip()
        }
        spinYellow.onItemSelectedListener = flip
        spinRed.onItemSelectedListener = flip

        button(R.id.btn_start, t.t("btn_start")) { start() }
        button(R.id.btn_stop, t.t("btn_stop")) { c.stop() }
        button(R.id.btn_copy, t.t("btn_copy")) { copy() }
        button(R.id.btn_close, t.t("btn_close")) { finish() }
        c.addListener(this)
        umodeFlip()
        refresh()
    }

    override fun onDestroy() {
        c.removeListener(this)
        if (isFinishing) c.matchWinOpen = false
        super.onDestroy()
    }

    private fun text(id: Int, s: String) {
        findViewById<TextView>(id).text = s
    }

    private fun button(id: Int, label: String, fn: () -> Unit) {
        val b = findViewById<Button>(id)
        b.text = label
        b.setOnClickListener { fn() }
    }

    private fun sideKeys(): Pair<String, String> =
        keys[spinYellow.selectedItemPosition.coerceAtLeast(0)] to keys[spinRed.selectedItemPosition.coerceAtLeast(0)]

    /** User fields enabled per key: field (1) <-> "user1", (2) <-> "user2". */
    private fun umodeFlip() {
        val (g, r) = sideKeys()
        psw1.forEach { it.isEnabled = "user1" == g || "user1" == r }
        psw2.forEach { it.isEnabled = "user2" == g || "user2" == r }
    }

    private fun start() {
        if (c.match?.running == true) return
        val (g, r) = sideKeys()
        if ("user1" == g || "user1" == r) {
            Levels.setUserPsw("user1", Levels.parsePsw(psw1[0].text.toString(), psw1[1].text.toString(), psw1[2].text.toString()))
        }
        if ("user2" == g || "user2" == r) {
            Levels.setUserPsw("user2", Levels.parsePsw(psw2[0].text.toString(), psw2[1].text.toString(), psw2[2].text.toString()))
        }
        val n = findViewById<EditText>(R.id.edit_games).text.toString().trim().toIntOrNull()?.coerceIn(1, 10000) ?: 20
        val tempo = findViewById<android.widget.RadioGroup>(R.id.tempo_group).checkedRadioButtonId
        c.matchStart(
            g, r, n, findViewById<CheckBox>(R.id.check_swap).isChecked,
            schnell = tempo == R.id.rb_fast, blind = tempo == R.id.rb_turbo,
        )
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ConnectFour Studio", findViewById<TextView>(R.id.result).text))
        Toast.makeText(this, t.t("a_copied"), Toast.LENGTH_SHORT).show()
    }

    /** Live score + final result from the controller's match. */
    private fun refresh() {
        val m = c.match
        if (m == null) {
            text(R.id.live, t.t("match_none"))
            text(R.id.result, t.t("match_no_result"))
            return
        }
        text(R.id.live, m.liveText(t))
        text(R.id.result, m.resultText(t))
    }

    override fun onUpdate(flags: Int) {
        if (flags and (Controller.MATCH or Controller.PANEL) != 0) refresh()
    }
}
