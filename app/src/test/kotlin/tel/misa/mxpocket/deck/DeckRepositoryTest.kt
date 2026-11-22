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
import tel.misa.mxpocket.decks.CallStatus
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.i18n.AppLanguage
import tel.misa.mxpocket.i18n.AppLocale
import tel.misa.mxpocket.i18n.displaySelectionReason

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeckRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private val file: File get() = File(folder.root, "deck.json")
    private val repo by lazy { DeckRepository(file) }

    @Test fun stockPhoneIsNotAChoiceButOldTargetsAndCustomizationsSurvive() {
        fun choices(repo: DeckRepository) = repo.state.value.navigationProfiles.map { it.id }
        assertFalse(repo.state.value.profiles.any { it.id == "phone" })
        repo.importLegacyPhone()
        val target = DeckKey(holdAction = ActionCall(DeckActions.PROFILE, mapOf("profileId" to "phone")))
        repo.setKey("focus", "root", 0, target).getOrThrow()
        val backup = repo.exportJson()
        val cold = DeckRepository(file)
        assertFalse("phone" in choices(cold))
        assertFalse(cold.operatingState.value.activePage.keys.any { it.action?.targetProfileId() == "phone" })
        assertEquals(backup, cold.exportJson())
        assertEquals(target, cold.state.value.profiles.single { it.id == "focus" }.pages.first().keys.first())
        cold.selectProfile("audio").getOrThrow()
        cold.changePage(1).getOrThrow()
        assertEquals("launcher", cold.operatingState.value.activeProfileId)
        cold.selectProfile("phone").getOrThrow()
        cold.changePage(-1).getOrThrow()
        assertEquals(PROFILE_HOME, cold.operatingState.value.activeProfileId)
        val beforeDisabling = cold.exportJson()
        cold.setDisabledPlugins(choices(cold).toSet())
        assertTrue(cold.operatingState.value.activePage.keys.any { it.action != null })
        assertFalse("phone" in choices(cold))
        assertEquals(beforeDisabling, cold.exportJson())
        cold.setDisabledPlugins(emptySet())
        val copy = cold.duplicateProfile("phone", "Phone").getOrThrow()
        assertTrue(copy in choices(cold))
        val child = cold.createPage("phone", "Tools").getOrThrow()
        assertTrue("phone" in choices(cold))
        cold.deletePage("phone", child).getOrThrow()
        cold.setKey("phone", "root", 4, target).getOrThrow()
        assertTrue("phone" in choices(DeckRepository(file)))
        cold.restoreDefaults().getOrThrow()
        assertFalse(cold.state.value.profiles.any { it.id == "phone" })
    }

    @Test fun factoryDefaultsStartOnLauncherAndKeepStudioSelected() {
        assertEquals("launcher", repo.state.value.activeProfileId)
        assertEquals("launcher", repo.state.value.defaultProfileId)
        assertFalse(repo.state.value.profiles.single { it.id == "studio" }.automatic)
        repo.selectProfile("studio").getOrThrow()
        repo.observe(emptyMap(), 0).getOrThrow()
        repo.observe(emptyMap(), 3000).getOrThrow()
        assertEquals("studio", repo.state.value.activeProfileId)
        val restored = DeckRepository(file)
        assertEquals("studio", restored.state.value.activeProfileId)
        assertEquals("launcher", restored.state.value.defaultProfileId)
    }

    @Test fun localizedReasonsAndValidationNeverChangeExportsOrRuleValues() {
        val previous = AppLocale.current
        try {
            AppLocale.update(AppLanguage.FRENCH, emptyList())
            repo.selectProfile("audio").getOrThrow()
            assertEquals("Sélection manuelle", displaySelectionReason(repo.reason.value))
            assertEquals("Manually selected", JSONObject(repo.exportJson()).getString("selectionReason"))
            val before = repo.exportJson()
            assertEquals("Page inconnue", repo.openPage("missing").exceptionOrNull()?.message)
            AppLocale.update(AppLanguage.SERBIAN_LATIN, emptyList())
            assertEquals("Nepoznata stranica", repo.openPage("missing").exceptionOrNull()?.message)
            assertEquals(before, repo.exportJson())
            val details = "dock=My {0} desk (+5)"
            assertEquals("Automatski: broj bodova 5 — $details", displaySelectionReason("Automatic: score 5 — $details"))
            val unknown = "Automatic: score 5 — my own note"
            assertEquals(unknown, displaySelectionReason(unknown))
        } finally {
            AppLocale.update(previous, emptyList())
        }
    }

    @Test fun workEntryYieldsToConfirmedCallAndExplicitMappings() {
        val foreground = mapOf("foregroundApp" to "com.microsoft.teams")
        fun observe(time: Long) = repo.observe(foreground, time, allowTeamsWork = true).getOrThrow()
        repo.showTeamsCall(CallStatus.JOINED)
        observe(0); observe(2000)
        assertEquals("in-call", repo.state.value.activePage.id)
        repo.selectProfile("audio").getOrThrow()
        repo.showTeamsCall(CallStatus.NO_SIGNAL); observe(4000); observe(6000)
        assertEquals("audio", repo.state.value.activeProfileId)
        repo.observe(emptyMap(), 7000).getOrThrow()
        repo.setProfileAutomatic("studio", true).getOrThrow()
        repo.setRules("studio", listOf(DeckRule("foregroundApp", "com.microsoft.teams", 5))).getOrThrow()
        observe(8000); observe(10000)
        assertEquals("studio", repo.state.value.activeProfileId)
    }

    @Test fun homeAndGameYieldOnlyToFreshAuthorizedTeamsEntries() {
        val fg = mapOf("foregroundApp" to "com.microsoft.teams")
        fun sample(t: Long) = repo.observe(fg, t, allowTeamsWork = true).getOrThrow()
        fun surface() = repo.operatingState.value.activeProfileId
        assertEquals(PROFILE_HOME, surface())
        repo.setDisabledPlugins(setOf("teams"))
        assertFalse(repo.operatingState.value.visibleProfiles.any { it.id == "teams" })
        sample(0); sample(2000); assertEquals(PROFILE_HOME, surface())
        repo.observe(emptyMap(), 2500).getOrThrow()
        repo.setDisabledPlugins(emptySet())
        sample(3000); assertEquals(PROFILE_HOME, surface())
        sample(5000); assertEquals("teams", surface())
        assertEquals("Work", repo.operatingState.value.activePage.name)
        repo.showHome()
        sample(6000); sample(8000); assertEquals(PROFILE_HOME, surface())
        repo.observe(emptyMap(), 9000).getOrThrow()
        sample(10000); sample(12000); assertEquals("teams", surface())
        repo.enterGame().getOrThrow()
        repo.showTeamsCall(CallStatus.JOINED)
        sample(14000); repo.showTeamsCall(CallStatus.JOINED); assertEquals(GAME_PROFILE, surface())
        assertTrue(repo.operatingState.value.activePage.keys.all { it.action?.params?.get("pluginId") == GAME_PROFILE })
        repo.changePage(1).getOrThrow()
        assertEquals("root", repo.operatingState.value.activePage.id)
        repo.showTeamsCall(CallStatus.NO_SIGNAL)
    }

    @Test fun oldTeamsWorkLosesOnlyItsExactTimerBindingAndNewEditsSurviveReload() {
        val old = DeckKey(label = "Focus timer", action = ActionCall(DeckActions.PROFILE, mapOf(DeckActions.PROFILE_ID to "focus")))
        fun keys(repo: DeckRepository) = repo.state.value.profiles.single { it.id == "teams" }.pages.first().keys
        val factory = keys(repo)
        assertEquals(DeckKey(), factory[6])
        repo.setKey("teams", "root", 6, old).getOrThrow()
        fun legacyFile() = file.writeText(JSONObject(repo.exportJson()).put("version", 3).toString())
        legacyFile()
        assertEquals(factory, keys(DeckRepository(file)))
        val customized = old.copy(holdAction = ActionCall(DeckActions.ROOT))
        repo.setKey("teams", "root", 6, customized).getOrThrow()
        legacyFile()
        assertEquals(customized, keys(DeckRepository(file))[6])
        repo.setKey("teams", "root", 6, old).getOrThrow()
        assertEquals(old, keys(DeckRepository(file))[6]) // deliberately restored in the new format
    }

    @Test fun callProjectionRestoresExactPageAndNeverOverwritesAnExplicitChoice() {
        repo.setProfileAutomatic("studio", true).getOrThrow()
        val child = repo.createPage("studio", "Tools").getOrThrow()
        repo.selectProfile("studio").getOrThrow()
        repo.openPage(child).getOrThrow()
        val before = repo.exportJson()
        repo.showTeamsCall(CallStatus.JOINED)
        assertEquals("in-call", repo.state.value.activePage.id)
        assertEquals(before, repo.exportJson())
        repo.renamePage("studio", child, "My tools").getOrThrow()
        repo.showTeamsCall(CallStatus.NO_SIGNAL)
        assertEquals(child, repo.state.value.activePage.id)
        assertEquals("My tools", repo.state.value.activePage.name)
        repo.showTeamsCall(CallStatus.JOINED)
        repo.openPage("root").getOrThrow()
        repo.showTeamsCall(CallStatus.JOINED)
        repo.showTeamsCall(CallStatus.NO_SIGNAL)
        assertEquals("Work", repo.state.value.activePage.name)
        repo.setPinned("audio").getOrThrow()
        repo.showTeamsCall(CallStatus.JOINED)
        assertEquals("audio", repo.state.value.activeProfileId)
        repo.showTeamsCall(CallStatus.NO_SIGNAL)
        repo.setPinned(null).getOrThrow()
        repo.deletePage("teams", "in-call").getOrThrow()
        repo.showTeamsCall(CallStatus.JOINED)
        assertEquals("audio", repo.state.value.activeProfileId)
        assertEquals(1, DeckRepository(file).state.value.profiles.single { it.id == "teams" }.pages.size)
        repo.deleteProfile("teams").getOrThrow()
        assertFalse(DeckRepository(file).state.value.profiles.any { it.id == "teams" })
    }

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
        repo.importLegacyPhone()
        val factory = repo.state.value
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

    @Test fun historicalPhoneSlotsAndExplicitAppCallsRoundTripWithoutRebinding() {
        val custom = repo.createProfile("My shortcuts").getOrThrow()
        val stock = (0..8).map { pluginKey("phone", it) }
        val apps = listOf("WEB", "ONENOTE", "ALARMS", "DIALER", "SETTINGS", "CAMERA")
        val keys = stock.reversed().mapIndexed { i, slot ->
            val params = if (i == 0) mapOf("app" to "WEB", "url" to "https://example.com")
                else mapOf("app" to apps[i % apps.size])
            val app = ActionCall("phone.open", params)
            if (i % 2 == 0) DeckKey(label = "My key", action = app, holdAction = slot.action)
            else slot.copy(holdAction = app)
        }
        keys.forEachIndexed { i, key -> repo.setKey(custom, "root", i, key).getOrThrow() }
        val backup = JSONObject(repo.exportJson()).put("version", 1).toString()
        repo.importJson(backup).getOrThrow()
        fun phoneKeys(state: DeckState) = state.profiles.single { it.id == custom }.pages.single().keys
        assertEquals(keys, phoneKeys(DeckRepository(file).state.value))
        assertEquals(keys, phoneKeys(DeckJson.decode(repo.exportJson())))
    }

    @Test fun selectedSlotOwnerPrefersRegisteredTapAcrossTheWholePageBeforeHold() {
        fun slot(id: String) = pluginKey(id, 0).action
        val page = DeckPage("root", "Controls", listOf(
            DeckKey(action = slot("missing"), holdAction = slot("focus")),
            DeckKey(holdAction = slot("phone")),
            DeckKey(action = ActionCall("media.next", mapOf(DeckActions.LEGACY_SLOT_OWNER to "audio"))),
            DeckKey(action = slot("navigation")),
        ) + List(5) { DeckKey() })
        assertEquals("audio", page.selectedSlotOwner(setOf("focus", "audio", "navigation")::contains))
        assertEquals("focus", page.selectedSlotOwner(setOf("focus", "phone")::contains))
        assertEquals("phone", page.selectedSlotOwner { it == "phone" })
        assertNull(page.selectedSlotOwner { false })
    }

    @Test fun existingGameDisplaysNewNameWithoutRenamingCustomProfilesPagesOrKeys() {
        val factory = defaultDeck()
        val old = factory.profiles.single { it.id == "game.xo" }
        val root = old.pages.single().copy(name = "X-O", keys = old.pages.single().keys.map { it.copy(label = "X-O") })
        val extra = root.copy(id = "other")
        val game = old.copy(name = "X-O", pages = listOf(root, extra))
        val other = game.copy(id = "custom")
        val state = factory.copy(activeProfileId = game.id, profiles = factory.profiles.filter { it.id != game.id } + game + other,
            histories = factory.histories + ("custom" to listOf("root")))
        file.writeText(DeckJson.encode(state))
        val loaded = DeckRepository(file)
        assertEquals(game.copy(name = "Tic Tac Toe", pages = listOf(root.copy(name = "Tic Tac Toe"), extra)),
            loaded.state.value.profiles.single { it.id == game.id })
        assertFalse(loaded.state.value.visibleProfiles.any { it.id == game.id })
        assertEquals(other, loaded.state.value.profiles.single { it.id == "custom" })
        assertTrue(loaded.renameProfile(game.id, "Game night").isFailure)
        assertTrue(loaded.renamePage(game.id, "root", "Board").isFailure)
        loaded.selectProfile(other.id).getOrThrow()
        loaded.renameProfile(other.id, "Game night").getOrThrow()
        loaded.renamePage(other.id, "root", "Board").getOrThrow()
        val restored = DeckRepository(file).state.value
        assertEquals("Game night", restored.activeProfile.name)
        assertEquals("Board", restored.activePage.name)
    }

    @Test fun freshAndRestoredNavigationDefaultsUsePhysicalOrderWithoutChangingOtherDecks() {
        fun checkDefaults() {
            for (profile in repo.state.value.profiles) {
                if (profile.id == "teams") {
                    assertEquals(listOf("Work", "In call"), profile.pages.map { it.name })
                    assertEquals(pluginKey("teams", 4), profile.pages.last().keys[4])
                    continue
                }
                if (profile.id == "navigation") {
                    assertEquals(expectedNavigationRoot(), profile.pages.first().keys)
                    assertEquals(ActionCall(DeckActions.ROOT), profile.pages.single { it.id == ACCESSIBILITY_PAGE }.keys[8].action)
                } else assertEquals(profile.id, (0..8).map { pluginKey(profile.id, it) }, profile.pages.single().keys)
            }

        }
        checkDefaults()
        repo.setKey("navigation", "root", 1, DeckKey()).getOrThrow()
        repo.restoreDefaults().getOrThrow()
        checkDefaults()
    }

    private fun expectedNavigationRoot() = listOf(0, 1, 2, 6, 4, 8, 3, 5).map { pluginKey("navigation", it) } +
        DeckKey(label = "More", action = ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to "accessibility")))

    @Test fun savedTalkBackFactoryKeepsItsActionsAcrossColdLoadAndBackupImport() {
        val original = requireNotNull(javaClass.getResource("/navigation-pr77.json")).readText()
        file.writeText(original)
        val loaded = DeckRepository(file)
        assertNull(loaded.loadError)
        fun root(repository: DeckRepository) = repository.state.value.profiles.single { it.id == "navigation" }.pages.first()
        fun actions(page: DeckPage) = page.keys.map { key ->
            val call = requireNotNull(key.action)
            if (call.id != "plugin.key") call.id else
                tel.misa.mxpocket.decks.navigationCommands[call.params.getValue("index").toInt()].id
        }
        assertEquals(listOf("navigation.back", "navigation.home", "navigation.recents",
            "navigation.focus-previous", "navigation.activate", "navigation.focus-next",
            "navigation.scroll-up", "navigation.scroll-down", DeckActions.PAGE), actions(root(loaded)))
        loaded.importJson(original).getOrThrow()
        val saved = loaded.exportJson()
        assertEquals(saved, DeckRepository(file).exportJson())
        // The same v5 index can also be a deliberate binding. A change to either page vetoes the migration.
        for (pageIndex in 0..1) {
            val edited = JSONObject(original)
            val pages = edited.getJSONArray("profiles").getJSONObject(0).getJSONArray("pages")
            pages.getJSONObject(pageIndex).getJSONArray("keys").getJSONObject(0).put("label", "Mine")
            loaded.importJson(edited.toString()).getOrThrow()
            assertEquals("navigation.scroll-up", actions(root(loaded))[3])
            assertEquals("Mine", loaded.state.value.activeProfile.pages[pageIndex].keys.first().label)
        }
    }

    @Test fun previousStockNavigationUpgradesButEditedRootIsPreserved() {
        val state = defaultDeck()
        val nav = state.profiles.single { it.id == "navigation" }
        val open = DeckKey(action = ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to "accessibility")))
        val root = nav.pages.first().copy(keys = listOf(0, 3, 1, 6, 4, 8, 2, 5).map { pluginKey("navigation", it) } + open)
        val oldActions = listOf("navigation.talkback-settings", "navigation.caption-settings", "navigation.night-light-settings",
            "navigation.accessibility-shortcut", "navigation.access")
        val oldPage = DeckPage("accessibility", "Accessibility", oldActions.map { DeckKey(action = ActionCall(it)) } +
            List(3) { DeckKey() } + DeckKey(label = "Back to Navigation", action = ActionCall(DeckActions.ROOT)))
        fun load(page: DeckPage): DeckState = DeckJson.decode(DeckJson.encode(state.copy(profiles = state.profiles.map {
            if (it.id == "navigation") it.copy(pages = listOf(page, oldPage)) else it
        })))
        assertEquals(expectedNavigationRoot(), load(root).profiles.single { it.id == "navigation" }.pages.first().keys)
        val edited = root.copy(keys = root.keys.mapIndexed { i, key -> if (i == 0) key.copy(label = "Custom") else key })
        val retained = load(edited).profiles.single { it.id == "navigation" }.pages.first()
        assertEquals(edited, retained)
        // Old plugin.key bindings still execute their original commands, not the new physical positions.
        assertEquals(listOf("navigation.back", "navigation.scroll-up", "navigation.home", "navigation.focus-previous",
            "navigation.activate", "navigation.focus-next", "navigation.recents", "navigation.scroll-down"),
            retained.keys.take(8).map { tel.misa.mxpocket.decks.navigationCommands[it.action!!.params.getValue("index").toInt()].id })
    }

    @Test fun v4UpgradesOnlyStockNavigationWithoutOverwritingUserPagesOrBindings() {
        val state = repo.state.value
        val nav = state.profiles.single { it.id == "navigation" }
        val old = DeckPage("root", "Navigation", listOf(0,3,1,6,4,8,2,5,7).map { pluginKey("navigation", it) })
        fun migrate(vararg pages: DeckPage): DeckProfile {
            val document = state.copy(version = 4, profiles = state.profiles.map {
                if (it.id == "navigation") nav.copy(name = "My pad", pages = pages.toList()) else it
            })
            repo.importJson(DeckJson.encode(document)).getOrThrow()
            return repo.state.value.profiles.single { it.id == "navigation" }.also { assertEquals("My pad", it.name) }
        }
        assertEquals(expectedNavigationRoot(), migrate(old).pages.first().keys)
        val edited = old.copy(keys = old.keys.mapIndexed { i, k -> if (i == 8) k.copy(holdAction = ActionCall(DeckActions.ROOT)) else k })
        assertEquals(listOf(edited), migrate(edited).pages)
        val existing = old.copy(id = "accessibility", name = "My tools")
        assertEquals(listOf(old, existing), migrate(old, existing).pages)
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

    @Test fun existingLayoutWithoutHoldOrWithExplicitNullKeepsTapAndExportsWithoutHoldFields() {
        val old = historicalNavigation().put("version", 3)
        val expected = (0..8).map { pluginKey("navigation", it) }
        file.writeText(old.toString())
        val current = DeckRepository(file)
        assertEquals(expected, current.state.value.activePage.keys)
        current.importJson(old.put("selectionReason", "Imported").apply { keyAt(0).put("holdAction", JSONObject.NULL) }
            .toString()).getOrThrow()
        assertEquals(expected, current.state.value.activePage.keys)
        assertFalse(current.exportJson().contains("\"holdAction\""))
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
        assertEquals(expectedNavigationRoot(), state.activePage.keys)
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
        assertEquals(expectedNavigationRoot(), result.getValue("navigation").pages.first().keys)
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
        repo.setProfileAutomatic("studio", true).getOrThrow()
        repo.setRules("studio", listOf(rule)).getOrThrow()
        for (time in listOf(0L, 2000L)) repo.observe(mapOf(rule.signal to pkg), time).getOrThrow()
        assertEquals("studio", repo.state.value.activeProfileId)
        assertEquals(listOf(rule), repo.decision.value.matches["studio"])
        assertFalse(repo.reason.value.contains(pkg))
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
        repo.selectProfile("studio").getOrThrow()
        // A backup from before the built-in game became read-only.
        val old = repo.state.value
        repo.importJson(DeckJson.encode(old.copy(profiles = old.profiles.filterNot { it.id == GAME_PROFILE },
            histories = old.histories - GAME_PROFILE, xoProfileRemoved = true))).getOrThrow()
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
        val holdParams = mutableMapOf("message" to "Later 🌙")
        val key = DeckKey("☕", "休憩", 0xffc91f8a.toInt(), ActionCall("example.message", params), false,
            holdAction = ActionCall("example.message", holdParams))
        repo.setKey(id, page, 7, key).getOrThrow()
        params.clear()
        holdParams.clear()
        repo.swapKeys(id, page, 7, 2).getOrThrow()
        repo.setPinned(id).getOrThrow()
        repo.setDefault(id).getOrThrow()
        repo.setProfileAutomatic(id, false).getOrThrow()
        repo.setRules(id, listOf(DeckRule("hour", "22-5", 5))).getOrThrow()
        repo.openPage(page).getOrThrow()
        val reloaded = DeckRepository(file)
        val state = reloaded.state.value
        assertEquals(repo.state.value, state)
        assertEquals("旅\n🌎", state.activePage.keys[2].action!!.params["message"])
        assertEquals("Later 🌙", state.activePage.keys[2].holdAction!!.params["message"])
        assertFalse(state.activePage.keys[2].enabled)
        assertEquals(id, state.pinnedProfileId)
        assertTrue(reloaded.reason.value.contains("pinned"))
        assertThrows(UnsupportedOperationException::class.java) { (state.activePage.keys as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (state.histories as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (state.activePage.keys[2].action!!.params as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (state.activePage.keys[2].holdAction!!.params as MutableMap).clear() }

        val exported = repo.exportJson()
        repo.restoreDefaults().getOrThrow()
        assertEquals("launcher", repo.state.value.activeProfileId)
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
            edit { it.keyAt(0).put("holdAction", "page.open") },
            edit { it.keyAt(0).put("holdAction", JSONObject().put("id", "page.back")) },
            edit { it.keyAt(0).put("action", JSONObject.NULL).put("holdAction",
                JSONObject("""{"id":"page.open","params":{"pageId":"missing"}}""")) },
            edit { it.keyAt(0).put("holdAction", JSONObject("""{"id":"plugin.key","params":{"pluginId":"focus","index":"9"}}""")) },
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
        repo.selectProfile("studio").getOrThrow()
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
        repo.setDefault("focus").getOrThrow()
        repo.setPinned(duplicate).getOrThrow()
        repo.deleteProfile(duplicate).getOrThrow()
        assertEquals("focus", repo.state.value.activeProfileId)
        assertNull(repo.state.value.pinnedProfileId)
        assertNull(repo.state.value.activePage.keys[0].action)
        assertEquals(repo.state.value, DeckRepository(file).state.value)
    }

    @Test fun copyingRenamingAndRemovingTargetsPreservesIndependentTapAndHoldLinks() {
        fun page(id: String) = ActionCall(DeckActions.PAGE, mapOf(DeckActions.PAGE_ID to id))
        fun profile(id: String) = ActionCall(DeckActions.PROFILE, mapOf(DeckActions.PROFILE_ID to id))
        repo.setDefault("focus").getOrThrow()
        val child = repo.createPage("focus", "Scene").getOrThrow()
        repo.setKey("focus", "root", 0, DeckKey(holdAction = page(child))).getOrThrow()
        val key = DeckKey(action = profile("focus"), holdAction = page(child))
        repo.setKey("focus", child, 0, key).getOrThrow()
        repo.setKey("focus", child, 1, DeckKey(action = page(child), holdAction = profile("focus"))).getOrThrow()
        val copiedPage = repo.duplicatePage("focus", child, "Copied scene").getOrThrow()
        val copiedProfile = repo.duplicateProfile("focus", "Copied profile").getOrThrow()
        repo.renamePage(copiedProfile, child, "Renamed").getOrThrow()
        repo.renameProfile(copiedProfile, "Renamed profile").getOrThrow()
        repo.selectProfile(copiedProfile).getOrThrow()
        repo.openPage(copiedPage).getOrThrow()
        assertEquals(DeckKey(action = profile(copiedProfile), holdAction = page(copiedPage)), repo.state.value.activePage.keys[0])
        assertEquals(DeckKey(action = page(copiedPage), holdAction = profile(copiedProfile)), repo.state.value.activePage.keys[1])
        repo.deletePage("focus", child).getOrThrow()
        repo.root().getOrThrow()
        assertEquals(DeckKey(holdAction = page(child)), repo.state.value.activePage.keys[0])
        repo.setKey("focus", "root", 1, DeckKey(action = page("root"), holdAction = profile(copiedProfile))).getOrThrow()
        repo.deleteProfile(copiedProfile).getOrThrow()
        val reloaded = DeckRepository(file).state.value
        assertEquals(DeckKey(), reloaded.activePage.keys[0])
        assertEquals(DeckKey(action = page("root")), reloaded.activePage.keys[1])
    }

    @Test fun scoringNeedsContinuousObservedMatchesAndAWinningMargin() {
        val music = DeckRule("mediaActive", "true", 3)
        val overnight = DeckRule("hour", "22-5", 4)
        val dock = DeckRule("dock", "desk", 1)
        repo.setDefault("focus").getOrThrow()
        repo.selectProfile("focus").getOrThrow()
        repo.setProfileAutomatic("studio", true).getOrThrow()
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
        repo.setDefault("focus").getOrThrow()
        repo.observe(media, 1).getOrThrow()
        repo.setPinned("studio").getOrThrow()
        repo.observe(media, 5000).getOrThrow()
        assertEquals("studio", repo.state.value.activeProfileId)
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
        repo.observe(media, 20000L).getOrThrow()
        assertEquals("audio", repo.state.value.activeProfileId)
    }

    @Test fun oldMasterSwitchDoesNotGateRulesOrDisplaceManualLayoutsWithoutAMatch() {
        repo.setProfileAutomatic("studio", true).getOrThrow()
        repo.setDefault("focus").getOrThrow()
        repo.selectProfile("studio").getOrThrow()
        file.writeText(JSONObject(repo.exportJson()).put("automatic", false).toString())
        val upgraded = DeckRepository(file)
        upgraded.selectProfile("studio").getOrThrow()
        fun sample(signals: Map<String, String>, start: Long) {
            upgraded.observe(signals, start).getOrThrow()
            upgraded.observe(signals, start + DeckRepository.DEBOUNCE_MS).getOrThrow()
        }
        sample(emptyMap(), 0); assertEquals("studio", upgraded.state.value.activeProfileId)
        sample(mapOf("mediaActive" to "true"), 2000); assertEquals("audio", upgraded.state.value.activeProfileId)
        sample(emptyMap(), 4000); assertEquals("focus", upgraded.state.value.activeProfileId)
        assertTrue(JSONObject(upgraded.exportJson()).getBoolean("automatic"))
    }

    private fun JSONObject.keyAt(index: Int): JSONObject = getJSONArray("profiles").getJSONObject(0)
        .getJSONArray("pages").getJSONObject(0).getJSONArray("keys").getJSONObject(index)
}
