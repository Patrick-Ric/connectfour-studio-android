package io.github.patrickric.connectfourstudio.core

/**
 * Running or last computer-computer match (port of `cfs_core.match.Match`).
 * gelb/rot = level keys of the two sides (incl. "mensch", "user1", "user2"),
 * nr = current game (1-based).
 */
class Match(
    val gelb: String,
    val rot: String,
    spiele: Int,
    val wechsel: Boolean = true,
    val schnell: Boolean = false,
    blind: Boolean = false,
) {
    val spiele: Int = spiele.coerceIn(1, 10000)

    /** Turbo ("results only"); never with a human side (board must stay visible). */
    val blind: Boolean = blind && "mensch" != gelb && "mensch" != rot

    var nr: Int = 1
        private set
    var punkteGelb: Int = 0
        private set
    var remis: Int = 0
        private set
    var fertig: Boolean = false
        private set
    var abgebrochen: Boolean = false
        private set

    val running: Boolean get() = !fertig

    /** Without colour swap Yellow always starts; with swap alternating. */
    fun gelbBeginnt(): Boolean = if (!wechsel) true else nr % 2 == 1

    /** Level of the side to move after [nMoves] moves. */
    fun sideStufe(nMoves: Int): String =
        if (gelbBeginnt()) {
            if (nMoves % 2 == 0) gelb else rot
        } else {
            if (nMoves % 2 == 0) rot else gelb
        }

    fun hasHuman(): Boolean = "mensch" == gelb || "mensch" == rot

    /** Pause between moves: normal 350 ms, fast/turbo 0. */
    fun delayMs(): Int = if (schnell || blind) 0 else 350

    /** 3 s pause before the next game (human side or normal tempo). */
    fun pauseBetweenGames(): Boolean = hasHuman() || !(schnell || blind)

    /**
     * Scores a game. winner: 1 Yellow (moved first), 2 Red, null/0 draw.
     * Returns the level key of the winning side or null (draw).
     */
    fun recordResult(winner: Int?): String? {
        if (winner == null || winner == 0) {
            remis++
            return null
        }
        if (winner == 1) {
            if (gelbBeginnt()) {
                punkteGelb++
                return gelb
            }
            return rot
        }
        if (!gelbBeginnt()) {
            punkteGelb++
            return gelb
        }
        return rot
    }

    /** Next game; true if there is one, otherwise the match is finished. */
    fun advance(): Boolean {
        if (nr >= spiele) {
            fertig = true
            return false
        }
        nr++
        return true
    }

    fun stop(cancelled: Boolean = true) {
        fertig = true
        if (cancelled) abgebrochen = true
    }

    fun done(): Int = maxOf(0, if (fertig) nr else nr - 1)

    fun points(): Pair<Double, Double> {
        val done = done()
        val pg = punkteGelb + 0.5 * remis
        val pr = (done - punkteGelb - remis) + 0.5 * remis
        return pg to pr
    }

    fun losses(): Int = done() - punkteGelb - remis

    fun elo(): Double? = Levels.matchElo(punkteGelb + 0.5 * remis, done())

    fun scoreStr(lang: String, fixed: Boolean = false): String {
        val (pg, pr) = points()
        val g = punkteGelb
        var r = losses()
        if (fixed) {
            val pgS = Levels.shortNumber(pg, lang)
            var prS = Levels.shortNumber(pr, lang)
            if (pr < 0) {
                prS = "0"
                r = maxOf(0, r)
            }
            return "$pgS-$prS (+$g/=$remis/-$r)"
        }
        return "${Fmt.g(pg)} – ${Fmt.g(pr)} (+$g/=$remis/-$r)"
    }

    // ------------------------------------------------------------ texts
    fun statusLine(tx: Texts): String {
        val e = elo()
        val eloTxt = if (e == null) "Elo –" else "Elo ${Fmt.signed0(e)}"
        return tx.tf(
            "match_status_line", "nr" to nr, "games" to spiele,
            "g" to Levels.stufeLabelFor(gelb, tx), "r" to Levels.stufeLabelFor(rot, tx),
            "score" to scoreStr(tx.lang, fixed = true), "elo" to eloTxt,
        )
    }

    fun liveText(tx: Texts): String {
        val e = elo()
        val eloTxt = if (e == null) "–" else Fmt.signed0(e)
        // Order as in Tk: finished (also after abort) -> "Beendet."
        val state = tx.t(
            when {
                fertig -> "match_state_done"
                abgebrochen -> "match_state_aborted"
                else -> "match_state_running"
            },
        )
        return tx.tf(
            "match_live", "nr" to minOf(nr, spiele), "games" to spiele, "state" to state,
            "g" to Levels.stufeLabelFor(gelb, tx), "r" to Levels.stufeLabelFor(rot, tx),
            "score" to scoreStr(tx.lang), "elo" to eloTxt,
        )
    }

    fun resultText(tx: Texts): String {
        val gn = Levels.stufeLabelFor(gelb, tx, mitPsw = true)
        val rn = Levels.stufeLabelFor(rot, tx, mitPsw = true)
        val (pg, pr) = points()
        val e = elo()
        val eloTxt = if (e == null) "–" else Fmt.signed0(e)
        val state = tx.t(if (abgebrochen) "match_aborted_lc" else "match_finished_lc")
        fun seite(key: String, label: String): String =
            if (key == "mensch") tx.t("level_human") else tx.tf("side_computer", "label" to label)
        return tx.tf(
            "match_result_text", "a" to seite(gelb, gn), "b" to seite(rot, rn),
            "pg" to Fmt.g(pg), "pr" to Fmt.g(pr), "w" to punkteGelb, "d" to remis,
            "l" to losses(), "elo" to eloTxt, "done" to done(), "games" to spiele,
            "state" to state, "swap" to tx.t(if (wechsel) "onoff_on" else "onoff_off"),
        )
    }

    companion object {
        fun resultTextOrNone(match: Match?, tx: Texts): String =
            match?.resultText(tx) ?: tx.t("match_no_result")

        /**
         * Normal human-computer game: true = human won, false = computer,
         * null = draw. Rule: the last mover won; the human played the odd
         * moves (humanFirst) or the even ones.
         */
        fun humanWonNormal(winner: Int?, nMoves: Int, humanFirst: Boolean): Boolean? {
            if (winner == null || winner == 0) return null
            val zuletztUngerade = nMoves % 2 == 1
            return zuletztUngerade == humanFirst
        }
    }
}

