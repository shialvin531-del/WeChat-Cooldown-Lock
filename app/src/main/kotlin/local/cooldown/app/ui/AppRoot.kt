package local.cooldown.app.ui

import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.cooldown.app.*
import local.cooldown.app.monitor.PermissionState
import local.cooldown.app.monitor.EnhancedRuntime
import local.cooldown.core.*
import local.cooldown.data.*
import java.text.DateFormat
import java.util.Date

@Composable fun AppRoot(vm: GuardViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val page by vm.page.collectAsStateWithLifecycle()
    val remaining by vm.remaining.collectAsStateWithLifecycle()
    val permissions by vm.permissions.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val storageError by vm.repository.storageError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler(page != Page.HOME) { vm.page.value = Page.HOME }
    Scaffold(containerColor = CanvasColor, bottomBar = {
        NavigationBar(containerColor = CanvasColor, tonalElevation = 0.dp) {
            listOf(Page.HOME to "首页", Page.SETTINGS to "保护设置", Page.JOURNAL to "情绪记录").forEach { (target, label) ->
                NavigationBarItem(selected = page == target, onClick = { vm.page.value = target },
                    icon = { Text(when (target) { Page.HOME -> "○"; Page.SETTINGS -> "≡"; else -> "□" }) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding())) {
            val current = state
            if (current == null) PageColumn { Heading("微信冷静锁", "正在读取本机设置…")
                CircularProgressIndicator()
                if (storageError != null) Text(storageError!!)
            } else when (page) {
                Page.HOME -> HomeScreen(vm, current, remaining, permissions)
                Page.LOCK -> LockScreen(remaining, current.deadline?.reason ?: current.lastReason.ifBlank { "主动冷静" },
                    { vm.page.value = Page.JOURNAL }, {
                        if (remaining > 0) context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                        else vm.page.value = Page.HOME
                    })
                Page.SETTINGS -> SettingsScreen(vm, current.settings, permissions)
                Page.JOURNAL -> JournalScreen(vm)
            }
        }
    }
    val error = message ?: storageError
    if (error != null) AlertDialog(onDismissRequest = { vm.message.value = null; vm.repository.clearError() },
        title = { Text("提示") }, text = { Text(error) },
        confirmButton = { TextButton({ vm.message.value = null; vm.repository.clearError() }) { Text("知道了") } })
}

@Composable private fun HomeScreen(vm: GuardViewModel, state: StoredState, remaining: Long, permissions: PermissionState) {
    val status by vm.monitor.collectAsStateWithLifecycle()
    val elapsed by vm.elapsedNow.collectAsStateWithLifecycle()
    val enhanced by EnhancedRuntime.connected.collectAsStateWithLifecycle()
    val enhancedHealth by EnhancedRuntime.health.collectAsStateWithLifecycle()
    var showHelp by remember { mutableStateOf(false) }
    var pauseConfirm by remember { mutableStateOf(false) }
    val enhancedRunning = enhanced && enhancedHealth.isRunning(elapsed)
    val active = (status.running && elapsed - status.heartbeat < 7000 && status.error == null && !enhanced) ||
        (enhancedRunning && (state.settings.monitoringWanted || remaining > 0))
    PageColumn {
        Text("WeChat Cooldown Lock", color = CalmGreen, style = MaterialTheme.typography.labelLarge)
        Heading("微信冷静锁", "给感受一点空间，给回复一点时间。")
        CalmCard {
            val title = when {
                !state.settings.monitoringWanted && remaining == 0L -> "全部后台监测已暂停"
                enhanced && enhancedHealth.error != null -> "增强保护显示异常 · 正在重试"
                enhanced && enhancedHealth.heartbeat == null -> "增强保护正在初始化"
                enhanced && !enhancedRunning -> "增强服务已连接 · 请检查运行状态"
                enhancedRunning -> "增强保护已连接"
                !permissions.complete -> "保护未生效 · 需要授权"
                active -> "本地监测正在运行"
                else -> "后台保护未运行"
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("monitor_status"))
            Text(if (active) "每次从其他应用进入微信，问候一次；微信内部聊天与朋友圈切换不重复问候。"
                else "开启使用情况访问和悬浮窗权限后，后台监测才能主动提供问候与保护。", style = MaterialTheme.typography.bodyMedium)
            listOfNotNull(status.error, enhancedHealth.error).distinct().forEach {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            val entry = maxOf(status.lastEntryWall, enhancedHealth.lastEntryWall)
            if (entry > 0) Text("最近检测到进入微信：${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(entry))}", style = MaterialTheme.typography.bodySmall)
            val hint = if (enhanced) enhancedHealth.behaviorHint else status.behaviorHint
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.bodySmall)
            if (!enhanced) Text("建议开启增强保护，降低切换事件延迟造成的漏检。", style = MaterialTheme.typography.bodySmall)
            if (!permissions.complete) QuietButton("开启所需权限") { vm.page.value = Page.SETTINGS }
            else if (!active) PrimaryButton("启动 / 重试监测", onClick = vm::restartMonitoring)
            SwitchRow("行为自动冷静", "关闭此项仍保留情绪问候", state.settings.autoEnabled) { checked -> vm.settings { it.copy(autoEnabled = checked) } }
        }
        if (remaining > 0) {
            CalmCard {
                Text("冷静中 · ${countdown(remaining)}", style = MaterialTheme.typography.headlineSmall)
                Text(state.deadline?.reason ?: "", style = MaterialTheme.typography.bodyMedium)
                if (!active) Text("计时仍有效，微信保护页暂未生效，请开启监测。", color = MaterialTheme.colorScheme.error)
                PrimaryButton("查看冷静锁", onClick = { vm.page.value = Page.LOCK })
            }
        } else {
            PrimaryButton("一键冷静 ${state.settings.manualMinutes} 分钟", Modifier.testTag("start_manual"), vm::manualLock)
            if (state.recovered) CalmCard {
                Text("上次冷静已结束，已自动解锁", style = MaterialTheme.typography.titleMedium)
                Text("当时的原因：${state.lastReason}", style = MaterialTheme.typography.bodyMedium)
                Reflection()
            }
        }
        QuietButton("想发的话，先写在这里") { vm.page.value = Page.JOURNAL }
        QuietButton("添加桌面一键冷静快捷方式", vm::pinShortcut)
        Text("离线 · 单人使用 · 不需要账号\n不读取微信聊天、输入框、密码或通知。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton({ showHelp = true }) { Text("使用边界与真机自检") }
        if (state.settings.monitoringWanted && remaining == 0L) TextButton({ pauseConfirm = true }) { Text("暂停全部后台监测") }
    }
    if (pauseConfirm) AlertDialog(onDismissRequest = { pauseConfirm = false }, title = { Text("暂停全部监测？") },
        text = { Text("暂停后不再显示情绪问候或微信保护页。当前计时保留，到期仍自动结束。") },
        confirmButton = { TextButton({ pauseConfirm = false; vm.settings { it.copy(monitoringWanted = false) } }) { Text("暂停") } },
        dismissButton = { TextButton({ pauseConfirm = false }) { Text("保持开启") } })
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false }, title = { Text("保护如何工作") },
        text = { Text("首次使用：授权后确认主页显示“本地监测正在运行”，从桌面打开微信，选择一个心情，应正常进入。微信内部切换聊天、朋友圈不应重复弹出。\n\n自动触发使用组合条件。离开微信后先回桌面或打开微信冷静锁的短会话可以累计；离开不足设定秒数仅跳过本次。选择“心情不错”不清空累计，首次进入不算“重新打开”。正常聊天出现相同行为也可能触发，规则不能识别真实情绪。\n\n保护页有检测延迟，不能覆盖通知快捷回复、所有分屏/小窗、微信分身、其他设备或系统停用后的操作。系统可能延迟或不提供使用事件，也可能关闭后台服务。不能保证持续运行或完全拦截。\n\n通知栏有一键冷静操作。快速设置面板的编辑菜单可添加“一键冷静”磁贴。手机重启后请检查状态。\n\n本版的厂商真机后台存活未获验证，请在自己的手机上检查。") },
        confirmButton = { TextButton({ showHelp = false }) { Text("知道了") } })
}

@Composable private fun SettingsScreen(vm: GuardViewModel, settings: GuardSettings, permissions: PermissionState) {
    PageColumn {
        Heading("自动保护设置", "规则只判断使用行为，不判断你是否生气。")
        PermissionCard(permissions, vm)
        CalmCard {
            Text("组合触发条件", style = MaterialTheme.typography.titleLarge)
            Stepper("时间窗口（分钟）", settings.windowMinutes, 1, 30) { value -> vm.settings { it.copy(windowMinutes = value) } }
            Stepper("重新打开次数", settings.reopenThreshold, 2, 10) { value -> vm.settings { it.copy(reopenThreshold = value) } }
            Stepper("短会话上限（秒）", settings.shortSessionSeconds, 15, 180, 5) { value -> vm.settings { it.copy(shortSessionSeconds = value) } }
            Stepper("短会话至少几次", settings.shortSessionThreshold, 2, 10) { value -> vm.settings { it.copy(shortSessionThreshold = value) } }
            Stepper("离开至少几秒才计数", settings.minAwaySeconds, 5, 120, 5) { value -> vm.settings { it.copy(minAwaySeconds = value) } }
            Text("以上条件须同时满足。离开微信后先回桌面或打开微信冷静锁，再返回微信，可以累计。微信内部页面和直接打开外链后的返回不累计。离开时间不足仅跳过本次，不清空之前的次数；选择“心情不错”也不清空。聊天达到短会话上限或次数超出时间窗口时不再累计。", style = MaterialTheme.typography.bodySmall)
            Text("正常聊天若也出现相同的短会话和反复返回行为，仍可能触发。规则无法判断聊天内容或真实情绪；若经常误触发，可提高重新打开次数或离开时长。", style = MaterialTheme.typography.bodySmall)
        }
        CalmCard {
            DurationSelector("自动触发后的冷静时长", settings.autoMinutes) { value -> vm.settings { it.copy(autoMinutes = value) } }
            DurationSelector("一键冷静的时长", settings.manualMinutes) { value -> vm.settings { it.copy(manualMinutes = value) } }
        }
        CalmCard {
            Text("情绪问候后的操作", style = MaterialTheme.typography.titleLarge)
            SwitchRow("😠 我很生气", "选中此心情后直接启动冷静锁", settings.angryStartsLock) { value -> vm.settings { it.copy(angryStartsLock = value) } }
            SwitchRow("💔 害怕失去这段感情", "选中此心情后直接启动冷静锁", settings.fearStartsLock) { value -> vm.settings { it.copy(fearStartsLock = value) } }
            Text("默认提供一键冷静，也允许继续聊天。烦躁时正常进入微信，显示可收起的小入口。", style = MaterialTheme.typography.bodySmall)
        }
        CalmCard {
            SwitchRow("容易冲突的时段", "默认关闭，支持跨午夜", settings.sensitiveEnabled) { value -> vm.settings { it.copy(sensitiveEnabled = value) } }
            TimeButton("开始", settings.sensitiveStartMinute) { value -> vm.settings { it.copy(sensitiveStartMinute = value) } }
            TimeButton("结束", settings.sensitiveEndMinute) { value -> vm.settings { it.copy(sensitiveEndMinute = value) } }
            Text("时段内将重新打开门槛降低 1 次（最低 2 次），仍须满足短会话及离开时长条件。不会改变情绪问候频率。起止相同表示不启用时段。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun PermissionCard(permissions: PermissionState, vm: GuardViewModel) {
    val context = LocalContext.current
    val enhanced by EnhancedRuntime.connected.collectAsStateWithLifecycle()
    var disclosure by remember { mutableStateOf<String?>(null) }
    fun launch(action: String, packageUri: Boolean) {
        try { context.startActivity(Intent(action).apply { if (packageUri) data = "package:${context.packageName}".toUri() }) }
        catch (_: ActivityNotFoundException) { vm.message.value = "系统未提供此入口，请在系统设置中手动查找。" }
    }
    CalmCard {
        Text("权限与后台运行", style = MaterialTheme.typography.titleLarge)
        QuietButton("增强保护（建议开启）：${if (enhanced) "已连接" else "未连接"}") { disclosure = "enhanced" }
        QuietButton("使用情况访问：${if (permissions.usage) "已开启" else "未开启"}") { disclosure = "usage" }
        QuietButton("悬浮保护页面：${if (permissions.overlay) "已开启" else "未开启"}") { disclosure = "overlay" }
        QuietButton("允许通知与通知栏快捷入口") { (context as? MainActivity)?.requestNotificationPermission() }
        QuietButton("检查系统后台 / 电池限制") { launch(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
        Text("增强保护需由你在无障碍设置中主动开启，只监听窗口所属应用，不读取聊天或输入内容。使用情况访问用于补充检测与会话统计。", style = MaterialTheme.typography.bodySmall)
        Text(if (Build.MANUFACTURER.equals("xiaomi", true) || Build.MANUFACTURER.equals("redmi", true) || Build.MANUFACTURER.equals("poco", true))
            "小米 / HyperOS：请在系统应用详情检查后台自启动与电池限制，菜单名称随系统版本变化。"
            else "请按本机系统检查后台运行与电池限制；主页会显示服务连接及运行状态。", style = MaterialTheme.typography.bodySmall)
        Text("授予两项必要权限后，返回主页会按已开启的设置启动后台监测。若厂商系统停止服务，主页会显示未运行。通知权限被拒绝时，系统可能只在活动应用管理中显示服务。", style = MaterialTheme.typography.bodySmall)
    }
    disclosure?.let { permission ->
        AlertDialog(onDismissRequest = { disclosure = null },
            title = { Text(when(permission) { "enhanced" -> "为什么建议开启增强保护"; "usage" -> "为什么需要使用情况访问"; else -> "为什么需要悬浮窗权限" }) },
            text = { Text(if (permission == "enhanced")
                "通过无障碍服务监听应用窗口切换，减少系统使用事件延迟造成的漏问候；冷静期使用无障碍保护覆盖页。不读取聊天、联系人、输入框、密码或通知，不截图，不执行手势。\n\n请在下一页找到“微信冷静锁 · 增强保护”主动开启。冷静仍按原截止时间自动结束，系统设置可关闭本服务。"
                else if (permission == "usage")
                "通过 UsageStats 读取应用进入前台的包名和时间，计算微信会话与反复进入行为。不读取聊天、输入框、密码或通知，不会上传数据。请在下一页由你主动开启。"
                else "在检测到进入微信时显示情绪问候，在冷静期显示暂时保护页；烦躁时可显示可收起的冷静入口。不修改微信。冷静到期自动结束，期间可以回桌面使用其他应用。请在下一页主动允许。") },
            confirmButton = { TextButton({ disclosure = null
                launch(when(permission) { "enhanced" -> Settings.ACTION_ACCESSIBILITY_SETTINGS; "usage" -> Settings.ACTION_USAGE_ACCESS_SETTINGS; else -> Settings.ACTION_MANAGE_OVERLAY_PERMISSION }, permission != "enhanced")
            }) { Text("了解，前往设置") } }, dismissButton = { TextButton({ disclosure = null }) { Text("暂不") } })
    }
}

@Composable private fun SwitchRow(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, change)
    }
}

@Composable private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int = 1, change: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton({ change((value - step).coerceAtLeast(min)) }, enabled = value > min, modifier = Modifier.width(44.dp)) { Text("−") }
        Text(value.toString(), Modifier.widthIn(min = 28.dp), style = MaterialTheme.typography.titleMedium)
        TextButton({ change((value + step).coerceAtMost(max)) }, enabled = value < max, modifier = Modifier.width(44.dp)) { Text("+") }
    }
}

@Composable private fun DurationSelector(label: String, value: Int, change: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LOCK_DURATIONS.forEach { minutes -> FilterChip(value == minutes, { change(minutes) }, label = { Text("$minutes 分钟") }) }
    }
}

@Composable private fun TimeButton(label: String, minute: Int, change: (Int) -> Unit) {
    val context = LocalContext.current
    QuietButton("$label  ${"%02d:%02d".format(minute / 60, minute % 60)}") {
        TimePickerDialog(context, { _, hour, min -> change(hour * 60 + min) }, minute / 60, minute % 60, true).show()
    }
}

@Composable private fun JournalScreen(vm: GuardViewModel) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val mood by vm.draftMood.collectAsStateWithLifecycle()
    val entries by vm.journal.collectAsStateWithLifecycle()
    val locks by vm.locks.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val stats by vm.statistics.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var erase by remember { mutableStateOf(false) }
    PageColumn {
        Heading("情绪记录", "写在本机，不会自动发送。")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(tab == 0, { tab = 0 }, label = { Text("写下来") })
            FilterChip(tab == 1, { tab = 1 }, label = { Text("记录与统计") })
        }
        if (tab == 0) {
            OutlinedTextField(draft, { vm.draft.value = it.take(6000) }, Modifier.fillMaxWidth().testTag("journal_input"),
                minLines = 6, maxLines = 12, label = { Text("原本想发送的话") }, supportingText = { Text("${draft.length} / 6000 · 草稿保存在本机") })
            Text("当时的心情", style = MaterialTheme.typography.titleMedium)
            Mood.entries.forEach { item ->
                FilterChip(mood == item, { vm.draftMood.value = item }, label = { Text("${item.emoji} ${item.label}") })
            }
            PrimaryButton("保存到本机", Modifier.testTag("save_note"), vm::saveNote)
            Text("保存情绪、时间与最近一次触发原因。没有发送、分享或导出功能。", style = MaterialTheme.typography.bodySmall)
        } else {
            CalmCard {
                Text("最近 7 天", style = MaterialTheme.typography.titleMedium)
                Text("冷静 ${stats.lockCount} 次 · 文字记录 ${stats.noteCount} 条\n已记录微信使用约 ${stats.usageMillis / 60_000} 分钟", style = MaterialTheme.typography.bodyLarge)
                Text("统计来自监测运行期间捕获的已结束会话，可能遗漏；不代表完整微信使用时长。", style = MaterialTheme.typography.bodySmall)
            }
            locks.take(10).forEach { lock -> CalmCard {
                Text("冷静 · ${date(lock.startedAt)}", style = MaterialTheme.typography.titleSmall)
                Text(lock.reason)
                Text(when (lock.outcome) { "expired" -> "已自动结束"; "emergency" -> "旧版提前结束"; else -> "计时记录，以主页当前状态为准" }, style = MaterialTheme.typography.bodySmall)
            } }
            if (entries.isEmpty()) Text("还没有情绪记录。")
            entries.forEach { entry -> CalmCard {
                val feeling = Mood.entries.firstOrNull { it.name == entry.mood }
                Text("${date(entry.at)} · ${feeling?.label ?: entry.mood}", style = MaterialTheme.typography.titleSmall)
                if (entry.kind == "checkin") Text("进入微信时的问候", style = MaterialTheme.typography.bodySmall)
                if (entry.text.isNotBlank()) Text(entry.text)
                Text("原因：${entry.reason}", style = MaterialTheme.typography.bodySmall)
                TextButton({ vm.deleteEntry(entry.id) }) { Text("删除这条记录") }
            } }
            Text("页面展示最近 300 条情绪记录、100 次冷静和 2000 次会话。单条可删除，全部历史可一键清除。", style = MaterialTheme.typography.bodySmall)
        }
        QuietButton("删除全部记录、统计与草稿") { erase = true }
    }
    if (erase) AlertDialog(onDismissRequest = { erase = false }, title = { Text("删除全部本地历史？") },
        text = { Text("删除后无法恢复。保护设置与当前冷静计时会保留。") },
        confirmButton = { TextButton({ erase = false; vm.eraseHistory() }) { Text("删除") } },
        dismissButton = { TextButton({ erase = false }) { Text("保留") } })
}

private fun date(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
