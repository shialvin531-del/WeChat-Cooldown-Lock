package local.cooldown.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.AppOpsManager
import android.os.Process
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowSettings
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun `actual application launches navigates and saves a room note`() {
        compose.runOnIdle {
            val activity = compose.activity
            Shadows.shadowOf(activity.getSystemService(AppOpsManager::class.java)).setMode(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), activity.packageName, AppOpsManager.MODE_IGNORED)
            ShadowSettings.setCanDrawOverlays(false)
            ViewModelProvider(activity)[GuardViewModel::class.java].refreshPermissions()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("monitor_status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("monitor_status").assertTextEquals("保护未生效 · 需要授权")
        screenshot("home.png")
        compose.onNodeWithText("保护设置").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("自动保护设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("使用情况访问：未开启").assertExists()
        screenshot("settings.png")
        compose.onNodeWithText("情绪记录").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("journal_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("journal_input").performTextInput("我很生气，先写在本机。")
        screenshot("journal.png")
        compose.onNodeWithTag("save_note").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已保存到本机，不会发送。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithText("记录与统计").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("我很生气，先写在本机。").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("我很生气，先写在本机。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("首页").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("start_manual").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("start_manual").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("countdown").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("紧急退出本次冷静").assertDoesNotExist()
        val model = ViewModelProvider(compose.activity)[GuardViewModel::class.java]
        compose.runOnIdle { model.page.value = Page.HOME }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("monitor_status").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("暂停全部后台监测").assertDoesNotExist()
        compose.runOnIdle { model.settings { it.copy(monitoringWanted = false) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("冷静期内不能在应用内暂停保护，倒计时结束后可修改。").fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertTrue(model.repository.remaining() > 0)
        org.junit.Assert.assertTrue(model.state.value!!.settings.monitoringWanted)
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
