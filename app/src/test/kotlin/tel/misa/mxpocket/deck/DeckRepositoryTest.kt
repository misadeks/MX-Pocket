package tel.misa.mxpocket.deck

import android.app.Application
import java.io.File
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
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

    @Test fun editorCannotSaveOverAChangedKeyRulesOrActiveHistory() = runBlocking {
        val editor = repo.asEditorStore()
        val original = repo.state.value.activePage.keys[0]
        val concurrent = original.copy(label = "New assignment")
        repo.setKey("focus", "root", 0, concurrent).getOrThrow()
        repo.setRules("focus", listOf(DeckRule("dock", "studio"))).getOrThrow()
        repo.selectProfile("audio").getOrThrow()
        val snapshot = repo.state.value
        assertTrue(editor.edit(DeckEdit.UpdateKey("focus", "root", 0, original, original.copy(label = "Old draft"))).isFailure)
        assertTrue(editor.edit(DeckEdit.Rules("focus", emptyList(), listOf(DeckRule("hour", "10")))).isFailure)
        assertTrue(editor.edit(DeckEdit.Root("focus")).isFailure)
        assertSame(snapshot, editor.state.value)
        assertEquals(snapshot, DeckRepository(file).state.value)
    }

    @Test fun addsXoProfileToAnExistingLayoutWithoutChangingSelectionOrKeys() {
        val factory = defaultDeck()
        val oldLayout = factory.copy(
            profiles = factory.profiles.filterNot { it.id == "game.xo" },
            activeProfileId = "phone",
            defaultProfileId = "audio",
            histories = factory.histories - "game.xo",
        )
        file.writeText(DeckJson.encode(oldLayout))

        val upgraded = DeckRepository(file).state.value
        assertEquals("phone", upgraded.activeProfileId)
        assertEquals("audio", upgraded.defaultProfileId)
        assertEquals(ActionCall("plugin.key", mapOf("pluginId" to "phone", "index" to "0")),
            upgraded.activePage.keys[0].action)
        assertTrue(upgraded.profiles.any { it.id == "game.xo" })
        assertTrue("game.xo" in upgraded.histories)
    }

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
        val expected = ActionCall("media.play-pause", mapOf(DeckActions.LEGACY_SLOT_OWNER to "audio"))
        assertEquals(expected, play(DeckRepository(file)))
        repo.importJson(old).getOrThrow()
        assertEquals(expected, play(repo))
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

    @Test fun failedPrivacyRewriteKeepsTheDecodedCustomDeckAndCanRetry() {
        val custom = repo.createProfile("My controls").getOrThrow()
        repo.selectProfile(custom).getOrThrow()
        val pkg = "example.privateplayer"
        val legacy = JSONObject(repo.exportJson()).put("selectionReason", "Automatic: score 5 — foregroundApp=$pkg (+5)").toString()
        file.writeText(legacy)
        // Reads still see the valid file; simulate a missing staging directory for atomic writes.
        var allowWrite = false
        val target = object : File(file.absolutePath) {
            override fun toPath(): Path = if (allowWrite) super.toPath() else super.toPath().resolve("missing/deck.json")
        }
        val recovered = DeckRepository(target)
        assertNull(recovered.loadError)
        assertNotNull(recovered.cleanupWarning)
        assertFalse(recovered.cleanupWarning!!.contains(pkg))
        assertEquals(custom, recovered.state.value.activeProfileId)
        assertFalse(recovered.exportJson().contains(pkg))
        assertEquals(legacy, file.readText())
        allowWrite = true
        recovered.save().getOrThrow()
        assertNull(recovered.cleanupWarning)
        assertFalse(file.readText().contains(pkg))
        assertEquals(custom, DeckRepository(file).state.value.activeProfileId)
    }

    @Test fun removingAnAppMappingLeavesNoPackageInTheSavedOrExportedReason() {
        val pkg = "example.privateplayer"
        val rule = DeckRule("foregroundApp", pkg, 5)
        repo.setRules("studio", listOf(rule)).getOrThrow()
        repo.setAutomatic(true).getOrThrow()
        for (time in listOf(0L, 2000L)) repo.observe(mapOf(rule.signal to pkg), time).getOrThrow()
        assertEquals("studio", repo.state.value.activeProfileId)
        assertEquals(listOf(rule), repo.decision.value.matches["studio"])
        assertFalse(repo.reason.value.contains(pkg))
        repo.setAutomatic(false).getOrThrow()
        repo.setRules("studio", emptyList()).getOrThrow()
        assertFalse(repo.exportJson().contains(pkg))
        assertFalse(file.readText().contains(pkg))
        assertFalse(DeckRepository(file).exportJson().contains(pkg))

        val legacy = JSONObject(file.readText()).put("selectionReason", "Automatic: score 5 — foregroundApp=$pkg (+5)").toString()
        file.writeText(legacy)
        val restarted = DeckRepository(file)
        assertEquals("studio", restarted.state.value.activeProfileId)
        assertFalse(file.readText().contains(pkg))
        restarted.importJson(legacy).getOrThrow()
        assertFalse(restarted.exportJson().contains(pkg))
        assertFalse(file.readText().contains(pkg))
    }

    @Test fun deletedXoStaysDeletedAcrossColdLoadAndBackupRestore() {
        repo.selectProfile("phone").getOrThrow()
        repo.deleteProfile("game.xo").getOrThrow()
        val expected = repo.state.value
        assertFalse(expected.profiles.any { it.id == "game.xo" })
        val reloaded = DeckRepository(file)
        assertEquals(expected, reloaded.state.value)
        val backup = reloaded.exportJson()
        repo.restoreDefaults().getOrThrow()
        repo.importJson(backup).getOrThrow()
        repo.save().getOrThrow()
        assertEquals(expected, DeckRepository(file).state.value)
    }

    @Test fun optionalXoDoesNotMakeAnAtLimitLegacyDeckUnsavable() {
        // Fill action text with multibyte UTF-8 to exercise the byte rather than character limit.
        fun layout(payloadBytes: Int): DeckState {
            var remaining = payloadBytes
            val pages = List(2) { page ->
                DeckPage("p$page", "Page $page", List(9) {
                    val params = (1..10).associate { index ->
                        val count = minOf(remaining, 3000)
                        remaining -= count
                        "p$index" to ("旅".repeat(count / 3) + "x".repeat(count % 3))
                    }
                    DeckKey(action = ActionCall("example.message", params))
                })
            }
            check(remaining == 0)
            val profile = DeckProfile("custom", "Custom", "p0", pages)
            return defaultDeck().copy(profiles = listOf(profile), activeProfileId = profile.id,
                defaultProfileId = profile.id, histories = mapOf(profile.id to listOf("p0", "p1")))
        }
        val expected = layout(DeckLimits.MAX_BYTES - DeckJson.encode(layout(0)).toByteArray().size)
        val original = DeckJson.encode(expected)
        assertEquals(DeckLimits.MAX_BYTES, original.toByteArray().size)
        file.writeText(original)

        val reloaded = DeckRepository(file)
        assertNull(reloaded.loadError)
        assertEquals(expected, reloaded.state.value)
        val exported = reloaded.exportJson()
        reloaded.save().getOrThrow()
        reloaded.restoreDefaults().getOrThrow()
        reloaded.importJson(exported).getOrThrow()
        reloaded.save().getOrThrow()
        assertEquals(expected, DeckRepository(file).state.value)
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
            edit { it.put("xoProfileRemoved", "true") },
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

    @Test fun exactLimitV1AudioSlotsRemainExportableAfterColdLoadAndImport() {
        // Build literal v1 input, not a current-schema export. Fill preserved action parameters,
        // not JSON whitespace that the encoder could remove to make the migration appear to fit.
        val pages = org.json.JSONArray()
        val fillers = mutableListOf<JSONObject>()
        repeat(18) { page ->
            val keys = org.json.JSONArray()
            repeat(9) { i ->
                val action = if (page == 0) {
                    JSONObject("""{"id":"plugin.key","params":{"pluginId":"audio","index":"${if (i == 0) 0 else 7}"}}""")
                } else {
                    val params = JSONObject().also { p -> repeat(4) { p.put("p$it", "") } }
                    fillers += params
                    JSONObject().put("id","custom.action").put("params",params)
                }
                keys.put(JSONObject().put("icon","").put("label","音").put("color",0).put("action",action).put("enabled",true))
            }
            pages.put(JSONObject().put("id","p$page").put("name","Page $page").put("keys",keys))
        }
        val profile = JSONObject("""{"id":"custom","name":"Custom","rootPageId":"p0","automatic":false,"rules":[]}""")
            .put("pages",pages)
        val v1 = JSONObject("""{"version":1,"activeProfileId":"custom","defaultProfileId":"custom","pinnedProfileId":null,
            "automatic":false,"histories":{"custom":["p0"]},"selectionReason":"Manual"}""")
            .put("profiles",org.json.JSONArray().put(profile))
        var remaining = DeckLimits.MAX_BYTES - v1.toString().toByteArray().size
        for (params in fillers) for (i in 0..3) {
            val n = minOf(remaining, DeckLimits.PARAM_VALUE)
            params.put("p$i","x".repeat(n)); remaining -= n
        }
        val input = v1.toString()
        assertEquals(DeckLimits.MAX_BYTES,input.toByteArray().size)
        file.writeText(input)
        val cold = DeckRepository(file)
        assertNull(cold.loadError)
        val exported = cold.exportJson()
        assertTrue(exported.toByteArray().size <= DeckLimits.MAX_BYTES)
        val savedKeys = cold.state.value.activePage.keys
        assertEquals("media.volume-down",savedKeys.first().action!!.id)
        assertEquals("media.volume-level",savedKeys.last().action!!.id)
        assertTrue(savedKeys.all { it.action!!.slotOwner() == "audio" })
        val imported = DeckRepository(File(folder.root,"imported.json"))
        imported.importJson(input).getOrThrow()
        imported.save().getOrThrow()
        assertEquals(exported,imported.exportJson())
        assertEquals(cold.state.value,DeckRepository(File(folder.root,"imported.json")).state.value)
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
