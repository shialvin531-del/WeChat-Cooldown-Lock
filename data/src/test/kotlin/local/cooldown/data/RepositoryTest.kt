package local.cooldown.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import local.cooldown.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33, 35])
class RepositoryTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var db: CooldownDatabase
    private lateinit var store: SettingsStore
    private lateinit var repo: CooldownRepository
    private lateinit var prefsFile: File
    private lateinit var databaseName: String
    private val clock = FakeClock()

    private class FakeClock : DeviceClock {
        var wall = 1_700_000_000_000L
        var elapsed = 10_000L
        var boot = 3
        override fun wall() = wall
        override fun elapsed() = elapsed
        override fun boot() = boot
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
    @Before fun setup() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        val id = UUID.randomUUID().toString()
        prefsFile = File(context.cacheDir, "$id.preferences_pb")
        databaseName = "$id.db"
        open()
    }
    private suspend fun open() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        store = SettingsStore(context, scope, prefsFile)
        db = Room.databaseBuilder(context, CooldownDatabase::class.java, databaseName).build()
        repo = CooldownRepository(store, db, clock, scope)
        withTimeout(10_000) { repo.state.first { it != null } }
    }
    private suspend fun close() { scope.coroutineContext.job.cancelAndJoin(); db.close() }
    @After fun cleanup() = runBlocking { close() }

    @Test fun `settings and room notes persist across repository and database recreation`() = runBlocking {
        assertEquals(GuardSettings(), store.read().settings)
        repo.updateSettings { it.copy(windowMinutes = 8, manualMinutes = 30, angryStartsLock = true, sensitiveEnabled = true) }
        repo.draft("还没准备好发送", Mood.FEAR)
        repo.saveNote("先保存在这里", Mood.ANGRY)
        repo.checkIn(Mood.IRRITATED)
        close(); open()
        assertEquals(8, store.read().settings.windowMinutes)
        assertEquals(30, store.read().settings.manualMinutes)
        assertTrue(store.read().settings.angryStartsLock)
        val notes = withTimeout(5000) { repo.journal.first() }
        assertEquals(2, notes.size)
        val note = notes.single { it.kind == "note" }
        assertEquals("先保存在这里", note.text); assertEquals("ANGRY", note.mood)
        assertEquals(clock.wall(), note.at); assertEquals("", store.read().draft)
    }

    @Test fun `cooldown persists repeated starts never extend and expiry needs no manual action`() = runBlocking {
        assertTrue(repo.startLock(5, "组合条件", "automatic"))
        val first = store.read().deadline!!
        clock.advance(60_000)
        assertFalse(repo.startLock(60, "重复点击", "manual"))
        assertEquals(first, store.read().deadline)
        close(); open()
        assertEquals(240_000, repo.remaining())
        clock.advance(240_000)
        assertEquals(0, repo.remaining()); assertTrue(repo.expireIfDue()); assertFalse(repo.expireIfDue())
        assertNull(store.read().deadline); assertTrue(store.read().recovered)
        assertEquals("组合条件", store.read().lastReason)
        assertEquals("expired", repo.locks.first().single().outcome)
    }

    @Test fun `changing monitor settings cannot end active cooldown before its deadline`() = runBlocking {
        repo.startLock(20, "手动", "manual")
        val first = store.read().deadline!!
        clock.advance(1000)
        repo.updateSettings { it.copy(monitoringWanted = false, autoEnabled = false, manualMinutes = 5) }
        assertFalse(repo.expireIfDue())
        close(); open()
        assertEquals(first, store.read().deadline)
        assertEquals(1_199_000, repo.remaining())
        repo.updateSettings { it.copy(monitoringWanted = true) }
        assertEquals(first, store.read().deadline)
        assertEquals("active", repo.locks.first().single().outcome)
    }

    @Test fun `clock changes and reboot never cause permanent lock`() = runBlocking {
        repo.startLock(10, "测试", "manual")
        clock.wall += 10 * 24 * 60 * 60_000L
        assertEquals(600_000, repo.remaining())
        clock.boot++
        assertEquals(0, repo.remaining()); assertTrue(repo.expireIfDue())
        repo.startLock(5, "重启后", "manual")
        clock.boot++; clock.wall -= 10 * 24 * 60 * 60_000L
        assertEquals(300_000, repo.remaining())
    }

    @Test fun `individual delete and all history delete retain active deadline and settings`() = runBlocking {
        repo.updateSettings { it.copy(manualMinutes = 60) }
        repo.startLock(60, "害怕失去", "emotion")
        repo.saveNote("暂时不发送的话", Mood.FEAR)
        assertEquals("害怕失去", repo.journal.first().single().reason)
        repo.deleteEntry(repo.journal.first().single().id)
        assertTrue(repo.journal.first().isEmpty())
        repo.saveNote("另一条", Mood.IRRITATED)
        repo.recordSession(clock.wall() - 30_000, clock.wall(), 30_000)
        val stats = repo.statistics(clock.wall() - 60_000).first()
        assertEquals(1, stats.noteCount); assertEquals(1, stats.lockCount); assertEquals(30_000, stats.usageMillis)
        repo.draft("草稿", Mood.ANGRY)
        repo.eraseHistory()
        assertTrue(repo.journal.first().isEmpty()); assertTrue(repo.locks.first().isEmpty()); assertTrue(repo.sessions.first().isEmpty())
        assertEquals("", store.read().draft)
        assertTrue(repo.remaining() > 0); assertEquals(60, store.read().settings.manualMinutes)
    }
}
