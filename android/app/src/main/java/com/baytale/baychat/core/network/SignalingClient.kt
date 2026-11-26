package com.baytale.baychat.core.network

import com.baytale.baychat.core.model.CachedMessage
import com.baytale.baychat.core.model.PeerProfile
import com.baytale.baychat.core.model.SignalEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

interface SignalingCallbacks {
    fun onConnectionState(connected: Boolean)
    fun onJoined(env: SignalEnvelope)
    fun onPeerJoined(profile: PeerProfile)
    fun onPeerUpdated(profile: PeerProfile)
    fun onPeerLeft(peerId: String)
    fun onSignal(env: SignalEnvelope)
    fun onMessageCached(env: SignalEnvelope)
    fun onMessageRecalled(env: SignalEnvelope)
    fun onRetentionUpdated(env: SignalEnvelope)
    fun onRoomSettingsUpdated(env: SignalEnvelope)
    fun onRoomStatsUpdated(env: SignalEnvelope)
    fun onHistoryCleared(env: SignalEnvelope)
    fun onRoomDestroyed(reason: String)
    fun onError(code: String, message: String)
    fun onQueuedMessagesDropped(messageIds: List<String>) {}
    fun onQueuedMessageFlushed(messageId: String) {}
}

// 持久化 WebSocket 信令客户端：支持 18 秒保活心跳、断线重发队列（上限 200 条/10 分钟）、网络恢复秒重连与致命错误熔断
class SignalingClient(
    private val wsUrl: String,
    private val roomCode: String,
    private val password: String,
    @Volatile private var profile: PeerProfile,
    private val okHttpClient: OkHttpClient,
    private val callbacks: SignalingCallbacks,
    private val ownerToken: String = "",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closedManually = AtomicBoolean(false)
    private val joinedReady = AtomicBoolean(false)
    private val outboundQueue = OutboundSignalQueue()

    @Volatile
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var probeJob: Job? = null
    private var reconnectAttempts = 0
    @Volatile
    private var lastServerActivityAt = 0L

    private val fatalErrors = setOf(
        "ROOM_NOT_FOUND",
        "ROOM_EXPIRED",
        "WRONG_PASSWORD",
        "ROOM_FULL",
        "INVALID_JOIN",
    )

    fun isConnected(): Boolean = webSocket != null && joinedReady.get()

    fun connect() {
        closedManually.set(false)
        reconnectAttempts = 0
        openSocket()
    }

    // B17：系统网络恢复时清零退避计数；仅在完成 join 握手后发送 ping 探活，避免抢先于首条 join 触发 INVALID_JOIN
    fun notifyNetworkAvailable() {
        if (closedManually.get()) return
        reconnectAttempts = 0
        val currentWs = webSocket
        if (currentWs == null) {
            reconnectJob?.cancel()
            reconnectJob = null
            openSocket()
        } else if (!joinedReady.get()) {
            // 正在建立 join 握手中，等待握手超时若仍无响应再重建连接
            val checkStartAt = System.currentTimeMillis()
            probeJob?.cancel()
            probeJob = scope.launch {
                delay(3_000L)
                if (!closedManually.get() && !joinedReady.get() &&
                    this@SignalingClient.webSocket === currentWs && lastServerActivityAt < checkStartAt
                ) {
                    openSocket()
                }
            }
        } else {
            val probeSentAt = System.currentTimeMillis()
            val sent = sendRawImmediate(currentWs, SignalEnvelope(type = "ping"))
            if (!sent) {
                openSocket()
                return
            }
            probeJob?.cancel()
            probeJob = scope.launch {
                delay(3_000L)
                if (!closedManually.get() && this@SignalingClient.webSocket === currentWs && lastServerActivityAt < probeSentAt) {
                    openSocket()
                }
            }
        }
    }

    @Synchronized
    private fun openSocket() {
        if (closedManually.get()) return
        joinedReady.set(false)
        cleanupSocketOnly()

        val request = Request.Builder().url(wsUrl).build()
        val ws = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    lastServerActivityAt = System.currentTimeMillis()
                    reconnectAttempts = 0
                    callbacks.onConnectionState(true)
                    sendRawImmediate(
                        webSocket,
                        SignalEnvelope(
                            type = "join",
                            roomCode = roomCode,
                            peerId = profile.peerId,
                            password = password,
                            ownerToken = ownerToken.takeIf { it.isNotBlank() },
                            profile = profile,
                        )
                    )
                    startHeartbeat()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    lastServerActivityAt = System.currentTimeMillis()
                    val env = runCatching { json.decodeFromString(SignalEnvelope.serializer(), text) }.getOrNull()
                        ?: return
                    when (env.type) {
                        "joined" -> {
                            joinedReady.set(true)
                            callbacks.onJoined(env)
                            flushQueuedSignals(webSocket)
                        }
                        "peer_joined" -> env.profile?.let { callbacks.onPeerJoined(it) }
                        "peer_updated" -> env.profile?.let { callbacks.onPeerUpdated(it) }
                        "peer_left" -> env.peerId?.let { callbacks.onPeerLeft(it) }
                        "offer", "answer", "ice-candidate" -> callbacks.onSignal(env)
                        "message_cached" -> callbacks.onMessageCached(env)
                        "message_recalled" -> callbacks.onMessageRecalled(env)
                        "retention_updated" -> callbacks.onRetentionUpdated(env)
                        "room_settings_updated" -> callbacks.onRoomSettingsUpdated(env)
                        "room_stats_updated" -> callbacks.onRoomStatsUpdated(env)
                        "history_cleared" -> callbacks.onHistoryCleared(env)
                        "room_destroyed" -> {
                            closedManually.set(true)
                            joinedReady.set(false)
                            callbacks.onRoomDestroyed(env.reason ?: "房间已销毁")
                        }
                        "error" -> {
                            val code = env.errorCode ?: "ERROR"
                            if (code in fatalErrors) {
                                closedManually.set(true)
                                joinedReady.set(false)
                            }
                            callbacks.onError(code, env.error ?: "连接失败")
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    handleDisconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    handleDisconnect()
                }
            }
        )
        this.webSocket = ws
    }

    private fun handleDisconnect() {
        joinedReady.set(false)
        webSocket = null
        stopTimers()
        callbacks.onConnectionState(false)
        if (!closedManually.get()) {
            // 缩短后台重连退避（500ms 起步、上限 5s），断线后亚秒级拉起信令收推消息
            val delayMs = min(5_000L, (500.0 * 2.0.pow(reconnectAttempts)).toLong())
            reconnectAttempts = min(reconnectAttempts + 1, 5)
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                if (!closedManually.get()) {
                    openSocket()
                }
            }
        }
    }

    private fun flushQueuedSignals(ws: WebSocket) {
        val drained = outboundQueue.drainValid()
        if (drained.expiredMessageIds.isNotEmpty()) {
            callbacks.onQueuedMessagesDropped(drained.expiredMessageIds)
        }
        drained.readyToSend.forEach { env ->
            val ok = sendRawImmediate(ws, env)
            if (ok) {
                env.message?.id?.let { callbacks.onQueuedMessageFlushed(it) }
            } else {
                val evicted = outboundQueue.enqueue(env)
                if (evicted != null) {
                    callbacks.onQueuedMessagesDropped(listOf(evicted))
                }
            }
        }
    }

    private fun sendRawImmediate(ws: WebSocket, env: SignalEnvelope): Boolean {
        val raw = runCatching { json.encodeToString(SignalEnvelope.serializer(), env) }.getOrNull() ?: return false
        return runCatching { ws.send(raw) }.getOrDefault(false)
    }

    // 返回是否即时发出（false 表示已暂存断线重发队列）
    fun send(env: SignalEnvelope): Boolean {
        val ws = webSocket
        if (env.type == "join" || env.type == "ping") {
            return ws != null && sendRawImmediate(ws, env)
        }
        if (ws != null && joinedReady.get() && sendRawImmediate(ws, env)) {
            return true
        }
        if (!closedManually.get()) {
            val evicted = outboundQueue.enqueue(env)
            if (evicted != null) {
                callbacks.onQueuedMessagesDropped(listOf(evicted))
            }
        }
        return false
    }

    fun updateProfile(next: PeerProfile) {
        profile = next
        send(SignalEnvelope(type = "profile_update", profile = next))
    }

    fun cacheMessage(message: CachedMessage): Boolean {
        return send(SignalEnvelope(type = "cache_message", message = message))
    }

    fun recallMessage(messageId: String) {
        send(SignalEnvelope(type = "recall_message", messageId = messageId))
    }

    fun updateRetention(days: Int) {
        send(SignalEnvelope(type = "update_retention", retentionDays = days))
    }

    fun updateMaxFileSize(maxFileSizeMB: Int) {
        send(SignalEnvelope(type = "update_max_file_size", maxFileSizeMB = maxFileSizeMB))
    }

    fun updateAllowRandom(allowRandom: Boolean) {
        send(SignalEnvelope(type = "update_allow_random", allowRandom = allowRandom))
    }

    fun recordActivity(kind: String, bytes: Long = 0) {
        send(SignalEnvelope(type = "record_activity", activityKind = kind, activityBytes = bytes))
    }

    fun clearHistory() {
        send(SignalEnvelope(type = "clear_history"))
    }

    fun leaveRoom() {
        closedManually.set(true)
        outboundQueue.clear()
        webSocket?.let { sendRawImmediate(it, SignalEnvelope(type = "leave_room")) }
        disconnect()
    }

    fun destroyRoom() {
        closedManually.set(true)
        outboundQueue.clear()
        webSocket?.let { sendRawImmediate(it, SignalEnvelope(type = "destroy_room")) }
        disconnect()
    }

    fun disconnect() {
        closedManually.set(true)
        joinedReady.set(false)
        outboundQueue.clear()
        stopTimers()
        cleanupSocketOnly()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !closedManually.get()) {
                delay(12_000L)
                // 超过 25 秒无任何服务端回包视为后台半开假死连接，立刻主动重连确保消息零漏推
                if (System.currentTimeMillis() - lastServerActivityAt > 25_000L) {
                    openSocket()
                    break
                }
                if (joinedReady.get()) {
                    webSocket?.let { sendRawImmediate(it, SignalEnvelope(type = "ping")) }
                }
            }
        }
    }

    private fun stopTimers() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        probeJob?.cancel()
        probeJob = null
    }

    private fun cleanupSocketOnly() {
        val old = webSocket
        webSocket = null
        runCatching { old?.close(1000, "client_close") }
    }
}
