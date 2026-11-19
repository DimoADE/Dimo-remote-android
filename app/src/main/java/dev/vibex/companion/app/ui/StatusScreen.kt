package dev.vibex.companion.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.PlanUsageResponse
import dev.vibex.companion.PlanUsageWindow
import dev.vibex.companion.ProjectUsageStatistics
import dev.vibex.companion.UsageDateRange
import dev.vibex.companion.agentDisplayName
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.UiState
import dev.vibex.companion.app.ui.theme.PlexMono
import dev.vibex.companion.app.ui.theme.VibexTheme
import kotlinx.coroutines.launch
import java.util.Locale

private enum class BoardDashboardMode(val id: String, val label: String) {
    Overview("overview", "用量概览"),
    PlanQuota("quota", "套餐额度"),
}

private data class PlanAgentDescriptor(
    val id: String,
    val title: String,
    val sourceNote: String,
)

private val planAgentDescriptors = listOf(
    PlanAgentDescriptor("claude_code", "Claude Code 套餐", "读取设置里的 Claude 订阅凭据"),
    PlanAgentDescriptor("codex", "Codex 套餐", "读取已安装的 Codex 与账号限额"),
    PlanAgentDescriptor("cursor", "Cursor 套餐", "读取本机 Cursor 登录凭据与官方用量接口"),
    PlanAgentDescriptor("grok", "Grok 套餐", "读取本机 Grok 登录凭据与官方套餐接口"),
    PlanAgentDescriptor("opencode", "OpenCode Go 套餐", "读取 OpenCode Go 订阅凭据与官方用量接口"),
    PlanAgentDescriptor("kimi_code", "Kimi 套餐", "读取 Kimi Code 订阅凭据与官方用量接口"),
    PlanAgentDescriptor("antigravity", "Antigravity 套餐", "读取设置里的 Antigravity 订阅凭据"),
)

/**
 * 1:1 port of iOS `BoardView.swift`:
 * Streamlined Analytics & Plan Quota Dashboard (`用量概览` | `套餐额度`).
 */
