package dev.vibex.companion

import dev.vibex.remote.v1.JsonValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffSideChatTest {
    @Test
    fun parsesProviderInstancesAndFiltersByAgent() {
        val data = JsonValue.parse(
            """[{"id":"1","instanceId":"work","agentId":"claude_code","displayName":"Work","isDefault":true},
               {"instanceId":"p","agent_id":"codex","display_name":"P"},{"agentId":"x"}]""",
        )
        val all = parseProviderInstances(data)
        assertEquals(2, all.size)
        assertEquals(listOf("work"), instancesForAgent(all, "claude-code").map { it.instanceId })
        assertTrue(all[0].isDefault)
        assertFalse(all[1].isDefault)
    }

    @Test
    fun parsesPreview() {
        val p = parseEngineHandoffPreview(
            JsonValue.parse(
                """{"sourceEngine":"codex","targetEngine":"claude_code","packedMessages":[{"id":"a"},{"id":"b"}],
                   "omittedIds":["c"],"totalTokens":120,"tokenBudget":8000,"handoffPromptText":"hi"}""",
            ),
        )
        assertNotNull(p)
        assertEquals(2, p.packedCount)
        assertEquals(1, p.omittedCount)
        assertEquals(8000, p.tokenBudget)
        assertEquals("hi", p.promptText)
        assertNull(parseEngineHandoffPreview(JsonValue.Null))
    }

    @Test
    fun handoffArgsOmitBlankInstanceAndBudget() {
        val a = handoffArgs("c1", "codex", " ", null)
        assertEquals(setOf("conversationId", "targetEngine"), a.keys)
        val b = handoffArgs("c1", "codex", "work", 100)
        assertEquals("work", b["targetProviderInstanceId"])
        assertEquals(100.0, b["tokenBudget"])
    }

    @Test
    fun parsesSideChatReplyWithDoneFallback() {
        val r = parseSideChatReply(
            JsonValue.parse("""{"content":"ok","toolCalls":[{"id":"t","name":"Read","status":"completed","summary":"s"}],"events":[]}"""),
        )
        assertNotNull(r)
        assertEquals("ok", r.content)
        assertEquals("Read", r.toolCalls.single().name)
        val f = parseSideChatReply(JsonValue.parse("""{"events":[{"type":"delta","text":"x"},{"type":"done","content":"final"}]}"""))
        assertEquals("final", f?.content)
        assertNull(parseSideChatReply(JsonValue.parse("""{"events":[]}""")))
    }

    @Test
    fun buildsContextTurnsAndSkipsErroredHistory() {
        val items = listOf(
            TimelineItem("1", TimelineItem.Role.User, "q1"),
            TimelineItem("2", TimelineItem.Role.Assistant, "a1"),
            TimelineItem("3", TimelineItem.Role.Assistant, "a1b"),
            TimelineItem("4", TimelineItem.Role.User, "q2"),
        )
        val ctx = buildSideChatContext(items, maxTurns = 1)
        assertEquals(1, ctx.size)
        assertEquals("q2", ctx[0].userText)
        assertEquals(1, ctx[0].turnNumber)
        val full = buildSideChatContext(items)
        assertEquals("a1\n\na1b", full[0].assistantText)
        val args = sideChatArgs(
            "c", "s", "p", full,
            listOf(SideChatMessage("1", "user", "hi"), SideChatMessage("2", "assistant", "err", isError = true)),
        )
        assertEquals(1, (args["history"] as List<*>).size)
    }
}
