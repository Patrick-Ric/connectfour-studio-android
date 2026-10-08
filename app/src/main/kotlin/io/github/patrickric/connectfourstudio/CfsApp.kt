package io.github.patrickric.connectfourstudio

import android.app.Application
import android.content.Context

/** Application object: owns the [Controller], so games survive activity recreation. */
class CfsApp : Application() {
    private val controllerLazy = lazy { Controller(this) }
    val controller: Controller by controllerLazy

    /** False after a process restart until the first activity creates the controller. */
    val hasController: Boolean get() = controllerLazy.isInitialized()

    /** Active language code (saved choice or system language). */
    fun lang(): String = LocaleUtil.effectiveLang(Prefs(this).lang)

    /** Texts in [lang] (default: the active language). */
    fun texts(lang: String = lang()): AppTexts = AppTexts(LocaleUtil.wrap(this, lang), lang)

    companion object {
        fun of(context: Context): CfsApp = context.applicationContext as CfsApp
    }
}
