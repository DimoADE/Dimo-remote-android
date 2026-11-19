package dev.vibex.companion.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.vibex.companion.CollabBotAvatarConfig
import dev.vibex.companion.CollabCard
import dev.vibex.companion.CollabEmbeddedCard
import dev.vibex.companion.CollabEmbeddedHandoff
import dev.vibex.companion.CollabScheduledMessage
import dev.vibex.companion.CollabSpaceMember
import dev.vibex.companion.CollabSpaceMessage
import dev.vibex.companion.applyMention
import dev.vibex.companion.buildCollabMessageLookup
import dev.vibex.companion.collabMessagePreviewText
import dev.vibex.companion.countCollabMessageLines
import dev.vibex.companion.mergeCollabMessages
import dev.vibex.companion.mentionTokenAt
import dev.vibex.companion.parseCollabStructuredMessage
import dev.vibex.companion.reconcileCollabStreamingDrafts
import dev.vibex.companion.app.CompanionModel
import dev.vibex.companion.app.ui.theme.PlexMono
import dev.vibex.companion.app.ui.theme.VibexTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val CollabUserBubbleBlue = Color(0xFF2563EB)

@Stable
class CollabConversationController(
    private val spaceId: String,
    private val threadId: String,
    private val isGroup: Boolean,
    private val model: CompanionModel,
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val nearBottomState: State<Boolean>,
) {
    var messages by mutableStateOf<List<CollabSpaceMessage>>(emptyList())
    var scheduled by mutableStateOf<List<CollabScheduledMessage>>(emptyList())
    var cards by mutableStateOf<List<CollabCard>>(emptyList())
    var spaceMembers by mutableStateOf<List<CollabSpaceMember>>(emptyList())
    var hasMoreBefore by mutableStateOf(false)
    var oldestSeq by mutableStateOf<Long?>(null)
    var loadingEarlier by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var unreadNewCount by mutableIntStateOf(0)
    var initialScrollDone by mutableStateOf(false)
    var readerMessage by mutableStateOf<CollabSpaceMessage?>(null)
    val expandedMessageIds = mutableStateMapOf<String, Boolean>()
    private var lastMarkedReadSeq: Int = 0

    val isNearBottom: Boolean get() = nearBottomState.value

    fun bottomItemIndex(msgCount: Int = messages.size, withBanner: Boolean = hasMoreBefore): Int =
        ((if (withBanner) 1 else 0) + msgCount - 1).coerceAtLeast(0)

    fun markLatestConfirmedRead(list: List<CollabSpaceMessage> = messages) {
        val maxSeq = list.asSequence().filter { !it.isStreamingDraft && it.seq > 0 }.maxOfOrNull { it.seq } ?: return
        if (maxSeq > lastMarkedReadSeq) {
            lastMarkedReadSeq = maxSeq
            scope.launch { runCatching { model.markCollabThreadRead(threadId, maxSeq, true) } }
        }
    }

    fun jumpToBottom() {
        unreadNewCount = 0
        markLatestConfirmedRead(messages)
        if (messages.isNotEmpty()) {
            scope.launch { runCatching { listState.animateScrollToItem(bottomItemIndex()) } }
        }
    }

    suspend fun loadInitial() {
        loading = true
        initialScrollDone = false
        runCatching { model.fetchCollabChatState(spaceId, isGroup, limit = 80) }
            .onSuccess { (page, members) ->
                val initial = mergeCollabMessages(emptyList(), page.messages, isIncremental = false)
                messages = initial
                scheduled = page.scheduled
                cards = page.cards
                hasMoreBefore = page.hasMoreBefore
                oldestSeq = page.oldestSeq ?: initial.asSequence()
                    .filter { !it.isStreamingDraft && it.seq > 0 }
                    .minOfOrNull { it.seq.toLong() }
                if (isGroup) spaceMembers = members
                markLatestConfirmedRead(initial)
                loading = false
                if (initial.isNotEmpty()) {
                    runCatching { listState.scrollToItem(bottomItemIndex(initial.size, page.hasMoreBefore)) }
                }
                initialScrollDone = true
            }
            .onFailure {
                loading = false
                initialScrollDone = true
            }
    }

    suspend fun syncIncrementalTail(refreshMembers: Boolean = false, forceScrollToBottom: Boolean = false) {
        val current = messages
        val isDirectThread = !isGroup || spaceId.startsWith("direct:")
        val latestConfirmedSeq = current.asSequence()
            .filter { !it.isStreamingDraft && it.seq > 0 }
            .maxOfOrNull { it.seq.toLong() } ?: 0L
        val earliestActiveReceiptSeq = current.asSequence()
            .filter { !it.isStreamingDraft && it.seq > 0 && it.receipts.any { r -> r.isProcessing } }
            .minOfOrNull { it.seq.toLong() }
        val hasStreaming = current.any { it.isStreamingDraft }

        val pageResult = runCatching {
            when {
                isDirectThread -> model.fetchCollabMessagesPage(spaceId = spaceId, limit = 40, latest = true)
                latestConfirmedSeq <= 0L -> model.fetchCollabMessagesPage(spaceId = spaceId, limit = 80, latest = true)
                else -> {
                    val syncCursorSeq = if (earliestActiveReceiptSeq != null) {
                        (earliestActiveReceiptSeq - 1L).takeIf { it > 0L }
                    } else {
                        latestConfirmedSeq
                    }
                    model.fetchCollabMessagesPage(
                        spaceId = spaceId,
                        limit = 80,
                        sinceSeq = syncCursorSeq,
                        latest = syncCursorSeq == null,
                    )
                }
            }
        }
        if (refreshMembers && isGroup) {
            runCatching { model.fetchCollabSpaceMembersOnly(spaceId) }.onSuccess { spaceMembers = it }
        }
        val page = pageResult.getOrNull() ?: return

        val seenKeys = HashSet<String>(current.size * 4)
        for (prev in current) {
            seenKeys += "id:${prev.id}"
            prev.rawId?.takeIf { it.isNotBlank() }?.let { seenKeys += "raw:$it" }
            prev.operationId?.takeIf { it.isNotBlank() }?.let { seenKeys += "op:$it" }
            if (!prev.isStreamingDraft && prev.seq > 0) seenKeys += "seq:${prev.seq}"
            prev.effectiveTurnId?.let { seenKeys += "turn:$it" }
            if (prev.isStreamingDraft && prev.envelope.sender.isNotBlank()) {
                seenKeys += "draft-sender:${prev.envelope.sender.lowercase()}"
            }
        }
        val prevTailContent = current.lastOrNull()?.envelope?.content

        var merged = mergeCollabMessages(current, page.messages, isIncremental = true)
        if (isDirectThread || hasStreaming || latestConfirmedSeq <= 0L) {
            merged = reconcileCollabStreamingDrafts(merged, page.messages)
        }
        val newCount = merged.count { msg ->
            val inTail = msg.isStreamingDraft || msg.seq.toLong() > latestConfirmedSeq
            inTail && !(
                ("id:${msg.id}" in seenKeys) ||
                    (msg.rawId?.takeIf { it.isNotBlank() }?.let { "raw:$it" in seenKeys } == true) ||
                    (msg.operationId?.takeIf { it.isNotBlank() }?.let { "op:$it" in seenKeys } == true) ||
                    (!msg.isStreamingDraft && msg.seq > 0 && "seq:${msg.seq}" in seenKeys) ||
                    (msg.effectiveTurnId?.let { "turn:$it" in seenKeys } == true) ||
                    (msg.envelope.sender.isNotBlank() && "draft-sender:${msg.envelope.sender.lowercase()}" in seenKeys)
                )
        }
        val streamingUpdated = merged.lastOrNull()?.isStreamingDraft == true &&
            merged.lastOrNull()?.envelope?.content != prevTailContent

        val wasNearBottom = isNearBottom
        val nextHasMore = if (current.isEmpty()) page.hasMoreBefore else hasMoreBefore
        if (current.isEmpty()) {
            hasMoreBefore = page.hasMoreBefore
            oldestSeq = page.oldestSeq ?: merged.asSequence()
                .filter { !it.isStreamingDraft && it.seq > 0 }
                .minOfOrNull { it.seq.toLong() }
        }
        messages = merged
        scheduled = page.scheduled
        cards = page.cards

        if (forceScrollToBottom || wasNearBottom) {
            unreadNewCount = 0
            markLatestConfirmedRead(merged)
            if (merged.isNotEmpty()) {
                val targetBottom = bottomItemIndex(merged.size, nextHasMore)
                if (forceScrollToBottom || newCount > 0) {
                    runCatching { listState.animateScrollToItem(targetBottom) }
                } else if (streamingUpdated) {
                    runCatching { listState.scrollToItem(targetBottom) }
                }
            }
        } else if (newCount > 0) {
            unreadNewCount += newCount
        }
    }

    fun refreshNow(refreshMembers: Boolean = false, forceScrollToBottom: Boolean = false) {
        scope.launch { syncIncrementalTail(refreshMembers, forceScrollToBottom) }
    }

    fun loadEarlierMessages() {
        if (!hasMoreBefore || loadingEarlier) return
        val targetBeforeSeq = oldestSeq ?: messages.asSequence()
            .filter { !it.isStreamingDraft && it.seq > 0 }
            .minOfOrNull { it.seq.toLong() } ?: return
        loadingEarlier = true
        val prevFirstIndex = listState.firstVisibleItemIndex
        val prevOffset = listState.firstVisibleItemScrollOffset
        val prevSize = messages.size
        val wasHasMore = hasMoreBefore
        scope.launch {
            runCatching {
                model.fetchCollabMessagesPage(spaceId = spaceId, limit = 80, beforeSeq = targetBeforeSeq, latest = false)
            }.onSuccess { earlierPage ->
                val merged = mergeCollabMessages(messages, earlierPage.messages, isIncremental = true)
                val added = (merged.size - prevSize).coerceAtLeast(0)
                val nextHasMore = earlierPage.hasMoreBefore && earlierPage.messages.isNotEmpty() && added > 0
                messages = merged
                hasMoreBefore = nextHasMore
                oldestSeq = earlierPage.oldestSeq ?: merged.asSequence()
                    .filter { !it.isStreamingDraft && it.seq > 0 }
                    .minOfOrNull { it.seq.toLong() }
                if (added > 0) {
                    val prevHeader = if (wasHasMore) 1 else 0
                    val nextHeader = if (nextHasMore) 1 else 0
                    val anchorMsgIdx = (prevFirstIndex - prevHeader).coerceAtLeast(0)
                    val targetIdx = (nextHeader + anchorMsgIdx + added)
                        .coerceIn(0, (nextHeader + merged.size - 1).coerceAtLeast(0))
                    runCatching {
                        listState.scrollToItem(targetIdx, if (prevFirstIndex >= prevHeader) prevOffset else 0)
                    }
                }
            }
            loadingEarlier = false
        }
    }
}

