package com.opencode.remote.data.api

import android.util.Log
import android.util.Base64
import com.opencode.remote.data.api.dto.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManager

/**
 * SSE client for OpenCode server v1.14.x real-time event streaming.
 *
 * Endpoint: GET /global/event (Content-Type: text/event-stream)
 *
 * Event format (observed from live server):
 *   data: {"directory":"...","project":"global","payload":{"type":"message.part.update","properties":{"sessionID":"ses_xxx","messageID":"msg_xxx","partID":"prt_xxx","delta":"..."}}}
 *   data: {"payload":{"type":"server.connected","properties":{}}}
 *
 * Each SSE "data:" line contains a complete JSON object.
 * Top-level fields (directory, project) are optional; payload is always present.
 */
class OConnectorSseClient @Inject constructor(
    private val json: Json,
) {

    private var baseUrl: String = ""
    private var authHeader: String? = null
    private var autoReconnect: Boolean = true
    private var insecureTrust: Boolean = false
    @Volatile
    private var sseClient: HttpClient = createSseClient()

    private fun createSseClient(insecureTrust: Boolean = false): HttpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = Long.MAX_VALUE // SSE is long-lived
            socketTimeoutMillis = Long.MAX_VALUE
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
        private const val TAG = "OpenCodeSse"
        private const val INITIAL_DELAY_MS = 5000L
        private const val MAX_DELAY_MS = 30000L
    }

    /**
     * Configure (or reconfigure) the SSE client with connection parameters.
     * Called by the repository when a new connection is established.
     */
    fun configure(baseUrl: String, username: String = "", password: String = "", autoReconnect: Boolean = true, insecureTrust: Boolean = false) {
        synchronized(this) {
            close()
            this.baseUrl = baseUrl
            this.autoReconnect = autoReconnect
            this.insecureTrust = insecureTrust
            this.authHeader = if (password.isNotEmpty()) {
                "Basic " + Base64.encodeToString(
                    "${username.ifEmpty { "opencode" }}:$password".toByteArray(),
                    Base64.NO_WRAP
                )
            } else null
            sseClient = createSseClient(insecureTrust)
        }
    }

    /**
     * Subscribe to server events via SSE.
     *
     * Uses channelFlow with retry loop: on connection failure the old HttpClient
     * is closed, a new one is created, and reconnection is attempted with
     * exponential backoff (5s × 2^retry, capped at 30s). Retries NEVER terminate
     * while [autoReconnect] (lifeline): a connection that delivered ≥1 line resets
     * the backoff; only cancellation ends the flow.
     */
    fun subscribeToEvents(): Flow<ServerEvent> = channelFlow {
        var retryCount = 0

        while (isActive) {
            // Heartbeat timeout tracking — 45s without any SSE line triggers reconnect
            var lastEventTimeMs = System.currentTimeMillis()
            var gotLine = false  // 本次连接是否见过行（ping 也算）——见过则退避清零
            val sseChannelRef = AtomicReference<ByteReadChannel?>(null)
            val heartbeatJob = launch {
                while (isActive) {
                    delay(5000)
                    val elapsed = System.currentTimeMillis() - lastEventTimeMs
                    if (elapsed > 45_000) {
                        Log.w(TAG, "SSE heartbeat timeout: ${elapsed}ms since last event, reconnecting")
                        sseChannelRef.get()?.cancel()
                        return@launch
                    }
                }
            }

            try {
                val sseUrl = "$baseUrl/api/event"
                val client = synchronized(this) { sseClient }
                client.prepareGet(sseUrl) {
                    headers {
                        append(HttpHeaders.Accept, "text/event-stream")
                        append(HttpHeaders.CacheControl, "no-cache")
                        authHeader?.let { append(HttpHeaders.Authorization, it) }
                    }
                }.execute { response ->
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    sseChannelRef.set(channel)

                    while (!channel.isClosedForRead) {
                        val line = try {
                            channel.readUTF8Line()
                        } catch (e: Exception) {
                            Log.w(TAG, "SSE read error: ${e.message}")
                            break
                        }

                        if (line == null) break
                        gotLine = true  // 行=活着（含 ": ping"），本轮退避可清零

                        // Only process "data:" lines — each is a complete JSON event
                        if (line.startsWith("data:")) {
                            lastEventTimeMs = System.currentTimeMillis()
                            val jsonStr = line.removePrefix("data:").trim()
                            if (jsonStr.isNotEmpty()) {
                                try {
                                    // v2 事件 → V2EventRaw → 翻译成 v1 ServerEvent（null = UI 不消费，跳过）
                                    val raw = json.decodeFromString<V2EventRaw>(jsonStr)
                                    val event = V2SseTranslation.toServerEvent(raw)
                                    if (event != null) send(event)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse SSE event: $jsonStr", e)
                                }
                            }
                        }
                        // Update heartbeat on any SSE comment line too (server sends ": ping")
                        if (line.startsWith(":")) {
                            lastEventTimeMs = System.currentTimeMillis()
                        }
                        // Empty lines are SSE event separators — ignore
                        // "event:" lines are optional — we parse type from JSON payload
                    }
                }

                // Connection ended (server closed or read error)
                if (!autoReconnect || !isActive) break
            } catch (_: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e(TAG, "SSE stream error: ${e.message}")
                if (!autoReconnect || !isActive) break
            } finally {
                heartbeatJob.cancel()
                sseChannelRef.set(null)
            }

            // 命根永不终局：见过行的连接退避清零；抖动连接退避到 30s 封顶后一直续。
            // （终局抛异常曾让 service 悄悄停流：HTTP 心跳全绿、弹窗震动全灭。封顶重试的耗电
            //  由前台服务常驻覆盖，单次失败 TCP 开销可忽略；真断网由仓库横条提示。）
            retryCount = if (gotLine) 0 else retryCount + 1

            // Close old client and create new one to prevent resource leak
            synchronized(this) {
                try { sseClient.close() } catch (_: Exception) {}
                sseClient = createSseClient(insecureTrust)
            }

            val shift = minOf((retryCount - 1).coerceAtLeast(0), 5)  // 2^5=32，5s*32 封顶 30s，不溢出
            val delayMs = minOf(INITIAL_DELAY_MS * (1L shl shift), MAX_DELAY_MS)
            Log.w(TAG, "SSE reconnecting in ${delayMs}ms (attempt ${retryCount + 1})")
            delay(delayMs)
        }
    }

    fun close() {
        synchronized(this) {
            try { sseClient.close() } catch (_: Exception) {}
        }
    }
}
