package local.cooldown.app.monitor

import android.app.AppOpsManager
import android.content.Context
import android.os.Process
import android.provider.Settings

data class PermissionState(val usage: Boolean, val overlay: Boolean) { val complete get() = usage && overlay }

object PermissionGate {
    fun read(context: Context): PermissionState {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val usage = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        return PermissionState(usage, Settings.canDrawOverlays(context))
    }
}
