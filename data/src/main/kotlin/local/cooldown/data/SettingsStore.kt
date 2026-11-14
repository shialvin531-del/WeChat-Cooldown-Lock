package local.cooldown.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import local.cooldown.core.*
import java.io.File

class SettingsStore(context: Context, scope: CoroutineScope, file: File = context.preferencesDataStoreFile("cooldown")) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    val state: Flow<StoredState> = store.data.map(::decode)
    suspend fun read(): StoredState = state.first()

    suspend fun settings(value: GuardSettings) {
        val s = value.validated()
        store.edit { p ->
            p[b("monitor")] = s.monitoringWanted; p[b("auto")] = s.autoEnabled
            p[i("window")] = s.windowMinutes; p[i("reopens")] = s.reopenThreshold
            p[i("short_sec")] = s.shortSessionSeconds; p[i("short_count")] = s.shortSessionThreshold
            p[i("away_sec")] = s.minAwaySeconds; p[i("auto_min")] = s.autoMinutes
            p[i("manual_min")] = s.manualMinutes; p[b("angry")] = s.angryStartsLock
            p[b("fear")] = s.fearStartsLock; p[b("sensitive")] = s.sensitiveEnabled
            p[i("sensitive_start")] = s.sensitiveStartMinute; p[i("sensitive_end")] = s.sensitiveEndMinute
        }
    }

    suspend fun lock(value: Deadline) = store.edit { p ->
        p[t("lock_id")] = value.id; p[l("start_wall")] = value.startedWall
        p[l("end_wall")] = value.endWall; p[l("end_elapsed")] = value.endElapsed
        p[i("lock_boot")] = value.boot; p[i("lock_minutes")] = value.minutes
        p[t("reason")] = value.reason; p[t("source")] = value.source
        p[b("recovered")] = false
    }

    suspend fun finish() = store.edit { p ->
        p.remove(t("lock_id")); p.remove(l("end_wall")); p.remove(l("end_elapsed"))
        p[b("recovered")] = true
    }
    suspend fun mood(value: Mood) = store.edit { it[t("mood")] = value.name }
    suspend fun draft(text: String, mood: Mood) = store.edit { it[t("draft")] = text; it[t("draft_mood")] = mood.name }
    suspend fun clearDraft() = store.edit { it.remove(t("draft")) }

    private fun decode(p: Preferences): StoredState {
        val s = GuardSettings(
            monitoringWanted = p[b("monitor")] ?: true, autoEnabled = p[b("auto")] ?: true,
            windowMinutes = p[i("window")] ?: 5, reopenThreshold = p[i("reopens")] ?: 3,
            shortSessionSeconds = p[i("short_sec")] ?: 60, shortSessionThreshold = p[i("short_count")] ?: 2,
            minAwaySeconds = p[i("away_sec")] ?: 10, autoMinutes = p[i("auto_min")] ?: 5,
            manualMinutes = p[i("manual_min")] ?: 20, angryStartsLock = p[b("angry")] ?: false,
            fearStartsLock = p[b("fear")] ?: false, sensitiveEnabled = p[b("sensitive")] ?: false,
            sensitiveStartMinute = p[i("sensitive_start")] ?: 1320, sensitiveEndMinute = p[i("sensitive_end")] ?: 60,
        ).validated()
        val deadline = p[t("lock_id")]?.let { id ->
            Deadline(id, p[l("start_wall")] ?: 0, p[l("end_wall")] ?: 0,
                p[l("end_elapsed")] ?: 0, p[i("lock_boot")] ?: -1, p[i("lock_minutes")] ?: 5,
                p[t("reason")] ?: "冷静一下", p[t("source")] ?: "manual")
        }
        return StoredState(s, deadline, p[t("reason")] ?: "", mood(p[t("mood")]),
            p[b("recovered")] ?: false,
            p[t("draft")] ?: "", mood(p[t("draft_mood")], Mood.IRRITATED))
    }
    private fun mood(value: String?, fallback: Mood = Mood.GOOD) = Mood.entries.firstOrNull { it.name == value } ?: fallback
    private fun b(key: String) = booleanPreferencesKey(key)
    private fun i(key: String) = intPreferencesKey(key)
    private fun l(key: String) = longPreferencesKey(key)
    private fun t(key: String) = stringPreferencesKey(key)
}
