package io.github.patrickric.connectfourstudio.core

import io.github.patrickric.connectfourstudio.core.engine.BookCodec
import io.github.patrickric.connectfourstudio.core.engine.OpeningBook
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Shared fixtures: repository paths, the opening book, German texts. */
object TestSupport {
    val root: File by lazy {
        val p = System.getProperty("cfs.root")
        if (p != null) File(p) else File("..").canonicalFile
    }

    val bookFile: File get() = File(root, "app/src/main/assets/book/book_12ply_dist.cfb")

    val book: OpeningBook by lazy { bookFile.inputStream().use { OpeningBook(BookCodec.decode(it)) } }

    /** One engine with book for all tests (like the session fixture of conftest.py). */
    val engine: Engine by lazy { Engine(book = book) }

    fun texts(lang: String = "de"): Texts = XmlTexts(lang)

    fun resource(name: String): String =
        TestSupport::class.java.classLoader.getResource(name)!!.readText()

    fun board(vararg moves: Int) = Game.boardFromMoves(moves.toList())
}

/** Texts backed by the app's generated strings.xml (same content as on Android). */
class XmlTexts(override val lang: String) : Texts {
    private val map: Map<String, String> = load(lang)

    override fun t(key: String): String = map[key] ?: key
    override fun levelName(key: String): String = map["level_$key"] ?: key
    override fun setName(no: Int): String = map["set_name_$no"] ?: "?"

    companion object {
        private fun unescape(s: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (val n = s[i + 1]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> sb.append(n)
                    }
                    i += 2
                    continue
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        fun load(lang: String): Map<String, String> {
            val dir = if (lang == "en") "values" else "values-$lang"
            val f = File(TestSupport.root, "app/src/main/res/$dir/strings.xml")
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.getElementsByTagName("string")
            val out = HashMap<String, String>()
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as org.w3c.dom.Element
                out[e.getAttribute("name")] = unescape(e.textContent)
            }
            return out
        }
    }
}

/** Minimal JSON reader for the reference fixture (objects, arrays, numbers, strings, null, booleans). */
class Json private constructor(private val s: String) {
    private var i = 0

    private fun ws() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun value(): Any? {
        ws()
        return when (s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> num()
        }
    }

    private fun obj(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (s[i] == '}') { i++; return m }
        while (true) {
            ws()
            val k = str()
            ws()
            i++ // :
            m[k] = value()
            ws()
            if (s[i++] == '}') return m
        }
    }

    private fun arr(): List<Any?> {
        val l = ArrayList<Any?>()
        i++
        ws()
        if (s[i] == ']') { i++; return l }
        while (true) {
            l.add(value())
            ws()
            if (s[i++] == ']') return l
        }
    }

    private fun str(): String {
        val sb = StringBuilder()
        i++
        while (s[i] != '"') {
            if (s[i] == '\\') {
                i++
                when (s[i]) {
                    'n' -> sb.append('\n')
                    'u' -> { sb.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                    else -> sb.append(s[i])
                }
            } else {
                sb.append(s[i])
            }
            i++
        }
        i++
        return sb.toString()
    }

    private fun num(): Any {
        val st = i
        while (i < s.length && (s[i] in "-+.eE" || s[i].isDigit())) i++
        val t = s.substring(st, i)
        return t.toLongOrNull() ?: t.toDouble()
    }

    companion object {
        fun parse(text: String): Any? = Json(text).value()
    }
}

@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
fun Any?.list(): List<Any?> = this as List<Any?>

fun Any?.ints(): List<Int> = list().map { (it as Long).toInt() }
