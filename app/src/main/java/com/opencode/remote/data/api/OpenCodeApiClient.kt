package com.opencode.remote.data.api

import android.util.Log
import android.util.Base64
import com.opencode.remote.data.api.dto.*
import com.opencode.remote.ui.chat.PermissionRequestData
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import java.net.URLEncoder
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManager

/**
 * REST API client for OpenCode server v2 (0.0.0.0:4096, Basic auth).
 *
 * All routes verified against v2 OpenAPI spec + live 4096 captures:
 *   GET   /api/session?list           → {data: [Session.Info], cursor}   （跨项目全量，需求②）
 *   POST  /api/session                → {data: Session.Info}
 *   GET   /api/session/{id}           → {data: Session.Info}
 *   DELETE /api/session/{id}          → 204
 *   POST  /api/session/{id}/fork      → {data: Session.Info}
 *   POST  /api/session/{id}/interrupt → 中断（v1 abort）
 *   GET   /api/session/{id}/message   → {data: [Message], cursor}  → V2MessageParser 翻译
 *   POST  /api/session/{id}/prompt    → {text, agents?}（v2 PromptInput）
 *   POST  /api/session/{id}/agent     → {agent}
 *   POST  /api/session/{id}/model     → {model: Model.Ref}
 *   GET   /api/agent                  → {location, data: [Agent.Info]}
 *   GET   /api/project                → 裸数组 [Project.Info]（无 data 包装）
 *   GET   /api/model                  → {location, data: [Model.Info]}   （需求③ 过滤源）
 *   GET   /api/provider               → {location, data: [Provider.Info]}
 *   GET   /api/config                 → 裸数组 [Config.Entry]（opencode.json 路径源）
 *   GET   /api/fs/read/{path}         → 文件原始内容（读 whitelist 用，需求③）
 *   GET   /api/fs/list                → 目录列表（best-effort）
 *
 * v2 已删除：/todo、/children、/session/status、/question/... → 优雅降级空结果。
 * v2 revert 改三阶段（stage→commit），v1 单调用语义降级为 best-effort。
 */
