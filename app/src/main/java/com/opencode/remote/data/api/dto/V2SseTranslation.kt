package com.opencode.remote.data.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * v2 SSE 事件（实测 4096 /api/event）：
 *   data: {"id":"evt_...","created":<ms>,"metadata":{...}?,"type":"...",
 *          "location":{"directory":"..."}?,"data":{...}?,"durable":{...}?}
 *
 * 流式输出事件序列（一次 AI 回复）：
 *   session.step.started → session.reasoning.{started,delta,ended}
 *   → session.text.{started,delta,ended} → session.step.streamed → session.step.ended
 *   → session.usage.updated → session.execution.succeeded
 * 失败：session.step.failed + session.execution.failed（data.error {type,message,status}）
 */
@Serializable
data class V2EventRaw(
    val id: String? = null,
    val created: Long? = null,
    val type: String = "",
    val location: V2EventLocation? = null,
    val data: JsonElement? = null,
    val durable: JsonElement? = null,
)

@Serializable
data class V2EventLocation(
    val directory: String? = null,
)

/**
 * 把 v2 事件翻译成 v1 形状的 [ServerEvent]（ChatViewModel 消费），
 * 翻译不了/无需 UI 处理的事件返回 null（调用方跳过）。
 */
object V2SseTranslation {

    private val TAG = "V2SseTranslation"

