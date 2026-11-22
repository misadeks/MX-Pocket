package tel.misa.mxpocket.i18n

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class LocalizationTest {
    private val previous = AppLocale.current
    @After fun restoreLocale() = AppLocale.update(previous, emptyList())

    @Test fun scriptPreferenceOrderFallbackAndOpaqueArguments() {
        AppLocale.update(null, listOf("sr-Cyrl-RS", "fr-CA"))
        assertEquals(AppLanguage.FRENCH, AppLocale.current)
        assertEquals("Sélection : {0}.", WorkspaceCopy.Selected.text("{0}"))
        AppLocale.update(null, listOf("sr-Latn-RS"))
        assertEquals(AppLanguage.SERBIAN_LATIN, AppLocale.current)
        AppLocale.update(null, listOf("de", "sr-Cyrl"))
        assertEquals(AppLanguage.ENGLISH, AppLocale.current)
    }

    @Test fun integerPluralsRespectFrenchZeroAndSerbianTeens() {
        val count = PluralCopy(Copy("one", "un", "{0} taster"), Copy("few", "peu", "{0} tastera"),
            Copy("other", "autres", "{0} tastera (više)"))
        AppLocale.update(AppLanguage.SERBIAN_LATIN, emptyList())
        assertEquals(listOf("1 taster", "2 tastera", "5 tastera (više)", "11 tastera (više)", "21 taster",
            "12 tastera (više)", "24 tastera"), listOf(1, 2, 5, 11, 21, 12, 24).map { count.text(it) })
        AppLocale.update(AppLanguage.FRENCH, emptyList())
        assertEquals("un", count.text(0))
        assertEquals("autres", count.text(2))
    }

    @Test fun catalogsHaveCompleteLatinTextAndPreserveFormatArguments() {
        val catalogs = listOf(WorkspaceCopy, LayoutCopy, WorkflowCopy, KeypadCopy, AccessCopy)
        fun checkEntry(id: String, copy: Copy) {
            val slots = { value: String -> Copy.slot.findAll(value).map { it.value }.toSet() }
            assertEquals("$id/fr", slots(copy.en), slots(copy.fr))
            assertEquals("$id/sr-Latn", slots(copy.en), slots(copy.sr))
            assertTrue(id, listOf(copy.en, copy.fr, copy.sr).none(String::isBlank))
            assertFalse(id, Regex("\\p{IsCyrillic}").containsMatchIn(copy.sr))
        }
        for (catalog in catalogs) for (field in catalog.javaClass.declaredFields) {
            field.isAccessible = true
            when (val entry = field.get(catalog)) {
                is Copy -> checkEntry(field.name, entry)
                is PluralCopy -> listOf(entry.one, entry.few, entry.other).forEach { checkEntry(field.name, it) }
            }
        }
    }
}