@Composable
fun StatusScreen(
    state: UiState,
    model: CompanionModel,
) {
    val colors = VibexTheme.colors
    val scope = rememberCoroutineScope()
    val projects = state.catalog?.projects.orEmpty()

    var modeId by rememberSaveable { mutableStateOf(BoardDashboardMode.Overview.id) }
    val activeMode = BoardDashboardMode.entries.find { it.id == modeId } ?: BoardDashboardMode.Overview

    var selectedTarget by rememberSaveable {
        mutableStateOf(
            if (!state.selectedProjectId.isNullOrBlank()) "project:${state.selectedProjectId}" else "global",
        )
    }
    var dateRange by remember { mutableStateOf(UsageDateRange.SevenDays) }
    var statistics by remember { mutableStateOf<ProjectUsageStatistics?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showTargetMenu by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    var selectedPlanAgent by remember { mutableStateOf(planAgentDescriptors.first()) }
    val planUsageByAgent = remember { mutableStateMapOf<String, PlanUsageResponse>() }
    var isLoadingPlan by remember { mutableStateOf(false) }

    val selectedTargetLabel = remember(selectedTarget, projects) {
        if (selectedTarget == "global") "全局"
        else {
            val id = selectedTarget.removePrefix("project:")
            projects.find { it.id.equals(id, ignoreCase = true) }?.name ?: "当前项目"
        }
    }

    suspend fun reloadStatistics() {
        isLoading = true
        val sc = if (selectedTarget == "global") "global" else "project"
        val pid = if (selectedTarget == "global") null else selectedTarget.removePrefix("project:")
        runCatching {
            statistics = model.fetchUsageStatistics(sc, pid, dateRange)
            errorMessage = null
        }.onFailure { err ->
            errorMessage = err.message ?: "读取计量统计失败"
        }
        isLoading = false
    }

    suspend fun loadPlanUsage(agentId: String, force: Boolean = false) {
        if (!force && planUsageByAgent[agentId] != null) return
        isLoadingPlan = true
        val res = model.fetchAgentPlanUsage(agentId)
        planUsageByAgent[agentId] = res
        isLoadingPlan = false
    }

    LaunchedEffect(selectedTarget, dateRange) {
        reloadStatistics()
    }

    LaunchedEffect(activeMode, selectedPlanAgent.id) {
        if (activeMode == BoardDashboardMode.PlanQuota) {
            loadPlanUsage(selectedPlanAgent.id)
        }
    }

    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            if (activeMode == BoardDashboardMode.PlanQuota) {
                loadPlanUsage(selectedPlanAgent.id, force = true)
            } else {
                reloadStatistics()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Top Header: Plain Scope Selector | Centered "看板" | Refresh Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (activeMode == BoardDashboardMode.Overview) {
                Box(modifier = Modifier.align(Alignment.CenterStart)) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showTargetMenu = true }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = if (selectedTarget == "global") Icons.Rounded.Public else Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = selectedTargetLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 104.dp),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    VibexDropdownMenu(
                        expanded = showTargetMenu,
                        onDismissRequest = { showTargetMenu = false },
                    ) {
                        VibexDropdownMenuItem(
                            text = "全局",
                            leadingIcon = Icons.Rounded.Public,
                            selected = selectedTarget == "global",
                            onClick = {
                                showTargetMenu = false
                                selectedTarget = "global"
                            },
                        )
                        if (projects.isNotEmpty()) {
                            VibexDropdownDivider()
                        }
                        projects.forEach { project ->
                            val target = "project:${project.id}"
                            VibexDropdownMenuItem(
                                text = project.name,
                                leadingIcon = Icons.Rounded.Folder,
                                selected = selectedTarget == target,
                                onClick = {
                                    showTargetMenu = false
                                    selectedTarget = target
                                },
                            )
                        }
                    }
                }
            }

            Text(
                text = "看板",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(colors.card)
                    .border(0.6.dp, colors.stroke, CircleShape)
                    .clickable { refreshTrigger += 1 },
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading || isLoadingPlan) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 1.8.dp,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新",
                        tint = colors.textPrimary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }

        // 2-Mode Segmented Picker ("用量概览" | "套餐额度")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.segmentedTrack)
                .padding(3.dp),
        ) {
            BoardDashboardMode.entries.forEach { mode ->
                val selected = activeMode == mode
                Text(
                    text = mode.label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (selected) Modifier.shadow(1.5.dp, RoundedCornerShape(8.dp), ambientColor = Color.Black.copy(0.08f))
                            else Modifier,
                        )
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) colors.segmentedThumb else Color.Transparent)
                        .clickable { modeId = mode.id }
                        .padding(vertical = 7.dp),
                )
            }
        }

        when (activeMode) {
            BoardDashboardMode.PlanQuota -> {
                UsagePlanTabContent(
                    selectedPlanAgent = selectedPlanAgent,
                    onSelectPlanAgent = { selectedPlanAgent = it },
                    planUsageByAgent = planUsageByAgent,
                    isLoadingPlan = isLoadingPlan,
                )
            }

            BoardDashboardMode.Overview -> {
                if (isLoading && statistics == null) {
                    KanbanBoardSkeletonView(modifier = Modifier.fillMaxSize())
                } else if (errorMessage != null && statistics == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = BOTTOM_DOCK_CLEARANCE),
                        contentAlignment = Alignment.Center,
                    ) {
                        EmptyState(
                            icon = Icons.Rounded.Warning,
                            title = "统计读取失败",
                            message = errorMessage.orEmpty(),
                            action = "重试",
                            onAction = { scope.launch { reloadStatistics() } },
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = BOTTOM_DOCK_CLEARANCE + 18.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item(key = "range_picker") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "统计区间",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.textSecondary,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(
                                    modifier = Modifier
                                        .width(168.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.segmentedTrack)
                                        .padding(2.dp),
                                ) {
                                    UsageDateRange.entries.forEach { range ->
                                        val selected = dateRange == range
                                        Text(
                                            text = range.label,
                                            fontSize = 12.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (selected) colors.textPrimary else colors.textSecondary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(
                                                    if (selected) Modifier.shadow(1.5.dp, RoundedCornerShape(6.dp), ambientColor = Color.Black.copy(0.08f))
                                                    else Modifier,
                                                )
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (selected) colors.segmentedThumb else Color.Transparent)
                                                .clickable { dateRange = range }
                                                .padding(vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }

                        val stats = statistics
                        if (stats != null) {
                            item(key = "overview_body") {
                                UsageOverviewContent(stats)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageOverviewContent(stats: ProjectUsageStatistics) {
    val colors = VibexTheme.colors
    val preferred = stats.totalTokens.preferred

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 2x2 Summary Metric Grid
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UsageMetricCard(
                title = "总 Token",
                value = formatUsageTokens(preferred.resolvedTotal),
                subtitle = "平均每次 ${formatUsageTokens(stats.avgTokensPerSession)}",
                icon = Icons.Rounded.Memory,
                tint = Color(0xFFAF52DE),
                modifier = Modifier.weight(1f),
            )
            UsageMetricCard(
                title = "预估成本",
                value = formatUsageCost(stats.resolvedCost),
                subtitle = if (stats.totalSessions > 0 && stats.resolvedCost != null) {
                    "均 ${formatUsageCost(stats.resolvedCost!! / stats.totalSessions.toDouble())}/会话"
                } else "按官方单价估算",
                icon = Icons.Rounded.Paid,
                tint = Color(0xFF34C759),
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UsageMetricCard(
                title = "会话数",
                value = "${stats.totalSessions}",
                subtitle = "${stats.activeDaysCount} 个活跃日",
                icon = Icons.Rounded.ChatBubble,
                tint = Color(0xFF007AFF),
                modifier = Modifier.weight(1f),
            )
            UsageMetricCard(
                title = "输入 / 输出",
                value = "${formatUsageTokens(preferred.inputTokens)} / ${formatUsageTokens(preferred.outputTokens)}",
                subtitle = stats.cacheHitRatePercent?.let {
                    String.format(Locale.US, "缓存命中 %.1f%%", it)
                } ?: "缓存读取 ${formatUsageTokens(preferred.cacheReadTokens)}",
                icon = Icons.Rounded.SwapVert,
                tint = Color(0xFFFF9500),
                modifier = Modifier.weight(1f),
            )
        }

        // 7-Day Trend Bar Card
        val recentDays = remember(stats.dailyUsage) { stats.dailyUsage.takeLast(7) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.card)
                .border(0.75.dp, colors.stroke, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "近 7 日趋势",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                val peak = recentDays.maxOfOrNull { it.tokens.preferred.resolvedTotal } ?: 0L
                if (peak > 0L) {
                    Text(
                        text = "峰值 ${formatUsageTokens(peak)}",
                        style = TextStyle(
                            fontSize = 11.5.sp,
                            fontFamily = PlexMono,
                            fontFeatureSettings = "tnum",
                            color = colors.textSecondary,
                        ),
                    )
                }
            }

            if (recentDays.isEmpty()) {
                Text(
                    text = "当前区间暂无每日趋势记录",
                    fontSize = 12.5.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                val maxTokens = (recentDays.maxOfOrNull { it.tokens.preferred.resolvedTotal } ?: 1L)
                    .coerceAtLeast(1L)
                    .toFloat()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(116.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    recentDays.forEach { day ->
                        val dayTokens = day.tokens.preferred.resolvedTotal
                        val ratio = (dayTokens.toFloat() / maxTokens).coerceIn(0f, 1f)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(20.dp)
                                        .fillMaxHeight(if (dayTokens > 0L) ratio.coerceAtLeast(0.06f) else 0.03f)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                        .background(if (dayTokens > 0L) colors.accent else colors.segmentedTrack),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = day.date.takeLast(5),
                                style = TextStyle(
                                    fontSize = 10.sp,
                                    fontFamily = PlexMono,
                                    fontFeatureSettings = "tnum",
                                    color = colors.textSecondary,
                                ),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        // Top 6 Models Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.card)
                .border(0.75.dp, colors.stroke, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "常用模型",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
            )
            if (stats.byModel.isEmpty()) {
                Text(
                    text = "暂无模型调用记录",
                    fontSize = 12.5.sp,
                    color = colors.textSecondary,
                )
            } else {
                val topModels = stats.byModel.take(6)
                val maxModelTokens = (topModels.maxOfOrNull { it.tokens.preferred.resolvedTotal } ?: 1L)
                    .coerceAtLeast(1L)
                    .toFloat()
                topModels.forEach { item ->
                    val count = item.tokens.preferred.resolvedTotal
                    val ratio = (count.toFloat() / maxModelTokens).coerceIn(0f, 1f)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = item.model,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = formatUsageTokens(count),
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    fontFamily = PlexMono,
                                    fontFeatureSettings = "tnum",
                                    color = colors.textSecondary,
                                ),
                            )
                            if (item.cost != null) {
                                Text(
                                    text = formatUsageCost(item.cost),
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = PlexMono,
                                        fontFeatureSettings = "tnum",
                                        color = colors.textPrimary,
                                    ),
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(colors.segmentedTrack),
                        ) {
                            if (count > 0L) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(ratio.coerceAtLeast(0.03f))
                                        .height(5.dp)
                                        .clip(CircleShape)
                                        .background(colors.accent.copy(alpha = 0.85f)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsagePlanTabContent(
    selectedPlanAgent: PlanAgentDescriptor,
    onSelectPlanAgent: (PlanAgentDescriptor) -> Unit,
    planUsageByAgent: Map<String, PlanUsageResponse>,
    isLoadingPlan: Boolean,
) {
    val colors = VibexTheme.colors
    val response = planUsageByAgent[selectedPlanAgent.id]

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 4.dp,
            bottom = BOTTOM_DOCK_CLEARANCE + 18.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(planAgentDescriptors, key = { it.id }) { descriptor ->
                    val isSelected = selectedPlanAgent.id == descriptor.id
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(if (isSelected) colors.accent else colors.card)
                            .border(
                                width = 0.6.dp,
                                color = if (isSelected) Color.Transparent else colors.stroke,
                                shape = CircleShape,
                            )
                            .clickable { onSelectPlanAgent(descriptor) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        AgentAvatar(descriptor.id, 14.dp)
                        Text(
                            text = agentDisplayName(descriptor.id),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) colors.onAccent else colors.textPrimary,
                        )
                    }
                }
            }
        }

        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.card)
                    .border(0.75.dp, colors.stroke, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(selectedPlanAgent.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                        Text(selectedPlanAgent.sourceNote, fontSize = 12.sp, color = colors.textSecondary)
                    }
                    if (response is PlanUsageResponse.Ok && !response.usage.planType.isNullOrBlank()) {
                        Text(
                            text = "${response.usage.planType!!.uppercase()} 套餐",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.accent.copy(0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }

                when {
                    isLoadingPlan && response == null -> {
                        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accent, modifier = Modifier.size(24.dp))
                        }
                    }
                    response is PlanUsageResponse.Ok -> {
                        if (response.usage.windows.isEmpty()) {
                            Text("账号未返回任何限额窗口", fontSize = 13.sp, color = colors.textSecondary)
                        } else {
                            response.usage.windows.forEach { window ->
                                PlanWindowRow(window)
                            }
                        }
                        val credits = response.usage.credits
                        if (credits != null) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (colors.isDark) colors.codeSurface else colors.bg)
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.CreditCard, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (credits.unlimited) "额度不限量" else "赠送额度余额：${credits.balance ?: "--"}",
                                    style = TextStyle(
                                        fontSize = 13.sp,
                                        fontFeatureSettings = "tnum",
                                        color = colors.textSecondary,
                                    ),
                                )
                            }
                        }
                    }
                    response is PlanUsageResponse.Unavailable -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Info, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                            Text(response.unavailableDescription, fontSize = 13.sp, color = colors.textSecondary)
                        }
                    }
                    response is PlanUsageResponse.Error -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Warning, null, tint = colors.hold, modifier = Modifier.size(16.dp))
                            Text(response.message, fontSize = 13.sp, color = colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanWindowRow(window: PlanUsageWindow) {
    val colors = VibexTheme.colors
    val used = window.clampedUsedPercent
    val remaining = window.remainingPercent
    val barColor = when {
        used == null -> colors.accent
        used >= 90 -> colors.danger
        used >= 70 -> colors.hold
        else -> colors.accent
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (colors.isDark) colors.codeSurface else colors.bg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(window.localizedTitle, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                val reset = window.resetLabel()
                if (!reset.isNullOrBlank()) {
                    Text(reset, fontSize = 12.sp, color = colors.textSecondary)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = used?.let { "已用 $it%" } ?: "--",
                    style = TextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = PlexMono,
                        fontFeatureSettings = "tnum",
                        color = colors.textPrimary,
                    ),
                )
                Text(
                    text = remaining?.let { "剩余 $it%" } ?: "--",
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = PlexMono,
                        fontFeatureSettings = "tnum",
                        color = colors.textSecondary,
                    ),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(colors.segmentedTrack),
        ) {
            val ratio = ((used ?: 0) / 100f).coerceIn(0f, 1f)
            if (ratio > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(ratio)
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(barColor),
                )
            }
        }
    }
}

@Composable
private fun UsageMetricCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val colors = VibexTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(0.75.dp, colors.stroke, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
            Text(title, fontSize = 12.sp, color = colors.textSecondary)
        }
        Text(
            text = value,
            style = TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = PlexMono,
                fontFeatureSettings = "tnum",
                color = colors.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(subtitle, fontSize = 11.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatUsageCost(cost: Double?): String {
    if (cost == null) return "--"
    if (cost == 0.0) return "$0.00"
    if (cost < 0.01) return String.format(Locale.US, "$%.4f", cost)
    return String.format(Locale.US, "$%.2f", cost)
}

private fun formatUsageTokens(count: Long): String = when {
    count >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", count / 1_000_000_000.0)
    count >= 1_000_000L -> String.format(Locale.US, "%.2fM", count / 1_000_000.0)
    count >= 1_000L -> String.format(Locale.US, "%.1fK", count / 1_000.0)
    else -> "$count"
}
