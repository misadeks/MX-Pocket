package tel.misa.mxpocket.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tel.misa.mxpocket.i18n.AppLanguage
import tel.misa.mxpocket.i18n.AppLocale
import java.util.Locale
import java.util.prefs.Preferences

internal class DesktopLanguage(private val settings: Preferences) {
    var choice by mutableStateOf(AppLanguage.entries.find { it.tag == settings.get("language", null) })
        private set

    init { applyChoice() }

    fun set(language: AppLanguage?) {
        if (language == null) settings.remove("language") else settings.put("language", language.tag)
        choice = language
        applyChoice()
    }

    private fun applyChoice() = AppLocale.update(choice, listOf(Locale.getDefault(Locale.Category.DISPLAY).toLanguageTag()))
}
