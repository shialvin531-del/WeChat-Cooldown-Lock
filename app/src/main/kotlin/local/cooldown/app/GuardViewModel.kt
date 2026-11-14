package local.cooldown.app

import android.app.Application
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import local.cooldown.app.monitor.*
import local.cooldown.core.*

enum class Page { HOME, LOCK, SETTINGS, JOURNAL }

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GuardViewModel(application: Application) : AndroidViewModel(application) {
    val repository = (application as CooldownApplication).repository
    val state = repository.state
    val journal = repository.journal.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val locks = repository.locks.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessions = repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val statistics = flow {
        while (currentCoroutineContext().isActive) { emit(repository.clock.wall() - 7 * 24 * 60 * 60_000L); delay(60_000) }
    }.flatMapLatest(repository::statistics).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), local.cooldown.data.HistoryStats())
    val monitor = MonitorRuntime.status
    val permissions = MutableStateFlow(PermissionGate.read(application))
    val page = MutableStateFlow(Page.HOME)
    val message = MutableStateFlow<String?>(null)
    val remaining = MutableStateFlow(0L)
    val elapsedNow = MutableStateFlow(android.os.SystemClock.elapsedRealtime())
    val draft = MutableStateFlow("")
    val draftMood = MutableStateFlow(Mood.IRRITATED)
    private var draftLoaded = false

    init {
        viewModelScope.launch {
            state.filterNotNull().collect {
                if (!draftLoaded) { draft.value = it.draft; draftMood.value = it.draftMood; draftLoaded = true }
                remaining.value = repository.remaining(it)
            }
        }
        viewModelScope.launch {
            combine(draft, draftMood) { text, mood -> text to mood }.debounce(350).collectLatest { (text, mood) ->
                if (draftLoaded) try { repository.draft(text, mood) }
                catch (e: Exception) { if (e is CancellationException) throw e; message.value = "草稿未能保存，请检查本机存储。" }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                if (state.value != null) {
                    remaining.value = repository.remaining()
                    try { repository.expireIfDue() }
                    catch (e: Exception) { if (e is CancellationException) throw e; message.value = "计时状态保存失败，请检查存储。" }
                    remaining.value = repository.remaining()
                }
                elapsedNow.value = android.os.SystemClock.elapsedRealtime()
                delay(if (remaining.value > 0) 1000 else 2000)
            }
        }
    }

    fun refreshPermissions() { permissions.value = PermissionGate.read(getApplication()) }
    fun settings(change: (GuardSettings) -> GuardSettings) = action {
        repository.updateSettings {
            val next = change(it)
            require(next.monitoringWanted || repository.remaining() == 0L) { "冷静期内不能在应用内暂停保护，倒计时结束后可修改。" }
            next
        }
    }
    fun manualLock() = action {
        val s = state.filterNotNull().first()
        repository.updateSettings { it.copy(monitoringWanted = true) }
        repository.startLock(s.settings.manualMinutes, "主动点击一键冷静", "manual")
        if (permissions.value.complete) MonitorService.start(getApplication())
        remaining.value = repository.remaining()
        page.value = Page.LOCK
    }
    fun saveNote() = action {
        repository.saveNote(draft.value, draftMood.value)
        draft.value = ""; message.value = "已保存到本机，不会发送。"
    }
    fun deleteEntry(id: Long) = action { repository.deleteEntry(id) }
    fun eraseHistory() = action { repository.eraseHistory(); draft.value = ""; message.value = "记录、统计和草稿已删除，当前冷静计时保留。" }
    fun flushDraft() {
        if (!draftLoaded) return
        val text = draft.value; val mood = draftMood.value
        (getApplication<Application>() as CooldownApplication).appScope.launch {
            try { repository.draft(text, mood) } catch (_: Exception) { }
        }
    }
    fun pinShortcut() {
        val context = getApplication<Application>()
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (!manager.isRequestPinShortcutSupported) { message.value = "此桌面不支持固定快捷方式，可长按应用图标使用一键冷静。"; return }
        val shortcut = ShortcutInfo.Builder(context, "pinned_cooldown").setShortLabel("一键冷静")
            .setIcon(Icon.createWithResource(context, R.drawable.ic_lock))
            .setIntent(Intent(context, MainActivity::class.java).setAction(MainActivity.QUICK_LOCK)).build()
        manager.requestPinShortcut(shortcut, null)
    }
    fun restartMonitoring() {
        refreshPermissions()
        if (!permissions.value.complete) { message.value = "请先开启使用情况访问和悬浮窗权限。"; return }
        action {
            repository.updateSettings { it.copy(monitoringWanted = true) }
            MonitorService.start(getApplication())
        }
    }
    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                message.value = if (e is IllegalArgumentException) e.message else "操作未完成，请检查权限和本机存储空间。"
            }
        }
    }
}
