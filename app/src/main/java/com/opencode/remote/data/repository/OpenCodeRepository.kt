package com.opencode.remote.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.opencode.remote.data.api.OConnectorApiClient
import com.opencode.remote.data.api.OConnectorSseClient
import com.opencode.remote.data.api.FileMediaTypes
import com.opencode.remote.data.api.PtyWsClient
import com.opencode.remote.data.api.dto.*
import com.opencode.remote.data.datastore.ConnectionConfig
import com.opencode.remote.data.network.NetworkMonitor
import com.opencode.remote.service.SseForegroundService
import com.opencode.remote.ui.chat.ResponseSegment
import com.opencode.remote.ui.chat.PermissionRequestData
import com.opencode.remote.ui.chat.ModelSelectionRef
import com.opencode.remote.ui.chat.QuestionRequestData
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.OfflineQueuedMessage
import com.opencode.remote.data.sse.SseEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the current streaming state: which session is streaming,
 * the accumulated response segments, and the agent being used.
 */
data class StreamingState(
    val sessionId: String?,
    val segments: List<ResponseSegment>,
    val agent: String?,
)

/**
 * Interface for the OpenCode repository.
 * All methods map to actual OpenCode server v1.14.x API routes.
 */
interface OConnectorRepository {
    val isConnected: Boolean
    val currentGeneration: Long

    fun connect(config: ConnectionConfig)
    /** Start SSE foreground service and network monitor. Call AFTER testConnection() succeeds. */
    fun startSseService()
    fun disconnect()
    fun switchToServer(serverId: String, config: ConnectionConfig)
    fun getActiveServerId(): String?

    // ─── Session Operations ──────────────────────────────────────────

    suspend fun listSessions(directory: String? = null): List<SessionInfo>
    /** Fetch sessions from ALL known projects (multi-directory query). */
    suspend fun listAllSessions(): List<SessionInfo>
    /** agent 参数：新建会话指定默认代理（需求②） */
    suspend fun createSession(directory: String? = null, agent: String? = null): CreateSessionResponse
    suspend fun getSession(sessionId: String, directory: String? = null): SessionInfo
    suspend fun deleteSession(sessionId: String, directory: String? = null)
    suspend fun forkSession(sessionId: String, directory: String? = null): CreateSessionResponse
    suspend fun abortSession(sessionId: String, directory: String? = null)
    suspend fun revertSession(sessionId: String, messageID: String, directory: String? = null): SessionInfo
    suspend fun unrevertSession(sessionId: String, directory: String? = null): SessionInfo

    // ─── Message Operations ──────────────────────────────────────────

    suspend fun getMessages(sessionId: String, directory: String? = null, limit: Int? = null): List<MessageInfo>
    suspend fun sendMessage(sessionId: String, message: String, agent: String? = null, providerID: String? = null, modelID: String? = null, variant: String? = null, directory: String? = null)
    /** v2: 切换会话 agent（需求②进会话带主代理） */
    suspend fun switchAgent(sessionId: String, agent: String)
    /** v2: 切换会话模型（v1 每消息模型语义在 v2 下改为会话级） */
    suspend fun switchModel(sessionId: String, providerID: String?, modelID: String?, variant: String? = null)

    // ─── Permission / Question Replies ──────────────────────────────

    suspend fun replyPermission(requestId: String, reply: String, message: String? = null, directory: String? = null, sessionId: String? = null)
    /** v2 #8 轮询挂起的权限确认（无 permission SSE 事件，桌面端同样轮询）。 */
    suspend fun pollPermissions(sessionId: String): List<PermissionRequestData>
    /** 服务端生效默认值（auto 显示真名用）。 */
    suspend fun getServerDefaults(): Pair<String?, ModelSelectionRef?>

    /** P2 离线队列：电梯/断流时存草稿，重连自动发出（带发送时选定的 agent/model）。 */
    suspend fun enqueueOffline(item: OfflineQueuedMessage)
    suspend fun flushOutbox(): Int
    fun isOnline(): Boolean
    suspend fun replyQuestion(requestId: String, answer: Map<String, JsonElement>, directory: String? = null)
    suspend fun rejectQuestion(requestId: String, directory: String? = null)

    // ─── Todo ────────────────────────────────────────────────────────

    suspend fun getTodoList(sessionId: String, directory: String? = null): List<TodoItem>

    // ─── Session Status ──────────────────────────────────────────────

    suspend fun getSessionStatus(): Map<String, String>
    suspend fun getSessionChildren(sessionId: String): List<SessionInfo>

    // ─── Project ─────────────────────────────────────────────────────

    suspend fun getCurrentProject(): ProjectInfo
    suspend fun listProjects(): List<ProjectInfo>

    // ─── Session Enhancement Pack（会话增强包：重命名/压缩/用量/搬迁/diff） ──
    suspend fun renameSession(sessionId: String, title: String): SessionInfo
    suspend fun compactSession(sessionId: String)
    suspend fun getSessionContext(sessionId: String): List<MessageInfo>
    suspend fun moveSession(sessionId: String, directory: String)
    suspend fun getSessionDiff(sessionId: String, from: String? = null, to: String? = null): List<FileDiffInfo>

