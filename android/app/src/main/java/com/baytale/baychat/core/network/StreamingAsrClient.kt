package com.baytale.baychat.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时流式中文语音转文字客户端（WebSocket 双向推流：边说边实时回调文字，松手发 eof 秒定稿）。
 */
class StreamingAsrClient(
    private val wsUrl: String,
    private val okHttpClient: OkHttpClient,
    private val onTextUpdate: (text: String, isFinal: Boolean) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val pendingBuffer = ByteArrayOutputStream()
    private val closedManually = AtomicBoolean(false)
    private val eofSent = AtomicBoolean(false)
    private val finalDeferred = CompletableDeferred<String?>()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var wsOpen = false

    @Volatile
    private var latestText: String = ""

    fun connect(initialPcmSnapshot: ByteArray? = null) {
        if (closedManually.get()) return
        if (initialPcmSnapshot != null && initialPcmSnapshot.isNotEmpty()) {
            synchronized(lock) {
                val roomLeft = MAX_PENDING_BYTES - pendingBuffer.size()
                if (roomLeft > 0) {
                    val toWrite = minOf(roomLeft, initialPcmSnapshot.size)
                    val startOffset = initialPcmSnapshot.size - toWrite
                    pendingBuffer.write(initialPcmSnapshot, startOffset, toWrite)
                }
            }
        }
        val req = Request.Builder().url(wsUrl).build()
        val ws = runCatching {
            okHttpClient.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (closedManually.get()) {
                            runCatching { webSocket.cancel() }
                            return
                        }
                        val buffered: ByteArray
                        val shouldSendEofNow: Boolean
                        synchronized(lock) {
                            wsOpen = true
                            buffered = pendingBuffer.toByteArray()
                            pendingBuffer.reset()
                            shouldSendEofNow = eofSent.get()
                        }
                        // 分 3200B（100ms）帧发出已缓冲的历史音频，触发实时滚动出字
                        var offset = 0
                        while (offset < buffered.size && !closedManually.get()) {
                            val chunkLen = minOf(STREAM_CHUNK_BYTES, buffered.size - offset)
                            webSocket.send(buffered.toByteString(offset, chunkLen))
                            offset += chunkLen
                        }
                        if (shouldSendEofNow && !closedManually.get()) {
                            webSocket.send("eof")
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (closedManually.get()) return
                        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                        val recognized = obj["text"]?.jsonPrimitive?.content?.trim() ?: return
                        val isFinal = obj["final"]?.jsonPrimitive?.booleanOrNull == true
                        latestText = recognized
                        onTextUpdate(recognized, isFinal)
                        if (isFinal) {
                            if (!finalDeferred.isCompleted) {
                                finalDeferred.complete(recognized)
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (!finalDeferred.isCompleted) {
                            finalDeferred.complete(latestText.takeIf { it.isNotBlank() })
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!closedManually.get()) {
                            onError(t.message ?: "实时语音连接异常")
                        }
                        if (!finalDeferred.isCompleted) {
                            finalDeferred.complete(latestText.takeIf { it.isNotBlank() })
                        }
                    }
                },
            )
        }.onFailure { err ->
            onError(err.message ?: "无法建立实时语音转文字连接")
            if (!finalDeferred.isCompleted) {
                finalDeferred.complete(latestText.takeIf { it.isNotBlank() })
            }
        }.getOrNull()
        webSocket = ws
    }

    fun sendPcmChunk(pcm: ByteArray, length: Int = pcm.size) {
        if (closedManually.get() || eofSent.get() || length <= 0) return
        val ws = webSocket
        synchronized(lock) {
            if (wsOpen && ws != null) {
                ws.send(pcm.toByteString(0, length))
            } else {
                val roomLeft = MAX_PENDING_BYTES - pendingBuffer.size()
                if (roomLeft > 0) {
                    pendingBuffer.write(pcm, 0, minOf(roomLeft, length))
                }
            }
        }
    }

    suspend fun finishAndAwaitFinal(timeoutMs: Long = 1_800L): String? {
        if (closedManually.get()) return latestText.takeIf { it.isNotBlank() }
        val ws = webSocket
        synchronized(lock) {
            if (eofSent.compareAndSet(false, true) && wsOpen && ws != null) {
                ws.send("eof")
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { finalDeferred.await() }
        cancel()
        return result?.takeIf { it.isNotBlank() } ?: latestText.takeIf { it.isNotBlank() }
    }

    fun cancel() {
        closedManually.set(true)
        synchronized(lock) {
            wsOpen = false
            pendingBuffer.reset()
        }
        if (!finalDeferred.isCompleted) {
            finalDeferred.complete(latestText.takeIf { it.isNotBlank() })
        }
        val old = webSocket
        webSocket = null
        runCatching { old?.cancel() }
    }

    companion object {
        private const val STREAM_CHUNK_BYTES = 3200
        private const val MAX_PENDING_BYTES = 2 * 1024 * 1024
    }
}
