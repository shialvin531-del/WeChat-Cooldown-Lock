package local.cooldown.core

import java.time.LocalTime
import java.util.ArrayDeque

enum class AppKind { WECHAT, HOME, OTHER, OWN, TRANSIENT }

sealed interface BehaviorAction {
    data class Greeting(val entry: Long) : BehaviorAction
    data class Session(val started: Long, val ended: Long, val duration: Long) : BehaviorAction
    data class AutoLock(val reason: String) : BehaviorAction
}

/** No Android or chat APIs. Events use monotonic milliseconds, not the adjustable date. */
class BehaviorEngine {
    private var current = AppKind.OTHER
    private var currentPackage = ""
    private var sessionStarted: Long? = null
    private var lastEventAt = Long.MIN_VALUE
    private var hint = "等待检测到进入微信"
    private var lastSettings = GuardSettings()
    private var lastLocalTime = LocalTime.NOON
    private var triggered = false
    private var lastCompleted: Completed? = null
    private val reopens = ArrayDeque<Long>()
    private data class Completed(val duration: Long, val leftAt: Long, val eligibleExit: Boolean)

    fun foreground(
        pkg: String,
        kind: AppKind,
        at: Long,
        settings: GuardSettings,
        localTime: LocalTime,
        allowAuto: Boolean = true,
    ): List<BehaviorAction> {
        if (kind == AppKind.TRANSIENT || at < lastEventAt) return emptyList()
        if (pkg == currentPackage) return emptyList()
        lastEventAt = at
        lastSettings = settings
        lastLocalTime = localTime
        val actions = mutableListOf<BehaviorAction>()
        if (current == AppKind.WECHAT && kind != AppKind.WECHAT) {
            val start = sessionStarted
            if (start != null) {
                val duration = (at - start).coerceAtLeast(0)
                actions += BehaviorAction.Session(start, at, duration)
                // Checking protection progress in our own app is also an intentional exit.
                // Other apps remain excluded: package metadata cannot distinguish an external
                // link opened by WeChat from an intentional switch to another app.
                lastCompleted = Completed(duration, at, kind == AppKind.HOME || kind == AppKind.OWN)
                if (duration >= settings.shortSessionSeconds * 1000L) reopens.clear()
            }
            sessionStarted = null
        }
        val entering = kind == AppKind.WECHAT && current != AppKind.WECHAT
        currentPackage = pkg
        current = kind
        if (!entering) return actions
        sessionStarted = at
        val window = settings.windowMinutes * 60_000L
        prune(at, settings)
        val previous = lastCompleted
        hint = when {
            previous == null -> "首次进入不计重开次数"
            !previous.eligibleExit -> "本次先进入了其他应用，暂不累计（避免将微信外链返回计入）"
            previous.duration >= settings.shortSessionSeconds * 1000L -> "上次聊天较长，已清空此前累计"
            at - previous.leftAt < settings.minAwaySeconds * 1000L -> "本次离开不足 ${settings.minAwaySeconds} 秒，不计入自动规则"
            at - previous.leftAt >= window -> "上次离开已超过 ${settings.windowMinutes} 分钟窗口"
            else -> ""
        }
        if (previous != null && previous.eligibleExit &&
            previous.duration < settings.shortSessionSeconds * 1000L &&
            at - previous.leftAt >= settings.minAwaySeconds * 1000L &&
            at - previous.leftAt < window) {
            reopens.addLast(at)
        }
        val sensitive = settings.isSensitive(localTime)
        val threshold = threshold(settings, localTime)
        if (hint.isEmpty()) hint = "本次已计入；选择“心情不错”不会清除累计"
        // Every counted reopen must also have a qualifying short preceding session.
        if (settings.autoEnabled && allowAuto && reopens.size >= threshold) {
            actions += BehaviorAction.AutoLock(
                "${settings.windowMinutes} 分钟内重新进入微信 ${reopens.size} 次；此前均为短于 ${settings.shortSessionSeconds} 秒的会话，" +
                    "每次离开至少 ${settings.minAwaySeconds} 秒" + if (sensitive) "（敏感时段）" else ""
            )
            reopens.clear()
            triggered = true
            hint = "已满足组合条件，启动冷静锁"
        } else actions += BehaviorAction.Greeting(at)
        return actions
    }

    fun screenOff(at: Long): List<BehaviorAction> {
        val start = sessionStarted
        val actions = if (current == AppKind.WECHAT && start != null)
            listOf(BehaviorAction.Session(start, at, (at - start).coerceAtLeast(0))) else emptyList()
        current = AppKind.OTHER; currentPackage = ""; sessionStarted = null; lastCompleted = null
        return actions
    }

    fun clearRisk() { reopens.clear(); lastCompleted = null; triggered = false; hint = "等待新的合格重开" }
    fun reset() {
        clearRisk(); current = AppKind.OTHER; currentPackage = ""; sessionStarted = null; lastEventAt = Long.MIN_VALUE
    }
    fun isInWechat() = current == AppKind.WECHAT
    private fun threshold(settings: GuardSettings, time: LocalTime): Int {
        val reopensNeeded = if (settings.isSensitive(time)) (settings.reopenThreshold - 1).coerceAtLeast(2) else settings.reopenThreshold
        return maxOf(reopensNeeded, settings.shortSessionThreshold)
    }
    private fun prune(at: Long, settings: GuardSettings) {
        val window = settings.windowMinutes * 60_000L
        while (reopens.isNotEmpty() && at - reopens.first >= window) reopens.removeFirst()
    }
    fun behaviorHint(at: Long = lastEventAt, settings: GuardSettings = lastSettings, time: LocalTime = lastLocalTime): String {
        prune(at, settings)
        if (triggered) return hint
        return "${settings.windowMinutes} 分钟内合格重开 ${reopens.size}/${threshold(settings, time)} 次" +
            (if (!settings.autoEnabled) "（自动冷静已关闭）" else "") + "\n最近一次判断：$hint"
    }
}
