package dev.vibex.companion

import dev.vibex.remote.v1.JsonValue
import dev.vibex.remote.v1.RemoteEvent
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.UUID

data class ConversationSummary(
    val id: String,
    val workspaceId: String,
    val title: String,
    val agentId: String,
    val status: String,
    val updatedAt: String,
    val pinned: Boolean = false,
)

data class CatalogProject(val id: String, val name: String, val path: String = "")
data class CatalogWorkspace(val id: String, val projectId: String, val name: String, val branch: String = "")
data class CatalogAgent(
    val id: String,
    val ready: Boolean,
    val displayName: String = "",
    val iconSvg: String = "",
    val currentModeId: String = "",
    val sessionConfig: List<SessionConfigOption> = emptyList(),
    val sessionModes: List<SessionMode> = emptyList(),
    val usable: Boolean = true,
    val lifecycle: String = "",
    val authentication: String = "",
) {
    val modelOptions: List<SessionConfigOption>
        get() {
            val matched = sessionConfig.filter {
                val cat = it.category.lowercase()
                val key = it.key.lowercase()
                cat == "model" || key == "model" || key.contains("model")
            }
            if (matched.isNotEmpty() && matched.any { it.choices.isNotEmpty() }) {
                return matched
            }
            return listOf(fallbackModelOptionForAgent(id))
        }

    val otherOptions: List<SessionConfigOption>
        get() = sessionConfig.filter {
            val cat = it.category.lowercase()
            val key = it.key.lowercase()
            cat != "model" && cat != "mode" && key != "model" && !key.contains("model") && key != "mode"
        }

    companion object {
        fun makeCustomChoice(modelId: String): SessionConfigChoice =
            SessionConfigChoice(value = modelId, label = modelId, description = "自定义 / 中转模型")

        fun makeCustomModelOption(key: String = "model", modelId: String): SessionConfigOption =
            SessionConfigOption(
                key = key,
                label = "模型",
                category = "model",
                value = modelId,
                choices = listOf(makeCustomChoice(modelId)),
            )
    }
}

data class CatalogTag(val id: String, val name: String, val content: String = "")
data class WorkspaceEntry(val name: String, val path: String, val directory: Boolean)

data class WorkspaceFileDiff(
    val path: String,
    val status: String,
    val diff: String,
    val addedLines: Int = 0,
    val removedLines: Int = 0,
)

data class CollaborationThreadSummary(
    val id: String,
    val kind: String,
    val name: String,
    val subtitle: String,
    val agentId: String = "",
    val memberId: String = "",
    val unreadCount: Int = 0,
    val updatedAt: String = "",
)

data class CollaborationMessageItem(
    val id: String,
    val seq: Long,
    val senderName: String,
    val senderKind: String,
    val agentId: String = "",
    val content: String,
    val createdAt: String = "",
    val receipts: List<String> = emptyList(),
)

data class SubagentRoleItem(
    val id: String,
    val name: String,
    val teamId: String = "",
    val teamName: String = "",
    val summary: String = "",
    val agentId: String = "",
    val isBuiltin: Boolean = false,
)

data class ExtensionCatalogItem(
    val id: String,
    val name: String,
    val summary: String = "",
    val kind: String = "",
    val status: String = "",
)

data class SessionCatalog(
    val projects: List<CatalogProject>,
    val workspaces: List<CatalogWorkspace>,
    val agents: List<CatalogAgent>,
    val tags: List<CatalogTag> = emptyList(),
)

