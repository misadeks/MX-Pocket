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
                val lcd = phoneCallFrame(line, "Incoming call", true, 42).keys
                val home = phoneCallHomeFrame(line, true, 42).keys
                assertEquals(incoming, lcd[CALL_IDENTITY].footer)
                assertEquals("Incoming call", lcd[CALL_IDENTITY].label)
                assertEquals(redacted, home[CALL_IDENTITY].label)
                assertEquals(lcd.map { it.inputToken }, home.map { it.inputToken })
                assertEquals("answer:42", lcd[CALL_ACCEPT].inputToken)
                assertEquals("decline:42", lcd[CALL_DECLINE].inputToken)
                assertEquals(listOf(6, 8), lcd.indices.filterNot { lcd[it].readOnly })
                val teams = incomingTeamsFrame().keys
                assertEquals("Teams", teams[CALL_IDENTITY].label)
                assertEquals(incoming, teams[CALL_IDENTITY].footer)
                assertEquals(IntegrationApp.TEAMS, teams[CALL_PHOTO].glyph)
                val named = incomingTeamsFrame("Fixture").keys[CALL_IDENTITY]
                assertEquals("Fixture", named.label)
                assertEquals(when (language) {
                    AppLanguage.FRENCH -> "Appel Teams entrant"
                    AppLanguage.SERBIAN_LATIN -> "Dolazni Teams poziv"
                    else -> "Incoming Teams call"
                }, named.footer)
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
                val expected = if (line == PhoneLine.CALL || line == PhoneLine.MULTIPLE) line.label else "Ime nije dostupno"
                assertEquals(expected, keys[CALL_IDENTITY].label)
                assertTrue(keys.filterIndexed { i, _ -> i != CALL_IDENTITY }.all { it.label.isEmpty() })
                assertTrue(keys.all { it.readOnly })
                keys.forEach { assertNull(it.inputToken) }
            }
        } finally {
            AppLocale.update(previous, emptyList())
        }
    }
}
