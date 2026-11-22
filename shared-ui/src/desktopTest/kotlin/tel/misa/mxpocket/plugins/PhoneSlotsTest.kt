package tel.misa.mxpocket.plugins

import org.junit.Assert.*
import org.junit.Test
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.targetProfileId
import tel.misa.mxpocket.i18n.AppLanguage
import tel.misa.mxpocket.i18n.AppLocale

class PhoneSlotsTest {
    private fun app(name: String) = ActionCall("phone.open", mapOf("app" to name))
    private fun slot(index: String) = ActionCall("plugin.key", mapOf("pluginId" to "phone", "index" to index))

    @Test fun savedCallsDescribeTheirEffectsWithoutOfferingEveryLegacySlot() {
        val before = AppLocale.current
        try {
            AppLocale.update(AppLanguage.ENGLISH, emptyList())
            val names = listOf("CAMERA", "SETTINGS", "DIALER", "WEB", null, "ALARMS")
            names.forEachIndexed { index, name ->
                if (name != null) {
                    assertNotNull(app(name).phoneActionLabel())
                    assertEquals(app(name).phoneActionLabel(), slot("$index").phoneActionLabel())
                }
            }
            assertEquals("Phone label (no action)", slot("4").phoneActionLabel())
            assertTrue(phoneFrame().keys[4].readOnly)
            assertEquals("Select profile: Focus", slot("6").phoneActionLabel())
            assertEquals("focus", slot("6").targetProfileId())
            assertEquals("Select profile: Audio", slot("7").phoneActionLabel())
            assertEquals("audio", slot("7").targetProfileId())
            assertEquals("Refresh keypad display", slot("8").phoneActionLabel())
            val choices = listOf("SETTINGS", "DIALER", "ALARMS", "ONENOTE").map(::app)
            for ((language, web) in listOf(AppLanguage.ENGLISH to "Open logitech.com",
                AppLanguage.FRENCH to "Ouvrir logitech.com", AppLanguage.SERBIAN_LATIN to "Otvori logitech.com")) {
                AppLocale.update(language, emptyList())
                assertEquals(web, app("WEB").phoneActionLabel())
                val imported = ActionCall("phone.open", mapOf("app" to "WEB", "url" to "https://example.com"))
                assertEquals(web, imported.phoneActionLabel())
                assertEquals(web, slot("3").phoneActionLabel())
                assertEquals(web, slot("03").phoneActionLabel())
                assertEquals(web, slot("+3").phoneActionLabel())
                assertEquals("logitech.com", phoneFrame().keys[3].label)
                assertEquals(choices, newPhoneAppActions())
                assertTrue(choices.all { !it.phoneActionLabel().isNullOrBlank() })
            }
        } finally {
            AppLocale.update(before, emptyList())
        }
    }

    @Test fun malformedOrUnrelatedBindingsAreNotDescribedAsPhoneActions() {
        val web = app("WEB")
        val invalid = listOf(ActionCall("phone.open"), app("web"), app("BROWSER"),
            web.copy(params = mapOf("url" to "https://example.com")), web.copy(id = "studio.open"),
            slot(""), slot("3x"), slot("-1"), slot("9"), slot("3").copy(params = mapOf("index" to "3")),
            slot("3").copy(params = mapOf("pluginId" to "audio", "index" to "3")),
            slot("3").copy(params = slot("3").params + ("app" to "WEB")))
        for (call in invalid) assertNull(call.toString(), call.phoneActionLabel())
    }
}
