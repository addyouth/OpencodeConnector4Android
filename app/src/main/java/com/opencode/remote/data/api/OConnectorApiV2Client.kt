package com.opencode.remote.data.api

import android.util.Log
import android.util.Base64
import com.opencode.remote.data.api.dto.v2.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManager

/**
 * REST API client for OpenCode server v2 (probed on 2.0.10).
 *
 * Base path is /api/*, auth scheme identical to v1 (Basic opencode:password).
 * 与 v1 的关键差异（详见 docs/V2-ADAPT.md）：
 * - 列表/单查/创建全部包 {data}（列表另带 cursor），调用方只取 .data
 * - prompt 只收 text；agent/model 先经 setSessionAgent/Model 对齐再 prompt
 * - revert 两段式 stage→commit；question 改 form；Todo 无端点
 * - 会话过滤走普通 query（project/parentID/directory），无需 x-opencode-directory 头
 */
class OConnectorApiV2Client @Inject constructor(
    private val json: Json,
) {

    private var authHeader: String? = null
    private var insecureTrust: Boolean = false

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
        private const val TAG = "OConnectorApiV2"
        private const val MAX_MESSAGES = 50
        private const val PAGE_LIMIT = 100
    }

    /** Configure (or reconfigure) the client with connection parameters. */
    fun configure(baseUrl: String, username: String = "", password: String = "", insecureTrust: Boolean = false) {
        close()
        this.insecureTrust = insecureTrust
        authHeader = if (password.isNotEmpty()) {
            "Basic " + Base64.encodeToString(
                "${username.ifEmpty { "opencode" }}:$password".toByteArray(),
                Base64.NO_WRAP
            )
        } else null
        client = HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
            defaultRequest {
                url(baseUrl)
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
    }

    // ---- Sessions ----

    /** GET /api/session → {data, cursor}（单页，上限 PAGE_LIMIT） */
    suspend fun listSessions(project: String? = null, parentID: String? = null, limit: Int = PAGE_LIMIT): List<V2SessionInfo> {
        val page = client.get("/api/session") {
            project?.let { parameter("project", it) }
            parentID?.let { parameter("parentID", it) }
            parameter("limit", limit)
        }.body<V2SessionPage>()
        Log.d(TAG, "Loaded ${page.data.size} sessions project=$project parentID=$parentID")
        return page.data
    }

    /**
     * Fetch sessions from ALL known projects (mirror of v1 listAllSessions).
     *   1. GET /api/project → all projects
     *   2. Per project: GET /api/session?project={id} (?project= 取 id，实测)
     *   3. Merge + dedupe.
     */
    suspend fun listAllSessions(): List<V2SessionInfo> {
        val allSessions = mutableListOf<V2SessionInfo>()
        val seenIds = mutableSetOf<String>()
        try {
            val projects = listProjects()
            Log.d(TAG, "Discovered ${projects.size} projects: ${projects.map { "${it.id}=${it.canonical}" }}")
            val results = coroutineScope {
                projects.map { project ->
                    async {
                        try {
                            listSessions(project = project.id)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to load sessions for project ${project.id}: ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
            for (sessions in results) {
                for (session in sessions) {
                    if (seenIds.add(session.id)) allSessions.add(session)
                }
            }
            Log.d(TAG, "Merged ${allSessions.size} unique sessions from ${projects.size} projects")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list projects, falling back to single query: ${e.message}")
            return listSessions()
        }
        return allSessions
    }

    /** POST /api/session → {data}（建会话可直接指定 agent/model） */
    suspend fun createSession(
        title: String? = null,
        agent: String? = null,
        model: V2ModelRef? = null,
        directory: String? = null,
    ): V2SessionInfo =
        client.post("/api/session") {
            setBody(V2CreateSessionRequest(title = title, agent = agent, model = model, location = directory?.let { V2LocationRef(it) }))
        }.body<V2CreateSessionResponse>().data

    /** GET /api/session/{id} → {data} */
    suspend fun getSession(id: String): V2SessionInfo =
        client.get("/api/session/$id").body<V2SessionSingle>().data

    /** DELETE /api/session/{id} */
    suspend fun deleteSession(id: String) {
        client.delete("/api/session/$id")
    }

    /** POST /api/session/{id}/fork {before?} → {data} */
    suspend fun forkSession(id: String, before: String? = null): V2SessionInfo =
        client.post("/api/session/$id/fork") {
            setBody(V2ForkRequest(before = before))
        }.body<V2CreateSessionResponse>().data

    /** POST /api/session/{id}/interrupt（无 body，对应 v1 abort） */
    suspend fun interruptSession(id: String) {
        client.post("/api/session/$id/interrupt")
    }

    /** POST /api/session/{id}/revert/stage（两段式之一步） */
    suspend fun revertStage(id: String, messageID: String) {
        client.post("/api/session/$id/revert/stage") {
            setBody(V2RevertStageRequest(messageID = messageID))
        }
    }

    /** POST /api/session/{id}/revert/commit（两段式之二步） */
    suspend fun revertCommit(id: String) {
        client.post("/api/session/$id/revert/commit")
    }

    /** PATCH /api/session/{id}（改标题） */
    suspend fun patchSessionTitle(id: String, title: String) {
        client.patch("/api/session/$id") {
            setBody(V2PatchSessionRequest(title = title))
        }
    }

    // ---- Messages ----

    /** GET /api/session/{id}/message → {data, cursor} */
    suspend fun getMessages(id: String, limit: Int? = null): List<V2Message> {
        val page = client.get("/api/session/$id/message") {
            parameter("limit", limit ?: MAX_MESSAGES)
        }.body<V2MessagePage>()
        return if (page.data.size > MAX_MESSAGES) {
            Log.w(TAG, "Server returned ${page.data.size} messages despite limit=$MAX_MESSAGES, truncating")
            page.data.takeLast(MAX_MESSAGES)
        } else page.data
    }

    /**
     * POST /api/session/{id}/prompt {text}。
     * 注意：v2 prompt 不带 agent/model——发送前调用方须先经
     * setSessionAgent/setSessionModel 把会话对齐到目标选择。
     */
    suspend fun sendMessage(sessionId: String, text: String) {
        client.post("/api/session/$sessionId/prompt") {
            setBody(V2PromptRequest(text = text))
        }
    }

    /** POST /api/session/{id}/agent {agent*} */
    suspend fun setSessionAgent(id: String, agent: String) {
        client.post("/api/session/$id/agent") {
            setBody(V2SetAgentRequest(agent = agent))
        }
    }

    /** POST /api/session/{id}/model {model*} */
    suspend fun setSessionModel(id: String, model: V2ModelRef) {
        client.post("/api/session/$id/model") {
            setBody(V2SetModelRequest(model = model))
        }
    }

    // ---- Permission / Form replies ----

    /** POST /api/session/{id}/permission/{requestID}/reply（decision ∈ once|always|reject） */
    suspend fun replyPermission(sessionId: String, requestId: String, decision: String, message: String? = null) {
        client.post("/api/session/$sessionId/permission/$requestId/reply") {
            setBody(V2PermissionReply(decision = decision, message = message))
        }
        Log.d(TAG, "Permission reply: $decision for request=$requestId")
    }

    /** POST /api/session/{id}/form/{formID}/reply */
    suspend fun replyForm(sessionId: String, formId: String, answer: Map<String, kotlinx.serialization.json.JsonElement>) {
        client.post("/api/session/$sessionId/form/$formId/reply") {
            setBody(V2FormReply(answer = answer))
        }
        Log.d(TAG, "Form reply for form=$formId")
    }

    // ---- Session status / children ----

    /** GET /api/session/active → {data:{...}}（key 存在即活跃） */
    suspend fun getActiveSessionIds(): Set<String> {
        val resp = client.get("/api/session/active").body<V2ActiveResponse>()
        Log.d(TAG, "Loaded ${resp.data.size} active sessions")
        return resp.data.keys
    }

    /** GET /api/session?parentID={id}（子会话树，对应 v1 children） */
    suspend fun getSessionChildren(parentID: String): List<V2SessionInfo> =
        listSessions(parentID = parentID)

    // ---- Projects / agents / models ----

    /** GET /api/project → Project[]（裸数组，无 worktree 字段） */
    suspend fun listProjects(): List<V2Project> =
        client.get("/api/project").body<List<V2Project>>()

    /** GET /api/agent → {location, data} */
    suspend fun listAgents(): List<V2AgentInfo> =
        client.get("/api/agent").body<V2AgentPage>().data

    /** GET /api/model → {location, data}（Model.Info 必带 enabled，需求③过滤用） */
    suspend fun listModels(): List<V2ModelInfo> =
        client.get("/api/model").body<V2ModelPage>().data

    /** GET /api/model/default → {location, data} */
    suspend fun getDefaultModel(): V2ModelInfo? =
        client.get("/api/model/default").body<V2DefaultModel>().data

    /** Test connectivity by hitting a lightweight endpoint */
    suspend fun testConnection(): Boolean = try {
        listProjects()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Test connection failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    fun close() {
        try { client.close() } catch (_: Exception) {}
    }
}
