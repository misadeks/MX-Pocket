package tel.misa.mxpocket.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"), FRENCH("fr", "Français"), SERBIAN_LATIN("sr-Latn", "Srpski (latinica)");

    companion object {
        /** Match preferred languages in order; never claim support for the Cyrillic script. */
        fun resolve(tags: List<String>): AppLanguage = tags.firstNotNullOfOrNull { tag ->
            val parts = tag.lowercase().split('-')
            val script = parts.drop(1).firstOrNull { it.length == 4 && it.all(Char::isLetter) }
            when (parts.first()) {
                "fr" -> FRENCH
                "en" -> ENGLISH
                "sr" -> SERBIAN_LATIN.takeIf { script == null || script == "latn" }
                else -> null
            }
        } ?: ENGLISH
    }
}

/** One host language for Compose, OS callbacks and offscreen LCD painting. Never used as a storage codec. */
object AppLocale {
    var current by mutableStateOf(AppLanguage.ENGLISH)
        private set

    fun update(preference: AppLanguage?, systemTags: List<String>) {
        current = preference ?: AppLanguage.resolve(systemTags)
    }
}

/** All supported translations travel together, so a call site cannot silently omit a locale. */
class Copy(internal val en: String, internal val fr: String, internal val sr: String) {
    fun text(vararg args: Any?): String {
        val template = when (AppLocale.current) {
            AppLanguage.ENGLISH -> en
            AppLanguage.FRENCH -> fr
            AppLanguage.SERBIAN_LATIN -> sr
        }
        // One pass: a user-authored name such as "{1}" is data, not another placeholder.
        return if (args.isEmpty()) template else slot.replace(template) { args[it.groupValues[1].toInt()].toString() }
    }

    internal companion object { val slot = Regex("""\{(\d+)}""") }
}

class PluralCopy(val one: Copy, val few: Copy, val other: Copy) {
    fun text(count: Int, vararg args: Any?): String {
        val n = kotlin.math.abs(count.toLong())
        return when (AppLocale.current) {
            AppLanguage.ENGLISH -> if (n == 1L) one else other
            AppLanguage.FRENCH -> if (n <= 1) one else other
            AppLanguage.SERBIAN_LATIN -> when {
                n % 10 == 1L && n % 100 != 11L -> one
                n % 10 in 2L..4L && n % 100 !in 12L..14L -> few
                else -> other
            }
        }.text(count, *args)
    }
}
