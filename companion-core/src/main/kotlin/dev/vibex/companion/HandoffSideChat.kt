package dev.vibex.companion

import dev.vibex.remote.v1.JsonValue

/** `provider_instances_list` wire row (camelCase `ProviderInstanceWireView`). */
data class ProviderInstanceItem(
    val instanceId: String,
    val agentId: String,
    val displayName: String,
    val isDefault: Boolean,
)

/** `ConversationEngineHandoffPreview` (camelCase). */
data class EngineHandoffPreview(
    val sourceEngine: String,
    val targetEngine: String,
    val packedCount: Int,
    val omittedCount: Int,
    val totalTokens: Int,
    val tokenBudget: Int,
    val promptText: String,
)

data class SideChatToolCall(val id: String, val name: String, val status: String, val summary: String)

data class SideChatReply(val content: String, val toolCalls: List<SideChatToolCall>)

data class SideChatContextTurn(val turnNumber: Int, val userText: String, val assistantText: String)

data class SideChatMessage(
    val id: String,
    val role: String, // "user" | "assistant"
    val content: String,
    val toolCalls: List<SideChatToolCall> = emptyList(),
    val isError: Boolean = false,
)

fun parseProviderInstances(data: JsonValue): List<ProviderInstanceItem> {
    val rows = data.asArray()?.value
        ?: data.asObject()?.arrOrNull("items")
        ?: data.asObject()?.arrOrNull("instances")
        ?: return emptyList()
    return rows.mapNotNull { item ->
        val obj = item.asObject() ?: return@mapNotNull null
        val instanceId = obj.textAny("instanceId", "instance_id")
        val agentId = obj.textAny("agentId", "agent_id")
        if (instanceId.isBlank() || agentId.isBlank()) return@mapNotNull null
        ProviderInstanceItem(
            instanceId = instanceId,
            agentId = agentId,
            displayName = obj.textAny("displayName", "display_name").ifBlank { instanceId },
            isDefault = obj.boolOrNull("isDefault") ?: obj.boolOrNull("is_default") ?: false,
        )
    }
}

fun parseEngineHandoffPreview(data: JsonValue): EngineHandoffPreview? {
    val obj = data.asObject() ?: return null
    return EngineHandoffPreview(
        sourceEngine = obj.textAny("sourceEngine", "source_engine"),
        targetEngine = obj.textAny("targetEngine", "target_engine"),
        packedCount = (obj.arrOrNull("packedMessages") ?: obj.arrOrNull("packed_messages")).orEmpty().size,
        omittedCount = (obj.arrOrNull("omittedIds") ?: obj.arrOrNull("omitted_ids")).orEmpty().size,
        totalTokens = (obj.longOrNull("totalTokens") ?: obj.longOrNull("total_tokens") ?: 0L).toInt(),
        tokenBudget = (obj.longOrNull("tokenBudget") ?: obj.longOrNull("token_budget") ?: 0L).toInt(),
        promptText = obj.textAny("handoffPromptText", "handoff_prompt_text"),
    )
}

/** Instances available for [agentId], case-insensitive and tolerant of `-`/`_` differences. */
fun instancesForAgent(all: List<ProviderInstanceItem>, agentId: String): List<ProviderInstanceItem> {
    fun norm(s: String) = s.lowercase().replace('-', '_')
    return all.filter { norm(it.agentId) == norm(agentId) }
}

fun parseSideChatReply(data: JsonValue): SideChatReply? {
    val obj = data.asObject() ?: return null
    fun tool(v: JsonValue): SideChatToolCall? {
        val t = v.asObject() ?: return null
        return SideChatToolCall(
            id = t.textAny("id"),
            name = t.textAny("name").ifBlank { "tool" },
            status = t.textAny("status"),
            summary = t.textAny("summary"),
        )
    }
    val tools = obj.arrOrNull("toolCalls").orEmpty().mapNotNull(::tool)
    val content = obj.textOrNull("content")
        // Fall back to the terminal `done` stream event if top-level content is absent.
        ?: obj.arrOrNull("events").orEmpty().asReversed().firstNotNullOfOrNull { e ->
            e.asObject()?.takeIf { it.textOrNull("type") == "done" }?.textOrNull("content")
        }
        ?: return null
    return SideChatReply(content, tools)
}

