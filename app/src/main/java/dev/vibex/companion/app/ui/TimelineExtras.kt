package dev.vibex.companion.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.RateLimitBanner
import dev.vibex.companion.agentDisplayName
import dev.vibex.companion.app.ui.theme.VibexTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Divider row for cross-engine handoff / fork (`agent_handoff` host note). */
@Composable
fun HandoffDivider(label: String) {
    val colors = VibexTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(colors.textTertiary.copy(alpha = 0.4f)))
        Text(
            text = label,
            color = colors.textTertiary,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(4f, fill = false),
        )
        Box(Modifier.weight(1f).height(0.5.dp).background(colors.textTertiary.copy(alpha = 0.4f)))
    }
}

/**
 * Rate-limit recovery banner. The host's live `conversation-rate-limit` push channel is not part of
 * the remote WebSocket, so the banner is derived from the `rate_limited` turn error and the
 * actions map to the host commands that exist (cancel wait / auto-resume).
 */
@Composable
fun RateLimitRecoveryBanner(
    banner: RateLimitBanner,
    agentId: String,
    writable: Boolean,
    onAutoResume: () -> Unit,
    onCancelWait: () -> Unit,
) {
    val colors = VibexTheme.colors
    val tint = colors.hold
    val engine = agentDisplayName(banner.engine.ifBlank { agentId })
    val recovery = when {
        banner.resetAtMs != null ->
            "预计 ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(banner.resetAtMs!!))} 后额度恢复"
        banner.retryAfterSeconds != null -> "约 ${banner.retryAfterSeconds} 秒后可重试"
        else -> "恢复时间未知"
    }
    Column(
        Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(0.12f))
            .border(0.5.dp, tint.copy(0.32f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.WarningAmber, null, tint = tint, modifier = Modifier.size(16.dp))
            Text("$engine 已触发限流", color = colors.textPrimary, fontSize = 14.sp)
        }
        Text(recovery, color = colors.textSecondary, fontSize = 13.sp)
        if (writable) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAutoResume) { Text("恢复后自动继续", color = colors.accent) }
                TextButton(onClick = onCancelWait) { Text("取消等待", color = colors.textSecondary) }
            }
        }
    }
}

/** One-line honest steering hint shown while a turn is in flight. */
@Composable
fun SteeringHint(canSteer: Boolean, known: Boolean) {
    val colors = VibexTheme.colors
    val text = when {
        !known -> "正在确认 Agent 是否支持回合纠偏…"
        canSteer -> "该 Agent 支持回合纠偏：发送的消息会直接引导当前回合"
        else -> "该 Agent 不支持回合纠偏：消息将排队，待当前回合结束后发送"
    }
    Text(
        text = text,
        color = colors.textTertiary,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    )
}
