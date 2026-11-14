package local.cooldown.app.monitor

data class SessionHealth(
    val heartbeat: Long? = null,
    val lastEntryWall: Long = 0,
    val behaviorHint: String = "",
    val error: String? = null,
) {
    fun isRunning(now: Long): Boolean = heartbeat?.let { now >= it && now - it < 7000 && error == null } ?: false
}
