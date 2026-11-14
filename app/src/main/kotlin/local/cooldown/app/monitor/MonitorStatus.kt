package local.cooldown.app.monitor

import kotlinx.coroutines.flow.MutableStateFlow

data class MonitorStatus(val running: Boolean = false, val heartbeat: Long = 0, val error: String? = null,
    val lastEntryWall: Long = 0, val behaviorHint: String = "")
object MonitorRuntime {
    val status = MutableStateFlow(MonitorStatus())
}
