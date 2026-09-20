package com.opencode.remote.data.api.dto.v2

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * v2 目录 DTO：模型 / provider / agent / 项目（实测于 opencode 2.0.10）。
 *
 * 与 v1 的关键差异：
 * - `GET /provider` 不再含模型表；模型走 `GET /api/model → {location, data}`
 * - `Model.Info` 必带 `enabled: boolean` + `status`（需求③：只显示勾选/启用的模型）
 * - `GET /api/agent → {location, data}`；agent 名首字母大写，传参大小写敏感
 * - `GET /api/project` 返回裸数组（无 worktree 字段）；`?project=` 过滤取 id
 */

/** GET /api/model → {location, data: Model.Info[]}（实测 295 个） */
@Serializable
data class V2ModelPage(
    val location: JsonElement? = null,
    val data: List<V2ModelInfo> = emptyList(),
)

@Serializable
data class V2ModelInfo(
    val id: String = "",
    @SerialName("modelID")
    val modelID: String? = null,
    @SerialName("providerID")
    val providerID: String? = null,
    val name: String? = null,
    val family: String? = null,
    val status: String? = null,
    val enabled: Boolean = false,
    val variants: List<V2ModelVariant> = emptyList(),
    val limit: JsonElement? = null,
    val capabilities: JsonElement? = null,
    val cost: JsonElement? = null,
)

@Serializable
data class V2ModelVariant(
    val id: String = "",
    val settings: JsonElement? = null,
)

/** GET /api/model/default → {location, data} */
@Serializable
data class V2DefaultModel(
    val location: JsonElement? = null,
    val data: V2ModelInfo? = null,
)

/** GET /api/provider → {location, data} */
@Serializable
data class V2ProviderPage(
    val location: JsonElement? = null,
    val data: List<V2ProviderInfo> = emptyList(),
)

@Serializable
data class V2ProviderInfo(
    val id: String = "",
    val name: String? = null,
)

/** GET /api/agent → {location, data} */
@Serializable
data class V2AgentPage(
    val location: JsonElement? = null,
    val data: List<V2AgentInfo> = emptyList(),
)

@Serializable
data class V2AgentInfo(
    val id: String? = null,
    val name: String = "",
    val mode: String? = null,
    val description: String? = null,
    val hidden: Boolean? = false,
)

/** GET /api/project → Project[] */
@Serializable
data class V2Project(
    val id: String = "",
    val canonical: String? = null,
)
