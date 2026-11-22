package tel.misa.mxpocket

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
import java.time.Duration
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class DeckSessionTest {
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication
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
            active.startPreview()
            main.idle()
            assertEquals("audio",active.plugins.selectedId)
            assertEquals("audio",active.signals.value["plugin"])
            assertTrue(active.frame().keys[0].enabled)
            assertFalse(active.frame().keys[1].enabled)
            assertTrue(active.phoneKey(0))
            main.idle()
            assertEquals(expectedVolume, audio.getStreamVolume(stream))
            active.disconnect()
            active.detach(screen)
        }
        verifyLoaded(session, 3)
        repo.save().getOrThrow()
        val reloaded = PocketSession(app)
        reloaded.attach(screen)
        assertEquals(PROFILE_HOME, reloaded.deck.operatingState.value.activeProfileId)
        reloaded.openProfile("studio")
        verifyLoaded(reloaded, 2)
        // Explicit v2 actions are not positional plugin bindings.
        reloaded.deck.setKey("studio","root",0,DeckKey(action = ActionCall("media.volume-down"))).getOrThrow()
        reloaded.attach(screen)
        reloaded.startPreview(); main.idle()
        assertEquals("studio", reloaded.signals.value["plugin"])
        assertFalse(reloaded.runDeckAction("media.volume-down", mapOf("unexpected" to "x")).success)
        reloaded.disconnect(); reloaded.detach(screen)
    }

    @Test fun simulatorRunsSavedPagesAndWorkflowAndReconnectsWithoutLosingLocation() {
        val session = app.session
        val repo = session.deck
        val id = repo.createProfile("Desk").getOrThrow()
        val child = repo.createPage(id,"Tools").getOrThrow()
        val root = repo.state.value.profiles.single { it.id == id }.rootPageId
        repo.setKey(id,root,0,DeckKey(label = "Tools",action = ActionCall("page.open",mapOf("pageId" to child)))).getOrThrow()
        repo.setKey(id,child,1,DeckKey(label = "Test",action = ActionCall("workflow.run",mapOf("workflowId" to "keypad-check")))).getOrThrow()
        session.attach(screen)
        session.selectProfile(id)
        session.startPreview()
        assertTrue(session.phoneKey(0))
        assertEquals(child, repo.state.value.activePage.id)
        session.disconnect()
        assertFalse(session.phoneKey(1))
        session.startPreview()
        assertEquals("Test",session.frame().keys[1].label)
        assertTrue(session.phoneKey(1))
        main.idle()
        assertEquals(WorkflowPhase.RUNNING,session.workflows.status.value?.phase)
        assertTrue(session.frame().keys[1].footer.contains("running"))
        main.idleFor(Duration.ofSeconds(3))
        assertEquals(WorkflowPhase.SUCCESS,session.workflows.status.value?.phase)
        assertTrue(session.frame().keys[1].footer.contains("success"))
        assertEquals(child,DeckRepository(app).state.value.activePage.id)
        session.disconnect()
        session.detach(screen)
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
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java)
        ReflectionHelpers.setField(device,"mName","simulated")
        val ticket = session.reserve(device)!!
        session.connect(ticket,app.getSystemService(UsbManager::class.java),device,{}, { error("Unexpected shortcut") })
        main.idle()
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
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java)
        ReflectionHelpers.setField(device,"mName","simulated")
        session.selectProfile("focus")
        session.connect(session.reserve(device)!!,app.getSystemService(UsbManager::class.java),device,{}, { error("Unexpected shortcut") })
        main.idle()
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
        val session = app.session
        var navigations = 0
        session.attach(object : PocketSession.Screen by screen {
            override fun deckAction(command: tel.misa.mxpocket.decks.DeckCommand, component: String?):
                tel.misa.mxpocket.decks.DeckResult {
                navigations++
                return tel.misa.mxpocket.decks.ok("Opened")
            }
        })
        val runner = session.workflows
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
