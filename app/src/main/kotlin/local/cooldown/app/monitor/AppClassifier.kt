package local.cooldown.app.monitor

import android.content.Context
import android.content.Intent
import local.cooldown.core.AppKind
import local.cooldown.core.WECHAT_PACKAGE

class AppClassifier(private val context: Context) {
    private val homes = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        .map { it.activityInfo.packageName }.toSet() + "com.miui.home"
    fun kind(pkg: String) = when {
        pkg == WECHAT_PACKAGE -> AppKind.WECHAT
        pkg == context.packageName -> AppKind.OWN
        pkg in homes -> AppKind.HOME
        pkg == "android" || pkg == "com.android.systemui" || pkg.contains("permissioncontroller") -> AppKind.TRANSIENT
        else -> AppKind.OTHER
    }
}