    fun toServerEvent(raw: V2EventRaw): ServerEvent? {
        val directory = raw.location?.directory
        val data = raw.data?.jsonObject ?: return null
        val sessionID = data.string("sessionID")
        val assistantID = data.string("assistantMessageID") ?: data.string("messageID")

        // 组装 ServerEvent 的小工具
        fun ev(type: String, props: EventProperties) =
            ServerEvent(directory = directory, payload = EventPayload(type = type, properties = props))

        return when (raw.type) {
            // ── 进入回复：assistant 消息锚点（roles 展示 agent） ──
            "session.step.started" -> {
                val agent = data.string("agent")
                val model = data["model"]?.jsonObject?.let { m ->
                    MessageModel(
                        providerID = m.string("providerID"),
                        modelID = m.string("id") ?: m.string("modelID"),
                        variant = m.string("variant"),
                    )
                }
                ev(
                    "message.updated",
                    EventProperties(
                        sessionID = sessionID,
                        messageID = assistantID,
                        info = MessageInfoData(
                            id = assistantID ?: "",
                            role = "assistant",
                            sessionID = sessionID,
                            agent = agent,
                            mode = agent,
                            model = model,
                        ),
                    ),
                )
            }

            // ── 推理流（增量） ──
            "session.reasoning.delta" -> ev(
                "message.part.delta",
                EventProperties(
                    sessionID = sessionID,
                    messageID = assistantID,
                    partID = "reasoning",
                    field = "reasoning",
                    delta = data.string("delta"),
                ),
            )

            // ── 文本流（增量） —— v2 核心输出事件 ──
            "session.text.delta" -> ev(
                "message.part.delta",
                EventProperties(
                    sessionID = sessionID,
                    messageID = assistantID,
                    partID = "text",
                    field = "text",
                    delta = data.string("delta"),
                ),
            )

            // ── 推理块结束（全量 text，用于状态恢复/兜底） ──
            "session.reasoning.ended" -> ev(
                "message.part.updated",
                EventProperties(
                    sessionID = sessionID,
                    messageID = assistantID,
                    partID = "reasoning",
                    part = MessagePart(
                        type = "reasoning",
                        text = data.string("text"),
                        messageID = assistantID,
                    ),
                ),
            )

            // ── 文本块结束（全量 text，用于状态恢复/兜底） ──
            "session.text.ended" -> ev(
                "message.part.updated",
                EventProperties(
                    sessionID = sessionID,
                    messageID = assistantID,
                    partID = "text",
                    part = MessagePart(
                        type = "text",
                        text = data.string("text"),
                        messageID = assistantID,
                    ),
                ),
            )

            // ── 单步完成：带 finish/cost/tokens → message.completed（UI 收尾） ──
            "session.step.ended" -> ev(
                "message.completed",
                EventProperties(
                    sessionID = sessionID,
                    messageID = assistantID,
                    info = MessageInfoData(
                        id = assistantID ?: "",
                        role = "assistant",
                        sessionID = sessionID,
                        finish = data.string("finish"),
                        cost = data.doubleOrNull("cost"),
                        tokens = data.tokensOrNull(),
                    ),
                ),
            )

            // ── 整个执行成功 → session.idle（主完成信号，清 streaming） ──
            "session.execution.succeeded" -> ev(
                "session.idle",
                EventProperties(
                    sessionID = sessionID,
                    status = StatusData(type = "idle"),
                ),
            )

            // ── 整个执行失败 → session.error + session.idle（让 UI 停止等待） ──
            "session.execution.failed" -> {
                val err = data["error"]?.jsonObject?.string("message")
                    ?: "Execution failed (${data.string("error") ?: data.string("type") ?: "unknown"})"
                ev(
                    "session.error",
                    EventProperties(sessionID = sessionID, messageID = assistantID, error = err),
                )
            }

            // ── Token 用量更新 → 透传 message.updated（带 tokens） ──
            "session.usage.updated" -> ev(
                "message.updated",
                EventProperties(
                    sessionID = sessionID,
                    messageID = assistantID,
                    info = MessageInfoData(
                        id = assistantID ?: "",
                        role = "assistant",
                        sessionID = sessionID,
                        cost = data.doubleOrNull("cost"),
                        tokens = data.tokensOrNull(),
                    ),
                ),
            )

            // ── question.asked：v1 事件名沿用（schema 源头实锤），无则翻译层直通不了 ——
            "question.asked" -> {
                val qs = data["questions"]?.jsonArray?.mapNotNull { q ->
                    try {
                        val qo = q.jsonObject
                        QuestionInfoDto(
                            question = qo.string("question") ?: return@mapNotNull null,
                            header = qo.string("header"),
                            options = qo["options"]?.jsonArray?.mapNotNull { opt ->
                                val oo = opt.jsonObject
                                val label = oo.string("label") ?: return@mapNotNull null
                                QuestionOptionDto(
                                    label = label,
                                    description = oo.string("description"),
                                )
                            } ?: emptyList(),
                            multiple = qo["multiple"]?.jsonPrimitive?.booleanOrNull ?: false,
                            custom = qo["custom"]?.jsonPrimitive?.booleanOrNull ?: true,
                        )
                    } catch (_: Exception) { null }
                } ?: emptyList()
                if (qs.isEmpty()) return null
                val toolObj = data["tool"]?.jsonObject
                ev(
                    "question.asked",
                    EventProperties(
                        sessionID = sessionID,
                        id = data.string("id"),
                        questions = qs,
                        tool = ToolRef(
                            messageID = toolObj?.string("messageID"),
                            callID = toolObj?.string("callID"),
                        ),
                    ),
                )
            }

            // ── 需要 UI 处理但 v2 无法完整对齐的（降级） ──
            "session.step.failed" -> {
                val err = data["error"]?.jsonObject?.string("message")
                    ?: "Step failed (${data.string("type") ?: "unknown"})"
                ev(
                    "session.error",
                    EventProperties(sessionID = sessionID, messageID = assistantID, error = err),
                )
            }

            // ── 其余事件类型：UI 不消费，跳过 ──
            else -> null
        }
    }

    // ─── JsonObject 小工具 ───────────────────────────────────────────────

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.doubleOrNull(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.tokensOrNull(): MessageTokens? {
        val t = this["tokens"] ?: return null
        val obj = t.jsonObject
        return MessageTokens(
            total = null,
            input = obj.intOrNull("input"),
            output = obj.intOrNull("output"),
            reasoning = obj.intOrNull("reasoning"),
            cache = obj["cache"]?.jsonObject?.let { c ->
                CacheTokens(read = c.intOrNull("read"), write = c.intOrNull("write"))
            },
            cacheRead = obj.intOrNull("cacheRead"),
            cacheWrite = obj.intOrNull("cacheWrite"),
        )
    }
}