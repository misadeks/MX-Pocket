package tel.misa.mxpocket

import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadata
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as P
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.decks.MediaSessionAccess
import tel.misa.mxpocket.decks.SessionArtwork
import tel.misa.mxpocket.driver.KeypadProtocol
import tel.misa.mxpocket.driver.SimulatedKeypad
import tel.misa.mxpocket.plugins.KeyTile
import tel.misa.mxpocket.ui.KeypadPanel
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaPanelTest {
    private val app get() = RuntimeEnvironment.getApplication() as PocketApplication
    private val main get() = shadowOf(Looper.getMainLooper())

    @Test fun oneCenterCropSpansHardwareRegionsSimulatedKeysAndThePhonePanel() = runBlocking {
        val source = Bitmap.createBitmap(600,480,Bitmap.Config.ARGB_8888)
        val mat = Color.rgb(95, 52, 80)
        source.eraseColor(mat)
        val painter = Canvas(source)
        val colors = (0..8).map { Color.rgb(240 - it*20,20+it*17,160-it*15) }
        val ink = Paint()
        colors.forEachIndexed { index,c ->
            val r = KeypadProtocol.keyRegion(index)
            ink.color = c
            painter.drawRect(60f+r.x,r.y.toFloat(),60f+r.x+r.width,(r.y+r.height).toFloat(),ink)
        }
        val art = SessionArtwork.from(MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,source).build())!!.load(app)!!
        assertEquals(480,art.width)
        assertEquals(mat,art.getPixel(24,7))
        val renderer = KeyImages { null }
        val tile = KeyTile("Play","Media",Color.BLACK)
        renderer.useArtwork(art)
        val sim = SimulatedKeypad()
        val tiles = colors.indices.map { i -> renderer.image(i,tile).also { sim.setKeyImage(i,it) } }
        val panel = KeypadPanel(app)
        panel.show(art, tiles)
        val exact = View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY)
        panel.measure(exact,exact); panel.layout(0,0,480,480)
        val mirrored = Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(mirrored))
        val samples = colors.indices.map { i ->
            val r = KeypadProtocol.keyRegion(i)
            assertEquals(colors[i],art.getPixel(r.x+59,r.y+45))
            val pixel = tiles[i].getPixel(59,45)
            assertEquals(colors[i],pixel)
            assertEquals(pixel,sim.renderedImage(i)!!.getPixel(59,45))
            assertEquals(pixel,mirrored.getPixel(r.x+59,r.y+45))
            pixel
        }
        assertEquals(9,samples.distinct().size)
        val previous = tiles[0]
        renderer.useArtwork(null)
        assertNotSame(previous,renderer.image(0,tile))
        sim.close()
    }

    @Test fun coverOnlyAppearsOnAudioWhetherPausedOrPlaying() {
        val session = app.session
        val listener = shadowOf(app.getSystemService(NotificationManager::class.java))
        val token = ComponentName(app,MediaSessionAccess::class.java)
        listener.setNotificationListenerAccessGranted(token,true)
        val external = MediaSession(app,"player")
        val player = shadowOf(external.controller)
        val manager = shadowOf(app.getSystemService(MediaSessionManager::class.java))
        manager.addController(external.controller)
        fun state(s: Int) = player.executeOnPlaybackStateChanged(P.Builder().setState(s,0,1f).setActions(P.ACTION_PLAY_PAUSE).build())
        fun metadata(color: Int) {
            val bitmap = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }
            val data = MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART,bitmap)
                .putString(MediaMetadata.METADATA_KEY_TITLE,"Unchanged title").build()
            player.executeOnMetadataChanged(data)
        }
        fun refresh() { main.idle(); session.frame() }
        fun awaitColor(c: Int) {
            refresh()
            val timeout = System.nanoTime() + 5_000_000_000L
            while (session.panelArt?.getPixel(5,5) != c && System.nanoTime() < timeout) {
                main.idle(); session.frame(); Thread.yield()
            }
            assertEquals(c,session.panelArt?.getPixel(5,5))
        }
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        state(P.STATE_PAUSED); metadata(Color.RED)
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            session.selectProfile("audio")
            awaitColor(Color.RED)
            session.deck.setPinned("focus").getOrThrow()
            val fixed = session.frame().keys
            assertNull(session.panelArt)
            state(P.STATE_PLAYING); refresh()
            assertNull(session.panelArt)
            val before = session.keyImage(0,fixed[0])
            metadata(Color.BLUE); refresh()
            assertNull(session.panelArt)
            assertSame(before,session.keyImage(0,fixed[0]))
            assertEquals("focus",session.deck.state.value.pinnedProfileId)
            assertEquals(fixed,session.frame().keys)
            session.deck.setPinned("audio").getOrThrow()
            awaitColor(Color.BLUE)
            state(P.STATE_STOPPED); refresh()
            assertNull(session.panelArt)
            state(P.STATE_PLAYING); awaitColor(Color.BLUE)
            listener.setNotificationListenerAccessGranted(token,false)
            Robolectric.buildService(MediaSessionAccess::class.java).get().onListenerDisconnected()
            refresh()
            assertNull(session.panelArt)
            assertEquals("",session.deckState.value.media.title)
            assertTrue(player.callbacks.isEmpty())
            manager.clearControllers()
            listener.setNotificationListenerAccessGranted(token,true)
            Robolectric.buildService(MediaSessionAccess::class.java).get().onListenerConnected()
            refresh()
            assertEquals("No session",session.deckState.value.media.status)
            assertNull(session.panelArt)
        }
        external.release()
    }

    @Test fun inkChangesWithEachLinesOwnBackground() {
        val image = Bitmap.createBitmap(480,480,Bitmap.Config.ARGB_8888)
        val r = KeypadProtocol.keyRegion(0)
        val dark = Color.rgb(26,39,57)
        val light = Color.rgb(231,226,211)
        image.eraseColor(light)
        Canvas(image).drawRect(r.x.toFloat(),r.y+40f,(r.x+r.width).toFloat(),r.y+80f,Paint().apply { color = dark })
        val renderer = KeyImages { null }
        renderer.useArtwork(image)
        val tile = renderer.image(0,KeyTile("LABEL","FOOTER",Color.GRAY,icon = "ICON"))
        fun count(color: Int, lines: IntRange) = lines.sumOf { y -> (20 until 99).count { x -> tile.getPixel(x,y) == color } }
        assertTrue(count(Color.BLACK,5..30) > 8)
        assertTrue(count(Color.WHITE,48..69) > 8)
        assertTrue(count(Color.BLACK,85..102) > 8)
    }

    @Test fun otherPlayerStartingAndOldCallbacksCannotLeaveStaleTitlesOrControls() {
        val session = app.session
        val manager = shadowOf(app.getSystemService(MediaSessionManager::class.java))
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app,MediaSessionAccess::class.java),true)
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val sessions = listOf("First", "Second").map { name ->
            MediaSession(app, name).also {
                shadowOf(it.controller).setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,name).build())
                shadowOf(it.controller).setPlaybackState(P.Builder().setState(P.STATE_PAUSED,0,0f).setActions(P.ACTION_PLAY).build())
            }
        }
        val second = shadowOf(sessions[1].controller)
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            sessions.forEach { manager.addController(it.controller) }
            main.idle()
            assertEquals("First",session.deckState.value.media.title)
            second.executeOnPlaybackStateChanged(P.Builder().setState(P.STATE_PLAYING,0,1f).setActions(P.ACTION_SKIP_TO_NEXT).build())
            main.idle()
            assertEquals("Second",session.deckState.value.media.title)
            assertEquals(setOf(tel.misa.mxpocket.decks.DeckCommand.NEXT),session.deckState.value.media.supported)
            val obsolete = second.callbacks.single()
            second.executeOnSessionDestroyed()
            main.idle()
            assertEquals("First",session.deckState.value.media.title)
            obsolete.onMetadataChanged(null)
            assertEquals("First",session.deckState.value.media.title)
            manager.clearControllers(); main.idle()
            assertEquals("No session",session.deckState.value.media.status)
            manager.addController(sessions[0].controller)
            session.setPluginEnabled("audio",false); main.idle()
            assertEquals("",session.deckState.value.media.title)
            assertTrue(shadowOf(sessions[0].controller).callbacks.isEmpty())
            session.setPluginEnabled("audio",true); main.idle()
            assertEquals("First",session.deckState.value.media.title)
        }
        main.idle()
        assertTrue(sessions.all { shadowOf(it.controller).callbacks.isEmpty() })
        sessions.forEach { it.release() }
    }

    @Test fun sinkRepaintsAllNineForArtAloneAndRemovesArtAfterLockOrUriRevocation() {
        val uri = Uri.parse("content://player/cover")
        var granted = true
        val context = object : ContextWrapper(app) {
            override fun checkUriPermission(u: Uri, pid: Int, uid: Int, mode: Int) =
                if (u == uri && granted && mode == Intent.FLAG_GRANT_READ_URI_PERMISSION) PackageManager.PERMISSION_GRANTED
                else PackageManager.PERMISSION_DENIED
        }
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app,MediaSessionAccess::class.java),true)
        val writes = mutableMapOf<Int, Bitmap>()
        val session = PocketSession(context) { _,_,_,_,_ ->
            object : KeypadOutput {
                override fun close() {}
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) { writes[index] = bitmap }
            }
        }
        session.selectProfile("audio")
        val ext = MediaSession(app,"player")
        val player = shadowOf(ext.controller)
        player.setPlaybackState(P.Builder().setState(P.STATE_PLAYING,0,1f).setActions(P.ACTION_PAUSE).build())
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(ext.controller)
        fun square(color: Int) = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }
        fun cover(color: Int) = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Same")
            .putBitmap(MediaMetadata.METADATA_KEY_ART,square(color)).build()
        fun awaitWrites(test: () -> Boolean) {
            val timeout = System.nanoTime() + 5_000_000_000L
            do { main.idle(); if (test()) return; Thread.yield() } while (System.nanoTime() < timeout)
            fail("Expected keypad sink repaint")
        }
        player.setMetadata(cover(Color.RED))
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java)
        ReflectionHelpers.setField(device,"mName","virtual-usb")
        session.connect(session.reserve(device)!!,context.getSystemService(UsbManager::class.java),device,{}, { error("Unexpected shortcut") })
        fun painted(color: Int) = session.panelArt?.getPixel(5,5) == color && writes.size == 9 &&
            writes.all { (i,image) -> image === session.keyImage(i,session.frame().keys[i]) }
        try {
            awaitWrites { painted(Color.RED) }
            val first = writes.toMap()
            writes.clear()
            player.executeOnMetadataChanged(cover(Color.BLUE))
            awaitWrites { painted(Color.BLUE) }
            assertTrue(writes.all { (i,image) -> image.getPixel(4,4) != first.getValue(i).getPixel(4,4) })

            val bytes = ByteArrayOutputStream().apply { square(Color.GREEN).compress(Bitmap.CompressFormat.PNG,100,this) }.toByteArray()
            val started = CountDownLatch(1)
            val gate = CountDownLatch(1)
            shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
                started.countDown()
                gate.await(3, TimeUnit.SECONDS)
                bytes.inputStream()
            }
            val local = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_ART_URI,uri.toString()).build()
            player.executeOnMetadataChanged(local); main.idle()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            granted = false
            gate.countDown()
            main.idleFor(java.time.Duration.ofSeconds(2))
            assertNull(session.panelArt)
            granted = true; main.idleFor(java.time.Duration.ofSeconds(2))
            awaitWrites { painted(Color.GREEN) }
            granted = false; writes.clear(); main.idleFor(java.time.Duration.ofSeconds(2))
            assertNull(session.panelArt)
            assertEquals(9,writes.size)

            player.executeOnMetadataChanged(cover(Color.RED)); main.idle()
            awaitWrites { painted(Color.RED) }
            writes.clear()
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); main.idle()
            assertNull(session.panelArt)
            assertEquals("",session.deckState.value.media.title)
            assertEquals(9,writes.size)
            val plain = writes.toMap()
            assertTrue(plain.all { (i,image) -> image.getPixel(4,4) != first.getValue(i).getPixel(4,4) })
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
            app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
            awaitWrites { painted(Color.RED) }
            assertEquals("Same",session.deckState.value.media.title)
        } finally { session.disconnect(); ext.release() }
    }

    @Test fun lateMetadataAndFailedProviderRecoverWithoutAnotherTrackAndPlayNeverUploadsPlainKeys() {
        val uri = Uri.parse("content://player/local-art")
        val context = object : ContextWrapper(app) {
            override fun checkUriPermission(u: Uri, pid: Int, uid: Int, mode: Int) =
                if (u == uri) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationListenerAccessGranted(
            ComponentName(app,MediaSessionAccess::class.java),true)
        val reads = AtomicInteger()
        val color = Color.rgb(211,85,89)
        val bitmap = Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG,100,this) }.toByteArray()
        val slowRead = CountDownLatch(1)
        val finishRead = CountDownLatch(1)
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
            when (reads.incrementAndGet()) {
                1 -> throw java.io.FileNotFoundException("Not ready")
                3 -> { slowRead.countDown(); finishRead.await(5,TimeUnit.SECONDS) }
            }
            bytes.inputStream()
        }
        val playerSession = MediaSession(app,"local")
        val player = shadowOf(playerSession.controller)
        fun playback(s: Int) = P.Builder().setState(s,0,1f).setActions(P.ACTION_PLAY_PAUSE).build()
        player.setPlaybackState(playback(P.STATE_PAUSED))
        shadowOf(app.getSystemService(MediaSessionManager::class.java)).addController(playerSession.controller)
        val writes = mutableListOf<Pair<Int, Bitmap>>()
        val session = PocketSession(context) { _,_,_,_,_ -> object : KeypadOutput {
            override fun close() {}
            override suspend fun setKeyImage(index: Int, bitmap: Bitmap) { writes += index to bitmap }
        } }
        session.selectProfile("audio")
        val device = ReflectionHelpers.newInstance(UsbDevice::class.java)
        ReflectionHelpers.setField(device,"mName","virtual-usb")
        session.connect(session.reserve(device)!!,context.getSystemService(UsbManager::class.java),device,{}, { error("Unexpected shortcut") })
        val local = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,"track-a")
            .putString(MediaMetadata.METADATA_KEY_TITLE,"First").putString(MediaMetadata.METADATA_KEY_ART_URI,uri.toString()).build()
        fun settle(advance: Boolean = false, test: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000L
            do {
                main.idle()
                if (test()) return
                if (advance) main.idleFor(Duration.ofMillis(10))
                Thread.yield()
            } while (System.nanoTime() < end)
            fail("Media state did not settle")
        }
        try {
            main.idle()
            assertNull(session.panelArt)
            player.setMetadata(local) // Simulate an incomplete initial snapshot and a missed metadata callback.
            main.idleFor(Duration.ofSeconds(2))
            settle { reads.get() == 1 }
            repeat(10) { main.idle() }
            assertEquals(1,reads.get())
            settle(advance = true) { session.panelArt?.getPixel(4,4) == color }
            assertEquals(2,reads.get())

            // Both a phone-side change and repeated keypad commands must reach the actual volume tile without advancing time.
            val audio = app.getSystemService(AudioManager::class.java)
            val art = session.panelArt
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,2,0)
            app.contentResolver.notifyChange(Settings.System.getUriFor("volume_music"),null)
            fun expectVolume() {
                main.idle()
                val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                val frame = session.frame()
                assertEquals("$actual / ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}",frame.keys[7].label)
                assertSame(session.keyImage(7,frame.keys[7]),writes.last { it.first == 7 }.second)
            }
            expectVolume()
            repeat(3) {
                assertTrue(session.runDeckAction("media.volume-up").success)
                expectVolume()
            }
            assertSame(art,session.panelArt)
            assertEquals(2,reads.get())

            writes.clear()
            player.executeOnPlaybackStateChanged(playback(P.STATE_PLAYING))
            player.executeOnMetadataChanged(local)
            main.idle()
            assertTrue(slowRead.await(3,TimeUnit.SECONDS))
            assertEquals("Pause",session.frame().keys[1].label)
            assertSame(art,session.panelArt)
            assertTrue(writes.any { it.first == 1 })
            assertTrue(writes.filter { it.first == 1 }.all { it.second.getPixel(4,4) == color })

            // A new track using the same URI must not borrow the previous pixels, even if that old read finishes late.
            player.executeOnMetadataChanged(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID,"track-b")
                .putString(MediaMetadata.METADATA_KEY_TITLE,"Second").build())
            main.idle()
            assertNull(session.panelArt)
            finishRead.countDown()
            main.idle()
            assertNull(session.deckState.value.media.artwork)
        } finally { finishRead.countDown(); session.disconnect(); playerSession.release() }
    }
}
