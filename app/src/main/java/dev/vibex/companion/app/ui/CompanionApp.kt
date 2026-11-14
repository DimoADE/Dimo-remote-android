package dev.vibex.companion.app.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import dev.vibex.companion.app.update.AppUpdateManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.KeyOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import dev.vibex.companion.ConnectionState
import dev.vibex.companion.NoticeSeverity
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.ConnectedSasNotice
import dev.vibex.companion.app.DesktopConnectTarget
import dev.vibex.companion.app.Route
import dev.vibex.companion.app.UiState
import dev.vibex.companion.app.ui.theme.PlexMono
import dev.vibex.companion.app.ui.theme.VibexTheme

@Composable
fun CompanionApp(state: UiState, model: CompanionModel) {
    SplashGate { CompanionShell(state, model) }
}

@Composable
private fun CompanionShell(state: UiState, model: CompanionModel) {
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(model::pairFromRaw)
    }
    val launchScanner = {
        scan.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("对准桌面端二维码即可自动识别连接"),
        )
    }

    var inNestedCollabChat by remember { mutableStateOf(false) }

    val isTimeline = state.route is Route.Timeline
    val isNewThread = state.route is Route.NewThread
    val fullscreen = isTimeline || isNewThread
    val isPairModal = state.route is Route.ManualPair
    val settingsSub = (state.route as? Route.Settings)?.section != null
    val inCollabChat = state.route is Route.Collaboration && state.selectedThread != null

    BackHandler(enabled = fullscreen || isPairModal || settingsSub || inCollabChat || state.route is Route.Folders) {
        when {
            isPairModal -> model.go(Route.Conversations)
            fullscreen -> model.go(Route.Conversations)
            settingsSub -> model.go(Route.Settings())
            inCollabChat -> model.closeCollaborationThread()
            state.route is Route.Folders -> model.go(Route.Conversations)
        }
    }

    val colors = VibexTheme.colors
    val context = LocalContext.current
    val updateManager = remember(context) { AppUpdateManager.getInstance(context) }
    val updateState by updateManager.state.collectAsState()

    LaunchedEffect(updateState.statusToast) {
        val toastMsg = updateState.statusToast ?: return@LaunchedEffect
        Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
        updateManager.consumeStatusToast()
    }

    AppUpdateDialog(
        state = updateState,
        onConfirmUpdate = { updateManager.startDownloadAndInstall() },
        onDismiss = { updateManager.dismissDialog() },
    )

    state.pendingCloudFallbackOrigin?.let {
        val hostName = state.pendingCloudFallbackHostName ?: "VibeX 桌面端"
        AlertDialog(
            onDismissRequest = { model.cancelSwitchToCloudFallback() },
            containerColor = colors.card,
            title = {
                Text(
                    "局域网已断开，是否切换至云服务？",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
            },
            text = {
                Text(
                    "当前与电脑「$hostName」的局域网直连已断开，但检测到可通过「VibeX 云服务 · Beta」继续连接。是否切换至云服务？",
                    fontSize = 14.sp,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { model.confirmSwitchToCloudFallback() }) {
                    Text(
                        "切换至云服务",
                        fontWeight = FontWeight.Bold,
                        color = colors.accent,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { model.cancelSwitchToCloudFallback() }) {
                    Text(
                        "保持局域网",
                        color = colors.textSecondary,
                    )
                }
            },
        )
    }

    state.pendingConflictDeviceName?.let { existingDeviceName ->
        AlertDialog(
            onDismissRequest = { model.cancelReplaceMobileDevice() },
            containerColor = colors.card,
            text = {
                Text(
                    "这台电脑已连接手机「$existingDeviceName」，要替换吗？",
                    fontSize = 15.sp,
                    color = colors.textPrimary,
                )
            },
            confirmButton = {
                TextButton(onClick = { model.confirmReplaceMobileDevice() }) {
                    Text("替换", fontWeight = FontWeight.Bold, color = colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { model.cancelReplaceMobileDevice() }) {
                    Text("取消", color = colors.textSecondary)
                }
            },
        )
    }

    // The tab shell keeps showing the page it was on while a conversation, new-thread page, or
    // pairing sheet slides over it, so the page underneath never changes mid-transition.
    val tabRouteHolder = remember { TabRouteHolder() }
    if (!fullscreen && !isPairModal) tabRouteHolder.route = state.route
    val tabRoute = tabRouteHolder.route

    val hasServer = state.profiles.isNotEmpty()
    val authExpired = state.connection == ConnectionState.AuthRequired
    val manualConnectNeeded = hasServer && !state.autoConnect && state.connection == ConnectionState.Offline
    val showConnectGate = !hasServer || authExpired || manualConnectNeeded

    val activeOpenConvId = (state.route as? Route.Timeline)?.conversationId
    val inlineToast = state.activeAttentionToast?.takeIf { toast ->
        toast.conversationId != activeOpenConvId
    }
    val hostNotices: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = state.connectedSasNotice != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            state.connectedSasNotice?.let { notice ->
                ConnectedSasCapsule(
                    notice = notice,
                    onDismiss = model::dismissConnectedSasNotice,
                )
            }
        }
        CompanionToastOverlay(
            toast = inlineToast,
            actionBusy = state.attentionActionBusy,
            actionError = state.attentionActionError,
            onDismiss = { model.dismissAttentionToast() },
            onOpenConversation = { convId -> model.openConversation(convId) },
            onRespondPermission = { convId, permId, optId ->
                model.respondToastPermission(convId, permId, optId)
            },
            onRespondQuestion = { convId, question, choice, fallbackText ->
                model.respondToastQuestion(convId, question, choice, fallbackText)
            },
            onSendQuickReply = { toast, text ->
                model.sendToastQuickReply(toast, text)
            },
        )
        state.error?.let { HostBanner(it, hostBannerSeverity(it)) { model.dismissNotice() } }
        state.notice?.let { HostBanner(it, NoticeSeverity.Info) { model.dismissNotice() } }
    }

    val requiresServer: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (showConnectGate) {
            DesktopEmptyState(state = state, model = model, authExpired = authExpired)
        } else {
            content()
        }
    }

    val layer = when (val route = state.route) {
        Route.ManualPair -> ShellLayer.Pairing
        else -> if (state.showsLogin) ShellLayer.Login else when (route) {
            is Route.Timeline -> ShellLayer.Conversation(route.conversationId)
            Route.NewThread -> ShellLayer.NewThread
            else -> ShellLayer.Tabs
        }
    }

    state.desktopConnect?.let { sheet ->
        DesktopConnectDialog(
            state = state,
            sheet = sheet,
            model = model,
            onPairWithCode = {
                model.dismissDesktopConnect()
                model.go(Route.ManualPair)
            },
        )
    }

    val shouldShowFloatingDock = !settingsSub &&
        !(tabRoute is Route.Collaboration && (state.selectedThread != null || inNestedCollabChat))

    GlowBackground {
        AnimatedContent(
            targetState = layer,
            transitionSpec = { layerTransition(initialState, targetState) },
            modifier = Modifier.fillMaxSize(),
            label = "shellLayer",
        ) { shownLayer ->
            when (shownLayer) {
                ShellLayer.Login -> Box(Modifier.fillMaxSize()) {
                    LoginScreen(state = state, model = model, onPairWithCode = { model.go(Route.ManualPair) })
                    Column(Modifier.align(Alignment.TopCenter).statusBarsPadding()) {
                        state.error?.let { HostBanner(it, hostBannerSeverity(it)) { model.dismissNotice() } }
                    }
                }

                ShellLayer.Pairing -> PairingCodeScreen(
                    state = state,
                    model = model,
                    onScan = launchScanner,
                    onDismiss = { model.go(Route.Conversations) },
                )

                ShellLayer.NewThread -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.bg)
                        .statusBarsPadding(),
                ) {
                    NewThreadScreen(state = state, model = model)
                    Column(
                        Modifier.align(Alignment.TopCenter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        hostNotices()
                    }
                }

                is ShellLayer.Conversation -> Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.bg)
                        .statusBarsPadding(),
                ) {
                    TimelinePane(
                        state = state,
                        model = model,
                        conversationId = shownLayer.conversationId,
                        onBack = { model.go(Route.Conversations) },
                    )
                    Column(
                        Modifier.align(Alignment.TopCenter),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        hostNotices()
                    }
                }

                ShellLayer.Tabs -> Box(Modifier.fillMaxSize().background(colors.bg)) {
                    AnimatedContent(
                        targetState = tabRoute,
                        transitionSpec = { tabTransition(initialState, targetState) },
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                        label = "tabRoute",
                    ) { route ->
                        Box(Modifier.fillMaxSize().background(colors.bg)) {
                            when (route) {
                                is Route.Settings -> SettingsScreen(
                                    state = state,
                                    model = model,
                                    section = route.section,
                                    onOpen = { model.go(Route.Settings(it)) },
                                    onBack = { model.go(Route.Settings()) },
                                    onScan = launchScanner,
                                    onManualPair = { model.go(Route.ManualPair) },
                                )
                                Route.Folders -> requiresServer { FoldersScreen(state, model) }
                                Route.Status -> requiresServer { StatusScreen(state, model) }
                                Route.Collaboration -> requiresServer {
                                    CollaborationScreen(
                                        state = state,
                                        model = model,
                                        onActiveChatChanged = { inNestedCollabChat = it },
                                    )
                                }
                                else -> requiresServer { ConversationList(state, model) }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = shouldShowFloatingDock,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    ) {
                        FloatingWorkspaceCapsuleDock(
                            selected = tabRoute.destination,
                            collabUnreadCount = state.collabUnreadCount,
                            onSelect = { dest -> model.navigate(dest) },
                            onNewThread = { model.openNewChatLanding() },
                        )
                    }

                    Column(
                        Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        hostNotices()
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedSasCapsule(
    notice: ConnectedSasNotice,
    onDismiss: () -> Unit,
) {
    val colors = VibexTheme.colors
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.14f))
            .clip(CircleShape)
            .background(colors.card)
            .border(0.75.dp, colors.pass.copy(alpha = 0.42f), CircleShape)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = colors.pass,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "已连接 ${notice.hostName}",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "连接码 ${notice.sasCode}",
            style = TextStyle(
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = PlexMono,
                fontFeatureSettings = "tnum",
                color = colors.accent,
            ),
        )
    }
}

