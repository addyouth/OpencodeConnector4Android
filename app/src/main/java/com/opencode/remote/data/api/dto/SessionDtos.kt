package com.opencode.remote.data.api.dto

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 实际 API (v2): GET /api/session → {data: [Session.Info], cursor}
 * v2 Session.Info 核心字段: id, projectID, agent, model, cost, tokens, outcome,
 * time, title, location{directory}, parentID?, fork?
 * 保留 v1 兼容字段（slug/path/version/summary/permission/revert 在 v2 下为 null）。
 */
@Immutable
@Serializable
data class SessionInfo(
    val id: String = "",
    val slug: String? = null,
    @SerialName("projectID")
    val projectID: String? = null,
    /** v2 无顶层 directory —— 位于 location.directory；保留兼容，由翻译层或 location 提供 */
    val directory: String? = null,
    val path: String? = null,
    val title: String? = null,
    val version: String? = null,
    val summary: SessionSummary? = null,
    val permission: List<SessionPermission>? = null,
    @SerialName("parentID")
    val parentID: String? = null,
    val time: SessionTime? = null,
    /** Revert state — non-null when the session has an active undo. */
    val revert: SessionRevert? = null,
    /** v2 fork 链：子会话带 fork.sessionID 指向父（parentID 为 v1 遗留，v2 为空）。 */
    val fork: SessionForkRef? = null,
    // ─── v2 新增字段 ───
    /** v2 Session.Info.agent —— 会话当前 agent 名（需求②「进会话带主代理」的数据源） */
    val agent: String? = null,
    /** v2 Session.Info.model —— 会话当前模型 */
    val model: MessageModel? = null,
    /** v2 Session.Info.location —— {directory} 项目目录 */
    val location: SessionLocation? = null,
    /** v2 Session.Info.outcome —— succeeded/failed/… */
    val outcome: String? = null,
    val cost: Double? = null,
    val tokens: MessageTokens? = null,
) {
    /** 兼容属性：v2 下 directory = location.directory */
    val resolvedDirectory: String? get() = directory ?: location?.directory
}

/** v2 fork 引用：{sessionID(父), boundary?} */
@Serializable
data class SessionForkRef(
    @SerialName("sessionID")
    val sessionID: String? = null,
)

@Serializable
data class SessionLocation(
    val directory: String? = null,
)

/** Revert marker on a session (from POST /session/{id}/revert). */
@Serializable
data class SessionRevert(
    @SerialName("messageID")
    val messageID: String? = null,
    @SerialName("partID")
    val partID: String? = null,
    val snapshot: String? = null,
    val diff: String? = null,
)

@Serializable
data class SessionSummary(
    val additions: Int = 0,
    val deletions: Int = 0,
    val files: Int = 0,
)

@Serializable
data class SessionPermission(
    val permission: String? = null,
    val action: String = "",
    val pattern: String? = null,
)

@Serializable
data class SessionTime(
    val created: Long? = null,
    val updated: Long? = null,
    val initialized: Long? = null,
    val completed: Long? = null,
    @SerialName("time.archived")
    val archived: Long? = null,
)

/**
 * 实际 API (v2): POST /api/session → {data: Session.Info}
 * 翻译层从 Session.Info 映射本结构（v1 兼容字段保留为空）。
 */
@Serializable
data class CreateSessionResponse(
    val id: String = "",
    val slug: String? = null,
    val title: String? = null,
    @SerialName("projectID")
    val projectID: String? = null,
    val directory: String? = null,
    val path: String? = null,
    val version: String? = null,
    val time: SessionTime? = null,
    // ─── v2 字段 ───
    val agent: String? = null,
    val model: MessageModel? = null,
    val location: SessionLocation? = null,
) {
    companion object {
        /** 从 v2 Session.Info 映射 */
        fun fromSession(info: SessionInfo): CreateSessionResponse = CreateSessionResponse(
            id = info.id,
            title = info.title,
            projectID = info.projectID,
            directory = info.directory ?: info.location?.directory,
            path = info.path,
            version = info.version,
            time = info.time,
            agent = info.agent,
            model = info.model,
            location = info.location,
        )
    }
}

// ─── v2 请求体 ──────────────────────────────────────────────────────────

/**
 * v2: POST /api/session body
 * 字段: id?, title?, agent?, model?: Model.Ref, location?: Location.PublicRef, metadata?, permissions?
 * 全可选；encodeDefaults=false 时空字段不发送。
 */