@Composable
fun rememberCollabConversationController(
    spaceId: String,
    threadId: String,
    isGroup: Boolean,
    model: CompanionModel,
    listState: LazyListState,
): Pair<CollabConversationController, Map<String, CollabSpaceMessage>> {
    val scope = rememberCoroutineScope()
    val nearBottomState = remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            if (total == 0) true else (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= total - 3
        }
    }
    val controller = remember(spaceId, threadId, isGroup) {
        CollabConversationController(spaceId, threadId, isGroup, model, listState, scope, nearBottomState)
    }

    LaunchedEffect(controller.isNearBottom) {
        if (controller.isNearBottom && controller.unreadNewCount > 0) {
            controller.unreadNewCount = 0
            controller.markLatestConfirmedRead()
        }
    }

    LaunchedEffect(spaceId) {
        controller.loadInitial()
        var pollTick = 0
        while (isActive) {
            delay(2_500L)
            pollTick += 1
            controller.syncIncrementalTail(refreshMembers = isGroup && pollTick % 10 == 0)
        }
    }

    LaunchedEffect(spaceId, listState) {
        var lastIdx = Int.MAX_VALUE
        var lastOffset = Int.MAX_VALUE
        snapshotFlow {
            Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress)
        }.collect { (idx, offset, scrolling) ->
            val movedUp = idx < lastIdx || (idx == lastIdx && offset < lastOffset)
            lastIdx = idx
            lastOffset = offset
            if (controller.initialScrollDone && scrolling && movedUp && idx <= 1 && controller.hasMoreBefore && !controller.loadingEarlier) {
                controller.loadEarlierMessages()
            }
        }
    }

    val lookup = remember(controller.messages) { buildCollabMessageLookup(controller.messages) }
    return controller to lookup
}

