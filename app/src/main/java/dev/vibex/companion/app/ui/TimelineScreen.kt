package dev.vibex.companion.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.CatalogAgent
import dev.vibex.companion.FileChangeItem
import dev.vibex.companion.QueuedInput
import dev.vibex.companion.TimelineItem
import dev.vibex.companion.TimelineSegment
import dev.vibex.companion.agentDisplayName
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.Route
import dev.vibex.companion.app.UiState
import dev.vibex.companion.app.ui.theme.VibexTheme
import dev.vibex.companion.app.ui.timeline.SessionInfoSheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val RunningOrange = Color(0xFFFF9500)

/**
 * 1:1 port of iOS `ConversationDetailView` (`ConversationDetailView.swift`).
 * Uses `FloatingCircleBackButton` + left-aligned title/model header + `WorkspaceDualToolsCapsule`,
 * instant optimistic `isThinkingOrRunning` feedback, auto-mark-viewed when a turn finishes while
 * watching, and the unified single-capsule `PromptBar`.
 */
@Composable
fun TimelinePane(
    state: UiState,
    model: CompanionModel,
    conversationId: String,
    onBack: (() -> Unit)? = null,
) {
    val colors = VibexTheme.colors
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val handleBack = remember(onBack, model) {
        onBack ?: { model.go(Route.Conversations) }
    }
    BackHandler(onBack = handleBack)

    val view = state.timeline?.takeIf { it.conversationId == conversationId }
    val summary = state.conversations.find { it.id == conversationId }
    val agentId = view?.agentId?.takeIf { it.isNotBlank() } ?: summary?.agentId.orEmpty()
    val agent = catalogAgent(state, agentId)
    val workspaceId = remember(view?.workspaceId, summary?.workspaceId, state.selectedProjectId, state.catalog) {
        view?.workspaceId?.takeIf { it.isNotBlank() && !it.startsWith("/") }
            ?: summary?.workspaceId?.takeIf { it.isNotBlank() && !it.startsWith("/") }
            ?: state.catalog?.workspaces.orEmpty()
                .firstOrNull { state.selectedProjectId == null || it.projectId == state.selectedProjectId }?.id
            ?: state.catalog?.workspaces?.firstOrNull()?.id.orEmpty()
    }
    LaunchedEffect(conversationId, workspaceId) {
        if (workspaceId.isNotBlank() && view?.workspaceId != workspaceId) {
            model.preferWorkspace(conversationId, workspaceId)
        }
    }

    val modes = view?.sessionModes?.takeIf { it.isNotEmpty() } ?: agent?.sessionModes.orEmpty()
    val currentModeId = view?.currentModeId?.takeIf { it.isNotBlank() } ?: agent?.currentModeId.orEmpty()
    val configOptions = view?.sessionConfig?.takeIf { it.isNotEmpty() } ?: agent?.sessionConfig.orEmpty()

    val currentModelLabel = remember(configOptions, agent, agentId) {
        val modelOpt = findSettingsModelOption(configOptions)
        val selected = modelOpt?.choices?.firstOrNull { it.value == modelOpt.value }?.label ?: modelOpt?.value
        selected?.takeIf { it.isNotBlank() }
            ?: agent?.displayName?.takeIf { it.isNotBlank() }
            ?: agentDisplayName(agentId, "")
    }

    val items = view?.items.orEmpty()
    val isTurnRunning = view?.isThinkingOrRunning == true

    var workspaceDiffCount by remember(conversationId, workspaceId) { mutableIntStateOf(0) }
    var workspaceAdditions by remember(conversationId, workspaceId) { mutableLongStateOf(0L) }
    var workspaceDeletions by remember(conversationId, workspaceId) { mutableLongStateOf(0L) }
    var wasTurnRunning by remember(conversationId) { mutableStateOf(false) }

    fun refreshDiffSummary() {
        if (workspaceId.isBlank()) return
        model.fetchWorkspaceDiff(workspaceId, conversationId) { result ->
            result.getOrNull()?.let { diffs ->
                workspaceDiffCount = diffs.size
                workspaceAdditions = diffs.sumOf { it.additions.toLong() }
                workspaceDeletions = diffs.sumOf { it.deletions.toLong() }
            }
        }
    }

    LaunchedEffect(conversationId, workspaceId) {
        refreshDiffSummary()
    }

    LaunchedEffect(conversationId, isTurnRunning) {
        if (wasTurnRunning && !isTurnRunning) {
            if (view?.permissionPrompt == null && view?.pendingQuestion == null) {
                model.markSessionViewed(conversationId)
            }
            refreshDiffSummary()
        }
        wasTurnRunning = isTurnRunning
    }

    val totalChangeCount = remember(workspaceDiffCount, view?.fileChanges, items) {
        val turnChanges = items.flatMap { it.changes }.map { it.path }.toSet().size
        maxOf(workspaceDiffCount, view?.fileChanges?.size ?: 0, turnChanges)
    }

    var respondingPermission by remember(conversationId) { mutableStateOf(false) }
    var respondingQuestion by remember(conversationId) { mutableStateOf(false) }
    var openSettingsTrigger by remember(conversationId) { mutableIntStateOf(0) }

    var showFiles by remember { mutableStateOf(false) }
    var showDiff by remember { mutableStateOf(false) }
    var diffTarget by remember { mutableStateOf<FileChangeItem?>(null) }
    var showSessionSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showHandoff by remember { mutableStateOf(false) }
    var showSideChat by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    var pinnedToBottom by remember(conversationId) { mutableStateOf(true) }
    var unreadUpdates by remember(conversationId) { mutableIntStateOf(0) }
    var prevCount by remember(conversationId) { mutableIntStateOf(0) }

    val isAtBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            if (total == 0) true
            else {
                val lastVisible = layout.visibleItemsInfo.lastOrNull()
                lastVisible != null && lastVisible.index >= total - 2
            }
        }
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            pinnedToBottom = true
            unreadUpdates = 0
        } else if (listState.isScrollInProgress) {
            pinnedToBottom = false
        }
    }

    val tailSignature = remember(items, isTurnRunning) {
        val last = items.lastOrNull()
        "${items.size}:${last?.id}:${last?.text?.length}:${last?.thinking?.length}:${last?.tools?.size}:$isTurnRunning"
    }

    LaunchedEffect(tailSignature) {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return@LaunchedEffect
        if (pinnedToBottom) {
            listState.scrollToItem((total - 1).coerceAtLeast(0))
        } else if (items.size > prevCount) {
            unreadUpdates += (items.size - prevCount).coerceAtLeast(1)
        }
        prevCount = items.size
    }

    val conversationTitle = view?.title?.takeIf { it.isNotBlank() }
        ?: summary?.title?.takeIf { it.isNotBlank() }
        ?: "未命名会话"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Level-2 Header Bar (1:1 iOS `levelTwoHeaderBar`)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FloatingCircleBackButton(onClick = handleBack)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showSessionSheet = true },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isTurnRunning) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(RunningOrange),
                        )
                    }
                    Text(
                        text = conversationTitle,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { openSettingsTrigger += 1 },
                    horizontalArrangement = Arrangement.spacedBy(4.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgentAvatar(
                        agentId = agentId,
                        size = 11.5.dp,
                        iconSvg = agent?.iconSvg,
                        displayName = agent?.displayName.orEmpty(),
                    )
                    Text(
                        text = currentModelLabel,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            WorkspaceDualToolsCapsule(
                diffCount = totalChangeCount,
                additionCount = workspaceAdditions,
                deletionCount = workspaceDeletions,
                enabled = workspaceId.isNotBlank(),
                onOpenDiff = { showDiff = true },
                onOpenFiles = { showFiles = true },
            )

            Box {
                Text(
                    text = "⋯",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { showMoreMenu = true }
                        .padding(horizontal = 8.dp),
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false },
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("切换引擎…") },
                        enabled = state.connection.allowsWrites && !isTurnRunning,
                        onClick = {
                            showMoreMenu = false
                            model.openHandoff(conversationId)
                            showHandoff = true
                        },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("旁路对话") },
                        onClick = {
                            showMoreMenu = false
                            showSideChat = true
                        },
                    )
                }
            }
        }

        // Session Notices
        view?.notices.orEmpty().forEach { item ->
            SessionNoticeCard(
                notice = item,
                onDismiss = { model.dismissSessionNotice(conversationId, item.id) },
                onAction = { if (item.action == "retry") model.retryInterrupted(conversationId) },
            )
        }

        view?.rateLimit?.let { limit ->
            RateLimitRecoveryBanner(
                banner = limit,
                agentId = agentId,
                writable = state.connection.allowsWrites,
                onAutoResume = { model.setRateLimitAutoResume(conversationId, true) },
                onCancelWait = { model.cancelRateLimitWait(conversationId) },
            )
        }

        // Main Timeline + Floating Scroll-to-Bottom Button
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    focusManager.clearFocus()
                },
        ) {
            if (view == null) {
                TimelineSkeletonView()
            } else if (items.isEmpty() && !isTurnRunning) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有消息",
                        fontSize = 14.sp,
                        color = colors.textSecondary,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(
                        count = items.size,
                        key = { idx -> items[idx].id },
                    ) { idx ->
                        val item = items[idx]
                        MessageRow(
                            item = item,
                            agentId = agentId,
                            agent = agent,
                            openDiff = { change -> diffTarget = change },
                        )
                    }

                    if (isTurnRunning && items.lastOrNull()?.role != TimelineItem.Role.Assistant) {
                        item(key = "loading_indicator") {
                            LoadingState(label = "正在思考")
                        }
                    }

                    item(key = "bottom_anchor") {
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }
            }

            if (!pinnedToBottom && items.isNotEmpty()) {
                ScrollToBottomButton(
                    unreadUpdates = unreadUpdates,
                    isTurnRunning = isTurnRunning,
                    onClick = {
                        pinnedToBottom = true
                        unreadUpdates = 0
                        scope.launch {
                            val total = listState.layoutInfo.totalItemsCount
                            if (total > 0) {
                                listState.animateScrollToItem(total - 1)
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            }
        }

        // Queued inputs strip
        if (view != null && view.queued.isNotEmpty()) {
            QueuedStrip(
                items = view.queued,
                enabled = state.connection.allowsWrites,
                onCancel = { model.cancelQueuedInput(conversationId, it.id, it.revision) },
            )
        }

        // TaskDock (`TaskRows.kt`)
        if (view != null && view.planTasks.isNotEmpty()) {
            TaskDock(
                tasks = view.planTasks,
                running = isTurnRunning,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
            )
        }

        // ApprovalCard (`ApprovalCard.kt`)
        val pendingQuestion = view?.pendingQuestion
        if (pendingQuestion != null) {
            ApprovalCard(
                request = pendingQuestion,
                busy = respondingQuestion,
                respond = { content ->
                    respondingQuestion = true
                    model.respondQuestionContent(
                        conversationId = conversationId,
                        questionId = pendingQuestion.id,
                        content = content,
                        onDone = { respondingQuestion = false },
                    )
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
            )
        }

        // PermissionBanner (`ApprovalCard.kt`)
        val permissionPrompt = view?.permissionPrompt
        if (permissionPrompt != null) {
            PermissionBanner(
                prompt = permissionPrompt,
                busy = respondingPermission,
                respond = { option, allowScope ->
                    respondingPermission = true
                    val persist = allowScope?.persistScope != null
                    model.respondPermission(
                        conversationId = conversationId,
                        permissionId = permissionPrompt.id,
                        optionId = option?.id,
                        persist = persist,
                        onDone = { respondingPermission = false },
                    )
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
            )
        }

        if (isTurnRunning) {
            SteeringHint(canSteer = view?.canSteer == true, known = view?.steeringKnown == true)
        }

        // Unified Single-Capsule PromptBar (`PromptBar.kt`)
        PromptBar(
            conversationKey = conversationId,
            agentId = agentId,
            agent = agent,
            agents = state.catalog?.agents.orEmpty(),
            allowCrossAgentSwitch = false,
            modes = modes,
            currentModeId = currentModeId,
            configOptions = configOptions,
            modelPrefs = model.modelPrefs,
            isStreaming = isTurnRunning,
            hasAlert = view?.permissionPrompt != null,
            enabled = state.connection.allowsWrites,
            openSettingsTrigger = openSettingsTrigger,
            onSubmit = { text ->
                pinnedToBottom = true
                unreadUpdates = 0
                if (isTurnRunning && view?.canSteer == true) {
                    model.steer(conversationId, text)
                } else {
                    model.send(conversationId, text)
                }
            },
            onStop = { model.cancel(conversationId) },
            onSelectAgentAndModel = { _, key, value ->
                model.setSessionConfig(conversationId, key, value)
            },
            onSelectMode = { modeId -> model.setSessionMode(conversationId, modeId) },
            onSelectOption = { key, value -> model.setSessionConfig(conversationId, key, value) },
            onOpenFileBrowser = if (workspaceId.isNotBlank()) {
                { showFiles = true }
            } else {
                null
            },
            onCompact = { model.compact(conversationId) },
            onRefreshCatalog = { model.loadCatalogSilent() },
        )
    }

    // Tool & Info Sheets
    if (showFiles && workspaceId.isNotBlank()) {
        FileBrowserSheet(
            workspaceId = workspaceId,
            title = conversationTitle,
            model = model,
            onDismiss = { showFiles = false },
        )
    }

    if (showDiff && workspaceId.isNotBlank()) {
        DiffListSheet(
            workspaceId = workspaceId,
            conversationId = conversationId,
            model = model,
            onDismiss = { showDiff = false },
        )
    }

    val activeDiffTarget = diffTarget
    if (activeDiffTarget != null) {
        FileDiffSheet(
            change = activeDiffTarget,
            workspaceId = workspaceId,
            conversationId = conversationId,
            model = model,
            onDismiss = { diffTarget = null },
        )
    }

    if (showHandoff) {
        dev.vibex.companion.app.ui.timeline.EngineHandoffSheet(
            state = state,
            currentAgentId = agentId,
            model = model,
            onDismiss = { showHandoff = false },
        )
    }

    if (showSideChat) {
        dev.vibex.companion.app.ui.timeline.SideChatSheet(
            conversationId = conversationId,
            items = items,
            model = model,
            onDismiss = { showSideChat = false },
        )
    }

    if (showSessionSheet) {
        SessionInfoSheet(
            state = state,
            conversationId = conversationId,
            model = model,
            onDismiss = { showSessionSheet = false },
        )
    }
}

/**
 * 1:1 port of iOS `MessageRow` (`ConversationDetailView.swift`) & Desktop `MessageTurnView.tsx`.
 */
@Composable
fun MessageRow(
    item: TimelineItem,
    agentId: String,
    agent: CatalogAgent? = null,
    openDiff: ((FileChangeItem) -> Unit)? = null,
) {
    val colors = VibexTheme.colors

    when (item.role) {
        TimelineItem.Role.Divider -> HandoffDivider(item.text)
        TimelineItem.Role.User -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Spacer(modifier = Modifier.width(56.dp))
                val bubbleShape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier
                        .clip(bubbleShape)
                        .background(colors.card)
                        .border(0.5.dp, colors.textPrimary.copy(alpha = 0.06f), bubbleShape)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = item.text,
                            fontSize = 14.5.sp,
                            lineHeight = 20.5.sp,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        }

        TimelineItem.Role.Assistant -> {
            val segments = remember(item) { orderedSegments(item) }
            val textSegments = remember(segments) {
                segments.filterIsInstance<TimelineSegment.Text>().filter { it.text.isNotBlank() }
            }
            val processSegments = remember(segments) {
                segments.filter { it !is TimelineSegment.Text }
            }
            val hasProcessSteps = processSegments.isNotEmpty() || item.changes.isNotEmpty()
            var processExpanded by remember(item.id) { mutableStateOf(false) }
            val showExpandedProcess = processExpanded || (item.streaming && textSegments.isEmpty() && item.text.isBlank())

            val workedForLabel = remember(item.streaming, textSegments.isEmpty(), item.thinkingDurationMs) {
                if (item.streaming && textSegments.isEmpty() && item.text.isBlank()) {
                    "Working…"
                } else {
                    val ms = (item.thinkingDurationMs ?: 60_000).toDouble()
                    val totalMinutes = kotlin.math.max(1L, kotlin.math.round(ms / 60_000.0).toLong())
                    val hours = totalMinutes / 60
                    val minutes = totalMinutes % 60
                    "Worked for ${hours}h ${minutes}m"
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hasProcessSteps) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { processExpanded = !processExpanded }
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier
                                .size(14.dp)
                                .rotate(if (showExpandedProcess) 90f else 0f),
                        )
                        Text(
                            text = workedForLabel,
                            style = TextStyle(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                                color = colors.textSecondary,
                            ),
                        )
                        if (item.streaming) {
                            Box(
                                modifier = Modifier
                                    .size(5.5.dp)
                                    .clip(CircleShape)
                                    .background(RunningOrange),
                            )
                        }
                    }

                    if (showExpandedProcess) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            processSegments.forEachIndexed { idx, segment ->
                                val isLastProcess = idx == processSegments.lastIndex && textSegments.isEmpty()
                                when (segment) {
                                    is TimelineSegment.Thinking -> {
                                        ThinkingView(
                                            text = segment.text,
                                            durationMs = segment.durationMs,
                                            working = item.streaming && isLastProcess,
                                        )
                                    }
                                    is TimelineSegment.Tools -> {
                                        ToolChipsView(
                                            tools = segment.steps,
                                            changes = emptyList(),
                                            working = item.streaming && isLastProcess,
                                            openDiff = openDiff,
                                        )
                                    }
                                    is TimelineSegment.Text -> Unit
                                }
                            }

                            if (item.changes.isNotEmpty()) {
                                ToolChipsView(
                                    tools = emptyList(),
                                    changes = item.changes,
                                    working = false,
                                    openDiff = openDiff,
                                )
                            }
                        }
                    }
                }

                textSegments.forEachIndexed { idx, segment ->
                    val isLastText = idx == textSegments.lastIndex
                    StreamingMessageText(
                        text = segment.text,
                        isStreaming = item.streaming && isLastText,
                        showCopyButton = !item.streaming && isLastText,
                    )
                }

                if (item.streaming && segments.isEmpty()) {
                    LoadingState(label = "正在思考")
                }
            }
        }
    }
}