private class TabRouteHolder {
    var route: Route = Route.Conversations
}

private sealed interface ShellLayer {
    val elevation: Float

    data object Login : ShellLayer {
        override val elevation = 0f
    }

    data object Tabs : ShellLayer {
        override val elevation = 0f
    }

    data object NewThread : ShellLayer {
        override val elevation = 1f
    }

    data class Conversation(val conversationId: String) : ShellLayer {
        override val elevation = 1f
    }

    data object Pairing : ShellLayer {
        override val elevation = 2f
    }
}

private fun layerTransition(from: ShellLayer, to: ShellLayer): ContentTransform = when {
    to is ShellLayer.Pairing -> NavMotion.present(to.elevation)
    from is ShellLayer.Pairing -> NavMotion.dismiss(to.elevation)
    to.elevation > from.elevation -> NavMotion.push(to.elevation)
    to.elevation < from.elevation -> NavMotion.pop(to.elevation)
    else -> NavMotion.crossfade(to.elevation)
}

private val Route.settingsDepth: Float
    get() = if (this is Route.Settings && section != null) 1f else 0f

private fun tabTransition(from: Route, to: Route): ContentTransform {
    val withinSettings = from is Route.Settings && to is Route.Settings
    return when {
        withinSettings && to.settingsDepth > from.settingsDepth -> NavMotion.push(to.settingsDepth)
        withinSettings && to.settingsDepth < from.settingsDepth -> NavMotion.pop(to.settingsDepth)
        else -> NavMotion.crossfade(to.settingsDepth)
    }
}

