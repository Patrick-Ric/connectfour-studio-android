package io.github.patrickric.connectfourstudio

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import io.github.patrickric.connectfourstudio.core.Texts
import java.util.Locale

/** Languages of the program in menu order (`cfs_lang.LANG_ORDER`). */
val LANG_ORDER: List<String> = listOf("de", "en", "fr", "es", "nl", "it")

object LocaleUtil {
    /**
     * Start language: saved choice > system language (if translated) > English.
     * (Desktop: saved > system "en" -> en, otherwise German; see DECISIONS.md.)
     */
    fun effectiveLang(saved: String): String {
        if (saved in LANG_ORDER) return saved
        val sys = systemLocale().language
        return if (sys in LANG_ORDER) sys else "en"
    }

    private fun systemLocale(): Locale {
        val cfg = android.content.res.Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= 24) cfg.locales[0] else legacyLocale(cfg)
    }

    @Suppress("DEPRECATION")
    private fun legacyLocale(cfg: Configuration): Locale = cfg.locale

    /** Context whose resources use [lang] and the chosen theme ("" = system, "light", "dark"). */
    fun wrap(base: Context, lang: String, theme: String = ""): Context {
        val locale = Locale(lang)
        val cfg = Configuration(base.resources.configuration)
        val night = when (theme) {
            "light" -> Configuration.UI_MODE_NIGHT_NO
            "dark" -> Configuration.UI_MODE_NIGHT_YES
            else -> cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK
        }
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        if (Build.VERSION.SDK_INT >= 24) {
            cfg.setLocales(LocaleList(locale))
        } else {
            cfg.setLocale(locale)
        }
        return base.createConfigurationContext(cfg)
    }
}

/** [Texts] backed by the generated string resources of a localized context. */
class AppTexts(private val ctx: Context, override val lang: String) : Texts {
    override fun t(key: String): String {
        val id = StringKeys.MAP[key] ?: return key
        return ctx.getString(id)
    }

    override fun levelName(key: String): String = t("level_$key")

    override fun setName(no: Int): String = StringKeys.MAP["set_name_$no"]?.let { ctx.getString(it) } ?: "?"
}
