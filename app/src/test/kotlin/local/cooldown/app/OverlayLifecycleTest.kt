package local.cooldown.app

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import local.cooldown.app.monitor.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35], application = Application::class)
class OverlayLifecycleTest {
    @Test fun `enhanced protection uses a trusted accessibility overlay`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val overlay = OverlayController(context, {}, {}, {}, {}, {}, {}, trusted = true)
        try {
            overlay.show(OverlayModel(OverlayMode.LOCK, 300_000, "测试冷静"))
            val view = ReflectionHelpers.getField<ComposeView>(overlay, "view")
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, (view.layoutParams as WindowManager.LayoutParams).type)
        } finally { overlay.hide() }
    }
    @Test fun `real compose overlay attaches with owners changes size and releases lifecycle`() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val overlay = OverlayController(context, {}, {}, {}, {}, {}, {})
        try {
            overlay.show(OverlayModel(OverlayMode.GREETING))
            val full = ReflectionHelpers.getField<ComposeView>(overlay, "view")
            val owner = full.findViewTreeLifecycleOwner()!!
            assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
            val params = full.layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
            assertTrue(params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
            overlay.show(OverlayModel(OverlayMode.NUDGE))
            assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
            val small = ReflectionHelpers.getField<ComposeView>(overlay, "view")
            assertNotSame(full, small)
            assertEquals(WindowManager.LayoutParams.WRAP_CONTENT, small.layoutParams.width)
            overlay.show(OverlayModel())
            assertNull(ReflectionHelpers.getField<ComposeView?>(overlay, "view"))
        } finally { overlay.hide() }
    }
}
