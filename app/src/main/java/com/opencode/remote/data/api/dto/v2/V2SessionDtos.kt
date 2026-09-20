package com.opencode.remote.data.api.dto.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * v2 会话 DTO（实测于 opencode 2.0.10）。
 *
 * 与 v1 的关键差异：
 * - 列表/单查/创建全部包 `{data}`（列表另带 cursor 分页），不再是裸数组/裸对象
 * - Session.Info 原生带 `agent` + `model`（需求②：进会话自动带主代理的数据基础）
 * - prompt 只收 text；agent/model 改为会话级设置（见 V2SetAgent/ModelRequest）
 * - revert 两段式 stage→commit；question 改 form（DTO 见 UI 气泡阶段）
 * - Todo 无对应端点，v2 模式隐藏 Todo 面板
 */

/** GET /api/session?... → {data, cursor} */
@Serializable
data class V2SessionPage(
    val data: List<V2SessionInfo> = emptyList(),
    val cursor: JsonElement? = null,
)

/** GET /api/session/{id} → {data} */
@Serializable
data class V2SessionSingle(
    val data: V2SessionInfo = V2SessionInfo(),
)

/** POST /api/session → {data} */
@Serializable
data class V2CreateSessionResponse(
    val data: V2SessionInfo = V2SessionInfo(),
)

/** Session.Info（仅收客户端用到的字段，其余靠 ignoreUnknownKeys 丢弃） */
@Serializable
data class V2SessionInfo(
    val id: String = "",
    @SerialName("parentID")
    val parentID: String? = null,
    @SerialName("projectID")
    val projectID: String? = null,
    val agent: String? = null,
    val model: V2ModelRef? = null,
    val title: String? = null,
    val time: JsonElement? = null,
    val location: V2LocationRef? = null,
    val revert: JsonElement? = null,
)

/** Model.Ref：{id, providerID, variant}（variant 含 default/low/high/max 等） */
@Serializable
data class V2ModelRef(
    val id: String = "",
    @SerialName("providerID")
    val providerID: String? = null,
    val variant: String? = null,
)

/** Location.PublicRef（实测仅 directory；建会话按项目时扩展） */
@Serializable
data class V2LocationRef(
    val directory: String? = null,
)

/** POST /api/session */
@Serializable
data class V2CreateSessionRequest(
    val title: String? = null,
    val agent: String? = null,
    val model: V2ModelRef? = null,
    val location: V2LocationRef? = null,
)

/** PATCH /api/session/{id}（改标题等） */
@Serializable
data class V2PatchSessionRequest(
    val title: String? = null,
)

/** POST /api/session/{id}/prompt：v2 只收 text，必须字段 */
@Serializable
data class V2PromptRequest(
    val text: String,
    val resume: Boolean? = null,
)

/** POST /api/session/{id}/agent {agent*} */
@Serializable
data class V2SetAgentRequest(
    val agent: String,
)

/** POST /api/session/{id}/model {model*} */
@Serializable
data class V2SetModelRequest(
    val model: V2ModelRef,
)

/** POST /api/session/{id}/fork {before?}（before 为 msg_ 开头的消息 ID） */
@Serializable
data class V2ForkRequest(
    val before: String? = null,
)

/** POST /api/session/{id}/revert/stage {messageID*, files?} */
@Serializable
data class V2RevertStageRequest(
    @SerialName("messageID")
    val messageID: String,
    val files: Boolean? = null,
)

/**
 * GET /api/session/{id}/message → {data, cursor}。
 * v2 消息扁平化：{id,time,type,agent,model,content,...}，无 v1 的 info/parts 结构。
 * content：user 为 null；assistant 为 parts 数组；其余 type 联调时补。
 */
@Serializable
data class V2MessagePage(
    val data: List<V2Message> = emptyList(),
    val cursor: JsonElement? = null,
)

@Serializable
data class V2Message(
    val id: String = "",
    val time: JsonElement? = null,
    val type: String = "",
    val agent: String? = null,
    val model: V2ModelRef? = null,
    val content: JsonElement? = null,
    val finish: String? = null,
    val cost: Double? = null,
    val tokens: JsonElement? = null,
)

/** assistant content 数组元素（实测键：type/text/state/time/id/name/providerState） */
@Serializable
data class V2ContentPart(
    val type: String = "",
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val state: JsonElement? = null,
    val time: JsonElement? = null,
    @SerialName("providerState")
    val providerState: JsonElement? = null,
)

/** GET /api/session/active → {data:{...}}（key 存在即活跃，值形状忽略） */
@Serializable
data class V2ActiveResponse(
    val data: Map<String, JsonElement> = emptyMap(),
)
