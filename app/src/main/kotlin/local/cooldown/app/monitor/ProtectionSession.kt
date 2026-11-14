package local.cooldown.app.monitor

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.cooldown.app.CooldownApplication
import local.cooldown.app.MainActivity
import local.cooldown.core.*
import java.time.LocalTime

/** Shared policy/UI for UsageStats and the optional accessibility fast path. */
class ProtectionSession(private val context: Context, trustedOverlay: Boolean,
    private val preferred: () -> Boolean = { true }, private val onHealth: (SessionHealth) -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = (context.applicationContext as CooldownApplication).repository
    private val engine = BehaviorEngine()
    private val events = Mutex()
    private var mode = OverlayMode.NONE
    private var mood = Mood.GOOD
    private var suspendedUntil = 0L
    private var screenWasAvailable = true
    private var lastEntryWall = 0L
    private var lastFailure: String? = null
    private var observedLockId: String? = null
    private var deferredForeground: ForegroundChange? = null
    private var publishedHealth = SessionHealth()
    private val overlays: OverlayController = OverlayController(context, ::chooseMood, ::manualLock, ::continueChat,
        { openApp("journal") }, ::goHome, ::dismissNudge, trustedOverlay)

    init { scope.launch {
        repository.state.filterNotNull().first()
        while (isActive) {
            try {
                events.withLock {
                    val state = repository.state.value
                    if (!preferred() || state == null) overlays.hide()
                    else {
                        reconcileDeadline(state)
                        if (!state.settings.monitoringWanted && repository.remaining() == 0L) overlays.hide()
                        else if (!screenAvailable()) pauseForScreen()
                        else {
                            screenWasAvailable = true
                            deferredForeground?.let { pending ->
                                deferredForeground = null
                                applyChanges(listOf(pending.copy(elapsed = SystemClock.elapsedRealtime())))
                            }
                            render()
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                overlays.hide()
                lastFailure = "保护页暂未显示，正在重试：${e.javaClass.simpleName}"
                MonitorRuntime.status.value = MonitorRuntime.status.value.copy(error = lastFailure)
            }
            publishHealth()
            delay(if (engine.isInWechat() || repository.remaining() > 0) 250 else 1000)
        }
    } }
    suspend fun accept(changes: List<ForegroundChange>) = events.withLock {
        if (!preferred()) return@withLock
        val state = repository.state.filterNotNull().first()
        val meaningful = changes.filter { it.kind != AppKind.TRANSIENT }
        reconcileDeadline(state)
        if (!screenAvailable()) {
            meaningful.lastOrNull()?.let { deferredForeground = it }
            pauseForScreen()
            publishHealth()
            return@withLock
        }
        if (meaningful.isNotEmpty()) deferredForeground = null
        screenWasAvailable = true
        applyChanges(meaningful)
        try { render() } catch (e: RuntimeException) {
            lastFailure = "保护页暂未显示，正在重试：${e.javaClass.simpleName}"
            MonitorRuntime.status.value = MonitorRuntime.status.value.copy(error = lastFailure)
        }
        publishHealth()
    }
    private suspend fun applyChanges(changes: List<ForegroundChange>) {
        for (event in changes) {
            val state = repository.state.value ?: continue
            if (!state.settings.monitoringWanted && repository.remaining(state) == 0L) continue
            val actions = engine.foreground(event.pkg, event.kind, event.elapsed, state.settings, LocalTime.now(),
                allowAuto = repository.remaining(state) == 0L)
            if (actions.any { it is BehaviorAction.Greeting || it is BehaviorAction.AutoLock })
                lastEntryWall = repository.clock.wall() - (repository.clock.elapsed() - event.elapsed)
            process(actions)
            if (!engine.isInWechat()) { mode = OverlayMode.NONE; overlays.hide() }
        }
    }
    private suspend fun reconcileDeadline(state: StoredState) {
        if (repository.remaining(state) > 0) observedLockId = state.deadline?.id
        else {
            // Another observer (e.g. the visible ViewModel) may already have cleared DataStore.
            if (observedLockId != null) {
                engine.clearRisk(); observedLockId = null; mode = OverlayMode.NONE; overlays.hide()
            }
            if (state.deadline != null) repository.expireIfDue()
        }
    }
    private suspend fun pauseForScreen() {
        overlays.hide()
        if (screenWasAvailable) { process(engine.screenOff(SystemClock.elapsedRealtime())); mode = OverlayMode.NONE }
        screenWasAvailable = false
    }
    private fun publishHealth() {
        if (!preferred()) return
        val now = SystemClock.elapsedRealtime()
        val hint = behaviorHint()
        if (publishedHealth.heartbeat == null || now - (publishedHealth.heartbeat ?: 0) >= 1000 ||
            publishedHealth.lastEntryWall != lastEntryWall || publishedHealth.behaviorHint != hint || publishedHealth.error != lastFailure) {
            publishedHealth = SessionHealth(now, lastEntryWall, hint, lastFailure)
            onHealth(publishedHealth)
        }
    }
    private suspend fun process(actions: List<BehaviorAction>) {
        for (action in actions) when (action) {
            is BehaviorAction.Greeting -> mode = OverlayMode.GREETING
            is BehaviorAction.AutoLock -> {
                val settings = repository.state.value?.settings ?: continue
                repository.startLock(settings.autoMinutes, action.reason, "automatic"); mode = OverlayMode.LOCK
            }
            is BehaviorAction.Session -> {
                val wall = repository.clock.wall(); val elapsed = repository.clock.elapsed()
                repository.recordSession(wall - (elapsed - action.started), wall - (elapsed - action.ended), action.duration)
            }
        }
    }
    private fun render() {
        if (!engine.isInWechat() || SystemClock.elapsedRealtime() < suspendedUntil) { overlays.hide(); return }
        val state = repository.state.value ?: return
        val remaining = repository.remaining(state)
        if (remaining > 0) mode = OverlayMode.LOCK
        else if (mode == OverlayMode.LOCK) mode = OverlayMode.NONE
        val displayedRemaining = ((remaining + 999) / 1000) * 1000
        overlays.show(OverlayModel(mode, displayedRemaining, state.deadline?.reason ?: state.lastReason, mood, state.settings.manualMinutes))
        lastFailure = null
    }
    private fun chooseMood(value: Mood) = action {
        mood = value
        val settings = repository.state.value?.settings ?: return@action
        repository.checkIn(value)
        when {
            value == Mood.GOOD -> mode = OverlayMode.NONE
            value == Mood.IRRITATED -> mode = OverlayMode.NUDGE
            (value == Mood.ANGRY && settings.angryStartsLock) || (value == Mood.FEAR && settings.fearStartsLock) -> {
                repository.startLock(settings.manualMinutes, "情绪问候：${value.label}", "emotion"); mode = OverlayMode.LOCK
            }
            else -> mode = OverlayMode.OFFER
        }
        render()
    }
    private fun manualLock() = action {
        val settings = repository.state.value?.settings ?: return@action
        repository.startLock(settings.manualMinutes, "主动冷静：${mood.label}", "manual"); mode = OverlayMode.LOCK; render()
    }
    private fun continueChat() { mode = OverlayMode.NUDGE; render() }
    private fun dismissNudge() { mode = OverlayMode.NONE; overlays.hide() }
    private fun goHome() = leaveFor(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun openApp(page: String) = leaveFor(Intent(context, MainActivity::class.java).putExtra("page", page)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    private fun leaveFor(intent: Intent) {
        try {
            context.startActivity(intent); suspendedUntil = SystemClock.elapsedRealtime() + 1500
            mode = OverlayMode.NONE; overlays.hide()
        } catch (_: RuntimeException) { Toast.makeText(context, "系统暂未允许打开页面，请通过桌面打开微信冷静锁。", Toast.LENGTH_LONG).show() }
    }
    private fun action(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(context, "操作未完成，请检查权限和本机存储。", Toast.LENGTH_LONG).show() }
        }
    }
    private fun screenAvailable() = context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun lastEntryWall() = lastEntryWall
    fun error() = lastFailure
    fun behaviorHint() = engine.behaviorHint(SystemClock.elapsedRealtime(), repository.state.value?.settings ?: GuardSettings(), LocalTime.now())
    fun reset() { engine.reset(); observedLockId = null; deferredForeground = null; mode = OverlayMode.NONE; overlays.hide() }
    fun close() { scope.cancel(); overlays.hide(); onHealth(SessionHealth()) }
}