private fun takeCollapsedPreviewSlice(text: String, maxLines: Int = 24, maxChars: Int = 1_400): String {
    val capped = if (text.length <= maxChars) text else text.take(maxChars)
    val lines = capped.lines()
    return if (lines.size <= maxLines) capped else lines.take(maxLines).joinToString("\n")
}

@Composable
fun CollabRichBubbleContent(
    message: CollabSpaceMessage,
    knownCardsById: Map<String, CollabCard>,
    bubbleBackground: Color,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenFullReader: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VibexTheme.colors
    val isOwn = message.isFromUser
    val parsed = remember(message.envelope.content) { parseCollabStructuredMessage(message.envelope.content) }
    val canCollapse = !message.streaming && parsed.isCollapsible
    val collapsed = canCollapse && !isExpanded

    Column(modifier = modifier) {
        Box(modifier = if (collapsed) Modifier.heightIn(max = 220.dp).clipToBounds() else Modifier) {
            Column(
                modifier = Modifier.padding(start = 13.dp, end = 13.dp, top = 9.dp, bottom = if (canCollapse) 6.dp else 9.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                parsed.handoffs.forEach { CollabEmbeddedHandoffCard(it, isOwn) }
                parsed.cards.forEach { CollabEmbeddedCardBlock(it, knownCardsById[it.id], isOwn) }

                val displayText = if (collapsed) takeCollapsedPreviewSlice(parsed.cleanText) else parsed.cleanText
                if (displayText.isNotEmpty()) {
                    when {
                        message.streaming -> Text(
                            text = displayText,
                            color = if (isOwn) Color.White else c.textPrimary,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                        )
                        isOwn -> CollabOwnBubbleMarkdown(displayText)
                        else -> CollabBotBubbleMarkdown(displayText)
                    }
                } else if (message.streaming && !parsed.hasStructuredBlocks) {
                    Text("思考中…", color = if (isOwn) Color.White.copy(alpha = 0.85f) else c.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }

            if (collapsed) {
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(44.dp)
                        .background(Brush.verticalGradient(listOf(bubbleBackground.copy(alpha = 0f), bubbleBackground))),
                )
            }
        }

        if (canCollapse) {
            val actionTint = if (isOwn) Color.White else CollabUserBubbleBlue
            val secondaryTint = if (isOwn) Color.White.copy(alpha = 0.88f) else c.textSecondary
            HorizontalDivider(color = if (isOwn) Color.White.copy(alpha = 0.18f) else c.stroke, thickness = 0.5.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onToggleExpand).padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, null, tint = actionTint, modifier = Modifier.size(15.dp))
                    Text(
                        text = if (collapsed) "展开全文 · ${parsed.lineCount} 行" else "收起",
                        style = TextStyle(color = actionTint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    )
                }
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpenFullReader).padding(horizontal = 4.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Outlined.OpenInFull, null, tint = secondaryTint, modifier = Modifier.size(13.dp))
                    Text("全屏阅读", color = secondaryTint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun CollabBotBubbleMarkdown(raw: String) {
    val blocks = remember(raw) { parseMarkdownBlocks(raw) }
    val fillWidth = remember(raw, blocks) {
        blocks.size > 1 || raw.length > 38 || raw.contains('\n') || blocks.any { it !is MarkdownBlock.Paragraph }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) SingleBlockView(block = block, fillWidth = fillWidth)
    }
}

@Composable
private fun CollabOwnBubbleMarkdown(raw: String) {
    val blocks = remember(raw) { parseMarkdownBlocks(raw) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Code -> Box(
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFF172554))
                        .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(block.code, color = Color(0xFFF8FAFC), fontFamily = PlexMono, fontSize = 12.sp, lineHeight = 18.sp)
                }
                is MarkdownBlock.Heading -> Text(
                    text = rememberOwnInlineMarkdown(block.text),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (block.level <= 2) 16.sp else 14.5.sp,
                    lineHeight = 22.sp,
                )
                is MarkdownBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("•", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                            Text(rememberOwnInlineMarkdown(item), color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
                is MarkdownBlock.NumberedList -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEach { (marker, item) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(marker, style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontFeatureSettings = "tnum"))
                            Text(rememberOwnInlineMarkdown(item), color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                        }
                    }
                }
                is MarkdownBlock.Quote -> Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.width(2.5.dp).height(18.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.6f)))
                    Text(rememberOwnInlineMarkdown(block.text), color = Color.White.copy(alpha = 0.9f), fontStyle = FontStyle.Italic, fontSize = 13.5.sp, lineHeight = 20.sp)
                }
                MarkdownBlock.Rule -> HorizontalDivider(color = Color.White.copy(alpha = 0.24f), thickness = 0.5.dp)
                is MarkdownBlock.Table -> Text(
                    text = (listOf(block.header) + block.rows).joinToString("\n") { it.joinToString(" | ") },
                    color = Color.White,
                    fontFamily = PlexMono,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                is MarkdownBlock.Paragraph -> Text(rememberOwnInlineMarkdown(block.text), color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun rememberOwnInlineMarkdown(raw: String): AnnotatedString = remember(raw) {
    buildAnnotatedString {
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                ch == '`' && raw.indexOf('`', i + 1) > i -> {
                    val end = raw.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = PlexMono, background = Color.White.copy(alpha = 0.18f), color = Color.White)) {
                        append(" ${raw.substring(i + 1, end)} ")
                    }
                    i = end + 1
                }
                (ch == '*' || ch == '_') && i + 1 < raw.length && raw[i + 1] == ch && raw.indexOf("$ch$ch", i + 2) > i + 1 -> {
                    val end = raw.indexOf("$ch$ch", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.substring(i + 2, end)) }
                    i = end + 2
                }
                raw.startsWith("~~", i) && raw.indexOf("~~", i + 2) > i + 1 -> {
                    val end = raw.indexOf("~~", i + 2)
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(raw.substring(i + 2, end)) }
                    i = end + 2
                }
                else -> {
                    append(ch)
                    i++
                }
            }
        }
    }
}

