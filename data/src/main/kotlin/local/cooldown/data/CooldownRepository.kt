package local.cooldown.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.cooldown.core.*
import java.util.UUID

interface DeviceClock {
    fun wall(): Long
    fun elapsed(): Long
    fun boot(): Int
}

class AndroidDeviceClock(private val context: Context) : DeviceClock {
    override fun wall() = System.currentTimeMillis()
    override fun elapsed() = SystemClock.elapsedRealtime()
    override fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
}

class CooldownRepository(
    private val settings: SettingsStore,
    private val db: CooldownDatabase,
    val clock: DeviceClock,
    scope: CoroutineScope,
) {
    private val mutex = Mutex()
    val state = MutableStateFlow<StoredState?>(null)
    val storageError = MutableStateFlow<String?>(null)
    val journal = db.dao().journal()
    val locks = db.dao().locks()
    val sessions = db.dao().sessions()
    fun statistics(since: Long) = db.dao().statistics(since)

    init { scope.launch {
        try { settings.state.collect { state.value = it } }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; storageError.value = "本地设置读取失败，请检查存储空间后重新打开应用。" }
    } }

    suspend fun updateSettings(transform: (GuardSettings) -> GuardSettings) = mutex.withLock {
        settings.settings(transform(settings.read().settings).validated())
        state.value = settings.read()
    }

    fun remaining(state: StoredState? = this.state.value): Long = state?.deadline?.remaining(clock.wall(), clock.elapsed(), clock.boot()) ?: 0

    suspend fun startLock(minutes: Int, reason: String, source: String): Boolean = mutex.withLock {
        require(minutes in LOCK_DURATIONS)
        val old = settings.read()
        if (remaining(old) > 0) return@withLock false
        // Reconcile an expired lock before creating another.
        old.deadline?.let { deadline -> safeHistory { db.dao().finishLock(deadline.id, deadline.endWall, "expired") } }
        val now = clock.wall()
        val lock = Deadline(UUID.randomUUID().toString(), now, now + minutes * 60_000L,
            clock.elapsed() + minutes * 60_000L, clock.boot(), minutes, reason, source)
        settings.lock(lock)
        state.value = settings.read()
        safeHistory { db.dao().insert(LockRecord(lock.id, now, lock.endWall, reason = reason, source = source)) }
        true
    }

    suspend fun expireIfDue(): Boolean = mutex.withLock {
        val current = settings.read()
        val lock = current.deadline ?: return@withLock false
        if (remaining(current) > 0) return@withLock false
        settings.finish()
        state.value = settings.read()
        safeHistory { db.dao().finishLock(lock.id, clock.wall(), "expired") }
        true
    }

    suspend fun checkIn(mood: Mood) {
        settings.mood(mood)
        safeHistory { db.dao().insert(JournalEntry(at = clock.wall(), text = "", mood = mood.name, reason = "进入微信时的情绪问候", kind = "checkin")) }
    }
    suspend fun draft(text: String, mood: Mood) { settings.draft(text.take(6000), mood) }
    suspend fun saveNote(text: String, mood: Mood) {
        require(text.isNotBlank()) { "先写一点内容再保存。" }
        val current = settings.read()
        db.dao().insert(JournalEntry(at = clock.wall(), text = text.take(6000), mood = mood.name,
            reason = current.deadline?.reason ?: current.lastReason.ifBlank { "主动记录" }))
        settings.clearDraft()
    }
    suspend fun recordSession(startedAt: Long, endedAt: Long, duration: Long) = safeHistory {
        db.dao().insert(UsageSession(startedAt = startedAt, endedAt = endedAt, durationMillis = duration))
        db.dao().trimSessions()
    }
    suspend fun deleteEntry(id: Long) = db.dao().deleteEntry(id)
    suspend fun eraseHistory() { db.dao().eraseHistory(); settings.clearDraft() }
    fun clearError() { storageError.value = null }

    private suspend fun safeHistory(block: suspend () -> Unit) {
        try { block() } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            storageError.value = "保护计时仍有效，但历史记录保存失败，请检查本机存储空间。"
        }
    }
}
