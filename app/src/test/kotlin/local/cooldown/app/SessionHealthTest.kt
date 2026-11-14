package local.cooldown.app

import local.cooldown.app.monitor.SessionHealth
import org.junit.Assert.*
import org.junit.Test

class SessionHealthTest {
    @Test fun `connection without a heartbeat is not reported as running`() {
        assertFalse(SessionHealth().isRunning(10_000))
    }
    @Test fun `stale future or failed heartbeat is not reported as running`() {
        assertTrue(SessionHealth(1000).isRunning(2000))
        assertFalse(SessionHealth(1000).isRunning(8000))
        assertFalse(SessionHealth(3000).isRunning(2000))
        assertFalse(SessionHealth(1000, error = "窗口未显示").isRunning(2000))
    }
}
