package com.opencode.remote.data.api.dto

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * v2 消息判别联合 → v1 MessageInfo(info + parts) 的手动翻译。
 *
 * v2 Session.Message.Info（anyOf 11 类，靠顶层 `type` 区分）：
 *   - type: "user" | "assistant" | "system" | "synthetic" | "skill" | "shell" |
 *           "AgentSelected" | "ModelSelected" | "LocationSwitched" | "Compaction" | "Idle"
 *   - assistant 结构：{id, time{created,streamed,completed}, type, agent, model{id,providerID,variant},
 *      content:[{type:"reasoning"|"text"|"tool", text?, state?, time?}, ...], finish, cost, tokens}
 *
 * 选择手动解析（而非 kotlinx 多态）的原因：content 数组元素类型多变 + 字段
 * 在不同消息类型间差异大，自动序列化配置脆弱且未知类型一旦出现即崩。
 * 解析失败时抛出异常由上层兜底（整体跳过该条消息）。
 */
object V2MessageParser {

    fun fromJsonElement(element: JsonElement): MessageInfo? =
        fromJsonObject(element.jsonObject)

    fun fromJsonObject(obj: JsonObject): MessageInfo? {
        val id = obj.string("id") ?: ""
        val type = obj.string("type") ?: ""
        val role = mapRole(type)

        // info 层
        val model = obj["model"]?.jsonObject?.let { m ->
            MessageModel(
                providerID = m.string("providerID"),
                modelID = m.string("id") ?: m.string("modelID"),
                variant = m.string("variant"),
            )
        }
        val time = obj["time"]?.jsonObject?.let { t ->
            MessageTime(
                created = t.longOrNull("created"),
                completed = t.longOrNull("completed") ?: t.longOrNull("streamed"),
            )
        }
        val tokens = obj["tokens"]?.jsonObject?.let { t ->
            MessageTokens(
                total = null,
                input = t.intOrNull("input"),
                output = t.intOrNull("output"),
                reasoning = t.intOrNull("reasoning"),
                cache = t.cacheOrNull(),
                cacheRead = t.intOrNull("cacheRead"),
                cacheWrite = t.intOrNull("cacheWrite"),
            )
        }
        val info = MessageInfoData(
            id = id,
            role = role,
            sessionID = obj.string("sessionID"),
            parentID = obj.string("parentID"),
            agent = obj.string("agent"),
            mode = obj.string("mode"),
            model = model,
            time = time,
            finish = obj.string("finish"),
            cost = obj.doubleOrNull("cost"),
            tokens = tokens,
            variant = model?.variant,
        )

        // parts 层：content[] → MessagePart[]
        val parts = mutableListOf<MessagePart>()
        obj["content"]?.jsonArray?.forEach { item ->
            val typeStr = item.jsonObject.string("type") ?: "text"
            when (typeStr) {
                "reasoning" -> parts += MessagePart(
                    type = "reasoning",
                    text = item.jsonObject.string("text"),
                    messageID = id,
                )
                "text" -> parts += MessagePart(
                    type = "text",
                    text = item.jsonObject.string("text"),
                    messageID = id,
                )
                "tool" -> parts += toolPart(item.jsonObject, id)
                else -> {
                    // 未知内容块：有 text 就降级为 text part，无则忽略
                    val txt = item.jsonObject.string("text")
                    if (!txt.isNullOrBlank()) {
                        parts += MessagePart(type = "text", text = txt, messageID = id)
                    }
                }
            }
        }

        // 无可渲染内容时：顶层 text 有就合成 text part（v2 user 消息文本只在顶层），
        // 否则丢弃（idle/model-switched 等合成消息，否则渲染成空白泡）。
        if (parts.isEmpty()) {
            val topText = obj.string("text")
            if (topText.isNullOrBlank()) return null
            parts += MessagePart(type = "text", text = topText, messageID = id)
        }

        return MessageInfo(info = info, parts = parts)
    }

    private fun toolPart(obj: JsonObject, messageId: String): MessagePart {
        val state = obj["state"]?.jsonObject
        val output = state?.string("content") ?: state?.string("output")
        return MessagePart(
            type = "tool",
            text = obj.string("text"),
            messageID = messageId,
            tool = obj.string("name"),
            callID = obj.string("id"),
            state = ToolState(
                status = state?.string("status"),
                input = state?.get("input"),
                output = output,
                metadata = state?.get("metadata"),
                title = state?.string("title"),
                time = state?.get("time"),
            ),
        )
    }

    /** v2 type → v1 role（UI 按 "user"/"assistant"/"system" 渲染） */
    private fun mapRole(type: String): String = when (type) {
        "user", "assistant", "system" -> type
        "AgentSelected", "ModelSelected", "LocationSwitched", "Compaction", "Idle" -> "system"
        "synthetic", "skill", "shell" -> "system"
        else -> "system"
    }

    // ─── JsonObject 小工具 ───────────────────────────────────────────────

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.longOrNull(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.doubleOrNull(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.cacheOrNull(): CacheTokens? {
        val c = this["cache"] ?: return null
        val obj = c.jsonObject
        return CacheTokens(
            read = obj.intOrNull("read"),
            write = obj.intOrNull("write"),
        )
    }
}