@Serializable
data class V2CreateSessionBody(
    val title: String? = null,
    val agent: String? = null,
    val location: V2LocationRef? = null,
)

/** v2 Location.PublicRef: {directory: string} */
@Serializable
data class V2LocationRef(
    val directory: String? = null,
) {
    companion object {
        fun of(directory: String): V2LocationRef = V2LocationRef(directory = directory)
    }
}

// session 列表就是 List<SessionInfo>，无需额外包装

/** Request body for POST /session/{id}/revert */
@Serializable
data class RevertRequest(
    @SerialName("messageID")
    val messageID: String,
)

/**
 * 实际 API: GET /session/{id}/message 返回的消息
 * 消息有 info + parts 两层结构
 */
@Immutable
@Serializable
data class MessageInfo(
    val info: MessageInfoData = MessageInfoData(),
    val parts: List<MessagePart> = emptyList(),
) {
    /** 兼容属性：从 info 提取 */
    val id: String get() = info.id
    val role: String get() = info.role
}

@Serializable
data class MessageInfoData(
    val id: String = "",
    val role: String = "",
    @SerialName("sessionID")
    val sessionID: String? = null,
    @SerialName("parentID")
    val parentID: String? = null,
    val agent: String? = null,
    val mode: String? = null,
    val model: MessageModel? = null,
    val time: MessageTime? = null,
    val finish: String? = null,
    val cost: Double? = null,
    val tokens: MessageTokens? = null,
    /** 服务器可能返回 boolean(true) 或 object({diffs:[...]})，用 JsonElement 兼容 */
    val summary: JsonElement? = null,
    val variant: String? = null,
) {
    val resolvedVariant: String? get() = model?.variant ?: variant
}

@Serializable
data class MessageModel(
    @SerialName("providerID")
    val providerID: String? = null,
    @SerialName("modelID")
    val modelID: String? = null,
    val variant: String? = null,
    /** v2 Model.Ref 用 `id` 而非 `modelID`——自动反序列化时兜底映射 */
    val id: String? = null,
) {
    /** v1 用 modelID，v2 用 id；两者都可能的统一取法 */
    val resolvedModelID: String? get() = modelID ?: id
}

