package io.github.patrickric.connectfourstudio

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import io.github.patrickric.connectfourstudio.core.Engine
import io.github.patrickric.connectfourstudio.core.Fmt
import io.github.patrickric.connectfourstudio.core.Game
import io.github.patrickric.connectfourstudio.core.Gp4
import io.github.patrickric.connectfourstudio.core.Levels
import io.github.patrickric.connectfourstudio.core.Match
import io.github.patrickric.connectfourstudio.core.ScoreDisplay
import io.github.patrickric.connectfourstudio.core.Scores
import io.github.patrickric.connectfourstudio.core.SessionScore
import io.github.patrickric.connectfourstudio.core.Stone
import io.github.patrickric.connectfourstudio.core.Stufe
import io.github.patrickric.connectfourstudio.core.Texts
import io.github.patrickric.connectfourstudio.core.engine.Board
import io.github.patrickric.connectfourstudio.core.tf
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/** One field of the evaluation row: top text, lower text, background colour. */
class ScoreCell(val top: String, val sub: String, val bg: Int)

/** Stone in flight: column, distance fallen in rows (float), stone colour. */
class Falling(val col: Int, var rows: Float, val stone: Stone)

/**
 * Game flow of ConnectFour Studio – port of `cfs_qt.main_window.MainWindow`
 * with the same method names (humanMove, engineMove, engineDone,
 * matchFinishGame …). Lives in the [CfsApp] object, so rotation, language
 * switches and background/foreground keep everything running. All methods
 * run on the main thread; workers post their results back via [post].
 */
class Controller(private val app: CfsApp) {
    interface Listener {
        fun onUpdate(flags: Int)
    }

    val prefs = Prefs(app)
    var tx: Texts = app.texts()
        private set
    val sets = StoneSets(app)
    val engine = Engine(logTtSize = ttSizeForDevice())
    val game = Game()
    private val main = Handler(Looper.getMainLooper())
    private val bookLatch = CountDownLatch(1)
    private val listeners = CopyOnWriteArrayList<Listener>()

    // ------------------------------------------------------------ state (names as in the Qt version)
    var mode = MODE_COMPUTER
        private set
    var twoPlayer = false
        private set
    private var selfplay = false
    var humanFirst = true
        private set
    var match: Match? = null
        private set
    private var matchStufe: String? = null
    var thinking = false
        private set

    @Volatile
    private var cancel = false
    private var randJob: RandJob? = null
    var showLast = prefs.showLast
        private set
    var anim = prefs.anim
        private set
    var ghost = prefs.ghost
        private set
    var bigBoard = prefs.bigBoard
        private set
    var hoverCol: Int? = null
        private set
    var falling: Falling? = null
        private set
    var lastScores: Array<ScoreCell>? = null
        private set
    var scoresVisible = false
        private set
    var autoAnalyze = false
        private set

    @Volatile
    private var anaSeq = 0
    private var anaBusy = false
    private var anaPending: Pair<Int, List<Int>>? = null
    private var anaRunningSnap: List<Int>? = null
    private val moveTimes = ArrayList<Double>()
    val stand = SessionScore()
    var standDisplay: ScoreDisplay = ScoreDisplay.EMPTY
        private set
    var level: String = prefs.level.let { if (it in Levels.STUFEN_ORDER) it else "perfekt" }
        private set
    var setNo: Int = prefs.setNo.let { if (sets.contains(it)) it else sets.order.first() }
        private set
    val info = LinkedHashMap<String, String>()
    var status: String = ""
        private set
    var animRunning = false
        private set

    /** Match screen open (desktop: match window exists). */
    var matchWinOpen = false

    /** Error to show as a dialog (title, message); consumed by the activity. */
    var pendingError: Pair<String, String>? = null

    private class RandJob {
        @Volatile
        var stop = false
    }

