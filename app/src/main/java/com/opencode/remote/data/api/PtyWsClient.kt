package com.opencode.remote.data.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * 远程终端传输：OkHttp WebSocket 直连，不新增依赖。
 * 协议（实测 101 握手通过）：出站纯文本帧 + 0x00+{"cursor":N} 控制帧；
 * 入站纯 UTF-8 文本（键盘怎么敲怎么发，特殊键由 UI 翻译成转义序列）。
 */
class PtyWsClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private var socket: WebSocket? = null
    var lastCursor: Long = -1L
        private set

    fun connect(
        url: String,
        authHeader: String?,
        onOutput: (String) -> Unit,
        onClosed: (String) -> Unit,
    ) {
        close()
        lastCursor = -1L
        val req = Request.Builder()
            .url(url)
            .apply { if (!authHeader.isNullOrEmpty()) header("Authorization", authHeader) }
            .build()
        socket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                stripAnsi(text).takeIf { it.isNotEmpty() }?.let(onOutput)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                if (arr.isEmpty()) return
                // 控制帧：0x00 + {"cursor": N}
                if (arr[0] == 0.toByte()) {
                    try {
                        val cur = JSONObject(String(arr, 1, arr.size - 1, Charsets.UTF_8))
                            .optLong("cursor", -1L)
                        if (cur >= 0) lastCursor = cur
                    } catch (e: Exception) {
                        Log.w(TAG, "bad cursor frame", e)
                    }
                    return
                }
                try {
                    stripAnsi(String(arr, Charsets.UTF_8)).takeIf { it.isNotEmpty() }?.let(onOutput)
                } catch (e: Exception) {
                    Log.w(TAG, "bad binary frame", e)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosed("closed($code) $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosed(t.message ?: t.javaClass.simpleName)
            }
        })
    }

    fun send(text: String): Boolean {
        val s = socket ?: return false
        return try {
            s.send(text)
        } catch (e: Exception) {
            Log.w(TAG, "ws send failed", e)
            false
        }
    }

    fun close(code: Int = 1000, reason: String? = null) {
        try {
            socket?.close(code, reason)
        } catch (e: Exception) {
            Log.w(TAG, "ws close failed", e)
        } finally {
            socket = null
        }
    }

    companion object {
        private const val TAG = "PtyWsClient"

        private val CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
        private val OSC = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
        private val CHARSET = Regex("\u001B[()][0-9A-Z]")
        private val SINGLE = Regex("\u001B[=>M78]")

        /** 去 ANSI 转义（v1 不做全终端仿真，纯文本可读优先）。 */
        fun stripAnsi(s: String): String {
            var r = s
            r = OSC.replace(r, "")
            r = CSI.replace(r, "")
            r = CHARSET.replace(r, "")
            r = SINGLE.replace(r, "")
            r = r.replace("\u0007", "")
            r = r.replace("\r\n", "\n")
            r = r.replace("\r", "\n")
            return r
        }
    }
}
