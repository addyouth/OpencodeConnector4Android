package com.opencode.remote.ui.chat

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.remote.AppForegroundTracker
import com.opencode.remote.OConnectorApp
import com.opencode.remote.R
import com.opencode.remote.data.api.dto.*
import com.opencode.remote.data.datastore.ConnectionPreferences
import com.opencode.remote.data.datastore.OfflineQueuedMessage
import com.opencode.remote.data.datastore.StoredModelSelection
import com.opencode.remote.data.datastore.TemplateEntry
import com.opencode.remote.data.repository.OConnectorRepository
import com.opencode.remote.data.sse.SseEventBus
import com.opencode.remote.ui.strings.AppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** 手机附件：先传（fs.write）后引（prompt files[]），uri 为服务端绝对路径。 */
data class AttachedFile(
    val uri: String,
    val name: String,
)

/** A single segment in the streaming or completed assistant response. */
@Serializable
data class ResponseSegment(
    val type: String,  // "thinking", "text", "tool"
    val text: String,
    val isStreaming: Boolean = false,  // true if still receiving content
    val id: String? = null,  // callID for tool segments, null for text/thinking — prevents tool calls from overwriting each other
)

/** Permission request data extracted from permission.asked SSE event. */
@Serializable
data class PermissionRequestData(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val always: List<String> = emptyList(),
    val tool: ToolRef? = null,
)

/** Question request data extracted from question.asked SSE event. */
@Serializable
data class QuestionRequestData(
    val id: String,
    val sessionID: String,
    val questions: List<QuestionInfoDto>,
    val tool: ToolRef? = null,
)

/** Session metadata — changes infrequently (init, session events). */
data class SessionMetaState(
    val sessionId: String = "",
    val sessionDirectory: String? = null,
    val sessionTitle: String? = null,
    val sessionStatus: String? = null,
    /** Non-null when session has an active revert (undo) — used to filter messages and show redo button. */
    val revertMessageId: String? = null,
)

/** Streaming UI state — changes rapidly during AI response (text deltas, agent info). */
data class StreamingDisplayState(
    val isStreaming: Boolean = false,
    val isSending: Boolean = false,
    val streamingSegments: List<ResponseSegment> = emptyList(),
    val streamingAgent: String? = null,
    val pendingAssistantMessageId: String? = null,
)

/** Chat display state — messages, input, panels, agents, errors. */
data class ChatDisplayState(
    val messages: List<MessageInfo> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val todoItems: List<TodoItem> = emptyList(),
    val showTodoPanel: Boolean = false,
    val availableAgents: List<AgentInfo> = emptyList(),
    val selectedAgent: String? = null,
    val availableAgentsError: Boolean = false,
    // Panel state
    val isPanelOpen: Boolean = false,
    val panelFiles: List<FileNode> = emptyList(),
    val currentFilePath: String = ".",
    val isLoadingFiles: Boolean = false,
    // File preview state
    val expandedFilePath: String? = null,
    val expandedFileContent: String? = null,
    val isLoadingFileContent: Boolean = false,
    // Model state
    val selectedModel: ModelInfo? = null,
    val availableModels: List<ModelInfo> = emptyList(),
    val isLoadingModels: Boolean = false,
    val availableModelsError: Boolean = false,
    // Context state
    val contextUsageK: String = "0K",
    // Selection state (agent/model/variant with committed/draft separation)
    val selection: ChatSelectionUiState = ChatSelectionUiState(),
    // Blocking interaction state (permission/question bubbles)
    val pendingPermission: PermissionRequestData? = null,
    val pendingQuestion: QuestionRequestData? = null,
    val isBlocked: Boolean = false,  // true when AI is waiting for user response
    val recoveryPending: Boolean = false,  // true when heuristic detected possible interrupted blocking state
    // Session Enhancement Pack: diff viewer state
    val showDiffDialog: Boolean = false,
    val diffFiles: List<FileDiffInfo> = emptyList(),
    val isLoadingDiff: Boolean = false,
    // shell 直调弹窗状态
    val showShellDialog: Boolean = false,
    val shellCommand: String = "",
    val shellOutput: String = "",
    val isRunningShell: Boolean = false,
    // 媒体查看状态（图片应用内 / PDF 应用内 / 其余系统应用）
    val showImageDialog: Boolean = false,
    val imageBytes: ByteArray? = null,
    val imageName: String = "",
    val isLoadingImage: Boolean = false,
    val showPdfDialog: Boolean = false,
    val pdfFile: java.io.File? = null,
    val pdfName: String = "",
    val isLoadingPdf: Boolean = false,
    // agent 详情 / vcs 状态
    val showAgentDetail: Boolean = false,
    val agentDetail: AgentInfo? = null,
    val isLoadingAgentDetail: Boolean = false,
    val showVcsDialog: Boolean = false,
    val vcsFiles: List<FileDiffInfo> = emptyList(),
    val isLoadingVcs: Boolean = false,
    // 服务端可达性（心跳）：false 时顶栏下挂“不可达”横条
    val serverUnreachable: Boolean = false,
    // 附件（图片/文件）：待随下一次发送一起发出
    val attachedFiles: List<AttachedFile> = emptyList(),
    val isUploading: Boolean = false,
)