    init {
        for (k in INFO_KEYS) info[k] = DASH
        for (key in Levels.USER_KEYS) {
            prefs.userPsw(key)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }?.let {
                Levels.setUserPsw(key, Triple(it[0], it[1], it[2]))
            }
        }
        status = tx.t("a_book_loading")
        Thread({
            var err: String? = null
            try {
                engine.book = BookLoader.load(app)
            } catch (e: Exception) {
                err = e.toString()
            } catch (e: OutOfMemoryError) {
                err = e.toString()
            }
            bookLatch.countDown()
            post {
                if (err != null) {
                    pendingError = tx.t("title_book") to tx.tf("err_book_load", "err" to err)
                    setStatus(tx.t("status_ready"))
                    notifyAll(ERROR)
                } else if (status == tx.t("a_book_loading")) {
                    setStatus(tx.t("status_ready"))
                }
            }
        }, "cfs-book").apply { isDaemon = true }.start()
        refresh(false)
    }

    // ------------------------------------------------------------ helpers
    val history: List<Int> get() = game.history
    val board: Board get() = game.board

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyAll(flags: Int) {
        for (l in listeners) l.onUpdate(flags)
    }

    private fun post(ms: Long = 0, fn: () -> Unit) {
        val r = Runnable {
            try {
                fn()
            } catch (e: RuntimeException) {
                android.util.Log.e("cfs", "callback failed", e)
            }
        }
        if (ms <= 0) main.post(r) else main.postDelayed(r, ms)
    }

    private fun worker(name: String, body: () -> Unit) {
        Thread({
            bookLatch.await()
            body()
        }, name).apply { isDaemon = true }.start()
    }

    fun setStatus(text: String) {
        status = text
        notifyAll(PANEL)
    }

    private fun setInfo(key: String, text: String) {
        if (info.containsKey(key)) info[key] = text
        notifyAll(PANEL)
    }

    private fun savePrefs() {
        prefs.ghost = ghost
        prefs.anim = anim
        prefs.showLast = showLast
        prefs.setNo = setNo
        prefs.level = level
    }

    /** New language: texts and relabelling (desktop: `_relabel_all`). */
    fun setLanguage(code: String) {
        val old = tx
        val wasReady = status in LANG_ORDER.map { app.texts(it).t("status_ready") }
        prefs.lang = code
        tx = app.texts()
        info["info_level"] = displayStufeLabel()
        if (stand.enabled) standDisplay = stand.display(tx)
        if (wasReady) status = tx.t("status_ready")
        // Beyond the desktop: also translate the values derived from the state.
        val n = history.size
        if (info["info_depth"] == old.t("depth_full")) info["info_depth"] = tx.t("depth_full")
        if (info["info_depth"]?.startsWith(old.t("depth_book")) == true) info["info_depth"] = engine.pliesText(n, tx)
        if (info["info_book"] != DASH) info["info_book"] = engine.bookText(n, tx)
        if (board.isGameOver() && !animRunning && match == null) finishInfo()
        notifyAll(PANEL or MENU or BOARD)
    }

    // ------------------------------------------------------------ board display
    private fun draw() = notifyAll(BOARD)

    fun setHover(col: Int?) {
        if (col != hoverCol) {
            hoverCol = col
            if (!animRunning) draw()
        }
    }

    fun onCanvasClick(col: Int) {
        if (thinking || animRunning) return
        humanMove(col)
    }

    private fun updateTurn() = notifyAll(PANEL)

    fun turnStone(): Stone = if (game.currentPlayerNo() == 1) Stone.YELLOW else Stone.RED

    private fun setScores(m: Array<ScoreCell>) {
        lastScores = m
        draw()
    }

    private fun clearScores(silent: Boolean = false) {
        setScores(
            Array(Game.COLS) { c ->
                if (board.isLegalMove(c)) ScoreCell((c + 1).toString(), "", C_BASE) else ScoreCell("X", "", C_FULL)
            },
        )
        if (silent) return
        for (key in listOf("info_value", "info_depth", "info_nodes", "info_time", "info_nodes_per_sec", "info_book")) {
            info[key] = DASH
        }
        setInfo("info_level", stufeLabel())
    }

    private var animCallback: Choreographer.FrameCallback? = null

    /** Stone falls into [col] (smooth, gravity-like; desktop: 16 ms per row). */
    private fun dropAnimation(col: Int, stone: Stone, done: () -> Unit) {
        animRunning = true
        val targetTop = Game.ROWS - board.columnHeight(col)
        val totalMs = if (targetTop <= 0) 0.0 else Math.sqrt(2.0 * targetTop / GRAVITY) * 1000.0
        val start = SystemClock.uptimeMillis()
        val f = Falling(col, 0f, stone)
        falling = f
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (animCallback !== this) return
                val t = (SystemClock.uptimeMillis() - start).toDouble()
                if (t >= totalMs) {
                    animCallback = null
                    animRunning = false
                    falling = null
                    refresh()
                    done()
                    return
                }
                val s = t / 1000.0
                f.rows = (0.5 * GRAVITY * s * s).toFloat().coerceAtMost(targetTop.toFloat())
                draw()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        animCallback = cb
        draw()
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun cancelAnim() {
        animCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        animCallback = null
        animRunning = false
        falling = null
    }

    // ------------------------------------------------------------ levels / identities
    private fun stufeKey(): String = if (twoPlayer) "mensch" else Levels.normalizeKey(level)

    private fun stufeLabel(): String = Levels.stufeLabelFor(level, tx)

    fun matchMode(): Boolean = match?.running == true

    private fun matchSideStufe(): String = match!!.sideStufe(history.size)

    fun matchHumanTurn(): Boolean = matchMode() && matchSideStufe() == "mensch"

    private fun validMatchStufe(key: String?): Boolean = key == "mensch" || (key != null && key in Levels.COMPUTER_KEYS)

    private fun displayStufeLabel(): String {
        if (matchMode()) return Levels.stufeLabelFor(matchSideStufe(), tx)
        if (validMatchStufe(matchStufe)) return Levels.stufeLabelFor(matchStufe, tx)
        return stufeLabel()
    }

    private fun moveStufe(): Stufe {
        if (matchMode()) return Levels.stufenWerte(matchSideStufe())
        if (validMatchStufe(matchStufe)) return Levels.stufenWerte(matchStufe!!)
        return Levels.stufenWerte(stufeKey())
    }

    fun selfplayMode(): Boolean = selfplay || mode == MODE_SELFPLAY

    /** Who is to move? Match side, human, or level with (p,s,w). */
    fun currentPlayerLabel(): String {
        if (matchMode()) return Levels.stufeLabelFor(matchSideStufe(), tx, mitPsw = true)
        if (twoPlayer) return tx.t("level_human")
        if (selfplayMode()) return Levels.stufeLabelFor(stufeKey(), tx, mitPsw = true)
        val n = history.size
        if ((n % 2 == 0) == humanFirst) return tx.t("level_human")
        return Levels.stufeLabelFor(stufeKey(), tx, mitPsw = true)
    }

    // ------------------------------------------------------------ moves
    fun humanMove(col: Int) {
        if (animRunning) return
        if (thinking) {
            setStatus(tx.t("status_wait_thinking"))
            return
        }
        if (matchMode()) {
            if (!matchHumanTurn()) {
                setStatus(tx.t("status_match_running"))
                return
            }
        } else if (selfplayMode()) {
            setStatus(tx.t("status_selfplay_running"))
            return
        }
        if (board.isGameOver()) {
            setStatus(tx.t("status_game_over_new"))
            return
        }
        if (!board.isLegalMove(col)) {
            setStatus(tx.tf("status_column_full", "col" to col + 1))
            return
        }
        val stone = game.stoneToMove()
        game.play(col)
        if (anim) {
            dropAnimation(col, stone) { afterHuman() }
        } else {
            refresh()
            afterHuman()
        }
    }

    private fun afterHuman() {
        if (board.isGameOver()) {
            finishInfo()
            if (matchMode()) {
                matchFinishGame()
                return
            }
            if (!twoPlayer && !selfplayMode()) {
                standBookNormal(Match.humanWonNormal(board.winner(), history.size, humanFirst))
            }
            return
        }
        if (selfplayMode()) {
            engineMove()
            return
        }
        if (matchMode()) {
            val side = matchSideStufe()
            if (side != "mensch") engineMove(side)
            return
        }
        if (!twoPlayer && mode == MODE_COMPUTER) engineMove()
    }

    /**
     * Starts a computer move. [stufe] (optional) only for this move (match).
     * Normal mode on an empty board: the computer opens (human plays Red).
     */
    fun engineMove(stufe: String? = null) {
        if (thinking || board.isGameOver() || animRunning) return
        if (!matchMode() && !twoPlayer && !selfplayMode() && history.isEmpty()) humanFirst = false
        thinking = true
        cancel = false
        matchStufe = if (stufe != null && validMatchStufe(stufe)) stufe else null
        val matchMode = matchMode()
        val blind = matchMode && match!!.blind
        if (!matchMode) setStatus(tx.t("status_thinking"))
        val stufeTxt = if (validMatchStufe(matchStufe)) Levels.stufeLabelFor(matchStufe, tx) else stufeLabel()
        val b = game.copyBoard()
        val snap = history.toList()
        val st = moveStufe()
        val snapSeq = anaSeq
        notifyAll(MENU)
        worker("cfs-engine") { engineThread(b, snap, st, blind, snapSeq, stufeTxt, matchMode) }
    }

    private fun engineThread(
        b: Board,
        snap: List<Int>,
        stufe: Stufe,
        blind: Boolean,
        snapSeq: Int,
        stufeTxt: String,
        matchMode: Boolean,
    ) {
        val t0 = System.nanoTime()
        val t = tx
        val prog = { depth: Int, scores: Scores, nodes: Long, dt: Double ->
            if (snapSeq == anaSeq && !scores.isEmpty() && !(matchMode && blind)) {
                if (matchMode) {
                    post { setInfo("info_level", stufeTxt) }
                } else {
                    val label = Engine.depthLabel(depth, t)
                    val kn = Fmt.thousands(nodes, t.lang)
                    val ms = maxOf(1L, Math.round(dt * 1000))
                    val kns = Engine.knsText(nodes, dt, t.lang)
                    post { engineProgUi(label, kn, ms, kns, stufeTxt) }
                }
            }
        }
        val m = try {
            engine.pickMove(b, stufe, prog, abort = { cancel }, keepTt = blind)
        } catch (e: Exception) {
            android.util.Log.w("cfs", "engine move failed", e)
            val err = e.message ?: e.toString()
            post { engineFailed(err) }
            return
        }
        val dt = (System.nanoTime() - t0) / 1e9
        post { engineDone(m.col, dt, m.nodes, m.score, snap) }
    }

    private fun engineProgUi(label: String, kn: String, ms: Long, kns: String, stufeTxt: String) {
        info["info_depth"] = "$label..."
        info["info_nodes"] = kn
        info["info_time"] = "$ms ms"
        info["info_nodes_per_sec"] = kns
        info["info_level"] = stufeTxt
        if (!matchMode()) status = tx.t("status_thinking")
        notifyAll(PANEL)
    }

    private fun engineFailed(err: String) {
        thinking = false
        notifyAll(MENU)
        if (matchMode()) {
            matchCancelled()
            return
        }
        setStatus(tx.tf("status_computer_error", "err" to err))
    }

    private fun engineDone(col: Int, dt: Double, nodes: Long, score: Int?, snap: List<Int>?) {
        thinking = false
        notifyAll(MENU)
        if (cancel) {
            if (matchMode()) matchCancelled() else setStatus(tx.t("status_aborted"))
            return
        }
        if (snap != null && history != snap) {
            setStatus(tx.t("status_discarded"))
            refresh()
            return
        }
        val stone = game.stoneToMove()
        val mlPlayed = score?.let { Engine.movesLeft(board, it) }
        game.play(col)
        moveTimes.add(dt)
        val m = match
        if (matchMode() && m!!.blind) {
            afterEngine(col, dt, nodes, score)
            return
        }
        val fast = matchMode() && m!!.delayMs() == 0
        if (anim && !fast) {
            dropAnimation(col, stone) { afterEngineAnim(col, dt, nodes, score, mlPlayed) }
        } else {
            refreshAfterEngine(nodes, score, mlPlayed)
            afterEngine(col, dt, nodes, score)
        }
    }

    private fun afterEngineAnim(col: Int, dt: Double, nodes: Long, score: Int?, mlPlayed: Int?) {
        val dt0 = moveTimes.lastOrNull() ?: dt
        showEngineInfo(nodes, dt0, score, mlPlayed)
        afterEngine(col, dt, nodes, score)
    }

    private fun afterEngine(col: Int, dt: Double, @Suppress("UNUSED_PARAMETER") nodes: Long, @Suppress("UNUSED_PARAMETER") score: Int?) {
        if (matchMode()) {
            matchAfterMove()
            return
        }
        setStatus(tx.tf("status_computer_move", "col" to col + 1, "sec" to Fmt.fixed(dt, 2)))
        if (board.isGameOver()) {
            finishInfo()
            if (!twoPlayer && !selfplayMode()) {
                standBookNormal(Match.humanWonNormal(board.winner(), history.size, humanFirst))
            }
            if (selfplayMode()) selfplayStop()
            return
        }
        if (selfplayMode()) post(350) { selfplayNext() }
    }

    private fun refreshAfterEngine(nodes: Long, score: Int?, mlPlayed: Int?) {
        val dt = moveTimes.lastOrNull() ?: 0.0
        showEngineInfo(nodes, dt, score, mlPlayed)
        draw()
        updateTurn()
        info["info_move"] = (history.size + 1).toString()
        setInfo("info_level", displayStufeLabel())
        if (board.isGameOver()) {
            finishInfo()
            return
        }
        scoresVisible = false
        clearScores(silent = true)
        // As on the animation path (refresh): permanent analysis also without animation.
        scheduleAutoAnalyze()
    }

    private fun showEngineInfo(nodes: Long, dt: Double, score: Int?, mlPlayed: Int?) {
        val n = history.size
        if (score == null) {
            info["info_value"] = DASH
        } else {
            val ml = mlPlayed ?: Engine.movesLeft(board, score)
            info["info_value"] = Engine.valueText(score, ml, n % 2 == 1, tx)
        }
        info["info_depth"] = engine.pliesText(n, tx)
        info["info_nodes"] = Fmt.thousands(nodes, tx.lang)
        info["info_time"] = "${maxOf(1L, Math.round(dt * 1000))} ms"
        info["info_nodes_per_sec"] = Engine.knsText(nodes, dt, tx.lang)
        info["info_book"] = engine.bookText(n, tx)
        notifyAll(PANEL)
    }

    /**
     * Stop: engine move, self-play, match, analysis and random search. A
     * thread still computing is aborted, its result discarded.
     */
    fun stop() {
        cancel = true
        selfplayStop()
        matchStop(cancelled = true)
        anaSeq++
        anaPending = null
        anaBusy = false
        anaRunningSnap = null
        randJob?.stop = true
        setStatus(tx.t("status_stopped"))
        notifyAll(MENU)
    }

    private fun navBlocked(): Boolean {
        if (animRunning) return true
        if (thinking) {
            setStatus(tx.t("status_wait_thinking"))
            return true
        }
        if (matchMode()) {
            setStatus(tx.t("status_match_running"))
            return true
        }
        if (selfplayMode()) {
            setStatus(tx.t("status_selfplay_running_stop"))
            return true
        }
        return false
    }

    fun undo() {
        if (!navBlocked() && game.undo()) refresh()
    }

    fun redo() {
        if (!navBlocked() && game.redo()) refresh()
    }

    fun gotoFirst() {
        if (!navBlocked()) {
            game.gotoFirst()
            refresh()
        }
    }

    fun gotoLast() {
        if (!navBlocked()) {
            game.gotoLast()
            refresh()
        }
    }

    fun newGame() {
        stop()
        selfplayStop()
        humanFirst = true
        if (match != null) {
            match = null
            matchStufe = null
            notifyAll(MATCH)
        }
        cancelAnim()
        game.reset()
        moveTimes.clear()
        // Reset the TT only if no worker holds the engine right now.
        engine.tryReset()
        setStatus(tx.t("status_new_game"))
        refresh()
    }

    // ------------------------------------------------------------ random position
    fun startRandom(n: Int, wunsch: String) {
        val job = RandJob()
        randJob = job
        setStatus(tx.tf("status_search_random", "n" to n, "wish" to wishLabel(wunsch)))
        worker("cfs-random") {
            val r = engine.randomPosition(n, wunsch, stop = { job.stop })
            post {
                when (r) {
                    is Engine.RandomResult.Stopped -> setStatus(tx.t("status_aborted"))
                    is Engine.RandomResult.NotFound -> setStatus(tx.t("status_no_position_found"))
                    is Engine.RandomResult.Found -> randomDone(r.moves, wunsch, job)
                }
            }
        }
    }

    fun wishLabel(wunsch: String): String = when (wunsch) {
        Engine.WISH_ANY -> tx.t("new_random_any")
        Engine.WISH_WIN -> tx.t("new_random_win")
        Engine.WISH_DRAW -> tx.t("new_random_draw")
        Engine.WISH_LOSS -> tx.t("new_random_loss")
        else -> wunsch
    }

    private fun randomDone(seq: List<Int>, wunsch: String, job: RandJob) {
        if (job.stop) return
        newGame()
        game.setMoves(seq)
        humanFirst = seq.size % 2 == 0
        randJob = null
        setStatus(tx.tf("status_random_done", "n" to seq.size, "wish" to wishLabel(wunsch)))
        refresh(allScores = true)
    }

    // ------------------------------------------------------------ files (.4gp)
    private fun loadMoves(moves: List<Int>) {
        newGame()
        game.setMoves(moves)
        humanFirst = history.size % 2 == 0
        refresh(allScores = scoresVisible)
    }

    /** Desktop: load refused while the computer thinks (stop first). */
    fun refuseLoadWhileThinking(): Boolean {
        if (!thinking) return false
        stop()
        setStatus(tx.t("status_load_stopped_thinking"))
        return true
    }

    fun loadBytes(bytes: ByteArray, name: String) {
        loadMoves(Gp4.parse(bytes))
        setStatus(tx.tf("status_loaded", "path" to name))
    }

    fun positionBytes(): ByteArray = Gp4.bytes(history)

    fun savedTo(name: String) = setStatus(tx.tf("status_saved", "path" to name))

    private val quickFile: File get() = File(app.filesDir, "quicksave.4gp")

    fun quickSave() {
        try {
            quickFile.writeBytes(Gp4.bytes(history))
            setStatus(tx.tf("status_quick_saved", "n" to history.size))
        } catch (e: java.io.IOException) {
            showError(tx.t("title_quick_save"), e.toString())
        }
    }

    fun quickLoad() {
        if (refuseLoadWhileThinking()) return
        val f = quickFile
        if (!f.isFile) {
            setStatus(tx.t("status_no_quicksave"))
            return
        }
        try {
            loadMoves(Gp4.parse(f.readBytes()))
            setStatus(tx.tf("status_quick_loaded", "name" to f.name))
        } catch (e: java.io.IOException) {
            showError(tx.t("title_quick_load"), e.toString())
        }
    }

    fun showError(title: String, msg: String) {
        pendingError = title to msg
        notifyAll(ERROR)
    }

    // ------------------------------------------------------------ evaluation / analysis
    /** F6: first call evaluates, second call goes back to 1-7. */
    fun toggleScores() {
        if (scoresVisible) {
            scoresVisible = false
            anaSeq++
            anaPending = null
            clearScores()
            setStatus(tx.t("status_scores_off"))
        } else {
            refresh(allScores = true)
        }
    }

    fun refresh(allScores: Boolean = false) {
        draw()
        updateTurn()
        info["info_move"] = (history.size + 1).toString()
        setInfo("info_level", displayStufeLabel())
        if (board.isGameOver()) {
            finishInfo()
            return
        }
        if (allScores && !thinking) {
            evaluateAll()
        } else {
            scoresVisible = false
            clearScores()
            setInfo("info_level", displayStufeLabel())
            scheduleAutoAnalyze()
        }
    }

    /**
     * One-off evaluation of all moves (F6). The desktop computes this
     * synchronously in the GUI thread; here it runs in a worker with live
     * depth display, the UI stays responsive (see DECISIONS.md).
     */
    private fun evaluateAll() {
        anaSeq++
        val seq = anaSeq
        val snap = history.toList()
        val b = game.copyBoard()
        scoresVisible = true
        info["info_depth"] = "…"
        notifyAll(PANEL or MENU)
        worker("cfs-eval") {
            val t0 = System.nanoTime()
            val (scores, nodes) = try {
                engine.iterativeScores(b, onProgress = { depth, s, n, dt ->
                    post { showScoresLive(s, n, dt, depth, seq, snap, requireAuto = false) }
                }, abort = { seq != anaSeq })
            } catch (e: Exception) {
                Scores.EMPTY to 0L
            }
            val dt = (System.nanoTime() - t0) / 1e9
            post {
                if (seq != anaSeq || history != snap) return@post
                if (!scores.isEmpty()) showScores(scores, nodes, dt) else setStatus(tx.t("status_eval_aborted"))
            }
        }
    }

    fun finishInfo() {
        val w = board.winner()
        if (w == null && !board.isGameOver()) return
        val msg = if (w == null || w == 0) {
            tx.t("msg_draw")
        } else {
            tx.tf("msg_wins", "who" to tx.t(if (w == 2) "color_red" else "color_yellow"))
        }
        setStatus(tx.tf("status_game_end", "msg" to msg))
        setInfo("info_value", msg)
    }

    /** Background analysis; the newest position wins. */
    private fun scheduleAutoAnalyze() {
        if (!autoAnalyze || board.isGameOver()) return
        val snap = history.toList()
        if (anaBusy) {
            if (snap == anaRunningSnap) return
            anaSeq++
            anaPending = anaSeq to snap
            return
        }
        anaSeq++
        startAnalysis(anaSeq, snap)
    }

    private fun startAnalysis(seq: Int, snap: List<Int>) {
        anaRunningSnap = snap.toList()
        anaBusy = true
        worker("cfs-analysis") { autoAnalyzeThread(seq, snap) }
    }

    private fun autoAnalyzeThread(seq: Int, snap: List<Int>) {
        val t0 = System.nanoTime()
        var last: Triple<Scores, Long, Double>? = null
        var (scores, nodes) = try {
            engine.iterativeScores(Game.boardFromMoves(snap), onProgress = { depth, s, n, dt ->
                if (seq == anaSeq) {
                    last = Triple(s, n, dt)
                    post { showScoresLive(s, n, dt, depth, seq, snap, requireAuto = true) }
                }
            }, abort = { seq != anaSeq })
        } catch (e: Exception) {
            val err = e.message ?: e.toString()
            post { autoAnalyzeFail(seq, err) }
            return
        }
        val l = last
        if (scores.isEmpty() && l != null) {
            scores = l.first
            nodes = l.second
        }
        val dt = (System.nanoTime() - t0) / 1e9
        post { autoAnalyzeDone(seq, snap, scores, nodes, dt) }
    }

    private fun autoAnalyzeFail(seq: Int, err: String) {
        anaBusy = false
        anaRunningSnap = null
        if (seq == anaSeq) setStatus(tx.tf("status_analysis_error", "err" to err))
        drainPending()
    }

    private fun autoAnalyzeDone(seq: Int, snap: List<Int>, scores: Scores, nodes: Long, dt: Double) {
        anaBusy = false
        anaRunningSnap = null
        if (seq != anaSeq || history != snap || !autoAnalyze || scores.isEmpty()) {
            drainPending()
            return
        }
        showScores(scores, nodes, dt)
        val best = scores.best()
        val sc = scores[best]!!
        setStatus(
            tx.tf(
                "status_analysis_done", "col" to best + 1,
                "score" to (if (sc >= 0) "+$sc" else sc.toString()), "sec" to Fmt.fixed(dt, 2),
            ),
        )
        drainPending()
    }

    private fun drainPending() {
        val p = anaPending ?: return
        anaPending = null
        val (seq, snap) = p
        if (seq != anaSeq || history != snap || board.isGameOver()) return
        cancel = false
        startAnalysis(seq, snap)
    }

    private fun showScoresLive(
        scores: Scores,
        nodes: Long,
        dt: Double,
        depth: Int,
        seq: Int,
        snap: List<Int>,
        requireAuto: Boolean,
    ) {
        if (seq != anaSeq || history != snap) return
        if ((requireAuto && !autoAnalyze) || board.isGameOver()) return
        showScores(scores, nodes, dt)
        val label = Engine.depthLabel(depth, tx)
        setInfo("info_depth", "$label...")
    }

    /** Evaluation in colour in the evaluation row + info box. */
    private fun showScores(scores: Scores, nodes: Long, dt: Double) {
        if (scores.isEmpty()) return
        updateTurn()
        scoresVisible = true
        setScores(
            Array(Game.COLS) { c ->
                val s = scores[c]
                if (s != null) {
                    val ml = Engine.movesLeft(board, s)
                    when {
                        s > 0 -> ScoreCell("+", ml.toString(), C_WIN)
                        s == 0 -> ScoreCell("=", ml.toString(), C_DRAW)
                        else -> ScoreCell("-", ml.toString(), C_LOSS)
                    }
                } else {
                    ScoreCell("X", "", C_FULL)
                }
            },
        )
        val bs = scores[scores.best()]!!
        val moverYellow = history.size % 2 == 0
        val n = history.size
        info["info_value"] = Engine.valueText(bs, Engine.movesLeft(board, bs), moverYellow, tx)
        info["info_depth"] = engine.pliesText(n, tx)
        info["info_nodes"] = Fmt.thousands(nodes, tx.lang)
        info["info_time"] = "${maxOf(1L, Math.round(dt * 1000))} ms"
        info["info_nodes_per_sec"] = Engine.knsText(nodes, dt, tx.lang)
        info["info_book"] = engine.bookText(n, tx)
        notifyAll(PANEL or MENU)
    }

    private fun setAutoAnalyze(on: Boolean, silent: Boolean = false) {
        autoAnalyze = on
        notifyAll(MENU)
        if (!on) {
            anaSeq++
            anaPending = null
            scoresVisible = false
            clearScores()
            if (!silent) setStatus(tx.t("status_autoanalysis_off"))
            return
        }
        if (!silent) setStatus(tx.t("status_autoanalysis_on"))
        scheduleAutoAnalyze()
    }

    fun toggleAutoAnalyzeBtn() = setAutoAnalyze(!autoAnalyze)

    // ------------------------------------------------------------ settings: level, mode
    private fun applyStufe() {
        if (level !in Levels.STUFEN_ORDER) level = "perfekt"
        setInfo("info_level", stufeLabel())
        savePrefs()
        notifyAll(MENU)
    }

    /**
     * Chooses the computer level; in self-play it stays Perfekt. A level
     * change resets the score of the new level to 0-0.
     */
    fun switchDepth(key: String?) {
        if (key != null) level = key
        if (selfplayMode()) level = "perfekt"
        applyStufe()
        val pair = stand.resetTo(stufeKey())
        if (pair != null) standShow(pair)
        if (selfplayMode()) {
            setStatus(tx.t("status_selfplay_fixed"))
        } else {
            setStatus(tx.tf("status_hc_level", "label" to stufeLabel()))
        }
        refresh(allScores = scoresVisible)
    }

    fun selectEngine() {
        selfplayStop()
        twoPlayer = false
        mode = MODE_COMPUTER
        notifyAll(MENU)
        setStatus(tx.tf("status_hc_mode", "label" to stufeLabel()))
        if (!board.isGameOver() && history.size % 2 == 1) {
            engineMove()
        } else {
            refresh(allScores = scoresVisible)
        }
    }

    fun toggleTwoPlayer() {
        selfplayStop()
        twoPlayer = true
        mode = MODE_TWO
        notifyAll(MENU or PANEL)
        setStatus(tx.t("status_two_player"))
        scheduleAutoAnalyze()
    }

    fun selectSelfplay() {
        twoPlayer = false
        selfplay = true
        mode = MODE_SELFPLAY
        level = "perfekt"
        applyStufe()
        setStatus(tx.t("status_selfplay_playing"))
        refresh(allScores = scoresVisible)
        if (!board.isGameOver() && !thinking) engineMove()
    }

    private fun selfplayStop() {
        selfplay = false
        if (mode == MODE_SELFPLAY) {
            mode = MODE_COMPUTER
            notifyAll(MENU)
        }
    }

    private fun selfplayNext() {
        if (!selfplayMode()) return
        if (board.isGameOver() || thinking) {
            if (board.isGameOver()) selfplayStop()
            return
        }
        if (level != "perfekt") {
            level = "perfekt"
            applyStufe()
        }
        engineMove()
    }

    // ------------------------------------------------------------ match
    /** Desktop `match_dialog`: false = refused (stop first). */
    fun canOpenMatch(): Boolean {
        // A running match can always be watched (desktop: the window stays open;
        // on Android the screen is left with "Back" and must be reachable again).
        if (matchMode()) return true
        if (selfplayMode() || thinking) {
            setStatus(tx.t("status_stop_before_match"))
            return false
        }
        return true
    }

    private var matchTimerFn: Runnable? = null

    private fun matchTimerStart(ms: Long, fn: () -> Unit) {
        matchTimerCancel()
        val r = Runnable {
            matchTimerFn = null
            fn()
        }
        matchTimerFn = r
        main.postDelayed(r, maxOf(0L, ms))
    }

    private fun matchTimerCancel() {
        matchTimerFn?.let { main.removeCallbacks(it) }
        matchTimerFn = null
    }

    fun matchStart(gelb: String, rot: String, spiele: Int, wechsel: Boolean, schnell: Boolean = false, blind: Boolean = false) {
        if (match?.running == true) return
        for (key in Levels.USER_KEYS) {
            val (p, s, w) = Levels.userPsw(key)
            prefs.setUserPsw(key, "$p,$s,$w")
        }
        selfplayStop()
        twoPlayer = false
        mode = MODE_COMPUTER
        cancel = false
        anaSeq++
        anaPending = null
        match = Match(gelb, rot, spiele, wechsel, schnell = schnell, blind = blind)
        matchStufe = null
        newMatchGame()
        notifyAll(MATCH or MENU)
    }

    private fun newMatchGame() {
        cancelAnim()
        game.reset()
        moveTimes.clear()
        refresh()
        setStatus(match!!.statusLine(tx))
        notifyAll(MATCH)
        matchTriggerNext()
    }

    private fun matchTriggerNext() {
        if (!matchMode() || board.isGameOver() || thinking) return
        matchTimerStart(match!!.delayMs().toLong()) { matchDoMove() }
    }

    private fun matchDoMove() {
        if (!matchMode() || board.isGameOver() || thinking) return
        val side = matchSideStufe()
        if (side == "mensch") return // human side: wait for a tap/key
        engineMove(side)
    }

    private fun matchAfterMove() {
        if (board.isGameOver()) {
            finishInfo()
            matchFinishGame()
            return
        }
        if (matchMode()) {
            val m = match!!
            if (m.delayMs() == 0) {
                if (thinking || matchSideStufe() == "mensch") return
                engineMove(matchSideStufe())
                return
            }
            post(m.delayMs().toLong()) { matchTriggerNext() }
        }
    }

    private fun matchFinishGame() {
        val m = match ?: return
        val sieger = m.recordResult(board.winner())
        val pair = stand.addMatchResult(m.gelb, m.rot, sieger)
        if (pair != null) standShow(pair)
        if (!m.advance()) {
            matchStufe = null
            finishInfo()
            // After finishInfo, so that the match result stays visible.
            setStatus(tx.tf("match_ended_status", "res" to m.resultText(tx)))
            notifyAll(MATCH or MENU or (if (matchWinOpen) MATCH_FRONT else 0))
            return
        }
        notifyAll(MATCH)
        if (m.pauseBetweenGames()) {
            setStatus(status + tx.t("match_next_in"))
            matchTimerStart(3000) { matchNextGame() }
            return
        }
        newMatchGame()
    }

    private fun matchNextGame() {
        if (matchMode()) newMatchGame()
    }

    private fun matchCancelled() {
        val m = match
        m?.stop(cancelled = true)
        matchStufe = null
        matchTimerCancel()
        setStatus(tx.tf("match_stopped_status", "res" to (m?.resultText(tx) ?: "")))
        notifyAll(MATCH or MENU)
    }

    private fun matchStop(cancelled: Boolean = false) {
        val m = match ?: return
        if (m.fertig) return
        m.stop(cancelled)
        matchStufe = null
        matchTimerCancel()
        if (cancelled) setStatus(tx.tf("match_stopped_status", "res" to m.resultText(tx)))
        notifyAll(MATCH or MENU)
    }

    // ------------------------------------------------------------ session score
    private fun standShow(pair: String? = null) {
        standDisplay = stand.display(tx, pair)
        notifyAll(PANEL)
    }

    private fun standBookNormal(siegerMensch: Boolean?) {
        val pair = stand.bookNormal(stufeKey(), siegerMensch)
        if (pair != null) standShow(pair)
    }

    fun standToggle() {
        stand.toggle(stufeKey())
        standShow()
        notifyAll(MENU)
    }

    fun standReset() {
        val pair = stand.reset(stufeKey())
        standShow(pair)
    }

    // ------------------------------------------------------------ sets / view
    fun switchSet(no: Int) {
        if (!sets.contains(no)) return
        setNo = no
        draw()
        updateTurn()
        setStatus(tx.tf("status_set_changed", "no" to no, "name" to tx.setName(no)))
        savePrefs()
        notifyAll(MENU)
    }

    fun cycleSet(direction: Int) {
        val ids = sets.order
        val i = ids.indexOf(setNo).coerceAtLeast(0)
        switchSet(ids[Math.floorMod(i + direction, ids.size)])
    }

    fun setShowLast(v: Boolean) {
        showLast = v
        draw()
        savePrefs()
        notifyAll(MENU)
    }

    fun setGhost(v: Boolean) {
        ghost = v
        draw()
        savePrefs()
        notifyAll(MENU)
    }

    /** View > Large board (Android only): board without side margin. */
    fun setBigBoard(v: Boolean) {
        bigBoard = v
        prefs.bigBoard = v
        notifyAll(BOARD or MENU)
    }

    fun setAnim(v: Boolean) {
        anim = v
        savePrefs()
        notifyAll(MENU)
    }

    // ------------------------------------------------------------ process death
    fun saveState(out: Bundle) {
        out.putIntArray("history", history.toIntArray())
        out.putIntArray("future", game.future.toIntArray())
        out.putBoolean("human_first", humanFirst)
        out.putBoolean("two_player", twoPlayer)
        out.putBoolean("auto_analyze", autoAnalyze)
        out.putBoolean("scores_visible", scoresVisible)
        out.putBoolean("stand_on", stand.enabled)
        out.putStringArray("stand_keys", stand.pairs.keys.toTypedArray())
        out.putIntArray("stand_vals", stand.pairs.values.flatMap { it.toList() }.toIntArray())
    }

    /** Restores a game after the process was killed in the background. */
    fun restoreState(b: Bundle) {
        game.restore(b.getIntArray("history")?.toList().orEmpty(), b.getIntArray("future")?.toList().orEmpty())
        humanFirst = b.getBoolean("human_first", true)
        twoPlayer = b.getBoolean("two_player", false)
        mode = if (twoPlayer) MODE_TWO else MODE_COMPUTER
        val keys = b.getStringArray("stand_keys").orEmpty()
        val vals = b.getIntArray("stand_vals") ?: IntArray(0)
        if (vals.size == keys.size * 3) {
            keys.forEachIndexed { i, k -> stand.pairs[k] = intArrayOf(vals[3 * i], vals[3 * i + 1], vals[3 * i + 2]) }
        }
        stand.enabled = b.getBoolean("stand_on", false)
        if (stand.enabled) standDisplay = stand.display(tx)
        autoAnalyze = b.getBoolean("auto_analyze", false)
        refresh(allScores = b.getBoolean("scores_visible", false))
        notifyAll(MENU)
    }

    /** "Quit": stop everything (the activity finishes itself). */
    fun quit() {
        cancel = true
        anaSeq++
        anaPending = null
        randJob?.stop = true
        cancelAnim()
        matchTimerCancel()
        selfplayStop()
        matchStop(cancelled = true)
        savePrefs()
    }

    val isBookReady: Boolean get() = bookLatch.count == 0L

    private fun ttSizeForDevice(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return when {
            max >= 384L shl 20 -> 22 // 48 MB, same as the C++ engine
            max >= 192L shl 20 -> 21
            else -> 20
        }
    }

    companion object {
        const val MODE_COMPUTER = "computer"
        const val MODE_TWO = "two"
        const val MODE_SELFPLAY = "selfplay"

        const val BOARD = 1
        const val PANEL = 2
        const val MENU = 4
        const val MATCH = 8
        const val MATCH_FRONT = 16
        const val ERROR = 32

        val INFO_KEYS = listOf(
            "info_move", "info_level", "info_depth", "info_value",
            "info_nodes", "info_time", "info_nodes_per_sec", "info_book",
        )
        const val DASH = "–"

        // Evaluation row colours (desktop cfs_qt/board.py)
        const val C_WIN = 0xffb8e6b8.toInt()
        const val C_DRAW = 0xfffff3a0.toInt()
        const val C_LOSS = 0xfff5b8b8.toInt()
        const val C_BASE = 0xffd9d9d9.toInt()
        const val C_FULL = 0xffa0a0a0.toInt()

        /** Rows per s² of the drop animation (6 rows ≈ 0.2 s). */
        private const val GRAVITY = 300.0
    }
}
