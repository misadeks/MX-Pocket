package tel.misa.mxpocket.ui

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tel.misa.mxpocket.R
import tel.misa.mxpocket.i18n.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], qualifiers = "en-rUS")
class LanguagePreferenceTest {
    @Test fun appAndSystemAgreeAfterRestartAndReset() {
        val app = RuntimeEnvironment.getApplication()
        try {
            LanguagePreference.set(app, AppLanguage.SERBIAN_LATIN)
            if (Build.VERSION.SDK_INT >= 33)
                assertEquals("sr-Latn", app.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
            AppLocale.update(AppLanguage.ENGLISH, emptyList())
            LanguagePreference.initialize(app)
            assertEquals(AppLanguage.SERBIAN_LATIN, LanguagePreference.choice(app))
            assertEquals("Prekinite vezu", LanguagePreference.resourcesFor(app).getString(R.string.disconnect))
            if (Build.VERSION.SDK_INT >= 33) {
                app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("fr")
                LanguagePreference.sync(app)
                assertEquals("Accueil", WorkspaceCopy.Home.text())
            }
            LanguagePreference.set(app, null)
            LanguagePreference.initialize(app)
            assertNull(LanguagePreference.choice(app))
            assertEquals(AppLanguage.ENGLISH, AppLocale.current)
        } finally { LanguagePreference.set(app, null) }
    }
}