class OConnectorApiClient @Inject constructor(
    private val json: Json,
) {

    private var authHeader: String? = null
    private var insecureTrust: Boolean = false
    private var baseUrl: String = ""
    /** 最近一次 testConnection 失败的真实原因（成功时为 null）。B 方案：失败不再吞异常。 */
    var lastTestError: String? = null
        private set

    @OptIn(ExperimentalSerializationApi::class)
    private var client: HttpClient = createClient()

    private fun createClient(insecureTrust: Boolean = false): HttpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
        defaultRequest {
            contentType(ContentType.Application.Json)
            authHeader?.let { header(HttpHeaders.Authorization, it) }
        }
        engine {
            if (insecureTrust) {
                val trustManager = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf<TrustManager>(trustManager), java.security.SecureRandom())
                config {
                    sslSocketFactory(sslContext.socketFactory, trustManager)
                    hostnameVerifier { _, _ -> true }
                }
            }
        }
    }

    companion object {
        private const val TAG = "OConnectorApiClient"
        /** Maximum number of messages to load from server. Prevents OOM on long sessions. */
        private const val MAX_MESSAGES = 50
    }

    // ─── 低层工具 ────────────────────────────────────────────────────────

    /** URL 编码文件路径（fs/read 路径段用；空格用 %20 而非 +） */
    private fun encPath(path: String): String =
        URLEncoder.encode(path, "UTF-8").replace("+", "%20")

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun getJson(url: String, block: HttpRequestBuilder.() -> Unit = {}): JsonElement =
        Json.parseToJsonElement(client.get(fullUrl(url), block).bodyAsText())

    /** 相对路径拼完整 URL（A 方案根治：configure 丢 baseUrl 的回归 bug）。 */
    private fun fullUrl(path: String): String = baseUrl.trimEnd('/') + path

    @OptIn(ExperimentalSerializationApi::class)
    private fun JsonElement.dataArray(): JsonArray? = when (this) {
        is JsonObject -> this["data"] as? JsonArray
        else -> null
    }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> JsonElement.decodeDataList(): List<T> {
        val arr = dataArray() ?: return emptyList()
        return arr.mapNotNull { item ->
            try { json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), item) }
            catch (e: Exception) { Log.w(TAG, "Failed to decode list item: $item", e); null }
        }
    }

    /**
     * Configure (or reconfigure) the client with connection parameters.
     * Called by the repository when a new connection is established.
     */
    fun configure(baseUrl: String, username: String = "", password: String = "", insecureTrust: Boolean = false) {
        close()
        this.baseUrl = baseUrl.trim().trimEnd('/')
        this.insecureTrust = insecureTrust
        authHeader = if (password.isNotEmpty()) {
            "Basic " + Base64.encodeToString(
                "${username.ifEmpty { "opencode" }}:$password".toByteArray(),
                Base64.NO_WRAP
            )
        } else null
        client = createClient(insecureTrust)
    }

    // ─── Sessions ──────────────────────────────────────────────────────

    /** GET /api/session → {data, cursor}；v2 返回全部项目会话（需求②在全量层天然满足） */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun listSessions(directory: String? = null, scope: String? = null): List<SessionInfo> {
        val el = getJson("/api/session") {
            parameter("list", "")
            directory?.let { parameter("directory", it) }
            scope?.let { parameter("scope", it) }
        }
        val sessions = el.decodeDataList<SessionInfo>()
        Log.d(TAG, "Loaded ${sessions.size} sessions (v2, cross-project) dir=$directory")
        return sessions
    }

    /** v2: 单次 GET /api/session 即返回全部项目会话 → 直接复用 listSessions */
    suspend fun listAllSessions(): List<SessionInfo> = listSessions(null, null)

    /** POST /api/session → {data: Session.Info}。显式传 agent（需求②默认主代理） */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun createSession(directory: String? = null, agent: String? = null): CreateSessionResponse {
        val body = V2CreateSessionBody(
            title = null,
            agent = agent,
            location = directory?.let { V2LocationRef.of(it) },
        )
        val el = getJson("/api/session") { setBody(body) }
        return try {
            CreateSessionResponse.fromSession(
                json.decodeFromJsonElement(SessionInfo.serializer(), el.jsonObject["data"] ?: el)
            )
        } catch (e: Exception) {
            Log.w(TAG, "createSession: odd response $el", e)
            CreateSessionResponse()
        }
    }

    /** GET /api/session/{id} → {data: Session.Info} */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getSession(id: String, directory: String? = null): SessionInfo {
        val el = getJson("/api/session/$id") {}
        return json.decodeFromJsonElement(
            SessionInfo.serializer(),
            el.jsonObject["data"] ?: el
        )
    }

    /** DELETE /api/session/{id} */
    suspend fun deleteSession(id: String, directory: String? = null) {
        client.delete(fullUrl("/api/session/$id")) {}
    }

    /** POST /api/session/{id}/fork → {data: Session.Info} */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun forkSession(id: String, directory: String? = null): CreateSessionResponse {
        val el = getJson("/api/session/$id/fork") { setBody("{}") }
        return try {
            CreateSessionResponse.fromSession(
                json.decodeFromJsonElement(SessionInfo.serializer(), el.jsonObject["data"] ?: el)
            )
        } catch (e: Exception) {
            Log.w(TAG, "forkSession: odd response $el", e)
            CreateSessionResponse()
        }
    }

    /** POST /api/session/{id}/interrupt（v1 abort） */
    suspend fun abortSession(id: String, directory: String? = null) {
        try { client.post(fullUrl("/api/session/$id/interrupt")) {} } catch (e: Exception) {
            Log.w(TAG, "abort failed (may already be idle)", e)
        }
    }

    /**
     * v2 revert 为三阶段（stage → commit），v1 单调用语义不再存在。
     * 实现 best-effort：stage + commit；失败时降级返回当前会话（不抛错打断 UI）。
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun revertSession(id: String, messageID: String, directory: String? = null): SessionInfo {
        try {
            getJson("/api/session/$id/revert/stage") { setBody(RevertRequest(messageID = messageID)) }
            try { client.post(fullUrl("/api/session/$id/revert/commit")) { setBody("{}") } } catch (e: Exception) {
                Log.w(TAG, "revert commit failed", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "revert stage failed, treating as no-op", e)
        }
        return getSession(id)
    }

    /** v2 无 unrevert 端点 —— 降级为返回当前会话 */
    suspend fun unrevertSession(id: String, directory: String? = null): SessionInfo {
        Log.w(TAG, "unrevert not supported by v2; returning current session")
        return getSession(id)
    }

    // ─── Messages ──────────────────────────────────────────────────────

    /**
     * GET /api/session/{id}/message → {data, cursor}
     * v2 消息为判别联合 → V2MessageParser 手动翻译成 v1 MessageInfo(info+parts)。
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getMessages(id: String, directory: String? = null, limit: Int? = null): List<MessageInfo> {
        val el = getJson("/api/session/$id/message") {
            parameter("limit", limit ?: MAX_MESSAGES)
        }
        val arr = el.dataArray() ?: return emptyList()
        val messages = arr.mapNotNull { item ->
            try { V2MessageParser.fromJsonElement(item) }
            catch (e: Exception) { Log.w(TAG, "Failed to parse v2 message: $item", e); null }
        }
        return if (messages.size > MAX_MESSAGES) {
            Log.w(TAG, "Server returned ${messages.size} messages despite limit=$MAX_MESSAGES, truncating")
            messages.takeLast(MAX_MESSAGES)
        } else {
            messages
        }
    }

    /**
     * POST /api/session/{id}/prompt（v2 PromptInput = {text, agents?, delivery?, resume?}）。
     * v2 模型是会话级：若调用方带 providerID/modelID（v1 每消息语义），先切会话模型再发。
     */
    suspend fun sendMessage(sessionId: String, text: String, agent: String? = null, providerID: String? = null, modelID: String? = null, variant: String? = null, directory: String? = null) {
        if (modelID != null) {
            try { switchModel(sessionId, providerID, modelID, variant) }
            catch (e: Exception) { Log.w(TAG, "switchModel before prompt failed: ${e.message}") }
        }
        client.post(fullUrl("/api/session/$sessionId/prompt")) {
            setBody(V2PromptBody(text = text, agents = agent?.let { listOf(it) }))
        }
    }

    /** POST /api/session/{id}/agent → {agent}（需求②切代理） */
    suspend fun switchAgent(sessionId: String, agent: String) {
        client.post(fullUrl("/api/session/$sessionId/agent")) {
            setBody(V2AgentSwitchBody(agent = agent))
        }
        Log.d(TAG, "Switched session $sessionId agent → $agent")
    }

    /** POST /api/session/{id}/model → {model: Model.Ref}（需求③选模型后切换） */
    suspend fun switchModel(sessionId: String, providerID: String?, modelID: String?, variant: String? = null) {
        client.post(fullUrl("/api/session/$sessionId/model")) {
            setBody(V2ModelSwitchBody(model = V2ModelRef(id = modelID, providerID = providerID, variant = variant)))
        }
    }

    // ─── Permission Replies ─────────────────────────────────────────────

    /**
     * v2: POST /api/session/{sessionID}/permission/{requestID}/reply
     * 需要 sessionID（v1 不需要）；UI 调用时传入，null 则 no-op 避免 404。
     */
    suspend fun replyPermission(requestId: String, reply: String, message: String? = null, directory: String? = null, sessionId: String? = null) {
        if (sessionId == null) {
            Log.w(TAG, "replyPermission: sessionId missing, skipping (request=$requestId)")
            return
        }
        client.post(fullUrl("/api/session/$sessionId/permission/$requestId/reply")) {
            setBody(V2PermissionReplyBody(reply = reply, message = message))
        }
        Log.d(TAG, "Permission reply: $reply for request=$requestId session=$sessionId")
    }

    /**
     * v2 #8: GET /api/session/{sessionID}/permission → {data: [Permission.Request]}.
     * v2 无 permission.asked SSE（spec 无此事件），桌面端同样靠轮询此路由；
     * 手机轮询：有挂起即弹已有确认气泡。字段映射 action→permission、resources→patterns、save→always。
     */
    suspend fun listPendingPermissions(sessionId: String): List<PermissionRequestData> {
        return try {
            val el = getJson("/api/session/$sessionId/permission") {}
            val arr = (el as? JsonObject)?.get("data") as? JsonArray ?: return emptyList()
            arr.mapNotNull { item ->
                try {
                    val o = item.jsonObject
                    PermissionRequestData(
                        id = o.string("id") ?: return@mapNotNull null,
                        sessionID = o.string("sessionID") ?: sessionId,
                        permission = o.string("action") ?: "unknown",
                        patterns = o["resources"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                        always = o["save"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                        tool = null,
                    )
                } catch (e: Exception) { Log.w(TAG, "Bad permission item $item", e); null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listPendingPermissions failed: ${e.message}")
            emptyList()
        }
    }

    /** v2 已无 /question/{id}/reply —— no-op 降级 */
    suspend fun replyQuestion(requestId: String, answers: List<List<String>>, directory: String? = null) {
        Log.w(TAG, "replyQuestion not supported by v2 (request=$requestId)")
    }

    /** v2 已无 /question/{id}/reject —— no-op 降级 */
    suspend fun rejectQuestion(requestId: String, directory: String? = null) {
        Log.w(TAG, "rejectQuestion not supported by v2 (request=$requestId)")
    }

    // ─── Todo / Status / Children（v2 已删除，优雅降级） ─────────────────

    /** v2 无 todo 端点 → 空列表 */
    suspend fun getTodoList(id: String, directory: String? = null): List<TodoItem> {
        Log.d(TAG, "getTodoList: v2 has no todo endpoint, returning empty")
        return emptyList()
    }

    /** v2 无 /session/status → 空 map */
    suspend fun getSessionStatus(): Map<String, String> {
        Log.d(TAG, "getSessionStatus: v2 has no status endpoint, returning empty")
        return emptyMap()
    }

    /** v2 无 children 端点 → 空列表 */
    suspend fun getSessionChildren(sessionId: String): List<SessionInfo> {
        Log.d(TAG, "getSessionChildren: v2 has no children endpoint, returning empty")
        return emptyList()
    }

    // ─── Project ───────────────────────────────────────────────────────

    /** GET /api/project → 裸数组。v2 无「当前项目」概念，取第一条（服务端最近使用排序）。 */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun getCurrentProject(): ProjectInfo {
        val arr = getJson("/api/project") {}.jsonArray
        return if (arr.isNotEmpty()) {
            try { json.decodeFromJsonElement(ProjectInfo.serializer(), arr.first()) }
            catch (e: Exception) { ProjectInfo() }
        } else {
            ProjectInfo()
        }
    }

    /** GET /api/project → List<ProjectInfo> */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun listProjects(): List<ProjectInfo> {
        val arr = getJson("/api/project") {}.jsonArray
        return arr.mapNotNull { item ->
            try { json.decodeFromJsonElement(ProjectInfo.serializer(), item) }
            catch (e: Exception) { Log.w(TAG, "Failed to decode project $item", e); null }
        }
    }

    /** 连通性：GET /api/project。失败时真实原因存 lastTestError，UI 可显示（B 方案）。 */
    suspend fun testConnection(): Boolean = try {
        getJson("/api/project") {}
        lastTestError = null
        true
    } catch (e: Exception) {
        lastTestError = "${e.javaClass.simpleName}: ${e.message}"
        Log.w(TAG, "Test connection failed: $lastTestError")
        false
    }

    // ─── Agents ─────────────────────────────────────────────────────────

    /** GET /api/agent → {location, data: [Agent.Info]}；directory 透给 location[directory] 取项目级 agent（如 02.写作的 writ-assist），null 则沿用 serve 默认目录。 */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun listAgents(directory: String? = null): List<AgentInfo> {
        val el = getJson("/api/agent") {
            if (!directory.isNullOrEmpty()) parameter("location[directory]", directory)
        }
        return el.decodeDataList<AgentInfo>()
    }

    // ─── Files（v2 /api/fs/*，best-effort） ─────────────────────────────

    /** GET /api/fs/list?path=... — v2 响应结构未完全对齐，防御式解析 */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun listFiles(path: String, directory: String? = null): List<FileNode> {
        return try {
            val el = getJson("/api/fs/list") { parameter("path", path) }
            val arr = when {
                el is JsonObject && el["data"] is JsonArray -> el["data"] as JsonArray
                el is JsonArray -> el
                else -> emptyList()
            }
            arr.mapNotNull { item ->
                try { json.decodeFromJsonElement(FileNode.serializer(), item) }
                catch (e: Exception) { Log.w(TAG, "Failed to decode file node", e); null }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listFiles failed for $path: ${e.message}")
            emptyList()
        }
    }

    /** GET /api/fs/read/{path} → 文件原始内容 */
    suspend fun readFileContent(path: String, directory: String? = null): FileContent {
        val body = client.get(fullUrl("/api/fs/read/${encPath(path)}")) {}.bodyAsText()
        return FileContent(type = "text", content = body)
    }

    // ─── Config / Providers / Models（需求③核心） ────────────────────────

    /** GET /api/provider → {location, data: [Provider.Info]} */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun listProvidersV2(): List<V2ProviderInfo> {
        val el = getJson("/api/provider") {}
        return el.decodeDataList<V2ProviderInfo>()
    }

    /** GET /api/model → {location, data: [Model.Info]} */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun listModelsV2(): List<V2ModelInfo> {
        val el = getJson("/api/model") {}
        return el.decodeDataList<V2ModelInfo>()
    }

    /**
     * 需求③：读服务端 opencode.json 原始内容，解析 v1 格式 `provider.<id>.whitelist`，
     * 得到「勾选模型 ID 集合」。
     *
     * 路径获取链：/api/config（返回配置文档数组，含 path）→ 找 opencode.json 文档
     * → /api/fs/read/{path} 读原始文件（/api/config 是解析后结果，whitelist 已被 v2 丢弃，
     * 必须读原始文件）。
     *
     * 返回集合元素格式：`providerID/modelID` 与裸 `modelID` 两者都放（兼容不同 provider 写法）。
     */
    suspend fun readModelWhitelist(): Set<String> {
        return try {
            val config = getJson("/api/config") {}.jsonArray
            val docPaths = config.mapNotNull { item ->
                val obj = item.jsonObject
                if (obj.string("type") == "document") {
                    val p = obj.string("path")
                    val base = p?.substringAfterLast('\\')?.substringAfterLast('/')
                    // 任意 .json/.jsonc（含 opencode.json 与 mirror）
                    if (base?.endsWith(".json") == true || base?.endsWith(".jsonc") == true) p else null
                } else null
            }
            // mirror 候选：每个文档同目录下的 opencode-whitelist.mirror.json（Vault 内必 200；
            // 全局 opencode.json 在 cwd 外会 500，逐个跳过）
            val mirrorPaths = docPaths.mapNotNull { p ->
                val sep = maxOf(p.lastIndexOf('\\'), p.lastIndexOf('/'))
                if (sep < 0) null else p.substring(0, sep + 1) + "opencode-whitelist.mirror.json"
            }.distinct()

            val out = mutableSetOf<String>()
            for (path in (docPaths + mirrorPaths).distinct()) {
                try {
                    val raw = client.get(fullUrl("/api/fs/read/${encPath(path)}")) {}.bodyAsText()
                    val root = json.parseToJsonElement(raw).jsonObject
                    val providers = root["provider"]?.jsonObject ?: continue
                    providers.forEach { (providerId, cfg) ->
                        val whitelist = cfg.jsonObject["whitelist"]?.jsonArray ?: return@forEach
                        whitelist.forEach { entry ->
                            val modelId = entry.jsonPrimitive.contentOrNull ?: return@forEach
                            out.add("$providerId/$modelId")
                            out.add(modelId)
                        }
                    }
                    Log.d(TAG, "Whitelist merged ${out.size} entries so far from $path")
                } catch (e: Exception) {
                    Log.w(TAG, "Whitelist skip $path: ${e.message}")
                }
            }
            Log.d(TAG, "Whitelist loaded: ${out.size} entries total")
            out
        } catch (e: Exception) {
            Log.w(TAG, "readModelWhitelist failed (fallback: no filtering): ${e.message}")
            emptySet()
        }
    }

    fun close() {
        try { client.close() } catch (_: Exception) {}
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
}