package dev.vibex.companion.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.CatalogAgent
import dev.vibex.companion.SessionConfigChoice
import dev.vibex.companion.SessionConfigOption
import dev.vibex.companion.SessionMode
import dev.vibex.companion.agentDisplayName
import dev.vibex.companion.app.data.ModelPreferencesStore
import dev.vibex.companion.app.ui.theme.VibexTheme
import dev.vibex.companion.fallbackModelOptionForAgent

private sealed interface ModelCatalogFilter {
    data object All : ModelCatalogFilter
    data object Favorites : ModelCatalogFilter
    data class Provider(val agentId: String, val title: String) : ModelCatalogFilter
}

private enum class SettingsSubpage { Main, Effort, Runtime }

private data class ProviderModelGroup(
    val agentId: String,
    val title: String,
    val iconSvg: String,
    val modelKey: String,
    val choices: List<SessionConfigChoice>,
    val activeValue: String?,
)

private val StarGold = Color(0xFFF5B400)
private val HiddenOrange = Color(0xFFFF9500)

private fun storageKeyForModel(agentId: String, choice: SessionConfigChoice): String =
    "${agentId.lowercase()}::${choice.value.lowercase()}"

internal fun findSettingsModelOption(options: List<SessionConfigOption>): SessionConfigOption? =
    options.firstOrNull { opt ->
        val k = opt.key.lowercase()
        val c = opt.category.lowercase()
        c == "model" || k == "model" || k.contains("model")
    }

internal fun findSettingsEffortOption(options: List<SessionConfigOption>): SessionConfigOption? =
    options.firstOrNull { opt ->
        val k = opt.key.lowercase()
        val c = opt.category.lowercase()
        c.contains("thought") || c.contains("effort") || c.contains("reason") ||
            k.contains("thought") || k.contains("effort") || k.contains("thinking") || k.contains("reason")
    }

