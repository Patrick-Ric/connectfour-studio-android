package io.github.patrickric.connectfourstudio.core

import java.util.Locale

/**
 * UI texts for the core (port of `cfs_core.lang`). The app backs this with
 * Android string resources (values-xx/strings.xml); the tests read the same
 * XML files. Keys are the keys of `cfs_core.lang.STRINGS`.
 */
interface Texts {
    /** Active language code: de, en, fr, es, nl or it. */
    val lang: String

    fun t(key: String): String

    /** Level name without number (`lang.level_name`), e.g. "Mittel". */
    fun levelName(key: String): String

    /** Display name of stone set [no] (`sets.set_display_name`). */
    fun setName(no: Int): String
}

/** `tf()`: fills `{name}` placeholders; unknown placeholders stay as they are. */
fun Texts.tf(key: String, vararg args: Pair<String, Any?>): String = fill(t(key), *args)

fun fill(template: String, vararg args: Pair<String, Any?>): String {
    if (args.isEmpty() || template.indexOf('{') < 0) return template
    val sb = StringBuilder(template.length + 32)
    var i = 0
    while (i < template.length) {
        val ch = template[i]
        if (ch == '{') {
            val end = template.indexOf('}', i + 1)
            if (end > i) {
                val name = template.substring(i + 1, end)
                val hit = args.firstOrNull { it.first == name }
                if (hit != null) {
                    sb.append(hit.second.toString())
                    i = end + 1
                    continue
                }
            }
        }
        sb.append(ch)
        i++
    }
    return sb.toString()
}

/** Number formatting per language (`fmt_thousands`, `fmt_decimal`). */
object Fmt {
    fun thousands(n: Long, lang: String): String {
        val sep = when (lang) {
            "de", "es", "nl", "it" -> "."
            "fr" -> " "
            else -> ","
        }
        // Grouped by hand: String.format("%,d") with Locale.ROOT throws
        // "divide by zero" on Android 7 (grouping size 0 in its locale data).
        val digits = Math.abs(n).toString()
        val sb = StringBuilder(digits.length + digits.length / 3 + 1)
        if (n < 0) sb.append('-')
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(sep)
            sb.append(ch)
        }
        return sb.toString()
    }

    fun decimal(txt: String, lang: String): String =
        if (lang in setOf("de", "es", "fr", "nl", "it")) txt.replace('.', ',') else txt

    /** Python `f"{x:.Nf}"` with a dot, independent of the device locale. */
    fun fixed(x: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", x)

    /** Python `f"{x:g}"` for the values used here (multiples of 0.5). */
    fun g(x: Double): String {
        if (x == Math.rint(x) && Math.abs(x) < 1e15) return x.toLong().toString()
        return BigDecimalHelper.plain(x)
    }

    /** Python `f"{x:+.0f}"` (keeps the sign of -0.0 like Python). */
    fun signed0(x: Double): String = String.format(Locale.US, "%+.0f", x)
}

internal object BigDecimalHelper {
    fun plain(x: Double): String = java.math.BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()
}