class HostApi(
    private val transport: HttpTransport,
    private val protocolVersion: String = PairingClient.CLIENT_PROTOCOL_VERSION,
) {
    fun health(origin: HostOrigin): Boolean {
        val response = transport.execute("GET", origin.resolve("/health"), emptyMap(), null)
        return response.status in 200..299
    }

    fun capabilities(origin: HostOrigin, token: String) =
        parseServerCapabilities(
            request(origin, token, "GET", "/api/v1/capabilities", null),
        )

    fun capabilitiesWithReachability(
        origin: HostOrigin,
        token: String,
    ): Pair<dev.vibex.remote.v1.ServerCapabilities, List<ReachabilityTarget>> {
        val body = request(origin, token, "GET", "/api/v1/capabilities", null)
        return parseServerCapabilities(body) to parseServerReachability(body)
    }

    fun revoke(origin: HostOrigin, token: String, deviceId: String) {
        request(origin, token, "DELETE", "/api/v1/auth/devices/$deviceId", null)
    }

    fun listRecent(
        origin: HostOrigin,
        token: String,
        sinceDays: Int = 3,
        projectId: String? = null,
    ): List<ConversationSummary> {
        val args = linkedMapOf<String, Any?>(
            "limit" to 100.0,
            "sinceDays" to sinceDays.toDouble(),
            "projectId" to projectId,
        )
        val data = call(origin, token, "conversation_list_recent", args)
        return parseSummaries(data)
    }

    fun listForWorkspace(origin: HostOrigin, token: String, workspaceId: String): List<ConversationSummary> {
        val data = call(origin, token, "conversation_list", mapOf("workspaceId" to workspaceId))
        return parseSummaries(data)
    }

    fun catalog(origin: HostOrigin, token: String): SessionCatalog {
        val data = call(origin, token, "conversation_catalog", emptyMap())
        val root = data.asObject() ?: return SessionCatalog(emptyList(), emptyList(), emptyList())
        val managementBar = runCatching {
            call(origin, token, "agent_management_bar", emptyMap())
        }.getOrNull()
        return SessionCatalog(
            projects = root.arrOrNull("projects").orEmpty().mapNotNull { item ->
                val obj = item.asObject() ?: return@mapNotNull null
                CatalogProject(
                    obj.textAny("id"),
                    obj.textAny("name"),
                    obj.textAny("path"),
                )
            },
            workspaces = root.arrOrNull("workspaces").orEmpty().mapNotNull { item ->
                val obj = item.asObject() ?: return@mapNotNull null
                CatalogWorkspace(
                    obj.textAny("id"),
                    obj.textAny("projectId", "project_id"),
                    obj.textAny("name"),
                    obj.textAny("branch"),
                )
            },
            agents = parseCatalogAgents(root, managementBar),
            tags = root.arrOrNull("tags").orEmpty().mapNotNull { item ->
                val obj = item.asObject() ?: return@mapNotNull null
                val name = obj.textAny("name", "tagName", "tag_name")
                if (name.isBlank()) null else CatalogTag(obj.textAny("id"), name, obj.textAny("content"))
            },
        )
    }

    fun listSlashCommands(
        origin: HostOrigin,
        token: String,
        agentId: String,
        workspaceId: String? = null,
    ): List<ComposerToken> {
        val args = linkedMapOf<String, Any?>(
            "agentId" to agentId,
            "workspaceId" to workspaceId?.takeIf { it.isNotBlank() },
        )
        val data = runCatching {
            call(origin, token, "conversation_slash_commands", args)
        }.getOrNull() ?: return emptyList()
        val items = data.asArray()?.value ?: data.asObject()?.arrOrNull("commands").orEmpty()
        return items.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val name = obj.textAny("name").removePrefix("/")
            if (name.isBlank()) return@mapNotNull null
            val sourceKind = obj.textAny("sourceKind", "source_kind").ifBlank { "skill" }
            val sourceId = obj.textAny("sourceId", "source_id").ifBlank { name }
            ComposerToken(
                prefix = TokenPrefix.Slash,
                name = name,
                value = obj.textAny("value").ifBlank {
                    if (sourceKind == "skill") "/skill:$sourceId:$name" else "/$name"
                },
                description = obj.textAny("description"),
                key = "$sourceKind:$sourceId:$name",
            )
        }
    }

    fun listWorkspaceEntries(origin: HostOrigin, token: String, workspaceId: String): List<WorkspaceEntry> {
        val data = runCatching {
            call(origin, token, "conversation_workspace_entries", mapOf("workspaceId" to workspaceId))
        }.getOrNull() ?: return emptyList()
        val items = data.asArray()?.value ?: data.asObject()?.arrOrNull("entries").orEmpty()
        return items.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val name = obj.textAny("name")
            if (name.isBlank()) null else WorkspaceEntry(
                name = name,
                path = obj.textAny("path").ifBlank { name },
                directory = obj.boolOrNull("directory") ?: (obj.boolOrNull("isDirectory") ?: (obj.boolOrNull("is_directory") ?: false)),
            )
        }
    }

    fun workspaceTree(origin: HostOrigin, token: String, workspaceId: String, path: String = ""): WorkspaceTree {
        val data = runCatching {
            call(
                origin,
                token,
                "conversation_workspace_tree",
                mapOf("workspaceId" to workspaceId, "path" to path),
            )
        }.getOrNull()?.asObject()
        if (data == null) {
            val fallback = if (path.isBlank()) listWorkspaceEntries(origin, token, workspaceId) else emptyList()
            return WorkspaceTree(path = path, entries = fallback, truncated = false)
        }
        val entries = data.arrOrNull("entries").orEmpty().mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val name = obj.textAny("name")
            if (name.isBlank()) return@mapNotNull null
            val isDir = obj.boolOrNull("directory")
                ?: obj.boolOrNull("isDirectory")
                ?: obj.boolOrNull("is_directory")
                ?: (obj.textAny("kind") == "directory")
            WorkspaceEntry(
                name = name,
                path = obj.textAny("path").ifBlank { if (path.isBlank()) name else "$path/$name" },
                directory = isDir,
            )
        }
        return WorkspaceTree(
            path = data.textAny("path").ifBlank { path },
            entries = entries,
            truncated = data.boolOrNull("truncated") ?: false,
        )
    }

    fun workspaceFile(origin: HostOrigin, token: String, workspaceId: String, path: String): WorkspaceFile {
        val data = call(
            origin,
            token,
            "conversation_workspace_file",
            mapOf("workspaceId" to workspaceId, "path" to path),
        ).asObject() ?: return WorkspaceFile(path = path, size = 0, binary = false, truncated = false, content = null)
        return WorkspaceFile(
            path = data.textAny("path").ifBlank { path },
            size = (data.longOrNull("size") ?: 0L).toInt(),
            binary = data.boolOrNull("binary") ?: (data.boolOrNull("is_binary") ?: false),
            truncated = data.boolOrNull("truncated") ?: false,
            content = data.textOrNull("content"),
        )
    }

    fun workspaceDiff(origin: HostOrigin, token: String, workspaceId: String, conversationId: String = ""): List<FileDiff> {
        val raw = runCatching {
            val args = linkedMapOf<String, Any?>()
            if (workspaceId.isNotBlank()) args["workspaceId"] = workspaceId
            if (conversationId.isNotBlank()) args["conversationId"] = conversationId
            call(origin, token, "conversation_workspace_diff", args)
        }.getOrNull()
        val items = raw?.asArray()?.value ?: raw?.asObject()?.arrOrNull("files", "diffs", "items").orEmpty()
        return items.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val path = obj.textAny("path", "filePath", "file_path", "name")
            if (path.isBlank()) return@mapNotNull null
            val diff = obj.textAny("diff", "patch", "content")
            val isBinary = obj.boolOrNull("is_binary") ?: (obj.boolOrNull("binary") ?: false)
            val truncated = obj.boolOrNull("truncated") ?: false
            FileDiff.fromEntry(
                path = path,
                status = obj.textAny("status", "kind").ifBlank { "M" },
                diff = diff,
                isBinary = isBinary,
                truncated = truncated,
            )
        }
    }

    fun archiveConversation(origin: HostOrigin, token: String, conversationId: String) {
        call(origin, token, "conversation_archive", mapOf("conversationId" to conversationId))
    }

    fun setConversationPinned(origin: HostOrigin, token: String, conversationId: String, pinned: Boolean) {
        call(origin, token, "conversation_set_pinned", mapOf("conversationId" to conversationId, "pinned" to pinned))
    }

    fun deleteConversation(origin: HostOrigin, token: String, conversationId: String) {
        call(origin, token, "conversation_delete", mapOf("conversationId" to conversationId))
    }

    fun renameConversation(origin: HostOrigin, token: String, conversationId: String, title: String) {
        call(origin, token, "conversation_rename", mapOf("conversationId" to conversationId, "title" to title))
    }

    fun setConversationStatus(origin: HostOrigin, token: String, conversationId: String, status: String) {
        call(origin, token, "conversation_set_status", mapOf("conversationId" to conversationId, "status" to status))
    }

    fun listArtifacts(origin: HostOrigin, token: String, conversationId: String): List<String> {
        val data = runCatching {
            call(origin, token, "artifact_list", mapOf("conversationId" to conversationId, "limit" to 20.0))
        }.getOrNull() ?: return emptyList()
        val items = data.asArray()?.value ?: (data.asObject()?.arrOrNull("artifacts") ?: emptyList())
        return items.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            obj.textAny("relativePath", "relative_path", "path", "name").takeIf { it.isNotBlank() }
        }
    }

    fun setSessionMode(origin: HostOrigin, token: String, conversationId: String, modeId: String) {
        call(
            origin,
            token,
            "conversation_set_session_mode",
            mapOf("conversationId" to conversationId, "modeId" to modeId),
        )
    }

    fun setSessionConfigOption(
        origin: HostOrigin,
        token: String,
        conversationId: String,
        key: String,
        value: String,
    ) {
        call(
            origin,
            token,
            "conversation_set_session_config_option",
            mapOf(
                "conversationId" to conversationId,
                "key" to key,
                "value" to value,
            ),
        )
    }

    fun createWorkspace(
        origin: HostOrigin,
        token: String,
        projectId: String,
        name: String,
        branch: String?,
    ): CatalogWorkspace {
        val args = linkedMapOf<String, Any?>(
            "projectId" to projectId,
            "name" to name,
            "branch" to branch,
        )
        val data = call(origin, token, "conversation_workspace_create", args).asObject()
            ?: error("workspace payload missing")
        return CatalogWorkspace(
            data.textAny("id"),
            data.textAny("projectId", "project_id"),
            data.textAny("name"),
            data.textAny("branch"),
        )
    }

    fun createConversation(
        origin: HostOrigin,
        token: String,
        workspaceId: String,
        agentId: String,
        title: String?,
        initialPrompt: String?,
    ): ConversationSummary {
        val args = linkedMapOf<String, Any?>(
            "workspaceId" to workspaceId,
            "agentId" to agentId,
            "title" to title,
            "initialPrompt" to initialPrompt,
        )
        return parseSummary(call(origin, token, "conversation_create", args))
    }

    fun submitInput(
        origin: HostOrigin,
        token: String,
        conversationId: String,
        agentId: String,
        workspaceId: String,
        text: String,
    ) {
        call(
            origin,
            token,
            "conversation_input_submit",
            mapOf(
                "request" to mapOf(
                    "conversationId" to conversationId,
                    "payload" to mapOf(
                        "agentId" to agentId,
                        "workspaceId" to workspaceId,
                        "text" to text,
                    ),
                ),
            ),
        )
    }

    fun steer(
        origin: HostOrigin,
        token: String,
        conversationId: String,
        expectedTurnId: String,
        text: String,
    ) {
        call(
            origin,
            token,
            "conversation_steer",
            mapOf(
                "request" to mapOf(
                    "conversationId" to conversationId,
                    "expectedTurnId" to expectedTurnId,
                    "text" to text,
                ),
            ),
        )
    }


    fun cancelConversationInput(
        origin: HostOrigin,
        token: String,
        conversationId: String,
        inputId: String,
        expectedRevision: Long,
    ) {
        call(
            origin,
            token,
            "conversation_input_cancel",
            mapOf(
                "request" to mapOf(
                    "conversationId" to conversationId,
                    "inputId" to inputId,
                    "expectedRevision" to expectedRevision.toDouble(),
                ),
            ),
        )
    }

    fun cancelTurn(origin: HostOrigin, token: String, conversationId: String) {
        call(
            origin,
            token,
            "conversation_cancel_turn",
            mapOf("request" to mapOf("conversationId" to conversationId)),
        )
    }

    fun respondPermission(
        origin: HostOrigin,
        token: String,
        conversationId: String,
        permissionId: String,
        optionId: String?,
        persist: Boolean = false,
    ) {
        val response: Map<String, Any?> = if (!optionId.isNullOrBlank()) {
            buildMap {
                put("kind", "selected")
                put("option_id", optionId)
                if (persist) put("persist", true)
            }
        } else {
            mapOf("kind" to "cancelled")
        }
        call(
            origin,
            token,
            "conversation_respond_permission",
            mapOf(
                "request" to mapOf(
                    "conversationId" to conversationId,
                    "permissionId" to permissionId,
                    "response" to response,
                ),
            ),
        )
    }

    fun respondQuestion(
        origin: HostOrigin,
        token: String,
        conversationId: String,
        questionId: String,
        content: String,
    ) {
        respondQuestion(
            origin = origin,
            token = token,
            conversationId = conversationId,
            questionId = questionId,
            content = JsonValue.Object(mapOf("text" to JsonValue.Text(content))),
        )
    }

    fun respondQuestion(
        origin: HostOrigin,
        token: String,
        conversationId: String,
        questionId: String,
        content: JsonValue?,
    ) {
        val response: Map<String, Any?> = if (content != null) {
            mapOf(
                "action" to "accept",
                "content" to content,
            )
        } else {
            mapOf("action" to "decline")
        }
        call(
            origin,
            token,
            "conversation_respond_question",
            mapOf(
                "request" to mapOf(
                    "conversationId" to conversationId,
                    "questionId" to questionId,
                    "response" to response,
                ),
            ),
        )
    }

    fun offlineEvents(origin: HostOrigin, token: String, conversationId: String, after: Long): List<RemoteEvent> {
        val body = request(
            origin,
            token,
            "GET",
            "/api/v1/conversations/$conversationId/offline?after_sequence=$after",
            null,
        )
        val root = JsonValue.parse(body) as JsonValue.Object
        return root.arrOrNull("events").orEmpty().mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val kind = obj.textAny("kind", "event_kind", "eventKind")
            if (kind.isBlank()) return@mapNotNull null
            RemoteEvent(
                sequence = obj.longOrNull("sequence") ?: return@mapNotNull null,
                kind = kind,
                payload = obj.value["payload"] ?: obj.value["normalized_json"] ?: JsonValue.Null,
            )
        }
    }

    /**
     * Fetches the JSONL transcript rows via `conversation_timeline_page` (ADR-0082).
     * Assistant text, thinking, tool calls, and plans live in the JSONL transcript
     * rather than `conversation_events`.
     */
    fun timelineRows(origin: HostOrigin, token: String, conversationId: String): List<JsonValue> {
        val allRows = mutableListOf<JsonValue>()
        var cursor: String? = null
        for (pageIdx in 0 until 20) {
            val args = linkedMapOf<String, Any?>(
                "conversationId" to conversationId,
                "limit" to 200.0,
            )
            if (!cursor.isNullOrBlank()) {
                args["cursor"] = cursor
            }
            val data = runCatching {
                call(origin, token, "conversation_timeline_page", args)
            }.getOrNull()?.asObject() ?: break
            val rows = data.arrOrNull("rows").orEmpty()
            allRows.addAll(rows)
            val next = data.textOrNull("next_cursor")?.takeIf { it.isNotBlank() }
                ?: data.textOrNull("nextCursor")?.takeIf { it.isNotBlank() }
            if (next == null || next == cursor) break
            cursor = next
        }
        return allRows
    }

    fun ensureSessionControls(origin: HostOrigin, token: String, conversationId: String): JsonValue? =
        runCatching {
            call(
                origin,
                token,
                "conversation_ensure_session_controls",
                mapOf("conversationId" to conversationId),
            )
        }.getOrNull()

    fun notificationSummary(origin: HostOrigin, token: String, conversationId: String): String? {
        val body = runCatching {
            request(
                origin,
                token,
                "GET",
                "/api/v1/conversations/$conversationId/notification-summary",
                null,
            )
        }.getOrNull() ?: return null
        val root = JsonValue.parse(body) as? JsonValue.Object ?: return null
        val outcome = root.textOrNull("outcome") ?: return null
        val at = root.textOrNull("occurred_at").orEmpty()
        return "$outcome · $at"
    }

    fun listWorkspaceDiffs(
        origin: HostOrigin,
        token: String,
        workspaceId: String,
        conversationId: String = "",
    ): List<WorkspaceFileDiff> {
        val raw = runCatching {
            val args = linkedMapOf<String, Any?>()
            if (workspaceId.isNotBlank()) args["workspaceId"] = workspaceId
            if (conversationId.isNotBlank()) args["conversationId"] = conversationId
            call(origin, token, "conversation_workspace_diff", args)
        }.getOrNull()
        val items = raw?.asArray()?.value ?: raw?.asObject()?.arrOrNull("files", "diffs", "items").orEmpty()
        return items.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val path = obj.textAny("path", "filePath", "file_path", "name")
            if (path.isBlank()) return@mapNotNull null
            val diff = obj.textAny("diff", "patch", "content")
            val lines = diff.lines()
            val added = (obj.longOrNull("addedLines")?.toInt() ?: obj.longOrNull("added_lines")?.toInt())
                ?: lines.count { it.startsWith("+") && !it.startsWith("+++") }
            val removed = (obj.longOrNull("removedLines")?.toInt() ?: obj.longOrNull("removed_lines")?.toInt())
                ?: lines.count { it.startsWith("-") && !it.startsWith("---") }
            WorkspaceFileDiff(
                path = path,
                status = obj.textAny("status", "kind").ifBlank { "modified" },
                diff = diff,
                addedLines = added,
                removedLines = removed,
            )
        }
    }

    fun collaboration(origin: HostOrigin, token: String, operation: String, input: Map<String, Any?>): JsonValue {
        return call(
            origin = origin,
            token = token,
            command = "collaboration_call",
            args = mapOf(
                "capability" to "collaboration",
                "operation" to operation,
                "input" to input,
            ),
        )
    }

    fun listCollaborationThreads(
        origin: HostOrigin,
        token: String,
        workspaceId: String,
    ): List<CollaborationThreadSummary> {
        val results = mutableListOf<CollaborationThreadSummary>()
        val seenIds = mutableSetOf<String>()
        if (workspaceId.isNotBlank()) {
            runCatching {
                collaboration(origin, token, "thread.listSidebar", mapOf("workspaceId" to workspaceId))
            }.getOrNull()?.let { root ->
                val arr = root.asArray()?.value ?: root.asObject()?.arrOrNull("items", "threads").orEmpty()
                arr.forEach { item ->
                    val obj = item.asObject() ?: return@forEach
                    val id = obj.textAny("threadId", "thread_id", "spaceId", "space_id", "id")
                    if (id.isBlank() || !seenIds.add(id)) return@forEach
                    val kind = obj.textAny("kind").ifBlank { if (id.startsWith("direct:")) "dm" else "group" }
                    val name = obj.textAny("name", "title", "displayName").ifBlank { "协作会话" }
                    val preview = obj.textAny("lastMessagePreview", "last_message_preview", "preview", "description", "summary")
                    results += CollaborationThreadSummary(
                        id = id,
                        kind = kind,
                        name = name,
                        subtitle = preview.ifBlank { if (kind == "dm") "私聊助手" else "协作群组" },
                        agentId = obj.textAny("agentId", "agent_id"),
                        memberId = obj.textAny("memberId", "member_id").ifBlank { id.removePrefix("direct:") },
                        unreadCount = (obj.longOrNull("unreadCount") ?: obj.longOrNull("unread_count") ?: 0L).toInt(),
                        updatedAt = obj.textAny("updatedAt", "updated_at", "lastMessageAt"),
                    )
                }
            }
            runCatching {
                collaboration(origin, token, "member.list", mapOf("workspaceId" to workspaceId))
            }.getOrNull()?.let { root ->
                val arr = root.asArray()?.value ?: root.asObject()?.arrOrNull("members", "items").orEmpty()
                arr.forEach { item ->
                    val obj = item.asObject() ?: return@forEach
                    val memberId = obj.textAny("id", "memberId", "member_id")
                    if (memberId.isBlank()) return@forEach
                    val threadId = "direct:$memberId"
                    if (!seenIds.add(threadId)) return@forEach
                    results += CollaborationThreadSummary(
                        id = threadId,
                        kind = "dm",
                        name = obj.textAny("name", "displayName").ifBlank { "助手" },
                        subtitle = obj.textAny("description", "summary", "roleName").ifBlank { "一对一私聊助手" },
                        agentId = obj.textAny("agentId", "agent_id"),
                        memberId = memberId,
                    )
                }
            }
        }
        return results
    }

    fun listCollaborationMessages(
        origin: HostOrigin,
        token: String,
        threadId: String,
    ): List<CollaborationMessageItem> {
        val raw = runCatching {
            collaboration(
                origin,
                token,
                "space.listMessages",
                mapOf(
                    "spaceId" to threadId,
                    "latest" to true,
                    "limit" to 120.0,
                ),
            )
        }.getOrNull() ?: return emptyList()
        val arr = raw.asArray()?.value ?: raw.asObject()?.arrOrNull("messages", "items").orEmpty()
        return arr.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val content = obj.textAny("content", "text", "body")
            if (content.isBlank()) return@mapNotNull null
            val senderKind = obj.textAny("senderKind", "sender_kind", "role").ifBlank { "user" }
            val senderName = obj.textAny("senderName", "sender_name", "authorName", "memberName")
                .ifBlank { if (senderKind == "user") "我" else "助手" }
            val receipts = obj.arrOrNull("receipts", "deliveries").orEmpty().mapNotNull { r ->
                val ro = r.asObject() ?: return@mapNotNull null
                val target = ro.textAny("memberName", "member_name", "name", "memberId")
                val status = ro.textAny("status")
                if (status.isBlank()) null else if (target.isNotBlank()) "$target:$status" else status
            }
            CollaborationMessageItem(
                id = obj.textAny("id", "messageId", "message_id").ifBlank { UUID.randomUUID().toString() },
                seq = obj.longOrNull("seq") ?: obj.longOrNull("sequence") ?: 0L,
                senderName = senderName,
                senderKind = senderKind,
                agentId = obj.textAny("agentId", "agent_id"),
                content = content,
                createdAt = obj.textAny("createdAt", "created_at"),
                receipts = receipts,
            )
        }
    }

    fun submitCollaborationMessage(
        origin: HostOrigin,
        token: String,
        threadId: String,
        content: String,
    ) {
        collaboration(
            origin,
            token,
            "space.submit",
            mapOf(
                "spaceId" to threadId,
                "content" to content,
                "operationId" to UUID.randomUUID().toString(),
            ),
        )
    }

    fun createCollaborationGroup(
        origin: HostOrigin,
        token: String,
        workspaceId: String,
        name: String,
    ) {
        collaboration(
            origin,
            token,
            "space.create",
            mapOf(
                "workspaceId" to workspaceId,
                "name" to name,
                "operationId" to UUID.randomUUID().toString(),
            ),
        )
    }

    fun createCollaborationBot(
        origin: HostOrigin,
        token: String,
        workspaceId: String,
        name: String,
        agentId: String,
        description: String,
    ) {
        collaboration(
            origin,
            token,
            "member.create",
            mapOf(
                "workspaceId" to workspaceId,
                "name" to name,
                "agentId" to agentId,
                "description" to description,
                "operationId" to UUID.randomUUID().toString(),
            ),
        )
    }

    fun listSkillsCatalog(origin: HostOrigin, token: String): List<ExtensionCatalogItem> {
        val raw = runCatching { call(origin, token, "scan_local_skills", emptyMap()) }.getOrNull()
        val arr = raw?.asArray()?.value ?: raw?.asObject()?.arrOrNull("skills", "items").orEmpty()
        return arr.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val name = obj.textAny("name", "title", "id")
            if (name.isBlank()) return@mapNotNull null
            ExtensionCatalogItem(
                id = obj.textAny("id", "path", "name").ifBlank { name },
                name = name,
                summary = obj.textAny("description", "summary", "path"),
                kind = obj.textAny("scope", "source", "kind").ifBlank { "Skill" },
                status = "已就绪",
            )
        }
    }

    fun listMcpCatalog(origin: HostOrigin, token: String): List<ExtensionCatalogItem> {
        val raw = runCatching { call(origin, token, "mcp_scan_local", emptyMap()) }.getOrNull()
        val arr = raw?.asArray()?.value ?: raw?.asObject()?.arrOrNull("servers", "items").orEmpty()
        return arr.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val name = obj.textAny("name", "title", "id")
            if (name.isBlank()) return@mapNotNull null
            val enabled = obj.boolOrNull("enabled") != false
            ExtensionCatalogItem(
                id = obj.textAny("id", "name").ifBlank { name },
                name = name,
                summary = obj.textAny("command", "url", "description", "summary"),
                kind = obj.textAny("transport", "kind").ifBlank { "MCP" },
                status = if (enabled) "已启用" else "已禁用",
            )
        }
    }

    fun listPluginCatalog(origin: HostOrigin, token: String): List<ExtensionCatalogItem> {
        val raw = runCatching { call(origin, token, "plugin_control_catalog", emptyMap()) }.getOrNull()
        val arr = raw?.asArray()?.value ?: raw?.asObject()?.arrOrNull("plugins", "items", "installed").orEmpty()
        return arr.mapNotNull { item ->
            val obj = item.asObject() ?: return@mapNotNull null
            val name = obj.textAny("name", "displayName", "title", "id")
            if (name.isBlank()) return@mapNotNull null
            val active = obj.boolOrNull("enabled") == true || obj.boolOrNull("active") == true
            ExtensionCatalogItem(
                id = obj.textAny("id", "pluginId", "name").ifBlank { name },
                name = name,
                summary = obj.textAny("summary", "description", "version"),
                kind = obj.textAny("publisher", "category").ifBlank { "Plugin" },
                status = if (active) "已启用" else "已安装",
            )
        }
    }

    fun listSubagentRoles(origin: HostOrigin, token: String, workspaceId: String): List<SubagentRoleItem> {
        val teamsById = mutableMapOf<String, String>()
        if (workspaceId.isNotBlank()) {
            runCatching {
                collaboration(origin, token, "team.list", mapOf("workspaceId" to workspaceId))
            }.getOrNull()?.let { root ->
                val arr = root.asArray()?.value ?: root.asObject()?.arrOrNull("teams", "items").orEmpty()
                arr.forEach { item ->
                    val obj = item.asObject() ?: return@forEach
                    val id = obj.textAny("id", "teamId")
                    val name = obj.textAny("name", "title")
                    if (id.isNotBlank() && name.isNotBlank()) teamsById[id] = name
                }
            }
        }
        val roles = mutableListOf<SubagentRoleItem>()
        if (workspaceId.isNotBlank()) {
            runCatching {
                collaboration(origin, token, "role.list", mapOf("workspaceId" to workspaceId))
            }.getOrNull()?.let { root ->
                val arr = root.asArray()?.value ?: root.asObject()?.arrOrNull("roles", "items").orEmpty()
                arr.forEach { item ->
                    val obj = item.asObject() ?: return@forEach
                    val id = obj.textAny("id", "roleId", "name")
                    val name = obj.textAny("name", "title")
                    if (name.isBlank()) return@forEach
                    val teamId = obj.textAny("teamId", "team_id")
                    roles += SubagentRoleItem(
                        id = id.ifBlank { name },
                        name = name,
                        teamId = teamId,
                        teamName = obj.textAny("teamName", "team_name").ifBlank { teamsById[teamId] ?: "默认团队" },
                        summary = obj.textAny("description", "summary", "prompt"),
                        agentId = obj.textAny("agentId", "agent_id").ifBlank { "claude_code" },
                        isBuiltin = false,
                    )
                }
            }
        }
        if (roles.isNotEmpty()) return roles
        return listOf(
            SubagentRoleItem("architect", "系统架构师", "core", "核心研发组", "负责模块边界设计、ADR 评审与重构拆解", "claude_code", true),
            SubagentRoleItem("fullstack", "全栈工程师", "core", "核心研发组", "负责 Rust 后端、Tauri IPC 与前端功能端到端实现", "codex", true),
            SubagentRoleItem("reviewer", "代码审查员", "qa", "质量保障组", "执行规范与规格双轴审查，拦截回归与架构违规", "claude_code", true),
            SubagentRoleItem("debugger", "故障诊断专家", "qa", "质量保障组", "定位复杂缺陷、并发竞态与性能退化根因", "codex", true),
        )
    }

    fun call(origin: HostOrigin, token: String, command: String, args: Map<String, Any?>): JsonValue {
        val operationId = UUID.randomUUID().toString()
        val body = encodeObject(
            mapOf(
                "operation_id" to operationId,
                "args" to args,
            ),
        )
        val response = request(origin, token, "POST", "/api/v1/call/$command", body)
        val root = JsonValue.parse(response) as JsonValue.Object
        return root.value["data"] ?: JsonValue.Null
    }

    private fun request(
        origin: HostOrigin,
        token: String,
        method: String,
        path: String,
        body: String?,
    ): String {
        val headers = mutableMapOf(
            "authorization" to "Bearer $token",
            PairingClient.PROTOCOL_HEADER to protocolVersion,
        )
        if (body != null) headers["content-type"] = "application/json"
        val exchange = transport.execute(method, origin.resolve(path), headers, body)
        if (exchange.status == 401) throw AuthRequiredException()
        if (exchange.status !in 200..299) {
            val message = runCatching {
                (JsonValue.parse(exchange.body) as JsonValue.Object).textOrNull("message")
            }.getOrNull() ?: "Host returned ${exchange.status}"
            throw HostApiException(exchange.status, message)
        }
        return exchange.body
    }

    private fun parseSummaries(data: JsonValue): List<ConversationSummary> {
        val items = data.asArray()?.value ?: return emptyList()
        return items.mapNotNull { parseSummaryOrNull(it) }
    }

    private fun parseSummary(data: JsonValue): ConversationSummary =
        parseSummaryOrNull(data) ?: error("conversation payload missing")

    private fun parseSummaryOrNull(data: JsonValue): ConversationSummary? {
        val obj = data.asObject() ?: return null
        val id = obj.textOrNull("id") ?: return null
        return ConversationSummary(
            id = id,
            workspaceId = obj.textAny("workspaceId", "workspace_id"),
            title = obj.textAny("title", "name").ifBlank { "会话" },
            agentId = obj.textAny("agentId", "agent_id"),
            status = obj.textAny("status"),
            updatedAt = obj.textAny("updatedAt", "updated_at"),
            pinned = obj.boolOrNull("pinned") == true || obj.textAny("pinnedAt", "pinned_at").isNotBlank(),
        )
    }
}

