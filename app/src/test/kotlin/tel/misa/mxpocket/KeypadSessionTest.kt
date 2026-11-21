package tel.misa.mxpocket

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowUsbManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import tel.misa.mxpocket.driver.KeyEvent
import tel.misa.mxpocket.plugins.PhoneApp
import tel.misa.mxpocket.decks.DeckCommand
import tel.misa.mxpocket.decks.StudioActivity

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = KeypadSessionTest.FakeApp::class, shadows = [KeypadSessionTest.ConsentUsb::class])
@LooperMode(LooperMode.Mode.PAUSED)
class KeypadSessionTest {
    class FakeApp : PocketApplication() {
        var claims = 0
        var closes = 0
        lateinit var emit: (KeyEvent) -> Unit
        override fun createSession() = PocketSession(this) { _, _, event, _, _ ->
            emit = event
            claims++
            object : KeypadOutput {
                override fun close() { closes++ }
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {}
            }
        }
    }
    @Implements(UsbManager::class)
    class ConsentUsb : ShadowUsbManager() {
        lateinit var reply: PendingIntent
        @Implementation fun requestPermission(device: UsbDevice, callback: PendingIntent) {
            check(!hasPermission(device))
            reply = callback
        }
    }

    private val app get() = RuntimeEnvironment.getApplication() as FakeApp
    private val usb get() = app.getSystemService(UsbManager::class.java)
    private val access get() = UsbAccess(app)
    private val main get() = shadowOf(Looper.getMainLooper())

