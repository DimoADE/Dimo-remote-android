package dev.vibex.companion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.ConnectionState
import dev.vibex.companion.KanbanSessionStatus
import dev.vibex.companion.KanbanSessionSummary
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.UiState
import dev.vibex.companion.app.ui.theme.VibexTheme
import dev.vibex.companion.isRecentlyActiveReadSession
import dev.vibex.companion.parseHostTimestampEpochMillis
import dev.vibex.companion.updatedEpochMs
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class SessionListOrganization(val label: String) {
    StatusGrouped("状态分组"),
    WorkspaceGrouped("工作区视图"),
}

private val StatusOrange = Color(0xFFFF9500)
private val StatusPurple = Color(0xFFAF52DE)
private val StatusTeal = Color(0xFF30B0C7)
private val StatusBlue = Color(0xFF007AFF)
private val StatusGreen = Color(0xFF34C759)

/**
 * 1:1 port of iOS `ConversationsView` Level-1 Session Hub (`ConversationsView.swift`).
 * Features project filter menu, `UnifiedSearchBar`, `状态分组 / 工作区视图` toggle,
 * 5 status groups (`进行中`, `待检查`, `近期对话` 24h window, `待开始`, collapsible `已完成历史会话`),
 * and full long-press session management actions.
 */
@Composable
fun ConversationList(
    state: UiState,
    model: CompanionModel,
) {
    val colors = VibexTheme.colors
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var organization by remember { mutableStateOf(SessionListOrganization.StatusGrouped) }
    var searchQuery by remember { mutableStateOf("") }
    var isCompletedShelfExpanded by remember { mutableStateOf(false) }
    var kanbanSessions by remember { mutableStateOf<List<KanbanSessionSummary>>(emptyList()) }
    var initialLoading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var removedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var projectMenuExpanded by remember { mutableStateOf(false) }

    var contextTarget by remember { mutableStateOf<KanbanSessionSummary?>(null) }
    var contextAnchor by remember { mutableStateOf(Offset.Zero) }
    var renamingSession by remember { mutableStateOf<KanbanSessionSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deletingSession by remember { mutableStateOf<KanbanSessionSummary?>(null) }

    val projects = state.catalog?.projects.orEmpty()
    val workspaces = state.catalog?.workspaces.orEmpty()
    val selectedFilterProject = remember(projects, state.selectedProjectId) {
        val pid = state.selectedProjectId ?: return@remember null
        projects.find { it.id.equals(pid, ignoreCase = true) }
    }

    suspend fun reloadKanban(silent: Boolean = false) {
        if (!silent && kanbanSessions.isEmpty()) {
            initialLoading = true
        }
        runCatching {
            model.fetchKanbanSessions(state.selectedProjectId)
        }.onSuccess { fetched ->
            kanbanSessions = fetched
        }
        initialLoading = false
    }

    LaunchedEffect(state.selectedProjectId, state.connection, state.conversations.size) {
        if (state.connection == ConnectionState.Online) {
            reloadKanban(silent = kanbanSessions.isNotEmpty())
        } else {
            initialLoading = false
        }
    }

    LaunchedEffect(state.selectedProjectId, state.connection) {
        while (isActive) {
            val hasRunning = kanbanSessions.any { it.isRunning || it.status == KanbanSessionStatus.InProgress } ||
                state.conversations.any { it.status.equals("inprogress", ignoreCase = true) || it.status.equals("running", ignoreCase = true) }
            delay(if (hasRunning) 3_500L else 12_000L)
            if (state.connection == ConnectionState.Online) {
                reloadKanban(silent = true)
            }
        }
    }

    fun projectNameForWorkspace(workspaceId: String): String? {
        val ws = workspaces.find { it.id == workspaceId } ?: return null
        return projects.find { it.id.equals(ws.projectId, ignoreCase = true) }?.name
    }

    val mergedSessions = remember(
        kanbanSessions,
        state.conversations,
        workspaces,
        projects,
        selectedFilterProject,
        searchQuery,
        removedIds,
    ) {
        val byId = LinkedHashMap<String, KanbanSessionSummary>()
        for (s in kanbanSessions) {
            if (!s.isArchived && s.id !in removedIds) {
                byId[s.id] = s
            }
        }
        for (c in state.conversations) {
            if (c.id !in removedIds && !byId.containsKey(c.id)) {
                val branch = workspaces.find { it.id == c.workspaceId }?.branch
                val converted = KanbanSessionSummary.fromConversationSummary(c, branch = branch)
                if (!converted.isArchived) {
                    byId[c.id] = converted
                }
            }
        }
        val allowedWorkspaces: Set<String>? = selectedFilterProject?.let { proj ->
            workspaces.filter { it.projectId.equals(proj.id, ignoreCase = true) }.map { it.id }.toSet()
        }
        val q = searchQuery.trim().lowercase()
        byId.values
            .filter { item ->
                if (allowedWorkspaces != null && allowedWorkspaces.isNotEmpty() && item.workspaceId !in allowedWorkspaces) {
                    return@filter false
                }
                if (q.isEmpty()) return@filter true
                val projName = projectNameForWorkspace(item.workspaceId).orEmpty().lowercase()
                item.resolvedTitle.lowercase().contains(q) ||
                    item.workspaceDisplayLabel.lowercase().contains(q) ||
                    item.agentDisplayLabel.lowercase().contains(q) ||
                    item.model.orEmpty().lowercase().contains(q) ||
                    projName.contains(q)
            }
            .sortedWith(
                compareByDescending<KanbanSessionSummary> { it.isPinned }
                    .thenByDescending { it.updatedEpochMs ?: 0L },
            )
    }

    val isOnline = state.connection == ConnectionState.Online

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        // Level-1 Navigation Bar: Project Filter | Centered "会话" | Connection Pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Box(modifier = Modifier.align(Alignment.CenterStart)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { projectMenuExpanded = true }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = if (selectedFilterProject == null) Icons.Rounded.Layers else Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = selectedFilterProject?.name ?: "全部项目",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(15.dp),
                    )
                }

                VibexDropdownMenu(
                    expanded = projectMenuExpanded,
                    onDismissRequest = { projectMenuExpanded = false },
                ) {
                    VibexDropdownMenuItem(
                        text = "全部项目",
                        leadingIcon = Icons.Rounded.Layers,
                        selected = selectedFilterProject == null,
                        onClick = {
                            projectMenuExpanded = false
                            model.selectProjectInPlace(null)
                        },
                    )
                    if (projects.isNotEmpty()) {
                        VibexDropdownDivider()
                        projects.forEach { proj ->
                            val isSelected = selectedFilterProject?.id?.equals(proj.id, ignoreCase = true) == true
                            VibexDropdownMenuItem(
                                text = proj.name,
                                leadingIcon = Icons.Rounded.Folder,
                                selected = isSelected,
                                onClick = {
                                    projectMenuExpanded = false
                                    model.selectProjectInPlace(proj.id)
                                },
                            )
                        }
                    }
                }
            }

            Text(
                text = "会话",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                modifier = Modifier.align(Alignment.Center),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clip(CircleShape)
                    .background(colors.card)
                    .border(0.6.dp, colors.stroke, CircleShape)
                    .clickable { model.openDesktopConnect() }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.5.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) colors.pass else colors.hold),
                )
                Text(
                    text = if (isOnline) "已连接" else connectionLabel(state.connection),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textSecondary,
                )
            }
        }

        // Pending Approval / Question Banner
        if (state.inbox.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.hold.copy(alpha = 0.14f))
                    .clickable { model.openConversation(state.inbox.first().conversationId) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${state.inbox.size} 条待你审批或回复",
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                        color = colors.hold,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.hold,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (initialLoading && mergedSessions.isEmpty() && state.conversationsLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                    Text(
                        text = "正在同步会话…",
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                    )
                }
            }
        } else {
            CompanionRefresh(
                refreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        model.refreshCurrent()
                        reloadKanban(silent = true)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                val (runningList, reviewList, recentReadList, todoList, doneList) = remember(
                    mergedSessions,
                    state.manuallyCompletedSessionIds,
                ) {
                    val nowMs = System.currentTimeMillis()
                    val running = mergedSessions.filter {
                        it.status == KanbanSessionStatus.InProgress ||
                            (it.isRunning && it.status != KanbanSessionStatus.InReview)
                    }
                    val runningIds = running.mapTo(HashSet()) { it.id }
                    val review = mergedSessions.filter {
                        it.status == KanbanSessionStatus.InReview && it.id !in runningIds
                    }
                    val recentRead = mergedSessions.filter {
                        it.isRecentlyActiveReadSession(state.manuallyCompletedSessionIds, nowMs)
                    }
                    val todo = mergedSessions.filter {
                        it.status == KanbanSessionStatus.Todo && !it.isRunning
                    }
                    val done = mergedSessions.filter {
                        it.status == KanbanSessionStatus.Done &&
                            !it.isRunning &&
                            !it.isRecentlyActiveReadSession(state.manuallyCompletedSessionIds, nowMs)
                    }
                    listOf(running, review, recentRead, todo, done)
                }
                val hasActive = runningList.isNotEmpty() ||
                    reviewList.isNotEmpty() ||
                    recentReadList.isNotEmpty() ||
                    todoList.isNotEmpty()
                val isSearching = searchQuery.trim().isNotEmpty()

                val workspaceGroups = remember(mergedSessions, organization, projects, workspaces) {
                    if (organization != SessionListOrganization.WorkspaceGrouped) {
                        emptyList()
                    } else {
                        mergedSessions
                            .groupBy { item ->
                                val proj = projectNameForWorkspace(item.workspaceId) ?: "其他项目"
                                val branch = item.workspaceDisplayLabel
                                if (branch.isEmpty()) proj else "$proj · $branch"
                            }
                            .entries
                            .sortedByDescending { entry -> entry.value.firstOrNull()?.updatedEpochMs ?: 0L }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 6.dp,
                        bottom = BOTTOM_DOCK_CLEARANCE + 18.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Search + Segmented Organization Header
                    item(key = "header_controls") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            UnifiedSearchBar(
                                query = searchQuery,
                                onQueryChange = { searchQuery = it },
                                placeholder = "搜索会话、分支或模型…",
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.segmentedTrack)
                                        .padding(2.5.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    SessionListOrganization.entries.forEach { mode ->
                                        val selected = organization == mode
                                        Text(
                                            text = mode.label,
                                            fontSize = 12.5.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                            color = if (selected) colors.textPrimary else colors.textSecondary,
                                            modifier = Modifier
                                                .then(
                                                    if (selected) {
                                                        Modifier.shadow(
                                                            1.5.dp,
                                                            RoundedCornerShape(8.dp),
                                                            ambientColor = Color.Black.copy(alpha = 0.08f),
                                                        )
                                                    } else {
                                                        Modifier
                                                    },
                                                )
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (selected) colors.segmentedThumb else Color.Transparent)
                                                .clickable { organization = mode }
                                                .padding(horizontal = 12.dp, vertical = 5.5.dp),
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                Text(
                                    text = "${mergedSessions.size} 个会话",
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFeatureSettings = "tnum",
                                        color = colors.textSecondary,
                                    ),
                                )
                            }
                        }
                    }

                    if (mergedSessions.isEmpty()) {
                        item(key = "empty_sessions") {
                            EmptySessionsCard(isSearching = isSearching)
                        }
                    } else if (organization == SessionListOrganization.StatusGrouped) {
                        if (!hasActive && !isSearching) {
                            item(key = "calm_all_done_banner") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.card)
                                        .border(0.6.dp, colors.stroke, RoundedCornerShape(14.dp))
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        tint = StatusGreen,
                                        modifier = Modifier.size(17.dp),
                                    )
                                    Text(
                                        text = "当前没有进行中或近期对话，点底部 + 新建会话",
                                        fontSize = 13.sp,
                                        color = colors.textSecondary,
                                    )
                                }
                            }
                        }

                        if (runningList.isNotEmpty()) {
                            item(key = "sec_running") {
                                SessionSectionBlock(
                                    title = "进行中",
                                    tint = StatusOrange,
                                    icon = Icons.Rounded.Bolt,
                                    items = runningList,
                                    state = state,
                                    showStatusBadge = false,
                                    projectNameForWorkspace = ::projectNameForWorkspace,
                                    onTap = { session ->
                                        openSession(session, model) { viewedId ->
                                            kanbanSessions = kanbanSessions.map {
                                                if (it.id == viewedId) it.copy(status = KanbanSessionStatus.Done) else it
                                            }
                                        }
                                    },
                                    onLongPress = { session, offset ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextAnchor = offset
                                        contextTarget = session
                                    },
                                )
                            }
                        }

                        if (reviewList.isNotEmpty()) {
                            item(key = "sec_review") {
                                SessionSectionBlock(
                                    title = "待检查",
                                    tint = StatusPurple,
                                    icon = Icons.Rounded.Visibility,
                                    items = reviewList,
                                    state = state,
                                    showStatusBadge = false,
                                    projectNameForWorkspace = ::projectNameForWorkspace,
                                    onTap = { session ->
                                        openSession(session, model) { viewedId ->
                                            kanbanSessions = kanbanSessions.map {
                                                if (it.id == viewedId) it.copy(status = KanbanSessionStatus.Done) else it
                                            }
                                        }
                                    },
                                    onLongPress = { session, offset ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextAnchor = offset
                                        contextTarget = session
                                    },
                                )
                            }
                        }

                        if (recentReadList.isNotEmpty()) {
                            item(key = "sec_recent_read") {
                                SessionSectionBlock(
                                    title = "近期对话",
                                    tint = StatusTeal,
                                    icon = Icons.Rounded.Forum,
                                    items = recentReadList,
                                    state = state,
                                    showStatusBadge = false,
                                    projectNameForWorkspace = ::projectNameForWorkspace,
                                    onTap = { session ->
                                        openSession(session, model) { viewedId ->
                                            kanbanSessions = kanbanSessions.map {
                                                if (it.id == viewedId) it.copy(status = KanbanSessionStatus.Done) else it
                                            }
                                        }
                                    },
                                    onLongPress = { session, offset ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextAnchor = offset
                                        contextTarget = session
                                    },
                                )
                            }
                        }

                        if (todoList.isNotEmpty()) {
                            item(key = "sec_todo") {
                                SessionSectionBlock(
                                    title = "待开始",
                                    tint = StatusBlue,
                                    icon = Icons.Rounded.RadioButtonUnchecked,
                                    items = todoList,
                                    state = state,
                                    showStatusBadge = false,
                                    projectNameForWorkspace = ::projectNameForWorkspace,
                                    onTap = { session ->
                                        openSession(session, model) { viewedId ->
                                            kanbanSessions = kanbanSessions.map {
                                                if (it.id == viewedId) it.copy(status = KanbanSessionStatus.Done) else it
                                            }
                                        }
                                    },
                                    onLongPress = { session, offset ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextAnchor = offset
                                        contextTarget = session
                                    },
                                )
                            }
                        }

                        if (doneList.isNotEmpty()) {
                            item(key = "sec_done_shelf") {
                                val expanded = isSearching || isCompletedShelfExpanded
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(colors.card)
                                            .border(0.6.dp, colors.stroke, RoundedCornerShape(14.dp))
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                isCompletedShelfExpanded = !isCompletedShelfExpanded
                                            }
                                            .padding(horizontal = 14.dp, vertical = 11.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = if (expanded) {
                                                Icons.Rounded.KeyboardArrowDown
                                            } else {
                                                Icons.AutoMirrored.Rounded.KeyboardArrowRight
                                            },
                                            contentDescription = null,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Icon(
                                            imageVector = Icons.Rounded.Verified,
                                            contentDescription = null,
                                            tint = StatusGreen.copy(alpha = 0.88f),
                                            modifier = Modifier.size(15.dp),
                                        )
                                        Text(
                                            text = "已完成历史会话",
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = colors.textSecondary,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .background(colors.textPrimary.copy(alpha = 0.08f))
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(
                                                text = "${doneList.size}",
                                                style = TextStyle(
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontFeatureSettings = "tnum",
                                                    color = colors.textSecondary,
                                                ),
                                            )
                                        }
                                    }

                                    AnimatedVisibility(
                                        visible = expanded,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut(),
                                    ) {
                                        SessionCardGroup(
                                            items = doneList,
                                            state = state,
                                            showStatusBadge = false,
                                            projectNameForWorkspace = ::projectNameForWorkspace,
                                            onTap = { session ->
                                        openSession(session, model) { viewedId ->
                                            kanbanSessions = kanbanSessions.map {
                                                if (it.id == viewedId) it.copy(status = KanbanSessionStatus.Done) else it
                                            }
                                        }
                                    },
                                            onLongPress = { session, offset ->
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                contextAnchor = offset
                                                contextTarget = session
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        workspaceGroups.forEach { (groupHeader, groupItems) ->
                            item(key = "ws_group_$groupHeader") {
                                SessionSectionBlock(
                                    title = groupHeader,
                                    tint = colors.textSecondary,
                                    icon = Icons.Rounded.AccountTree,
                                    items = groupItems,
                                    state = state,
                                    showStatusBadge = true,
                                    projectNameForWorkspace = ::projectNameForWorkspace,
                                    onTap = { session ->
                                        openSession(session, model) { viewedId ->
                                            kanbanSessions = kanbanSessions.map {
                                                if (it.id == viewedId) it.copy(status = KanbanSessionStatus.Done) else it
                                            }
                                        }
                                    },
                                    onLongPress = { session, offset ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        contextAnchor = offset
                                        contextTarget = session
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Long-press Session Context Menu
    val activeMenuSession = contextTarget
    if (activeMenuSession != null && state.connection.allowsWrites) {
        val isRecentRead = activeMenuSession.isRecentlyActiveReadSession(state.manuallyCompletedSessionIds)
        val actions = buildList {
            KanbanSessionStatus.activeColumns.forEach { targetStatus ->
                val isCurrent = activeMenuSession.status == targetStatus &&
                    !(targetStatus == KanbanSessionStatus.Done && isRecentRead)
                if (!isCurrent) {
                    add(
                        ActionMenuItem("标为「${targetStatus.title}」") {
                            kanbanSessions = kanbanSessions.map {
                                if (it.id == activeMenuSession.id) {
                                    it.copy(
                                        status = targetStatus,
                                        isRunning = if (targetStatus == KanbanSessionStatus.InProgress) it.isRunning else false,
                                    )
                                } else {
                                    it
                                }
                            }
                            model.setManualSessionStatus(activeMenuSession.id, targetStatus)
                        },
                    )
                }
            }
            add(
                ActionMenuItem(if (activeMenuSession.isPinned) "取消置顶" else "置顶会话") {
                    val nextPinned = !activeMenuSession.isPinned
                    kanbanSessions = kanbanSessions.map {
                        if (it.id == activeMenuSession.id) {
                            it.copy(pinnedAt = if (nextPinned) Instant.now().toString() else null)
                        } else {
                            it
                        }
                    }
                    scope.launch {
                        runCatching { model.mutateKanbanSessionPinned(activeMenuSession.id, nextPinned) }
                            .onFailure { model.fail(it) }
                    }
                },
            )
            add(
                ActionMenuItem("重命名") {
                    renameDraft = activeMenuSession.name ?: activeMenuSession.resolvedTitle
                    renamingSession = activeMenuSession
                },
            )
            if (activeMenuSession.isForkSupported) {
                add(
                    ActionMenuItem("创建分支会话") {
                        scope.launch {
                            runCatching { model.mutateKanbanSessionFork(activeMenuSession.id) }
                                .onSuccess { forkedId ->
                                    if (!forkedId.isNullOrBlank()) {
                                        model.openConversation(forkedId)
                                    }
                                }
                                .onFailure { model.fail(it) }
                        }
                    },
                )
            }
            add(
                ActionMenuItem("归档会话") {
                    removedIds = removedIds + activeMenuSession.id
                    model.archiveConversation(activeMenuSession.id)
                },
            )
            add(
                ActionMenuItem("删除会话", danger = true) {
                    deletingSession = activeMenuSession
                },
            )
        }

        ActionMenu(
            items = actions,
            anchor = contextAnchor,
            onDismiss = { contextTarget = null },
        )
    }

    // Rename Dialog
    val activeRename = renamingSession
    if (activeRename != null) {
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine = true,
                    placeholder = { Text("会话名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = renameDraft.trim()
                        renamingSession = null
                        kanbanSessions = kanbanSessions.map {
                            if (it.id == activeRename.id) it.copy(name = trimmed.ifEmpty { null }) else it
                        }
                        scope.launch {
                            runCatching { model.mutateKanbanSessionRename(activeRename.id, trimmed.ifEmpty { null }) }
                                .onFailure { model.fail(it) }
                        }
                    },
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingSession = null }) {
                    Text("取消")
                }
            },
        )
    }

    // Delete Confirm Dialog
    val activeDelete = deletingSession
    if (activeDelete != null) {
        AlertDialog(
            onDismissRequest = { deletingSession = null },
            title = { Text("删除会话") },
            text = { Text("确定删除会话“${activeDelete.resolvedTitle}”吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingSession = null
                        removedIds = removedIds + activeDelete.id
                        scope.launch {
                            runCatching { model.mutateKanbanSessionDelete(activeDelete.id) }
                                .onFailure { model.fail(it) }
                        }
                    },
                ) {
                    Text("删除", color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingSession = null }) {
                    Text("取消")
                }
            },
        )
    }
}

private inline fun openSession(
    session: KanbanSessionSummary,
    model: CompanionModel,
    onMarkViewedLocal: (String) -> Unit = {},
) {
    if (session.completesWhenViewed) {
        onMarkViewedLocal(session.id)
        model.markSessionViewed(session.id)
    }
    model.openConversation(session.id)
}

@Composable
private fun EmptySessionsCard(isSearching: Boolean) {
    val colors = VibexTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.card)
            .border(0.6.dp, colors.stroke, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.ChatBubbleOutline,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = if (isSearching) "没有找到匹配的会话" else "暂无会话记录",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        Text(
            text = if (isSearching) "请尝试更换搜索关键词或切换项目筛选" else "点击底部胶囊栏中间的 + 发起新会话",
            fontSize = 13.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SessionSectionBlock(
    title: String,
    tint: Color,
    icon: ImageVector,
    items: List<KanbanSessionSummary>,
    state: UiState,
    showStatusBadge: Boolean,
    projectNameForWorkspace: (String) -> String?,
    onTap: (KanbanSessionSummary) -> Unit,
    onLongPress: (KanbanSessionSummary, Offset) -> Unit,
) {
    val colors = VibexTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = title,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${items.size}",
                style = TextStyle(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                    color = colors.textTertiary,
                ),
            )
            Spacer(modifier = Modifier.weight(1f))
        }

        SessionCardGroup(
            items = items,
            state = state,
            showStatusBadge = showStatusBadge,
            projectNameForWorkspace = projectNameForWorkspace,
            onTap = onTap,
            onLongPress = onLongPress,
        )
    }
}

@Composable
private fun SessionCardGroup(
    items: List<KanbanSessionSummary>,
    state: UiState,
    showStatusBadge: Boolean,
    projectNameForWorkspace: (String) -> String?,
    onTap: (KanbanSessionSummary) -> Unit,
    onLongPress: (KanbanSessionSummary, Offset) -> Unit,
) {
    val colors = VibexTheme.colors
    val cardShape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(colors.card)
            .border(0.6.dp, colors.stroke, cardShape),
    ) {
        items.forEachIndexed { idx, session ->
            SessionRowItem(
                session = session,
                state = state,
                showStatusBadge = showStatusBadge,
                projectLabel = projectNameForWorkspace(session.workspaceId),
                onTap = { onTap(session) },
                onLongPress = { offset -> onLongPress(session, offset) },
            )
            if (idx < items.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 56.dp),
                    thickness = 0.5.dp,
                    color = colors.hairline,
                )
            }
        }
    }
}

