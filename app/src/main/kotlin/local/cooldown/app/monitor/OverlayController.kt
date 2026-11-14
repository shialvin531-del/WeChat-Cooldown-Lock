package local.cooldown.app.monitor

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import local.cooldown.app.ui.*
import local.cooldown.core.Mood

enum class OverlayMode { NONE, GREETING, OFFER, LOCK, NUDGE }
data class OverlayModel(
    val mode: OverlayMode = OverlayMode.NONE,
    val remaining: Long = 0,
    val reason: String = "",
    val mood: Mood = Mood.GOOD,
    val manualMinutes: Int = 20,
)

class OverlayController(
    private val context: Context,
    private val onMood: (Mood) -> Unit,
    private val onLock: () -> Unit,
    private val onContinue: () -> Unit,
    private val onRecord: () -> Unit,
    private val onHome: () -> Unit,
    private val onDismissNudge: () -> Unit,
    private val trusted: Boolean = false,
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val model = MutableStateFlow(OverlayModel())
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var small = false

    fun show(value: OverlayModel) {
        if (value.mode == OverlayMode.NONE) { hide(); return }
        val nextSmall = value.mode == OverlayMode.NUDGE
        if (view != null && small != nextSmall) hide()
        model.value = value
        if (view?.isAttachedToWindow == false) hide()
        if (view != null) return
        small = nextSmall
        val lifecycle = OverlayOwner()
        val compose = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val current by model.collectAsState()
                CooldownTheme {
                    when (current.mode) {
                        OverlayMode.GREETING -> GreetingScreen(onMood = onMood, onHome = onHome)
                        OverlayMode.OFFER -> OfferScreen(current.mood, current.manualMinutes, onLock, onContinue, onHome)
                        OverlayMode.LOCK -> LockScreen(current.remaining, current.reason, onRecord, onHome)
                        OverlayMode.NUDGE -> Nudge(onLock, onDismissNudge)
                        OverlayMode.NONE -> Unit
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            if (small) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            if (small) WindowManager.LayoutParams.WRAP_CONTENT else WindowManager.LayoutParams.MATCH_PARENT,
            if (trusted) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                if (small) WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE else 0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = if (small) Gravity.END or Gravity.BOTTOM else Gravity.TOP or Gravity.START
            if (small) { x = 12; y = (96 * context.resources.displayMetrics.density).toInt() }
            title = "微信冷静锁保护页"
        }
        try { manager.addView(compose, params); view = compose; owner = lifecycle; lifecycle.resume() }
        catch (e: RuntimeException) { compose.disposeComposition(); lifecycle.close(); throw e }
    }

    fun hide() {
        view?.let { compose ->
            // Detach synchronously while the owner is still alive. Disposing first and
            // queuing removeView can make a rapidly reused Compose window detach without owners.
            try { manager.removeViewImmediate(compose) } catch (_: IllegalArgumentException) { }
            compose.disposeComposition()
        }
        owner?.close(); owner = null; view = null
    }

    private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        init { saved.performAttach(); saved.performRestore(null); registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
        fun resume() { registry.handleLifecycleEvent(Lifecycle.Event.ON_START); registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        fun close() { registry.currentState = Lifecycle.State.DESTROYED; viewModelStore.clear() }
    }
}