    @Before fun grantNotifications() {
        shadowOf(app).grantPermissions("tel.misa.mxpocket.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        if (Build.VERSION.SDK_INT >= 33) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun device(name: String, product: Int = 0xc354) =
        ReflectionHelpers.newInstance(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mName", name)
            ReflectionHelpers.setField(it, "mVendorId", 0x046d)
            ReflectionHelpers.setField(it, "mProductId", product)
            ReflectionHelpers.setField(it, "mConfigurations", emptyArray<UsbConfiguration>())
        }
    private fun attached(device: UsbDevice) = Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        .putExtra(UsbManager.EXTRA_DEVICE, device)
    private fun deliver(intent: Intent) {
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().use { /* no button press */ }
    }

    @Test fun manualPermissionCanBeDeniedRetriedAndCompletedAfterRecreationWithOptInOff() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, false)
        assertFalse(access.automatic)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            fun click() = controller.get().findViewById<View>(R.id.pocket_connect).performClick()
            click()
            (shadowOf(usb) as ConsentUsb).reply.send()
            main.idle()
            assertNull(shadowOf(app).nextStartedService)
            assertTrue(controller.get().findViewById<TextView>(R.id.pocket_status).text.contains("denied"))
            click()
            val grant = (shadowOf(usb) as ConsentUsb).reply
            controller.recreate()
            shadowOf(usb).addOrUpdateUsbDevice(real, true)
            grant.send()
            main.idle()
            val command = shadowOf(app).nextStartedService
            assertNotNull(command)
            val service = Robolectric.buildService(KeypadService::class.java).create()
            service.get().onStartCommand(command, 0, 1)
            controller.recreate()
            controller.pause().stop()
            assertEquals(1, app.claims)
            assertEquals(0, app.closes)
            shadowOf(usb).removeUsbDevice(real)
            app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, real))
            main.idle()
            assertNull(app.session.request)
            assertEquals(1, app.closes)
            assertTrue(shadowOf(service.get()).isStoppedBySelf)
            service.destroy()
            controller.start().resume()
        }
    }

    @Test fun pendingPermissionCannotStartAfterDetachAndLockedOrHiddenNotificationCannotStart() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, false)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            controller.get().findViewById<View>(R.id.pocket_connect).performClick()
            val stale = (shadowOf(usb) as ConsentUsb).reply
            shadowOf(usb).removeUsbDevice(real)
            app.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, real))
            main.idle()
            assertEquals(app.getString(R.string.connect), controller.get().findViewById<TextView>(R.id.pocket_connect).text)
            shadowOf(usb).addOrUpdateUsbDevice(real, true)
            stale.send()
            main.idle()
            assertNull(shadowOf(app).nextStartedService)
            access.automatic = true
            val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
            lock.setKeyguardLocked(true)
            controller.newIntent(attached(real))
            assertNull(shadowOf(app).nextStartedService)
            lock.setKeyguardLocked(false)
            shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
            controller.get().findViewById<View>(R.id.pocket_connect).performClick()
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun attachRequiresOptInOneRealMatchingDeviceAndLiveGrant() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        deliver(attached(real))
        assertNull(shadowOf(app).nextStartedService)
        access.automatic = true
        val unrelated = device(real.deviceName, 123)
        shadowOf(usb).removeUsbDevice(real)
        shadowOf(usb).addOrUpdateUsbDevice(unrelated, true)
        deliver(attached(real)) // Supported extra cannot substitute for a different real device.
        assertNull(shadowOf(app).nextStartedService)
        shadowOf(usb).removeUsbDevice(unrelated)
        shadowOf(usb).addOrUpdateUsbDevice(real, false)
        deliver(attached(real))
        assertNull(shadowOf(app).nextStartedService)
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        deliver(attached(real))
        assertNotNull(shadowOf(app).nextStartedService)
        val ticket = app.session.request
        deliver(attached(real))
        assertEquals(ticket, app.session.request)
        assertNull(shadowOf(app).nextStartedService)
        app.session.disconnect()
        deliver(attached(real))
        assertNull(shadowOf(app).nextStartedService)
        val next = device("replugged-keypad")
        shadowOf(usb).removeUsbDevice(real)
        shadowOf(usb).addOrUpdateUsbDevice(next, true)
        deliver(attached(next))
        assertNotNull(shadowOf(app).nextStartedService)
        assertNotEquals(ticket, app.session.request)
        shadowOf(usb).removeUsbDevice(next)
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        deliver(attached(real)) // Arrival outruns detach of the previous reservation.
        assertNotNull(shadowOf(app).nextStartedService)
        assertEquals(real.deviceName, app.session.request?.name)
    }

    @Test fun serviceRevalidatesRevokedGrantAndRejectsStartAfterProcessLoss() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        access.automatic = true
        deliver(attached(real))
        val command = shadowOf(app).nextStartedService
        assertNotNull(command)
        shadowOf(usb).revokePermission(real, app.packageName)
        val controller = Robolectric.buildService(KeypadService::class.java).create()
        val service = controller.get()
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(command, 0, 1))
        assertNull(app.session.request)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertNull(shadowOf(service).lastForegroundNotification)
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        service.onStartCommand(command, 0, 2) // no live reservation
        assertNull(shadowOf(service).lastForegroundNotification)
        controller.destroy()
    }

    @Test fun servicePromotesOnceAndOnlyItsCurrentNotificationCanStopIt() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        val ticket = app.session.reserve(real)!!
        val command = KeypadService.command(app, ticket)
        val controller = Robolectric.buildService(KeypadService::class.java).create()
        val service = controller.get()
        service.onStartCommand(command, 0, 1)
        val notice = shadowOf(service).lastForegroundNotification
        assertNotNull(notice.contentIntent)
        if (Build.VERSION.SDK_INT >= 29) assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            service.foregroundServiceType)
        val stop = shadowOf(notice.actions.single().actionIntent).savedIntent
        service.onStartCommand(command, 0, 2)
        service.onStartCommand(Intent(stop).setData(null), 0, 3)
        assertEquals(ticket, app.session.request)
        service.onStartCommand(stop, 0, 4)
        assertNull(app.session.request)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun screenReplacementAndBackgroundKeepOneInputOwnerAndFinishInFlightLcdBeforeSwitch() {
        val real = device("test-keypad")
        val uploads = mutableListOf<Int>()
        val nextPageImage = CompletableDeferred<Unit>()
        var closed = 0
        var opens = 0
        var stops = 0
        lateinit var event: (KeyEvent) -> Unit
        lateinit var driverFailed: (Throwable) -> Unit
        val session = PocketSession(app) { _, _, onEvent, _, error ->
            opens++
            event = onEvent
            driverFailed = error
            object : KeypadOutput {
                override fun close() { closed++ }
                override suspend fun setKeyImage(index: Int, bitmap: Bitmap) {
                    uploads.add(index)
                    if (uploads.size == 10) nextPageImage.await()
                }
            }
        }
        var handoff: PhoneApp? = null
        val screen = object : PocketSession.Screen {
            override fun render() {}
            override fun openApp(app: PhoneApp): Boolean = error("Background action used an old Activity")
        }
        session.attach(screen)
        val ticket = session.reserve(real)!!
        session.connect(ticket, usb, real, { stops++ }, { handoff = (it.target as PocketSession.Target.Phone).app })
        main.idle()
        session.detach(screen)
        event(KeyEvent(1, true))
        event(KeyEvent(1, false))
        main.idle()
        assertEquals("Pause", session.plugins.render().keys[0].label)
        // Focus's updated Start/Pause tile is now in flight. Switch to Phone and try Camera.
        event(KeyEvent(0x01a2, true)) // Focus -> Media
        event(KeyEvent(0x01a2, false))
        event(KeyEvent(0x01a2, true)) // Media -> Phone, while the old frame is in flight.
        event(KeyEvent(0x01a2, false))
        event(KeyEvent(1, true))
        event(KeyEvent(1, false))
        main.idle()
        assertNull(handoff)
        assertEquals((0..8).toList() + 0, uploads)
        nextPageImage.complete(Unit)
        main.idle()
        assertEquals((0..8).toList() + 0 + (0..8), uploads)
        event(KeyEvent(1, true))
        main.idle()
        assertEquals(PhoneApp.CAMERA, handoff)
        session.attach(screen)
        assertNull(session.reserve(real))
        assertEquals("phone", session.plugins.selectedId)
        assertEquals(1, opens)
        session.disconnect()
        assertEquals(1, closed)
        assertEquals(1, stops)
        event(KeyEvent(0x01a1, true))
        driverFailed(IllegalStateException("Late error"))
        val nextTicket = session.reserve(real)!!
        main.idle()
        assertEquals(nextTicket, session.request)
        assertEquals("phone", session.plugins.selectedId)
    }

    @Test fun backgroundPhoneKeyOffersExactSingleTapTargetAndRejectsStaleTaps() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        val ticket = app.session.reserve(real)!!
        val service = Robolectric.buildService(KeypadService::class.java).create()
        service.get().onStartCommand(KeypadService.command(app, ticket), 0, 1)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            fun press(key: Int) {
                app.emit(KeyEvent(key, true))
                app.emit(KeyEvent(key, false))
                main.idle()
            }
            fun shortcut(): Intent = shadowOf(manager.getNotification(PHONE_NOTICE).contentIntent).savedIntent
            fun tap(intent: Intent): Intent? =
                Robolectric.buildActivity(PhoneActionActivity::class.java, intent).setup().use {
                    shadowOf(it.get()).nextStartedActivity
                }
            press(0x01a2)
            press(0x01a2)
            press(1)
            assertEquals("android.media.action.STILL_IMAGE_CAMERA", shadowOf(controller.get()).nextStartedActivity?.action)
            controller.pause().stop()
            press(1)
            assertNull(shadowOf(app).nextStartedActivity)
            val camera = shortcut()
            assertEquals(PhoneActionActivity::class.java.name, camera.component?.className)
            assertEquals("Open Camera", manager.getNotification(PHONE_NOTICE).extras.getString(Notification.EXTRA_TITLE))
            press(3)
            val dialer = shortcut()
            assertNull(tap(camera))
            val target = tap(dialer)
            assertEquals(Intent.ACTION_DIAL, target?.action)
            assertEquals("tel:", target?.dataString)
            assertNull(tap(dialer))
            press(1)
            val missing = shortcut()
            shadowOf(app).checkActivities(true)
            try {
                assertNull(tap(missing))
                assertEquals("No app can handle CAMERA.", app.session.status)
            } finally {
                shadowOf(app).checkActivities(false)
            }
            // A system grant alone is not consent to the feature. Revocation takes effect at the next press.
            ShadowSettings.setCanDrawOverlays(true)
            press(1)
            assertNull(shadowOf(app).nextStartedActivity)
            DirectShortcuts(app).enabled = true
            press(3)
            val direct = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_DIAL, direct?.action)
            assertEquals("tel:", direct?.dataString)
            app.emit(KeyEvent(1, true))
            app.emit(KeyEvent(1, false))
            SystemClock.sleep(2_001) // Backlogged input must not interrupt an unrelated Activity.
            main.idle()
            assertNull(shadowOf(app).nextStartedActivity)
            ShadowSettings.setCanDrawOverlays(false)
            press(1)
            assertNull(shadowOf(app).nextStartedActivity)
            val locked = shortcut()
            val lock = shadowOf(app.getSystemService(KeyguardManager::class.java))
            lock.setKeyguardLocked(true)
            ShadowSettings.setCanDrawOverlays(true)
            press(1)
            assertNull(shadowOf(app).nextStartedActivity)
            assertNull(tap(locked))
            assertNull(tap(shortcut()))
            lock.setKeyguardLocked(false)
            DirectShortcuts(app).enabled = false
            press(1)
            val expired = shortcut()
            SystemClock.sleep(PocketSession.PHONE_ACTION_TTL + 1)
            assertNull(tap(expired))
            press(1)
            val revoked = shortcut()
            shadowOf(usb).revokePermission(real, app.packageName)
            assertNull(tap(revoked))
            shadowOf(usb).addOrUpdateUsbDevice(real, true)
            press(1)
            val disconnected = shortcut()
            app.session.disconnect()
            assertNull(manager.getNotification(PHONE_NOTICE))
            assertNull(tap(disconnected))
            controller.start().resume()
        }
        service.destroy()
    }

    @Test fun studioMayOfferAnUnlockedFreshOpenButNeverBackgroundCaptureOrOverlayLaunch() {
        val real = device("test-keypad")
        shadowOf(usb).addOrUpdateUsbDevice(real, true)
        val ticket = app.session.reserve(real)!!
        val service = Robolectric.buildService(KeypadService::class.java).create()
        service.get().onStartCommand(KeypadService.command(app, ticket), 0, 1)
        val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
        app.session.plugins.select("studio")
        main.idle()
        fun press(key: Int) {
            app.emit(KeyEvent(key, true)); app.emit(KeyEvent(key, false)); main.idle()
        }
        ShadowSettings.setCanDrawOverlays(true)
        DirectShortcuts(app).enabled = true
        press(2) // Record without a resumed Studio.
        assertNull(manager.getNotification(PHONE_NOTICE))
        assertFalse(app.session.deckState.value.studio.recording)
        assertFalse(app.session.runDeckAction(DeckCommand.STUDIO.id).success)
        press(8)
        assertNull(shadowOf(app).nextStartedActivity)
        val open = shadowOf(manager.getNotification(PHONE_NOTICE).contentIntent).savedIntent
        Robolectric.buildActivity(PhoneActionActivity::class.java, open).setup().use {
            val target = shadowOf(it.get()).nextStartedActivity
            assertEquals(StudioActivity::class.java.name, target.component?.className)
            assertNull(target.extras) // No capture action can be embedded in this handoff.
        }
        press(8)
        val stale = shadowOf(manager.getNotification(PHONE_NOTICE).contentIntent).savedIntent
        shadowOf(usb).revokePermission(real, app.packageName)
        Robolectric.buildActivity(PhoneActionActivity::class.java, stale).setup().use {
            assertNull(shadowOf(it.get()).nextStartedActivity)
        }
        app.session.disconnect()
        service.destroy()
    }
}