data class ChatUiState(
    val sessionMeta: SessionMetaState = SessionMetaState(),
    val streaming: StreamingDisplayState = StreamingDisplayState(),
    val chatDisplay: ChatDisplayState = ChatDisplayState(),
) {
    // ── Convenience delegation properties for backward-compatible reads ──
    val sessionId get() = sessionMeta.sessionId
    val sessionDirectory get() = sessionMeta.sessionDirectory
    val sessionTitle get() = sessionMeta.sessionTitle
    val sessionStatus get() = sessionMeta.sessionStatus
    val revertMessageId get() = sessionMeta.revertMessageId

    val isStreaming get() = streaming.isStreaming
    val isSending get() = streaming.isSending
    val streamingSegments get() = streaming.streamingSegments
    val streamingAgent get() = streaming.streamingAgent
    val pendingAssistantMessageId get() = streaming.pendingAssistantMessageId

    val messages get() = chatDisplay.messages
    val inputText get() = chatDisplay.inputText
    val isLoading get() = chatDisplay.isLoading
    val error get() = chatDisplay.error
    val todoItems get() = chatDisplay.todoItems
    val showTodoPanel get() = chatDisplay.showTodoPanel
    val availableAgents get() = chatDisplay.availableAgents
    val selectedAgent get() = chatDisplay.selectedAgent
    val availableAgentsError get() = chatDisplay.availableAgentsError
    val isPanelOpen get() = chatDisplay.isPanelOpen
    val panelFiles get() = chatDisplay.panelFiles
    val currentFilePath get() = chatDisplay.currentFilePath
    val isLoadingFiles get() = chatDisplay.isLoadingFiles
    val expandedFilePath get() = chatDisplay.expandedFilePath
    val expandedFileContent get() = chatDisplay.expandedFileContent
    val isLoadingFileContent get() = chatDisplay.isLoadingFileContent
    val selectedModel get() = chatDisplay.selectedModel
    val availableModels get() = chatDisplay.availableModels
    val isLoadingModels get() = chatDisplay.isLoadingModels
    val availableModelsError get() = chatDisplay.availableModelsError
    val contextUsageK get() = chatDisplay.contextUsageK
    val pendingPermission get() = chatDisplay.pendingPermission
    val pendingQuestion get() = chatDisplay.pendingQuestion
    val isBlocked get() = chatDisplay.isBlocked
    val recoveryPending get() = chatDisplay.recoveryPending
    val selection get() = chatDisplay.selection
    val serverUnreachable get() = chatDisplay.serverUnreachable
    val attachedFiles get() = chatDisplay.attachedFiles
    val isUploading get() = chatDisplay.isUploading
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: OConnectorRepository,
    private val sseEventBus: SseEventBus,
    private val connectionPreferences: ConnectionPreferences,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sseJob: Job? = null
    private var pollingJob: Job? = null
    private var streamingWatchdogJob: Job? = null
    private var blockingWatchdogJob: Job? = null
    private var lastSseEventTime = 0L  // Timestamp of last SSE event — used for fallback polling
    private var serverReachCollecting = false  // 心跳流只收一次（initialize 会被反复调用）

    /**
     * IDs of messages that already completed — guards against late [message.updated] re-triggering streaming.
     *
     * This is a [mutableSetOf] (not thread-safe) by design:
     * - All reads and writes happen inside [viewModelScope.launch] coroutines,
     *   which run on [Dispatchers.Main] by default.
     * - The set is accessed from [initialize], [sendMessage], and [handleEvent] —
     *   all of which are invoked on the main dispatcher.
     * - Therefore no synchronization (e.g. [ConcurrentHashMap.newKeySet]) is needed.
     */
    private val completedMessageIds = mutableSetOf<String>()
    /** Cache partID → confirmed segType from message.part.updated. Used to correctly classify
     *  subsequent message.part.delta events that may arrive with field=null. */
    private val partTypeMap = mutableMapOf<String?, String>()
    private var deltaLogCounter = 0
    private var pendingDeltas = mutableListOf<ServerEvent>()  // accumulated delta events for 16ms batching
    private var batchFlushJob: Job? = null  // debounce job for 16ms coalescing window
    /** Queue of permission requests that arrived while another permission is being handled. */
    private val permissionQueue = mutableListOf<PermissionRequestData>()
    /** #8 权限轮询任务（v2 无 permission SSE，turn 中 5s 一次，有挂起即弹气泡）。 */
    private var permissionPollJob: Job? = null

    companion object {
        private const val TAG = "ChatViewModel"
        private const val MAX_STREAMING_TEXT = 10_000
        private const val TODO_COMPLETED_NOTIFICATION_ID = 2001
        private const val PERMISSION_NOTIFICATION_ID = 2002
        private const val QUESTION_NOTIFICATION_ID = 2003
        /** 快捷模板预设（常驻不可删改） */
        val DEFAULT_TEMPLATES = listOf(
            TemplateEntry("打卡", "打卡"),
            TemplateEntry("重启服务", "重启服务"),
        )
    }

    /**
     * Initialize (or re-initialize) the chat session.
     *
     * CRITICAL ORDERING:
     * 1. Load messages from server (await synchronously)
     * 2. Pre-populate completedMessageIds from loaded messages (prevents stale events)
     * 3. Check if streaming state should be restored vs turn completed while away
     * 4. Subscribe to SSE events AFTER state is fully set (prevents race conditions)
     *
     * This ordering ensures the UI never flashes between empty/streaming/idle states.
     */
    fun initialize(sessionId: String, directory: String? = null) {
        pollingJob?.cancel()
        blockingWatchdogJob?.cancel()
        lastSseEventTime = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                sessionMeta = it.sessionMeta.copy(
                    sessionId = sessionId,
                    sessionDirectory = directory,
                    // 跨会话必须清 revert：否则上个会话的 undo 锚点会把新会话的
                    // local_* 乐观消息（'l'<'m' 恒成立）连同服务端消息一起滤掉
                    revertMessageId = null,
                ),
                // Reset streaming + display state to prevent stale data from previous session
                streaming = StreamingDisplayState(),
                // NOTE: .copy() only overrides listed fields — blocking state is preserved
                // when the same ViewModel instance calls initialize() again. For cross-ViewModel
                // recovery (new NavBackStackEntry), the repository cache is used (Step 5).
                chatDisplay = it.chatDisplay.copy(
                    isLoading = true,
                    contextUsageK = "0K",
                    expandedFilePath = null,
                    expandedFileContent = null,
                    isLoadingFileContent = false,
                    error = null,
                    availableAgentsError = false,
                ),
            )
        }
        Log.d(TAG, "initialize() preserving blocking state: permission=${_uiState.value.pendingPermission != null}, question=${_uiState.value.pendingQuestion != null}, blocked=${_uiState.value.isBlocked}")

        // Track active session for notification deep link
        repository.activeSessionId = sessionId
        repository.activeSessionDirectory = directory

        // 服务端可达性：心跳流只收一次（initialize 会被反复调用），断连挂横条、恢复自动清
        if (!serverReachCollecting) {
            serverReachCollecting = true
            viewModelScope.launch {
                try {
                    repository.serverReachable.collect { ok ->
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(serverUnreachable = !ok)) }
                        if (ok) {
                            // 恢复后顺手把离线草稿补发（repository 心跳里也会发，双保险去重靠 outbox 清空）
                            try {
                                val sent = repository.flushOutbox()
                                if (sent > 0) {
                                    try { Toast.makeText(appContext, "已重连，发出${sent}条离线草稿", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        } else {
            try {
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(serverUnreachable = !repository.serverReachable.value)) }
            } catch (_: Exception) {}
        }

        // These can run in parallel — they don't affect streaming state
        loadSessionInfo()
        loadTodoList()
        loadAgents()
        loadModels()
        loadServerDefaults()

        // Core init: heal connection first (background return / process restore),
        // then load messages → check state → subscribe to SSE (sequential)
        viewModelScope.launch {
            // Cache sessionId at launch time to detect stale coroutines
            val initSessionId = sessionId

            // 自愈：后台回来连接已死时先续命，否则下面 getMessages 必跪、用户被迫退到服务器列表
            try { repository.ensureConnected() } catch (_: Exception) {}

            // ── Step 1: Load messages from server (await synchronously) ──
            val hasData = _uiState.value.messages.isNotEmpty()
            if (!hasData) {
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoading = true)) }
            }
            val messages = try {
                repository.getMessages(sessionId, directory)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load messages", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update {
                    it.copy(chatDisplay = it.chatDisplay.copy(isLoading = false, error = s.errLoadMessages.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)))
                }
                subscribeToEvents()
                return@launch
            }

            // Guard: if initialize() was called again with a different sessionId,
            // discard this stale coroutine's results to avoid overwriting new session state.
            if (_uiState.value.sessionId != initSessionId) {
                Log.d(TAG, "Stale initialize for $initSessionId, current is ${_uiState.value.sessionId}, aborting after message load")
                return@launch
            }

            // ── Step 2: Pre-populate completedMessageIds from loaded messages ──
            // This prevents stale message.updated events from re-triggering streaming
            // for messages that already have a completed timestamp from the server.
            completedMessageIds.clear()
            messages.filter { msg ->
                msg.role == "assistant" && msg.info.time?.completed != null && msg.info.time.completed > 0
            }.forEach { msg ->
                completedMessageIds.add(msg.id)
            }

            // Guard: re-check before streaming state restoration — the expensive
            // getStreamingBlocksState() call may have given a stale coroutine enough
            // time for a second initialize() to update the sessionId.
            if (_uiState.value.sessionId != initSessionId) {
                Log.d(TAG, "Stale initialize for $initSessionId, current is ${_uiState.value.sessionId}, aborting before streaming check")
                return@launch
            }

            // ── Step 3: Check if streaming state should be restored ──
            val streamingState = repository.getStreamingBlocksState()
            val streamingSid = streamingState.sessionId
            val segments = streamingState.segments
            val agent = streamingState.agent
            val pendingMsgId = repository.getStreamingPendingMsgId()
            val shouldRestore = streamingSid == sessionId

            if (shouldRestore) {
                // Check if the turn completed while we were away:
                // The loaded messages include the assistant response with completed timestamp.
                val turnCompleted = pendingMsgId != null && messages.any { msg ->
                    msg.role == "assistant" && msg.id == pendingMsgId &&
                        msg.info.time?.completed != null && msg.info.time.completed > 0
                }

                // Also check: if the last assistant message has completed timestamp but
                // no pendingMsgId was stored (e.g. ViewModel killed before message.updated),
                // the session is idle. Force-clear stale streaming state.
                val lastAssistantCompleted = !turnCompleted && messages.lastOrNull()?.let { msg ->
                    msg.role == "assistant" &&
                        msg.info.time?.completed != null && msg.info.time.completed > 0
                } == true

                if (turnCompleted || lastAssistantCompleted) {
                    // Turn finished while user was away — clear stale streaming state
                    Log.d(TAG, "Turn completed while away, clearing streaming state (turnCompleted=$turnCompleted, lastAssistantCompleted=$lastAssistantCompleted)")
                    batchFlushJob?.cancel()
                    streamingWatchdogJob?.cancel()
                    synchronized(pendingDeltas) { pendingDeltas.clear() }
                    repository.clearStreaming()
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages.applyMessageFilters(it.sessionMeta.revertMessageId), isLoading = false)) }
                } else {
                    // Turn still in progress (or AI hasn't started responding yet)
                    // Restore full streaming state so the UI shows segments + stop button
                    Log.d(TAG, "Restoring streaming state: segs=${segments.size} pending=${pendingMsgId?.take(8)}")
                    _uiState.update {
                        it.copy(
                            chatDisplay = it.chatDisplay.copy(messages = messages.applyMessageFilters(it.sessionMeta.revertMessageId), isLoading = false),
                            streaming = it.streaming.copy(
                                isSending = true,
                                isStreaming = segments.isNotEmpty(),
                                streamingSegments = segments,
                                streamingAgent = agent,
                                pendingAssistantMessageId = pendingMsgId,
                            ),
                        )
                    }
                    // Start watchdog for restored streaming — if SSE never delivers
                    // session.idle (server already finished), force-clear after timeout.
                    startStreamingWatchdog()
                }
            } else {
                // No streaming state for this session — normal load
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages.applyMessageFilters(it.sessionMeta.revertMessageId), isLoading = false)) }
            }

            // Compute context usage from loaded messages
            updateContextUsage()

            // ── Step 4: Subscribe to SSE AFTER state is fully restored ──
            // This eliminates race conditions where stale SSE events arrive
            // before streaming state is set, causing premature clearing.
            subscribeToEvents()
            // 问答/权限轮询不再只靠 SSE 事件唤醒：流死时定时器就是唯一活路（安静期自退，不耗电）
            ensurePermissionPoll()

            // ── Step 5: Restore blocking state from cache or message check ──
            // First try to restore from repository cache (survives ViewModel recreation).
            // If no cached state, fall back to checking message completion.
            val cached = repository.getBlockingState(initSessionId)
            val currentDisplay = _uiState.value.chatDisplay
            if (cached != null && !currentDisplay.isBlocked) {
                val perm = cached.permission
                val qst = cached.question
                if (perm != null || qst != null) {
                    Log.d(TAG, "Restoring blocking state from cache: perm=${perm != null} qst=${qst != null}")
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        pendingPermission = perm,
                        pendingQuestion = qst,
                        isBlocked = true,
                    ))}
                    startBlockingWatchdog()
                }
            } else {
                checkSessionBlocking(messages)
            }

            // Start fallback polling (only when SSE stalls)
            startFallbackPolling(initSessionId)
        }
    }

    private fun loadSessionInfo() {
        viewModelScope.launch {
            try {
                val session = repository.getSession(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                val isCompleted = session.time?.completed != null && session.time.completed > 0
                // If we don't have directory yet, store it from session info
                val dir = _uiState.value.sessionDirectory ?: session.resolvedDirectory
                // 需求②：进会话自动带出该会话当前主代理（v2 Session.Info.agent）
                val sessionAgent = session.agent
                if (!sessionAgent.isNullOrBlank()) {
                    selectAgent(sessionAgent)
                }
                // 新发现3：同样带出会话当前模型（只在无显式选择时播种，不覆盖用户选择）
                val smProv = session.model?.providerID?.takeIf { it.isNotBlank() }
                val smId = session.model?.resolvedModelID?.takeIf { it.isNotBlank() }
                if (smProv != null && smId != null && _uiState.value.selection.committed.model == null) {
                    val ref = ModelSelectionRef(smProv, smId)
                    _uiState.update {
                        it.copy(chatDisplay = it.chatDisplay.copy(
                            selection = it.chatDisplay.selection.copy(
                                committed = it.chatDisplay.selection.committed.copy(model = ref),
                                draft = it.chatDisplay.selection.draft.copy(model = ref),
                            ),
                        ))
                    }
                }
                _uiState.update {
                    it.copy(
                        sessionMeta = it.sessionMeta.copy(
                            sessionTitle = session.title ?: session.slug ?: "${com.opencode.remote.ui.strings.AppLocale.strings.sessionFallback} ${session.id.take(8)}...",
                            sessionStatus = if (isCompleted) "completed" else "active",
                            sessionDirectory = dir,
                            revertMessageId = session.revert?.messageID,
                        ),
                    )
                }
                // 服务端权威用量顺带刷新（失败沿用本地启发值）
                refreshContextUsage()
            } catch (e: Exception) { Log.w(TAG, "Failed to load session info", e) }
        }
    }

    private fun loadTodoList() {
        viewModelScope.launch {
            try {
                val todos = repository.getTodoList(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                val hadActiveTodos = _uiState.value.todoItems.any { it.status != "completed" && it.status != "cancelled" }
                val activeTodos = todos.filter { it.status != "completed" && it.status != "cancelled" }
                val allDone = todos.isNotEmpty() && activeTodos.isEmpty()

                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    todoItems = activeTodos,
                    showTodoPanel = if (allDone) false else it.chatDisplay.showTodoPanel,
                ))}

                if (hadActiveTodos && allDone) {
                    showTodoCompletionNotification()
                }
            } catch (e: Exception) { Log.w(TAG, "Failed to load todo list", e) }
        }
    }

    /** 服务端生效默认值（auto 显示真名用，如 big-pickle（默认））。 */
    fun loadServerDefaults() {
        viewModelScope.launch {
            try {
                val (agent, model) = repository.getServerDefaults()
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    selection = it.chatDisplay.selection.copy(
                        resolvedDefaultAgent = agent,
                        resolvedDefaultModel = model,
                    ),
                ))}
            } catch (e: Exception) { Log.w(TAG, "load server defaults failed", e) }
        }
    }

    fun loadAgents() {
        viewModelScope.launch {
            try {
                // Per-project agents: resolve current session directory so e.g. 02.写作
                // sessions list writ-assist instead of Vault defaults.
                // NOTE v2 has no top-level SessionInfo.directory (lives in location.directory),
                // so prefer state sessionDirectory, then resolvedDirectory — never bare directory.
                val dir = _uiState.value.sessionDirectory
                    ?: try {
                        val sid = _uiState.value.sessionId
                        if (sid.isBlank()) null
                        else repository.getSession(sid, null)?.resolvedDirectory
                    } catch (_: Exception) { null }
                val agents = repository.listAgents(dir)
                _uiState.update {
                    it.copy(chatDisplay = it.chatDisplay.copy(
                        availableAgents = agents,
                        availableAgentsError = false,
                        selection = it.chatDisplay.selection.copy(availableAgents = agents),
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load agents", e)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(availableAgentsError = true)) }
            }
        }
    }

    private var subscribedGeneration: Long = 0L

    private fun subscribeToEvents() {
        subscribedGeneration = repository.currentGeneration
        sseJob?.cancel()
        sseJob = viewModelScope.launch {
            try {
                sseEventBus.events.collect { envelope ->
                    // Accept events from the service's generation — it may differ from
                    // repository.currentGeneration if the gen was incremented (e.g., network
                    // recovery callback) after the service was started.
                    if (envelope.generation < subscribedGeneration) {
                        // If the service is using a LOWER generation than what we subscribed with,
                        // it means the gen was incremented after service start. Adopt the service's
                        // gen — this is the authoritative source for live events.
                        Log.w(TAG, "SSE DIAG: gen mismatch gen=${envelope.generation} < subscribed=$subscribedGeneration, adopting service gen")
                        subscribedGeneration = envelope.generation
                    }
                    // Follow upward — update to newer generation
                    if (envelope.generation > subscribedGeneration) {
                        subscribedGeneration = envelope.generation
                    }
                    lastSseEventTime = System.currentTimeMillis()
                    handleEvent(envelope.event)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    Log.e(TAG, "SSE event stream error", e)
                    batchFlushJob?.cancel()
                    synchronized(pendingDeltas) { pendingDeltas.clear() }
                    repository.clearStreaming()
                    val s = com.opencode.remote.ui.strings.AppLocale.strings
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errStreamInterrupted.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
                }
            }
        }
    }

    // DIAGNOSTIC: Track event types
    private val eventCounts = mutableMapOf<String, Int>()

    private fun handleEvent(event: ServerEvent) {
        val props = event.payload.properties
        val currentSessionId = _uiState.value.sessionId

        // Filter: only process events for current session (or global events without sessionID)
        if (props.sessionID != null && props.sessionID != currentSessionId) return

        // DIAGNOSTIC: Count event types
        val type = event.payload.type
        eventCounts[type] = (eventCounts[type] ?: 0) + 1
        // Log every 10 events of any type
        if ((eventCounts.values.sum()) % 10 == 0) {
            Log.w(TAG, "SSE DIAG: total=${eventCounts.values.sum()} counts=${eventCounts.entries.sortedByDescending { it.value }.take(6).joinToString { "${it.key}=${it.value}" }}")
        }

        Log.d(TAG, "SSE event: ${event.payload.type} session=${props.sessionID?.take(8)} msg=${props.messageID?.take(8)}")
        lastSseEventTime = System.currentTimeMillis()

        when (event.payload.type) {
            // ── Streaming text delta (incremental) ──
            "message.part.delta" -> {
                ensurePermissionPoll()
                val chunk = props.delta
                if (chunk != null) {
                    Log.w(TAG, "SSE DIAG: delta field=${props.field} partID=${props.partID?.take(8)} chunkLen=${chunk.length} chunk='${chunk.take(30).replace("\n","\\n")}'")
                } else {
                    Log.w(TAG, "SSE DIAG: delta with NULL chunk! field=${props.field} partID=${props.partID}")
                }
                // Accumulate delta events for batch processing (16ms coalescing window)
                synchronized(pendingDeltas) {
                    pendingDeltas.add(event)
                }
                // Start batch flush if not already scheduled
                if (batchFlushJob?.isActive != true) {
                    batchFlushJob = viewModelScope.launch {
                        delay(16)  // Coalesce window: ~1 frame at 60fps
                        flushPendingDeltas()
                    }
                }
            }

            // ── Part state update (full text so far) ──
            "message.part.updated" -> {
                val part = props.part ?: return
                val partMessageId = part.messageID ?: return
                val pendingId = _uiState.value.pendingAssistantMessageId ?: return
                if (partMessageId != pendingId) return

                // Refresh todo list when todowrite tool completes
                if (part.tool == "todowrite" && part.state?.status == "completed") {
                    loadTodoList()
                }

                val segType = when (part.type) {
                    "reasoning" -> "thinking"
                    "text" -> "text"
                    "tool-invocation", "tool-call", "tool" -> "tool"
                    else -> return
                }

                // For tool parts: use tool name + state info if text is empty
                val partText = if (part.type == "tool" && part.text.isNullOrBlank()) {
                    ToolSummarizer.summarize(part)
                } else {
                    part.text ?: return
                }
                if (partText.isBlank()) return

                val callId = part.callID
                Log.d(TAG, "part.updated type=${part.type} -> segType=$segType callId=${callId?.take(8)} msg=${partMessageId.take(8)}")

                val segments = _uiState.value.streamingSegments
                val updated = putSegment(segments, segType, partText, id = callId)

                // Cache the confirmed type for future deltas on this partID
                partTypeMap[props.partID] = segType

                // Dedup: when "thinking" is confirmed, remove misclassified "text" segments before it
                val deduped = if (segType == "thinking") dedupMisclassifiedText(updated) else updated

                repository.setStreamingBlocks(deduped)
                _uiState.update {
                    it.copy(streaming = it.streaming.copy(isStreaming = true, isSending = false, streamingSegments = deduped))
                }
            }

            // ── Message created/updated ──
            "message.updated" -> {
                val info = props.info
                if (info != null) {
                    Log.w(TAG, "SSE DIAG: message.updated role=${info.role} id=${info.id?.take(8)} agent=${info.agent}")
                } else {
                    Log.w(TAG, "SSE DIAG: message.updated with NULL info!")
                }
                if (info == null) return
                if (info.role == "assistant") {
                    val currentPending = _uiState.value.pendingAssistantMessageId
                    if (currentPending == info.id) return
                    if (completedMessageIds.contains(info.id)) {
                        Log.d(TAG, "Skipping late message.updated for completed ${info.id?.take(8)}")
                        return
                    }
                    Log.d(TAG, "New assistant msg: ${info.id?.take(8)} agent=${info.agent} segs=${_uiState.value.streamingSegments.size}")
                    val agentName = info.agent ?: info.mode ?: _uiState.value.streamingAgent
                    repository.setStreamingPendingMsgId(info.id)
                    _uiState.update {
                        it.copy(
                            streaming = it.streaming.copy(
                                isSending = true,
                                streamingAgent = agentName,
                                pendingAssistantMessageId = info.id,
                                // DO NOT clear streamingSegments or change isStreaming
                            ),
                        )
                    }
                    // If this update carries token data, refresh context usage immediately.
                    // This helps when TUI operations cause the server to re-report tokens
                    // or when the user re-enters a session with streaming in progress.
                    if (info.tokens?.tokenTotal() != null && info.tokens.tokenTotal() > 0) {
                        updateContextUsage()
                    }
                } else {
                    // Non-assistant roles (e.g. user) — add to local list incrementally via SSE
                    val msgId = info.id ?: return
                    val currentMessages = _uiState.value.messages
                    // Skip if message already exists (by ID)
                    if (currentMessages.any { it.id == msgId }) return
                    // Also skip optimistic local messages (prefixed with "local_")
                    if (msgId.startsWith("local_")) return
                    Log.d(TAG, "Incremental SSE: adding ${info.role} message ${msgId.take(8)}")
                    // Fetch messages to replace the optimistic local placeholder with real data
                    viewModelScope.launch {
                        try {
                            val allMessages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = allMessages.applyMessageFilters(it.sessionMeta.revertMessageId))) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch messages after user message.updated", e)
                        }
                    }
                }
            }

            // ── Message completed ──
            "message.completed" -> {
                val msgId = props.messageID
                if (msgId != null) completedMessageIds.add(msgId)
                Log.d(TAG, "Message completed: ${msgId?.take(8)} segs=${_uiState.value.streamingSegments.size} streaming=${_uiState.value.isStreaming}")
                viewModelScope.launch { loadTodoList() }
            }

            // ── Session idle = PRIMARY completion signal ──
            "session.idle" -> {
                Log.w(TAG, "SSE DIAG: session.idle RECEIVED isStreaming=${_uiState.value.isStreaming} isSending=${_uiState.value.isSending}")
                val state = _uiState.value
                // Only finalize if we're streaming AND the AI has actually started responding
                // (pendingAssistantMessageId set by message.updated). Prevents premature clearing
                // from stale idle events that arrive before the AI begins generating.
                if ((state.isStreaming || state.isSending) && state.pendingAssistantMessageId != null) {
                    val msgId = state.pendingAssistantMessageId
                    if (msgId != null) completedMessageIds.add(msgId)
                    Log.d(TAG, "session.idle — turn complete, reloading messages")

                    // DO NOT clear streaming state yet — keep streaming segments visible
                    // until we've confirmed the replacement message is in the list.
                    // This prevents the "response disappears" flash.
                    viewModelScope.launch {
                        try {
                            // Load messages with retry: the server may not have fully
                            // persisted the assistant message when session.idle fires.
                            var freshMessages = repository.getMessages(state.sessionId, state.sessionDirectory)
                            val expectedId = state.pendingAssistantMessageId
                            if (expectedId != null) {
                                var attempts = 0
                                while (attempts < 3 && !freshMessages.any { it.id == expectedId }) {
                                    attempts++
                                    Log.d(TAG, "session.idle: assistant msg not found, retry $attempts/3")
                                    delay(300L * attempts)
                                    freshMessages = repository.getMessages(state.sessionId, state.sessionDirectory)
                                }
                                // Final check: does the assistant message have actual content?
                                val assistantMsg = freshMessages.find { it.id == expectedId }
                                val hasContent = assistantMsg != null && assistantMsg.parts.any { p ->
                                    p.type in listOf("text", "reasoning") && !p.text.isNullOrBlank()
                                }
                                if (!hasContent) {
                                    // Assistant message not found or has no text/reasoning content after retries.
                                    // Force-clear streaming state — keeping it visible with no SSE activity
                                    // would show a perpetual spinner in the UI.
                                    Log.w(TAG, "session.idle: assistant msg not found or no content after retries, force-clearing streaming")
                                    deltaLogCounter = 0
                                    partTypeMap.clear()
                                    batchFlushJob?.cancel()
                                    streamingWatchdogJob?.cancel()
                                    synchronized(pendingDeltas) { pendingDeltas.clear() }
                                    repository.clearStreaming()
                                    _uiState.update {
                                        it.copy(
                                            chatDisplay = it.chatDisplay.copy(
                                                messages = freshMessages.applyMessageFilters(it.sessionMeta.revertMessageId),
                                            ),
                                            streaming = it.streaming.copy(
                                                isStreaming = false,
                                                streamingSegments = emptyList(),
                                                streamingAgent = null,
                                                isSending = false,
                                                pendingAssistantMessageId = null,
                                            ),
                                        )
                                    }
                                    return@launch
                                }
                            }

                            // Content confirmed — now safe to clear everything atomically.
                            deltaLogCounter = 0
                            partTypeMap.clear()
                            batchFlushJob?.cancel()
                            streamingWatchdogJob?.cancel()
                            synchronized(pendingDeltas) { pendingDeltas.clear() }
                            repository.clearStreaming()
                            // 前台盯着看：service 层免打扰，这里补震动（回 completed 信号）
                            buzzOnce()

                            _uiState.update {
                                it.copy(
                                    chatDisplay = it.chatDisplay.copy(messages = freshMessages.applyMessageFilters(it.sessionMeta.revertMessageId)),
                                    streaming = it.streaming.copy(
                                        isStreaming = false,
                                        streamingSegments = emptyList(),
                                        streamingAgent = null,
                                        isSending = false,
                                        pendingAssistantMessageId = null,
                                    ),
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to reload messages after session.idle", e)
                            // On network error, still clear streaming to un-stuck the UI
                            deltaLogCounter = 0
                            partTypeMap.clear()
                            batchFlushJob?.cancel()
                            streamingWatchdogJob?.cancel()
                            synchronized(pendingDeltas) { pendingDeltas.clear() }
                            repository.clearStreaming()
                            _uiState.update {
                                it.copy(streaming = it.streaming.copy(
                                    isStreaming = false,
                                    streamingSegments = emptyList(),
                                    streamingAgent = null,
                                    isSending = false,
                                    pendingAssistantMessageId = null,
                                ))
                            }
                        }
                        loadTodoList()
                        updateContextUsage()
                    }
                }
                // Always reload session info on idle — TUI may have caused state changes
                // (undo/redo, compaction, etc.) that we need to reflect.
                loadSessionInfo()
                // If we weren't streaming (TUI drove the conversation), still refresh
                // messages + context since TUI activity changed the message list.
                if (!_uiState.value.isStreaming && !_uiState.value.isSending) {
                    viewModelScope.launch {
                        try {
                            val fresh = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = fresh.applyMessageFilters(it.sessionMeta.revertMessageId))) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to refresh messages on idle", e)
                        }
                        updateContextUsage()
                    }
                }
                // session.idle means the AI turn is complete — clear blocking state
                // ONLY when this idle corresponds to the current streaming turn.
                // Do NOT clear blocking state during reconnection (isStreaming=false, isSending=false)
                // because the session may still be waiting for question/permission answer.
                val blockedState = _uiState.value.chatDisplay
                if ((state.isStreaming || state.isSending) &&
                    (blockedState.pendingPermission != null || blockedState.pendingQuestion != null || blockedState.isBlocked)) {
                    Log.d(TAG, "session.idle — clearing blocking state after streaming turn completed")
                    clearBlockingState()
                    permissionQueue.clear()
                }
            }

            // ── Session compacted — context reset ──
            "session.compacted" -> {
                Log.d(TAG, "Session compacted — resetting context usage")
                viewModelScope.launch {
                    try {
                        val messages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = messages.applyMessageFilters(it.sessionMeta.revertMessageId))) }
                        updateContextUsage()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to reload messages after compaction", e)
                    }
                }
            }

            // ── Session events ──
            "session.updated" -> {
                loadSessionInfo()
            }

            // ── Session status (busy/idle transition) ──
            // NOTE: The server emits session.status { type: "idle" } AND session.idle
            // at the same time. We handle the completion logic ONLY in session.idle
            // to avoid duplicate message reloads racing against each other.
            // session.status is used only for metadata refresh.
            "session.status" -> {
                loadSessionInfo()
                ensurePermissionPoll()
            }

            // ── Todo events ──
            "todo.updated" -> {
                loadTodoList()
            }

            // ── Permission asked (tool confirmation) ──
            "permission.asked" -> {
                val requestId = props.id ?: return
                val permType = props.permission ?: return
                val sessionId = props.sessionID ?: currentSessionId
                Log.d(TAG, "Permission asked: id=$requestId perm=$permType patterns=${props.patterns}")
                val request = PermissionRequestData(
                    id = requestId,
                    sessionID = sessionId,
                    permission = permType,
                    patterns = props.patterns ?: emptyList(),
                    always = props.always ?: emptyList(),
                    tool = props.tool,
                )
                if (_uiState.value.pendingPermission != null) {
                    // Queue the request — current one is still being handled by user
                    permissionQueue.add(request)
                    Log.d(TAG, "Permission queued: ${request.id}, queue size: ${permissionQueue.size}")
                } else {
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        pendingPermission = request, isBlocked = true,
                    ))}
                    repository.saveBlockingState(sessionId, request, _uiState.value.pendingQuestion)
                    startBlockingWatchdog()
                }
            }

            // ── Question asked (AI question) ──
            "question.asked" -> {
                val requestId = props.id ?: return
                val questions = props.questions ?: return
                val sessionId = props.sessionID ?: currentSessionId
                if (questions.isEmpty()) return
                Log.d(TAG, "Question asked: id=$requestId questions=${questions.size}")
                val request = QuestionRequestData(
                    id = requestId,
                    sessionID = sessionId,
                    questions = questions,
                    tool = props.tool,
                )
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    pendingQuestion = request, isBlocked = true,
                ))}
                repository.saveBlockingState(currentSessionId, _uiState.value.pendingPermission, request)
                startBlockingWatchdog()
            }

            // ── Internal sync (ignore) ──
            "sync" -> { /* silently ignore */ }

            // ── Session created (log only — SessionsViewModel handles refresh) ──
            "session.created" -> {
                Log.d(TAG, "Session created: ${props.sessionID?.take(8)}")
            }

            // ── Session deleted (log only — SessionsViewModel handles refresh) ──
            "session.deleted" -> {
                Log.d(TAG, "Session deleted: ${props.sessionID?.take(8)}")
            }

            // ── Permission replied from TUI — clear pending permission ──
            "permission.replied" -> {
                Log.d(TAG, "Permission replied: id=${props.id?.take(8)} reply=${props.reply}")
                if (_uiState.value.pendingPermission != null) {
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        pendingPermission = null, isBlocked = _uiState.value.pendingQuestion != null,
                    ))}
                    if (_uiState.value.pendingQuestion == null) {
                        repository.clearBlockingState(_uiState.value.sessionId)
                    }
                }
            }

            // ── Question replied from TUI — clear pending question ──
            "question.replied" -> {
                Log.d(TAG, "Question replied: id=${props.id?.take(8)}")
                if (_uiState.value.pendingQuestion != null) {
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        pendingQuestion = null, isBlocked = _uiState.value.pendingPermission != null,
                    ))}
                    if (_uiState.value.pendingPermission == null) {
                        repository.clearBlockingState(_uiState.value.sessionId)
                    }
                }
            }

            // ── Question rejected from TUI — clear pending question ──
            "question.rejected" -> {
                Log.d(TAG, "Question rejected: id=${props.id?.take(8)}")
                if (_uiState.value.pendingQuestion != null) {
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        pendingQuestion = null, isBlocked = _uiState.value.pendingPermission != null,
                    ))}
                    if (_uiState.value.pendingPermission == null) {
                        repository.clearBlockingState(_uiState.value.sessionId)
                    }
                }
            }

            // ── Project updated (log only) ──
            "project.updated" -> {
                Log.d(TAG, "Project updated: name=${props.name} path=${props.path}")
            }

            // ── VCS branch updated (log only) ──
            "vcs.branch.updated" -> {
                Log.d(TAG, "VCS branch updated: ${props.previousBranch} -> ${props.branch}")
            }

            // ── Errors ──
            "session.error" -> {
                val errorMsg = props.error ?: com.opencode.remote.ui.strings.AppLocale.strings.errUnknown
                Log.e(TAG, "Session error: $errorMsg")
                batchFlushJob?.cancel()
                synchronized(pendingDeltas) { pendingDeltas.clear() }
                repository.clearStreaming()
                _uiState.update {
                    it.copy(
                        chatDisplay = it.chatDisplay.copy(error = errorMsg),
                        streaming = it.streaming.copy(isSending = false, isStreaming = false, streamingSegments = emptyList()),
                    )
                }
            }
        }
    }

    /** Remove "text" segments that appear before the first "thinking" segment.
     *  These were created by message.part.delta events with field=null that misclassified
     *  reasoning content as "text". Once message.part.updated confirms type="reasoning",
     *  those stale "text" segments should be removed to prevent duplicate display. */
    private fun dedupMisclassifiedText(segments: List<ResponseSegment>): List<ResponseSegment> {
        val firstThinkingIdx = segments.indexOfFirst { it.type == "thinking" }
        if (firstThinkingIdx <= 0) return segments  // No thinking segment, or thinking is already first
        // Remove "text" segments before the first "thinking" — they were misclassified reasoning deltas
        return segments.filterIndexed { idx, seg ->
            idx >= firstThinkingIdx || seg.type != "text"
        }
    }

    /** Append incremental chunk. If last segment has same type AND id, append to it. Otherwise create new segment.
     *  Truncates if accumulated text exceeds limit to prevent OOM. */
    private fun appendToLastSegment(segments: List<ResponseSegment>, type: String, chunk: String, id: String? = null): List<ResponseSegment> {
        if (segments.isNotEmpty() && segments.last().type == type && segments.last().id == id) {
            val combined = segments.last().text + chunk
            val truncated = if (combined.length > MAX_STREAMING_TEXT) {
                combined.substring(0, MAX_STREAMING_TEXT) + "\n\n… [truncated]"
            } else combined
            return segments.dropLast(1) + segments.last().copy(text = truncated)
        }
        // Also truncate if a single chunk exceeds limit (e.g. large tool output)
        val safeChunk = if (chunk.length > MAX_STREAMING_TEXT) {
            chunk.take(MAX_STREAMING_TEXT) + "\n\n… [truncated]"
        } else chunk
        return segments + ResponseSegment(type = type, text = safeChunk, isStreaming = true, id = id)
    }

    /** Flush all accumulated delta events in a single batch UI update. */
    private fun flushPendingDeltas() {
        val batch: List<ServerEvent>
        synchronized(pendingDeltas) {
            batch = pendingDeltas.toList()
            pendingDeltas.clear()
        }
        if (batch.isEmpty()) return

        // Process all accumulated deltas in a single UI update
        for (deltaEvent in batch) {
            val p = deltaEvent.payload.properties
            val chunk = p.delta ?: continue
            val cachedType = partTypeMap[p.partID]
            val segType = when {
                cachedType != null -> cachedType
                p.field == "reasoning" -> "thinking"
                else -> "text"
            }
            val callId = p.callID
            val segments = _uiState.value.streamingSegments
            val updated = appendToLastSegment(segments, segType, chunk, id = callId)
            repository.setStreamingBlocks(updated)
            deltaLogCounter++
            if (deltaLogCounter % 50 == 0) {
                Log.d(TAG, "delta #$deltaLogCounter field=${p.field} type=$segType segs=${updated.size}")
            }
            _uiState.update {
                it.copy(streaming = it.streaming.copy(isStreaming = true, isSending = false, streamingSegments = updated))
            }
        }
    }

    /** Update or create a segment with full text (from part.updated). Truncates if text exceeds limit.
     *  Matches by both type AND id to prevent tool calls from overwriting each other. */
    private fun putSegment(segments: List<ResponseSegment>, type: String, fullText: String, id: String? = null): List<ResponseSegment> {
        val truncatedText = if (fullText.length > MAX_STREAMING_TEXT) {
            fullText.take(MAX_STREAMING_TEXT) + "\n\n… [truncated]"
        } else fullText
        val lastIdx = segments.indexOfLast { it.type == type && it.id == id }
        return if (lastIdx >= 0) {
            segments.subList(0, lastIdx) + ResponseSegment(type, truncatedText, isStreaming = true, id = id) + segments.subList(lastIdx + 1, segments.size)
        } else {
            segments + ResponseSegment(type = type, text = truncatedText, isStreaming = true, id = id)
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(inputText = text)) }
    }

    // ── #14 输入历史（上下键翻，高频输入刚需；全局环形 50 条，落盘 DataStore） ──
    private val inputHistory = mutableListOf<String>()
    private var historyLoaded = false
    private var historyIndex = -1  // -1 = 当前草稿，否则为 history 下标
    private var historyDraft = ""

    private fun ensureHistoryLoaded() {
        if (historyLoaded) return
        historyLoaded = true
        viewModelScope.launch {
            try {
                val saved = connectionPreferences.getInputHistory()
                if (saved.isNotEmpty()) {
                    inputHistory.clear()
                    inputHistory.addAll(saved.take(50))
                }
            } catch (e: Exception) { Log.w(TAG, "load input history failed", e) }
        }
    }

    private fun pushInputHistory(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        ensureHistoryLoaded()
        if (inputHistory.lastOrNull() == t) { historyIndex = -1; return }
        inputHistory.add(t)
        while (inputHistory.size > 50) inputHistory.removeAt(0)
        historyIndex = -1
        viewModelScope.launch {
            try { connectionPreferences.saveInputHistory(inputHistory.toList()) }
            catch (e: Exception) { Log.w(TAG, "save input history failed", e) }
        }
    }

    /** 上一条（delta=-1）/下一条（delta=+1）；返回是否消费了按键。 */
    fun navigateHistory(delta: Int): Boolean {
        ensureHistoryLoaded()
        if (inputHistory.isEmpty()) return false
        if (delta < 0) {
            if (historyIndex == -1) {
                historyDraft = _uiState.value.inputText
                historyIndex = inputHistory.lastIndex
            } else if (historyIndex > 0) {
                historyIndex--
            } else return false
        } else {
            if (historyIndex == -1) return false
            if (historyIndex < inputHistory.lastIndex) {
                historyIndex++
            } else {
                historyIndex = -1
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(inputText = historyDraft)) }
                return true
            }
        }
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(inputText = inputHistory[historyIndex])) }
        return true
    }

    fun historyPrev(): Boolean = navigateHistory(-1)
    fun historyNext(): Boolean = navigateHistory(1)

    // ── 快捷模板 Tier 1（一键发常用语，不用打字；标题与内容可不同） ──
    val messageTemplates: StateFlow<List<TemplateEntry>> =
        connectionPreferences.customTemplates
            .map { DEFAULT_TEMPLATES + it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_TEMPLATES)

    /** 点 chip 即发内容：塞进输入框走正常发送链（历史/离线队列全吃上）。 */
    fun sendTemplate(entry: TemplateEntry) {
        if (entry.content.isBlank()) return
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(inputText = entry.content)) }
        sendMessage()
    }

    /** 把当前输入框内容存成模板（上限 10，去重）。 */
    /** 新建/更新模板（按标题去重，上限 10）。 */
    fun saveTemplate(title: String, content: String) {
        val t = title.trim()
        val c = content.trim()
        if (t.isEmpty() || c.isEmpty()) return
        viewModelScope.launch {
            try {
                val cur = connectionPreferences.customTemplates.first()
                val next = (cur.filter { it.title != t } + TemplateEntry(t, c)).takeLast(10)
                connectionPreferences.saveCustomTemplates(next)
                try { Toast.makeText(appContext, "已存模板", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            } catch (e: Exception) { Log.w(TAG, "save template failed", e) }
        }
    }

    /** 预设不可删/改：UI 长按编辑只挂自定义项 */
    fun isCustomTemplate(title: String): Boolean = DEFAULT_TEMPLATES.none { it.title == title }
    fun removeTemplate(title: String) {
        viewModelScope.launch {
            try {
                val cur = connectionPreferences.customTemplates.first()
                if (cur.any { it.title == title }) {
                    connectionPreferences.saveCustomTemplates(cur.filter { it.title != title })
                }
            } catch (e: Exception) { Log.w(TAG, "remove template failed", e) }
        }
    }

    // ── 附件（图片/文件）：先传后引 ──
    /** 系统 picker 回调：读字节→图片压到可发尺寸→上传→挂到待发送。 */
    fun attachPickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_uiState.value.sessionId.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isUploading = true)) }
            try {
                for (uri in uris) {
                    if (_uiState.value.attachedFiles.size >= 5) {
                        try { Toast.makeText(appContext, "一次最多 5 个附件", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        break
                    }
                    val (bytes, name) = loadUploadBytes(uri) ?: continue
                    if (bytes.size > 10_000_000) {
                        try { Toast.makeText(appContext, "文件太大（10MB 上限）：$name", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        continue
                    }
                    try {
                        val serverPath = repository.uploadAttachment(bytes, name, _uiState.value.sessionDirectory)
                        val cur = _uiState.value.attachedFiles + AttachedFile(serverPath, name)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(attachedFiles = cur)) }
                    } catch (e: Exception) {
                        Log.w(TAG, "upload failed: $name", e)
                        val s = com.opencode.remote.ui.strings.AppLocale.strings
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
                    }
                }
            } finally {
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isUploading = false)) }
            }
        }
    }

    fun removeAttachment(file: AttachedFile) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(attachedFiles = it.chatDisplay.attachedFiles - file)) }
    }

    /** 读 picker 内容：图片最长边超 2048 或超 4MB 则转 JPEG 压小。 */
    private fun loadUploadBytes(uri: Uri): Pair<ByteArray, String>? {
        return try {
            val cr = appContext.contentResolver
            var name = "file"
            try {
                cr.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { name = it }
                }
            } catch (_: Exception) {}
            var bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val mime = try { cr.getType(uri) } catch (_: Exception) { null } ?: ""
            if (mime.startsWith("image/")) {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val longest = maxOf(bounds.outWidth, bounds.outHeight)
                    var sample = 1
                    while (longest / sample > 2048) sample *= 2
                    if (sample > 1 || bytes.size > 4_000_000) {
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (bmp != null) {
                            val out = java.io.ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            bmp.recycle()
                            bytes = out.toByteArray()
                            name = name.substringBeforeLast('.', name) + ".jpg"
                        }
                    }
                } catch (_: Exception) {}
            }
            Pair(bytes, name)
        } catch (e: Exception) {
            Log.w(TAG, "read picked file failed", e)
            null
        }
    }

    // TTS read-aloud: speak text segments only.
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastUtteranceId = ""
    private var pendingSpeak: Pair<String, String>? = null
    private val _speakingId = MutableStateFlow<String?>(null)
    val speakingId: StateFlow<String?> = _speakingId.asStateFlow()

    /** Speaker button: tap again to stop; switching messages stops first. */
    fun toggleSpeak(messageId: String, text: String) {
        if (_speakingId.value == messageId) { stopSpeak(); return }
        val clean = TtsCleaner.clean(text)
        // 静默失败是最差体验：没声必须有字
        if (clean.isBlank()) {
            try { Toast.makeText(appContext, "这条没正文可念", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            return
        }
        // 媒体音量 0 = 天王老子也听不见，先吱一声
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            if (am != null && am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0) {
                try { Toast.makeText(appContext, "媒体音量为 0，先调大音量", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        stopSpeak()
        val engine = tts
        if (engine != null && ttsReady) {
            speakNow(messageId, clean, engine)
            return
        }
        if (engine == null) {
            pendingSpeak = messageId to clean
            _speakingId.value = messageId
            startTtsEngine(null, emptySet())
        }
    }

    /** 起引擎（preferEngine=null 即系统默认）；ColorOS 这类默认引擎残了就往下顺位。 */
    private fun startTtsEngine(preferEngine: String?, tried: Set<String>) {
        try {
            val listener = TextToSpeech.OnInitListener { status -> onTtsInit(status, preferEngine, tried) }
            tts = if (preferEngine == null) TextToSpeech(appContext, listener)
            else TextToSpeech(appContext, listener, preferEngine)
        } catch (e: Exception) {
            Log.w(TAG, "tts engine start failed ($preferEngine)", e)
            onTtsEngineFailed(preferEngine, tried)
        }
    }

    private fun onTtsInit(status: Int, engine: String?, tried: Set<String>) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) {
            Log.w(TAG, "tts init status=$status engine=$engine")
            onTtsEngineFailed(engine, tried)
            return
        }
        // 简中不行退繁中/通用中文（部分引擎只认 CHINESE）
        var r = tts?.setLanguage(java.util.Locale.SIMPLIFIED_CHINESE)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            r = tts?.setLanguage(java.util.Locale.CHINESE)
        }
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            try { Toast.makeText(appContext, "缺中文语音包，去系统设置下载", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            ttsReady = false
            stopSpeak()
            return
        }
        val eng = tts
        val pend = pendingSpeak
        pendingSpeak = null
        if (eng != null && pend != null) speakNow(pend.first, pend.second, eng)
    }

    /** 默认引擎残了：枚举机上引擎顺位重试；一个没有就指路去装。 */
    private fun onTtsEngineFailed(failedEngine: String?, tried: Set<String>) {
        val done = tried + setOfNotNull(failedEngine)
        val alts: List<Pair<String, String>> = try {
            tts?.engines?.map { it.name to it.label }?.filter { it.first !in done } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
        if (alts.isEmpty()) {
            if (failedEngine == null) {
                try { Toast.makeText(appContext, "没装TTS语音引擎，去应用市场装一个（如 谷歌TTS / 讯飞语记）", Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            } else {
                try { Toast.makeText(appContext, "语音引擎启动失败", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            }
            stopSpeak()
            return
        }
        val (pkg, label) = alts.first()
        try { Toast.makeText(appContext, "默认语音引擎不可用，切到${label}", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
        // stopSpeak 会清 pending：先存后恢复，朗读意图不断
        val keep = pendingSpeak
        val keepId = _speakingId.value
        stopSpeak()
        pendingSpeak = keep
        _speakingId.value = keepId
        startTtsEngine(pkg, done)
    }

    private fun speakNow(messageId: String, clean: String, engine: TextToSpeech) {
        try {
            utteranceStarted = false
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) { utteranceStarted = true }
                override fun onDone(utteranceId: String) {
                    if (utteranceId == lastUtteranceId) _speakingId.value = null
                }
                override fun onError(utteranceId: String) {
                    Log.w(TAG, "tts utterance error: $utteranceId")
                    try {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try { Toast.makeText(appContext, "朗读失败", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                    stopSpeak()
                }
            })
            val chunks = TtsCleaner.chunk(clean)
            lastUtteranceId = "oc${chunks.size - 1}"
            // speak() 返回码必须查：ERROR 时无异常无回调，静默哑火
            var rcOk = true
            chunks.forEachIndexed { i, c ->
                val rc = engine.speak(c, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "oc$i")
                if (rc == TextToSpeech.ERROR) rcOk = false
            }
            if (!rcOk) {
                Log.w(TAG, "tts speak returned ERROR")
                try { Toast.makeText(appContext, "朗读失败（引擎拒收）", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                stopSpeak()
                return
            }
            _speakingId.value = messageId
            // 看门狗：8 秒没 onStart = 引擎僵死（init 回调丢了之类），有字为证再停
            watchTtsStart(messageId)
        } catch (e: Exception) {
            Log.w(TAG, "tts speak failed", e)
            try { Toast.makeText(appContext, "朗读失败", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            stopSpeak()
        }
    }

    private var utteranceStarted = false

    private fun watchTtsStart(messageId: String) {
        viewModelScope.launch {
            kotlinx.coroutines.delay(8000)
            if (_speakingId.value == messageId && !utteranceStarted) {
                Log.w(TAG, "tts watchdog: no onStart in 8s")
                try { Toast.makeText(appContext, "语音引擎无响应", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                stopSpeak()
            }
        }
    }

    private fun stopSpeak() {
        try { tts?.stop() } catch (_: Exception) {}
        pendingSpeak = null
        _speakingId.value = null
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isEmpty()) return
        // 空会话 id 直接拦：否则 POST 到垃圾 URL（404 被静默吞掉）+ 永远等不到事件
        if (_uiState.value.sessionId.isBlank()) {
            val s0 = com.opencode.remote.ui.strings.AppLocale.strings
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s0.errSendFailed.replace("%s", "empty session id"))) }
            return
        }
        // Allow sending during recoveryPending — user is resuming an interrupted conversation
        if (_uiState.value.isBlocked && !_uiState.value.recoveryPending) return
        // P2 离线队列：无网时存草稿（带当前选定），重连自动发出
        if (!repository.isOnline()) {
            // 附件走离线队列会塞爆 DataStore：无网时有附件直接拦下保留
            if (_uiState.value.attachedFiles.isNotEmpty()) {
                try { Toast.makeText(appContext, "附件需联网发送，已保留", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                return
            }
            val sel = _uiState.value.selection.committed
            viewModelScope.launch {
                try {
                    repository.enqueueOffline(
                        OfflineQueuedMessage(
                            sessionId = _uiState.value.sessionId,
                            text = text,
                            agent = sel.agent,
                            providerId = sel.model?.providerId,
                            modelId = sel.model?.modelId,
                            variant = sel.variant,
                            ts = System.currentTimeMillis(),
                        )
                    )
                } catch (e: Exception) { Log.w(TAG, "enqueue failed", e) }
            }
            try { Toast.makeText(appContext, "网络断开，已存草稿，重连自动发送", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(inputText = "")) }
            return
        }
        pushInputHistory(text)

        // 随本轮发出的附件（uri 为服务端绝对路径，转 file:// 正斜杠引用）
        val files = _uiState.value.attachedFiles

        val state = _uiState.value

        // When user hasn't explicitly picked an agent, send null to let the server
        // use its configured default (from opencode.json). DO NOT try to guess via
        // mode=="primary" — only show main agents (mode != subagent && !hidden),
        // matching TUI tab-switching behavior.
        // 展示名→id 归一：UI 态存展示名（Build），服务端只认 id（build），这里是发送边界
        val agentName = canonicalAgentId(_uiState.value.selectedAgent)

        // If stuck in stale streaming state (e.g. app killed during generation,
        // missed session.idle), abort the server-side generation and clear local state
        // before sending a new message. The server coalesces concurrent prompt_async
        // calls, so a stale session would swallow our new prompt without feedback.
        if (state.isStreaming || state.isSending) {
            Log.w(TAG, "sendMessage() while streaming — aborting stale state before new send")
            viewModelScope.launch {
                try { repository.abortSession(state.sessionId, state.sessionDirectory) } catch (_: Exception) {}
            }
            batchFlushJob?.cancel()
            synchronized(pendingDeltas) { pendingDeltas.clear() }
            repository.clearStreaming()
            streamingWatchdogJob?.cancel()
        }

        // Clear completed IDs from previous turn — new conversation turn starting
        completedMessageIds.clear()
        partTypeMap.clear()
        deltaLogCounter = 0
        // Clear recovery state if present — user is sending a new message to resume
        if (_uiState.value.recoveryPending) {
            blockingWatchdogJob?.cancel()
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                isBlocked = false, recoveryPending = false,
            ))}
        }

        // 1. Immediately add user message to local list (optimistic update)
        val localUserMsg = MessageInfo(
            info = MessageInfoData(
                id = "local_${System.currentTimeMillis()}",
                role = "user",
            ),
            parts = listOf(MessagePart(type = "text", text = text)) +
                files.map { MessagePart(type = "file", text = it.name, name = it.name) },
        )
        _uiState.update {
            it.copy(
                chatDisplay = it.chatDisplay.copy(
                    inputText = "",
                    attachedFiles = emptyList(),
                    messages = it.messages + localUserMsg,
                    error = null,
                ),
                streaming = it.streaming.copy(
                    isSending = true,
                    isStreaming = false,
                    streamingSegments = emptyList(),
                    streamingAgent = agentName,
                    pendingAssistantMessageId = null,
                ),
            )
        }

        // 2. Fire-and-forget: send async, don't block UI
        viewModelScope.launch {
            try {
                repository.beginStreaming(_uiState.value.sessionId, agentName)
                val committed = _uiState.value.selection.committed
                val providerId = committed.model?.providerId ?: _uiState.value.selectedModel?.providerID
                val modelId = committed.model?.modelId ?: _uiState.value.selectedModel?.id
                val variant = committed.variant
                repository.sendMessage(
                    _uiState.value.sessionId, text, agentName,
                    providerId, modelId, variant,
                    _uiState.value.sessionDirectory,
                    files = files.map { V2FileAttachment(uri = "file://" + it.uri.replace('\\', '/'), name = it.name) }.ifEmpty { null },
                )
                // prompt_async returns 204 immediately — SSE events drive the rest
                // 轮询定时器同步起跑：SSE 正常时它是冗余，流死时它是唯一活路
                ensurePermissionPoll()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                batchFlushJob?.cancel()
                synchronized(pendingDeltas) { pendingDeltas.clear() }
                repository.clearStreaming()
                streamingWatchdogJob?.cancel()
                // 电梯/断流：发送中途断网 → 自动存离线草稿，重连补发（消息不丢）
                val msg = (e.localizedMessage ?: e.javaClass.simpleName).orEmpty()
                val netHint = msg.contains("Unable to resolve host", true) ||
                    msg.contains("failed to connect", true) ||
                    msg.contains("Software caused connection abort", true) ||
                    e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    e is java.net.SocketTimeoutException ||
                    e is java.io.IOException && msg.contains("timeout", true)
                if (netHint) {
                    try {
                        val sel = _uiState.value.selection.committed
                        repository.enqueueOffline(
                            OfflineQueuedMessage(
                                sessionId = _uiState.value.sessionId,
                                text = text,
                                agent = sel.agent ?: agentName,
                                providerId = sel.model?.providerId,
                                modelId = sel.model?.modelId,
                                variant = sel.variant,
                                ts = System.currentTimeMillis(),
                            )
                        )
                        try { Toast.makeText(appContext, "发送失败，已存离线草稿，重连自动发送", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                    } catch (_: Exception) {}
                }
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update {
                    it.copy(
                        streaming = it.streaming.copy(isSending = false, isStreaming = false),
                        chatDisplay = it.chatDisplay.copy(error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName)),
                    )
                }
            }
        }

        // Start timeout watchdog — if no SSE events arrive within 120s, assume
        // the server is stuck (missed session.idle) and force-clear streaming state.
        startStreamingWatchdog()
    }

    // ─── Session Enhancement Pack ───

    /** 压缩上下文：服务端总结后重载消息 */
    fun compactSession() {
        val sid = _uiState.value.sessionId
        if (sid.isBlank()) return
        viewModelScope.launch {
            try {
                repository.compactSession(sid)
                val fresh = repository.getMessages(sid, _uiState.value.sessionDirectory)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = fresh.applyMessageFilters(it.sessionMeta.revertMessageId))) }
                updateContextUsage()
                try { android.widget.Toast.makeText(appContext, "已压缩", android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.e(TAG, "compact failed", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
            }
        }
    }

    /** 用服务端上下文窗口用量刷新显示（失败沿用本地启发值）。 */
    fun refreshContextUsage() {
        val sid = _uiState.value.sessionId
        if (sid.isBlank()) return
        viewModelScope.launch {
            try {
                val ctxMsgs = repository.getSessionContext(sid)
                val total = ctxMsgs.sumOf { m ->
                    (m.info.tokens?.tokenTotal() ?: 0) +
                        m.parts.sumOf { it.tokens?.tokenTotal() ?: 0 }
                }
                if (total > 0) {
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(contextUsageK = "${total / 1000}K")) }
                }
            } catch (e: Exception) { Log.w(TAG, "server context usage failed, keep local", e) }
        }
    }

    /** 横条“重试”：不断服务器列表，当前会话原地重连（心跳恢复 SSE + 补发草稿）。 */
    fun retryConnection() {
        val sid = _uiState.value.sessionId
        val dir = _uiState.value.sessionDirectory
        if (sid.isBlank()) return
        viewModelScope.launch {
            try {
                val ok = try { repository.testConnection() } catch (_: Exception) { false }
                if (ok) {
                    try {
                        val sent = repository.flushOutbox()
                        if (sent > 0) {
                            try { Toast.makeText(appContext, "已重连，发出${sent}条离线草稿", Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(serverUnreachable = false, error = null)) }
                    subscribeToEvents()
                    initialize(sid, dir)
                } else {
                    val s = com.opencode.remote.ui.strings.AppLocale.strings
                    val detail = try { repository.getLastTestError() } catch (_: Exception) { null }
                    _uiState.update {
                        it.copy(chatDisplay = it.chatDisplay.copy(
                            error = s.errSendFailed.replace("%s", detail ?: "server unreachable"),
                        ))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "retryConnection failed", e)
            }
        }
    }

    fun openDiffDialog() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showDiffDialog = true, isLoadingDiff = true, diffFiles = emptyList())) }
        val sid = _uiState.value.sessionId
        if (sid.isBlank()) return
        viewModelScope.launch {
            try {
                val files = repository.getSessionDiff(sid)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(diffFiles = files, isLoadingDiff = false)) }
            } catch (e: Exception) {
                Log.e(TAG, "load diff failed", e)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingDiff = false)) }
            }
        }
    }

    fun closeDiffDialog() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showDiffDialog = false)) }
    }

    /** shell 直调：跳过 AI，直接跑命令取输出（无 token 消耗）。 */
    fun openShellDialog() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showShellDialog = true)) }
    }

    fun closeShellDialog() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showShellDialog = false)) }
    }

    /** 文件点击分发：图片应用内看，PDF 应用内渲染，其余下载走系统应用。 */
    fun openMedia(path: String, displayName: String) {
        val sid = _uiState.value.sessionId
        val dir = _uiState.value.sessionDirectory
        when {
            com.opencode.remote.data.api.FileMediaTypes.isImage(displayName) -> {
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    showImageDialog = true, isLoadingImage = true, imageBytes = null, imageName = displayName,
                ))}
                viewModelScope.launch {
                    try {
                        val bytes = repository.readFileBytes(path, dir)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(imageBytes = bytes, isLoadingImage = false)) }
                    } catch (e: Exception) {
                        Log.e(TAG, "load image failed", e)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingImage = false)) }
                        toast("图片加载失败：" + (e.localizedMessage ?: e.javaClass.simpleName))
                    }
                }
            }
            com.opencode.remote.data.api.FileMediaTypes.isPdf(displayName) -> {
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    showPdfDialog = true, isLoadingPdf = true, pdfFile = null, pdfName = displayName,
                ))}
                viewModelScope.launch {
                    try {
                        val bytes = repository.readFileBytes(path, dir)
                        val f = java.io.File(appContext.cacheDir, "shared/pdf_" + System.currentTimeMillis() + ".pdf")
                        f.parentFile?.mkdirs()
                        f.writeBytes(bytes)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(pdfFile = f, isLoadingPdf = false)) }
                    } catch (e: Exception) {
                        Log.e(TAG, "load pdf failed", e)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingPdf = false)) }
                        toast("PDF 加载失败：" + (e.localizedMessage ?: e.javaClass.simpleName))
                    }
                }
            }
            else -> {
                viewModelScope.launch {
                    val msg = try {
                        repository.openWithSystem(path, dir)
                    } catch (e: Exception) {
                        "打开失败：" + (e.localizedMessage ?: e.javaClass.simpleName)
                    }
                    toast(msg)
                }
            }
        }
    }

    fun closeImageDialog() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showImageDialog = false, imageBytes = null)) }
    }

    fun closePdfDialog() {
        val f = _uiState.value.chatDisplay.pdfFile
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showPdfDialog = false, pdfFile = null)) }
        try { f?.delete() } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        try { android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    fun onShellInput(text: String) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(shellCommand = text)) }
    }

    fun runShell() {
        val sid = _uiState.value.sessionId
        val cmd = _uiState.value.chatDisplay.shellCommand.trim()
        if (sid.isBlank() || cmd.isEmpty()) return
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isRunningShell = true, shellOutput = "")) }
        viewModelScope.launch {
            try {
                val r = repository.runShell(sid, cmd)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    shellOutput = r.output.ifBlank { "(no output)" } +
                        (if ((r.exit ?: 0) != 0) "\n[exit ${r.exit}]" else "") +
                        (if (r.truncated) "\n[truncated]" else ""),
                    isRunningShell = false,
                ))}
            } catch (e: Exception) {
                Log.e(TAG, "runShell failed", e)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    shellOutput = "Error: ${e.localizedMessage ?: e.javaClass.simpleName}",
                    isRunningShell = false,
                ))}
            }
        }
    }

    /** agent 详情弹窗（description/mode/默认模型）。 */
    fun openAgentDetail() {
        val name = _uiState.value.selection.draft.agent
            ?: _uiState.value.selection.committed.agent
            ?: _uiState.value.selection.resolvedDefaultAgent
            ?: return
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showAgentDetail = true, isLoadingAgentDetail = true, agentDetail = null)) }
        viewModelScope.launch {
            try {
                val detail = repository.getAgentDetail(name, _uiState.value.sessionDirectory)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(agentDetail = detail, isLoadingAgentDetail = false)) }
            } catch (e: Exception) {
                Log.w(TAG, "agent detail failed", e)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingAgentDetail = false)) }
            }
        }
    }

    fun closeAgentDetail() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showAgentDetail = false)) }
    }

    /** vcs 状态（慢接口，后台刷；面板打开/切目录时各刷一次）。 */
    fun loadVcsStatus() {
        val dir = _uiState.value.sessionDirectory ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingVcs = true)) }
            try {
                val files = repository.getVcsStatus(dir)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(vcsFiles = files, isLoadingVcs = false)) }
            } catch (e: Exception) {
                Log.w(TAG, "vcs status failed", e)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingVcs = false)) }
            }
        }
    }

    fun openVcsDialog() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showVcsDialog = true)) }
        if (_uiState.value.chatDisplay.vcsFiles.isEmpty() && !_uiState.value.chatDisplay.isLoadingVcs) loadVcsStatus()
    }

    fun closeVcsDialog() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showVcsDialog = false)) }
    }

    fun abortSession() {        viewModelScope.launch {
            try {
                repository.abortSession(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                batchFlushJob?.cancel()
                synchronized(pendingDeltas) { pendingDeltas.clear() }
                repository.clearStreaming()
                _uiState.update {
                    it.copy(streaming = it.streaming.copy(isSending = false, isStreaming = false, streamingAgent = null, streamingSegments = emptyList()))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to abort session", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errAbortFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
            }
        }
    }

    /**
     * Undo the last user message.
     * 1. Abort any in-progress generation
     * 2. Find last user message
     * 3. Call POST /session/{id}/revert with that messageID
     * 4. Reload messages (server soft-hides reverted messages)
     * 5. Restore the undone text to the input box
     */
    fun undoLastMessage() {
        viewModelScope.launch {
            try {
                val state = _uiState.value

                // 1. Abort if streaming
                if (state.isStreaming || state.isSending) {
                    try { repository.abortSession(state.sessionId, state.sessionDirectory) } catch (_: Exception) {}
                    batchFlushJob?.cancel()
                    synchronized(pendingDeltas) { pendingDeltas.clear() }
                    repository.clearStreaming()
                    _uiState.update {
                        it.copy(streaming = it.streaming.copy(isSending = false, isStreaming = false, streamingAgent = null, streamingSegments = emptyList()))
                    }
                }

                // 2. Find last user message (filter out already-reverted ones)
                val revertId = state.revertMessageId
                val messages = state.messages
                val lastUserMsg = messages
                    .filter { it.role == "user" }
                    .filter { revertId == null || it.id < revertId }
                    .lastOrNull()
                if (lastUserMsg == null) {
                    Log.w(TAG, "No user message to undo")
                    return@launch
                }

                // 3. Call revert
                val updatedSession = repository.revertSession(state.sessionId, lastUserMsg.id, state.sessionDirectory)

                // 4. Extract the user's text to restore into input
                val userText = lastUserMsg.parts
                    .filter { it.type == "text" }
                    .mapNotNull { it.text }
                    .joinToString("\n")
                    .ifBlank { lastUserMsg.parts.firstOrNull()?.text ?: "" }

                // 5. Reload messages (server filters by revert marker)
                val freshMessages = repository.getMessages(state.sessionId, state.sessionDirectory)

                _uiState.update {
                    it.copy(
                        sessionMeta = it.sessionMeta.copy(
                            revertMessageId = updatedSession.revert?.messageID,
                        ),
                        chatDisplay = it.chatDisplay.copy(
                            messages = freshMessages.filterReverted(updatedSession.revert?.messageID).trimToLatest(),
                            inputText = userText,
                        ),
                    )
                }
                Log.d(TAG, "Undo: reverted message ${lastUserMsg.id.take(8)}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to undo message", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errUndoFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
            }
        }
    }

    /**
     * Redo — restore all reverted messages.
     * Calls POST /session/{id}/unrevert and reloads.
     */
    fun redoLastUndo() {
        viewModelScope.launch {
            try {
                val state = _uiState.value
                if (state.revertMessageId == null) return@launch

                val updatedSession = repository.unrevertSession(state.sessionId, state.sessionDirectory)
                val freshMessages = repository.getMessages(state.sessionId, state.sessionDirectory)

                _uiState.update {
                    it.copy(
                        sessionMeta = it.sessionMeta.copy(
                            revertMessageId = updatedSession.revert?.messageID,
                        ),
                        chatDisplay = it.chatDisplay.copy(
                            messages = freshMessages.filterReverted(updatedSession.revert?.messageID).trimToLatest(),
                            inputText = "",
                        ),
                    )
                }
                Log.d(TAG, "Redo: restored reverted messages")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to redo", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = s.errRedoFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName))) }
            }
        }
    }

    fun toggleTodoPanel() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showTodoPanel = !it.chatDisplay.showTodoPanel)) }
        // 右侧面板互斥：待办和文件面板同宽同边，同时开会叠出幽灵行
        if (_uiState.value.showTodoPanel) {
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isPanelOpen = false)) }
        }
    }

    // ── Panel Methods ──────────────────────────────────────────────────

    fun togglePanel() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isPanelOpen = !it.chatDisplay.isPanelOpen)) }
        // 右侧面板互斥：开文件面板时关待办面板
        if (_uiState.value.isPanelOpen) {
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showTodoPanel = false)) }
        }
        if (!_uiState.value.isPanelOpen) return
        if (_uiState.value.panelFiles.isEmpty()) {
            navigateToDirectory(".")
        }
        // 面板打开顺带刷 vcs（一目录一次，65s 超时，后台跑）
        loadVcsStatus()
    }

    fun setPanelOpen(open: Boolean) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isPanelOpen = open)) }
        if (open) {
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(showTodoPanel = false)) }
            if (_uiState.value.panelFiles.isEmpty()) {
                navigateToDirectory(".")
            }
            loadVcsStatus()
            if (_uiState.value.availableModels.isEmpty()) {
                loadModels()
            }
        }
    }

    fun navigateToDirectory(path: String) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingFiles = true, currentFilePath = path)) }
        viewModelScope.launch {
            try {
                val files = repository.listFiles(path, _uiState.value.sessionDirectory)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(panelFiles = files, isLoadingFiles = false)) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to list files", e)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingFiles = false)) }
            }
        }
    }

    fun navigateUp() {
        val current = _uiState.value.currentFilePath
        if (current == "." || current.isEmpty()) return
        // 服务端回反斜杠路径，按两种分隔符取父级（否则 02.写作\子目录 永远上不去）
        val parent = current.replace('\\', '/').substringBeforeLast("/", ".")
        navigateToDirectory(parent)
    }

    fun toggleFilePreview(file: FileNode) {
        val current = _uiState.value.expandedFilePath
        if (current == file.path) {
            // Collapse
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                expandedFilePath = null,
                expandedFileContent = null,
            )) }
        } else {
            // Expand — load content
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                expandedFilePath = file.path,
                expandedFileContent = null,
                isLoadingFileContent = true,
            )) }
            viewModelScope.launch {
                try {
                    val result = repository.readFileContent(file.path, _uiState.value.sessionDirectory)
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        expandedFileContent = result.content,
                        isLoadingFileContent = false,
                    )) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read file: ${file.path}", e)
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        expandedFileContent = "Error: ${e.message}",
                        isLoadingFileContent = false,
                    )) }
                }
            }
        }
    }

    fun refreshFiles() {
        navigateToDirectory(_uiState.value.currentFilePath)
    }

    // ── Model Methods ──────────────────────────────────────────────────

    fun loadProviders() {
        if (_uiState.value.isLoadingModels) return  // already loading
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(isLoadingModels = true)) }
        viewModelScope.launch {
            try {
                val providers = repository.listProviders()
                val connected = providers.connected
                val options = buildModelOptions(providers.providers, connected)
                // Also keep legacy availableModels populated for backward compat
                val legacyModels = repository.getCachedModels()
                val currentAgents = _uiState.value.availableAgents
                val currentSelection = _uiState.value.selection
                val normalized = normalizeSelectionState(
                    currentSelection.copy(availableModels = options, availableAgents = currentAgents)
                )
                _uiState.update {
                    it.copy(chatDisplay = it.chatDisplay.copy(
                        selection = normalized,
                        availableModels = legacyModels,
                        availableModelsError = false,
                        isLoadingModels = false,
                    ))
                }
                // Restore saved selection preferences now that models + agents are loaded
                restoreSelection()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load providers", e)
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    isLoadingModels = false,
                    availableModelsError = true,
                )) }
            }
        }
    }

    fun loadModels() = loadProviders()

    fun selectModel(model: ModelInfo) {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedModel = model)) }
    }

    private fun buildModelOptions(
        providers: List<ProviderInfo>,
        connectedIds: List<String> = emptyList(),
    ): List<ModelSelectionOption> {
        val filtered = if (connectedIds.isNotEmpty()) {
            providers.filter { it.id in connectedIds }
        } else {
            providers
        }
        return filtered.flatMap { provider ->
            provider.models.map { (modelId, model) ->
                ModelSelectionOption(
                    ref = ModelSelectionRef(provider.id, model.id.ifBlank { modelId }),
                    providerName = provider.name ?: provider.id,
                    modelName = model.name ?: model.id.ifBlank { modelId },
                    variants = model.variants.keys.sorted(),
                )
            }
        }.sortedBy { it.displayLabel }
    }

    private fun normalizeSelectionState(state: ChatSelectionUiState): ChatSelectionUiState {
        val committed = normalizeSelectionConfig(state.committed, state)
        val draftBase = state.copy(committed = committed)
        val draft = normalizeSelectionConfig(state.draft, draftBase)
        return state.copy(committed = committed, draft = draft)
    }

    fun syncModelWithAgent(agentName: String?) {
        if (agentName == null) {
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedModel = null)) }
            return
        }
        val agents = repository.getCachedAgents()
        val agent = agents.find { it.name == agentName }
        val agentModel = agent?.model
        if (agentModel != null) {
            val matchedModel = _uiState.value.availableModels.find {
                it.id == agentModel.modelID
            }
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(selectedModel = matchedModel)) }
        }
    }

    fun updateContextUsage() {
        // Walk backwards to find the last assistant message WITH actual token data.
        // This handles edge cases where the most recent assistant message has no tokens
        // (e.g., TUI triggered operations, provider didn't report usage, etc.)
        val messages = _uiState.value.messages
        val lastAssistant = messages.lastOrNull { it.role == "assistant" }
        val tokenCount = lastAssistant?.let { msg ->
            val infoTotal = msg.info.tokens?.tokenTotal() ?: 0
            if (infoTotal > 0) infoTotal
            else msg.parts.sumOf { it.tokens?.tokenTotal() ?: 0 }
        } ?: 0

        // If the last assistant has zero tokens, walk backwards to find one with data
        val resolvedCount = if (tokenCount > 0) tokenCount else {
            var found = 0
            for (msg in messages.reversed()) {
                if (msg.role != "assistant") continue
                val total = msg.info.tokens?.tokenTotal() ?: 0
                val partsTotal = if (total > 0) total else msg.parts.sumOf { it.tokens?.tokenTotal() ?: 0 }
                if (partsTotal > 0) {
                    found = partsTotal
                    break
                }
            }
            found
        }

        val usageK = if (resolvedCount > 0) "${resolvedCount / 1000}K" else "0K"
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(contextUsageK = usageK)) }
    }

    /**
     * 展示名→id 归一：对话框/记忆里存的是展示名（Build），服务端只认 id（build）。
     * 名==id 的 agent 碰巧能过，大小写一差就 400。只在发送/建会话边界调用，UI 态保持展示名。
     */
    private fun canonicalAgentId(ref: String?): String? {
        if (ref.isNullOrBlank()) return ref
        val agents = _uiState.value.availableAgents + repository.getCachedAgents()
        agents.find { it.id == ref }?.let { return it.id }
        agents.find { it.name == ref }?.let { return it.id }
        return ref
    }

    fun selectAgent(agentName: String?) {
        _uiState.update {
            val newConfig = it.selection.committed.copy(agent = agentName)
            it.copy(chatDisplay = it.chatDisplay.copy(
                selectedAgent = agentName,
                selection = it.chatDisplay.selection.copy(
                    committed = newConfig,
                    draft = newConfig,
                ),
            ))
        }
        syncModelWithAgent(agentName)
    }

    // ── Selection Dialog Methods ─────────────────────────────────────

    fun openSelectionDialog() {
        val committed = _uiState.value.selection.committed
        _uiState.update {
            it.copy(chatDisplay = it.chatDisplay.copy(
                selection = it.chatDisplay.selection.copy(
                    isDialogOpen = true,
                    draft = committed,
                ),
            ))
        }
        // 开窗即刷新服务端默认值（初始化时的异步加载可能还没回来，先看到 auto）
        loadServerDefaults()
    }

    fun dismissSelectionDialog() {
        val committed = _uiState.value.selection.committed
        _uiState.update {
            it.copy(chatDisplay = it.chatDisplay.copy(
                selection = it.chatDisplay.selection.copy(
                    isDialogOpen = false,
                    draft = committed,
                ),
            ))
        }
    }

    fun confirmSelectionDialog() {
        val draft = _uiState.value.selection.draft
        _uiState.update {
            it.copy(chatDisplay = it.chatDisplay.copy(
                selection = it.chatDisplay.selection.copy(
                    isDialogOpen = false,
                    committed = draft,
                ),
            ))
        }
        // Update backward-compat fields
        syncSelectionToLegacyFields(draft)
        // Persist selection
        viewModelScope.launch { persistSelection(draft) }
    }

    fun updateDraftAgent(agent: String?) {
        _uiState.update {
            val newDraft = it.selection.draft.copy(agent = agent)
            it.copy(chatDisplay = it.chatDisplay.copy(
                selection = normalizeSelectionState(it.selection.copy(draft = newDraft)),
            ))
        }
    }

    fun updateDraftModel(model: ModelSelectionRef?) {
        _uiState.update {
            val newDraft = it.selection.draft.copy(model = model, variant = null)
            it.copy(chatDisplay = it.chatDisplay.copy(
                selection = normalizeSelectionState(it.selection.copy(draft = newDraft)),
            ))
        }
    }

    fun updateDraftVariant(variant: String?) {
        _uiState.update {
            val newDraft = it.selection.draft.copy(variant = variant)
            it.copy(chatDisplay = it.chatDisplay.copy(
                selection = it.selection.copy(draft = newDraft),
            ))
        }
    }

    /** Sync selection state to legacy fields for backward compat during migration. */
    private fun syncSelectionToLegacyFields(config: ChatSelectionConfig) {
        val selectedModelInfo = config.model?.let { ref ->
            ModelInfo(id = ref.modelId, name = ref.modelId, providerID = ref.providerId)
        }
        _uiState.update {
            it.copy(chatDisplay = it.chatDisplay.copy(
                selectedAgent = config.agent,
                selectedModel = selectedModelInfo,
            ))
        }
    }

    // ── Selection Persistence ─────────────────────────────────────

    private suspend fun persistSelection(config: ChatSelectionConfig) {
        val sessionId = _uiState.value.sessionId
        if (sessionId.isBlank()) return
        try {
            connectionPreferences.saveSelectedAgent(sessionId, config.agent)
            if (config.model != null) {
                connectionPreferences.saveSelectedModel(sessionId, StoredModelSelection(config.model.providerId, config.model.modelId))
            } else {
                connectionPreferences.saveSelectedModel(sessionId, null)
            }
            connectionPreferences.saveSelectedVariant(sessionId, config.variant)
            // 全局也存一份：新会话无专属偏好时继承，不用每次重选
            connectionPreferences.saveLastAgent(config.agent)
            if (config.model != null) {
                connectionPreferences.saveLastModel(StoredModelSelection(config.model.providerId, config.model.modelId))
            } else {
                connectionPreferences.saveLastModel(null)
            }
            connectionPreferences.saveLastVariant(config.variant)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist selection", e)
        }
    }

    private suspend fun restoreSelection() {
        val sessionId = _uiState.value.sessionId
        if (sessionId.isBlank()) return
        try {
            // 专属偏好 → 全局上次 → 会话带出值（loadSessionInfo 已播种）
            val agent = connectionPreferences.getSelectedAgent(sessionId)
                ?: connectionPreferences.getLastAgent()
            val storedModel = connectionPreferences.getSelectedModel(sessionId)
                ?: connectionPreferences.getLastModel()
            val variant = connectionPreferences.getSelectedVariant(sessionId)
                ?: connectionPreferences.getLastVariant()
            val model = storedModel?.let { ModelSelectionRef(it.providerId, it.modelId) }
            // 新发现3：合并而非替换——偏好为空时保留会话带出的值（否则 restore 把 session agent/model 洗成 auto）
            val cur = _uiState.value.selection.committed
            val config = ChatSelectionConfig(
                agent = agent ?: cur.agent,
                model = model ?: cur.model,
                variant = variant ?: cur.variant,
            )
            val normalized = normalizeSelectionConfig(config, _uiState.value.selection.availableModels, _uiState.value.availableAgents)
            _uiState.update {
                it.copy(chatDisplay = it.chatDisplay.copy(
                    selection = it.chatDisplay.selection.copy(
                        committed = normalized,
                        draft = normalized,
                    ),
                ))
            }
            syncSelectionToLegacyFields(normalized)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore selection", e)
        }
    }

    private fun normalizeSelectionConfig(
        config: ChatSelectionConfig,
        availableModels: List<ModelSelectionOption>,
        availableAgents: List<AgentInfo>,
    ): ChatSelectionConfig {
        var result = config
        // 新发现3：列表为空 = 还没加载完，此时不校验（否则会话带出的值被洗成 auto）
        val agentsLoaded = availableAgents.isNotEmpty()
        val modelsLoaded = availableModels.isNotEmpty()
        // Validate agent
        if (agentsLoaded && result.agent != null && result.agent !in availableAgents.map { it.name }) {
            result = result.copy(agent = null)
        }
        // Validate model
        if (modelsLoaded && result.model != null && result.model !in availableModels.map { it.ref }) {
            result = result.copy(model = null, variant = null)
        }
        // Validate variant
        if (result.variant != null && result.model != null && modelsLoaded) {
            val modelVariants = availableModels.find { it.ref == result.model }?.variants.orEmpty()
            if (result.variant !in modelVariants) {
                result = result.copy(variant = null)
            }
        }
        return result
    }

    private fun normalizeSelectionConfig(
        config: ChatSelectionConfig,
        options: ChatSelectionUiState,
    ): ChatSelectionConfig {
        // 新发现3：列表为空 = 还没加载完，此时保留原值（否则会话带出的值被洗成 auto）
        val model = if (options.availableModels.isEmpty()) config.model
            else config.model?.takeIf { options.resolveModel(it) != null }
        val availableVariants = options.resolveModel(model)?.variants.orEmpty()
        val variant = if (options.availableModels.isEmpty()) config.variant
            else config.variant?.takeIf { it in availableVariants }
        val agent = if (options.availableAgents.isEmpty()) config.agent
            else config.agent?.takeIf { it in options.availableAgents.map { a -> a.name } }
        return config.copy(agent = agent, model = model, variant = variant)
    }

    private fun selectionFromMessageInfo(
        info: MessageInfoData,
        fallbackAgent: String? = null,
    ): ChatSelectionConfig? {
        val agent = info.agent?.takeIf { it.isNotBlank() }
            ?: fallbackAgent?.takeIf { it.isNotBlank() }
        val providerId = info.model?.providerID?.takeIf { it.isNotBlank() }
        val modelId = info.model?.modelID?.takeIf { it.isNotBlank() }
        val model = if (providerId != null && modelId != null) {
            ModelSelectionRef(providerId, modelId)
        } else null
        val variant = info.resolvedVariant?.takeIf { it.isNotBlank() }
        if (agent == null && model == null && variant == null) return null
        return ChatSelectionConfig(agent = agent, model = model, variant = variant)
    }

    fun clearError() {
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(error = null)) }
    }

    // ── Permission / Question Reply Methods ────────────────────────────

    fun replyPermission(reply: String, message: String? = null) {
        val request = _uiState.value.pendingPermission ?: return
        viewModelScope.launch {
            try {
                repository.replyPermission(
                    requestId = request.id,
                    reply = reply,
                    message = message,
                    directory = _uiState.value.sessionDirectory,
                    sessionId = _uiState.value.sessionId,
                )
                Log.d(TAG, "Permission replied: $reply for ${request.id}")
                advancePermission()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply permission", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName),
                ))}
            }
        }
    }

    fun replyQuestion(answers: List<List<String>>) {
        val request = _uiState.value.pendingQuestion ?: return
        viewModelScope.launch {
            try {
                // form 应答：key→选中值（单选标量，多选数组；按 value 回传，无则回 label）
                val ansMap = request.questions.mapIndexed { i, q ->
                    val key = q.key.ifEmpty { "q$i" }
                    val sels = answers.getOrNull(i) ?: emptyList()
                    val vals = sels.mapNotNull { sel -> q.options.find { it.label == sel }?.value ?: sel }
                    val v: JsonElement =
                        if (!q.multiple && vals.size == 1) JsonPrimitive(vals[0])
                        else JsonArray(vals.map { JsonPrimitive(it) })
                    key to v
                }.toMap()
                repository.replyQuestion(request.id, ansMap, _uiState.value.sessionDirectory)
                Log.d(TAG, "Question replied for ${request.id}")
                clearBlockingState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reply question", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName),
                ))}
            }
        }
    }

    fun rejectQuestion() {
        val request = _uiState.value.pendingQuestion ?: return
        viewModelScope.launch {
            try {
                repository.rejectQuestion(request.id, _uiState.value.sessionDirectory)
                Log.d(TAG, "Question rejected for ${request.id}")
                clearBlockingState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reject question", e)
                val s = com.opencode.remote.ui.strings.AppLocale.strings
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    error = s.errSendFailed.replace("%s", e.localizedMessage ?: e.javaClass.simpleName),
                ))}
            }
        }
    }

    /**
     * v2 #8 权限轮询：turn 中每 5s 查一次挂起确认（v2 无 permission SSE 事件）。
     * 安静期（不 streaming/sending、无挂起、无排队）自动退出，不耗电。
     */
    private fun ensurePermissionPoll() {
        if (permissionPollJob?.isActive == true) return
        permissionPollJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                val s = _uiState.value
                if (!s.isStreaming && !s.isSending && s.pendingPermission == null && permissionQueue.isEmpty()) break
                pollPermissionsOnce()
            }
        }
    }

    private fun pollPermissionsOnce() {
        val sid = _uiState.value.sessionId
        if (sid.isBlank()) return
        viewModelScope.launch {
            try {
                val fresh = repository.pollPermissions(sid)
                    .filter { it.id != _uiState.value.pendingPermission?.id }
                    .filter { f -> permissionQueue.none { it.id == f.id } }
                if (fresh.isNotEmpty()) {
                    Log.d(TAG, "Permission poll: ${fresh.size} pending")
                    val first = fresh.first()
                    if (_uiState.value.pendingPermission != null) {
                        permissionQueue.addAll(fresh)
                    } else {
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                            pendingPermission = first, isBlocked = true,
                        ))}
                        repository.saveBlockingState(sid, first, _uiState.value.pendingQuestion)
                        startBlockingWatchdog()
                        if (fresh.size > 1) permissionQueue.addAll(fresh.drop(1))
                        showPermissionNotification(first)
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "pollPermissions failed: ${e.message}") }
            // question 表单轮询（form 是唯一活路：无 SSE、无 permission 条目）
            try {
                val forms = repository.listQuestionForms(sid)
                val fq = forms.firstOrNull()
                val cur = _uiState.value.pendingQuestion
                if (fq != null && cur?.id != fq.id) {
                    Log.d(TAG, "Question form: ${fq.id}")
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        pendingQuestion = fq, isBlocked = true,
                    ))}
                    repository.saveBlockingState(sid, _uiState.value.pendingPermission, fq)
                    startBlockingWatchdog()
                    showQuestionNotification(fq)
                } else if (fq == null && cur != null && cur.id.startsWith("frm_")) {
                    // 表单消失（别处已答/已关）→ 清气泡
                    _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                        pendingQuestion = null, isBlocked = _uiState.value.pendingPermission != null,
                    ))}
                }
            } catch (e: Exception) { Log.w(TAG, "question form poll failed: ${e.message}") }
        }
    }

    private fun showQuestionNotification(req: QuestionRequestData) {
        // 震动先行：前台盯着看也要震（这是等回复的信号），只是不弹系统通知
        buzzOnce()
        // P2：前台时气泡已可见，不再打扰
        if (AppForegroundTracker.isForeground) return
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val firstQ = req.questions.firstOrNull()?.question ?: req.id
            val n = android.app.Notification.Builder(appContext, OConnectorApp.CHANNEL_ID_COMPLETION)
                .setContentTitle("需要选择")
                .setContentText(firstQ)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .build()
            nm.notify(QUESTION_NOTIFICATION_ID, n)
            // (buzz 已在入口震过，前台/后台都有份)
        } catch (e: Exception) { Log.w(TAG, "question notify failed", e) }
    }

    private fun showPermissionNotification(req: PermissionRequestData) {
        // 震动先行：前台盯着看也要震，只是免系统通知
        buzzOnce()
        // P2：前台时气泡已可见，不再打扰
        if (AppForegroundTracker.isForeground) return
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = android.app.Notification.Builder(appContext, OConnectorApp.CHANNEL_ID_COMPLETION)
                .setContentTitle("需要确认：${req.permission}")
                .setContentText(req.patterns.take(2).joinToString(", ").ifEmpty { req.sessionID })
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true)
                .build()
            nm.notify(PERMISSION_NOTIFICATION_ID, n)
            // (buzz 已在入口震过，前台/后台都有份)
        } catch (e: Exception) { Log.w(TAG, "permission notify failed", e) }
    }

    /** P2：短震动（完成/确认提醒）。 */
    private fun buzzOnce() {
        try {
            val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vib?.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) { Log.w(TAG, "buzz failed", e) }
    }

    /** Advance to the next queued permission, or clear blocked state if queue is empty. */    private fun advancePermission() {
        if (permissionQueue.isNotEmpty()) {
            val next = permissionQueue.removeAt(0)
            Log.d(TAG, "Advancing to queued permission: ${next.id}, remaining: ${permissionQueue.size}")
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                pendingPermission = next, isBlocked = true,
            ))}
            repository.saveBlockingState(_uiState.value.sessionId, next, _uiState.value.pendingQuestion)
            startBlockingWatchdog()  // Start timeout for queued permission
        } else {
            blockingWatchdogJob?.cancel()  // Cancel orphaned watchdog
            repository.clearBlockingState(_uiState.value.sessionId)
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                pendingPermission = null, isBlocked = false,
            ))}
        }
    }

    private fun clearBlockingState() {
        blockingWatchdogJob?.cancel()
        repository.clearBlockingState(_uiState.value.sessionId)
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
            pendingPermission = null, pendingQuestion = null, isBlocked = false, recoveryPending = false,
        ))}
    }

    private fun showTodoCompletionNotification() {
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val strings = AppLocale.strings
        val title = _uiState.value.sessionTitle ?: strings.sessionFallback

        val notification = android.app.Notification.Builder(appContext, OConnectorApp.CHANNEL_ID)
            .setContentTitle(strings.todoCompleted)
            .setContentText(strings.todoCompletedDesc.format(title))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setTimeoutAfter(5000)
            .build()

        notificationManager.notify(TODO_COMPLETED_NOTIFICATION_ID, notification)
    }

    /** Trim message list to the latest [maxCount] messages to prevent memory bloat on long conversations. */
    private fun List<MessageInfo>.trimToLatest(maxCount: Int = 150): List<MessageInfo> {
        return if (size > maxCount) takeLast(maxCount) else this
    }

    /** Filter out messages at or after the revert point (undo hides them). */
    private fun List<MessageInfo>.filterReverted(revertMessageId: String?): List<MessageInfo> {
        if (revertMessageId == null) return this
        return filter { it.id < revertMessageId }
    }

    /** Apply both revert filtering and trim in one call. */
    private fun List<MessageInfo>.applyMessageFilters(revertMessageId: String?): List<MessageInfo> =
        filterReverted(revertMessageId).trimToLatest()

    /**
     * Fallback polling: only checks for new messages when SSE appears to have stalled
     * (no events received for 15 seconds). This avoids redundant API calls while SSE
     * is actively streaming, reducing server load and battery usage.
     */
    private fun startFallbackPolling(expectedSessionId: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                delay(5000)  // Check every 5 seconds
                if (_uiState.value.sessionId != expectedSessionId) break
                if (_uiState.value.isStreaming) continue  // Still streaming via SSE, skip

                // Only poll if no SSE events received in last 15 seconds
                val timeSinceLastEvent = System.currentTimeMillis() - lastSseEventTime
                if (timeSinceLastEvent < 15_000) continue

                try {
                    val freshMessages = repository.getMessages(
                        _uiState.value.sessionId,
                        _uiState.value.sessionDirectory,
                        limit = 5,
                    )
                    consecutiveFailures = 0
                    // 轮询通了 → 心跳可能还没跑到，先把横条清掉
                    if (_uiState.value.serverUnreachable) {
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(serverUnreachable = false)) }
                    }
                    val currentMessages = _uiState.value.messages
                    val currentLatestId = currentMessages.lastOrNull()?.id
                    val freshLatestId = freshMessages.lastOrNull()?.id
                    if (freshLatestId != null && freshLatestId != currentLatestId) {
                        Log.d(TAG, "Fallback polling detected new message: $freshLatestId")
                        val fullMessages = repository.getMessages(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(messages = fullMessages.applyMessageFilters(it.sessionMeta.revertMessageId))) }
                        updateContextUsage()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback polling error", e)
                    // 连续 3 次轮询失败（约 15s+ 无 SSE）→ 大概率断流，挂横条别让用户干等转圈
                    consecutiveFailures++
                    if (consecutiveFailures >= 3 && !_uiState.value.serverUnreachable) {
                        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(serverUnreachable = true)) }
                    }
                }
            }
        }
    }

    /**
     * Watchdog that force-clears streaming state if no SSE events arrive for 120s.
     * Prevents permanent UI freeze when session.idle is missed (e.g. app killed
     * during generation, SSE reconnection gap, server crash).
     */
    private fun startStreamingWatchdog() {
        streamingWatchdogJob?.cancel()
        streamingWatchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)  // Check every 15s
                val state = _uiState.value
                if (!state.isStreaming && !state.isSending) {
                    // Not streaming — watchdog not needed
                    break
                }
                // Reset if any SSE event arrived recently
                val timeSinceEvent = System.currentTimeMillis() - lastSseEventTime
                if (timeSinceEvent < 120_000) continue  // SSE is still active

                // 120s with no SSE events while stuck in sending/streaming — force clear
                Log.w(TAG, "Streaming watchdog: no SSE events for 120s, force-clearing stuck state")
                try { repository.abortSession(state.sessionId, state.sessionDirectory) } catch (_: Exception) {}
                batchFlushJob?.cancel()
                synchronized(pendingDeltas) { pendingDeltas.clear() }
                repository.clearStreaming()
                // Reload messages to get the latest state from server
                try {
                    val fresh = repository.getMessages(state.sessionId, state.sessionDirectory)
                    _uiState.update {
                        it.copy(
                            streaming = it.streaming.copy(
                                isStreaming = false,
                                isSending = false,
                                streamingSegments = emptyList(),
                                streamingAgent = null,
                                pendingAssistantMessageId = null,
                            ),
                            chatDisplay = it.chatDisplay.copy(
                                messages = fresh.applyMessageFilters(it.sessionMeta.revertMessageId),
                            ),
                        )
                    }
                } catch (e: Exception) {
                    // Even reload failed — just clear streaming state
                    _uiState.update {
                        it.copy(streaming = it.streaming.copy(
                            isStreaming = false, isSending = false,
                            streamingSegments = emptyList(), streamingAgent = null,
                            pendingAssistantMessageId = null,
                        ))
                    }
                }
                updateContextUsage()
                break
            }
        }
    }

    /**
     * Watchdog that auto-clears stale blocking state after 120 seconds.
     * If the AI is waiting for permission/question reply but no response is given
     * within 120s, the server has likely timed out and the blocking state is stale.
     */
    private fun startBlockingWatchdog() {
        blockingWatchdogJob?.cancel()
        blockingWatchdogJob = viewModelScope.launch {
            delay(120_000)  // 120 seconds
            val state = _uiState.value.chatDisplay
            if (state.pendingPermission != null || state.pendingQuestion != null || state.isBlocked) {
                Log.w(TAG, "Blocking watchdog: stale state after 120s, auto-clearing")
                clearBlockingState()
                permissionQueue.clear()
                // Notify user via Toast
                android.widget.Toast.makeText(
                    appContext,
                    com.opencode.remote.ui.strings.AppLocale.strings.blockingStateExpired,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Immediate recovery check: runs right after message loading in initialize(). Detects if the AI was interrupted
     * mid-work by checking if the last assistant message has no completed timestamp. This covers question, permission,
     * and any other blocking state uniformly. No delay — runs synchronously.
     */
    private fun checkSessionBlocking(messages: List<MessageInfo>) {
        val state = _uiState.value.chatDisplay
        // If session is actively streaming, don't trigger recovery
        val streaming = _uiState.value.streaming
        if (streaming.isStreaming || streaming.isSending) {
            Log.d(TAG, "checkSessionBlocking: session is streaming, skipping heuristic")
            return
        }
        // If recovery already pending from a previous check, skip redundant heuristic
        if (state.recoveryPending) {
            Log.d(TAG, "checkSessionBlocking: recovery already pending, skipping heuristic")
            return
        }
        // If blocking state is already set (preserved from T1 or SSE re-delivery), skip heuristic
        if (state.pendingPermission != null || state.pendingQuestion != null) {
            Log.d(TAG, "checkSessionBlocking: blocking state already set, skipping heuristic")
            return
        }

        // Find the last assistant message
        val lastAssistant = messages.lastOrNull { it.role == "assistant" } ?: return
        val hasCompletedTimestamp = lastAssistant.info.time?.completed != null && lastAssistant.info.time.completed > 0

        if (hasCompletedTimestamp) {
            // Last assistant message completed normally — not blocked
            Log.d(TAG, "checkSessionBlocking: last assistant completed, session not blocked")
            return
        }

        // Last assistant has no completed timestamp. Before triggering recovery,
        // verify the session itself has completed — if it hasn't, the AI is still
        // actively working (e.g., in TUI) and this is NOT an interrupted state.
        viewModelScope.launch {
            try {
                val session = repository.getSession(_uiState.value.sessionId, _uiState.value.sessionDirectory)
                val sessionCompleted = session.time?.completed != null && session.time.completed > 0
                if (!sessionCompleted) {
                    Log.d(TAG, "checkSessionBlocking: session not completed, AI may still be working — skipping recovery")
                    return@launch
                }
                // Session completed but last assistant isn't — AI was interrupted
                Log.d(TAG, "checkSessionBlocking: session completed but assistant incomplete, triggering recovery")
                _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                    isBlocked = true,
                    recoveryPending = true,
                ))}
                startBlockingWatchdog()
            } catch (e: Exception) {
                Log.w(TAG, "checkSessionBlocking: failed to check session status, skipping recovery", e)
            }
        }
    }

    /** Dismiss recovery/heuristic blocking state — user chose to ignore. */
    fun dismissBlocking() {
        blockingWatchdogJob?.cancel()
        permissionQueue.clear()
        repository.clearBlockingState(_uiState.value.sessionId)
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
            pendingPermission = null, pendingQuestion = null, isBlocked = false, recoveryPending = false,
        ))}
    }

    /**
     * Re-check blocking state when the user taps "Check Status" on the RecoveryBubble.
     * This clears the heuristic-only state and tries to recover real data from
     * cache (memory + disk), falling back to a fresh server message check.
     */
    fun recheckBlockingState() {
        val sessionId = _uiState.value.sessionId
        if (sessionId.isBlank()) return

        // Clear current recovery-only state so guards don't block re-check
        _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
            pendingPermission = null, pendingQuestion = null, isBlocked = false, recoveryPending = false,
        ))}
        blockingWatchdogJob?.cancel()

        // Step 1: Try cache (memory → disk)
        val cached = repository.getBlockingState(sessionId)
        if (cached != null && (cached.permission != null || cached.question != null)) {
            Log.d(TAG, "recheckBlockingState: restored from cache perm=${cached.permission != null} qst=${cached.question != null}")
            _uiState.update { it.copy(chatDisplay = it.chatDisplay.copy(
                pendingPermission = cached.permission,
                pendingQuestion = cached.question,
                isBlocked = true,
            ))}
            startBlockingWatchdog()
            return
        }

        // Step 2: Load messages from server and run heuristic
        // (checkSessionBlocking now also checks session completion to avoid
        // false positives when AI is actively working in TUI)
        viewModelScope.launch {
            try {
                val messages = repository.getMessages(sessionId, _uiState.value.sessionDirectory)
                Log.d(TAG, "recheckBlockingState: loaded ${messages.size} messages, running heuristic")
                checkSessionBlocking(messages)
            } catch (e: Exception) {
                Log.e(TAG, "recheckBlockingState: failed to load messages", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        batchFlushJob?.cancel()
        pollingJob?.cancel()
        sseJob?.cancel()
        streamingWatchdogJob?.cancel()
        blockingWatchdogJob?.cancel()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }
}
