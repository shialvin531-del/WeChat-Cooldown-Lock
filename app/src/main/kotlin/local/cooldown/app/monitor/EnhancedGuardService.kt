package local.cooldown.app.monitor

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import local.cooldown.app.MainActivity
import java.lang.ref.WeakReference

object EnhancedRuntime {
    val connected = MutableStateFlow(false)
    val health = MutableStateFlow(SessionHealth())
}

/** Only window package/class metadata; never reads nodes, text, input or screenshots. */
class EnhancedGuardService : AccessibilityService() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: ProtectionSession
    private lateinit var classifier: AppClassifier
    private val keyboards = mutableSetOf<String>()
    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.cancel()
        if (::session.isInitialized) session.close()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        instance = WeakReference(this)
        session = ProtectionSession(this, true, onHealth = { health ->
            if (instance.get() == this) EnhancedRuntime.health.value = health
        })
        classifier = AppClassifier(this)
        keyboards.clear()
        keyboards += getSystemService(InputMethodManager::class.java).inputMethodList.map { it.packageName }
        EnhancedRuntime.connected.value = true
        // Reconnection can happen while WeChat is already visible, without a fresh window event.
        scope.launch {
            try {
                if (PermissionGate.read(this@EnhancedGuardService).usage) {
                    val initial = withContext(Dispatchers.IO) { UsageEventSource(this@EnhancedGuardService).poll() }
                    session.accept(initial)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Window events remain available when the supplemental query fails. */ }
        }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::session.isInitialized || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in keyboards) return
        if (pkg == packageName && event.className?.toString() != MainActivity::class.java.name) return
        val age = SystemClock.uptimeMillis() - event.eventTime
        val elapsed = SystemClock.elapsedRealtime() - if (event.eventTime > 0 && age in 0..10_000) age else 0
        scope.launch {
            try {
                session.accept(listOf(ForegroundChange(pkg, classifier.kind(pkg), elapsed)))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { MonitorRuntime.status.value = MonitorRuntime.status.value.copy(error = "增强检测遇到暂时错误，持续保护正在重试。") }
        }
    }
    override fun onInterrupt() { /* Feedback interruption must not erase an active protection session. */ }
    override fun onUnbind(intent: Intent?): Boolean { close(); return super.onUnbind(intent) }
    override fun onDestroy() { close(); super.onDestroy() }
    private fun close() {
        scope.cancel(); if (::session.isInitialized) session.close()
        if (instance.get() == this) { instance.clear(); EnhancedRuntime.connected.value = false; EnhancedRuntime.health.value = SessionHealth() }
    }
    companion object {
        private var instance = WeakReference<EnhancedGuardService>(null)
        fun activeSession(): ProtectionSession? = instance.get()?.let { if (it::session.isInitialized) it.session else null }
    }
}
