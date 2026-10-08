package io.github.patrickric.connectfourstudio.core

import java.math.BigDecimal
import java.math.RoundingMode

/** Parameters of one move: key, blunder rate (0..1), loss guard s, win guard w. */
data class Stufe(val key: String, val err: Double, val s: Int?, val w: Int?)

/**
 * Levels (p, s, w), level names and Elo formula (port of `cfs_core.levels`).
 *
 * p = % perfect moves (blunder rate = (100-p)/100); s = loss guard in
 * opponent moves (blunder taboo if ml <= 2*s); w = win guard (short win with
 * ml <= 2*w-1 is always played). ml = stones to the end from own view.
 */
object Levels {
    val STUFEN_ORDER: List<String> = listOf(
        "verlierer", "zufall", "sehr_leicht", "leicht", "anfaenger",
        "fortgeschritten", "taktiker", "mittel", "fordernd",
        "schwer", "sehr_schwer", "experte", "meister",
        "starker_meister", "perfekt",
    )

    /** key -> (p, s, w); s/w null = not applicable (Perfekt). */
    private val STUFEN: Map<String, Triple<Int, Int?, Int?>> = linkedMapOf(
        "zufall" to Triple(0, 0, 0),
        "sehr_leicht" to Triple(25, 0, 0),
        "leicht" to Triple(40, 0, 0),
        "anfaenger" to Triple(50, 0, 0),
        "fortgeschritten" to Triple(20, 1, 1),
        "taktiker" to Triple(0, 3, 3),
        "mittel" to Triple(50, 1, 1),
        "fordernd" to Triple(55, 1, 1),
        "schwer" to Triple(65, 1, 1),
        "sehr_schwer" to Triple(70, 2, 2),
        "experte" to Triple(80, 2, 2),
        "meister" to Triple(85, 3, 3),
        "starker_meister" to Triple(92, 4, 4),
        "perfekt" to Triple(100, null, null),
    )

    val USER_KEYS: List<String> = listOf("user1", "user2")
    const val USER_LABEL_1 = "User (1)"
    const val USER_LABEL_2 = "User (2)"
    val USER_PSW_DEFAULT: Triple<Int, Int, Int> = Triple(50, 1, 1)

    /** Current user (p,s,w) per key (match dialog writes, engine reads). */
    private val userPsw: MutableMap<String, Triple<Int, Int, Int>> =
        java.util.concurrent.ConcurrentHashMap(mapOf("user1" to USER_PSW_DEFAULT, "user2" to USER_PSW_DEFAULT))

    val MATCH_STUFEN: List<String> = listOf("mensch") + STUFEN_ORDER + USER_KEYS

    /** Valid computer levels (without human). */
    val COMPUTER_KEYS: Set<String> = STUFEN.keys + setOf("verlierer", "user1", "user2")

    fun isStufe(key: String?): Boolean = key != null && key in STUFEN

    fun clampPsw(p: Int, s: Int, w: Int): Triple<Int, Int, Int> =
        Triple(p.coerceIn(0, 100), s.coerceIn(0, 9), w.coerceIn(0, 9))

    /** (p,s,w) from three text fields; invalid field -> default (50,1,1). */
    fun parsePsw(pTxt: String, sTxt: String, wTxt: String): Triple<Int, Int, Int> {
        fun one(txt: String, default: Int, hi: Int): Int =
            txt.trim().toIntOrNull()?.coerceIn(0, hi) ?: default
        return Triple(one(pTxt, 50, 100), one(sTxt, 1, 9), one(wTxt, 1, 9))
    }

    fun userPsw(key: String): Triple<Int, Int, Int> {
        val v = userPsw[key] ?: USER_PSW_DEFAULT
        return clampPsw(v.first, v.second, v.third)
    }

    fun setUserPsw(key: String, psw: Triple<Int, Int, Int>) {
        if (key in USER_KEYS) userPsw[key] = clampPsw(psw.first, psw.second, psw.third)
    }

    /** "Nr Name" in the active language (fallback 14 Perfekt). */
    fun levelLabel(key: String, tx: Texts): String {
        val k = if (key in STUFEN_ORDER) key else "perfekt"
        return "${STUFEN_ORDER.indexOf(k)} ${tx.levelName(k)}"
    }

    /** Unknown levels -> "perfekt" (like `stufe_key` of the Tk version). */
    fun normalizeKey(key: String): String =
        if (key == "mensch") key else if (key in COMPUTER_KEYS) key else "perfekt"

    /** (key, blunder rate, s, w) for a level key. */
    fun stufenWerte(key: String): Stufe {
        if (key == "mensch") return Stufe("mensch", 0.0, null, null)
        if (key == "verlierer") return Stufe("verlierer", 1.0, 0, 0)
        if (key in USER_KEYS) {
            val (p, s, w) = userPsw(key)
            val q = (100 - p) / 100.0
            return Stufe(key, q.coerceIn(0.0, 1.0), s, w)
        }
        val dat = STUFEN[key] ?: return Stufe("perfekt", 0.0, null, null)
        val q = ((100 - dat.first) / 100.0).coerceIn(0.0, 1.0)
        return Stufe(key, q, dat.second, dat.third)
    }

    /** Level name for match, info box and score box. */
    fun stufeLabelFor(key: String?, tx: Texts, mitPsw: Boolean = false): String {
        if (key == "mensch") return tx.t("level_human")
        if (key == "verlierer") return levelLabel("verlierer", tx)
        if (key != null && key in USER_KEYS) {
            val lbl = if (key == "user1") USER_LABEL_1 else USER_LABEL_2
            if (!mitPsw) return lbl
            val (p, s, w) = userPsw(key)
            return "$lbl ($p,$s,$w)"
        }
        val k = key ?: "perfekt"
        val name = levelLabel(k, tx)
        if (mitPsw && k !in setOf("perfekt", "verlierer", "zufall")) {
            val dat = STUFEN[k]
            if (dat != null) {
                val sTxt = dat.second?.toString() ?: "-"
                val wTxt = dat.third?.toString() ?: "-"
                return "$name (${dat.first},$sTxt,$wTxt)"
            }
        }
        return name
    }

    /** Elo difference from Yellow's view (-400*log10((1-p)/p)), 0/100 % -> -/+2000. */
    fun matchElo(punkteGelb: Double, partien: Int): Double? {
        if (partien <= 0) return null
        val p = punkteGelb / partien
        if (p <= 0.0) return -2000.0
        if (p >= 1.0) return 2000.0
        val raw = -400.0 * Math.log10((1.0 - p) / p)
        val rounded = BigDecimal.valueOf(raw).setScale(0, RoundingMode.HALF_UP).toDouble()
        // Python's Decimal keeps the sign of zero ("-0"); BigDecimal does not.
        return if (rounded == 0.0 && (raw < 0.0 || 1.0 / raw < 0.0)) -0.0 else rounded
    }

    /** 12.0 -> "12", 12.5 -> "12,5" (decimal separator per language). */
    fun shortNumber(x: Double, lang: String): String {
        if (Math.abs(x - Math.rint(x)) < 1e-9) return Math.rint(x).toLong().toString()
        return Fmt.decimal(Fmt.fixed(x, 1), lang)
    }
}
