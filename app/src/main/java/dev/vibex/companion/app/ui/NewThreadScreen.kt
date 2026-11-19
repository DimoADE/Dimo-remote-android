package dev.vibex.companion.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.CatalogAgent
import dev.vibex.companion.SessionConfigChoice
import dev.vibex.companion.SessionConfigOption
import dev.vibex.companion.SessionMode
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.Route
import dev.vibex.companion.app.UiState
import dev.vibex.companion.app.ui.theme.PlexMono
import dev.vibex.companion.app.ui.theme.VibexTheme
import dev.vibex.companion.fallbackModelOptionForAgent

private data class NewThreadStarterChip(val id: String, val label: String, val prompt: String)

private val newThreadSuggestions = listOf(
    NewThreadStarterChip("arch", "梳理项目架构", "梳理当前项目的整体架构、目录结构与核心模块职责"),
    NewThreadStarterChip("bug", "排查近期改动", "检查当前工作区最近的代码改动，排查潜在问题并给出修复方案"),
    NewThreadStarterChip("test", "补充单元测试", "为当前工作区最近修改的核心逻辑补充单元测试并运行验证"),
)

/**
 * 1:1 port of iOS Level-2 `新建会话` (`newThreadScreen` in `ConversationsView.swift`).
 * Triggered by the centered `+` button in the Level-1 floating capsule dock, with top-right
 * `WorkspaceDualToolsCapsule`, lightweight 2-row project/host/branch breadcrumbs above the
 * unified `PromptBar`, and zero bottom dock interference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewThreadScreen(
    state: UiState,
    model: CompanionModel,
) {
    val colors = VibexTheme.colors
    val focusManager = LocalFocusManager.current

    BackHandler {
        model.go(Route.Conversations)
    }

    val projects = state.catalog?.projects.orEmpty()
    val workspaces = state.catalog?.workspaces.orEmpty()
    val usableAgents = remember(state.catalog?.agents) {
        val all = state.catalog?.agents.orEmpty()
        all.filter { it.usable }.ifEmpty { all }
    }

    var selectedProjectId by remember(state.selectedProjectId) {
        mutableStateOf(state.selectedProjectId)
    }
    var selectedWorkspaceId by remember { mutableStateOf<String?>(null) }
    var selectedAgentId by remember { mutableStateOf<String?>(null) }
    var selectedModeId by remember { mutableStateOf("auto") }
    var selectedEffortValue by remember { mutableStateOf("medium") }
    var selectedModelByAgent by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var isDraftEmpty by remember { mutableStateOf(true) }
    var seedText by remember { mutableStateOf("") }
    var seedVersion by remember { mutableIntStateOf(0) }

    var diffCount by remember { mutableIntStateOf(0) }
    var diffAdditions by remember { mutableLongStateOf(0L) }
    var diffDeletions by remember { mutableLongStateOf(0L) }

    var showProjectSheet by remember { mutableStateOf(false) }
    var workspaceMenuExpanded by remember { mutableStateOf(false) }
    var showNewWorktreeDialog by remember { mutableStateOf(false) }
    var newWorktreeName by remember { mutableStateOf("") }
    var newWorktreeBranch by remember { mutableStateOf("") }
    var showDiffSheet by remember { mutableStateOf(false) }
    var showFileBrowserSheet by remember { mutableStateOf(false) }

    val currentProject = remember(projects, selectedProjectId, state.selectedProjectId) {
        val targetId = selectedProjectId ?: state.selectedProjectId
        projects.find { targetId != null && it.id.equals(targetId, ignoreCase = true) }
            ?: projects.firstOrNull { !it.path.endsWith("/.vibex") && !it.path.endsWith("/.dimo") }
            ?: projects.firstOrNull()
    }

    val projectWorkspaces = remember(workspaces, currentProject) {
        if (currentProject == null) emptyList()
        else workspaces.filter { it.projectId.equals(currentProject.id, ignoreCase = true) }
    }

    val currentWorkspace = remember(projectWorkspaces, workspaces, selectedWorkspaceId) {
        projectWorkspaces.find { it.id == selectedWorkspaceId }
            ?: projectWorkspaces.firstOrNull()
            ?: workspaces.firstOrNull()
    }

    val currentAgent = remember(usableAgents, selectedAgentId, state.conversations) {
        usableAgents.find { it.id == selectedAgentId }
            ?: usableAgents.find { it.id == state.conversations.firstOrNull()?.agentId }
            ?: usableAgents.firstOrNull()
    }
    val currentAgentId = currentAgent?.id ?: "claude_code"

    LaunchedEffect(currentWorkspace?.id) {
        val wsId = currentWorkspace?.id
        if (wsId.isNullOrBlank()) {
            diffCount = 0
            diffAdditions = 0L
            diffDeletions = 0L
        } else {
            model.fetchWorkspaceDiff(wsId) { result ->
                val diffs = result.getOrNull().orEmpty()
                diffCount = diffs.size
                diffAdditions = diffs.sumOf { it.additions.toLong() }
                diffDeletions = diffs.sumOf { it.deletions.toLong() }
            }
        }
    }

    val landingModes = remember(currentAgent) {
        currentAgent?.sessionModes?.takeIf { it.isNotEmpty() } ?: listOf(
            SessionMode("manual", "手动审批"),
            SessionMode("accept_edits", "自动接受编辑"),
            SessionMode("plan", "仅规划"),
            SessionMode("auto", "自动"),
            SessionMode("bypass", "完全访问"),
        )
    }

    val baseModelOption = remember(currentAgent, currentAgentId) {
        currentAgent?.modelOptions?.firstOrNull() ?: fallbackModelOptionForAgent(currentAgentId)
    }

    val baseEffortOption = remember(currentAgent) {
        currentAgent?.otherOptions?.firstOrNull {
            val k = it.key.lowercase()
            val c = it.category.lowercase()
            c == "thought_level" || k.contains("effort") || k.contains("reasoning") || k.contains("thinking")
        } ?: SessionConfigOption(
            key = "reasoning_effort",
            label = "思考强度",
            category = "thought_level",
            value = "medium",
            choices = listOf(
                SessionConfigChoice("low", "低"),
                SessionConfigChoice("medium", "中"),
                SessionConfigChoice("high", "高"),
            ),
        )
    }

    val activeModelValue = selectedModelByAgent[currentAgentId]
        ?: baseModelOption.value.ifBlank { baseModelOption.choices.firstOrNull()?.value ?: "default" }

    val landingConfigOptions = remember(
        baseModelOption,
        baseEffortOption,
        activeModelValue,
        selectedEffortValue,
    ) {
        val nextModelChoices = if (baseModelOption.choices.any { it.value == activeModelValue }) {
            baseModelOption.choices
        } else {
            listOf(CatalogAgent.makeCustomChoice(activeModelValue)) + baseModelOption.choices
        }
        listOf(
            baseModelOption.copy(value = activeModelValue, choices = nextModelChoices),
            baseEffortOption.copy(value = selectedEffortValue),
        )
    }

    val resolvedWsId = currentWorkspace?.id?.ifBlank { null }
        ?: state.conversations.firstOrNull { it.workspaceId.isNotBlank() }?.workspaceId

    val activeHostName = remember(state.profiles, state.selectedHostId) {
        state.profiles.find { it.hostId == state.selectedHostId }?.name?.ifBlank { null } ?: "Dimo 桌面端"
    }

    val branchLabel = remember(currentWorkspace) {
        currentWorkspace?.branch?.ifBlank { null }
            ?: currentWorkspace?.name?.ifBlank { null }
            ?: "main"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Level-2 Header Bar: `<` | `新建会话` | `[ ⑂ | 📁 ]`
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FloatingCircleBackButton(
                onClick = { model.go(Route.Conversations) },
            )

            Text(
                text = "新建会话",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )

            Spacer(modifier = Modifier.weight(1f))

            WorkspaceDualToolsCapsule(
                diffCount = diffCount,
                additionCount = diffAdditions,
                deletionCount = diffDeletions,
                enabled = currentWorkspace != null,
                onOpenDiff = { showDiffSheet = true },
                onOpenFiles = { showFileBrowserSheet = true },
            )
        }

        // Calm Center Canvas — tap anywhere to dismiss keyboard
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    focusManager.clearFocus()
                }
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(14.dp),
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        spotColor = Color.Black.copy(alpha = 0.12f),
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.card),
                contentAlignment = Alignment.Center,
            ) {
                BrandMark(40.dp)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "有什么可以帮你？",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
        }

        // Quiet Starter Suggestion Chips when composer is empty
        if (isDraftEmpty) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                newThreadSuggestions.forEach { chip ->
                    Text(
                        text = chip.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.card)
                            .border(0.6.dp, colors.stroke, CircleShape)
                            .clickable {
                                seedText = chip.prompt
                                seedVersion += 1
                            }
                            .padding(horizontal = 13.dp, vertical = 7.5.dp),
                    )
                }
            }
        }

        // Lightweight 2-Row Context Breadcrumbs above Composer (1:1 with iOS `newThreadContextBreadcrumbs`)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(top = 6.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Row 1: `📁 <project> ›` + `💻 <host> ›`
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            focusManager.clearFocus()
                            showProjectSheet = true
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = currentProject?.name ?: "选择项目",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            focusManager.clearFocus()
                            model.openDesktopConnect()
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Computer,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = activeHostName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // Row 2: `📁 当前检出` + `⑂ <branch / workspace> ›`
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "当前检出",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                    )
                }

                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = currentProject != null) {
                                focusManager.clearFocus()
                                workspaceMenuExpanded = true
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AccountTree,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = branchLabel,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }

                    VibexDropdownMenu(
                        expanded = workspaceMenuExpanded,
                        onDismissRequest = { workspaceMenuExpanded = false },
                    ) {
                        projectWorkspaces.forEach { ws ->
                            val label = ws.branch.ifBlank { ws.name.ifBlank { "默认工作区" } }
                            val isSelected = ws.id == currentWorkspace?.id
                            VibexDropdownMenuItem(
                                text = label,
                                leadingIcon = Icons.Rounded.AccountTree,
                                selected = isSelected,
                                onClick = {
                                    workspaceMenuExpanded = false
                                    selectedWorkspaceId = ws.id
                                },
                            )
                        }
                        if (currentProject != null && state.connection.allowsWrites) {
                            if (projectWorkspaces.isNotEmpty()) {
                                VibexDropdownDivider()
                            }
                            VibexDropdownMenuItem(
                                text = "新建工作树…",
                                leadingIcon = Icons.Rounded.Add,
                                onClick = {
                                    workspaceMenuExpanded = false
                                    newWorktreeName = ""
                                    newWorktreeBranch = ""
                                    showNewWorktreeDialog = true
                                },
                            )
                        }
                    }
                }
            }
        }

        // Unified Floating Capsule Composer
        PromptBar(
            conversationKey = "new_thread",
            externalDraftSeed = seedText,
            externalSeedVersion = seedVersion,
            onDraftEmptyChanged = { isDraftEmpty = it },
            agentId = currentAgentId,
            agent = currentAgent,
            agents = usableAgents,
            allowCrossAgentSwitch = true,
            modes = landingModes,
            currentModeId = selectedModeId,
            configOptions = landingConfigOptions,
            modelPrefs = model.modelPrefs,
            isStreaming = false,
            enabled = state.connection.allowsWrites && !resolvedWsId.isNullOrBlank(),
            onSubmit = { text ->
                val wsId = resolvedWsId ?: return@PromptBar
                model.createConversation(
                    workspaceId = wsId,
                    agentId = currentAgentId,
                    title = text.take(36),
                    prompt = text,
                    modeId = selectedModeId,
                    config = mapOf(
                        baseModelOption.key to activeModelValue,
                        baseEffortOption.key to selectedEffortValue,
                    ),
                )
            },
            onStop = {},
            onSelectAgentAndModel = { nextAgentId, _, modelValue ->
                selectedAgentId = nextAgentId
                if (modelValue.isNotBlank()) {
                    selectedModelByAgent = selectedModelByAgent + (nextAgentId to modelValue)
                }
            },
            onSelectMode = { modeId ->
                selectedModeId = modeId
            },
            onSelectOption = { key, value ->
                if (key == baseModelOption.key || key.lowercase().contains("model")) {
                    selectedModelByAgent = selectedModelByAgent + (currentAgentId to value)
                } else {
                    selectedEffortValue = value
                }
            },
            onOpenFileBrowser = if (currentWorkspace != null) {
                { showFileBrowserSheet = true }
            } else {
                null
            },
            onRefreshCatalog = { model.loadCatalogSilent() },
        )
    }

    // Project Picker Bottom Sheet
    if (showProjectSheet) {
        ModalBottomSheet(
            onDismissRequest = { showProjectSheet = false },
            containerColor = colors.bgElevated,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "选择项目",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                projects.forEach { proj ->
                    val selected = proj.id.equals(currentProject?.id, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) colors.accent.copy(alpha = 0.12f) else colors.bg)
                            .clickable {
                                showProjectSheet = false
                                selectedProjectId = proj.id
                                selectedWorkspaceId = null
                                model.selectProjectInPlace(proj.id)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = if (selected) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = proj.name,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.textPrimary,
                            )
                            if (proj.path.isNotBlank()) {
                                Text(
                                    text = proj.path,
                                    fontSize = 11.sp,
                                    fontFamily = PlexMono,
                                    color = colors.textTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // New Worktree Dialog
    val activeProjectForWorktree = currentProject
    if (showNewWorktreeDialog && activeProjectForWorktree != null) {
        AlertDialog(
            onDismissRequest = { showNewWorktreeDialog = false },
            title = { Text("新建工作树") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newWorktreeName,
                        onValueChange = { newWorktreeName = it },
                        singleLine = true,
                        label = { Text("工作区名称") },
                        placeholder = { Text("例如 feat-auth") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newWorktreeBranch,
                        onValueChange = { newWorktreeBranch = it },
                        singleLine = true,
                        label = { Text("分支名称（可选）") },
                        placeholder = { Text("例如 feat/auth") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newWorktreeName.trim().isNotEmpty(),
                    onClick = {
                        val name = newWorktreeName.trim()
                        val branch = newWorktreeBranch.trim().ifEmpty { null }
                        showNewWorktreeDialog = false
                        model.createWorkspace(activeProjectForWorktree.id, name, branch) { createdId ->
                            selectedWorkspaceId = createdId
                        }
                    },
                ) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewWorktreeDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (showDiffSheet && currentWorkspace != null) {
        DiffListSheet(
            workspaceId = currentWorkspace.id,
            conversationId = "",
            model = model,
            onDismiss = { showDiffSheet = false },
        )
    }

    if (showFileBrowserSheet && currentWorkspace != null) {
        FileBrowserSheet(
            workspaceId = currentWorkspace.id,
            title = currentProject?.name ?: currentWorkspace.name.ifBlank { "项目文件" },
            model = model,
            onDismiss = { showFileBrowserSheet = false },
        )
    }
}
