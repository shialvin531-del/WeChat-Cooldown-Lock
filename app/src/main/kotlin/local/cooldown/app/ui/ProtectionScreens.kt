package local.cooldown.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.cooldown.core.Mood
import local.cooldown.core.countdown

@Composable fun GreetingScreen(onMood: (Mood) -> Unit, onHome: () -> Unit) {
    PageColumn {
        Spacer(Modifier.height(20.dp))
        Text("微信冷静锁 · 进入前的一次问候", color = CalmGreen, style = MaterialTheme.typography.labelLarge)
        Heading("现在的心情怎么样？", "点一下就好，不用解释。")
        Spacer(Modifier.height(8.dp))
        Mood.entries.forEach { mood ->
            FilledTonalButton({ onMood(mood) }, Modifier.fillMaxWidth().heightIn(min = 62.dp).testTag("mood_${mood.name}"),
                shape = MaterialTheme.shapes.medium) {
                Text("${mood.emoji}  ${mood.label}", fontSize = 18.sp)
            }
        }
        Text("这是一次自我感受确认，不是对聊天内容的判断。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onHome) { Text("先回桌面") }
    }
}

@Composable fun OfferScreen(mood: Mood, minutes: Int, onLock: () -> Unit, onContinue: () -> Unit, onHome: () -> Unit) {
    PageColumn {
        Text("${mood.emoji}  ${mood.label}", color = CalmGreen)
        Heading("先给自己一点时间", "你可以先冷静，也可以继续聊天。")
        CalmCard { Reflection() }
        PrimaryButton("一键冷静 $minutes 分钟", onClick = onLock)
        QuietButton("继续聊天，保留冷静入口", onContinue)
        TextButton(onHome) { Text("先回桌面") }
    }
}

@Composable fun LockScreen(remaining: Long, reason: String, onRecord: () -> Unit, onHome: () -> Unit) {
    PageColumn {
        Text("微信冷静锁", color = CalmGreen, style = MaterialTheme.typography.labelLarge)
        Heading(if (remaining > 0) "先暂停，再决定怎么回复" else "冷静结束，已自动解锁")
        if (remaining > 0) {
            Text(countdown(remaining), fontSize = 56.sp, fontWeight = FontWeight.Light, color = Ink, modifier = Modifier.testTag("countdown"))
            Text("剩余时间 · 到期自动恢复，无需手动解锁", style = MaterialTheme.typography.bodyMedium)
        }
        CalmCard {
            Text("${if (remaining > 0) "本次触发原因" else "当时启动的原因"}：$reason", style = MaterialTheme.typography.bodyLarge)
            Reflection()
        }
        PrimaryButton("冲动消息先写在这里", Modifier.testTag("record"), onRecord)
        QuietButton(if (remaining > 0) "先离开微信，回到桌面" else "回到主页", onHome)
        Text("保护通过本地使用行为触发，不能准确识别情绪，也不能保证拦住所有发送。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun Reflection() {
    Text("对方实际说了什么？\n我担心会发生什么？", style = MaterialTheme.typography.titleMedium, lineHeight = 30.sp)
    Text("先分开事实与担心。想说的话可以先留在本机，晚一点再看。", style = MaterialTheme.typography.bodyMedium)
}

@Composable fun Nudge(onLock: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = SoftGreen, shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TextButton(onLock) { Text("需要就停一下") }
            TextButton(onDismiss, Modifier.widthIn(min = 48.dp)) { Text("收起") }
        }
    }
}
