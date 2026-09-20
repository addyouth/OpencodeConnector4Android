package com.opencode.remote.data.api

import android.util.Log
import android.util.Base64
import com.opencode.remote.data.api.dto.v2.V2EventFrame
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManager

/**
 * SSE client for OpenCode server v2 event streaming.
 *
 * Endpoint: GET /api/event (Content-Type: text/event-stream, Basic auth).
 * 帧格式（实测 2.0.10）：`data: {"id","type","data"}` + `: heartbeat` 注释行。
 * 重连/心跳逻辑与 v1 OConnectorSseClient 同构；事件 type→UI 映射在联调真流量时补。
 */
class OConnectorSseV2Client @Inject constructor(
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
        private const val TAG = "OpenCodeSseV2"
        private const val MAX_RETRIES = 5
        private const val INITIAL_DELAY_MS = 5000L
        private const val MAX_DELAY_MS = 30000L
    }

    /** Configure (or reconfigure) the SSE client with connection parameters. */
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

    /** Subscribe to v2 server events via SSE (same retry/heartbeat contract as v1). */
    fun subscribeToEvents(): Flow<V2EventFrame> = channelFlow {
        var retryCount = 0
        var terminalError: IOException? = null

        while (isActive) {
            var lastEventTimeMs = System.currentTimeMillis()
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

                        if (line.startsWith("data:")) {
                            lastEventTimeMs = System.currentTimeMillis()
                            val jsonStr = line.removePrefix("data:").trim()
                            if (jsonStr.isNotEmpty()) {
                                try {
                                    val event = json.decodeFromString<V2EventFrame>(jsonStr)
                                    send(event)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse SSE event: $jsonStr", e)
                                }
                            }
                        }
                        // v2 server sends ": heartbeat" comments — any ":" line refreshes liveness
                        if (line.startsWith(":")) {
                            lastEventTimeMs = System.currentTimeMillis()
                        }
                    }
                }

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

            retryCount++
            if (retryCount > MAX_RETRIES) {
                terminalError = IOException("SSE connection failed after $MAX_RETRIES retries")
                break
            }

            synchronized(this) {
                try { sseClient.close() } catch (_: Exception) {}
                sseClient = createSseClient(insecureTrust)
            }

            val delayMs = minOf(INITIAL_DELAY_MS * (1L shl (retryCount - 1)), MAX_DELAY_MS)
            Log.w(TAG, "SSE reconnecting in ${delayMs}ms (attempt $retryCount/$MAX_RETRIES)")
            delay(delayMs)
        }

        terminalError?.let { throw it }
    }

    fun close() {
        synchronized(this) {
            try { sseClient.close() } catch (_: Exception) {}
        }
    }
}