@Composable
private fun SessionRowItem(
    session: KanbanSessionSummary,
    state: UiState,
    showStatusBadge: Boolean,
    projectLabel: String?,
    onTap: () -> Unit,
    onLongPress: (Offset) -> Unit,
) {
    val colors = VibexTheme.colors
    val agentKey = session.effectiveAgentKey.ifBlank { "claude_code" }
    val agent = catalogAgent(state, agentKey)
    val branchLabel = session.workspaceDisplayLabel
    val showProjectLabel = !projectLabel.isNullOrBlank() && state.selectedProjectId == null
    val displayBranchLabel = remember(branchLabel, projectLabel, showProjectLabel) {
        if (!showProjectLabel || projectLabel.isNullOrBlank()) {
            branchLabel
        } else {
            val trimmed = branchLabel.trim()
            val prefixDot = "$projectLabel · "
            val prefixSlash = "$projectLabel/"
            when {
                trimmed.equals(projectLabel, ignoreCase = true) -> ""
                trimmed.startsWith(prefixDot, ignoreCase = true) -> trimmed.substring(prefixDot.length).trim()
                trimmed.startsWith(prefixSlash, ignoreCase = true) -> trimmed.substring(prefixSlash.length).trim()
                else -> trimmed
            }
        }
    }
    val relTime = remember(session.updatedAt) { relativeTime(session.updatedAt) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(longPressModifier(onClick = onTap, onLongClick = onLongPress))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(modifier = Modifier.size(32.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.textPrimary.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                AgentAvatar(
                    agentId = agentKey,
                    size = 20.dp,
                    iconSvg = agent?.iconSvg,
                    displayName = agent?.displayName.orEmpty(),
                )
            }

            val dotColor = when {
                session.isRunning || session.status == KanbanSessionStatus.InProgress -> StatusOrange
                session.status == KanbanSessionStatus.InReview -> StatusPurple
                else -> null
            }
            if (dotColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(colors.card)
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (session.isPinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = "已置顶",
                        tint = StatusOrange,
                        modifier = Modifier.size(11.dp),
                    )
                }
                Text(
                    text = session.resolvedTitle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showProjectLabel) {
                    Text(
                        text = projectLabel.orEmpty(),
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (displayBranchLabel.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccountTree,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(11.dp),
                        )
                        Text(
                            text = displayBranchLabel,
                            fontSize = 12.sp,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val modelLabel = session.model?.trim().orEmpty()
                if (modelLabel.isNotEmpty()) {
                    Text(
                        text = "· $modelLabel",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (relTime.isNotEmpty()) {
                Text(
                    text = relTime,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontFeatureSettings = "tnum",
                        color = colors.textTertiary,
                    ),
                )
            }
            if (showStatusBadge) {
                val (badgeLabel, badgeColor) = sessionStatusBadge(session, state.manuallyCompletedSessionIds)
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(badgeColor.copy(alpha = 0.13f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = badgeLabel,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeColor,
                    )
                }
            }
        }
    }
}

private fun sessionStatusBadge(
    session: KanbanSessionSummary,
    manuallyCompletedIds: Set<String>,
): Pair<String, Color> {
    if (session.isRunning || session.status == KanbanSessionStatus.InProgress) {
        return "进行中" to StatusOrange
    }
    return when (session.status) {
        KanbanSessionStatus.InReview -> "待检查" to StatusPurple
        KanbanSessionStatus.Todo -> "待开始" to StatusBlue
        KanbanSessionStatus.Done -> {
            if (session.isRecentlyActiveReadSession(manuallyCompletedIds)) {
                "近期对话" to StatusTeal
            } else {
                "已完成" to StatusGreen
            }
        }
        KanbanSessionStatus.Archived -> "已归档" to Color.Gray
        KanbanSessionStatus.InProgress -> "进行中" to StatusOrange
    }
}

fun relativeTime(raw: String): String {
    val epochMs = parseHostTimestampEpochMillis(raw) ?: return ""
    val minutes = ((System.currentTimeMillis() - epochMs).coerceAtLeast(0L)) / 60_000L
    return when {
        minutes < 1L -> "刚刚"
        minutes < 60L -> "${minutes}m"
        minutes < 60L * 24L -> "${minutes / 60L}h"
        else -> "${minutes / (60L * 24L)}d"
    }
}