/** Last [maxTurns] user→assistant pairs as side-chat context (text truncated like desktop: 600 chars). */
fun buildSideChatContext(items: List<TimelineItem>, maxTurns: Int = 6, maxChars: Int = 600): List<SideChatContextTurn> {
    fun clip(s: String): String = s.trim().let { if (it.length <= maxChars) it else it.take(maxChars) + "…" }
    val turns = mutableListOf<SideChatContextTurn>()
    var user: String? = null
    var assistant = StringBuilder()
    fun flush() {
        val u = user
        if (u != null) turns += SideChatContextTurn(turns.size + 1, clip(u), clip(assistant.toString()))
        user = null
        assistant = StringBuilder()
    }
    for (item in items) {
        when (item.role) {
            TimelineItem.Role.User -> { flush(); user = item.text }
            TimelineItem.Role.Assistant -> if (user != null && item.text.isNotBlank()) {
                if (assistant.isNotEmpty()) assistant.append("\n\n")
                assistant.append(item.text.trim())
            }
            TimelineItem.Role.Divider -> {}
        }
    }
    flush()
    return turns.takeLast(maxTurns).mapIndexed { i, t -> t.copy(turnNumber = i + 1) }
}

fun HostApi.providerInstances(origin: HostOrigin, token: String): List<ProviderInstanceItem> =
    parseProviderInstances(call(origin, token, "provider_instances_list", emptyMap()))

fun HostApi.previewEngineHandoff(
    origin: HostOrigin,
    token: String,
    conversationId: String,
    targetEngine: String,
    targetProviderInstanceId: String?,
    tokenBudget: Int? = null,
): EngineHandoffPreview =
    parseEngineHandoffPreview(
        call(origin, token, "conversation_preview_engine_handoff", handoffArgs(conversationId, targetEngine, targetProviderInstanceId, tokenBudget)),
    ) ?: throw HostApiException(500, "Host 返回了无法识别的交接预览")

fun HostApi.confirmEngineHandoff(
    origin: HostOrigin,
    token: String,
    conversationId: String,
    targetEngine: String,
    targetProviderInstanceId: String?,
) {
    call(origin, token, "conversation_confirm_engine_handoff", handoffArgs(conversationId, targetEngine, targetProviderInstanceId, null))
}

internal fun handoffArgs(
    conversationId: String,
    targetEngine: String,
    targetProviderInstanceId: String?,
    tokenBudget: Int?,
): Map<String, Any?> {
    val args = linkedMapOf<String, Any?>("conversationId" to conversationId, "targetEngine" to targetEngine)
    if (!targetProviderInstanceId.isNullOrBlank()) args["targetProviderInstanceId"] = targetProviderInstanceId
    if (tokenBudget != null && tokenBudget > 0) args["tokenBudget"] = tokenBudget.toDouble()
    return args
}

fun HostApi.sideChatSend(
    origin: HostOrigin,
    token: String,
    conversationId: String,
    sideChatId: String,
    prompt: String,
    contextTurns: List<SideChatContextTurn>,
    history: List<SideChatMessage>,
): SideChatReply =
    parseSideChatReply(
        call(
            origin,
            token,
            "conversation_side_chat_send",
            sideChatArgs(conversationId, sideChatId, prompt, contextTurns, history),
        ),
    ) ?: throw HostApiException(500, "Host 返回了无法识别的旁路对话结果")

fun HostApi.sideChatAbort(origin: HostOrigin, token: String, conversationId: String, sideChatId: String) {
    call(origin, token, "conversation_side_chat_abort", mapOf("conversationId" to conversationId, "sideChatId" to sideChatId))
}

internal fun sideChatArgs(
    conversationId: String,
    sideChatId: String,
    prompt: String,
    contextTurns: List<SideChatContextTurn>,
    history: List<SideChatMessage>,
): Map<String, Any?> = mapOf(
    "conversationId" to conversationId,
    "sideChatId" to sideChatId,
    "prompt" to prompt,
    "contextTurns" to contextTurns.map {
        mapOf("turnNumber" to it.turnNumber.toDouble(), "userText" to it.userText, "assistantText" to it.assistantText)
    },
    "history" to history.filter { !it.isError }.map { mapOf("role" to it.role, "content" to it.content) },
)
