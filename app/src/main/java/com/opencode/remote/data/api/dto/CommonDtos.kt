package com.opencode.remote.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 实际 API (v2): GET /api/project → 裸数组
 * v2 Project.Info: {"id","canonical","vcs"?,"name"?,"time"{created,updated},"sandboxes":[]}
 * canonical 即 v1 的 worktree（项目目录），用 SerialName 直接映射保持 UI 兼容。
 */
@Serializable
data class ProjectInfo(
    val id: String = "",
    @SerialName("canonical")
    val worktree: String? = null,
    val time: ProjectTime? = null,
    val sandboxes: List<ProjectSandbox> = emptyList(),
    // ─── v2 字段 ───
    val vcs: String? = null,
    val name: String? = null,
)

@Serializable
data class ProjectTime(
    val created: Long? = null,
)

@Serializable
data class ProjectSandbox(
    val id: String? = null,
    val name: String? = null,
    val directory: String? = null,
)

/**
 * 实际 API: GET /agent → 返回数组
 * 字段: name, mode, description, hidden, ...
 * mode: "primary" | "subagent" | "all"
 * hidden: true 时不在 UI 显示，自定义 agent 可能为 null
 */
@Serializable
data class AgentInfo(
    // ─── v2 Agent.Info: id/name/model/request/system/description/mode/hidden/color/steps/permissions ───
    /** v2 新增：agent ID（切代理用） */
    val id: String? = null,
    val name: String = "",
    val mode: String? = null,
    val description: String? = null,
    val hidden: Boolean? = false,
    val model: AgentModel? = null,
)

@Serializable
data class AgentModel(
    @SerialName("modelID")
    val modelID: String? = null,
    @SerialName("providerID")
    val providerID: String? = null,
    /** v2 Model.Ref 用 `id` */
    val id: String? = null,
) {
    val resolvedModelID: String? get() = modelID ?: id
}

/**
 * 实际 API: GET /file?path=... 返回数组
 * 字段: name, path, absolute, type ("file"|"directory"), ignored
 */
@Serializable
data class FileNode(
    val name: String = "",
    val path: String = "",
    val absolute: String = "",
    val type: String = "file",
    val ignored: Boolean = false,
)

/**
 * API response: GET /file/content?path=...
 * type: "text" | "binary"
 * content: file body (text or base64-encoded binary)
 */
@Serializable
data class FileContent(
    val type: String = "text",
    val content: String = "",
    val encoding: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class ProviderList(
    @SerialName("all")
    val providers: List<ProviderInfo> = emptyList(),
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList(),
)

@Serializable
data class ProviderInfo(
    val id: String = "",
    val name: String? = null,
    /** Server returns models as a JSON object (Map keyed by model ID), not an array. */
    val models: Map<String, ProviderModelInfo> = emptyMap(),
)

@Serializable
data class ModelInfo(
    val id: String? = null,
    val name: String? = null,
    val providerID: String? = null,
    val status: String? = null,
)

/** Server returns limit as { context: Int, input?: Int, output: Int }, not a simple integer. */
@Serializable
data class ModelLimitInfo(
    val context: Int = 0,
    val input: Int? = null,
    val output: Int = 0,
)

@Serializable
data class ProviderModelInfo(
    val id: String = "",
    val name: String? = null,
    val limit: ModelLimitInfo? = null,
    val variants: Map<String, JsonElement> = emptyMap(),
)

// ─── v2 原始响应 DTO（/api/model、/api/provider） ─────────────────────────

/** v2 Model.Variant —— variants 由 v1 Map 变为 v2 Array */
@Serializable
data class V2ModelVariant(
    val id: String = "",
    val name: String? = null,
)

/** v2: GET /api/model → {location, data: [Model.Info]} */
@Serializable
data class V2ModelInfo(
    val id: String = "",
    @SerialName("modelID")
    val modelID: String = "",
    @SerialName("providerID")
    val providerID: String = "",
    val canonical: String? = null,
    val family: String? = null,
    val name: String? = null,
    val status: String? = null,
    val enabled: Boolean? = null,
    val limit: ModelLimitInfo? = null,
    val variants: List<V2ModelVariant> = emptyList(),
) {
    /** 需求③匹配用：whitelist 比较键（providerID/modelID） */
    val whitelistKey: String get() = "$providerID/$modelID"
}

/** v2: GET /api/provider → {location, data: [Provider.Info]} */
@Serializable
data class V2ProviderInfo(
    val id: String = "",
    val canonical: String? = null,
    @SerialName("integrationID")
    val integrationID: String? = null,
    val name: String? = null,
    val activation: String? = null,  // auto | enabled | disabled
    val package: String? = null,
)