@Composable
private fun DesktopEmptyState(
    state: UiState,
    model: CompanionModel,
    authExpired: Boolean,
) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = BOTTOM_DOCK_CLEARANCE),
        contentAlignment = Alignment.Center,
    ) {
        if (state.isAutoConnectingInBackground || (state.accountSignedIn && (state.accountLoading || state.accountSyncingHosts))) {
            LoadingState(
                state.autoConnectingHostName?.let { "正在连接 $it…" } ?: "正在同步你的电脑…",
            )
        } else {
            EmptyState(
                icon = if (authExpired) Icons.Rounded.KeyOff else Icons.Rounded.Computer,
                title = if (authExpired) "连接凭据已失效" else "未连接电脑",
                action = "连接电脑",
                onAction = {
                    when {
                        state.accountSignedIn && state.accountDesktops.size == 1 -> {
                            model.connectDesktopTarget(DesktopConnectTarget.Account(state.accountDesktops.single()))
                        }
                        state.accountSignedIn || state.discoveredHosts.any { !it.isCloudService } -> {
                            model.openDesktopConnect()
                        }
                        state.profiles.isNotEmpty() && !authExpired -> {
                            model.connectSelected()
                        }
                        else -> {
                            model.go(Route.ManualPair)
                        }
                    }
                },
            )
        }
    }
}
