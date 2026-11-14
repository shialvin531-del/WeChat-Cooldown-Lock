package local.cooldown.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import local.cooldown.app.ui.*
import local.cooldown.core.Mood
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `greeting has four quick actions and no text entry`() {
        val selected = mutableListOf<Mood>()
        compose.setContent { CooldownTheme { GreetingScreen(selected::add) {} } }
        compose.onNodeWithText("现在的心情怎么样？").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        Mood.entries.forEach { mood -> compose.onNodeWithTag("mood_${mood.name}").performClick() }
        assertEquals(Mood.entries, selected)
        screenshot("greeting.png")
    }
    @Test fun `lock has no early exit and automatically renders recovered state`() {
        var left by mutableLongStateOf(300_000)
        var record = 0
        compose.setContent { CooldownTheme { LockScreen(left, "短会话与反复进入的组合条件", { record++ }, {}) } }
        compose.onNodeWithTag("countdown").assertTextEquals("05:00")
        compose.onNodeWithTag("record").performScrollTo().performClick(); assertEquals(1, record)
        compose.onNodeWithTag("emergency").assertDoesNotExist()
        compose.onNodeWithText("紧急退出本次冷静").assertDoesNotExist()
        screenshot("lock.png")
        compose.runOnIdle { left = 0 }
        compose.onNodeWithText("冷静结束，已自动解锁").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("countdown").assertDoesNotExist()
        compose.onNodeWithTag("emergency").assertDoesNotExist()
    }
    @Test fun `angry offer allows cooling or continued chat without fields`() {
        var cooled = false; var continued = false
        compose.setContent { CooldownTheme { OfferScreen(Mood.ANGRY, 20, { cooled = true }, { continued = true }, {}) } }
        compose.onNodeWithText("一键冷静 20 分钟").performScrollTo().performClick()
        compose.onNodeWithText("继续聊天，保留冷静入口").performScrollTo().performClick()
        assertTrue(cooled); assertTrue(continued)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        screenshot("offer.png")
    }
    private fun screenshot(name: String) {
        val file = File("build/previews/$name"); file.parentFile!!.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
