package dev.vibex.companion.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import dev.vibex.companion.CatalogAgent
import dev.vibex.companion.SessionConfigOption
import dev.vibex.companion.SessionMode
import dev.vibex.companion.app.data.ModelPreferencesStore
import dev.vibex.companion.app.ui.theme.VibexTheme
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ComposerMenu { Plus, Agent, Model, Effort }

private data class AttachedMediaChip(
    val id: String = UUID.randomUUID().toString(),
    val uriString: String,
    val label: String,
)

private val SparkColor = Color(red = 0.70f, green = 0.62f, blue = 1.0f)
private val AlertOrange = Color(0xFFFF9500)
private val RecordingRed = Color(0xFFFF3B30)

/**
 * 1:1 port of iOS `PromptBar` (`PromptBar.swift`).
 * Unified single-capsule floating composer (`[ + | Multiline BasicTextField | AgentIcon ⌄ | Mic | 36dp Circular ↑/■ ]`)
 * with composer-local zero-lag text editing, live speech dictation (`SpeechRecognizer`),
 * and `ThreadSettingsSheet` (`会话设置`) modal integration.
 */
@Composable
fun PromptBar(
    conversationKey: String = "default",
    externalDraftSeed: String = "",
    externalSeedVersion: Int = 0,
    onDraftEmptyChanged: ((Boolean) -> Unit)? = null,
    agentId: String,
    agent: CatalogAgent? = null,
    agents: List<CatalogAgent> = emptyList(),
    allowCrossAgentSwitch: Boolean = false,
    modes: List<SessionMode>,
    currentModeId: String,
    configOptions: List<SessionConfigOption>,
    modelPrefs: ModelPreferencesStore,
    isStreaming: Boolean,
    hasAlert: Boolean = false,
    enabled: Boolean = true,
    placeholder: String = "发消息或提问…",
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onSelectAgentAndModel: (agentId: String, modelKey: String, modelValue: String) -> Unit,
    onSelectMode: (String) -> Unit,
    onSelectOption: (key: String, value: String) -> Unit,
    onOpenFileBrowser: (() -> Unit)? = null,
    onCompact: (() -> Unit)? = null,
    onRefreshCatalog: (() -> Unit)? = null,
    openSettingsTrigger: Int = 0,
    modifier: Modifier = Modifier,
) {
    val colors = VibexTheme.colors
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // Composer-local text buffer avoids round-tripping every keystroke through ViewModel StateFlow
    var localText by remember(conversationKey) { mutableStateOf(externalDraftSeed) }
    var lastAppliedSeedVersion by remember(conversationKey) { mutableIntStateOf(0) }
    var attachedMedia by remember(conversationKey) { mutableStateOf<List<AttachedMediaChip>>(emptyList()) }
    var plusMenuExpanded by remember { mutableStateOf(false) }
    var showThreadSettingsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(externalDraftSeed, externalSeedVersion) {
        if (externalSeedVersion > lastAppliedSeedVersion) {
            lastAppliedSeedVersion = externalSeedVersion
            localText = externalDraftSeed
            onDraftEmptyChanged?.invoke(localText.isBlank() && attachedMedia.isEmpty())
        }
    }

    var lastSettingsTrigger by remember { mutableIntStateOf(openSettingsTrigger) }
    LaunchedEffect(openSettingsTrigger) {
        if (openSettingsTrigger != lastSettingsTrigger) {
            lastSettingsTrigger = openSettingsTrigger
            focusManager.clearFocus()
            onRefreshCatalog?.invoke()
            showThreadSettingsSheet = true
        }
    }

    // Photo picker for up to 4 images
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(4),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val added = uris.mapIndexed { idx, uri ->
                AttachedMediaChip(
                    uriString = uri.toString(),
                    label = "image-${attachedMedia.size + idx + 1}.jpg",
                )
            }
            attachedMedia = (attachedMedia + added).take(4)
            onDraftEmptyChanged?.invoke(localText.isBlank() && attachedMedia.isEmpty())
        }
    }

    // Live Speech Dictation State (`SpeechRecognizer`)
    var isRecording by remember { mutableStateOf(false) }
    var dictationError by remember { mutableStateOf<String?>(null) }
    var dictationPrefix by remember { mutableStateOf("") }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun showTempDictationError(msg: String) {
        dictationError = msg
        scope.launch {
            delay(2_800L)
            if (dictationError == msg) dictationError = null
        }
    }

    fun stopDictation() {
        runCatching {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        }
        speechRecognizer = null
        isRecording = false
    }

    fun startDictationNow() {
        stopDictation()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            showTempDictationError("当前设备暂不支持实时语音转写")
            return
        }
        val trimmedPrefix = localText.trim()
        dictationPrefix = if (trimmedPrefix.isEmpty()) "" else "$localText "
        dictationError = null

        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (recognizer == null) {
            showTempDictationError("无法初始化语音识别服务")
            return
        }
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isRecording = true
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onPartialResults(partialResults: Bundle?) {
                    val spoken = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (spoken.isNotEmpty()) {
                        localText = dictationPrefix + spoken
                        onDraftEmptyChanged?.invoke(false)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (spoken.isNotEmpty()) {
                        localText = dictationPrefix + spoken
                        onDraftEmptyChanged?.invoke(false)
                    }
                    stopDictation()
                }

                override fun onError(error: Int) {
                    val wasRecording = isRecording
                    stopDictation()
                    if (error != SpeechRecognizer.ERROR_CLIENT &&
                        error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                        !wasRecording
                    ) {
                        showTempDictationError("语音转写暂不可用，请检查麦克风或语音服务")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        runCatching {
            speechRecognizer = recognizer
            isRecording = true
            recognizer.startListening(intent)
        }.onFailure {
            stopDictation()
            showTempDictationError("无法启动麦克风录音")
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startDictationNow()
        } else {
            showTempDictationError("请在系统设置中允许麦克风权限")
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopDictation() }
    }

    val effortOption = remember(configOptions) { findSettingsEffortOption(configOptions) }
    val effortAtMax = remember(effortOption) {
        effortOption != null &&
            effortOption.choices.size > 1 &&
            effortOption.choices.last().value == effortOption.value
    }

    val canSend = enabled && (localText.trim().isNotEmpty() || attachedMedia.isNotEmpty())
    val armed = isStreaming || canSend
    val capsuleShape = RoundedCornerShape(26.dp)

    fun performSend() {
        if (isRecording) stopDictation()
        var composed = localText.trim()
        if (attachedMedia.isNotEmpty()) {
            val tags = attachedMedia.joinToString(" ") { "#${it.label}" }
            val marker = "[图片附件 ×${attachedMedia.size} $tags]"
            composed = if (composed.isEmpty()) marker else "$composed\n$marker"
        }
        if (composed.isEmpty() || !enabled) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        localText = ""
        attachedMedia = emptyList()
        onDraftEmptyChanged?.invoke(true)
        onSubmit(composed)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedVisibility(
            visible = dictationError != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            dictationError?.let { msg ->
                Text(
                    text = msg,
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (colors.isDark) 10.dp else 6.dp,
                    shape = capsuleShape,
                    ambientColor = Color.Black.copy(alpha = if (colors.isDark) 0.24f else 0.06f),
                    spotColor = Color.Black.copy(alpha = if (colors.isDark) 0.30f else 0.09f),
                )
                .clip(capsuleShape)
                .background(colors.card)
                .then(
                    if (effortAtMax) {
                        Modifier.background(
                            Brush.radialGradient(
                                colors = listOf(
                                    SparkColor.copy(alpha = 0.22f),
                                    Color.Transparent,
                                ),
                                center = Offset(80f, 180f),
                                radius = 420f,
                            ),
                        )
                    } else {
                        Modifier
                    },
                )
                .border(
                    width = 0.75.dp,
                    color = if (effortAtMax) SparkColor.copy(alpha = 0.36f) else colors.stroke,
                    shape = capsuleShape,
                )
                .padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (attachedMedia.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachedMedia.forEach { item ->
                        Box(modifier = Modifier.size(54.dp)) {
                            AsyncImage(
                                model = item.uriString,
                                contentDescription = item.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(0.6.dp, colors.stroke, RoundedCornerShape(12.dp)),
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 3.dp, y = (-3).dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.68f))
                                    .clickable {
                                        attachedMedia = attachedMedia.filterNot { it.id == item.id }
                                        onDraftEmptyChanged?.invoke(localText.isBlank() && attachedMedia.isEmpty())
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "移除图片",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // 1. `+` Attachment & Quick Options Menu
                Box {
                    Box(
                        modifier = Modifier
                            .size(width = 34.dp, height = 36.dp)
                            .clip(CircleShape)
                            .clickable {
                                focusManager.clearFocus()
                                plusMenuExpanded = true
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "添加附件或选项",
                            tint = colors.textPrimary.copy(alpha = 0.82f),
                            modifier = Modifier.size(20.dp),
                        )
                        if (hasAlert) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = (-3).dp, y = 4.dp)
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(AlertOrange),
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = plusMenuExpanded,
                        onDismissRequest = { plusMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("照片图库", fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                plusMenuExpanded = false
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        )
                        if (onOpenFileBrowser != null) {
                            DropdownMenuItem(
                                text = { Text("浏览项目文件", fontSize = 14.sp) },
                                leadingIcon = { Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    plusMenuExpanded = false
                                    onOpenFileBrowser()
                                },
                            )
                        }
                        if (onCompact != null) {
                            DropdownMenuItem(
                                text = { Text("压缩上下文 (/compact)", fontSize = 14.sp) },
                                leadingIcon = { Icon(Icons.Rounded.Compress, null, modifier = Modifier.size(18.dp)) },
                                onClick = {
                                    plusMenuExpanded = false
                                    onCompact()
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("会话与模型设置", fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                plusMenuExpanded = false
                                onRefreshCatalog?.invoke()
                                showThreadSettingsSheet = true
                            },
                        )
                    }
                }

                // 2. Multiline Text Input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (localText.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 15.5.sp,
                            color = colors.textTertiary,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = localText,
                        onValueChange = { next ->
                            localText = next
                            onDraftEmptyChanged?.invoke(next.isBlank() && attachedMedia.isEmpty())
                        },
                        enabled = enabled,
                        maxLines = 5,
                        textStyle = TextStyle(
                            fontSize = 15.5.sp,
                            lineHeight = 21.sp,
                            color = colors.textPrimary,
                        ),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 3. Thread Settings Trigger (`AgentIcon ⌄`)
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            focusManager.clearFocus()
                            onRefreshCatalog?.invoke()
                            showThreadSettingsSheet = true
                        }
                        .padding(horizontal = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    AgentAvatar(
                        agentId = agentId,
                        size = 17.dp,
                        iconSvg = agent?.iconSvg,
                        displayName = agent?.displayName.orEmpty(),
                    )
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "会话设置",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                }

                // 4. Live Speech Dictation Button (`Mic` / Pulsing Waveform)
                val pulseTransition = rememberInfiniteTransition(label = "micPulse")
                val micScale by pulseTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isRecording) 1.14f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 560),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "micScale",
                )
                Box(
                    modifier = Modifier
                        .size(width = 32.dp, height = 36.dp)
                        .clip(CircleShape)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isRecording) {
                                stopDictation()
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) startDictationNow()
                                else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
                        contentDescription = if (isRecording) "停止语音输入" else "语音输入",
                        tint = if (isRecording) RecordingRed else colors.textPrimary.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(if (isRecording) 20.dp else 18.dp)
                            .scale(if (isRecording) micScale else 1f),
                    )
                }

                // 5. Circular Monochrome Send / Stop Button (`36dp`)
                CircularSendStopButton(
                    armed = armed,
                    running = isStreaming,
                    onClick = {
                        if (isStreaming) onStop() else performSend()
                    },
                )
            }
        }
    }

    if (showThreadSettingsSheet) {
        ThreadSettingsSheet(
            agentId = agentId,
            agent = agent,
            agents = agents,
            allowCrossAgentSwitch = allowCrossAgentSwitch,
            modes = modes,
            currentModeId = currentModeId,
            configOptions = configOptions,
            modelPrefs = modelPrefs,
            onSelectAgentAndModel = onSelectAgentAndModel,
            onSelectMode = onSelectMode,
            onSelectOption = onSelectOption,
            onRefreshCatalog = onRefreshCatalog,
            onDismiss = { showThreadSettingsSheet = false },
        )
    }
}

