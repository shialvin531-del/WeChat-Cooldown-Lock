package local.cooldown.app

import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Process
import local.cooldown.app.monitor.*
import local.cooldown.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.Duration
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35], application = Application::class)
class UsageSourceTest {
    private lateinit var context: Context
    private lateinit var source: UsageEventSource
    private lateinit var usage: ShadowUsageStatsManager
    private var wall = 1_700_000_000_000L
    private var elapsed = 100_000L
    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        val info = ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "test.launcher"; name = "Home" } }
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), info)
        usage = Shadows.shadowOf(context.getSystemService(UsageStatsManager::class.java))
        source = UsageEventSource(context, { wall }, { elapsed })
    }
    private fun event(pkg: String, at: Long, type: Int = UsageEvents.Event.ACTIVITY_RESUMED, cls: String = "AnyActivity") {
        usage.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent().setPackage(pkg).setClass(cls).setEventType(type).setTimeStamp(at).build())
    }
    @Test fun `usage events produce app boundaries while internal activity resumes stay in one visit`() {
        source.poll()
        val now = wall
        event(WECHAT_PACKAGE, now + 100, cls = "Chat")
        event(WECHAT_PACKAGE, now + 200, cls = "Moments")
        event(WECHAT_PACKAGE, now + 250, UsageEvents.Event.ACTIVITY_PAUSED)
        event(WECHAT_PACKAGE, now + 300, cls = "ChatAgain")
        event("com.android.systemui", now + 350)
        event(WECHAT_PACKAGE, now + 375, UsageEvents.Event.ACTIVITY_PAUSED)
        event("test.launcher", now + 400)
        wall += 1000; elapsed += 1000
        val changes = source.poll()
        assertEquals(2, changes.size)
        val engine = BehaviorEngine()
        val actions = changes.flatMap { engine.foreground(it.pkg, it.kind, it.elapsed, GuardSettings(), LocalTime.NOON) }
        assertEquals(1, actions.filterIsInstance<BehaviorAction.Greeting>().size)
        assertEquals(1, actions.filterIsInstance<BehaviorAction.Session>().size)
        assertEquals(275, actions.filterIsInstance<BehaviorAction.Session>().single().duration)
        assertFalse(engine.isInWechat())
        assertEquals(AppKind.HOME, changes.last().kind)
        assertTrue(source.poll().isEmpty())
    }
    @Test fun `bootstrap only seeds latest foreground and never replays historical bursts`() {
        val now = wall
        event(WECHAT_PACKAGE, now - 50_000)
        event("test.launcher", now - 40_000)
        event(WECHAT_PACKAGE, now - 30_000)
        event("com.android.systemui", now - 20_000)
        val changes = source.poll()
        assertEquals(1, changes.size); assertEquals(WECHAT_PACKAGE, changes.single().pkg)
    }
    @Test fun `late launcher event repairs a missed exit after duplicate wechat resumes`() {
        source.poll()
        val start = wall
        event(WECHAT_PACKAGE, start + 100)
        event(WECHAT_PACKAGE, start + 800, cls = "ChatAgain")
        wall += 1000; elapsed += 1000
        val engine = BehaviorEngine()
        source.poll().forEach { engine.foreground(it.pkg, it.kind, it.elapsed, GuardSettings(), LocalTime.NOON) }
        // Published after the old query's end, but its timestamp belongs to that old interval.
        event("test.launcher", start + 500)
        wall += 1000; elapsed += 1000
        val repaired = source.poll()
        assertEquals(listOf("test.launcher", WECHAT_PACKAGE), repaired.map { it.pkg })
        val actions = repaired.flatMap { engine.foreground(it.pkg, it.kind, it.elapsed, GuardSettings(), LocalTime.NOON) }
        assertEquals(1, actions.filterIsInstance<BehaviorAction.Greeting>().size)
        assertTrue(engine.isInWechat()); assertTrue(source.poll().isEmpty())
    }
    @Test fun `multiple rapid exits inside one poll retain each true entry without a false auto lock`() {
        source.poll(); val start = wall
        event(WECHAT_PACKAGE, start + 100); event("test.launcher", start + 200)
        event(WECHAT_PACKAGE, start + 300); event("test.launcher", start + 400); event(WECHAT_PACKAGE, start + 500)
        wall += 1000; elapsed += 1000
        val engine = BehaviorEngine()
        val actions = source.poll().flatMap { engine.foreground(it.pkg, it.kind, it.elapsed, GuardSettings(), LocalTime.NOON) }
        assertEquals(3, actions.filterIsInstance<BehaviorAction.Greeting>().size)
        assertFalse(actions.any { it is BehaviorAction.AutoLock })
        assertTrue(engine.behaviorHint().contains("不足"))
    }
    @Test fun `permission gate reflects app ops and overlay permission independently`() {
        val ops = context.getSystemService(AppOpsManager::class.java)
        Shadows.shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_IGNORED)
        ShadowSettings.setCanDrawOverlays(false)
        assertFalse(PermissionGate.read(context).complete)
        Shadows.shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(PermissionGate.read(context).usage); assertFalse(PermissionGate.read(context).overlay)
        ShadowSettings.setCanDrawOverlays(true)
        assertTrue(PermissionGate.read(context).complete)
    }
    @Test fun `enhancement is system bound and cannot retrieve content or perform gestures`() {
        val service = context.packageManager.getServiceInfo(ComponentName(context, EnhancedGuardService::class.java), 0)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission)
        context.resources.getXml(R.xml.enhanced_guard).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) { }
            val ns = "http://schemas.android.com/apk/res/android"
            assertFalse(xml.getAttributeBooleanValue(ns, "canRetrieveWindowContent", true))
            assertFalse(xml.getAttributeBooleanValue(ns, "canPerformGestures", true))
            assertEquals(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, xml.getAttributeIntValue(ns, "accessibilityEventTypes", 0))
        }
    }
    @Test fun `packaged permissions have no network or chat access`() {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        val permissions = info.requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.PACKAGE_USAGE_STATS" in permissions)
        assertTrue("android.permission.SYSTEM_ALERT_WINDOW" in permissions)
        assertFalse("android.permission.INTERNET" in permissions)
        assertFalse("android.permission.READ_CONTACTS" in permissions)
        val service = context.packageManager.getServiceInfo(ComponentName(context, MonitorService::class.java), 0)
        assertFalse(service.exported)
        assertEquals(0, info.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}
