package local.cooldown.app.monitor

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import local.cooldown.app.MainActivity
import local.cooldown.app.R

object GuardNotifications {
    const val CHANNEL = "local_guard"
    const val FOREGROUND_ID = 7
    fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "本地保护监测", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "后台监测状态与一键冷静入口" }
        )
    }
    fun intent(context: Context, quick: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context, if (quick) 2 else 1,
        Intent(context, MainActivity::class.java).apply {
            action = if (quick) MainActivity.QUICK_LOCK else Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    fun notification(context: Context, message: String): Notification {
        channel(context)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_lock).setContentTitle("微信冷静锁")
            .setContentText(message).setContentIntent(intent(context)).setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "一键冷静", intent(context, true)).build()).build()
    }
    fun recovery(context: Context) {
        channel(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).notify(8,
            Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_lock)
                .setContentTitle("点击恢复微信冷静锁监测").setContentText("系统没有允许自动恢复，打开应用可检查保护状态。")
                .setContentIntent(intent(context)).setAutoCancel(true).build())
    }
}
