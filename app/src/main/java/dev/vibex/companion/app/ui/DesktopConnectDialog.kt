package dev.vibex.companion.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.AccountDesktop
import dev.vibex.companion.DiscoveredLanHost
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.DesktopConnectSheet
import dev.vibex.companion.app.DesktopConnectStage
import dev.vibex.companion.app.UiState
import dev.vibex.companion.app.ui.theme.PlexMono
import dev.vibex.companion.app.ui.theme.VibexTheme

/**
 * 1:1 port of iOS `DesktopConnectSheet`:
 * Same-account zero-click connection (LAN-first -> Cloud Relay) + LAN SAS fallback for unmatched hosts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopConnectDialog(
    state: UiState,
    sheet: DesktopConnectSheet,
    model: CompanionModel,
    onPairWithCode: () -> Unit,
) {
    val colors = VibexTheme.colors
    val desktops = state.accountDesktops
    val selected = desktops.firstOrNull { it.hostId == sheet.selectedHostId } ?: desktops.firstOrNull()
    val accountHostIds = remember(desktops) { desktops.map { it.hostId.lowercase() }.toSet() }
    val unmatchedLanHosts = remember(state.discoveredHosts, accountHostIds) {
        state.discoveredHosts.filter { host ->
            val hId = host.hostId
            !host.isCloudService && (hId == null || hId.lowercase() !in accountHostIds)
        }
    }

    ModalBottomSheet(
        onDismissRequest = { model.dismissDesktopConnect() },
        containerColor = colors.bgElevated,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val stage = sheet.stage
            val showEmptyAccountGuide =
                stage == DesktopConnectStage.Choosing && desktops.isEmpty() && unmatchedLanHosts.isEmpty() && state.accountSignedIn

            if (!showEmptyAccountGuide) {
                Text(
                    text = if (state.accountSignedIn) "连接你的电脑" else "当前 Wi-Fi 发现电脑",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
            }

            when (stage) {
                DesktopConnectStage.Syncing -> LoadingState("正在同步你的电脑…")

                DesktopConnectStage.Choosing -> {
                    if (showEmptyAccountGuide) {
                        NoDesktopGuide(onDownload = { model.openAccountSitePath("/download") })
                    } else if (desktops.isEmpty() && unmatchedLanHosts.isEmpty()) {
                        Text(
                            text = "同一 Wi-Fi 下暂未发现运行中的 Dimo 电脑",
                            fontSize = 14.sp,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    } else {
                        if (desktops.isNotEmpty()) {
                            DesktopList(
                                desktops = desktops,
                                selected = selected,
                                model = model,
                                onSelect = model::selectAccountDesktop,
                            )
                            selected?.let { SingleTapConnectArea(state = state, desktop = it, model = model) }
                        }

                        if (unmatchedLanHosts.isNotEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (desktops.isNotEmpty()) {
                                    Text(
                                        text = "当前 Wi-Fi 其他电脑",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                                    )
                                }
                                unmatchedLanHosts.forEach { host ->
                                    LanDiscoveredHostRow(
                                        host = host,
                                        onClick = { model.connectLanHostInSheet(host) },
                                    )
                                }
                            }
                        }
                    }
                }

                is DesktopConnectStage.Connecting -> {
                    ConnectingTargetPreview(name = stage.target.displayName)
                    LoadingState("正在连接…")
                }

                is DesktopConnectStage.AwaitingApproval -> {
                    ApprovalPromptCard(hostName = stage.hostName, sasCode = stage.sasCode)
                }

                is DesktopConnectStage.Failed -> {
                    FailureState(stage.message, onRetry = model::retryDesktopConnect)
                }
            }

            if (stage == DesktopConnectStage.Choosing || stage is DesktopConnectStage.Failed) {
                Text(
                    text = "扫码或输入 8 位连接码",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.textTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onPairWithCode)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DesktopList(
    desktops: List<AccountDesktop>,
    selected: AccountDesktop?,
    model: CompanionModel,
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        desktops.forEach { desktop ->
            val onSameWifi = model.lanOriginFor(desktop) != null
            val isOnline = onSameWifi || desktop.cloudOnline
            DesktopRow(
                desktop = desktop,
                onSameWifi = onSameWifi,
                isSelected = desktops.size > 1 && desktop.hostId == selected?.hostId,
                onClick = if (desktops.size > 1) ({ onSelect(desktop.hostId) }) else null,
                onRemove = if (desktops.size > 1 && !isOnline) ({ model.removeAccountDesktop(desktop) }) else null,
            )
        }
    }
}

@Composable
private fun DesktopRow(
    desktop: AccountDesktop,
    onSameWifi: Boolean,
    isSelected: Boolean,
    onClick: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val colors = VibexTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(0.75.dp, if (isSelected) colors.accent else colors.stroke, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Computer, null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Text(
            text = desktop.displayName,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onSameWifi) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.pass.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Wifi, null, tint = colors.pass, modifier = Modifier.size(11.dp))
                Text(
                    text = "局域网极速",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.pass,
                )
            }
        } else if (desktop.cloudOnline) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.pass.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(Icons.Rounded.Cloud, null, tint = colors.pass, modifier = Modifier.size(11.dp))
                Text(
                    text = "云端在线",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.pass,
                )
            }
        }
        desktop.platformBadge?.let {
            Text(
                text = it,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.segmentedTrack)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (isSelected) {
            Icon(Icons.Rounded.CheckCircle, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "移除离线电脑",
                    tint = colors.textTertiary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun LanDiscoveredHostRow(
    host: DiscoveredLanHost,
    onClick: () -> Unit,
) {
    val colors = VibexTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(0.75.dp, colors.stroke, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Computer, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = host.displayName,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "对比连接码接入",
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }
        Text(
            text = "连接",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(colors.accent.copy(alpha = 0.14f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun SingleTapConnectArea(
    state: UiState,
    desktop: AccountDesktop,
    model: CompanionModel,
) {
    val colors = VibexTheme.colors
    val lanEnabled = model.lanOriginFor(desktop) != null
    val cloudEnabled = state.accountOverview?.canUseCloudRelay == true
    val canConnect = lanEnabled || cloudEnabled
    val hint = when {
        lanEnabled -> "同一 Wi-Fi 自动走局域网直连"
        cloudEnabled -> "当前通过 Dimo 云服务连接"
        else -> "当前不在同一 Wi-Fi，且账号未开通云服务"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (canConnect) 1f else 0.4f)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accent)
                .clickable(enabled = canConnect) { model.connectSelectedAccountDesktop() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "一键连接",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onAccent,
            )
        }
        Text(
            text = hint,
            fontSize = 12.sp,
            color = if (canConnect) colors.textSecondary else colors.danger,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConnectingTargetPreview(name: String) {
    val colors = VibexTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(0.75.dp, colors.stroke, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.Computer, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Text(
            text = name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ApprovalPromptCard(hostName: String, sasCode: String) {
    val colors = VibexTheme.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.card)
            .border(0.75.dp, colors.stroke, shape)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "请在电脑「$hostName」上核对连接码并点击允许",
            fontSize = 14.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = sasCode,
            style = TextStyle(
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = PlexMono,
                fontFeatureSettings = "tnum",
                letterSpacing = 3.sp,
                color = colors.accent,
            ),
        )
        LoadingState("等待电脑端确认…")
    }
}

@Composable
private fun FailureState(message: String, onRetry: () -> Unit) {
    val colors = VibexTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Warning, null, tint = colors.danger, modifier = Modifier.size(18.dp))
        Text(message, fontSize = 14.sp, color = colors.danger, textAlign = TextAlign.Center)
    }
    PrimaryButton(label = "重试", onClick = onRetry)
}

@Composable
private fun NoDesktopGuide(onDownload: () -> Unit) {
    val colors = VibexTheme.colors
    Text(
        text = "在电脑上安装 Dimo 并登录同一账号，即可免输码连接",
        fontSize = 14.sp,
        color = colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    PrimaryButton(label = "下载 Dimo", onClick = onDownload)
}
