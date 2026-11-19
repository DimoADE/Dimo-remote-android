package dev.vibex.companion.app.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.TimelineItem
import dev.vibex.companion.agentDisplayName
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.UiState
import dev.vibex.companion.app.ui.AgentAvatar
import dev.vibex.companion.app.ui.theme.VibexTheme
import dev.vibex.companion.instancesForAgent

private val Shape = RoundedCornerShape(12.dp)

@Composable
private fun ChoiceRow(selected: Boolean, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    val colors = VibexTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Shape)
            .background(colors.card)
            .border(if (selected) 1.2.dp else 0.6.dp, if (selected) colors.textPrimary else colors.stroke, Shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = VibexTheme.colors
    Box(
        modifier = modifier
            .clip(Shape)
            .background(if (enabled) colors.accent else colors.card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.onAccent else colors.textTertiary)
    }
}

@Composable
fun EngineHandoffSheet(state: UiState, currentAgentId: String, model: CompanionModel, onDismiss: () -> Unit) {
    val colors = VibexTheme.colors
    val h by model.handoff.collectAsState()
    val writable = state.connection.allowsWrites
    val engines = state.catalog?.agents.orEmpty().filter { it.usable && !it.id.equals(currentAgentId, true) || (it.usable && it.id.equals(currentAgentId, true) && instancesForAgent(h.instances, it.id).size > 1) }
    val instances = instancesForAgent(h.instances, h.targetEngine)

    DraggableSheet(title = "切换引擎", onDismiss = { model.closeHandoff(); onDismiss() }) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (h.done) {
                Text("已切换到 ${agentDisplayName(h.targetEngine, "")}。下一轮将由新引擎接手，并带上交接上下文。", fontSize = 14.sp, color = colors.textPrimary)
                PrimaryButton("完成", true, { model.closeHandoff(); onDismiss() }, Modifier.fillMaxWidth())
                return@Column
            }
            Text("目标引擎", fontSize = 12.sp, color = colors.textTertiary, fontWeight = FontWeight.SemiBold)
            if (state.catalog == null) {
                Text("正在读取引擎列表…", fontSize = 13.sp, color = colors.textTertiary)
            } else if (engines.isEmpty()) {
                Text("没有其他可用引擎。请先在电脑端安装并登录其他智能体。", fontSize = 13.sp, color = colors.textTertiary)
            }
            engines.forEach { a ->
                ChoiceRow(selected = a.id == h.targetEngine, enabled = !h.busy, onClick = {
                    val inst = instancesForAgent(h.instances, a.id)
                    model.selectHandoffTarget(a.id, inst.firstOrNull { it.isDefault }?.instanceId ?: inst.singleOrNull()?.instanceId)
                }) {
                    AgentAvatar(a.id, 26.dp, a.iconSvg, a.displayName)
                    Text(a.displayName.ifBlank { agentDisplayName(a.id, "") }, fontSize = 14.5.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    if (a.id.equals(currentAgentId, true)) Text("当前", fontSize = 11.sp, color = colors.textTertiary)
                }
            }

            if (h.targetEngine.isNotBlank()) {
                Text("账号实例", fontSize = 12.sp, color = colors.textTertiary, fontWeight = FontWeight.SemiBold)
                when {
                    h.loadingInstances -> Text("正在读取账号实例…", fontSize = 13.sp, color = colors.textTertiary)
                    h.instancesError != null -> Text("无法读取账号实例（${h.instancesError}），将使用默认账号。", fontSize = 13.sp, color = colors.textTertiary)
                    instances.isEmpty() -> Text("该引擎没有额外账号实例，使用默认账号。", fontSize = 13.sp, color = colors.textTertiary)
                }
                instances.forEach { i ->
                    ChoiceRow(selected = i.instanceId == h.targetInstanceId, enabled = !h.busy, onClick = {
                        model.selectHandoffTarget(h.targetEngine, i.instanceId)
                    }) {
                        Text(i.displayName, fontSize = 14.sp, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        if (i.isDefault) Text("默认", fontSize = 11.sp, color = colors.textTertiary)
                    }
                }
            }

            h.preview?.let { p ->
                Column(
                    modifier = Modifier.fillMaxWidth().clip(Shape).background(colors.card).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("交接预览", fontSize = 12.sp, color = colors.textTertiary, fontWeight = FontWeight.SemiBold)
                    Text("打包 ${p.packedCount} 条消息，省略 ${p.omittedCount} 条", fontSize = 13.sp, color = colors.textPrimary)
                    Text("约 ${p.totalTokens} / ${p.tokenBudget} tokens", fontSize = 13.sp, color = colors.textPrimary)
                    if (p.promptText.isNotBlank()) {
                        Text(p.promptText.take(600) + if (p.promptText.length > 600) "…" else "", fontSize = 12.sp, color = colors.textTertiary)
                    }
                }
            }
            h.error?.let { Text(it, fontSize = 13.sp, color = colors.danger) }
            if (!writable) Text("当前为只读连接，无法执行切换。", fontSize = 13.sp, color = colors.textTertiary)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PrimaryButton(
                    if (h.busy && h.preview == null) "预览中…" else "预览",
                    enabled = h.targetEngine.isNotBlank() && !h.busy,
                    onClick = { model.previewHandoff() },
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    if (h.busy && h.preview != null) "切换中…" else "确认切换",
                    enabled = h.preview != null && !h.busy && writable,
                    onClick = { model.confirmHandoff() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
fun SideChatSheet(conversationId: String, items: List<TimelineItem>, model: CompanionModel, onDismiss: () -> Unit) {
    val colors = VibexTheme.colors
    val all by model.sideChats.collectAsState()
    val chat = all[conversationId]
    val messages = chat?.messages.orEmpty()
    val sending = chat?.sending == true
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(messages.size, sending) { scroll.animateScrollTo(scroll.maxValue) }

    DraggableSheet(title = "旁路对话", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "只读问答，不会写入当前会话，也不会打断正在进行的回合。结果在完成后一次性返回（手机端不显示流式输出）。",
                fontSize = 12.sp, color = colors.textTertiary,
            )
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                messages.forEach { m ->
                    val mine = m.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        Column(
                            modifier = Modifier.clip(Shape).background(if (m.isError) colors.danger.copy(alpha = 0.14f) else colors.card).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(m.content, fontSize = 14.sp, color = if (m.isError) colors.danger else colors.textPrimary)
                            m.toolCalls.forEach { t ->
                                Text("${t.name} · ${t.status}" + if (t.summary.isNotBlank()) " — ${t.summary.take(80)}" else "", fontSize = 11.sp, color = colors.textTertiary)
                            }
                        }
                    }
                }
                if (sending) Text("思考中…（最长 3 分钟）", fontSize = 13.sp, color = colors.textTertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).clip(Shape).background(colors.card).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (input.isEmpty()) Text("问点别的…", fontSize = 14.sp, color = colors.textTertiary)
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        enabled = !sending,
                        textStyle = TextStyle(fontSize = 14.sp, color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.textPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (sending) {
                    PrimaryButton("中止", true, { model.abortSideChat(conversationId) })
                } else {
                    PrimaryButton("发送", input.isNotBlank(), {
                        model.sendSideChat(conversationId, input, items)
                        input = ""
                    })
                }
            }
            if (messages.isNotEmpty() && !sending) {
                Text("清空对话", fontSize = 12.sp, color = colors.textTertiary, modifier = Modifier.clickable { model.clearSideChat(conversationId) })
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}
