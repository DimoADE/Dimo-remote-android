package dev.vibex.companion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.Route
import dev.vibex.companion.app.ui.theme.PlexMono
import dev.vibex.companion.app.ui.theme.VibexTheme

val BOTTOM_DOCK_CLEARANCE = 78.dp

enum class AppDestination(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Conversations("会话", Icons.Outlined.ChatBubbleOutline, Icons.Rounded.ChatBubble),
    Status("看板", Icons.Outlined.BarChart, Icons.Rounded.BarChart),
    Collaboration("协作", Icons.Outlined.Groups, Icons.Rounded.Groups),
    Settings("设置", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

val Route.destination: AppDestination
    get() = when (this) {
        Route.Status -> AppDestination.Status
        Route.Collaboration -> AppDestination.Collaboration
        is Route.Settings -> AppDestination.Settings
        Route.Conversations,
        Route.NewThread,
        Route.Folders,
        Route.ManualPair,
        is Route.Timeline,
        -> AppDestination.Conversations
    }

fun CompanionModel.navigate(to: AppDestination) = when (to) {
    AppDestination.Conversations -> go(Route.Conversations)
    AppDestination.Status -> go(Route.Status)
    AppDestination.Collaboration -> go(Route.Collaboration)
    AppDestination.Settings -> go(Route.Settings())
}

/**
 * 1:1 port of iOS `FloatingWorkspaceCapsuleDock` (`MainWorkspaceView.swift`).
 * Floating bottom capsule dock with contextual center `+` action on the Conversations hub.
 */
@Composable
fun FloatingWorkspaceCapsuleDock(
    selected: AppDestination,
    collabUnreadCount: Int,
    onSelect: (AppDestination) -> Unit,
    onNewThread: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VibexTheme.colors
    val haptic = LocalHapticFeedback.current
    val capsuleShape = RoundedCornerShape(50)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .shadow(
                    elevation = if (colors.isDark) 14.dp else 10.dp,
                    shape = capsuleShape,
                    ambientColor = Color.Black.copy(alpha = if (colors.isDark) 0.30f else 0.08f),
                    spotColor = Color.Black.copy(alpha = if (colors.isDark) 0.36f else 0.12f),
                )
                .clip(capsuleShape)
                .background(colors.card.copy(alpha = 0.96f))
                .border(0.75.dp, colors.stroke, capsuleShape)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DockTabItem(
                destination = AppDestination.Conversations,
                isSelected = selected == AppDestination.Conversations,
                badgeCount = 0,
                onClick = {
                    if (selected != AppDestination.Conversations) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onSelect(AppDestination.Conversations)
                },
                modifier = Modifier.weight(1f),
            )

            DockTabItem(
                destination = AppDestination.Status,
                isSelected = selected == AppDestination.Status,
                badgeCount = 0,
                onClick = {
                    if (selected != AppDestination.Status) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onSelect(AppDestination.Status)
                },
                modifier = Modifier.weight(1f),
            )

            AnimatedVisibility(
                visible = selected == AppDestination.Conversations,
                enter = expandHorizontally(
                    animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
                    expandFrom = Alignment.CenterHorizontally,
                ) + scaleIn(
                    animationSpec = spring(dampingRatio = 0.80f, stiffness = Spring.StiffnessMediumLow),
                ) + fadeIn(),
                exit = shrinkHorizontally(
                    animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMedium),
                    shrinkTowards = Alignment.CenterHorizontally,
                ) + scaleOut() + fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(colors.textPrimary)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNewThread()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "新建会话",
                        tint = colors.bg,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            DockTabItem(
                destination = AppDestination.Collaboration,
                isSelected = selected == AppDestination.Collaboration,
                badgeCount = collabUnreadCount,
                onClick = {
                    if (selected != AppDestination.Collaboration) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onSelect(AppDestination.Collaboration)
                },
                modifier = Modifier.weight(1f),
            )

            DockTabItem(
                destination = AppDestination.Settings,
                isSelected = selected == AppDestination.Settings,
                badgeCount = 0,
                onClick = {
                    if (selected != AppDestination.Settings) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onSelect(AppDestination.Settings)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DockTabItem(
    destination: AppDestination,
    isSelected: Boolean,
    badgeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VibexTheme.colors
    val pillShape = RoundedCornerShape(50)
    val bgColor = if (isSelected) {
        colors.textPrimary.copy(alpha = if (colors.isDark) 0.14f else 0.09f)
    } else {
        Color.Transparent
    }
    val fgColor = if (isSelected) colors.textPrimary else colors.textSecondary

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(pillShape)
            .background(bgColor)
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(
                imageVector = if (isSelected) destination.selectedIcon else destination.icon,
                contentDescription = destination.title,
                tint = fgColor,
                modifier = Modifier.size(20.dp),
            )
            if (badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 7.dp, y = (-3).dp)
                        .clip(CircleShape)
                        .background(colors.danger)
                        .padding(horizontal = if (badgeCount > 9) 3.5.dp else 4.dp, vertical = 0.5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (badgeCount > 99) "99+" else "$badgeCount",
                        style = TextStyle(
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                            color = Color.White,
                        ),
                    )
                }
            }
        }
        Text(
            text = destination.title,
            fontSize = 10.5.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = fgColor,
            maxLines = 1,
        )
    }
}

/**
 * 40dp circular back button for Level-2 screens (`NewThreadScreen`, `TimelineScreen`).
 */
@Composable
fun FloatingCircleBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VibexTheme.colors
    Box(
        modifier = modifier
            .size(40.dp)
            .shadow(
                elevation = if (colors.isDark) 4.dp else 2.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.08f),
            )
            .clip(CircleShape)
            .background(colors.card)
            .border(0.6.dp, colors.stroke, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = "返回",
            tint = colors.textPrimary,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 40dp top-right dual-action capsule (`Diff` | `Files`) for Level-2 workspace screens.
 */
@Composable
fun WorkspaceDualToolsCapsule(
    diffCount: Int = 0,
    additionCount: Long = 0L,
    deletionCount: Long = 0L,
    enabled: Boolean = true,
    onOpenDiff: () -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = VibexTheme.colors
    val shape = RoundedCornerShape(50)

    Row(
        modifier = modifier
            .height(40.dp)
            .shadow(
                elevation = if (colors.isDark) 4.dp else 2.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.06f),
                spotColor = Color.Black.copy(alpha = 0.08f),
            )
            .clip(shape)
            .background(colors.card)
            .border(0.6.dp, colors.stroke, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(enabled = enabled, onClick = onOpenDiff)
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Code,
                contentDescription = "查看代码变更",
                tint = if (enabled) colors.textPrimary else colors.textTertiary,
                modifier = Modifier.size(17.dp),
            )
            if (additionCount > 0L || deletionCount > 0L) {
                if (additionCount > 0L) {
                    Text(
                        text = "+$additionCount",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = PlexMono,
                            fontFeatureSettings = "tnum",
                            color = colors.pass,
                        ),
                    )
                }
                if (deletionCount > 0L) {
                    Text(
                        text = "-$deletionCount",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = PlexMono,
                            fontFeatureSettings = "tnum",
                            color = colors.danger,
                        ),
                    )
                }
            } else if (diffCount > 0) {
                Text(
                    text = "$diffCount",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = PlexMono,
                        fontFeatureSettings = "tnum",
                        color = colors.accent,
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .width(0.6.dp)
                .height(18.dp)
                .background(colors.stroke),
        )

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(enabled = enabled, onClick = onOpenFiles)
                .padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Folder,
                contentDescription = "浏览工作区文件",
                tint = if (enabled) colors.textPrimary else colors.textTertiary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
