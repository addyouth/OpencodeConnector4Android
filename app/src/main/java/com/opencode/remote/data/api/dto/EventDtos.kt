package com.opencode.remote.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenCode v1.14.x SSE 事件格式 (captured from live server):
 *
 * Top-level JSON:
 *   {"directory":"D:\\path\\to\\project","project":"global","payload":{...}}
 *
 * Event types observed (verified from captured SSE data):
 *   server.connected     — initial handshake
 *   session.created      — new session created (properties.sessionID)
 *   session.updated      — session metadata changed (properties.info)
 *   session.deleted      — session deleted (properties.sessionID)
 *   session.status       — session status transition (properties.status.type = "busy"/"idle")
 *   session.idle         — session became idle
 *   session.diff         — code diff generated
 *   message.updated      — message created/updated (properties.info.role = "user"/"assistant")
 *   message.completed    — message fully generated (reload list, clear streaming)
 *   message.part.updated — part state change (properties.part.text = full accumulated text)
 *   message.part.delta   — streaming text delta (properties.delta = incremental chunk)
 *   message.part.completed — single part finished
 *   sync                 — internal synchronization (syncEvent at payload level, ignore)
 *   session.error        — server error (properties.error)
 *   permission.replied   — permission response received from TUI (properties.id)
 *   question.replied     — question answered from TUI (properties.id)
 *   question.rejected    — question rejected from TUI (properties.id)
 *   project.updated      — project metadata changed (properties.name, properties.path)
 *   vcs.branch.updated   — git branch changed (properties.branch, properties.previousBranch)
 *
 * Key streaming fields:
 *   message.part.delta  → properties.delta = incremental text chunk (APPEND)
 *   message.part.updated → properties.part.text = full text so far (can use for state recovery)
 *   message.updated     → properties.info.role = "assistant" signals thinking started
 */

@Serializable
data class ServerEvent(
    val directory: String? = null,
    val project: String? = null,
    val payload: EventPayload,
)

@Serializable
data class EventPayload(
    val type: String = "",
    val properties: EventProperties = EventProperties(),
)

@Serializable
data class EventProperties(
    val sessionID: String? = null,
    val messageID: String? = null,
    val partID: String? = null,
    @SerialName("callID")
    val callID: String? = null,
    val text: String? = null,
    val delta: String? = null,
    val field: String? = null,
    val error: String? = null,
    val part: MessagePart? = null,
    val info: MessageInfoData? = null,
    // Permission/question event fields (nullable for backward compatibility)
    val id: String? = null,                              // request ID
    val permission: String? = null,                      // "edit", "bash", "read", etc
    val patterns: List<String>? = null,                  // file path patterns
    val always: List<String>? = null,                    // always-allow patterns
    val tool: ToolRef? = null,                           // tool reference {messageID, callID}
    val questions: List<QuestionInfoDto>? = null,        // question definitions
    val status: StatusData? = null,                      // session.status event data
    // Permission/question reply fields
    val reply: String? = null,                            // reply value ("once", "always", "reject")
    // Project metadata fields
    val name: String? = null,                             // project name (project.updated)
    val path: String? = null,                             // project path (project.updated)
    // VCS branch fields
    val branch: String? = null,                           // current branch name (vcs.branch.updated)
    val previousBranch: String? = null,                   // previous branch name (vcs.branch.updated)
)

/** Session status data carried by session.status SSE events. */
@Serializable
data class StatusData(
    val type: String? = null,  // "busy" or "idle"
)

/** Tool reference for blocking permission/question events. */
@Serializable
data class ToolRef(
    val messageID: String? = null,
    @SerialName("callID")
    val callID: String? = null,
)

/** Option within a question.asked event. */
@Serializable
data class QuestionOptionDto(
    val label: String = "",
    val description: String? = null,
    /** form 选项的 value（应答时按 value 回传，无则回 label）。 */
    val value: String? = null,
)

/** Single question in a question.asked event. */
@Serializable
data class QuestionInfoDto(
    val question: String = "",
    val header: String? = null,
    val options: List<QuestionOptionDto> = emptyList(),
    val multiple: Boolean = false,
    val custom: Boolean = false,
    /** form 字段 key（应答 answer map 用；消息扫描路径为空）。 */
    val key: String = "",
)

/** Reply payload for POST /permission/:id/reply */
@Serializable
data class PermissionReplyPayload(
    val reply: String,  // "once", "always", "reject"
    val message: String? = null,
)

/** Reply payload for POST /question/:id/reply */
@Serializable
data class QuestionReplyPayload(
    val answers: List<List<String>>,
)
