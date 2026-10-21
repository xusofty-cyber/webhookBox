package com.hookbox.app.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/**
 * 一条会自己重连的 WebSocket。
 *
 * 断线不慌：OkHttp 的 pingInterval 负责发现半开连接，
 * 这里负责指数退避 + 抖动（避免所有设备在同一秒一起重连打爆服务端）。
 */
class RelayConnection(
    private val baseUrl: String,
    private val token: String,
    private val onFrame: (String) -> Unit,
    private val onState: (ConnState) -> Unit,
) {

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 一直跑，直到外层协程被取消 */
    suspend fun run(): Unit = coroutineScope {
        var attempt = 0

        while (isActive) {
            onState(ConnState.Connecting)
            val closed = CompletableDeferred<String?>()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    attempt = 0
                    onState(ConnState.Connected)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    onFrame(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closed.complete(t.message ?: t::class.java.simpleName)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(reason.ifBlank { "closed:$code" })
                }
            }

            val socket = client.newWebSocket(Request.Builder().url(wsUrl()).build(), listener)

            try {
                closed.await()
            } finally {
                socket.cancel()
            }

            if (!isActive) break

            val wait = backoffMillis(attempt++)
            onState(ConnState.Retrying((wait / 1000).toInt()))
            delay(wait)
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = min(MAX_BACKOFF_MS, 1_000L shl min(attempt, 6))
        return base + Random.nextLong(0, base / 2 + 1)
    }

    private fun wsUrl(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val scheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
            trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
            trimmed.startsWith("ws://", ignoreCase = true) -> trimmed
            else -> "wss://$trimmed"
        }
        val encoded = URLEncoder.encode(token, "UTF-8")
        return "$scheme/ws?token=$encoded"
    }

    private companion object {
        const val MAX_BACKOFF_MS = 60_000L
    }
}