/**
 * 1:1 port of iOS `ThreadSettingsSheet` (`ThreadSettingsSheet.swift`).
 * Grouped collapsible provider model catalog with favorites, hidden-model management,
 * inline reasoning effort & runtime access controls, custom relay model entry,
 * and a floating bottom filter + search bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadSettingsSheet(
    agentId: String,
    agent: CatalogAgent?,
    agents: List<CatalogAgent>,
    allowCrossAgentSwitch: Boolean,
    modes: List<SessionMode>,
    currentModeId: String,
    configOptions: List<SessionConfigOption>,
    modelPrefs: ModelPreferencesStore,
    onSelectAgentAndModel: (agentId: String, modelKey: String, modelValue: String) -> Unit,
    onSelectMode: (String) -> Unit,
    onSelectOption: (key: String, value: String) -> Unit,
    onRefreshCatalog: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val colors = VibexTheme.colors
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val favoriteIds by modelPrefs.favoriteIds.collectAsState()
    val hiddenIds by modelPrefs.hiddenIds.collectAsState()
    val customModelsByAgent by modelPrefs.customModelsByAgent.collectAsState()

    var subpage by remember { mutableStateOf(SettingsSubpage.Main) }
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf<ModelCatalogFilter>(ModelCatalogFilter.All) }
    var filterMenuExpanded by remember { mutableStateOf(false) }
    var manageHiddenModels by remember { mutableStateOf(false) }
    var collapsedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initialGroupOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var didApplyDefaultCollapse by remember { mutableStateOf(false) }
    var showCustomModelDialog by remember { mutableStateOf(false) }
    var customModelDraft by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        onRefreshCatalog?.invoke()
    }

    val activeModelOption = remember(configOptions, agent, agentId) {
        findSettingsModelOption(configOptions)
            ?: agent?.modelOptions?.firstOrNull()
            ?: fallbackModelOptionForAgent(agentId)
    }
    val effortOption = remember(configOptions, agent) {
        findSettingsEffortOption(configOptions)
            ?: findSettingsEffortOption(agent?.otherOptions.orEmpty())
    }
    val extraOptions = remember(configOptions) {
        configOptions.filter { opt ->
            opt.key != activeModelOption.key &&
                opt.key != effortOption?.key &&
                !opt.category.equals("model", ignoreCase = true) &&
                !opt.category.equals("mode", ignoreCase = true) &&
                opt.choices.size > 1
        }
    }

    val rawGroups = remember(
        agentId,
        agent,
        agents,
        allowCrossAgentSwitch,
        activeModelOption,
        customModelsByAgent,
    ) {
        val usableAgents = agents.filter { it.usable }
        val primaryTitle = agent?.displayName?.takeIf { it.isNotBlank() }
            ?: agentDisplayName(agentId, "")
        val primaryCustoms = customModelsByAgent[agentId].orEmpty()
        val primaryChoices = buildList {
            primaryCustoms.forEach { custom ->
                if (activeModelOption.choices.none { it.value.equals(custom, ignoreCase = true) }) {
                    add(CatalogAgent.makeCustomChoice(custom))
                }
            }
            addAll(activeModelOption.choices)
        }
        val primaryGroup = ProviderModelGroup(
            agentId = agentId,
            title = primaryTitle,
            iconSvg = agent?.iconSvg.orEmpty(),
            modelKey = activeModelOption.key.ifBlank { "model" },
            choices = primaryChoices,
            activeValue = activeModelOption.value.ifBlank { primaryChoices.firstOrNull()?.value },
        )

        if (!allowCrossAgentSwitch) {
            listOf(primaryGroup)
        } else {
            val others = usableAgents
                .filterNot { it.id.equals(agentId, ignoreCase = true) }
                .map { other ->
                    val opt = other.modelOptions.firstOrNull() ?: fallbackModelOptionForAgent(other.id)
                    val customs = customModelsByAgent[other.id].orEmpty()
                    val merged = buildList {
                        customs.forEach { custom ->
                            if (opt.choices.none { c -> c.value.equals(custom, ignoreCase = true) }) {
                                add(CatalogAgent.makeCustomChoice(custom))
                            }
                        }
                        addAll(opt.choices)
                    }
                    ProviderModelGroup(
                        agentId = other.id,
                        title = agentDisplayName(other.id, other.displayName),
                        iconSvg = other.iconSvg,
                        modelKey = opt.key.ifBlank { "model" },
                        choices = merged,
                        activeValue = null,
                    )
                }
            listOf(primaryGroup) + others
        }
    }

    LaunchedEffect(rawGroups.map { it.agentId }) {
        val missing = rawGroups.map { it.agentId }.filterNot { it in initialGroupOrder }
        if (missing.isNotEmpty()) {
            initialGroupOrder = initialGroupOrder + missing
        }
        if (!didApplyDefaultCollapse && rawGroups.size > 1) {
            val totalChoices = rawGroups.sumOf { it.choices.size }
            if (rawGroups.size >= 3 || totalChoices > 8) {
                collapsedGroupIds = rawGroups.drop(1).map { it.agentId }.toSet()
            }
            didApplyDefaultCollapse = true
        }
    }

    val orderedGroups = remember(rawGroups, initialGroupOrder) {
        if (initialGroupOrder.isEmpty()) rawGroups
        else {
            val rank = initialGroupOrder.withIndex().associate { it.value to it.index }
            rawGroups.sortedBy { rank[it.agentId] ?: 10_000 }
        }
    }

    val isSearchingModels = searchQuery.trim().isNotEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bg,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 720.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            AnimatedContent(
                targetState = subpage,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "settingsSubpage",
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    SettingsSubpage.Effort -> EffortSubpageContent(
                        option = effortOption,
                        onBack = { subpage = SettingsSubpage.Main },
                        onSelect = { value ->
                            effortOption?.let { onSelectOption(it.key, value) }
                            subpage = SettingsSubpage.Main
                        },
                    )

                    SettingsSubpage.Runtime -> RuntimeSubpageContent(
                        modes = modes,
                        currentModeId = currentModeId,
                        onBack = { subpage = SettingsSubpage.Main },
                        onSelect = { modeId ->
                            onSelectMode(modeId)
                            subpage = SettingsSubpage.Main
                        },
                    )

                    SettingsSubpage.Main -> Column(modifier = Modifier.fillMaxWidth()) {
                        // Top Header (`✕` · `会话设置` · `✓`)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.06f))
                                    .clip(CircleShape)
                                    .background(colors.card)
                                    .border(0.6.dp, colors.stroke, CircleShape)
                                    .clickable(onClick = onDismiss),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "关闭",
                                    tint = colors.textPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            Text(
                                text = "会话设置",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )

                            Spacer(modifier = Modifier.weight(1f))

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .shadow(4.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f))
                                    .clip(CircleShape)
                                    .background(colors.textPrimary)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onDismiss()
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "完成",
                                    tint = colors.bg,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            val hasSessionParams = !isSearchingModels &&
                                ((effortOption != null && effortOption.choices.size > 1) ||
                                    extraOptions.isNotEmpty() ||
                                    modes.isNotEmpty())

                            if (hasSessionParams) {
                                SessionParametersCard(
                                    effortOption = effortOption,
                                    extraOptions = extraOptions,
                                    modes = modes,
                                    currentModeId = currentModeId,
                                    onSelectEffort = { value ->
                                        effortOption?.let { onSelectOption(it.key, value) }
                                    },
                                    onOpenEffortSubpage = { subpage = SettingsSubpage.Effort },
                                    onCycleExtraOption = { opt ->
                                        val idx = opt.choices.indexOfFirst { it.value == opt.value }
                                        val next = opt.choices[(idx + 1).coerceAtLeast(0) % opt.choices.size]
                                        onSelectOption(opt.key, next.value)
                                    },
                                    onOpenRuntimeSubpage = { subpage = SettingsSubpage.Runtime },
                                )
                            }

                            // Provider-grouped model catalog
                            val visibleGroups = orderedGroups.mapNotNull { group ->
                                val filtered = filterGroupChoices(
                                    group = group,
                                    query = searchQuery,
                                    filter = activeFilter,
                                    favoriteIds = favoriteIds,
                                    hiddenIds = hiddenIds,
                                    manageHiddenModels = manageHiddenModels,
                                )
                                if (filtered.isEmpty()) null else group to filtered
                            }

                            if (visibleGroups.isEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(colors.card)
                                        .padding(vertical = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        tint = colors.textTertiary,
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Text(
                                        text = "没有匹配的模型",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textSecondary,
                                    )
                                }
                            } else {
                                visibleGroups.forEach { (group, rows) ->
                                    val providerFiltered = (activeFilter as? ModelCatalogFilter.Provider)
                                        ?.agentId?.equals(group.agentId, ignoreCase = true) == true
                                    val isCollapsed = group.agentId in collapsedGroupIds &&
                                        !isSearchingModels &&
                                        !providerFiltered
                                    val hasSelected = rows.any { it.value == group.activeValue }

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    collapsedGroupIds = if (group.agentId in collapsedGroupIds) {
                                                        collapsedGroupIds - group.agentId
                                                    } else {
                                                        collapsedGroupIds + group.agentId
                                                    }
                                                }
                                                .padding(horizontal = 4.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                                        ) {
                                            AgentAvatar(
                                                agentId = group.agentId,
                                                size = 16.dp,
                                                iconSvg = group.iconSvg,
                                                displayName = group.title,
                                            )
                                            Text(
                                                text = group.title,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (hasSelected) colors.textPrimary else colors.textSecondary,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (isCollapsed) {
                                                val selectedChoice = rows.firstOrNull { it.value == group.activeValue }
                                                Text(
                                                    text = selectedChoice?.label ?: "${rows.size}",
                                                    style = TextStyle(
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        fontFeatureSettings = "tnum",
                                                        color = if (selectedChoice != null) colors.textSecondary else colors.textTertiary,
                                                    ),
                                                    maxLines = 1,
                                                )
                                            }
                                            Icon(
                                                imageVector = if (isCollapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                                                contentDescription = null,
                                                tint = colors.textTertiary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }

                                        if (!isCollapsed) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .background(colors.card)
                                                    .border(0.6.dp, colors.stroke, RoundedCornerShape(16.dp)),
                                            ) {
                                                rows.forEachIndexed { idx, choice ->
                                                    val key = storageKeyForModel(group.agentId, choice)
                                                    val isSelected = choice.value == group.activeValue
                                                    val isFav = key in favoriteIds
                                                    val isHidden = key in hiddenIds
                                                    val isDefaultBadge = idx == 0 &&
                                                        !(choice.description.orEmpty().contains("自定义"))

                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                onSelectAgentAndModel(
                                                                    group.agentId,
                                                                    group.modelKey,
                                                                    choice.value,
                                                                )
                                                            }
                                                            .padding(horizontal = 16.dp, vertical = 11.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        Column(
                                                            modifier = Modifier.weight(1f),
                                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            ) {
                                                                Text(
                                                                    text = choice.label,
                                                                    fontSize = 15.sp,
                                                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                                    color = if (isHidden) colors.textSecondary else colors.textPrimary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                )
                                                                if (isDefaultBadge) {
                                                                    SmallBadgePill("默认")
                                                                }
                                                            }
                                                            val desc = choice.description?.takeIf { it.isNotBlank() }
                                                            if (desc != null) {
                                                                Text(
                                                                    text = desc,
                                                                    fontSize = 12.sp,
                                                                    color = colors.textTertiary,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                )
                                                            }
                                                        }

                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Rounded.Check,
                                                                contentDescription = null,
                                                                tint = colors.textPrimary,
                                                                modifier = Modifier.size(16.dp),
                                                            )
                                                        }

                                                        if (manageHiddenModels) {
                                                            Icon(
                                                                imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                                contentDescription = if (isHidden) "取消隐藏模型" else "隐藏模型",
                                                                tint = if (isHidden) HiddenOrange else colors.textSecondary,
                                                                modifier = Modifier
                                                                    .size(28.dp)
                                                                    .clip(CircleShape)
                                                                    .clickable { modelPrefs.toggleHidden(key) }
                                                                    .padding(5.dp),
                                                            )
                                                        }

                                                        Icon(
                                                            imageVector = if (isFav) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                                                            contentDescription = if (isFav) "取消收藏" else "收藏模型",
                                                            tint = if (isFav) StarGold else colors.textTertiary,
                                                            modifier = Modifier
                                                                .size(28.dp)
                                                                .clip(CircleShape)
                                                                .clickable { modelPrefs.toggleFavorite(key) }
                                                                .padding(5.dp),
                                                        )
                                                    }

                                                    if (idx < rows.lastIndex) {
                                                        HorizontalDivider(
                                                            modifier = Modifier.padding(start = 16.dp),
                                                            thickness = 0.5.dp,
                                                            color = colors.stroke,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Catalog Management Section (`模型目录`)
                            if (!isSearchingModels) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "模型目录",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(colors.card)
                                            .border(0.6.dp, colors.stroke, RoundedCornerShape(16.dp)),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "管理隐藏模型",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colors.textPrimary,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Switch(
                                                checked = manageHiddenModels,
                                                onCheckedChange = { manageHiddenModels = it },
                                            )
                                        }

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 16.dp),
                                            thickness = 0.5.dp,
                                            color = colors.stroke,
                                        )

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    customModelDraft = ""
                                                    showCustomModelDialog = true
                                                }
                                                .padding(horizontal = 16.dp, vertical = 13.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = "自定义 / 中转模型…",
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colors.textPrimary,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Icon(
                                                imageVector = Icons.Rounded.AddCircleOutline,
                                                contentDescription = null,
                                                tint = colors.accent,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }

            // Bottom Floating Filter + Search Capsule Bar
            if (subpage == SettingsSubpage.Main) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.bg)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f))
                                .clip(CircleShape)
                                .background(colors.card)
                                .border(0.7.dp, colors.stroke, CircleShape)
                                .clickable { filterMenuExpanded = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FilterList,
                                contentDescription = "筛选模型",
                                tint = colors.textPrimary,
                                modifier = Modifier.size(19.dp),
                            )
                            if (activeFilter != ModelCatalogFilter.All) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = (-3).dp, y = 3.dp)
                                        .size(8.5.dp)
                                        .clip(CircleShape)
                                        .background(colors.accent),
                                )
                            }
                        }

                        VibexDropdownMenu(
                            expanded = filterMenuExpanded,
                            onDismissRequest = { filterMenuExpanded = false },
                        ) {
                            VibexDropdownMenuItem(
                                text = "全部模型",
                                selected = activeFilter == ModelCatalogFilter.All,
                                onClick = {
                                    activeFilter = ModelCatalogFilter.All
                                    filterMenuExpanded = false
                                },
                            )
                            VibexDropdownMenuItem(
                                text = "仅显示收藏",
                                selected = activeFilter == ModelCatalogFilter.Favorites,
                                onClick = {
                                    activeFilter = ModelCatalogFilter.Favorites
                                    filterMenuExpanded = false
                                },
                            )
                            if (orderedGroups.size > 1) {
                                VibexDropdownDivider()
                                orderedGroups.forEach { grp ->
                                    val selected = (activeFilter as? ModelCatalogFilter.Provider)
                                        ?.agentId?.equals(grp.agentId, ignoreCase = true) == true
                                    VibexDropdownMenuItem(
                                        text = grp.title,
                                        selected = selected,
                                        onClick = {
                                            activeFilter = ModelCatalogFilter.Provider(grp.agentId, grp.title)
                                            filterMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.08f))
                            .clip(CircleShape)
                            .background(colors.card)
                            .border(0.7.dp, colors.stroke, CircleShape)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(17.dp),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "搜索模型…",
                                    fontSize = 14.5.sp,
                                    color = colors.textTertiary,
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 14.5.sp, color = colors.textPrimary),
                                cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Rounded.Cancel,
                                contentDescription = "清除",
                                tint = colors.textTertiary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { searchQuery = "" },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCustomModelDialog) {
        AlertDialog(
            onDismissRequest = { showCustomModelDialog = false },
            containerColor = colors.card,
            title = { Text("添加自定义 / 中转模型", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "若桌面端已配置 API 中转或网关，输入模型 ID 即可直接切换。",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                    )
                    OutlinedTextField(
                        value = customModelDraft,
                        onValueChange = { customModelDraft = it },
                        placeholder = { Text("例如 claude-opus-4-8 / gpt-6-sol", fontSize = 13.5.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = customModelDraft.trim()
                        showCustomModelDialog = false
                        if (trimmed.isNotEmpty()) {
                            modelPrefs.addCustomModel(agentId, trimmed)
                            onSelectAgentAndModel(agentId, activeModelOption.key.ifBlank { "model" }, trimmed)
                        }
                    },
                ) {
                    Text("使用此模型", fontWeight = FontWeight.SemiBold, color = colors.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomModelDialog = false }) {
                    Text("取消", color = colors.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun SessionParametersCard(
    effortOption: SessionConfigOption?,
    extraOptions: List<SessionConfigOption>,
    modes: List<SessionMode>,
    currentModeId: String,
    onSelectEffort: (String) -> Unit,
    onOpenEffortSubpage: () -> Unit,
    onCycleExtraOption: (SessionConfigOption) -> Unit,
    onOpenRuntimeSubpage: () -> Unit,
) {
    val colors = VibexTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "会话参数",
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .border(0.6.dp, colors.stroke, RoundedCornerShape(16.dp)),
        ) {
            if (effortOption != null && effortOption.choices.size > 1) {
                val useInline = effortOption.choices.size <= 4 && effortOption.choices.all { it.label.length <= 6 }
                if (useInline) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "推理强度",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(colors.segmentedTrack)
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            val activeVal = effortOption.value.ifBlank { effortOption.choices.first().value }
                            effortOption.choices.forEach { choice ->
                                val selected = choice.value == activeVal
                                Text(
                                    text = choice.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) colors.textPrimary else colors.textSecondary,
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (selected) colors.segmentedThumb else Color.Transparent)
                                        .clickable {
                                            if (!selected) onSelectEffort(choice.value)
                                        }
                                        .padding(horizontal = 11.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                } else {
                    val currentLabel = effortOption.choices.firstOrNull { it.value == effortOption.value }?.label
                        ?: effortOption.choices.first().label
                    SettingsDisclosureRow(
                        label = "推理强度",
                        value = currentLabel,
                        onClick = onOpenEffortSubpage,
                    )
                }

                if (extraOptions.isNotEmpty() || modes.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = colors.stroke,
                    )
                }
            }

            extraOptions.forEachIndexed { idx, extra ->
                val valLabel = extra.choices.firstOrNull { it.value == extra.value }?.label ?: extra.value
                SettingsDisclosureRow(
                    label = extra.label,
                    value = valLabel,
                    onClick = { onCycleExtraOption(extra) },
                )
                if (idx < extraOptions.lastIndex || modes.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        thickness = 0.5.dp,
                        color = colors.stroke,
                    )
                }
            }

            if (modes.isNotEmpty()) {
                val activeMode = modes.firstOrNull { it.id == currentModeId } ?: modes.first()
                SettingsDisclosureRow(
                    label = "运行权限",
                    value = activeMode.name,
                    onClick = onOpenRuntimeSubpage,
                )
            }
        }
    }
}

@Composable
private fun SettingsDisclosureRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    val colors = VibexTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            fontSize = 14.5.sp,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun EffortSubpageContent(
    option: SessionConfigOption?,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = VibexTheme.colors
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        SubpageTopHeader(title = "推理强度", onBack = onBack)
        Spacer(modifier = Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .border(0.6.dp, colors.stroke, RoundedCornerShape(16.dp)),
        ) {
            val choices = option?.choices.orEmpty()
            choices.forEachIndexed { idx, choice ->
                val selected = choice.value == option?.value
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(choice.value) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = choice.label,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Rounded.Check, null, tint = colors.accent, modifier = Modifier.size(17.dp))
                    }
                }
                if (idx < choices.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = colors.stroke)
                }
            }
        }
    }
}

@Composable
private fun RuntimeSubpageContent(
    modes: List<SessionMode>,
    currentModeId: String,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = VibexTheme.colors
    val activeId = currentModeId.ifBlank { modes.firstOrNull()?.id.orEmpty() }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        SubpageTopHeader(title = "运行权限", onBack = onBack)
        Spacer(modifier = Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(colors.card)
                .border(0.6.dp, colors.stroke, RoundedCornerShape(16.dp)),
        ) {
            modes.forEachIndexed { idx, mode ->
                val selected = mode.id == activeId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode.id) }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = mode.name,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(Icons.Rounded.Check, null, tint = colors.accent, modifier = Modifier.size(17.dp))
                    }
                }
                if (idx < modes.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = colors.stroke)
                }
            }
        }
    }
}

@Composable
private fun SubpageTopHeader(
    title: String,
    onBack: () -> Unit,
) {
    val colors = VibexTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.card)
                .border(0.6.dp, colors.stroke, CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                contentDescription = "返回",
                tint = colors.textPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun SmallBadgePill(label: String) {
    val colors = VibexTheme.colors
    Text(
        text = label,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textSecondary,
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.textPrimary.copy(alpha = if (colors.isDark) 0.12f else 0.07f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

private fun filterGroupChoices(
    group: ProviderModelGroup,
    query: String,
    filter: ModelCatalogFilter,
    favoriteIds: Set<String>,
    hiddenIds: Set<String>,
    manageHiddenModels: Boolean,
): List<SessionConfigChoice> {
    if (filter is ModelCatalogFilter.Provider && !filter.agentId.equals(group.agentId, ignoreCase = true)) {
        return emptyList()
    }
    val q = query.trim().lowercase()
    val visible = group.choices.filter { choice ->
        val key = storageKeyForModel(group.agentId, choice)
        val isSelected = choice.value == group.activeValue
        if (!manageHiddenModels && !isSelected && key in hiddenIds) return@filter false
        if (filter == ModelCatalogFilter.Favorites && key !in favoriteIds && !isSelected) return@filter false
        if (q.isEmpty()) return@filter true
        choice.label.lowercase().contains(q) ||
            choice.value.lowercase().contains(q) ||
            choice.description.orEmpty().lowercase().contains(q) ||
            group.title.lowercase().contains(q)
    }
    return visible.sortedWith(
        compareByDescending { choice -> storageKeyForModel(group.agentId, choice) in favoriteIds },
    )
}
