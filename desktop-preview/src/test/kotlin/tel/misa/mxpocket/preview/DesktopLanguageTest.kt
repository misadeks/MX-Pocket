package tel.misa.mxpocket.preview

import java.util.Locale
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import tel.misa.mxpocket.i18n.AppLanguage
import tel.misa.mxpocket.i18n.AppLocale
import tel.misa.mxpocket.i18n.WorkspaceCopy

class DesktopLanguageTest {
    @Test fun explicitSelectionSurvivesAHostRestartAndSystemClearsIt() {
        val settings = Preferences.userRoot().node("tel/misa/mxpocket-test/${UUID.randomUUID()}")
        val before = AppLocale.current
        try {
            DesktopLanguage(settings).set(AppLanguage.SERBIAN_LATIN)
            settings.flush()
            AppLocale.update(AppLanguage.ENGLISH, emptyList())
            val reopened = DesktopLanguage(settings)
            assertEquals(AppLanguage.SERBIAN_LATIN, reopened.choice)
            assertEquals("Početna", WorkspaceCopy.Home.text())
            reopened.set(null)
            assertNull(DesktopLanguage(settings).choice)
            assertEquals(AppLanguage.resolve(listOf(Locale.getDefault(Locale.Category.DISPLAY).toLanguageTag())), AppLocale.current)
        } finally {
            settings.removeNode()
            AppLocale.update(before, emptyList())
        }
    }
}