class HostApiException(val status: Int, message: String) : RuntimeException(message)
class AuthRequiredException : RuntimeException("需要重新配对")

private fun JsonValue.Object.arrOrNull(vararg keys: String): List<JsonValue>? =
    keys.firstNotNullOfOrNull { arrOrNull(it) }

private fun parseJsonOptions(obj: JsonValue.Object, vararg keys: String): List<SessionConfigOption> {
    keys.forEach { key ->
        val node = obj.value[key] ?: return@forEach
        val parsed = sessionConfigItems(node).mapNotNull { EventFold.parseConfigOption(it) }
        if (parsed.isNotEmpty()) return parsed
    }
    return emptyList()
}

private fun parseJsonModes(obj: JsonValue.Object, vararg keys: String): List<SessionMode> {
    keys.forEach { key ->
        val node = obj.value[key] ?: return@forEach
        val parsed = sessionModeItems(node).mapNotNull { parseSessionMode(it) }
        if (parsed.isNotEmpty()) return parsed
    }
    return emptyList()
}

private fun extractModesFromControls(obj: JsonValue.Object): List<SessionMode> {
    listOf("sessionConfig", "session_config", "sessionConfigJson", "session_config_json", "controls").forEach { key ->
        val wrapper = unwrapObject(obj.value[key]) ?: return@forEach
        val parsed = parseJsonModes(wrapper, "modes", "sessionModes", "session_modes")
        if (parsed.isNotEmpty()) return parsed
    }
    return emptyList()
}