@Serializable
data class MessageTime(
    val created: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class MessageTokens(
    val total: Int? = null,
    val input: Int? = null,
    val output: Int? = null,
    val reasoning: Int? = null,
    val cache: CacheTokens? = null,
    @SerialName("cacheRead")
    val cacheRead: Int? = null,
    @SerialName("cacheWrite")
    val cacheWrite: Int? = null,
) {
    /** Comprehensive token sum: prefers `total`, otherwise sums all available fields. */
    fun tokenTotal(): Int {
        if (total != null && total > 0) return total
        return (input ?: 0) +
            (output ?: 0) +
            (reasoning ?: 0) +
            (cacheRead ?: cache?.read ?: 0) +
            (cacheWrite ?: cache?.write ?: 0)
    }
}

@Serializable
data class CacheTokens(
    val read: Int? = null,
    val write: Int? = null,
)

/**
 * 消息的 part —— 一个消息包含多个 part
 * type 可以是: "text", "step-start", "step-finish", "reasoning", "tool-call",
 * "file", "agent", "snapshot", "patch", "retry", "compaction", "subtask" 等
 */
@Immutable
@Serializable
data class MessagePart(
    val type: String = "text",
    val text: String? = null,
    val id: String? = null,
    @SerialName("sessionID")
    val sessionID: String? = null,
    @SerialName("messageID")
    val messageID: String? = null,
    val time: PartTime? = null,
    val reason: String? = null,
    val tokens: MessageTokens? = null,
    val cost: Double? = null,
    /** Tool name for type="tool" parts (e.g. "edit", "read", "bash", "glob"). */
    val tool: String? = null,
    /** Unique call identifier for tool parts. */
    @SerialName("callID")
    val callID: String? = null,
    /** Tool execution state: status, input, output. */
    val state: ToolState? = null,
    /** File name for type="file" parts. */
    val name: String? = null,
    /** File path for type="file" parts. */
    val path: String? = null,
)

@Serializable
data class PartTime(
    val start: Long? = null,
    val end: Long? = null,
)

/** Tool execution state from the server (type="tool" parts). */
@Serializable
data class ToolState(
    val status: String? = null,
    /** Input varies by tool: {filePath} for edit/read/write, {command} for bash, etc. */
    val input: JsonElement? = null,
    /** Tool output — can be very long (file contents, command output, etc.). */
    val output: String? = null,
    /** Metadata — can be a JSON object or string. */
    val metadata: JsonElement? = null,
    val title: String? = null,
    /** Time — can be a JSON object {start, end} or string. */
    val time: JsonElement? = null,
)

/**
 * 发送消息的请求体
 * 实际 API: POST /session/{id}/message + {"parts":[{"type":"text","text":"..."}]}
 */
@Serializable
data class SendMessageRequest(
    val parts: List<SendMessagePart>,
    val agent: String? = null,
    /** Nested model reference — server expects { "model": { "providerID": "...", "modelID": "..." } } */
    val model: ModelRef? = null,
    val variant: String? = null,
)

/** Model reference matching the server's PromptInput.model (ModelRef schema). */
@Serializable
data class ModelRef(
    val providerID: String? = null,
    val modelID: String? = null,
)

// ─── v2 请求体（续） ─────────────────────────────────────────────────────

/** v2 Model.Ref —— {id, providerID, variant?}（id 为模型 ID） */
@Serializable
data class V2ModelRef(
    val id: String? = null,
    @SerialName("providerID")
    val providerID: String? = null,
    val variant: String? = null,
)

/**
 * v2: POST /api/session/{id}/prompt body —— {text(必填), agents?, files?, skills?, metadata?, delivery?, resume?}
 * 并发/投递语义与 v1 的 prompt_async 对齐。
 */
@Serializable
data class V2PromptBody(
    val text: String,
    val agents: List<V2AgentAttachment>? = null,
    val files: List<V2FileAttachment>? = null,
    val delivery: String? = null,
    val resume: Boolean? = null,
)

/** v2 PromptInput.FileAttachment —— {uri(必填，file:// + 正斜杠)，name?, description?}（裸路径 400）。 */
@Serializable
data class V2FileAttachment(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
)

/** v2 Prompt.AgentAttachment —— {name(必填)}；字符串数组会被 400（Expected Prompt.AgentAttachment）。 */
@Serializable
data class V2AgentAttachment(
    val name: String,
)

/** v2 shell 直调结果（POST 本体 204，输出靠轮询 shell 消息）。 */
@Serializable
data class ShellResult(
    val output: String = "",
    val exit: Int? = null,
    val truncated: Boolean = false,
)

/** v2: POST /api/session/{id}/agent body —— {agent: "id"|"name"} */
@Serializable
data class V2AgentSwitchBody(
    val agent: String,
)

/** v2: POST /api/session/{id}/model body —— {model: Model.Ref} */
@Serializable
data class V2ModelSwitchBody(
    val model: V2ModelRef,
)

/** v2: POST …/permission/{requestID}/reply body —— {decision: once|always|reject, message?}（字段名必须是 decision，reply 会 400）。 */
@Serializable
data class V2PermissionReplyBody(
    val decision: String,
    val message: String? = null,
)

@Serializable
data class SendMessagePart(
    val type: String = "text",
    val text: String,
)

/**
 * 发送消息的响应 —— 通常返回完整的 assistant MessageInfo（包含 info + parts）
 * 但某些情况下服务器可能返回空或不完整响应，所以 info 可选
 */
@Serializable
data class SendMessageResponse(
    val info: MessageInfoData? = null,
    val parts: List<MessagePart> = emptyList(),
)

/**
 * Todo 项目
 * 实际 API: GET /session/{id}/todo 返回 List<TodoItem>
 * Server returns: { content, status, priority }
 * No "id" field — use index as fallback
 */
@Serializable
data class TodoItem(
    val content: String = "",
    val status: String = "",
    val priority: String? = null,
)

// ─── Truncation helpers (safety net — main truncation happens in OpenCodeApiClient at raw JSON level) ─────────

private const val MAX_TEXT_LENGTH = 5_000
private const val TRUNCATION_NOTICE = "\n\n… [truncated]"

private fun String?.truncateIfNeeded(): String? {
    if (this == null || length <= MAX_TEXT_LENGTH) return this
    return substring(0, MAX_TEXT_LENGTH) + TRUNCATION_NOTICE
}

fun MessageInfo.truncateLargeText() = copy(
    parts = parts.map { part ->
        when {
            part.state?.output != null -> part.copy(
                state = part.state.copy(output = part.state.output.truncateIfNeeded()),
                text = part.text.truncateIfNeeded(),
            )
            else -> part.copy(text = part.text.truncateIfNeeded())
        }
    }
)