    /** agent 详情 / vcs 状态 */
    suspend fun getAgentDetail(agentId: String, directory: String? = null): AgentInfo?
    suspend fun getVcsStatus(directory: String): List<FileDiffInfo>

    /** shell 直调（跳过 AI）：发命令轮询取输出 */
    suspend fun runShell(sessionId: String, command: String): ShellResult
    /** 二进制下载 + 系统应用打开 */
    suspend fun readFileBytes(path: String, directory: String?): ByteArray
    suspend fun openWithSystem(path: String, directory: String?): String

    /** worktree 真管理 */
    suspend fun listWorktrees(projectID: String): List<WorktreeInfo>
    suspend fun createWorktree(projectID: String, branch: String? = null, name: String? = null, directory: String? = null, from: String? = null)
    suspend fun removeWorktree(projectID: String, directory: String)
    suspend fun refreshWorktrees(projectID: String)

    /** 远程终端（单活跃）：输出流 + 状态流 */
    val terminalText: StateFlow<String>
    val terminalStatus: StateFlow<String>
    suspend fun terminalOpen(directory: String?): String
    fun terminalSend(text: String): Boolean
    fun terminalReconnect(): Boolean
    fun hasTerminal(): Boolean
    fun terminalClose(delete: Boolean)

    // ─── Test Connection ─────────────────────────────────────────────

    suspend fun testConnection(): Boolean

    /** 最近一次 testConnection 失败的真实原因（成功时为 null）。 */
    fun getLastTestError(): String?
    /** 最近一次 createSession 失败的真实原因（成功时为 null）。 */
    fun getLastCreateError(): String?
    /** 服务端可达性（心跳）：Tailscale 断开时 Android 网络仍在，只有这能发现。 */
    val serverReachable: StateFlow<Boolean>
    /**
     * 后台回来/进程复活后自愈（不用退到服务器列表手动重连）：
     * 已连接 → 测活，挂了重启 SSE 再测；未连接 → 用上次服务器直连（=服务器列表页自动连接同一套）。
     */
    suspend fun ensureConnected(): Boolean
    /** v2 question 表单：唯一活路（无 SSE、无 permission 条目）。 */
    suspend fun listQuestionForms(sessionId: String): List<QuestionRequestData>

    // ─── Agents ─────────────────────────────────────────────────────────

    /** 项目级 agent：directory 透给 /api/agent location[directory]（如 02.写作取 writ-assist），null 则 serve 默认目录。 */
    suspend fun listAgents(directory: String? = null): List<AgentInfo>
    fun getCachedAgents(): List<AgentInfo>

    // ─── Files ──────────────────────────────────────────────────────────

    suspend fun listFiles(path: String, directory: String? = null): List<FileNode>
    suspend fun readFileContent(path: String, directory: String? = null): FileContent

    // ─── Config / Providers ─────────────────────────────────────────────

    suspend fun listProviders(): ProviderList
    fun getCachedModels(): List<ModelInfo>

    // ─── Active Session (notification deep link) ─────────────────────

    var activeSessionId: String?
    var activeSessionDirectory: String?

    // ─── Server Name ─────────────────────────────────────────────────

    fun setServerName(name: String?)
    fun getCurrentServerName(): String?

    // ─── SSE Events ──────────────────────────────────────────────────

    fun subscribeToEvents(): Flow<ServerEvent>

    // ─── Streaming State Persistence ─────────────────────────────────

    fun beginStreaming(sessionId: String, agent: String?)
    fun setStreamingBlocks(segments: List<ResponseSegment>)
    fun setStreamingPendingMsgId(msgId: String?)
    fun getStreamingBlocksState(): StreamingState
    fun getStreamingPendingMsgId(): String?
    fun clearStreaming()

    // ─── Blocking State Cache (survives ViewModel recreation) ───────

    /** Persist blocking state for a session (question/permission data). */
    fun saveBlockingState(sessionId: String, permission: PermissionRequestData?, question: QuestionRequestData?)
    /** Retrieve persisted blocking state for a session. Returns null if none. */
    fun getBlockingState(sessionId: String): BlockingStateCache?
    /** Clear persisted blocking state for a session. */
    fun clearBlockingState(sessionId: String)
}

/** Cache entry for blocking state, stored by session ID. */
@Serializable
data class BlockingStateCache(
    val permission: PermissionRequestData? = null,
    val question: QuestionRequestData? = null,
)

/**
 * Concrete implementation that manages the API client lifecycle and exposes
 * all OpenCode server operations through clean suspend functions.
 */