private fun parseCurrentMode(obj: JsonValue.Object): String {
    obj.textAny("currentMode", "current_mode").takeIf { it.isNotBlank() }?.let { return it }
    listOf("sessionConfig", "session_config", "sessionConfigJson", "session_config_json", "controls").forEach { key ->
        val wrapper = unwrapObject(obj.value[key]) ?: return@forEach
        wrapper.textAny("currentMode", "current_mode").takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

private fun parseSessionMode(item: JsonValue): SessionMode? {
    val mode = item.asObject() ?: return null
    val id = mode.textAny("id")
    if (id.isBlank()) return null
    return SessionMode(id, mode.textAny("label", "name").ifBlank { id })
}

private fun sessionConfigItems(node: JsonValue): List<JsonValue> = when (node) {
    is JsonValue.Array -> node.value
    is JsonValue.Text -> {
        val parsed = runCatching { JsonValue.parse(node.value) }.getOrNull() ?: return emptyList()
        sessionConfigItems(parsed)
    }
    is JsonValue.Object -> {
        listOf("config_options", "configOptions", "sessionConfig", "session_config", "options", "choices", "value")
            .firstNotNullOfOrNull { key ->
                node.value[key]?.let { sessionConfigItems(it) }?.takeIf { it.isNotEmpty() }
            }
            .orEmpty()
    }
    else -> emptyList()
}

private fun sessionModeItems(node: JsonValue): List<JsonValue> = when (node) {
    is JsonValue.Array -> node.value
    is JsonValue.Text -> {
        val parsed = runCatching { JsonValue.parse(node.value) }.getOrNull() ?: return emptyList()
        sessionModeItems(parsed)
    }
    is JsonValue.Object -> {
        val nested = node.value["modes"] ?: node.value["sessionModes"] ?: node.value["session_modes"]
        if (nested != null) sessionModeItems(nested) else emptyList()
    }
    else -> emptyList()
}

private fun unwrapObject(node: JsonValue?): JsonValue.Object? = when (node) {
    is JsonValue.Object -> node
    is JsonValue.Text -> runCatching { JsonValue.parse(node.value) }.getOrNull()?.asObject()
    else -> null
}

internal fun parseCatalogAgents(
    root: JsonValue.Object,
    managementBar: JsonValue? = null,
): List<CatalogAgent> {
    val rawAgents = root.arrOrNull("agents").orEmpty()
    if (rawAgents.isEmpty()) return emptyList()

    var managementRunnableIds: Set<String>? = null
    val managementPositions = mutableMapOf<String, Int>()
    val barRows = managementBar?.asArray()?.value
        ?: managementBar?.asObject()?.arrOrNull("items", "entries")
    if (!barRows.isNullOrEmpty()) {
        val runnable = mutableSetOf<String>()
        var sawBarEntry = false
        barRows.forEachIndexed { idx, rowItem ->
            val row = rowItem.asObject() ?: return@forEachIndexed
            val id = row.textAny("agent_id", "agentId").trim()
            if (id.isEmpty()) return@forEachIndexed
            sawBarEntry = true
            val pos = row.longOrNull("position")?.toInt() ?: idx
            managementPositions[id] = pos
            val enabled = row.boolOrNull("enabled") == true
            val retired = row.boolOrNull("retired") == true
            val lifecycle = row.textAny("lifecycle").trim().lowercase()
            val activeOp = row.textAny("active_operation", "activeOperation").trim()
            val localPath = (
                row.objOrNull("local_runtime")?.textAny("path")
                    ?: row.objOrNull("localRuntime")?.textAny("path")
                ).orEmpty().trim()
            if (enabled && !retired && activeOp.isEmpty() && (lifecycle == "ready" || localPath.isNotEmpty())) {
                runnable += id
            }
        }
        if (sawBarEntry) {
            managementRunnableIds = runnable
        }
    }

    val hasLifecycleSignal = rawAgents.any { item ->
        item.asObject()?.textAny("lifecycle")?.trim()?.isNotEmpty() == true
    }

    val indexed = rawAgents.mapIndexedNotNull { originalIdx, item ->
        val obj = item.asObject() ?: return@mapIndexedNotNull null
        val agentId = obj.textAny("id")
        if (agentId.isBlank()) return@mapIndexedNotNull null
        val ready = obj.boolOrNull("ready") ?: true
        val lifecycle = obj.textAny("lifecycle").trim()
        val authentication = obj.textAny("authentication")
        val parsedConfig = parseJsonOptions(
            obj,
            "sessionConfigJson",
            "session_config_json",
            "sessionConfig",
            "session_config",
            "configOptions",
            "config_options",
            "controls",
        )
        val parsedModes = parseJsonModes(
            obj,
            "sessionModesJson",
            "session_modes_json",
            "sessionModes",
            "session_modes",
        ).ifEmpty { extractModesFromControls(obj) }
        val catalogUsable = obj.boolOrNull("usable") ?: agentIsUsable(ready, lifecycle, authentication)
        val runnableSet = managementRunnableIds
        val effectiveUsable = when {
            runnableSet != null -> catalogUsable && agentId in runnableSet
            hasLifecycleSignal -> {
                val isInstalledByCatalog = lifecycle.equals("ready", ignoreCase = true) ||
                    parsedConfig.isNotEmpty() ||
                    parsedModes.isNotEmpty()
                catalogUsable && isInstalledByCatalog
            }
            else -> catalogUsable
        }
        originalIdx to CatalogAgent(
            id = agentId,
            ready = ready,
            displayName = obj.textAny("displayName", "display_name", "name").ifBlank { agentId },
            iconSvg = obj.textAny("iconSvg", "icon_svg"),
            currentModeId = parseCurrentMode(obj),
            sessionConfig = parsedConfig,
            sessionModes = parsedModes,
            usable = effectiveUsable,
            lifecycle = lifecycle,
            authentication = authentication,
        )
    }

    if (managementPositions.isEmpty()) {
        return indexed.map { it.second }
    }
    return indexed.sortedWith { lhs, rhs ->
        val lPos = managementPositions[lhs.second.id] ?: (10_000 + lhs.first)
        val rPos = managementPositions[rhs.second.id] ?: (10_000 + rhs.first)
        if (lPos != rPos) lPos.compareTo(rPos) else lhs.first.compareTo(rhs.first)
    }.map { it.second }
}

fun fallbackModelOptionForAgent(agentId: String): SessionConfigOption {
    val lower = agentId.lowercase()
    return when {
        lower.contains("claude") -> SessionConfigOption(
            key = "model",
            label = "模型",
            category = "model",
            value = "opus",
            choices = listOf(
                SessionConfigChoice("opus", "Claude Opus 4.6", "最强深度推理与复杂工程"),
                SessionConfigChoice("sonnet", "Claude Sonnet 4.6", "日常开发主力 · 均衡高速"),
                SessionConfigChoice("haiku", "Claude Haiku 4.5", "轻量极速响应"),
                SessionConfigChoice("claude-opus-4-8", "claude-opus-4-8", "自定义 / 中转 Opus"),
                SessionConfigChoice("claude-sonnet-5", "claude-sonnet-5", "自定义 / 中转 Sonnet"),
                SessionConfigChoice("claude-haiku-5-5", "claude-haiku-5-5", "自定义 / 中转 Haiku"),
            ),
        )
        lower.contains("codex") || lower.contains("openai") -> SessionConfigOption(
            key = "model",
            label = "模型",
            category = "model",
            value = "gpt-5.4",
            choices = listOf(
                SessionConfigChoice("gpt-5.4", "GPT-5.4", "OpenAI 旗舰代码与推理模型"),
                SessionConfigChoice("gpt-5.3-codex", "GPT-5.3 Codex", "专为软件工程优化的 Codex 模型"),
                SessionConfigChoice("gpt-5.2-codex", "GPT-5.2 Codex", "稳健高速代码生成"),
                SessionConfigChoice("gpt-6-sol", "gpt-6-sol", "自定义 / 中转旗舰模型"),
                SessionConfigChoice("o3", "o3", "深度推理模型"),
                SessionConfigChoice("o4-mini", "o4-mini", "高性价比快速推理"),
            ),
        )
        lower.contains("gemini") || lower.contains("antigravity") -> SessionConfigOption(
            key = "model",
            label = "模型",
            category = "model",
            value = "gemini-2.5-pro",
            choices = listOf(
                SessionConfigChoice("gemini-2.5-pro", "Gemini 2.5 Pro", "旗舰多模态与深度长上下文推理"),
                SessionConfigChoice("gemini-2.5-flash", "Gemini 2.5 Flash", "高速低延迟日常主力"),
                SessionConfigChoice("gemini-3.8-flash", "Gemini 3.8 Flash", "新一代高速推理"),
                SessionConfigChoice("gemini-4-argon", "Gemini 4 Argon", "前沿旗舰推理"),
            ),
        )
        lower.contains("kimi") -> SessionConfigOption(
            key = "model",
            label = "模型",
            category = "model",
            value = "kimi-k2",
            choices = listOf(
                SessionConfigChoice("kimi-k2", "Kimi K2", "Moonshot 旗舰代码与推理"),
                SessionConfigChoice("kimi-latest", "Kimi Latest", "最新默认模型"),
                SessionConfigChoice("moonshot-v1-128k", "Moonshot v1 128K", "长上下文模型"),
            ),
        )
        lower.contains("deepseek") -> SessionConfigOption(
            key = "model",
            label = "模型",
            category = "model",
            value = "deepseek-chat",
            choices = listOf(
                SessionConfigChoice("deepseek-chat", "DeepSeek V3", "高效日常对话与编码"),
                SessionConfigChoice("deepseek-reasoner", "DeepSeek R1", "深度思考推理模型"),
            ),
        )
        lower.contains("grok") -> SessionConfigOption(
            key = "model",
            label = "模型",
            category = "model",
            value = "grok-4",
            choices = listOf(
                SessionConfigChoice("grok-4", "Grok 4", "xAI 旗舰推理模型"),
                SessionConfigChoice("grok-3", "Grok 3", "标准主力模型"),
                SessionConfigChoice("grok-3-mini", "Grok 3 Mini", "轻量快速思考"),
            ),
        )
        else -> SessionConfigOption(
            key = "model",
            label = "模型",
            category = "model",
            value = "default",
            choices = listOf(
                SessionConfigChoice("default", "默认模型 (跟随电脑端配置)", "使用电脑端当前配置的默认模型"),
                SessionConfigChoice("claude-sonnet-4-6", "Claude Sonnet 4.6", "通用主力代码模型"),
                SessionConfigChoice("claude-opus-4-6", "Claude Opus 4.6", "深度推理旗舰"),
                SessionConfigChoice("gpt-5.4", "GPT-5.4", "OpenAI 旗舰模型"),
                SessionConfigChoice("gemini-2.5-pro", "Gemini 2.5 Pro", "Google 旗舰模型"),
            ),
        )
    }
}

private val SQLITE_UTC_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
    .appendPattern("yyyy-MM-dd HH:mm:ss")
    .optionalStart()
    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
    .optionalEnd()
    .toFormatter()

fun parseHostTimestampEpochMillis(raw: String?): Long? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    runCatching { return Instant.parse(trimmed).toEpochMilli() }
    runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
    runCatching {
        val normalized = trimmed.removeSuffix("Z").removeSuffix("z").trim()
        return LocalDateTime.parse(normalized, SQLITE_UTC_FORMATTER)
            .toInstant(ZoneOffset.UTC)
            .toEpochMilli()
    }
    return null
}

val KanbanSessionSummary.updatedEpochMs: Long?
    get() = parseHostTimestampEpochMillis(updatedAt) ?: parseHostTimestampEpochMillis(createdAt)

fun KanbanSessionSummary.isRecentlyActiveReadSession(
    manuallyCompletedIds: Set<String>,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (status != KanbanSessionStatus.Done || isRunning) return false
    if (id in manuallyCompletedIds) return false
    val epochMs = updatedEpochMs ?: return false
    return (nowMs - epochMs) <= 24L * 3600_000L
}

fun encodeObject(map: Map<String, Any?>): String = encodeValue(map).encode()

fun encodeValue(value: Any?): JsonValue = when (value) {
    null -> JsonValue.Null
    is JsonValue -> value
    is String -> JsonValue.Text(value)
    is Boolean -> JsonValue.Bool(value)
    is Int -> JsonValue.Number(value.toDouble())
    is Long -> JsonValue.Number(value.toDouble())
    is Double -> JsonValue.Number(value)
    is Map<*, *> -> JsonValue.Object(
        value.entries.associate { (k, v) -> k.toString() to encodeValue(v) },
    )
    is List<*> -> JsonValue.Array(value.map { encodeValue(it) })
    else -> JsonValue.Text(value.toString())
}
