package local.cooldown.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import local.cooldown.app.monitor.MonitorService
import local.cooldown.app.monitor.PermissionGate
import local.cooldown.app.ui.AppRoot
import local.cooldown.app.ui.CooldownTheme

class MainActivity : ComponentActivity() {
    private val viewModel: GuardViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermissions() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { CooldownTheme { AppRoot(viewModel) } }
        if (savedInstanceState == null) handleIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.refreshPermissions()
                viewModel.state.filterNotNull().map { it.settings.monitoringWanted || viewModel.repository.remaining(it) > 0 }.distinctUntilChanged().collect { wanted ->
                    if (wanted && PermissionGate.read(this@MainActivity).complete) MonitorService.start(this@MainActivity)
                    else if (!wanted) MonitorService.stop(this@MainActivity)
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onStop() { viewModel.flushDraft(); super.onStop() }
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == QUICK_LOCK) viewModel.manualLock()
        else if (intent?.getStringExtra("page") == "journal") viewModel.page.value = Page.JOURNAL
    }
    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else viewModel.message.value = "此版本无需单独申请通知权限。请在系统通知设置中检查是否允许。"
    }
    companion object { const val QUICK_LOCK = "local.cooldown.QUICK_LOCK" }
}
