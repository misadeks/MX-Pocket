package tel.misa.mxpocket.deck

import android.app.Application
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tel.misa.mxpocket.actions.ActionCall

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeckRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val file: File get() = File(folder.root, "deck.json")
    private val repo by lazy { DeckRepository(file) }

    private fun pluginKey(id: String, index: Int) =
        DeckKey(action = ActionCall("plugin.key", mapOf("pluginId" to id, "index" to index.toString())))

    @Test fun freshAndRestoredNavigationDefaultsUsePhysicalOrderWithoutChangingOtherDecks() {
        fun checkDefaults() {
            for (profile in repo.state.value.profiles) {
                val slots = if (profile.id == "navigation") listOf(0, 3, 1, 6, 4, 8, 2, 5, 7) else (0..8).toList()
                assertEquals(profile.id, slots.map { pluginKey(profile.id, it) }, profile.pages.single().keys)
            }
        }
        checkDefaults()
        repo.setKey("navigation", "root", 1, DeckKey()).getOrThrow()
        repo.restoreDefaults().getOrThrow()
        checkDefaults()
    }

    // The v1 on-disk format, independent of the current default generator and writer.
    private fun historicalNavigation(): JSONObject {
        val keys = (0..8).joinToString(",") {
            """{"icon":"","label":"","color":-14138284,"enabled":true,"action":{"id":"plugin.key","params":{"pluginId":"navigation","index":"$it"}}}"""
        }
        return JSONObject("""{"version":1,"activeProfileId":"navigation","defaultProfileId":"navigation",
            "pinnedProfileId":null,"automatic":false,"histories":{"navigation":["root"]},"selectionReason":"Default profile",
            "profiles":[{"id":"navigation","name":"Navigation","rootPageId":"root","automatic":true,"rules":[],
            "pages":[{"id":"root","name":"Navigation","keys":[$keys]}]}]}""")
    }

    @Test fun loadingHistoricalStockUpgradesOnlyTheOriginalRootAndCanBeRetried() {
        val old = historicalNavigation()
        val profiles = old.getJSONArray("profiles")
        val profile = profiles.getJSONObject(0)
        profiles.put(JSONObject(profile.toString()).put("id", "copy"))
        old.getJSONObject("histories").put("copy", old.getJSONObject("histories").getJSONArray("navigation"))
        val pages = profile.getJSONArray("pages")
        pages.put(JSONObject(pages.getJSONObject(0).toString()).put("id", "copy"))
        file.writeText(old.toString())
        val loaded = DeckRepository(file)
        val state = loaded.state.value
        val originalKeys = (0..8).map { pluginKey("navigation", it) }
        assertEquals(listOf(0, 3, 1, 6, 4, 8, 2, 5, 7).map(originalKeys::get), state.activePage.keys)
        assertEquals(originalKeys, state.activeProfile.pages.single { it.id == "copy" }.keys)
        assertEquals(originalKeys, state.profiles.single { it.id == "copy" }.pages.single().keys)
        assertEquals(state, DeckRepository(file).state.value) // retry before a save
        loaded.save().getOrThrow()
        assertEquals(state, DeckRepository(file).state.value) // no second permutation
    }

    @Test fun intentionallyRestoredNavigationOrderSurvivesColdStartAndExportImport() {
        file.writeText(historicalNavigation().toString())
        val current = DeckRepository(file)
        val chosen = (0..8).map { pluginKey("navigation", it) }
        chosen.forEachIndexed { index, key -> current.setKey("navigation", "root", index, key).getOrThrow() }
        val exported = current.exportJson()
        assertEquals(DeckLimits.VERSION, JSONObject(exported).getInt("version"))
        val reopened = DeckRepository(file)
        assertEquals(chosen, reopened.state.value.activePage.keys)
        reopened.restoreDefaults().getOrThrow()
        reopened.importJson(exported).getOrThrow()
        assertEquals(chosen, reopened.state.value.activePage.keys)
        assertEquals(chosen, DeckRepository(file).state.value.activePage.keys)
    }

    @Test fun renamedV1AudioRootRetainsItsLegacyCommandsOnLoadAndImport() {
        repo.renamePage("audio", "root", "My transport").getOrThrow()
        val old = JSONObject(repo.exportJson()).put("version", 1).toString()
        fun play(deck: DeckRepository) = deck.state.value.profiles.single { it.id == "audio" }.pages.single().keys[1].action
        file.writeText(old)
        assertEquals(ActionCall("media.play-pause"), play(DeckRepository(file)))
        repo.importJson(old).getOrThrow()
        assertEquals(ActionCall("media.play-pause"), play(repo))
    }

    @Test fun intermediateV2UpdatesNavigationWithoutReapplyingTheAudioMigration() {
        val copy = repo.duplicateProfile("audio", "My v2 player").getOrThrow()
        val exported = JSONObject(repo.exportJson()).put("version", 2)
        val profiles = exported.getJSONArray("profiles")
        val nav = (0 until profiles.length()).map(profiles::getJSONObject).single { it.getString("id") == "navigation" }
        nav.put("pages", historicalNavigation().getJSONArray("profiles").getJSONObject(0).getJSONArray("pages"))
        repo.importJson(exported.toString()).getOrThrow()
        val result = repo.state.value.profiles.associateBy { it.id }
        assertEquals(listOf(0, 3, 1, 6, 4, 8, 2, 5, 7).map { pluginKey("navigation", it) },
            result.getValue("navigation").pages.single().keys)
        assertEquals((0..8).map { pluginKey("audio", it) }, result.getValue(copy).pages.single().keys)
    }

    @Test fun anyEditToTheHistoricalNavigationRootPreservesAllNineBindingsOnLoadAndImport() {
        val state = DeckJson.decode(historicalNavigation().toString())
        val keys = (0..8).map { pluginKey("navigation", it) }
        val root = DeckPage("root", "Navigation", keys)
        val edits = listOf(keys[1].copy(enabled = false), keys[1].copy(label = "Mine"), keys[1].copy(icon = "X"),
            keys[1].copy(color = 1), keys[7], DeckKey(action = ActionCall("navigation.home")))
        for (page in edits.map { root.copy(keys = keys.toMutableList().apply { set(1, it) }) } + root.copy(name = "Mine")) {
            val old = DeckJson.encode(state.copy(version = 1, profiles = listOf(state.activeProfile.copy(pages = listOf(page)))))
            file.writeText(old)
            val loaded = DeckRepository(file)
            assertNull(loaded.loadError)
            assertEquals(page, loaded.state.value.activePage)
            loaded.restoreDefaults().getOrThrow()
            loaded.importJson(old).getOrThrow()
            assertEquals(page, loaded.state.value.activePage)
        }
    }

    @Test fun savesUnicodeBindingsRulesAndNavigationWithoutLeakingMutableReferences() {
        val id = repo.createProfile("Travail 🐈").getOrThrow()
        val page = repo.createPage(id, "🌿").getOrThrow()
        val params = mutableMapOf("message" to "旅\n🌎")
        val key = DeckKey("☕", "休憩", 0xffc91f8a.toInt(), ActionCall("example.message", params), false)
        repo.setKey(id, page, 7, key).getOrThrow()
        params.clear()
        repo.swapKeys(id, page, 7, 2).getOrThrow()
        repo.setPinned(id).getOrThrow()
        repo.setDefault(id).getOrThrow()
        repo.setProfileAutomatic(id, false).getOrThrow()
        repo.setAutomatic(true).getOrThrow()
        repo.setRules(id, listOf(DeckRule("hour", "22-5", 5))).getOrThrow()
        repo.openPage(page).getOrThrow()
        val reloaded = DeckRepository(file)
        val state = reloaded.state.value
        assertEquals(repo.state.value, state)
        assertEquals("旅\n🌎", state.activePage.keys[2].action!!.params["message"])
        assertFalse(state.activePage.keys[2].enabled)
        assertEquals(id, state.pinnedProfileId)
        assertTrue(reloaded.reason.value.contains("pinned"))
        assertThrows(UnsupportedOperationException::class.java) { (state.activePage.keys as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (state.histories as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (state.activePage.keys[2].action!!.params as MutableMap).clear() }

        val exported = repo.exportJson()
        repo.restoreDefaults().getOrThrow()
        assertEquals("focus", repo.state.value.activeProfileId)
        repo.importJson(exported).getOrThrow()
        assertEquals(state, repo.state.value)
    }

    @Test fun invalidImportIsAtomicAndUnpublishedEvenForDeepOrOversizedInput() {
        repo.renameProfile("studio", "My studio").getOrThrow()
        val before = repo.state.value
        val original = repo.exportJson()
        fun edit(f: (JSONObject) -> Unit) = JSONObject(original).apply(f).toString()
        val bad = listOf(
            edit { it.put("version", DeckLimits.VERSION + 1) },
            original.replaceFirst("\"version\":", "\"version\":${DeckLimits.VERSION},\"version\":"),
            edit { it.put("automatic", "true") }, edit { it.put("activeProfileId", "missing") },
            edit { it.getJSONArray("profiles").getJSONObject(0).put("id", "audio") },
            edit { it.getJSONObject("histories").getJSONArray("focus").put("missing") },
            edit { it.getJSONArray("profiles").getJSONObject(0).getJSONArray("pages").getJSONObject(0).getJSONArray("keys").remove(0) },
            edit { it.keyAt(0).put("action", JSONObject().put("id", "page.open").put("params", JSONObject().put("pageId", "missing"))) },
            edit { it.keyAt(0).put("color", 1.5) }, edit { it.keyAt(0).put("icon", "a".repeat(33)) },
            edit { it.keyAt(0).getJSONObject("action").getJSONObject("params").put("index", "9") },
            original.replace("My studio", "invalid\\escape"),
            original.replace("My studio", "invalid\nnewline"),
            " ".repeat(DeckLimits.MAX_BYTES + 1), "[".repeat(4096) + "]".repeat(4096), "$original 42",
        )
        for (input in bad) {
            assertTrue("Accepted invalid input", repo.importJson(input).isFailure)
            assertSame(before, repo.state.value)
            assertEquals(original, file.readText())
        }
    }

    @Test fun upgradeOnlyReordersStockAudioAndPreservesEditedSlotBindings() {
        val copy = repo.duplicateProfile("audio", "My player").getOrThrow()
        val custom = DeckKey("X", "Custom action", action = ActionCall("media.unmute"))
        repo.setKey(copy, "root", 2, custom).getOrThrow()
        repo.importJson(JSONObject(repo.exportJson()).put("version", 1).toString()).getOrThrow()
        val stock = repo.state.value.profiles.single { it.id == "audio" }.pages.single().keys
        assertTrue(stock.all { it.action!!.id == "plugin.key" })
        val saved = repo.state.value.profiles.single { it.id == copy }.pages.single().keys
        assertEquals(custom, saved[2])
        assertEquals(listOf("media.volume-down","media.play-pause","media.unmute","media.previous","media.access",
            "media.next","media.mute","media.volume-level","media.unmute"), saved.map { it.action!!.id })
        assertEquals(repo.state.value, DeckRepository(file).state.value)
    }

    @Test fun failedWritesAndCorruptStartupDoNotClobberLastData() {
        file.writeText("{broken")
        val recovery = DeckRepository(file)
        assertNotNull(recovery.loadError)
        assertTrue(recovery.save().isFailure)
        assertTrue(recovery.renameProfile("focus", "Overwrite").isFailure)
        assertTrue(recovery.importJson("invalid").isFailure)
        assertEquals("{broken", file.readText())
        recovery.restoreDefaults().getOrThrow()
        assertNull(recovery.loadError)
        assertNull(DeckRepository(file).loadError)

        val missingDirectory = DeckRepository(File(folder.root, "does-not-exist/deck.json"))
        val previous = missingDirectory.state.value
        assertTrue(missingDirectory.deleteProfile("audio").isFailure)
        assertSame(previous, missingDirectory.state.value)
    }

    @Test fun nestedNavigationDuplicationAndDeletionPreserveValidHistoryAndLinks() {
        repo.selectProfile("audio").getOrThrow()
        val child = repo.createPage("audio", "One").getOrThrow()
        val leaf = repo.duplicatePage("audio", child, "Two").getOrThrow()
        fun link(page: String) = DeckKey(action = ActionCall("page.open", mapOf("pageId" to page)))
        repo.setKey("audio", "root", 8, link(child)).getOrThrow()
        repo.openPage(child).getOrThrow()
        repo.openPage(leaf).getOrThrow()
        repo.selectProfile("phone").getOrThrow()
        repo.selectProfile("audio").getOrThrow()
        assertEquals(leaf, repo.state.value.activePage.id)
        repo.back().getOrThrow()
        assertEquals(child, repo.state.value.activePage.id)
        repo.openPage(leaf).getOrThrow()
        repo.openPage(child).getOrThrow()
        assertEquals(listOf("root", child), repo.state.value.histories["audio"])
        val duplicate = repo.duplicateProfile("audio", "My audio").getOrThrow()
        repo.deletePage("audio", child).getOrThrow()
        assertNull(repo.state.value.activePage.keys[8].action)
        repo.selectProfile(duplicate).getOrThrow()
        assertEquals(link(child), repo.state.value.activePage.keys[8])
        repo.renamePage(duplicate, child, "Changed").getOrThrow()
        repo.openPage(child).getOrThrow()
        assertEquals("Changed", repo.state.value.activePage.name)
        repo.root().getOrThrow()
        assertTrue(repo.deletePage(duplicate, "root").isFailure)
        repo.setKey("focus", "root", 0, DeckKey(action = ActionCall("profile.switch", mapOf("profileId" to duplicate)))).getOrThrow()
        repo.setPinned(duplicate).getOrThrow()
        repo.deleteProfile(duplicate).getOrThrow()
        assertEquals("focus", repo.state.value.activeProfileId)
        assertNull(repo.state.value.pinnedProfileId)
        assertNull(repo.state.value.activePage.keys[0].action)
        assertEquals(repo.state.value, DeckRepository(file).state.value)
    }

    @Test fun scoringNeedsContinuousObservedMatchesAndAWinningMargin() {
        val music = DeckRule("mediaActive", "true", 3)
        val overnight = DeckRule("hour", "22-5", 4)
        val dock = DeckRule("dock", "desk", 1)
        repo.setAutomatic(true).getOrThrow()
        repo.setRules("audio", listOf(music)).getOrThrow()
        repo.setRules("studio", listOf(overnight, dock)).getOrThrow()
        val media = mapOf("mediaActive" to "true")
        fun sample(time: Long, signals: Map<String, String>) = repo.observe(signals, time).getOrThrow()
        sample(0, media)
        sample(1200, emptyMap())
        sample(1300, media)
        sample(2200, media)
        assertEquals("focus", repo.state.value.activeProfileId)
        sample(2800, media)
        assertEquals("audio", repo.state.value.activeProfileId)
        val whyAudio = repo.reason.value
        assertTrue(whyAudio.contains("mediaActive=true"))
        sample(3000, media + ("hour" to "2"))
        sample(6000, media + ("hour" to "2"))
        assertEquals("audio", repo.state.value.activeProfileId)
        assertEquals(whyAudio, repo.reason.value)
        val studio = media + mapOf("hour" to "23", "dock" to "desk")
        sample(7000, studio)
        assertEquals("studio", repo.decision.value.candidateId)
        assertEquals(5, repo.decision.value.scores["studio"])
        assertEquals(listOf(overnight, dock), repo.decision.value.matches["studio"])
        sample(8500, studio)
        assertEquals("studio", repo.state.value.activeProfileId)
        val whyStudio = repo.reason.value
        assertEquals(whyStudio, DeckRepository(file).reason.value)
        // Out-of-range or absent observations don't match; a fallback must stabilize too.
        sample(8800, mapOf("hour" to "99"))
        sample(9500, emptyMap())
        assertEquals(whyStudio, repo.reason.value)
        sample(10300, emptyMap())
        assertEquals("focus", repo.state.value.activeProfileId)
        assertTrue(repo.reason.value.contains("default"))
        assertTrue(repo.setRules("focus", listOf(DeckRule("hour", "4-24"))).isFailure)
    }

    @Test fun explicitSelectionPinAndOptOutCannotBeOverriddenByAnOldCandidate() {
        val media = mapOf("mediaActive" to "true")
        repo.setAutomatic(true).getOrThrow()
        repo.observe(media, 1).getOrThrow()
        repo.setPinned("phone").getOrThrow()
        repo.observe(media, 5000).getOrThrow()
        assertEquals("phone", repo.state.value.activeProfileId)
        assertTrue(repo.selectProfile("audio", SelectionSource.AUTOMATIC).isFailure)
        repo.selectProfile("focus").getOrThrow()
        assertEquals("focus", repo.state.value.pinnedProfileId)
        repo.setPinned(null).getOrThrow()
        repo.setProfileAutomatic("focus", false).getOrThrow()
        repeat(2) { repo.observe(media, 9000L * it).getOrThrow() }
        assertEquals("focus", repo.state.value.activeProfileId)
        repo.setProfileAutomatic("focus", true).getOrThrow()
        repo.setProfileAutomatic("audio", false).getOrThrow()
        repeat(2) { repo.observe(media, 9000L * it).getOrThrow() }
        assertEquals("focus", repo.state.value.activeProfileId)
        repo.setProfileAutomatic("audio", true).getOrThrow()
        repo.observe(media, 9000L).getOrThrow()
        repo.clearObservation()
        repo.observe(media, 18000L).getOrThrow()
        assertEquals("focus", repo.state.value.activeProfileId)
        repo.setAutomatic(false).getOrThrow()
        repo.observe(media, 20000L).getOrThrow()
        assertEquals("focus", repo.state.value.activeProfileId)
    }

    private fun JSONObject.keyAt(index: Int): JSONObject = getJSONArray("profiles").getJSONObject(0)
        .getJSONArray("pages").getJSONObject(0).getJSONArray("keys").getJSONObject(index)
}