@Composable
private fun CircularSendStopButton(
    armed: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    val colors = VibexTheme.colors
    val morph by animateFloatAsState(
        targetValue = if (running) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "sendMorph",
    )
    val bg = if (armed) colors.textPrimary else colors.textPrimary.copy(alpha = 0.08f)
    val fg = if (armed) colors.bg else colors.textPrimary.copy(alpha = 0.36f)

    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = armed, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(15.dp)) {
            val scale = size.width / 24f
            val arrow = arrayOf(
                12f to 4.5f,
                18.5f to 11f,
                14.25f to 11f,
                14.25f to 19.5f,
                9.75f to 19.5f,
                9.75f to 11f,
                5.5f to 11f,
            )
            val square = arrayOf(
                12f to 6f,
                18f to 6f,
                18f to 12f,
                18f to 18f,
                6f to 18f,
                6f to 12f,
                6f to 6f,
            )
            val path = Path().apply {
                for (i in arrow.indices) {
                    val (ax, ay) = arrow[i]
                    val (bx, by) = square[i]
                    val px = (ax + (bx - ax) * morph) * scale
                    val py = (ay + (by - ay) * morph) * scale
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            drawPath(path = path, color = fg)
            drawPath(
                path = path,
                color = fg,
                style = Stroke(width = 1.3f * scale, join = StrokeJoin.Round),
            )
        }
    }
}
