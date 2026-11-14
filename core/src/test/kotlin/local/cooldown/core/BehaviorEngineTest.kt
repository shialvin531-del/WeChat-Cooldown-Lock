package local.cooldown.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class BehaviorEngineTest {
    private val base = GuardSettings()
    private val noon = LocalTime.NOON
    private fun visit(e: BehaviorEngine, at: Long, settings: GuardSettings = base, auto: Boolean = true) =
        e.foreground(WECHAT_PACKAGE, AppKind.WECHAT, at, settings, noon, auto)
    private fun home(e: BehaviorEngine, at: Long) = e.foreground("launcher", AppKind.HOME, at, base, noon)

    @Test fun `same package activities do not repeat greeting or count as reopens`() {
        val engine = BehaviorEngine()
        assertEquals(1, visit(engine, 0).filterIsInstance<BehaviorAction.Greeting>().size)
        for (t in 1L..100L) assertTrue(visit(engine, t).isEmpty())
    }
    @Test fun `every real external return gets one greeting`() {
        val engine = BehaviorEngine(); visit(engine, 0)
        engine.foreground("other", AppKind.OTHER, 2000, base, noon)
        assertTrue(visit(engine, 3000).single() is BehaviorAction.Greeting)
        assertTrue(visit(engine, 4000).isEmpty())
    }
    @Test fun `three qualified reopens with short sessions trigger one five minute lock`() {
        val engine = BehaviorEngine(); visit(engine, 0)
        for (i in 1..3) {
            val exit = i * 30_000L - 15_000
            val end = home(engine, exit).filterIsInstance<BehaviorAction.Session>().single()
            assertEquals(15_000, end.duration)
            val actions = visit(engine, i * 30_000L)
            if (i < 3) assertTrue(actions.single() is BehaviorAction.Greeting)
            else {
                val lock = actions.single() as BehaviorAction.AutoLock
                assertTrue(lock.reason.contains("3 次"))
                assertEquals(5, base.autoMinutes)
            }
        }
    }
    @Test fun `brief app switches never accumulate risk but still greet`() {
        val engine = BehaviorEngine(); visit(engine, 0)
        for (i in 1..20) {
            home(engine, i * 5000L)
            val actions = visit(engine, i * 5000L + 1000)
            assertFalse(actions.any { it is BehaviorAction.AutoLock })
            assertTrue(actions.any { it is BehaviorAction.Greeting })
        }
    }
    @Test fun `checking the guard app between short chats counts toward the same burst`() {
        val e = BehaviorEngine(); visit(e, 0)
        for (i in 1..3) {
            e.foreground("guard", AppKind.OWN, i * 30_000L - 15_000, base, noon)
            // A launcher event in the gesture route must not overwrite the preceding short session.
            home(e, i * 30_000L - 14_000)
            val actions = visit(e, i * 30_000L)
            assertEquals(i == 3, actions.any { it is BehaviorAction.AutoLock })
        }
    }
    @Test fun `skipped return keeps previous progress visible and next qualified return adds to it`() {
        val e = BehaviorEngine(); visit(e, 0)
        home(e, 15_000); visit(e, 30_000)
        assertTrue(e.behaviorHint().contains("1/3"))
        home(e, 40_000); visit(e, 41_000)
        assertTrue(e.behaviorHint().contains("1/3"))
        assertTrue(e.behaviorHint().contains("离开不足"))
        e.foreground("browser", AppKind.OTHER, 50_000, base, noon)
        visit(e, 65_000)
        assertTrue(e.behaviorHint().contains("1/3"))
        assertTrue(e.behaviorHint().contains("先进入了其他应用"))
        home(e, 80_000); visit(e, 95_000)
        assertTrue(e.behaviorHint().contains("2/3"))
        home(e, 110_000)
        assertTrue(visit(e, 125_000).single() is BehaviorAction.AutoLock)
    }
    @Test fun `displayed progress expires without needing another WeChat entry`() {
        val e = BehaviorEngine(); visit(e, 0)
        home(e, 15_000); visit(e, 30_000)
        assertTrue(e.behaviorHint(329_999).contains("1/3"))
        assertTrue(e.behaviorHint(330_000).contains("0/3"))
    }
    @Test fun `long normal chat clears the previous burst`() {
        val e = BehaviorEngine(); visit(e, 0)
        home(e, 10_000); visit(e, 25_000)
        home(e, 35_000); visit(e, 50_000)
        home(e, 120_000); assertFalse(visit(e, 135_000).any { it is BehaviorAction.AutoLock })
        home(e, 145_000); assertFalse(visit(e, 160_000).any { it is BehaviorAction.AutoLock })
    }
    @Test fun `external link flows are conservatively excluded even if returning through launcher`() {
        val e = BehaviorEngine(); visit(e, 0)
        for (i in 1..5) {
            e.foreground("browser", AppKind.OTHER, i * 40_000L - 25_000, base, noon)
            home(e, i * 40_000L - 10_000)
            assertFalse(visit(e, i * 40_000L).any { it is BehaviorAction.AutoLock })
        }
    }
    @Test fun `expired rolling window entries do not count`() {
        val e = BehaviorEngine(); visit(e, 0)
        home(e, 1000); visit(e, 12_000)
        home(e, 13_000); visit(e, 24_000)
        home(e, 25_000)
        assertFalse(visit(e, 324_000).any { it is BehaviorAction.AutoLock })
    }
    @Test fun `separate short session minimum is always required`() {
        val e = BehaviorEngine(); val settings = base.copy(reopenThreshold = 2, shortSessionThreshold = 4)
        visit(e, 0, settings)
        for (i in 1..4) {
            home(e, i * 30_000L - 15_000)
            val actions = visit(e, i * 30_000L, settings)
            assertEquals(i == 4, actions.any { it is BehaviorAction.AutoLock })
        }
    }
    @Test fun `disabled rules and an existing lock do not suppress greetings`() {
        val e = BehaviorEngine(); val settings = base.copy(autoEnabled = false)
        visit(e, 0, settings)
        for (i in 1..5) { home(e, i * 30_000L - 15_000); assertTrue(visit(e, i * 30_000L, settings).single() is BehaviorAction.Greeting) }
        e.clearRisk()
        home(e, 170_000)
        assertTrue(visit(e, 190_000, base, auto = false).single() is BehaviorAction.Greeting)
    }
    @Test fun `sensitive period spans midnight without altering short session gate`() {
        val settings = base.copy(sensitiveEnabled = true)
        assertTrue(settings.isSensitive(LocalTime.of(22, 0)))
        assertTrue(settings.isSensitive(LocalTime.of(0, 59)))
        assertFalse(settings.isSensitive(LocalTime.of(1, 0)))
        assertFalse(settings.isSensitive(LocalTime.of(21, 59)))
        assertFalse(settings.copy(sensitiveEndMinute = settings.sensitiveStartMinute).isSensitive(LocalTime.MIDNIGHT))
        val e = BehaviorEngine()
        e.foreground(WECHAT_PACKAGE, AppKind.WECHAT, 0, settings, LocalTime.of(23, 0))
        for (i in 1..2) {
            home(e, i * 30_000L - 15_000)
            val actions = e.foreground(WECHAT_PACKAGE, AppKind.WECHAT, i * 30_000L, settings, LocalTime.of(23, 0))
            assertEquals(i == 2, actions.any { it is BehaviorAction.AutoLock })
        }
    }
    @Test fun `screen off ends a session but cannot build a risk burst`() {
        val e = BehaviorEngine(); visit(e, 1000)
        assertEquals(9000, (e.screenOff(10_000).single() as BehaviorAction.Session).duration)
        assertFalse(e.isInWechat())
        assertTrue(visit(e, 40_000).single() is BehaviorAction.Greeting)
    }
    @Test fun `transient system windows do not cause reentry and stale events are ignored`() {
        val e = BehaviorEngine(); visit(e, 1000)
        assertTrue(e.foreground("systemui", AppKind.TRANSIENT, 2000, base, noon).isEmpty())
        assertTrue(visit(e, 3000).isEmpty())
        assertTrue(home(e, 500).isEmpty())
        assertTrue(e.isInWechat())
    }
    @Test fun `same boot deadline ignores date changes and reboot fallback is bounded`() {
        val d = Deadline("a", 1000, 301_000, 310_000, 2, 5, "reason", "manual")
        assertEquals(300_000, d.remaining(9_999_999, 10_000, 2))
        assertEquals(0, d.remaining(0, 310_000, 2))
        assertEquals(1000, d.remaining(300_000, 1000, 3))
        assertEquals(0, d.remaining(400_000, 1000, 3))
        assertEquals(300_000, d.remaining(0, 0, 3))
        assertEquals("60:00", countdown(60 * 60_000))
    }
    @Test fun `settings validation preserves supported durations and nonzero compound thresholds`() {
        val safe = base.copy(shortSessionThreshold = 0, reopenThreshold = -1, minAwaySeconds = 0, autoMinutes = 999).validated()
        assertEquals(2, safe.shortSessionThreshold); assertEquals(2, safe.reopenThreshold)
        assertEquals(5, safe.minAwaySeconds); assertEquals(5, safe.autoMinutes)
        assertEquals(listOf(5, 10, 20, 30, 60), LOCK_DURATIONS)
    }
}
