package com.opencode.remote.data.api.dto.v2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * v2 交互回复 DTO（permission 气泡 / form 气泡）。
 * 字段形状以 spec 为准；气泡 UI 映射在真事件抓包后补。
 */

/**
 * POST /api/session/{id}/permission/{requestID}/reply。
 * decision ∈ once | always | reject，对应 v1 气泡：单次允许 / 总是允许 / 拒绝。
 */
@Serializable
data class V2PermissionReply(
    val decision: String,
    val message: String? = null,
)

/** POST /api/session/{id}/form/{formID}/reply（answer 为字段 key→值表） */
@Serializable
data class V2FormReply(
    val answer: Map<String, JsonElement> = emptyMap(),
)
