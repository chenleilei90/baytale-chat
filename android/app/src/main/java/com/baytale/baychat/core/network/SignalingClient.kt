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
}

// 持久化 WebSocket 信令客户端：支持 18 秒保活心跳、指数退避断线重连与致命错误熔断
class SignalingClient(
    private val wsUrl: String,
    private val roomCode: String,
    private val password: String,
    @Volatile private var profile: PeerProfile,
    private val okHttpClient: OkHttpClient,
    private val callbacks: SignalingCallbacks,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closedManually = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0

    private val fatalErrors = setOf(
        "ROOM_NOT_FOUND",
        "ROOM_EXPIRED",
        "WRONG_PASSWORD",
        "ROOM_FULL",
        "INVALID_JOIN",
    )

    fun connect() {
        closedManually.set(false)
        reconnectAttempts = 0
        openSocket()
    }

    private fun openSocket() {
        if (closedManually.get()) return
        cleanupSocketOnly()

        val request = Request.Builder().url(wsUrl).build()
        val ws = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    reconnectAttempts = 0
                    callbacks.onConnectionState(true)
                    send(
                        SignalEnvelope(
                            type = "join",
                            roomCode = roomCode,
                            peerId = profile.peerId,
                            password = password,
                            profile = profile,
                        )
                    )
                    startHeartbeat()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (this@SignalingClient.webSocket !== webSocket) return
                    val env = runCatching { json.decodeFromString(SignalEnvelope.serializer(), text) }.getOrNull()
                        ?: return
                    when (env.type) {
                        "joined" -> callbacks.onJoined(env)
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
                            callbacks.onRoomDestroyed(env.reason ?: "房间已销毁")
                        }
                        "error" -> {
                            val code = env.errorCode ?: "ERROR"
                            if (code in fatalErrors) {
                                closedManually.set(true)
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
        stopTimers()
        callbacks.onConnectionState(false)
        if (!closedManually.get()) {
            val delayMs = min(10_000L, (1000.0 * 2.0.pow(reconnectAttempts)).toLong())
            reconnectAttempts = min(reconnectAttempts + 1, 5)
            reconnectJob = scope.launch {
                delay(delayMs)
                if (!closedManually.get()) {
                    openSocket()
                }
            }
        }
    }

    fun send(env: SignalEnvelope) {
        val raw = runCatching { json.encodeToString(SignalEnvelope.serializer(), env) }.getOrNull() ?: return
        webSocket?.send(raw)
    }

    fun updateProfile(next: PeerProfile) {
        profile = next
        send(SignalEnvelope(type = "profile_update", profile = next))
    }

    fun cacheMessage(message: CachedMessage) {
        send(SignalEnvelope(type = "cache_message", message = message))
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

    fun recordActivity(kind: String, bytes: Long = 0) {
        send(SignalEnvelope(type = "record_activity", activityKind = kind, activityBytes = bytes))
    }

    fun clearHistory() {
        send(SignalEnvelope(type = "clear_history"))
    }

    fun leaveRoom() {
        closedManually.set(true)
        send(SignalEnvelope(type = "leave_room"))
        disconnect()
    }

    fun destroyRoom() {
        closedManually.set(true)
        send(SignalEnvelope(type = "destroy_room"))
        disconnect()
    }

    fun disconnect() {
        closedManually.set(true)
        stopTimers()
        cleanupSocketOnly()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && !closedManually.get()) {
                delay(18_000L)
                send(SignalEnvelope(type = "ping"))
            }
        }
    }

    private fun stopTimers() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun cleanupSocketOnly() {
        val old = webSocket
        webSocket = null
        runCatching { old?.close(1000, "client_close") }
    }
}
