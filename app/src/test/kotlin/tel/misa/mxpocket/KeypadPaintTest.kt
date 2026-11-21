package tel.misa.mxpocket

import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.actions.ActionCall
import tel.misa.mxpocket.deck.DeckActions
import tel.misa.mxpocket.deck.DeckKey
import tel.misa.mxpocket.decks.MediaSessionAccess
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.decks.ok
import tel.misa.mxpocket.driver.KeyEvent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeypadPaintTest {
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication
    private val main get() = shadowOf(Looper.getMainLooper())

    /** One ACK per release; keep native pixels to detect visually stale but logically equal tiles. */
    private inner class Panel(val supportsGrid: Boolean = false) {
        val pixels = arrayOfNulls<Bitmap>(9)
        val writes = mutableListOf<Int>()
        var pause = false
        var inFlight: Bitmap? = null
        var outstanding: CompletableDeferred<Unit>? = null
        lateinit var event: (KeyEvent) -> Unit
        var shortcuts = 0
        val grids = mutableListOf<Int?>()
        var split = false
        val session = PocketSession(app) { _, _, onEvent, _, _ ->
            event = onEvent
            val keys = object : KeypadOutput {
                override fun close() {}
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
                    check(outstanding == null) { "Concurrent transfer" }
                    writes += index
                    if (pause) {
                        inFlight = bitmap
                        val ack = CompletableDeferred<Unit>()
                        outstanding = ack
                        ack.await()
                        outstanding = null
                    }
                    pixels[index] = bitmap
                }
            }
            if (!supportsGrid) keys else object : GridOutput, KeypadOutput by keys {
                override suspend fun writeGrid(art: Bitmap?, tiles: List<Bitmap>, clearOldGaps: Boolean, current: () -> Boolean): GridWrite {
                    check(outstanding == null && current())
                    grids += art?.getPixel(240,240)
                    if (split) return GridWrite.TOO_LARGE
                    if (pause) {
                        val ack = CompletableDeferred<Unit>()
                        outstanding = ack; ack.await(); outstanding = null
                    }
                    tiles.forEachIndexed { i, image -> pixels[i] = image }
                    return GridWrite.ACKNOWLEDGED
                }
            }
        }
        init {
            shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            val device = ReflectionHelpers.newInstance(UsbDevice::class.java)
            ReflectionHelpers.setField(device, "mName", "test-panel")
            ReflectionHelpers.setField(device, "mVendorId", 0x046d)
            ReflectionHelpers.setField(device, "mProductId", 0xc354)
            ReflectionHelpers.setField(device, "mConfigurations", emptyArray<UsbConfiguration>())
            val usb = app.getSystemService(UsbManager::class.java)
            shadowOf(usb).addOrUpdateUsbDevice(device, true)
            session.connect(session.reserve(device)!!, usb, device, {}, {
                shortcuts++; ok("Offered")
            })
            main.idle()
        }
        fun ack() { checkNotNull(outstanding).complete(Unit); main.idle() }
        fun settle() { pause = false; ack() }
        fun press(id: Int) { event(KeyEvent(id, true)); event(KeyEvent(id, false)); main.idle() }
        fun expectAll() {
            val frame = session.frame()
            for (i in pixels.indices)
                assertTrue("Wrong pixels on key ${i + 1}", pixels[i]?.sameAs(session.keyImage(i, frame.keys[i])) == true)
        }
    }

    @Test fun navigationPhysicalWindowsAndPhoneTapsDispatchTheSameUnlockedLocalActions() {
        val p = Panel()
        val actions = mutableListOf<DeckCommand>()
        val screen = object : PocketSession.Screen {
            override fun render() {}
            override fun openApp(app: PhoneApp) = false
            override fun deckAction(command: DeckCommand, component: String?) = ok("Handled").also { actions += command }
        }
        val expected = listOf(DeckCommand.BACK, DeckCommand.UP, DeckCommand.HOME, DeckCommand.REVERSE,
            DeckCommand.ACTIVATE, DeckCommand.FORWARD, DeckCommand.RECENTS, DeckCommand.DOWN, DeckCommand.NAV_ACCESS)
        fun physical(i: Int) {
            p.event(KeyEvent(i + 1, true)); p.event(KeyEvent(i + 1, false)); main.idle()
        }
        try {
            p.session.attach(screen)
            p.session.selectProfile("navigation"); main.idle()
            p.expectAll()
            for (i in expected.indices) {
                actions.clear()
                physical(i)
                p.session.phoneKey(i)
                assertEquals("Window ${i + 1}", if (i == 6) emptyList() else List(2) { expected[i] }, actions)
            }
            actions.clear()
            // Showing a disclosure and sending Back are not exceptions to the lock guard.
            val guard = app.getSystemService(android.app.KeyguardManager::class.java)
            shadowOf(guard).setKeyguardLocked(true)
            for (i in listOf(0, 8)) { physical(i); p.session.phoneKey(i) }
            assertTrue(actions.isEmpty())
        } finally { p.session.detach(screen); p.session.disconnect() }
    }

    @Test fun initialProfileAndCustomPageNavigationAlwaysWriteOneGridEvenWithIdenticalTiles() {
        val p = Panel(supportsGrid = true)
        val deck = p.session.deck
        try {
            val profile = deck.createProfile("Desk").getOrThrow()
            val child = deck.createPage(profile, "Tools").getOrThrow()
            // Even the Open key has identical pixels and binding in both pages.
            val open = DeckKey(label = "Tools", action = ActionCall(DeckActions.PAGE, mapOf("pageId" to child)))
            for (page in listOf("root", child)) deck.setKey(profile, page, 0, open).getOrThrow()
            fun expectGrids(count: Int) {
                assertEquals(List<Int?>(count) { null }, p.grids)
                assertTrue(p.writes.isEmpty())
                p.expectAll()
            }
            expectGrids(1)
            p.session.selectProfile(profile); main.idle(); expectGrids(2)
            p.press(1); assertEquals(child, deck.state.value.activePage.id); expectGrids(3)
            p.press(0x01a1); assertEquals("root", deck.state.value.activePage.id); expectGrids(4)
            p.press(0x01a2); assertEquals(child, deck.state.value.activePage.id); expectGrids(5)
            p.press(1); expectGrids(5) // Already on Tools.
        } finally { p.session.disconnect() }
    }

    @Test fun singleVisualChangeRequiresItsOwnAckAndLeavesOtherKeysActionable() {
        val p = Panel(supportsGrid = true)
        try {
            p.session.selectProfile("phone"); main.idle()
            p.grids.clear(); p.writes.clear()
            fun status(text: String) {
                assertTrue(runBlocking {
                    p.session.actions.dispatch(ActionCall("keypad.status", mapOf("key" to "0", "text" to text))).success
                })
                main.idle()
            }
            p.pause = true
            status("First"); status("Second")
            assertEquals(listOf(0), p.writes)
            p.press(1); assertEquals(0, p.shortcuts)
            p.press(3); assertEquals(1, p.shortcuts) // Unchanged acknowledged Dialer still works.
            p.ack() // "First" reached the panel, but "Second" is now desired.
            p.press(1); assertEquals(1, p.shortcuts)
            p.settle()
            p.press(1); assertEquals(2, p.shortcuts)
            status("Second")
            assertEquals(listOf(0, 0), p.writes)
            assertTrue(p.grids.isEmpty()); p.expectAll()
        } finally { p.session.disconnect() }
    }

    @Test fun rebindingAnIdenticalLookingKeyRequiresOneKeyAckBeforeItCanNavigate() {
        val p = Panel(supportsGrid = true)
        try {
            val deck = p.session.deck
            p.session.selectProfile("phone")
            val key = DeckKey(label = "Go", action = ActionCall(DeckActions.ROOT))
            deck.setKey("phone", "root", 4, key).getOrThrow(); main.idle()
            p.writes.clear(); p.grids.clear()
            // The action changes despite identical pixels; the new binding needs its own ACK.
            val child = deck.duplicatePage("phone", "root", "More").getOrThrow()
            p.pause = true
            deck.setKey("phone", "root", 4, key.copy(action = ActionCall(DeckActions.PAGE, mapOf("pageId" to child)))).getOrThrow()
            main.idle(); p.press(5)
            assertEquals("root", deck.state.value.activePage.id)
            p.settle()
            assertEquals(listOf(4), p.writes)
            assertTrue(p.grids.isEmpty())
            p.press(5); assertEquals(child, deck.state.value.activePage.id)
            assertEquals(listOf<Int?>(null), p.grids)
        } finally { p.session.disconnect() }
    }

    @Test fun samePageMultiTileChangeUsesOneGridAndIdenticalRebuiltPixelsSendNothing() {
        val p = Panel(supportsGrid = true)
        try {
            val backdrop = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            p.grids.clear()
            p.session.setImage(backdrop); main.idle()
            p.expectAll()
            assertEquals(listOf<Int?>(null), p.grids)
            p.session.setImage(backdrop.copy(backdrop.config!!, false)); main.idle()
            assertEquals(listOf<Int?>(null), p.grids)
            assertTrue(p.writes.isEmpty())
        } finally { p.session.disconnect() }
    }

    @Test fun delayedGridFromCustomPageRoundTripCannotAuthorizeAnIdenticalScreen() {
        val p = Panel(supportsGrid = true)
        try {
            val deck = p.session.deck
            val profile = deck.createProfile("Desk").getOrThrow()
            val child = deck.createPage(profile, "Next").getOrThrow()
            val open = DeckKey(label = "Go", action = ActionCall(DeckActions.PAGE, mapOf("pageId" to child)))
            deck.setKey(profile, "root", 0, open).getOrThrow()
            p.pause = true; p.grids.clear()
            p.session.selectProfile(profile); main.idle()
            p.session.changePage(1); p.session.changePage(-1); main.idle()
            p.ack()
            assertEquals(2, p.grids.size)
            p.press(1); assertEquals("root", deck.state.value.activePage.id)
            p.settle()
            p.press(1); assertEquals(child, deck.state.value.activePage.id)
            assertEquals(3, p.grids.size); assertTrue(p.writes.isEmpty()); p.expectAll()
        } finally { p.session.disconnect() }
    }

    @Test fun interruptedBackdropRestoresOnlyKeysThatAcknowledgedDifferentPixels() {
        val p = Panel()
        fun backdrop(color: Int) = Bitmap.createBitmap(118, 118, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val a = backdrop(Color.RED)
        val b = backdrop(Color.BLUE)
        try {
            p.session.setImage(a); main.idle(); p.expectAll()
            val keys = p.session.frame().keys
            p.writes.clear()
            p.pause = true
            p.session.setImage(b); main.idle(); p.ack(); p.ack()
            assertEquals(listOf(0, 1, 2), p.writes)
            p.session.setImage(a); main.idle()
            p.settle()
            assertEquals(keys, p.session.frame().keys)
            p.expectAll()
            assertEquals("Restore only the three overwritten keys", listOf(0, 0, 1, 1, 2, 2), p.writes.sorted())

            p.writes.clear()
            p.session.repaint(); main.idle()
            assertEquals((0..8).toList(), p.writes.sorted())
        } finally { p.session.disconnect() }
    }

    @Test fun artworkChangesBetweenAcksCannotStarveTheLastEightKeys() {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app, MediaSessionAccess::class.java), true)
        val media = MediaSession(app, "test-player")
        val controller = shadowOf(media.controller)
        controller.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f)
            .setActions(PlaybackState.ACTION_PAUSE).build())
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(media.controller)
        val p = Panel()
        fun cover(color: Int) {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            controller.executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "same")
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Same").putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap).build())
            val end = System.nanoTime() + 5_000_000_000L
            do { main.idle(); if (p.session.panelArt?.getPixel(240, 240) == color) return; Thread.yield() }
            while (System.nanoTime() < end)
            fail("Test player did not publish its cover")
        }
        try {
            p.session.selectProfile("audio"); main.idle()
            cover(Color.RED); p.expectAll()
            val bindings = p.session.frame().keys
            p.pause = true; p.writes.clear()
            cover(Color.BLUE)
            repeat(8) { i -> cover(Color.rgb(80 + i * 10, 100, 150)); p.ack() }
            val servedDuringChurn = p.writes.toSet()
            p.settle(); p.expectAll()
            assertEquals(bindings, p.session.frame().keys)
            assertEquals("Even continuously changing covers must visit every window", (0..8).toSet(), servedDuringChurn)
        } finally { p.session.disconnect(); media.release() }
    }

    @Test fun newerVolumeReachesLaterKeysWhileTheFirstKeyKeepsChanging() {
        val p = Panel()
        val audio = app.getSystemService(AudioManager::class.java)
        fun volume(n: Int) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, n, 0)
            app.contentResolver.notifyChange(Settings.System.getUriFor("volume_music"), null)
            main.idle()
        }
        try {
            // The factory player has a single volume readout. Exercise multi-key fairness on
            // a saved layout with another explicit volume binding, not the old factory order.
            p.session.deck.setKey("audio", "root", 8, tel.misa.mxpocket.deck.DeckKey(
                action = tel.misa.mxpocket.actions.ActionCall("media.volume-level"))).getOrThrow()
            p.session.selectProfile("audio"); volume(1)
            val original = p.session.frame()
            p.writes.clear(); p.pause = true
            volume(2)
            val dirty = (0..8).filter { original.keys[it] != p.session.frame().keys[it] }
            assertTrue(dirty.size > 1)
            for (i in dirty.indices) {
                volume(3 + i % 2)
                val desired = p.session.frame().keys.mapIndexed(p.session::keyImage)
                p.ack()
                val next = p.writes.last()
                assertTrue("Key ${next + 1} started a superseded volume after the previous ACK",
                    p.inFlight!!.sameAs(desired[next]))
            }
            p.settle()
            p.expectAll()
            // Every changed key gets a turn; unchanged transport keys never use the wire.
            assertEquals(dirty, p.writes.take(dirty.size).sorted())
            assertTrue(p.writes.all { it in dirty })
        } finally { p.session.disconnect() }
    }

    @Test fun profileRoundTripDoesNotAuthorizeInputDuringMixedPage() {
        val p = Panel()
        try {
            p.session.selectProfile("phone"); main.idle()
            p.pause = true; p.writes.clear()
            p.session.selectProfile("audio"); main.idle(); p.ack()
            p.session.selectProfile("phone"); main.idle()
            p.event(KeyEvent(1, true)); p.event(KeyEvent(1, false)); main.idle()
            assertEquals(0, p.shortcuts)
            p.settle(); p.expectAll()
            assertEquals(listOf(0, 0, 1, 1), p.writes.sorted())
            p.event(KeyEvent(1, true)); p.event(KeyEvent(1, false)); main.idle()
            assertEquals(1, p.shortcuts)
        } finally { p.session.disconnect() }
    }

    @Test fun oversizeCoverFallsBackOnceThenVolumeUsesOneKeyAndNewCoversUseGrids() {
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app, MediaSessionAccess::class.java), true)
        val media = MediaSession(app, "panel")
        val player = shadowOf(media.controller)
        player.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING,0,1f)
            .setActions(PlaybackState.ACTION_PAUSE).build())
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(media.controller)
        val p = Panel(supportsGrid = true)
        fun cover(color: Int) {
            val bitmap = Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            player.executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "one")
                .putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap).build())
            val end = System.nanoTime() + 5_000_000_000L
            do { main.idle(); if (p.session.panelArt?.getPixel(240,240) == color) return; Thread.yield() }
            while (System.nanoTime() < end)
            fail("Test cover not published")
        }
        try {
            p.session.selectProfile("audio"); main.idle(); p.writes.clear(); p.grids.clear()
            p.split = true; cover(Color.RED)
            assertEquals(listOf(Color.RED), p.grids)
            assertEquals((0..8).toList(), p.writes.sorted()); p.expectAll()
            p.split = false; p.writes.clear(); cover(Color.GREEN)
            assertEquals(listOf(Color.RED,Color.GREEN), p.grids); assertTrue(p.writes.isEmpty()); p.expectAll()
            val audio = app.getSystemService(AudioManager::class.java)
            val v = if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 1) 2 else 1
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,v,0)
            app.contentResolver.notifyChange(Settings.System.getUriFor("volume_music"),null); main.idle()
            assertEquals(listOf(Color.RED, Color.GREEN), p.grids); assertEquals(listOf(1),p.writes); p.expectAll()
            p.writes.clear(); p.pause = true; cover(Color.BLUE); cover(Color.YELLOW)
            assertEquals(Color.BLUE, p.grids.last())
            p.ack()
            assertEquals(Color.YELLOW, p.grids.last())
            p.session.selectProfile("phone"); main.idle()
            p.event(KeyEvent(1,true)); p.event(KeyEvent(1,false)); main.idle()
            assertEquals(0,p.shortcuts)
            p.settle(); p.expectAll()
            assertNull(p.grids.last()); assertTrue(p.writes.isEmpty())
            p.event(KeyEvent(1,true)); p.event(KeyEvent(1,false)); main.idle()
            assertEquals(1,p.shortcuts)
        } finally { p.session.disconnect(); media.release() }
    }
}
