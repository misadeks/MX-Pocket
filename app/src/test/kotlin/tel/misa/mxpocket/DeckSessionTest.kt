package tel.misa.mxpocket

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.*
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.workflow.WorkflowPhase
import tel.misa.mxpocket.workflow.WorkflowDefinition
import tel.misa.mxpocket.workflow.WorkflowOrigin
import tel.misa.mxpocket.workflow.WorkflowStep
import tel.misa.mxpocket.workflow.StepKind
import tel.misa.mxpocket.workflow.TriggerKind
import tel.misa.mxpocket.workflow.WorkflowLibrary
import tel.misa.mxpocket.workflow.WorkflowStore
import tel.misa.mxpocket.workflow.WorkflowTrigger
import java.time.Duration
import android.graphics.Bitmap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = KeypadSessionTest.FakeApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeckSessionTest {
    private val app get() = RuntimeEnvironment.getApplication() as KeypadSessionTest.FakeApp
    private val main get() = shadowOf(Looper.getMainLooper())
    private val screen = object : PocketSession.Screen {
        override fun render() {}
        override fun openApp(app: PhoneApp) = false
    }

    @Test fun v1MixedStudioPageKeepsItsOriginalPluginSelection() {
        val session = app.session
        val repo = session.deck
        repo.setKey("studio","root",0,DeckKey(action = ActionCall("plugin.key",
            mapOf("pluginId" to "audio","index" to "0")))).getOrThrow()
        session.attach(screen)
        session.selectProfile("studio")
        repo.importJson(org.json.JSONObject(repo.exportJson()).put("version",1).toString()).getOrThrow()
        val audio = app.getSystemService(android.media.AudioManager::class.java)
        val stream = android.media.AudioManager.STREAM_MUSIC
        audio.setStreamVolume(stream, 4, 0)
        fun verifyLoaded(active: PocketSession, expectedVolume: Int) {
            app.connectKeypad(active)
            assertEquals("audio",active.plugins.selectedId)
            assertEquals("audio",active.signals.value["plugin"])
            assertTrue(active.frame().keys[0].enabled)
            assertFalse(active.frame().keys[1].enabled)
            app.press(1)
            assertEquals(expectedVolume, audio.getStreamVolume(stream))
            active.disconnect()
            active.detach(screen)
        }
        verifyLoaded(session, 3)
        repo.save().getOrThrow()
        val reloaded = app.createSession()
        reloaded.attach(screen)
        assertEquals(PROFILE_HOME, reloaded.deck.operatingState.value.activeProfileId)
        reloaded.openProfile("studio")
        verifyLoaded(reloaded, 2)
        // Explicit v2 actions are not positional plugin bindings.
        reloaded.deck.setKey("studio","root",0,DeckKey(action = ActionCall("media.volume-down"))).getOrThrow()
        reloaded.attach(screen)
        app.connectKeypad(reloaded)
        assertEquals("studio", reloaded.signals.value["plugin"])
        assertFalse(reloaded.runDeckAction("media.volume-down", mapOf("unexpected" to "x")).success)
        reloaded.disconnect(); reloaded.detach(screen)
    }

    @Test fun physicalKeypadRunsSavedWorkflowAfterReconnectingOnTheSamePage() {
        app.workflowsEnabled = true
        val session = app.session
        val workflows = checkNotNull(session.workflows)
        val repo = session.deck
        val id = repo.createProfile("Desk").getOrThrow()
        val child = repo.createPage(id,"Tools").getOrThrow()
        val root = repo.state.value.profiles.single { it.id == id }.rootPageId
        repo.setKey(id,root,0,DeckKey(label = "Tools",action = ActionCall("page.open",mapOf("pageId" to child)))).getOrThrow()
        repo.setKey(id,child,1,DeckKey(label = "Test",action = ActionCall("workflow.run",mapOf("workflowId" to "keypad-check")))).getOrThrow()
        session.attach(screen)
        session.selectProfile(id)
        app.connectKeypad()
        app.press(1)
        assertEquals(child, repo.state.value.activePage.id)
        session.disconnect()
        app.press(2) // The old USB callback cannot start a workflow after disconnect.
        assertNull(workflows.status.value)
        app.connectKeypad()
        assertEquals("Test",session.frame().keys[1].label)
        app.press(2)
        assertEquals(WorkflowPhase.RUNNING,workflows.status.value?.phase)
        assertEquals(WorkflowOrigin.PHYSICAL,workflows.status.value?.origin)
        assertTrue(session.frame().keys[1].footer.contains("running"))
        main.idleFor(Duration.ofSeconds(3))
        assertEquals(WorkflowPhase.SUCCESS,workflows.status.value?.phase)
        assertTrue(session.frame().keys[1].footer.contains("success"))
        assertEquals(child,DeckRepository(app).state.value.activePage.id)
        session.disconnect()
        session.detach(screen)
    }

    @Test fun upgradedWorkflowBindingsStayDormantWithoutDisablingOrdinaryTapOrHold() {
        val repo = DeckRepository(app)
        val flow = WorkflowDefinition("legacy", "Old shortcut",
            steps = listOf(WorkflowStep(StepKind.ACTION, target = DeckActions.PROFILE, params = mapOf("profileId" to "phone"))))
        val library = WorkflowLibrary(1, listOf(flow, flow.copy(id = "connect", trigger = WorkflowTrigger(TriggerKind.CONNECTED))))
        val store = WorkflowStore(app)
        store.save(library)
        val workflow = ActionCall("workflow.run", mapOf("workflowId" to flow.id))
        val next = ActionCall(DeckActions.PROFILE, mapOf("profileId" to "audio"))
        val keys = listOf(DeckKey(action = workflow), DeckKey(action = workflow, holdAction = next),
            DeckKey(action = next, holdAction = workflow))
        keys.forEachIndexed { i, key -> repo.setKey("focus", "root", i, key).getOrThrow() }

        // A new session reads the existing deck; an imported copy follows the same dispatch path.
        val session = app.session
        session.deck.importJson(repo.exportJson()).getOrThrow()
        session.attach(screen)
        session.selectProfile("focus")
        app.connectKeypad()
        assertEquals("focus", session.deck.operatingState.value.activeProfileId)
        assertFalse(session.frame().keys[0].enabled)
        assertFalse(kotlinx.coroutines.runBlocking { session.actions.dispatch(workflow) }.success)
        app.press(1)
        app.press(2)
        assertEquals("focus", session.deck.operatingState.value.activeProfileId)
        fun hold(key: Int) {
            app.emit(KeyEvent(key, true))
            main.idleFor(Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1))
            app.emit(KeyEvent(key, false)); main.idle()
        }
        hold(2)
        assertEquals("audio", session.deck.operatingState.value.activeProfileId)
        session.selectProfile("focus"); main.idle()
        hold(3)
        assertEquals("focus", session.deck.operatingState.value.activeProfileId)
        app.press(3)
        assertEquals("audio", session.deck.operatingState.value.activeProfileId)
        session.disconnect(); session.detach(screen)
        assertEquals(library, store.load())
        assertTrue(store.loadHistory().isEmpty())
        assertEquals(keys, DeckRepository(app).state.value.profiles.single { it.id == "focus" }.pages.first().keys.take(3))
    }

    @Test fun changingOnlyTheActionRequiresANewReceiptEvenWhenThePixelsAreIdentical() {
        lateinit var emit: (KeyEvent) -> Unit
        var writes = 0
        val gate = CompletableDeferred<Unit>()
        val session = PocketSession(app) { _,_,event,_,_ ->
            emit = event
            object : KeypadOutput {
                override fun close() {}
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
                    writes++
                    if (writes == 10) gate.await()
                }
            }
        }
        val layout = session.deck
        val id = layout.createProfile("Board").getOrThrow()
        val page = layout.createPage(id,"Next").getOrThrow()
        val key = DeckKey(label = "Go",action = ActionCall("page.root"))
        layout.setKey(id,"root",0,key).getOrThrow()
        session.selectProfile(id)
        app.connectKeypad(session)
        assertEquals(9,writes)
        // Transmit a changed tile, then change the binding while that transaction is waiting.
        layout.setKey(id,"root",1,key.copy(label = "Root")).getOrThrow()
        main.idle()
        assertEquals(10,writes)
        layout.setKey(id,"root",0,key.copy(action = ActionCall("page.open",mapOf("pageId" to page)))).getOrThrow()
        emit(KeyEvent(1,true))
        emit(KeyEvent(1,false))
        gate.complete(Unit)
        main.idle()
        assertEquals("root",layout.state.value.activePage.id)
        emit(KeyEvent(1,true))
        main.idle()
        assertEquals(page,layout.state.value.activePage.id)
        session.disconnect()
    }

    @Test fun rebindingAHeldPhysicalPluginKeyReleasesTheOldSlot() {
        lateinit var emit: (KeyEvent) -> Unit
        val session = PocketSession(app) { _,_,event,_,_ ->
            emit = event
            object : KeypadOutput {
                override fun close() {}
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {}
            }
        }
        session.selectProfile("focus")
        app.connectKeypad(session)
        emit(KeyEvent(1,true))
        main.idle()
        assertEquals("Pause",session.frame().keys[0].label)
        session.deck.swapKeys("focus","root",0,2).getOrThrow()
        main.idle()
        emit(KeyEvent(1,false))
        emit(KeyEvent(3,true))
        main.idle()
        assertEquals("Start",session.frame().keys[2].label)
        session.disconnect()
    }

    @Test fun delayedPhysicalWorkflowCannotNavigateOrLaunchWithoutSeparateApproval() {
        app.workflowsEnabled = true
        val session = app.session
        var navigations = 0
        session.attach(object : PocketSession.Screen by screen {
            override fun deckAction(command: tel.misa.mxpocket.decks.DeckCommand, component: String?):
                tel.misa.mxpocket.decks.DeckResult {
                navigations++
                return tel.misa.mxpocket.decks.ok("Opened")
            }
        })
        val runner = checkNotNull(session.workflows)
        val draft = WorkflowDefinition(name = "Delayed Home",steps = listOf(
            WorkflowStep(StepKind.WAIT,value = "1"), WorkflowStep(StepKind.ACTION,target = "navigation.home")))
        assertNotNull(runner.start(draft,WorkflowOrigin.PHYSICAL))
        main.idleFor(Duration.ofSeconds(2))
        assertEquals(WorkflowPhase.CONFIRMATION,runner.status.value?.phase)
        assertEquals(0,navigations)
        runner.effects.respond(runner.effects.prompt.value!!,accept = false,activity = null)
        main.idle()
        assertEquals(WorkflowPhase.FAILED,runner.status.value?.phase)
        assertEquals(0,navigations)
    }
}
