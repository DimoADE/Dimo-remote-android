package dev.vibex.companion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollabLongConversationTest {
    private val origin = HostOrigin.parse("http://127.0.0.1:17891")

    @Test
    fun collabMessagesSendsExpectedPaginationPayloadsAndParsesPageMetadata() {
        val latestTransport = CollabRecordingTransport(
            200,
            """
            {"data":{
              "messages":[
                {"id":"m-101","seq":101,"origin":"user","operationId":"op-101","envelope":{"id":"m-101","sender":"Person","content":"hello"}},
                {"id":"draft:t-1","seq":0,"streaming":true,"turn_id":"t-1","envelope":{"sender":"ArchBot","content":"streaming..."}}
              ],
              "scheduled":[],
              "cards":[],
              "hasMoreBefore":true,
              "oldestSeq":101
            }}
            """.trimIndent(),
        )
        val latestPage = HostApi(latestTransport).collabMessages(origin, "token", spaceId = "sp-1")
        assertTrue(latestTransport.lastBody!!.contains("\"spaceId\":\"sp-1\""))
        assertTrue(latestTransport.lastBody!!.contains("\"latest\":true"))
        assertTrue(latestTransport.lastBody!!.contains("\"limit\":80"))
        assertFalse(latestTransport.lastBody!!.contains("sinceSeq"))
        assertFalse(latestTransport.lastBody!!.contains("beforeSeq"))
        assertTrue(latestPage.hasMoreBefore)
        assertEquals(101L, latestPage.oldestSeq)
        assertEquals(2, latestPage.messages.size)
        assertEquals("op-101", latestPage.messages[0].operationId)
        assertEquals("t-1", latestPage.messages[1].effectiveTurnId)
        assertTrue(latestPage.messages[1].isStreamingDraft)

        val sinceTransport = CollabRecordingTransport(
            200,
            """{"data":{"messages":[],"scheduled":[],"cards":[]}}""",
        )
        val sincePage = HostApi(sinceTransport).collabMessages(
            origin = origin,
            token = "token",
            spaceId = "sp-1",
            limit = 80,
            sinceSeq = 101L,
        )
        assertTrue(sinceTransport.lastBody!!.contains("\"sinceSeq\":101"))
        assertTrue(sinceTransport.lastBody!!.contains("\"limit\":80"))
        assertFalse(sinceTransport.lastBody!!.contains("\"latest\""))
        assertFalse(sinceTransport.lastBody!!.contains("beforeSeq"))
        assertFalse(sincePage.hasMoreBefore)
        assertNull(sincePage.oldestSeq)

        val beforeTransport = CollabRecordingTransport(
            200,
            """{"data":{"messages":[{"id":"m-50","seq":50,"envelope":{"sender":"Coder","content":"earlier"}}],"has_more_before":false,"oldest_seq":50}}""",
        )
        val beforePage = HostApi(beforeTransport).collabMessages(
            origin = origin,
            token = "token",
            spaceId = "sp-1",
            limit = 80,
            beforeSeq = 101L,
        )
        assertTrue(beforeTransport.lastBody!!.contains("\"beforeSeq\":101"))
        assertTrue(beforeTransport.lastBody!!.contains("\"limit\":80"))
        assertFalse(beforeTransport.lastBody!!.contains("\"latest\""))
        assertFalse(beforeTransport.lastBody!!.contains("sinceSeq"))
        assertFalse(beforePage.hasMoreBefore)
        assertEquals(50L, beforePage.oldestSeq)
        assertEquals(listOf(50), beforePage.messages.map { it.seq })
    }

    @Test
    fun mergeCollabMessagesDeduplicatesByIdRawIdOperationIdAndPositiveSeq() {
        val existing = listOf(
            CollabSpaceMessage(
                rawId = "vibex://space-message/m-1",
                seq = 1,
                operationId = "op-1",
                envelope = CollabMessageEnvelope(id = "m-1", sender = "Person", content = "init"),
            ),
            CollabSpaceMessage(
                rawId = null,
                seq = 0,
                operationId = "op-pending",
                envelope = CollabMessageEnvelope(sender = "Person", content = "optimistic retry"),
            ),
            CollabSpaceMessage(
                rawId = "m-3",
                seq = 3,
                envelope = CollabMessageEnvelope(id = "m-3", sender = "BotA", content = "v1"),
            ),
        )
        val incoming = listOf(
            // Matches m-1 by id & seq, updates reactions
            CollabSpaceMessage(
                rawId = "m-1",
                seq = 1,
                envelope = CollabMessageEnvelope(
                    id = "m-1",
                    sender = "Person",
                    content = "init",
                    reactions = listOf(CollabReaction("👍", "user")),
                ),
            ),
            // Matches optimistic message by operationId and assigns seq=2
            CollabSpaceMessage(
                rawId = "m-2",
                seq = 2,
                operationId = "op-pending",
                envelope = CollabMessageEnvelope(id = "m-2", sender = "Person", content = "optimistic retry"),
            ),
            // Matches m-3 by positive seq
            CollabSpaceMessage(
                rawId = "m-3-updated",
                seq = 3,
                envelope = CollabMessageEnvelope(id = "m-3-updated", sender = "BotA", content = "v2"),
            ),
        )

        val merged = mergeCollabMessages(existing, incoming)
        assertEquals(listOf(1, 2, 3), merged.map { it.seq })
        assertEquals(1, merged[0].envelope.reactions.size)
        assertEquals("op-pending", merged[1].operationId)
        assertEquals("v2", merged[2].envelope.content)
    }

    @Test
    fun mergeCollabMessagesReplacesStreamingDraftsOnUpdateAndFinalization() {
        val initial = listOf(
            CollabSpaceMessage(
                rawId = "m-10",
                seq = 10,
                envelope = CollabMessageEnvelope(id = "m-10", sender = "Person", content = "Design this"),
            ),
            CollabSpaceMessage(
                rawId = "draft:turn-99",
                seq = 0,
                streaming = true,
                turnId = "turn-99",
                envelope = CollabMessageEnvelope(sender = "Architect", content = "Step 1"),
            ),
        )

        // Newer streaming chunk for the same turn
        val afterChunk = mergeCollabMessages(
            initial,
            listOf(
                CollabSpaceMessage(
                    rawId = "draft:turn-99",
                    seq = 0,
                    streaming = true,
                    turnId = "turn-99",
                    envelope = CollabMessageEnvelope(sender = "Architect", content = "Step 1\nStep 2"),
                ),
            ),
        )
        assertEquals(2, afterChunk.size)
        assertEquals("Step 1\nStep 2", afterChunk.last().envelope.content)
        assertTrue(afterChunk.last().isStreamingDraft)

        // Finalized message arrives in group space (even without turnId, matched by sender + seq > 10)
        val finalized = mergeCollabMessages(
            afterChunk,
            listOf(
                CollabSpaceMessage(
                    rawId = "m-11",
                    seq = 11,
                    streaming = false,
                    envelope = CollabMessageEnvelope(id = "m-11", sender = "Architect", content = "Step 1\nStep 2\nDone!"),
                ),
            ),
        )
        assertEquals(2, finalized.size)
        assertFalse(finalized.any { it.isStreamingDraft })
        assertEquals(listOf(10, 11), finalized.map { it.seq })
        assertEquals("Step 1\nStep 2\nDone!", finalized.last().envelope.content)
    }

    @Test
    fun mergeCollabMessagesSupportsBeforeSeqPrependAndSinceSeqAppendWhileKeepingDraftsAtTail() {
        val window = listOf(
            CollabSpaceMessage(rawId = "m-11", seq = 11, envelope = CollabMessageEnvelope(sender = "BotA", content = "11")),
            CollabSpaceMessage(rawId = "m-12", seq = 12, envelope = CollabMessageEnvelope(sender = "BotB", content = "12")),
            CollabSpaceMessage(
                rawId = "draft:botA",
                seq = 0,
                streaming = true,
                envelope = CollabMessageEnvelope(sender = "BotA", content = "Live tail draft"),
            ),
        )

        // Prepend older messages via beforeSeq=11 (including an older message from BotA that must NOT clobber active tail draft!)
        val earlier = listOf(
            CollabSpaceMessage(rawId = "m-9", seq = 9, envelope = CollabMessageEnvelope(sender = "BotA", content = "Old message 9")),
            CollabSpaceMessage(rawId = "m-10", seq = 10, envelope = CollabMessageEnvelope(sender = "Person", content = "10")),
        )
        val afterPrepend = mergeCollabMessages(window, earlier)
        assertEquals(listOf(9, 10, 11, 12, 0), afterPrepend.map { it.seq })
        assertTrue(afterPrepend.last().isStreamingDraft)
        assertEquals("Live tail draft", afterPrepend.last().envelope.content)

        // Append newer confirmed message via sinceSeq=12 from BotB; BotA's draft still stays at tail
        val afterAppend = mergeCollabMessages(
            afterPrepend,
            listOf(
                CollabSpaceMessage(rawId = "m-13", seq = 13, envelope = CollabMessageEnvelope(sender = "BotB", content = "13")),
            ),
        )
        assertEquals(listOf(9, 10, 11, 12, 13, 0), afterAppend.map { it.seq })
        assertEquals("draft:botA", afterAppend.last().id)

        // Reconciling against an empty active tail draft set removes cancelled draft
        val reconciled = reconcileCollabStreamingDrafts(afterAppend, emptyList())
        assertEquals(listOf(9, 10, 11, 12, 13), reconciled.map { it.seq })
    }

    @Test
    fun parseCollabStructuredMessageExtractsCardsAndHandoffsWithoutLeakingRawXml() {
        val approvalMsg = """
            请求审批：合并 PR

            <card kind="approval" id="card-1">
            <action>
            合并 PR #42
            </action>
            <command>
            git merge &lt;feature/long-conv&gt;
            </command>
            <reason>
            全部单测已通过
            </reason>
            </card>
        """.trimIndent()

        val parsedApproval = parseCollabStructuredMessage(approvalMsg)
        assertEquals("请求审批：合并 PR", parsedApproval.cleanText)
        assertFalse(parsedApproval.cleanText.contains("<card"))
        val apprCard = parsedApproval.card!!
        assertEquals("card-1", apprCard.id)
        assertEquals("approval", apprCard.kind)
        assertEquals("合并 PR #42", apprCard.action)
        assertEquals("git merge <feature/long-conv>", apprCard.command)
        assertEquals("全部单测已通过", apprCard.reason)

        val askAndHandoffMsg = """
            已完成接口拆分，请确认下一步：

            <handoff id="h-7" from="BackendBot" to="AndroidBot" depth="1">
            <done>space.listMessages beforeSeq &amp; hasMoreBefore</done>
            <inputs_constraints>不要改动协议外字段</inputs_constraints>
            <ask>在 Android 端接入分页与折叠长消息</ask>
            </handoff>

            <card kind="ask" id="card-ask-2">
            <question>默认分页条数用多少？</question>
            <options>40|80|120</options>
            </card>
        """.trimIndent()

        val parsedCombo = parseCollabStructuredMessage(askAndHandoffMsg)
        assertEquals("已完成接口拆分，请确认下一步：", parsedCombo.cleanText)
        assertFalse(parsedCombo.cleanText.contains("<handoff"))
        assertFalse(parsedCombo.cleanText.contains("<card"))
        val handoff = parsedCombo.handoff!!
        assertEquals("h-7", handoff.id)
        assertEquals("BackendBot", handoff.from)
        assertEquals("AndroidBot", handoff.to)
        assertEquals("1", handoff.depth)
        assertEquals("在 Android 端接入分页与折叠长消息", handoff.summary)
        assertEquals("space.listMessages beforeSeq & hasMoreBefore", handoff.done)

        val askCard = parsedCombo.card!!
        assertEquals("card-ask-2", askCard.id)
        assertEquals("ask", askCard.kind)
        assertEquals("默认分页条数用多少？", askCard.question)
        assertEquals(listOf("40", "80", "120"), askCard.options)
    }

    @Test
    fun lineCountAndShouldCollapseLongMessageRespectThresholds() {
        val shortMsg = (1..12).joinToString("\n") { "第 $it 行简短总结" }
        val parsedShort = parseCollabStructuredMessage(shortMsg)
        assertEquals(12, parsedShort.lineCount)
        assertFalse(parsedShort.isCollapsible)
        assertFalse(shouldCollapseLongMessage(shortMsg))

        val thirteenLines = (1..13).joinToString("\n") { "line $it" }
        val parsedThirteen = parseCollabStructuredMessage(thirteenLines)
        assertEquals(13, parsedThirteen.lineCount)
        assertTrue(parsedThirteen.isCollapsible)

        val longSingleParagraph = "长段落测试 ".repeat(100)
        assertTrue(longSingleParagraph.length > 480)
        assertTrue(shouldCollapseLongMessage(longSingleParagraph))

        val lookup = buildCollabMessageLookup(
            listOf(
                CollabSpaceMessage(
                    rawId = "vibex://space-message/abc-123",
                    seq = 5,
                    envelope = CollabMessageEnvelope(id = "abc-123", sender = "Bot", content = "target"),
                ),
            ),
        )
        assertEquals(5, lookupCollabReplyMessage(lookup, "abc-123")?.seq)
        assertEquals(5, lookupCollabReplyMessage(lookup, "vibex://space-message/abc-123")?.seq)
    }
}

private class CollabRecordingTransport(
    private val status: Int,
    private val body: String,
) : HttpTransport {
    var lastBody: String? = null

    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String?,
    ): HttpExchange {
        lastBody = body
        return HttpExchange(status, this.body)
    }
}
