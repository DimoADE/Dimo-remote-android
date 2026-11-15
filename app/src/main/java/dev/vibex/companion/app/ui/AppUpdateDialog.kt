package dev.vibex.companion.app.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.vibex.companion.app.ui.theme.PlexMono
import dev.vibex.companion.app.ui.theme.VibexTheme
import dev.vibex.companion.app.update.ApkDownloadState
import dev.vibex.companion.app.update.AppUpdateUiState
import java.util.Locale

@Composable
fun AppUpdateDialog(
    state: AppUpdateUiState,
    onConfirmUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release = state.release ?: return
    if (!state.dialogVisible) return

    val colors = VibexTheme.colors
    val isDownloading = state.downloadState is ApkDownloadState.Downloading
    val canDismiss = !release.forceUpdate && !isDownloading

    Dialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = canDismiss,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.card)
                .border(1.dp, colors.hairline, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "发现新版本",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(colors.accent.copy(alpha = 0.14f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "v${release.version}",
                                fontSize = 11.sp,
                                fontFamily = PlexMono,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.accent,
                            )
                        }
                    }
                    Text(
                        text = buildString {
                            append("当前版本 v${state.currentVersionName}")
                            if (release.apkSize > 0L) {
                                append(" · 安装包 ${formatApkMegabytes(release.apkSize)}")
                            }
                        },
                        fontSize = 12.sp,
                        fontFamily = PlexMono,
                        color = colors.textSecondary,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.segmentedTrack)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "更新内容",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textSecondary,
                )
                Text(
                    text = release.changelog.ifBlank { "• 性能优化与体验改进" },
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = colors.textPrimary,
                )
            }

            when (val dl = state.downloadState) {
                is ApkDownloadState.Downloading -> {
                    val pct = (dl.progress * 100f).toInt().coerceIn(0, 100)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "正在下载更新包…",
                                fontSize = 12.sp,
                                color = colors.textSecondary,
                            )
                            Text(
                                text = if (dl.totalBytes > 0L) {
                                    "${formatApkMegabytes(dl.downloadedBytes)} / ${formatApkMegabytes(dl.totalBytes)} ($pct%)"
                                } else {
                                    formatApkMegabytes(dl.downloadedBytes)
                                },
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    fontFamily = PlexMono,
                                    fontFeatureSettings = "tnum",
                                    color = colors.textPrimary,
                                ),
                            )
                        }
                        LinearProgressIndicator(
                            progress = { dl.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = colors.accent,
                            trackColor = colors.segmentedTrack,
                        )
                    }
                }
                is ApkDownloadState.PendingInstallPermission -> {
                    Text(
                        text = "安装包已就绪，请开启「允许来自此来源的应用」权限后自动完成覆盖安装。",
                        fontSize = 12.5.sp,
                        color = colors.textSecondary,
                    )
                }
                is ApkDownloadState.ReadyToInstall -> {
                    Text(
                        text = "已调起系统安装器，直接点击「更新」即可覆盖安装并保留全部登录与连接数据。",
                        fontSize = 12.5.sp,
                        color = colors.textSecondary,
                    )
                }
                is ApkDownloadState.Error -> {
                    Text(
                        text = dl.message,
                        fontSize = 12.5.sp,
                        color = colors.danger,
                    )
                }
                ApkDownloadState.Idle -> Unit
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!release.forceUpdate) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.segmentedTrack)
                            .clickable(enabled = !isDownloading) { onDismiss() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "稍后提醒",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textSecondary,
                        )
                    }
                }

                val primaryLabel = when (state.downloadState) {
                    is ApkDownloadState.Downloading -> "正在下载…"
                    is ApkDownloadState.PendingInstallPermission -> "授权并安装"
                    is ApkDownloadState.ReadyToInstall -> "立即安装"
                    is ApkDownloadState.Error -> "重试下载"
                    ApkDownloadState.Idle -> "立即更新"
                }

                Box(
                    modifier = Modifier
                        .weight(if (release.forceUpdate) 1f else 1.25f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isDownloading) colors.accent.copy(alpha = 0.6f) else colors.accent)
                        .clickable(enabled = !isDownloading) { onConfirmUpdate() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = primaryLabel,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

private fun formatApkMegabytes(bytes: Long): String {
    if (bytes <= 0L) return "0.0 MB"
    return String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
}

@Composable
fun SettingsVersionUpdateRow(
    state: AppUpdateUiState,
    protocolVersion: String,
    onClick: () -> Unit,
) {
    val colors = VibexTheme.colors
    val release = state.release
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !state.checking, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background((if (state.hasUpdate) Color(0xFFFF3B30) else Color(0xFF34C759)).copy(0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state.hasUpdate) Icons.Rounded.SystemUpdate else Icons.Rounded.Verified,
                contentDescription = null,
                tint = if (state.hasUpdate) Color(0xFFFF3B30) else Color(0xFF34C759),
                modifier = Modifier.size(17.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (state.hasUpdate) "版本更新" else "当前版本",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
            )
            Text(
                when {
                    state.checking -> "正在检查更新…"
                    state.hasUpdate && release != null -> "点击覆盖安装新版本 v${release.version}"
                    else -> "点击检查在线更新 · 协议 v${protocolVersion.ifBlank { "1.0" }}"
                },
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }
        if (state.hasUpdate && release != null) {
            Text(
                "新版本 v${release.version}",
                fontSize = 11.5.sp,
                fontFamily = PlexMono,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFF3B30),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xFFFF3B30).copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        } else {
            Text(
                "v${state.currentVersionName}",
                fontSize = 13.sp,
                fontFamily = PlexMono,
                color = colors.textSecondary,
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
fun AboutUpdateActionButton(
    state: AppUpdateUiState,
    onClick: () -> Unit,
) {
    val colors = VibexTheme.colors
    val release = state.release
    val label = when {
        state.checking -> "正在检查更新…"
        state.hasUpdate && release != null -> "发现新版本 v${release.version} · 立即覆盖安装"
        else -> "检查在线更新"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (state.hasUpdate) colors.accent else colors.segmentedTrack)
            .clickable(enabled = !state.checking, onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (state.hasUpdate) Color.White else colors.accent,
        )
    }
}
