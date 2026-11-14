package local.cooldown.app.monitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import local.cooldown.app.CooldownApplication

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { (application as CooldownApplication).repository }
    private lateinit var feed: UsageEventSource
    private lateinit var session: ProtectionSession
    private var job: Job? = null
    override fun onCreate() {
        super.onCreate(); foreground("正在启动本地监测")
        feed = UsageEventSource(this)
        session = ProtectionSession(this, false, preferred = { !EnhancedRuntime.connected.value })
    }
    private fun foreground(message: String) {
        val notification = GuardNotifications.notification(this, message)
        if (Build.VERSION.SDK_INT >= 34) startForeground(GuardNotifications.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(GuardNotifications.FOREGROUND_ID, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) job = scope.launch { monitor() }
        return START_STICKY
    }
    private suspend fun monitor() {
        try {
            repository.state.filterNotNull().first()
            var enhancedBefore = EnhancedRuntime.connected.value
            var ready = false
            while (currentCoroutineContext().isActive) {
                val state = repository.state.value ?: break
                if (!state.settings.monitoringWanted && repository.remaining(state) == 0L) break
                if (!PermissionGate.read(this).complete) {
                    MonitorRuntime.status.value = MonitorStatus(error = "使用情况或悬浮窗权限未开启；请检查保护设置。")
                    break
                }
                val enhanced = EnhancedRuntime.connected.value
                if (enhanced != enhancedBefore) { session.reset(); feed.reset(); enhancedBefore = enhanced }
                val changes = withContext(Dispatchers.IO) { feed.poll() }
                val active = EnhancedGuardService.activeSession() ?: session
                active.accept(changes)
                MonitorRuntime.status.value = MonitorStatus(true, SystemClock.elapsedRealtime(), error = active.error(),
                    lastEntryWall = active.lastEntryWall(), behaviorHint = active.behaviorHint())
                if (!ready) { foreground("正在本地监测微信进出 · 可一键冷静"); ready = true }
                delay(if (!getSystemService(PowerManager::class.java).isInteractive) 2000 else if (enhanced) 1500 else 350)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { MonitorRuntime.status.value = MonitorStatus(error = "后台补充检测已停止：${e.javaClass.simpleName}。请打开应用重试。") }
        finally { MonitorRuntime.status.value = MonitorRuntime.status.value.copy(running = false); stopSelf() }
    }
    override fun onDestroy() { job?.cancel(); scope.cancel(); if (::session.isInitialized) session.close(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        fun start(context: Context) {
            try { context.startForegroundService(Intent(context, MonitorService::class.java)) }
            catch (_: RuntimeException) { MonitorRuntime.status.value = MonitorStatus(error = "系统未允许启动后台补充检测，请在主页重试。") }
        }
        fun stop(context: Context) { context.stopService(Intent(context, MonitorService::class.java)) }
    }
}