@Singleton
class OConnectorRepositoryImpl @Inject constructor(
    private val apiClient: OConnectorApiClient,
    private val sseClient: OConnectorSseClient,
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val networkMonitor: NetworkMonitor,
    private val connectionPreferences: ConnectionPreferences,
    private val eventBus: SseEventBus,
) : OConnectorRepository {

    companion object {
        private const val TAG = "OConnectorRepository"
    }

    /** P2：重连回调里的 flush 用（短任务，单例常驻）。 */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong(0)
    override val currentGeneration: Long get() = connectionGeneration.get()

    @Volatile
    private var connected = false
    private var cachedAgents: List<AgentInfo>? = null
    private var agentsCacheTime: Long = 0
    private var cachedAgentsDir: String? = null
    private var cachedModels: List<ModelInfo>? = null
    private var modelsCacheTime: Long = 0
    private var activeServerId: String? = null
    private var activeServerName: String? = null

    override var activeSessionId: String? = null
    override var activeSessionDirectory: String? = null

    /** 服务端心跳：Tailscale 断开时 Android 网络仍在，NetworkMonitor 看不见，只能靠轮询。 */
    private val _serverReachable = MutableStateFlow(true)
    override val serverReachable: StateFlow<Boolean> = _serverReachable.asStateFlow()
    private var heartbeatJob: kotlinx.coroutines.Job? = null

    // ─── Disk persistence for state that must survive process death ───────
    private val statePrefs: SharedPreferences by lazy {
        context.getSharedPreferences("opencode_state_cache", Context.MODE_PRIVATE)
    }

    private fun persistBlockingState(sessionId: String, cache: BlockingStateCache?) {
        try {
            if (cache != null && (cache.permission != null || cache.question != null)) {
                val encoded = json.encodeToString(BlockingStateCache.serializer(), cache)
                statePrefs.edit()
                    .putString("block_$sessionId", encoded)
                    .putLong("block_timestamp_$sessionId", System.currentTimeMillis())
                    .commit()
            } else {
                statePrefs.edit()
                    .remove("block_$sessionId")
                    .remove("block_timestamp_$sessionId")
                    .commit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist blocking state for $sessionId", e)
        }
    }

    private fun restoreBlockingState(sessionId: String): BlockingStateCache? {
        return try {
            // Check TTL: if timestamp is missing or older than 5 minutes, treat as stale
            val timestamp = statePrefs.getLong("block_timestamp_$sessionId", 0L)
            if (timestamp == 0L || System.currentTimeMillis() - timestamp > 300_000L) {
                clearBlockingStateDisk(sessionId)
                return null
            }
            val encoded = statePrefs.getString("block_$sessionId", null)
            if (encoded != null) json.decodeFromString(BlockingStateCache.serializer(), encoded) else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore blocking state for $sessionId", e)
            null
        }
    }

    private fun clearBlockingStateDisk(sessionId: String) {
        statePrefs.edit()
            .remove("block_$sessionId")
            .remove("block_timestamp_$sessionId")
            .apply()
    }

    private fun persistStreamingState() {
        try {
            val sessionId = _streamingSessionId
            if (sessionId != null) {
                val segmentsJson = json.encodeToString(ListSerializer(ResponseSegment.serializer()), _streamingBlocks)
                statePrefs.edit()
                    .putString("stream_session", sessionId)
                    .putString("stream_segments", segmentsJson)
                    .putString("stream_agent", _streamingAgent)
                    .putString("stream_pending_msg", _streamingPendingMsgId)
                    .putLong("stream_timestamp", System.currentTimeMillis())
                    .commit()
            } else {
                statePrefs.edit()
                    .remove("stream_session")
                    .remove("stream_segments")
                    .remove("stream_agent")
                    .remove("stream_pending_msg")
                    .remove("stream_timestamp")
                    .commit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist streaming state", e)
        }
    }

    private fun clearStreamingDisk() {
        statePrefs.edit()
            .remove("stream_session")
            .remove("stream_segments")
            .remove("stream_agent")
            .remove("stream_pending_msg")
            .remove("stream_timestamp")
            .apply()
    }

    private fun restoreStreamingStateFromDisk(): StreamingState? {
        return try {
            val sessionId = statePrefs.getString("stream_session", null) ?: return null
            // Check TTL: if timestamp is missing or older than 5 minutes, treat as stale
            val timestamp = statePrefs.getLong("stream_timestamp", 0L)
            if (timestamp == 0L || System.currentTimeMillis() - timestamp > 300_000L) {
                clearStreamingDisk()
                return null
            }
            val segmentsJson = statePrefs.getString("stream_segments", null) ?: return null
            val segments = json.decodeFromString(ListSerializer(ResponseSegment.serializer()), segmentsJson)
            val agent = statePrefs.getString("stream_agent", null)
            StreamingState(sessionId, segments, agent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore streaming state from disk", e)
            null
        }
    }

    private fun restoreStreamingPendingMsgId(): String? {
        return try {
            statePrefs.getString("stream_pending_msg", null)
        } catch (e: Exception) {
            null
        }
    }

    override val isConnected: Boolean
        get() = connected

    override fun setServerName(name: String?) {
        activeServerName = name
    }

    override fun getCurrentServerName(): String? = activeServerName

    override fun switchToServer(serverId: String, config: ConnectionConfig) {
        if (activeServerId == serverId && connected) return  // already connected to this server
        if (connected) disconnect()
        connect(config)
        startSseService()
        activeServerId = serverId
    }

    override fun getActiveServerId(): String? = activeServerId

    /**
     * Configure API and SSE clients with connection parameters.
     * Does NOT start the foreground service — call [startSseService] after
     * [testConnection] confirms the server is reachable.
     */
    override fun connect(config: ConnectionConfig) {
        if (connected) disconnect()
        val scheme = if (config.useTls) "https" else "http"
        val baseUrl = "$scheme://${config.host}:${config.port}"

        apiClient.configure(baseUrl, config.username, config.password, config.insecureTrust)
        sseClient.configure(baseUrl, config.username, config.password, config.autoReconnect, config.insecureTrust)
        connected = true
        connectionGeneration.incrementAndGet()
    }

    /**
     * Start the SSE foreground service and network recovery monitor.
     * Must be called AFTER [testConnection] succeeds.
     */
    override fun startSseService() {
        if (!connected) return
        val gen = connectionGeneration.get()
        try {
            SseForegroundService.start(context, gen)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SSE foreground service", e)
        }

        networkMonitor.onNetworkAvailable = {
            if (connected) {
                Log.d(TAG, "Network recovered, restarting SSE")
                val restartGen = connectionGeneration.incrementAndGet()
                try {
                    SseForegroundService.restart(context, restartGen)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart SSE foreground service", e)
                }
                // P2 离线队列：重连后把草稿按序发出
                repoScope.launch {
                    try {
                        val sent = flushOutbox()
                        if (sent > 0) {
                            withContext(Dispatchers.Main) {
                                try {
                                    android.widget.Toast.makeText(
                                        context, "已发出${sent}条离线草稿", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "flushOutbox on reconnect failed", e) }
                }
            }
        }
        networkMonitor.start()
        startHeartbeat()
    }

    /**
     * 服务端心跳（15s 一次轻量 GET /api/project）：
     * Tailscale 断开不断 Android 网络，NetworkMonitor 不会回调，只有这里能发现。
     * 恢复时自动重启 SSE + 补发离线草稿，用户不用退到服务器列表手动重连。
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = repoScope.launch {
            while (true) {
                delay(15_000)
                if (!connected) break
                val ok = try { requireClient().testConnection() } catch (_: Exception) { false }
                val was = _serverReachable.value
                if (ok != was) {
                    _serverReachable.value = ok
                    Log.d(TAG, "Heartbeat: reachable=$ok (was $was)")
                }
                if (!was && ok) {
                    Log.d(TAG, "Heartbeat: server back, restarting SSE + flushing outbox")
                    val restartGen = connectionGeneration.incrementAndGet()
                    try { SseForegroundService.restart(context, restartGen) }
                    catch (e: Exception) { Log.e(TAG, "Failed to restart SSE on heartbeat recovery", e) }
                    try {
                        val sent = flushOutbox()
                        if (sent > 0) {
                            withContext(Dispatchers.Main) {
                                try {
                                    android.widget.Toast.makeText(
                                        context, "已重连，发出${sent}条离线草稿", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "flushOutbox on heartbeat recovery failed", e) }
                }
            }
        }
    }

    override fun isOnline(): Boolean = try {
        networkMonitor.isConnected.value
    } catch (e: Exception) { true }

    override suspend fun enqueueOffline(item: OfflineQueuedMessage) {
        try {
            val cur = connectionPreferences.getOutbox().toMutableList()
            cur.add(item)
            connectionPreferences.saveOutbox(cur.takeLast(20))
            Log.d(TAG, "Offline queued for session=${item.sessionId}")
        } catch (e: Exception) { Log.w(TAG, "enqueueOffline failed", e) }
    }

    override suspend fun flushOutbox(): Int {
        val items = try { connectionPreferences.getOutbox() } catch (e: Exception) { emptyList() }
        if (items.isEmpty()) return 0
        var sent = 0
        val failed = mutableListOf<OfflineQueuedMessage>()
        for (item in items) {
            try {
                requireClient().sendMessage(
                    sessionId = item.sessionId,
                    text = item.text,
                    agent = item.agent,
                    providerID = item.providerId,
                    modelID = item.modelId,
                    variant = item.variant,
                )
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "flushOutbox send failed, keeping: ${e.message}")
                failed.add(item)
            }
        }
        try { connectionPreferences.saveOutbox(failed) } catch (e: Exception) { Log.w(TAG, "flushOutbox save failed", e) }
        Log.d(TAG, "flushOutbox: sent=$sent kept=${failed.size}")
        return sent
    }

    /**
     * Disconnect from the server.
     */
    override fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        _serverReachable.value = true
        networkMonitor.stop()
        networkMonitor.onNetworkAvailable = null
        try { apiClient.close() } catch (_: Exception) {}
        try { sseClient.close() } catch (_: Exception) {}
        try { SseForegroundService.stop(context) } catch (_: Exception) {}
        connected = false
        cachedAgents = null
        cachedModels = null
        agentsCacheTime = 0
        cachedAgentsDir = null
        modelsCacheTime = 0
        activeSessionId = null
        activeSessionDirectory = null
        activeServerId = null
        activeServerName = null
    }

    private fun requireClient(): OConnectorApiClient {
        check(connected) { "Not connected to server" }
        return apiClient
    }

    // ─── Session Operations ──────────────────────────────────────────

    override suspend fun listSessions(directory: String?): List<SessionInfo> =
        requireClient().listSessions(directory)

    override suspend fun listAllSessions(): List<SessionInfo> =
        requireClient().listAllSessions()

    override suspend fun createSession(directory: String?, agent: String?): CreateSessionResponse =
        requireClient().createSession(directory, agent)

    override suspend fun getSession(sessionId: String, directory: String?): SessionInfo =
        requireClient().getSession(sessionId, directory)

    override suspend fun deleteSession(sessionId: String, directory: String?) =
        requireClient().deleteSession(sessionId, directory)

    override suspend fun forkSession(sessionId: String, directory: String?): CreateSessionResponse =
        requireClient().forkSession(sessionId, directory)

    override suspend fun abortSession(sessionId: String, directory: String?) =
        requireClient().abortSession(sessionId, directory)

    override suspend fun revertSession(sessionId: String, messageID: String, directory: String?): SessionInfo =
        requireClient().revertSession(sessionId, messageID, directory)

    override suspend fun unrevertSession(sessionId: String, directory: String?): SessionInfo =
        requireClient().unrevertSession(sessionId, directory)

    // ─── Message Operations ──────────────────────────────────────────

    override suspend fun getMessages(sessionId: String, directory: String?, limit: Int?): List<MessageInfo> =
        requireClient().getMessages(sessionId, directory, limit)

    override suspend fun sendMessage(sessionId: String, message: String, agent: String?, providerID: String?, modelID: String?, variant: String?, directory: String?) =
        requireClient().sendMessage(sessionId, message, agent, providerID, modelID, variant, directory)

    override suspend fun switchAgent(sessionId: String, agent: String) =
        requireClient().switchAgent(sessionId, agent)

    override suspend fun switchModel(sessionId: String, providerID: String?, modelID: String?, variant: String?) =
        requireClient().switchModel(sessionId, providerID, modelID, variant)

    // ─── Permission / Question Replies ──────────────────────────────

    override suspend fun replyPermission(requestId: String, reply: String, message: String?, directory: String?, sessionId: String?) =
        requireClient().replyPermission(requestId, reply, message, directory, sessionId)

    override suspend fun pollPermissions(sessionId: String): List<PermissionRequestData> =
        requireClient().listPendingPermissions(sessionId)

    override suspend fun getServerDefaults(): Pair<String?, ModelSelectionRef?> =
        try { requireClient().getServerDefaults() } catch (e: Exception) { Pair(null, null) }

    override suspend fun replyQuestion(requestId: String, answer: Map<String, JsonElement>, directory: String?) {
        val sid = activeSessionId ?: throw IllegalStateException("no active session")
        requireClient().answerQuestion(sid, requestId, answer)
    }

    override suspend fun listQuestionForms(sessionId: String): List<QuestionRequestData> =
        requireClient().listQuestionForms(sessionId)

    override suspend fun rejectQuestion(requestId: String, directory: String?) {
        // v2 无 question reject 路由 → 中止 turn 解锁（等价桌面“忽略”）
        val sid = activeSessionId
        if (sid != null) { try { requireClient().abortSession(sid, directory) } catch (_: Exception) {} }
    }

    // ─── Todo ────────────────────────────────────────────────────────

    override suspend fun getTodoList(sessionId: String, directory: String?): List<TodoItem> =
        requireClient().getTodoList(sessionId, directory)

    // ─── Session Status ─────────────────────────────────────────────────

    override suspend fun getSessionStatus(): Map<String, String> =
        requireClient().getSessionStatus()

    override suspend fun getSessionChildren(sessionId: String): List<SessionInfo> =
        requireClient().getSessionChildren(sessionId)

    // ─── Project ─────────────────────────────────────────────────────

    override suspend fun getCurrentProject(): ProjectInfo =
        requireClient().getCurrentProject()

    override suspend fun listProjects(): List<ProjectInfo> =
        requireClient().listProjects()

    // ─── Session Enhancement Pack ────────────────────────────────────

    override suspend fun renameSession(sessionId: String, title: String): SessionInfo =
        requireClient().renameSession(sessionId, title)

    override suspend fun compactSession(sessionId: String) =
        requireClient().compactSession(sessionId)

    override suspend fun getSessionContext(sessionId: String): List<MessageInfo> =
        requireClient().getSessionContext(sessionId)

    override suspend fun moveSession(sessionId: String, directory: String) =
        requireClient().moveSession(sessionId, directory)

    override suspend fun getSessionDiff(sessionId: String, from: String?, to: String?): List<FileDiffInfo> =
        requireClient().getSessionDiff(sessionId, from, to)

    override suspend fun getAgentDetail(agentId: String, directory: String?): AgentInfo? =
        requireClient().getAgentDetail(agentId, directory)

    override suspend fun getVcsStatus(directory: String): List<FileDiffInfo> =
        requireClient().getVcsStatus(directory)

    override suspend fun runShell(sessionId: String, command: String): ShellResult =
        requireClient().runShell(sessionId, command)

    override suspend fun readFileBytes(path: String, directory: String?): ByteArray =
        requireClient().readFileBytes(path, directory)

    /** 下载到缓存经 FileProvider 用系统应用打开（图片/PDF 走应用内，此处只管剩下的）。返回展示文案。 */
    override suspend fun openWithSystem(path: String, directory: String?): String {
        return try {
            val data = requireClient().readFileBytes(path, directory)
            val name = path.replace('\\', '/').substringAfterLast('/').ifEmpty { "file" }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80)
            val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
            val file = java.io.File(dir, System.currentTimeMillis().toString() + "_" + safe)
            file.writeBytes(data)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, FileMediaTypes.mimeFor(name))
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            "已用系统应用打开"
        } catch (e: Exception) {
            Log.w(TAG, "openWithSystem failed", e)
            "打开失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
        }
    }

    override suspend fun listWorktrees(projectID: String): List<WorktreeInfo> =
        requireClient().listWorktrees(projectID)

    override suspend fun createWorktree(projectID: String, branch: String?, name: String?, directory: String?, from: String?) =
        requireClient().createWorktree(projectID, branch, name, directory, from)

    override suspend fun removeWorktree(projectID: String, directory: String) =
        requireClient().removeWorktree(projectID, directory)

    override suspend fun refreshWorktrees(projectID: String) =
        requireClient().refreshWorktrees(projectID)

    // ─── Terminal（单活跃 pty + cursor 续连） ────────────────────────────

    private val _terminalText = MutableStateFlow("")
    override val terminalText: StateFlow<String> = _terminalText.asStateFlow()

    private val _terminalStatus = MutableStateFlow("closed")
    override val terminalStatus: StateFlow<String> = _terminalStatus.asStateFlow()

    private var ptySocket: PtyWsClient? = null
    private var ptyId: String? = null
    private var ptyCursor: Long = -1L

    override fun hasTerminal(): Boolean = ptyId != null

    override suspend fun terminalOpen(directory: String?): String {
        val info = requireClient().createPty(directory)
        val id = info.id.ifEmpty { throw IllegalStateException("empty pty id") }
        ptyId = id
        ptyCursor = -1L
        _terminalText.value = ""
        connectPtySocket(id, -1L)
        return id
    }

    override fun terminalSend(text: String): Boolean =
        try { ptySocket?.send(text) ?: false } catch (_: Exception) { false }

    override fun terminalReconnect(): Boolean {
        val id = ptyId ?: return false
        val c = try { ptySocket?.lastCursor?.takeIf { it >= 0 } ?: ptyCursor } catch (_: Exception) { ptyCursor }
        ptyCursor = c
        connectPtySocket(id, c)
        return true
    }

    override fun terminalClose(delete: Boolean) {
        try { ptySocket?.lastCursor?.takeIf { it >= 0 }?.let { ptyCursor = it } } catch (_: Exception) {}
        try { ptySocket?.close() } catch (_: Exception) {}
        ptySocket = null
        _terminalStatus.value = "closed"
        if (delete) {
            val id = ptyId
            ptyId = null
            if (!id.isNullOrEmpty()) {
                repoScope.launch {
                    try { apiClient.deletePty(id) } catch (e: Exception) { Log.w(TAG, "deletePty failed", e) }
                }
            }
        }
    }

    private fun connectPtySocket(id: String, cursor: Long) {
        try { ptySocket?.close() } catch (_: Exception) {}
        val base = requireClient().serverBaseUrl()
        val auth = try { requireClient().serverAuthHeader() } catch (_: Exception) { null }
        val ws = base.replaceFirst("http", "ws") + "/api/pty/$id/connect?cursor=$cursor"
        _terminalStatus.value = "connecting"
        val client = PtyWsClient()
        ptySocket = client
        client.connect(
            url = ws,
            authHeader = auth,
            onOutput = { chunk ->
                _terminalText.value = (_terminalText.value + chunk).takeLast(120_000)
            },
            onClosed = { reason ->
                _terminalStatus.value = "closed: $reason"
            },
        )
        _terminalStatus.value = "open"
    }

    // ─── Test Connection ─────────────────────────────────────────────

    override suspend fun testConnection(): Boolean =
        requireClient().testConnection()

    /**
     * 后台回来/进程复活后自愈：调用方（聊天/会话页 resume）先调它再干活。
     * 睡眠后陈旧 socket、进程被杀后 restored 导航，两条都盖住。
     */
    override suspend fun ensureConnected(): Boolean {
        return try {
            if (connected) {
                val ok = try { requireClient().testConnection() } catch (_: Exception) { false }
                if (ok) {
                    _serverReachable.value = true
                    // HTTP 绿但 SSE 流死（serve 活着、长连接断了）：心跳看不见，只能看总线新鲜度
                    if (System.currentTimeMillis() - eventBus.lastEventTime > 90_000) {
                        Log.d(TAG, "ensureConnected: SSE stale >90s, restarting")
                        val g = connectionGeneration.incrementAndGet()
                        try { SseForegroundService.restart(context, g) } catch (_: Exception) {}
                    }
                    return true
                }
                Log.d(TAG, "ensureConnected: connected but stale, restarting SSE")
                val g = connectionGeneration.incrementAndGet()
                try { SseForegroundService.restart(context, g) } catch (_: Exception) {}
                startHeartbeat()
                val ok2 = try { requireClient().testConnection() } catch (_: Exception) { false }
                _serverReachable.value = ok2
                ok2
            } else {
                // 进程复活：用上次服务器直连（与服务器列表页自动连接同一套参数）
                val lastId = try { connectionPreferences.lastActiveServerId.first() } catch (_: Exception) { null }
                    ?: return false
                val servers = try { connectionPreferences.savedServers.first() } catch (_: Exception) { emptyList() }
                val server = servers.find { it.id == lastId } ?: return false
                val password = try {
                    withContext(Dispatchers.IO) { connectionPreferences.getServerPassword(lastId) }
                } catch (_: Exception) { null } ?: ""
                connect(ConnectionConfig(
                    serverId = server.id,
                    host = server.host,
                    port = server.port,
                    username = server.username,
                    password = password,
                    useTls = server.useTls,
                    insecureTrust = server.insecureTrust,
                ))
                setServerName(server.name)
                val ok = try { requireClient().testConnection() } catch (_: Exception) { false }
                if (ok) {
                    startSseService()
                    try { connectionPreferences.saveLastActiveServerId(lastId) } catch (_: Exception) {}
                    _serverReachable.value = true
                    true
                } else {
                    try { disconnect() } catch (_: Exception) {}
                    _serverReachable.value = false
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureConnected failed", e)
            false
        }
    }

    override fun getLastTestError(): String? =
        try { apiClient.lastTestError } catch (_: Exception) { null }

    override fun getLastCreateError(): String? =
        try { apiClient.lastCreateError } catch (_: Exception) { null }

    // ─── Agents ─────────────────────────────────────────────────────────

    override suspend fun listAgents(directory: String?): List<AgentInfo> {
        // Return cache if valid (within 30s TTL) AND same directory (per-project agents differ)
        if (cachedAgents != null && cachedAgentsDir == directory &&
            System.currentTimeMillis() - agentsCacheTime < 30_000) {
            return cachedAgents!!
        }
        val agents = requireClient().listAgents(directory)
            .filter { it.mode != "subagent" && it.hidden != true }
        cachedAgents = agents
        cachedAgentsDir = directory
        agentsCacheTime = System.currentTimeMillis()
        return agents
    }

    /** Get cached agents (returns empty list if not loaded yet) */
    override fun getCachedAgents(): List<AgentInfo> = cachedAgents ?: emptyList()

    // ─── Files ──────────────────────────────────────────────────────────

    override suspend fun listFiles(path: String, directory: String?): List<FileNode> =
        requireClient().listFiles(path, directory)

    override suspend fun readFileContent(path: String, directory: String?): FileContent =
        requireClient().readFileContent(path, directory)

    // ─── Config / Providers ─────────────────────────────────────────────

    /**
     * v2 适配（需求③）：
     *   /api/provider + /api/model 全量模型 → 读服务端 opencode.json 的 v1 whitelist
     *   → 只保留勾选模型 → 按 provider 分组构造 v1 ProviderList 形状（UI 零改动）。
     * whitelist 读取失败时降级为全量（不阻塞使用）。
     */
    override suspend fun listProviders(): ProviderList {
        val providers = requireClient().listProvidersV2()
        val models = requireClient().listModelsV2()
        val whitelist = requireClient().readModelWhitelist()

        val filteredModels = if (whitelist.isEmpty()) {
            models
        } else {
            models.filter { m ->
                whitelist.contains(m.whitelistKey) || whitelist.contains(m.modelID) || whitelist.contains(m.id)
            }
        }
        Log.d(TAG, "Model filter: ${models.size} total → ${filteredModels.size} whitelisted")

        val providerNameById = providers.associate { it.id to (it.name ?: it.id) }
        val activationById = providers.associate { it.id to it.activation }
        val providerInfos = filteredModels.groupBy { it.providerID }.map { (pid, mods) ->
            ProviderInfo(
                id = pid,
                name = providerNameById[pid] ?: pid,
                models = mods.associate { m ->
                    m.modelID to ProviderModelInfo(
                        id = m.modelID,
                        name = m.name ?: m.modelID,
                        limit = m.limit,
                        variants = m.variants.associate { v ->
                            v.id to kotlinx.serialization.json.JsonPrimitive(v.name ?: v.id)
                        },
                    )
                },
            )
        }.sortedBy { it.id }

        val connected = providerInfos
            .filter { activationById[it.id] != "disabled" }
            .map { it.id }

        val result = ProviderList(providers = providerInfos, connected = connected)

        // 缓存给 getCachedModels()（legacy availableModels 用）
        cachedModels = filteredModels.map { m ->
            ModelInfo(id = m.modelID, name = m.name, providerID = m.providerID, status = m.status)
        }
        modelsCacheTime = System.currentTimeMillis()
        Log.d(TAG, "ProviderList built: ${providerInfos.size} providers, ${filteredModels.size} models, connected=${connected.size}")
        return result
    }

    override fun getCachedModels(): List<ModelInfo> = cachedModels ?: emptyList()

    // ─── SSE Events ──────────────────────────────────────────────────

    override fun subscribeToEvents(): Flow<ServerEvent> {
        check(connected) { "Not connected to server" }
        return sseClient.subscribeToEvents()
    }

    // ─── Streaming State Persistence ─────────────────────────────────
    // Survives ViewModel recreation when navigating away and back.
    // Stored in @Singleton Repository so state lives as long as the app process.
    //
    // Thread safety: All streaming state fields are accessed only from the
    // main thread via viewModelScope coroutines. The Repository is @Singleton
    // and all callers dispatch on Dispatchers.Main, so no synchronization
    // primitives are needed.

    /** Currently streaming session ID, or null if no active stream. */
    private var _streamingSessionId: String? = null

    /** Accumulated response segments for the current streaming message. */
    private var _streamingBlocks: List<ResponseSegment> = emptyList()

    /** Agent name used for the current streaming session, or null. */
    private var _streamingAgent: String? = null

    /** Pending message ID used to correlate SSE events after send. */
    private var _streamingPendingMsgId: String? = null

    override fun beginStreaming(sessionId: String, agent: String?) {
        _streamingSessionId = sessionId
        _streamingBlocks = emptyList()
        _streamingAgent = agent
        _streamingPendingMsgId = null
        persistStreamingState()
    }

    override fun setStreamingBlocks(segments: List<ResponseSegment>) {
        _streamingBlocks = segments
        persistStreamingState()
    }

    override fun setStreamingPendingMsgId(msgId: String?) {
        _streamingPendingMsgId = msgId
        persistStreamingState()
    }

    override fun getStreamingBlocksState(): StreamingState {
        // Memory cache first
        if (_streamingSessionId != null) {
            return StreamingState(_streamingSessionId, _streamingBlocks, _streamingAgent)
        }
        // Fall back to disk (survives process death)
        val fromDisk = restoreStreamingStateFromDisk()
        if (fromDisk != null && fromDisk.sessionId != null) {
            _streamingSessionId = fromDisk.sessionId
            _streamingBlocks = fromDisk.segments
            _streamingAgent = fromDisk.agent
            return fromDisk
        }
        return StreamingState(null, emptyList(), null)
    }

    override fun getStreamingPendingMsgId(): String? {
        if (_streamingPendingMsgId != null) return _streamingPendingMsgId
        return restoreStreamingPendingMsgId()
    }

    override fun clearStreaming() {
        _streamingSessionId = null
        _streamingBlocks = emptyList()
        _streamingAgent = null
        _streamingPendingMsgId = null
        clearStreamingDisk()
    }

    // ─── Blocking State Cache ──────────────────────────────────────

    private val _blockingStateCache = mutableMapOf<String, BlockingStateCache>()

    override fun saveBlockingState(sessionId: String, permission: PermissionRequestData?, question: QuestionRequestData?) {
        if (permission != null || question != null) {
            val cache = BlockingStateCache(permission, question)
            _blockingStateCache[sessionId] = cache
            persistBlockingState(sessionId, cache)
        } else {
            _blockingStateCache.remove(sessionId)
            persistBlockingState(sessionId, null)
        }
    }

    override fun getBlockingState(sessionId: String): BlockingStateCache? {
        // Memory cache first, then fall back to disk (survives process death)
        val cached = _blockingStateCache[sessionId]
        if (cached != null) return cached
        val fromDisk = restoreBlockingState(sessionId)
        if (fromDisk != null) {
            _blockingStateCache[sessionId] = fromDisk  // warm memory cache
        }
        return fromDisk
    }

    override fun clearBlockingState(sessionId: String) {
        _blockingStateCache.remove(sessionId)
        clearBlockingStateDisk(sessionId)
    }
}