/** Display lines of the score box: head, big result, detail line, Elo line. */
data class ScoreDisplay(val head: String, val big: String, val sub: String, val elo: String) {
    companion object {
        val EMPTY = ScoreDisplay("", "", "", "")
    }
}

/**
 * Session score human vs. computer per opponent level (port of
 * `SessionScore`): pairs[compKey] = [human wins, computer wins, draws].
 */
class SessionScore {
    var enabled: Boolean = false
    val pairs: LinkedHashMap<String, IntArray> = LinkedHashMap()

    private fun bump(pair: String, idx: Int?) {
        val z = pairs.getOrPut(pair) { IntArray(3) }
        if (idx != null) z[idx]++
    }

    /** Books a normal game (only when enabled). Returns the pair or null. */
    fun bookNormal(compKey: String, siegerMensch: Boolean?): String? {
        if (!enabled) return null
        val pair = pairKeyNormal(compKey) ?: return null
        bump(pair, if (siegerMensch == null) 2 else if (siegerMensch) 0 else 1)
        return pair
    }

    /** Books a match game with a human side (only when enabled). */
    fun addMatchResult(gelb: String, rot: String, siegerKey: String?): String? {
        if (!enabled) return null
        val pair = pairKeyMatch(gelb, rot) ?: return null
        val idx = when (siegerKey) {
            null -> 2
            "mensch" -> 0
            else -> 1
        }
        bump(pair, idx)
        return pair
    }

    /** Level change: score of the new opponent level back to 0-0. */
    fun resetTo(compKey: String): String? {
        if (compKey == "mensch" || compKey !in Levels.COMPUTER_KEYS) return null
        // Like a Python dict: an existing key keeps its position.
        val z = pairs[compKey]
        if (z != null) z.fill(0) else pairs[compKey] = IntArray(3)
        return compKey
    }

    fun lastPair(): String? = pairs.keys.lastOrNull()

    /** On/off. Freshly on without data -> 0-0 against compKey. */
    fun toggle(compKey: String): Boolean {
        enabled = !enabled
        if (enabled && pairs.isEmpty()) {
            var k = compKey
            if (k == "mensch" || (!Levels.isStufe(k) && k != "verlierer")) k = "perfekt"
            pairs.getOrPut(k) { IntArray(3) }
        }
        return enabled
    }

    /** Reset button: last pair (or current level) back to 0-0. */
    fun reset(compKey: String): String {
        val pair = lastPair()
            ?: if (compKey != "mensch" && Levels.isStufe(compKey)) compKey else "perfekt"
        val z = pairs[pair]
        if (z != null) z.fill(0) else pairs[pair] = IntArray(3)
        return pair
    }

    fun display(tx: Texts, pairIn: String? = null): ScoreDisplay {
        if (!enabled) return ScoreDisplay.EMPTY
        val pair = pairIn ?: lastPair() ?: return ScoreDisplay.EMPTY
        val z = pairs[pair] ?: return ScoreDisplay.EMPTY
        val (sm, sc, rem) = Triple(z[0], z[1], z[2])
        val pm = sm + 0.5 * rem
        val pc = sc + 0.5 * rem
        val partien = sm + sc + rem
        val head = tx.tf("stand_head", "x" to Levels.stufeLabelFor(pair, tx))
        val big = "${Levels.shortNumber(pm, tx.lang)}-${Levels.shortNumber(pc, tx.lang)}"
        val sub = tx.tf("stand_sub", "w" to sm, "d" to rem, "l" to sc, "n" to partien)
        var eloLine = ""
        if (pm >= 0.5 && pc >= 0.5 && partien > 0) {
            val e = Levels.matchElo(pm, partien)
            val eloTxt = when {
                e == null -> "–"
                Math.abs(e) < 0.5 -> "±0"
                else -> Fmt.signed0(e)
            }
            eloLine = "Elo $eloTxt"
        }
        return ScoreDisplay(head, big, sub, eloLine)
    }

    companion object {
        fun pairKeyNormal(compKey: String): String? =
            if (compKey == "mensch") null else if (compKey in Levels.COMPUTER_KEYS) compKey else null

        /** Opponent level of a match with exactly one human side. */
        fun pairKeyMatch(gelb: String, rot: String): String? {
            val other = when {
                gelb == "mensch" && rot != "mensch" -> rot
                rot == "mensch" && gelb != "mensch" -> gelb
                else -> return null
            }
            return if (other in Levels.COMPUTER_KEYS) other else null
        }
    }
}
