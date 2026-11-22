package tel.misa.mxpocket.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import tel.misa.mxpocket.i18n.AppLanguage
import tel.misa.mxpocket.i18n.AppLocale
import java.util.Locale

internal object LanguagePreference {
    private const val TAG = "language"
    private fun legacy(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private fun parse(tag: String?) = AppLanguage.entries.find { it.tag.equals(tag, ignoreCase = true) }

    fun choice(context: Context): AppLanguage? =
        if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java)
            .applicationLocales.takeUnless { it.isEmpty }?.let { AppLanguage.resolve(it.toLanguageTags().split(',')) }
        else parse(legacy(context).getString(TAG, null))

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && legacy(context).contains(TAG)) {
            val manager = context.getSystemService(LocaleManager::class.java)
            if (manager.applicationLocales.isEmpty) parse(legacy(context).getString(TAG, null))?.let {
                manager.applicationLocales = LocaleList.forLanguageTags(it.tag)
            }
            legacy(context).edit().remove(TAG).apply()
        }
        sync(context)
    }

    fun sync(context: Context) {
        val system = if (Build.VERSION.SDK_INT >= 33) context.getSystemService(LocaleManager::class.java).systemLocales
            else Resources.getSystem().configuration.locales
        AppLocale.update(choice(context), system.toLanguageTags().split(','))
    }

    fun set(context: Context, choice: AppLanguage?) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(choice?.tag.orEmpty())
        } else {
            legacy(context).edit().putString(TAG, choice?.tag).apply()
        }
        sync(context)
    }

    /** Services and older Android Activity contexts don't necessarily carry the app locale. */
    fun activityContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val explicit = choice(context) ?: return context
        return withLocale(context, explicit)
    }

    fun resourcesFor(context: Context): Resources = withLocale(context, AppLocale.current).resources

    private fun withLocale(context: Context, language: AppLanguage): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language.tag))
        })
}
