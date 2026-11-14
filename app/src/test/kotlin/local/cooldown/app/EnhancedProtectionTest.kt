package local.cooldown.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import local.cooldown.app.monitor.*
import local.cooldown.core.WECHAT_PACKAGE
import local.cooldown.core.Mood
import local.cooldown.core.BehaviorEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnhancedProtectionTest {
    @Test fun `good mood and visiting the guard app retain progress then automatically lock on third return`() = withEnhanced { context, service ->
        val repo = (context as CooldownApplication).repository
        val session = EnhancedGuardService.activeSession()!!
        val engine = ReflectionHelpers.getField<BehaviorEngine>(session, "engine")
        send(service, WECHAT_PACKAGE)
        pump { ReflectionHelpers.getField<ComposeView?>(overlays(), "view") != null }
        repeat(3) { index ->
            ReflectionHelpers.callInstanceMethod<Any?>(session, "chooseMood", ReflectionHelpers.ClassParameter.from(Mood::class.java, Mood.GOOD))
            pump { ReflectionHelpers.getField<ComposeView?>(overlays(), "view") == null }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(15))
            send(service, if (index == 1) context.packageName else "com.miui.home")
            pump { !engine.isInWechat() }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(15)); send(service, WECHAT_PACKAGE)
            if (index < 2) {
                pump { EnhancedRuntime.health.value.behaviorHint.contains("${index + 1}/3") }
                assertNull(repo.state.value!!.deadline)
            } else {
                pump { repo.state.value!!.deadline != null && ReflectionHelpers.getField<ComposeView?>(overlays(), "view") != null }
                assertEquals("automatic", repo.state.value!!.deadline!!.source)
                assertEquals(5, repo.state.value!!.deadline!!.minutes)
                assertTrue(repo.state.value!!.deadline!!.reason.contains("3 次"))
            }
        }
    }
    @Test fun `window received behind keyguard is protected on unlock without another window event`() = withEnhanced { context, service ->
        val repo = (context as CooldownApplication).repository
        runBlocking { repo.startLock(5, "锁屏恢复测试", "manual") }
        val deadline = repo.state.value!!.deadline
        val keyguard = Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        send(service, WECHAT_PACKAGE)
        val overlay = overlays()
        pump { EnhancedRuntime.health.value.heartbeat != null }
        assertNull(ReflectionHelpers.getField<ComposeView?>(overlay, "view"))
        keyguard.setKeyguardLocked(false)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1200))
        pump { ReflectionHelpers.getField<ComposeView?>(overlay, "view") != null }
        assertEquals(deadline, repo.state.value!!.deadline)
    }

    @Test fun `reconnected service accepts windows with a fresh scope and preserves active deadline`() = withEnhanced { context, service ->
        val repo = (context as CooldownApplication).repository
        runBlocking { repo.startLock(5, "重新连接测试", "manual") }
        val deadline = repo.state.value!!.deadline
        send(service, WECHAT_PACKAGE)
        val previous = EnhancedGuardService.activeSession()!!
        pump { ReflectionHelpers.getField<ComposeView?>(overlays(), "view") != null }
        service.onUnbind(Intent())
        ReflectionHelpers.callInstanceMethod<Any?>(service, "onServiceConnected")
        assertNotSame(previous, EnhancedGuardService.activeSession())
        send(service, WECHAT_PACKAGE)
        pump { ReflectionHelpers.getField<ComposeView?>(overlays(), "view") != null }
        assertEquals(deadline, repo.state.value!!.deadline)
    }

    @Test fun `expiry by another observer clears lock-period risk before the next normal visit`() = withEnhanced { context, service ->
        val repo = (context as CooldownApplication).repository
        runBlocking { repo.startLock(5, "到期同步测试", "manual") }
        send(service, WECHAT_PACKAGE)
        pump { ReflectionHelpers.getField<ComposeView?>(overlays(), "view") != null }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(200))
        repeat(3) {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(15)); send(service, "com.miui.home")
            pump { ReflectionHelpers.getField<ComposeView?>(overlays(), "view") == null }
            ShadowSystemClock.advanceBy(Duration.ofSeconds(15)); send(service, WECHAT_PACKAGE)
            pump { ReflectionHelpers.getField<ComposeView?>(overlays(), "view") != null }
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
        runBlocking { assertTrue(repo.expireIfDue()) }
        pump { ReflectionHelpers.getField<ComposeView?>(overlays(), "view") == null }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15)); send(service, "com.miui.home")
        pump { ReflectionHelpers.getField<ComposeView?>(overlays(), "view") == null }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(15)); send(service, WECHAT_PACKAGE)
        pump { EnhancedRuntime.health.value.behaviorHint.contains("1/3") || repo.state.value!!.deadline != null }
        assertNull(repo.state.value!!.deadline)
        assertTrue(EnhancedRuntime.health.value.behaviorHint.contains("1/3"))
    }

    private fun overlays(): OverlayController = ReflectionHelpers.getField(EnhancedGuardService.activeSession()!!, "overlays")
    private fun withEnhanced(block: (Context, EnhancedGuardService) -> Unit) {
        val context: Context = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
        Shadows.shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val life = Robolectric.buildService(EnhancedGuardService::class.java).create()
        try {
            ReflectionHelpers.callInstanceMethod<Any?>(life.get(), "onServiceConnected")
            runBlocking { withTimeout(10_000) { (context as CooldownApplication).repository.state.filterNotNull().first() } }
            block(context, life.get())
        } finally { life.destroy() }
    }
    @Test fun `cold lock reappears on every real reentry and expires without a running foreground service`() {
        val context: Context = RuntimeEnvironment.getApplication()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 1)
        Shadows.shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        Shadows.shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val life = Robolectric.buildService(EnhancedGuardService::class.java).create()
        try {
            val service = life.get()
            ReflectionHelpers.callInstanceMethod<Any?>(service, "onServiceConnected")
            val repo = (context as CooldownApplication).repository
            runBlocking { withTimeout(10_000) { repo.state.filterNotNull().first() }; repo.startLock(5, "持续保护测试", "manual") }
            val deadline = repo.state.value!!.deadline!!
            send(service, WECHAT_PACKAGE)
            val session = EnhancedGuardService.activeSession()!!
            val overlays = ReflectionHelpers.getField<OverlayController>(session, "overlays")
            pump { ReflectionHelpers.getField<ComposeView?>(overlays, "view") != null }
            val first = ReflectionHelpers.getField<ComposeView>(overlays, "view")
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, (first.layoutParams as WindowManager.LayoutParams).type)
            for (i in 1..5) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(10))
                send(service, "another.app")
                pump { ReflectionHelpers.getField<ComposeView?>(overlays, "view") == null }
                ShadowSystemClock.advanceBy(Duration.ofMillis(10))
                send(service, WECHAT_PACKAGE)
                pump { ReflectionHelpers.getField<ComposeView?>(overlays, "view") != null }
                assertEquals(deadline, repo.state.value!!.deadline)
            }
            assertFalse(MonitorRuntime.status.value.running)
            ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
            pump { ReflectionHelpers.getField<ComposeView?>(overlays, "view") == null && repo.state.value!!.deadline == null }
            assertTrue(repo.state.value!!.recovered)
        } finally { life.destroy() }
        assertFalse(EnhancedRuntime.connected.value)
    }
    private fun send(service: EnhancedGuardService, pkg: String) {
        val e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        e.packageName = pkg; e.className = if (pkg == service.packageName) MainActivity::class.java.name else "$pkg.Activity"
        e.eventTime = SystemClock.uptimeMillis()
        service.onAccessibilityEvent(e); e.recycle()
    }
    private fun pump(condition: () -> Boolean) {
        repeat(500) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Protection did not reach expected state")
    }
}