private fun orderedSegments(item: TimelineItem): List<TimelineSegment> {
    if (item.segments.isNotEmpty()) return item.segments
    val fallback = mutableListOf<TimelineSegment>()
    val thinkingText = item.thinking
    if (!thinkingText.isNullOrBlank()) {
        fallback += TimelineSegment.Thinking(
            id = "${item.id}:thinking",
            text = thinkingText,
            durationMs = item.thinkingDurationMs,
        )
    }
    if (item.tools.isNotEmpty()) {
        fallback += TimelineSegment.Tools(
            id = "${item.id}:tools",
            steps = item.tools,
        )
    }
    if (item.text.isNotBlank()) {
        fallback += TimelineSegment.Text(
            id = "${item.id}:text",
            text = item.text,
        )
    }
    return fallback
}

/**
 * 1:1 port of iOS `StreamingMessageText` (`StreamingText.swift`).
 */
@Composable
fun StreamingMessageText(
    text: String,
    isStreaming: Boolean,
    showCopyButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = VibexTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1500L)
            copied = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MarkdownContent(raw = text, fillWidth = true)

        if (showCopyButton && !isStreaming && text.isNotBlank()) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(text))
                        copied = true
                    }
                    .padding(vertical = 3.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = "复制",
                    tint = if (copied) colors.pass else colors.textSecondary,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = if (copied) "已复制" else "复制",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (copied) colors.pass else colors.textSecondary,
                )
            }
        }
    }
}

