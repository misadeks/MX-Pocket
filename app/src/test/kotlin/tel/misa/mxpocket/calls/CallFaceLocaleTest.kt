package tel.misa.mxpocket.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tel.misa.mxpocket.i18n.AppLanguage
import tel.misa.mxpocket.i18n.AppLocale
import tel.misa.mxpocket.plugins.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CallFaceLocaleTest {
    @Test fun switchingLanguagesChangesCaptionsButNotTheCallerOrActionReceipts() {
        val previous = AppLocale.current
        val line = PhoneLine.RINGING
        try {
            for ((language, incoming, redacted) in listOf(
                Triple(AppLanguage.ENGLISH, "Incoming call", "On keypad"),
                Triple(AppLanguage.FRENCH, "Appel entrant", "Sur le pavé"),
                Triple(AppLanguage.SERBIAN_LATIN, "Dolazni poziv", "Na tastaturi"),
            )) {
                AppLocale.update(language, emptyList())
                // A dialer-supplied label can look exactly like localizable UI copy.
                val lcd = phoneCallFrame(line, "Private / unknown", true, 42).keys
                val home = phoneCallHomeFrame(line, true, 42).keys
                assertEquals(incoming, lcd[0].label)
                assertEquals("Private / unknown", lcd[4].label)
                assertEquals(redacted, home[4].label)
                assertEquals(lcd.map { it.inputToken }, home.map { it.inputToken })
                assertEquals("answer:42", lcd[CALL_ACCEPT].inputToken)
                assertEquals("decline:42", lcd[CALL_DECLINE].inputToken)
                assertEquals(listOf(6, 8), lcd.indices.filterNot { lcd[it].readOnly })
            }
        } finally {
            AppLocale.update(previous, emptyList())
        }
    }

    @Test fun missingIdentityAndUnavailableControlsRemainReadOnly() {
        val previous = AppLocale.current
        try {
            AppLocale.update(AppLanguage.SERBIAN_LATIN, emptyList())
            for (line in PhoneLine.entries) {
                val keys = phoneCallFrame(line, null, controls = line != PhoneLine.RINGING, generation = 1).keys
                assertEquals("Skriven / nepoznat", keys[4].label)
                assertEquals("Na telefonu", keys[5].label)
                assertTrue(keys.all { it.readOnly })
                keys.forEach { assertNull(it.inputToken) }
            }
        } finally {
            AppLocale.update(previous, emptyList())
        }
    }
}
