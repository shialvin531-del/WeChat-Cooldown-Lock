package local.cooldown.app.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.SystemClock
import local.cooldown.core.AppKind
import local.cooldown.core.WECHAT_PACKAGE

data class ForegroundChange(val pkg: String, val kind: AppKind, val elapsed: Long)

/** Replay overlap catches late events. Only real package boundaries advance the watermark. */
class UsageEventSource(context: Context,
    private val wallTime: () -> Long = { System.currentTimeMillis() },
    private val elapsedTime: () -> Long = { SystemClock.elapsedRealtime() }) {
    private val manager = context.getSystemService(UsageStatsManager::class.java)
    private val classifier = AppClassifier(context)
    private var initialized = false
    private var transitionWall = Long.MIN_VALUE
    private var foreground = ""
    private var previousWall = 0L
    private var previousElapsed = 0L
    private var pendingWechatPause: Long? = null
    private val boundaryKeys = mutableSetOf<Raw>()
    private data class Raw(val pkg: String, val cls: String?, val type: Int, val time: Long)
    fun reset() {
        initialized = false; transitionWall = Long.MIN_VALUE; foreground = ""
        previousWall = 0; previousElapsed = 0; pendingWechatPause = null; boundaryKeys.clear()
    }
    fun poll(): List<ForegroundChange> {
        val wall = wallTime(); val elapsed = elapsedTime()
        val clockChanged = initialized && kotlin.math.abs((wall - previousWall) - (elapsed - previousElapsed)) > 3000
        val bootstrap = !initialized || clockChanged
        val from = if (bootstrap) wall - 30 * 60_000 else maxOf(transitionWall, wall - 30_000)
        val events = manager.queryEvents(from, wall) ?: return emptyList()
        val raw = mutableListOf<Raw>(); val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                (pkg == WECHAT_PACKAGE && event.eventType == UsageEvents.Event.ACTIVITY_PAUSED))
                raw += Raw(pkg, event.className, event.eventType, event.timeStamp)
        }
        previousWall = wall; previousElapsed = elapsed
        if (bootstrap) {
            initialized = true; pendingWechatPause = null; boundaryKeys.clear(); transitionWall = wall
            val latest = raw.lastOrNull { it.type == UsageEvents.Event.ACTIVITY_RESUMED && classifier.kind(it.pkg) != AppKind.TRANSIENT }
            foreground = latest?.pkg ?: ""
            return latest?.let { listOf(ForegroundChange(it.pkg, classifier.kind(it.pkg), elapsed)) } ?: emptyList()
        }
        val result = mutableListOf<ForegroundChange>()
        for (record in raw.sortedBy { it.time }) {
            if (record.time < transitionWall || record in boundaryKeys) continue
            if (record.type == UsageEvents.Event.ACTIVITY_PAUSED) { pendingWechatPause = record.time; continue }
            val kind = classifier.kind(record.pkg)
            if (kind == AppKind.TRANSIENT) continue
            val stamp = if (record.pkg != WECHAT_PACKAGE) pendingWechatPause ?: record.time else record.time
            pendingWechatPause = null
            if (record.pkg == foreground) continue
            foreground = record.pkg
            if (record.time > transitionWall) { transitionWall = record.time; boundaryKeys.clear() }
            boundaryKeys += record
            result += ForegroundChange(record.pkg, kind, (elapsed - (wall - stamp)).coerceAtLeast(0))
        }
        return result
    }
}
