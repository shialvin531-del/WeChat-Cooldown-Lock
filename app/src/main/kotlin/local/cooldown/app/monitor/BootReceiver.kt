package local.cooldown.app.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import local.cooldown.app.CooldownApplication

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val app = context.applicationContext as CooldownApplication
        app.appScope.launch {
            try {
                val state = withTimeout(6000) { app.repository.state.filterNotNull().first() }
                if (state.settings.monitoringWanted && PermissionGate.read(context).complete) {
                    MonitorService.start(context)
                    if (MonitorRuntime.status.value.error != null) GuardNotifications.recovery(context)
                }
            } catch (_: Exception) {
                MonitorRuntime.status.value = MonitorStatus(error = "开机后未能恢复监测，请打开应用检查。")
                GuardNotifications.recovery(context)
            } finally { pending.finish() }
        }
    }
}
