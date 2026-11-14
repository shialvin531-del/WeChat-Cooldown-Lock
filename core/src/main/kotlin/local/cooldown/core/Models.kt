package local.cooldown.core

import java.time.LocalTime

const val WECHAT_PACKAGE = "com.tencent.mm"
val LOCK_DURATIONS = listOf(5, 10, 20, 30, 60)

enum class Mood(val label: String, val emoji: String) {
    GOOD("心情不错", "😊"), IRRITATED("有点烦躁", "😐"),
    ANGRY("我很生气", "😠"), FEAR("害怕失去这段感情", "💔")
}

data class GuardSettings(
    val monitoringWanted: Boolean = true,
    val autoEnabled: Boolean = true,
    val windowMinutes: Int = 5,
    val reopenThreshold: Int = 3,
    val shortSessionSeconds: Int = 60,
    val shortSessionThreshold: Int = 2,
    val minAwaySeconds: Int = 10,
    val autoMinutes: Int = 5,
    val manualMinutes: Int = 20,
    val angryStartsLock: Boolean = false,
    val fearStartsLock: Boolean = false,
    val sensitiveEnabled: Boolean = false,
    val sensitiveStartMinute: Int = 22 * 60,
    val sensitiveEndMinute: Int = 60,
) {
    fun validated() = copy(
        windowMinutes = windowMinutes.coerceIn(1, 30),
        reopenThreshold = reopenThreshold.coerceIn(2, 10),
        shortSessionSeconds = shortSessionSeconds.coerceIn(15, 180),
        shortSessionThreshold = shortSessionThreshold.coerceIn(2, 10),
        minAwaySeconds = minAwaySeconds.coerceIn(5, 120),
        autoMinutes = autoMinutes.takeIf { it in LOCK_DURATIONS } ?: 5,
        manualMinutes = manualMinutes.takeIf { it in LOCK_DURATIONS } ?: 20,
        sensitiveStartMinute = sensitiveStartMinute.coerceIn(0, 1439),
        sensitiveEndMinute = sensitiveEndMinute.coerceIn(0, 1439),
    )

    fun isSensitive(time: LocalTime): Boolean {
        if (!sensitiveEnabled || sensitiveStartMinute == sensitiveEndMinute) return false
        val minute = time.hour * 60 + time.minute
        return if (sensitiveStartMinute < sensitiveEndMinute)
            minute in sensitiveStartMinute until sensitiveEndMinute
        else minute >= sensitiveStartMinute || minute < sensitiveEndMinute
    }
}

data class Deadline(
    val id: String,
    val startedWall: Long,
    val endWall: Long,
    val endElapsed: Long,
    val boot: Int,
    val minutes: Int,
    val reason: String,
    val source: String,
) {
    fun remaining(wallNow: Long, elapsedNow: Long, bootNow: Int): Long {
        val raw = if (bootNow >= 0 && bootNow == boot) endElapsed - elapsedNow else endWall - wallNow
        return raw.coerceIn(0, minutes * 60_000L)
    }
}

data class StoredState(
    val settings: GuardSettings = GuardSettings(),
    val deadline: Deadline? = null,
    val lastReason: String = "",
    val lastMood: Mood = Mood.GOOD,
    val recovered: Boolean = false,
    val draft: String = "",
    val draftMood: Mood = Mood.IRRITATED,
)

fun countdown(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}
