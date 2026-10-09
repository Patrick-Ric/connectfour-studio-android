package io.github.patrickric.connectfourstudio

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent settings (desktop: settings.json + lang.cfg): view options,
 * stone set, computer level, language and the user (p,s,w) of the match.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var ghost: Boolean
        get() = sp.getBoolean("ghost", true)
        set(v) = sp.edit().putBoolean("ghost", v).apply()

    var anim: Boolean
        get() = sp.getBoolean("anim", true)
        set(v) = sp.edit().putBoolean("anim", v).apply()

    var showLast: Boolean
        get() = sp.getBoolean("show_last", true)
        set(v) = sp.edit().putBoolean("show_last", v).apply()

    var bigBoard: Boolean
        get() = sp.getBoolean("big_board", true)
        set(v) = sp.edit().putBoolean("big_board", v).apply()

    var setNo: Int
        get() = sp.getInt("set_no", 1)
        set(v) = sp.edit().putInt("set_no", v).apply()

    var level: String
        get() = sp.getString("level", "perfekt") ?: "perfekt"
        set(v) = sp.edit().putString("level", v).apply()

    /** Theme: "" = like the system, "light", "dark". */
    var theme: String
        get() = sp.getString("theme", "") ?: ""
        set(v) = sp.edit().putString("theme", v).apply()

    /** "" = not chosen (system language). */
    var lang: String
        get() = sp.getString("lang", "") ?: ""
        set(v) = sp.edit().putString("lang", v).apply()

    fun userPsw(key: String): String? = sp.getString("psw_$key", null)

    fun setUserPsw(key: String, value: String) = sp.edit().putString("psw_$key", value).apply()
}