/**
 * 1:1 port of iOS `scrollToBottomButton` (`ConversationDetailView.swift`).
 */
@Composable
private fun ScrollToBottomButton(
    unreadUpdates: Int,
    isTurnRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VibexTheme.colors
    val showExtendedLabel = unreadUpdates > 0 || isTurnRunning
    val label = when {
        unreadUpdates > 0 -> "$unreadUpdates 条新动态"
        isTurnRunning -> "跟随最新回答"
        else -> null
    }

    Row(
        modifier = modifier
            .height(34.dp)
            .shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.16f), spotColor = Color.Black.copy(alpha = 0.16f))
            .clip(CircleShape)
            .background(colors.card)
            .border(0.8.dp, colors.textPrimary.copy(alpha = 0.14f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = if (showExtendedLabel) 13.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (unreadUpdates > 0 || isTurnRunning) {
            Box(
                modifier = Modifier
                    .size(6.5.dp)
                    .clip(CircleShape)
                    .background(RunningOrange),
            )
        }
        if (label != null) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                    color = colors.textPrimary,
                ),
            )
        }
        Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "回到底部",
            tint = colors.textPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun QueuedStrip(
    items: List<QueuedInput>,
    enabled: Boolean,
    onCancel: (QueuedInput) -> Unit,
) {
    val colors = VibexTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.card)
            .border(0.6.dp, colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "排队中 (${items.size})",
            style = TextStyle(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                color = colors.textSecondary,
            ),
        )
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.text.ifBlank { "排队消息" },
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "取消排队",
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable(enabled = enabled) { onCancel(item) },
                )
            }
        }
    }
}
