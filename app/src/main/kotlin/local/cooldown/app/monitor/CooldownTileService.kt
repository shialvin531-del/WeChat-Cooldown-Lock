package local.cooldown.app.monitor

import android.os.Build
import android.annotation.SuppressLint
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class CooldownTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_ACTIVE; label = "一键冷静"; updateTile() }
    }
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(GuardNotifications.intent(this, true))
        else launchBeforeAndroid14()
    }
    // The PendingIntent overload does not exist before API 34. This method is
    // only called below 34; on 34+ the replacement overload above is mandatory.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchBeforeAndroid14() {
        startActivityAndCollapse(android.content.Intent(this, local.cooldown.app.MainActivity::class.java)
            .setAction(local.cooldown.app.MainActivity.QUICK_LOCK).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
