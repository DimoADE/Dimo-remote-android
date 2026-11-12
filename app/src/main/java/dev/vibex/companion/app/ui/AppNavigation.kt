package dev.vibex.companion.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.Route
import dev.vibex.companion.app.UiState
import dev.vibex.companion.app.ui.theme.VibexTheme

enum class AppDestination(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Conversations("对话", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
    Status("看板", Icons.Outlined.GridView, Icons.Rounded.GridView),
    Collaboration("协作空间", Icons.Outlined.Groups, Icons.Rounded.Groups),
    Settings("设置", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

val Route.destination: AppDestination
get() = when (this) {
    Route.Status -> AppDestination.Status
    Route.Collaboration -> AppDestination.Collaboration
    is Route.Settings -> AppDestination.Settings
    Route.Conversations, Route.Folders, Route.ManualPair, is Route.Timeline -> AppDestination.Conversations
}

private fun CompanionModel.navigate(to: AppDestination, from: Route) = when (to) {
    AppDestination.Conversations -> if (from is Route.Timeline) openNewChatLanding() else go(Route.Conversations)
    AppDestination.Status -> go(Route.Status)
    AppDestination.Collaboration -> go(Route.Collaboration)
    AppDestination.Settings -> go(Route.Settings())
}

@Composable
fun AppNavigationSection(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VibexTheme.colors
    val haptic = LocalHapticFeedback.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AppDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            val tint = if (isSelected) colors.accent else colors.textSecondary
            val rowBackground = when {
                !isSelected -> Color.Transparent
                colors.isDark -> Color.White.copy(alpha = 0.10f)
                else -> colors.accent.copy(alpha = 0.10f)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(rowBackground)
                    .selectable(selected = isSelected, role = Role.Tab) {
                        if (!isSelected) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(destination)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (isSelected) destination.selectedIcon else destination.icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(19.dp),
                )
                Text(
                    destination.title,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) colors.accent else colors.textPrimary,
                )
            }
        }
    }
}

@Composable
fun AppSidebarButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = VibexTheme.colors
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.bgElevated)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Menu, contentDescription = "打开侧边栏", tint = colors.textPrimary, modifier = Modifier.size(18.dp))
    }
}

/**
 * The one left drawer every destination uses: app navigation on top, then the
 * destination's own [sidebar] content.
 */
@Composable
fun AppSidebarDrawer(
    state: UiState,
    model: CompanionModel,
    isOpen: Boolean,
    onOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    sidebar: @Composable ColumnScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = VibexTheme.colors
    val focusManager = LocalFocusManager.current
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)

    // Registered on open so it outranks back handlers of screens composed after the drawer.
    if (isOpen) BackHandler { onOpenChange(false) }
    LaunchedEffect(isOpen) {
        if (isOpen) focusManager.clearFocus()
    }

    val edgeSwipe = if (gesturesEnabled) {
        Modifier.pointerInput(isOpen) {
            var totalDx = 0f
            var startX = 0f
            detectHorizontalDragGestures(
                onDragStart = { offset ->
                    startX = offset.x
                    totalDx = 0f
                },
                onHorizontalDrag = { _, dragAmount -> totalDx += dragAmount },
                onDragEnd = {
                    if (!isOpen && startX < 96f && totalDx > 100f) {
                        currentOnOpenChange(true)
                    } else if (isOpen && totalDx < -100f) {
                        currentOnOpenChange(false)
                    }
                },
            )
        }
    } else {
        Modifier
    }

    Box(modifier.fillMaxSize().then(edgeSwipe)) {
        content()

        AnimatedVisibility(
            visible = isOpen,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.zIndex(1f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.36f))
                    .clickable { onOpenChange(false) },
            )
        }

        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally { -it },
            exit = slideOutHorizontally { -it },
            modifier = Modifier.zIndex(2f),
        ) {
            Column(
                Modifier
                    .width(304.dp)
                    .fillMaxHeight()
                    .background(colors.bgElevated)
                    .clickable(enabled = false) {}
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                AppNavigationSection(
                    selected = state.route.destination,
                    onSelect = { destination ->
                        onOpenChange(false)
                        model.navigate(destination, state.route)
                    },
                )
                HorizontalDivider(color = colors.hairline)
                sidebar()
            }
        }
    }
}