@Composable
private fun CollabEmbeddedCardBlock(card: CollabEmbeddedCard, liveCard: CollabCard?, isOwn: Boolean) {
    val c = VibexTheme.colors
    val containerBg = if (isOwn) Color(0xFF1D4ED8) else c.bgElevated
    val borderColor = if (isOwn) Color.White.copy(alpha = 0.22f) else c.stroke
    val primaryText = if (isOwn) Color.White else c.textPrimary
    val secondaryText = if (isOwn) Color.White.copy(alpha = 0.82f) else c.textSecondary
    val resolvedStatus = (liveCard?.status ?: card.status ?: "pending").lowercase()
    val (statusLabel, statusColor) = when (resolvedStatus) {
        "approved", "allow_once", "allow_always" -> "已批准" to Color(0xFF16A34A)
        "denied", "declined", "rejected", "deny" -> "已拒绝" to Color(0xFFDC2626)
        "answered", "resolved", "completed", "responded" -> "已处理" to Color(0xFF16A34A)
        else -> "待处理" to Color(0xFFD97706)
    }

    Surface(shape = RoundedCornerShape(10.dp), color = containerBg, border = BorderStroke(0.5.dp, borderColor), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (card.kind == "approval") Icons.Outlined.Shield else Icons.Outlined.HelpOutline, null, tint = if (isOwn) Color.White else Color(0xFFF97316), modifier = Modifier.size(14.dp))
                Text(if (card.kind == "approval") "审批请求" else "助手提问", color = primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(50), color = if (isOwn) Color.White.copy(alpha = 0.16f) else statusColor.copy(alpha = 0.14f)) {
                    Text(statusLabel, color = if (isOwn) Color.White else statusColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                }
            }
            val mainLine = if (card.kind == "approval") (card.action ?: card.body) else (card.question ?: card.body)
            if (mainLine.isNotBlank()) {
                Text(mainLine, color = primaryText, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
            }
            if (!card.command.isNullOrBlank()) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(if (isOwn) Color(0xFF172554) else c.codeSurface).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text(card.command.orEmpty(), color = if (isOwn) Color(0xFFF8FAFC) else c.textPrimary, fontFamily = PlexMono, fontSize = 11.5.sp, lineHeight = 16.sp)
                }
            }
            if (!card.reason.isNullOrBlank() && card.reason != mainLine) {
                Text(card.reason.orEmpty(), color = secondaryText, fontSize = 12.sp, lineHeight = 17.sp)
            }
            if (card.options.isNotEmpty()) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    card.options.forEach { opt ->
                        Surface(shape = RoundedCornerShape(50), color = if (isOwn) Color.White.copy(alpha = 0.16f) else c.card, border = BorderStroke(0.5.dp, borderColor)) {
                            Text(opt, color = primaryText, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollabEmbeddedHandoffCard(handoff: CollabEmbeddedHandoff, isOwn: Boolean) {
    val c = VibexTheme.colors
    val containerBg = if (isOwn) Color(0xFF1D4ED8) else c.bgElevated
    val borderColor = if (isOwn) Color.White.copy(alpha = 0.22f) else c.stroke
    val primaryText = if (isOwn) Color.White else c.textPrimary
    val secondaryText = if (isOwn) Color.White.copy(alpha = 0.82f) else c.textSecondary
    val routeTitle = if (!handoff.from.isNullOrBlank() || !handoff.to.isNullOrBlank()) {
        "${handoff.from ?: "助手"} → ${handoff.to ?: "助手"}"
    } else {
        "任务交接"
    }

    Surface(shape = RoundedCornerShape(10.dp), color = containerBg, border = BorderStroke(0.5.dp, borderColor), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.SwapHoriz, null, tint = if (isOwn) Color.White else CollabUserBubbleBlue, modifier = Modifier.size(14.dp))
                Text(routeTitle, color = primaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (!handoff.depth.isNullOrBlank()) {
                    Spacer(Modifier.weight(1f))
                    Text("L${handoff.depth}", style = TextStyle(color = secondaryText, fontSize = 10.sp, fontFamily = PlexMono, fontFeatureSettings = "tnum"))
                }
            }
            if (handoff.summary.isNotBlank()) {
                Text(handoff.summary, color = primaryText, fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
            }
            if (!handoff.done.isNullOrBlank() && handoff.done != handoff.summary) {
                Text("已完成：${handoff.done}", color = secondaryText, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
            if (!handoff.inputsConstraints.isNullOrBlank()) {
                Text("约束：${handoff.inputsConstraints}", color = secondaryText, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollabFullMessageReaderSheet(
    message: CollabSpaceMessage,
    avatarConfig: CollabBotAvatarConfig,
    knownCardsById: Map<String, CollabCard>,
    onDismiss: () -> Unit,
) {
    val c = VibexTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val parsed = remember(message.envelope.content) { parseCollabStructuredMessage(message.envelope.content) }
    val copyableText = remember(parsed, message.envelope.content) {
        parsed.cleanText.ifBlank { collabMessagePreviewText(message.envelope.content) }
    }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800L)
            copied = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.card, contentColor = c.textPrimary) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!message.isFromUser) MemberBotAvatarView(config = avatarConfig, size = 30.dp, sessionActive = false)
                Column(modifier = Modifier.weight(1f)) {
                    Text(message.envelope.displaySender, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val metaParts = buildList {
                        message.envelope.published?.takeIf { it.isNotBlank() }?.let {
                            val rel = formatCollabRelativeTime(it)
                            if (rel.isNotBlank()) add(rel)
                        }
                        if (parsed.lineCount > 0) add("${parsed.lineCount} 行")
                    }
                    if (metaParts.isNotEmpty()) {
                        Text(metaParts.joinToString(" · "), style = TextStyle(color = c.textSecondary, fontSize = 12.sp, fontFeatureSettings = "tnum"))
                    }
                }
                Surface(
                    onClick = {
                        clipboard.setText(AnnotatedString(copyableText))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        Toast.makeText(context, "已复制全文", Toast.LENGTH_SHORT).show()
                        copied = true
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = c.bgElevated,
                    border = BorderStroke(0.75.dp, c.stroke),
                ) {
                    Row(modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, tint = if (copied) Color(0xFF16A34A) else c.textPrimary, modifier = Modifier.size(15.dp))
                        Text(if (copied) "已复制" else "复制全文", color = if (copied) Color(0xFF16A34A) else c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = c.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = c.stroke, thickness = 0.5.dp)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                parsed.handoffs.forEach { SelectionContainer { CollabEmbeddedHandoffCard(it, isOwn = false) } }
                parsed.cards.forEach { SelectionContainer { CollabEmbeddedCardBlock(it, knownCardsById[it.id], isOwn = false) } }
                if (parsed.cleanText.isNotBlank()) {
                    MarkdownText(source = parsed.cleanText, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
fun CollabJumpToBottomPill(unreadNewCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = VibexTheme.colors
    val hasUnread = unreadNewCount > 0
    val contentColor = if (hasUnread) Color.White else c.textPrimary
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (hasUnread) CollabUserBubbleBlue else c.card,
        border = if (hasUnread) null else BorderStroke(0.75.dp, c.stroke),
        modifier = modifier.shadow(
            elevation = if (c.isDark) 8.dp else 4.dp,
            shape = CircleShape,
            ambientColor = Color.Black.copy(alpha = if (c.isDark) 0.28f else 0.08f),
            spotColor = Color.Black.copy(alpha = if (c.isDark) 0.34f else 0.12f),
        ),
    ) {
        Row(modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(Icons.Outlined.ArrowDownward, null, tint = contentColor, modifier = Modifier.size(15.dp))
            Text(
                text = if (hasUnread) "$unreadNewCount 条新消息" else "回到底部",
                style = TextStyle(color = contentColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            )
        }
    }
}

@Composable
fun CollabLoadEarlierBanner(loading: Boolean, onLoadEarlier: () -> Unit, modifier: Modifier = Modifier) {
    val c = VibexTheme.colors
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = { if (!loading) onLoadEarlier() }, shape = CircleShape, color = c.card, border = BorderStroke(0.75.dp, c.stroke)) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (loading) {
                    CircularProgressIndicator(color = c.accent, strokeWidth = 1.8.dp, modifier = Modifier.size(13.dp))
                    Text("加载更早消息…", color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                } else {
                    Icon(Icons.Outlined.History, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    Text("加载更早消息", color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollabExpandedComposerSheet(
    title: String,
    isGroup: Boolean,
    leadMemberId: String?,
    agentMembers: List<CollabSpaceMember>,
    selectedTargets: List<String>,
    replyingTo: CollabSpaceMessage?,
    inputValue: TextFieldValue,
    sending: Boolean,
    onInputChange: (TextFieldValue) -> Unit,
    onToggleTarget: (String) -> Unit,
    onClearReply: () -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = VibexTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canSend = inputValue.text.trim().isNotEmpty() && !sending
    val lineCount = remember(inputValue.text) { countCollabMessageLines(inputValue.text) }

    fun insertChipMention(label: String, target: String) {
        val text = inputValue.text
        val caret = inputValue.selection.start.coerceIn(0, text.length)
        val activeToken = if (inputValue.selection.collapsed) mentionTokenAt(text, caret) else null
        if (activeToken != null) {
            val (next, nextCaret) = applyMention(text, activeToken, label)
            onInputChange(TextFieldValue(next, TextRange(nextCaret)))
        } else {
            val insert = (if (caret > 0 && !text[caret - 1].isWhitespace()) " " else "") + "@$label "
            onInputChange(TextFieldValue(text.substring(0, caret) + insert + text.substring(caret), TextRange(caret + insert.length)))
        }
        if (!selectedTargets.contains(target)) onToggleTarget(target)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = c.card, contentColor = c.textPrimary) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = c.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${lineCount.coerceAtLeast(1)} 行 · ${inputValue.text.length} 字", style = TextStyle(color = c.textSecondary, fontSize = 11.5.sp, fontFeatureSettings = "tnum"))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "收起编辑器", tint = c.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            if (replyingTo != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.bgElevated).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Reply, null, tint = CollabUserBubbleBlue, modifier = Modifier.size(14.dp))
                    Text("回复 ${replyingTo.envelope.displaySender}：${collabMessagePreviewText(replyingTo.envelope.content)}", color = c.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.Close, "取消回复", tint = c.textSecondary, modifier = Modifier.size(16.dp).clickable(onClick = onClearReply))
                }
            }
            if (isGroup && agentMembers.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val allSelected = selectedTargets.contains("@all")
                    Surface(onClick = { insertChipMention("全体成员", "@all") }, shape = RoundedCornerShape(50), color = if (allSelected) CollabUserBubbleBlue else c.bgElevated) {
                        Text("@全体成员", color = if (allSelected) Color.White else c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                    agentMembers.forEach { member ->
                        val selected = selectedTargets.contains(member.resolvedIdentityId)
                        Surface(onClick = { insertChipMention(member.nickname, member.resolvedIdentityId) }, shape = RoundedCornerShape(50), color = if (selected) CollabUserBubbleBlue else c.bgElevated) {
                            Row(modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                MemberBotAvatarView(config = member.botAvatar, size = 16.dp, sessionActive = false, isLead = leadMemberId == member.resolvedIdentityId)
                                Text("@${member.nickname}", color = if (selected) Color.White else c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 340.dp).clip(RoundedCornerShape(14.dp)).background(c.bgElevated)
                    .border(0.75.dp, c.stroke, RoundedCornerShape(14.dp)).verticalScroll(rememberScrollState()).padding(14.dp),
            ) {
                if (inputValue.text.isEmpty()) {
                    Text("输入多行提示词、架构说明或代码片段…", color = c.textTertiary, fontSize = 15.sp)
                }
                BasicTextField(
                    value = inputValue,
                    onValueChange = onInputChange,
                    textStyle = TextStyle(color = c.textPrimary, fontSize = 15.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { onSend(); onDismiss() },
                    enabled = canSend,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = c.textPrimary, contentColor = c.bg),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(Icons.Outlined.ArrowUpward, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("发送", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
