package com.opencode.remote.data.api.dto.v2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * v2 事件帧（实测于 opencode 2.0.10）。
 *
 * Endpoint: GET /api/event（Content-Type: text/event-stream，需 Basic 鉴权）。
 * 帧格式：`data: {"id","type","data"}` + `: heartbeat` 注释行；
 * 首帧 `type=server.connected`。其余事件 type 在联调真流量时补映射。
 */
@Serializable
data class V2EventFrame(
    val id: String? = null,
    val type: String = "",
    val data: JsonElement? = null,
)
