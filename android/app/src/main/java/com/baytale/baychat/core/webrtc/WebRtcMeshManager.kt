package com.baytale.baychat.core.webrtc

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.baytale.baychat.core.model.FileTransferInfo
import com.baytale.baychat.core.model.IceCandidatePayload
import com.baytale.baychat.core.model.IceServerConfig
import com.baytale.baychat.core.model.LinkTransportType
import com.baytale.baychat.core.model.PeerLinkStatus
import com.baytale.baychat.core.model.ReplyQuote
import com.baytale.baychat.core.model.SdpPayload
import com.baytale.baychat.core.model.SignalEnvelope
import com.baytale.baychat.core.model.TransferDirection
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.storage.MediaDownloadHelper
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.core.util.TransferResumeUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface MeshCallbacks {
    fun sendSignal(env: SignalEnvelope)
    fun onLinkStatusChange(status: PeerLinkStatus)
    fun onTextMessage(peerId: String, id: String, text: String, replyTo: ReplyQuote?, timestamp: Long)
    fun onRecallMessage(peerId: String, msgId: String)
    fun onTyping(peerId: String, isTyping: Boolean, mode: String = "text")
    fun onReaction(peerId: String, msgId: String, emoji: String)
    fun onFileOffer(peerId: String, info: FileTransferInfo, msgId: String, caption: String?, timestamp: Long)
    fun onTransferUpdate(info: FileTransferInfo)
    fun shouldAutoAccept(info: FileTransferInfo): Boolean
    fun isPeerBlocked(peerId: String): Boolean = false
    fun onClipboardSync(peerId: String, text: String, updatedAt: Long) {}
    fun onRoomMoved(peerId: String, newCode: String, password: String) {}
    fun onResumeRequested(peerId: String, resumeKey: String, fileName: String, fileSize: Long) {}
    fun onResumeUnavailable(peerId: String, resumeKey: String, fileName: String, reason: String) {}
    fun onPartExpired(partFileName: String) {}
    fun onNudge(
        peerId: String,
        id: String,
        targetPeerId: String,
        targetName: String,
        fromName: String,
        timestamp: Long,
    ) {}
}

data class OutgoingFileSource(
    val uri: Uri? = null,
    val bytes: ByteArray? = null,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val isImage: Boolean,
    val isAudio: Boolean = false,
    val caption: String? = null,
    val transferIdPrefix: String,
    val timestamp: Long,
    val resumeKey: String? = null,
)

// 核心原生 WebRTC Mesh P2P 引擎：Perfect Negotiation + chat/file 双 DataChannel + 48KB 背压流式传输 + 全量 SHA-256 续传校验
class WebRtcMeshManager(
    private val appContext: Context,
    private val selfPeerId: String,
    initialIceServers: List<IceServerConfig>,
    private val callbacks: MeshCallbacks,
) {
    companion object {
        private const val CHUNK_SIZE = 48 * 1024 // 与 Web 端保持一致 48KB 分片
        private const val HIGH_WATER_MARK = 1024L * 1024L // 1MB 背压上限
        private const val LOW_WATER_MARK = 256L * 1024L // 256KB 低水位恢复
        private const val EOF_CHUNK_INDEX = -1 // 0xFFFFFFFF (uint32)
        private const val PART_EXPIRE_MS = 24L * 3600L * 1000L

        private val factoryInitialized = AtomicBoolean(false)

        // B4/B24：迁移至应用私有持久化目录 filesDir/transfer_parts/，避免被系统 cache 回收
        fun transferCacheDir(context: Context): File {
            val appCtx = context.applicationContext
            val legacyDir = File(appCtx.cacheDir, "baychat_transfers")
            if (legacyDir.exists() && legacyDir.isDirectory) {
                legacyDir.listFiles()?.forEach { runCatching { it.delete() } }
                runCatching { legacyDir.delete() }
            }
            return File(appCtx.filesDir, "transfer_parts").apply { mkdirs() }
        }

        fun resumePartFileName(fileName: String, fileSize: Long, resumeKey: String? = null): String {
            return if (!resumeKey.isNullOrBlank()) {
                TransferResumeUtils.partFileNameForKey(resumeKey)
            } else {
                TransferResumeUtils.legacyPartFileName(fileName, fileSize)
            }
        }

        fun ensureWebRtcInitialized(context: Context) {
            if (factoryInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val factory: PeerConnectionFactory
    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val nextStreamId = AtomicInteger(1)

    @Volatile
    private var iceServers: List<PeerConnection.IceServer> = convertIceServers(initialIceServers)

    init {
        ensureWebRtcInitialized(appContext)
        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    private data class OutgoingTransfer(
        val transferId: String,
        val streamId: Int,
        val source: OutgoingFileSource,
        @Volatile var info: FileTransferInfo,
        @Volatile var cancelled: Boolean = false,
        @Volatile var started: Boolean = false,
    )

    private class IncomingTransfer(
        val transferId: String,
        val streamId: Int,
        val peerId: String,
        @Volatile var info: FileTransferInfo,
        @Volatile var accepted: Boolean = false,
        @Volatile var cancelled: Boolean = false,
        @Volatile var hashScope: String? = null,
        @Volatile var expectedSha256Full: String? = null,
        val fullSha: MessageDigest = MessageDigest.getInstance("SHA-256"),
        val partialSha: MessageDigest = MessageDigest.getInstance("SHA-256"),
        var tempFile: File? = null,
        var outputStream: BufferedOutputStream? = null,
        var lastReportAt: Long = 0L,
        var lastReportBytes: Long = 0L,
    )

    private class PeerSession(
        val peerId: String,
        val polite: Boolean,
        val pc: PeerConnection,
    ) {
        @Volatile var chatChannel: DataChannel? = null
        @Volatile var fileChannel: DataChannel? = null
        @Volatile var makingOffer: Boolean = false
        @Volatile var ignoreOffer: Boolean = false
        @Volatile var status: PeerLinkStatus = PeerLinkStatus(peerId, LinkTransportType.CONNECTING, null, false)
        @Volatile var bufferedLowWaiter: CompletableDeferred<Unit>? = null
        val pendingIceCandidates = mutableListOf<IceCandidate>()
        val outgoing = ConcurrentHashMap<String, OutgoingTransfer>()
        val incomingById = ConcurrentHashMap<String, IncomingTransfer>()
        val incomingByStream = ConcurrentHashMap<Int, IncomingTransfer>()
        // 严格单线程串行执行二进制分片写入，防止乱序或 EOF 抢跑
        val receiveExecutor = Executors.newSingleThreadExecutor()
        var statsJob: Job? = null
    }

    fun updateIceServers(configs: List<IceServerConfig>) {
        val converted = convertIceServers(configs)
        iceServers = converted
        sessions.values.forEach { session ->
            runCatching {
                val rtcConfig = buildRtcConfig(converted)
                session.pc.setConfiguration(rtcConfig)
                if (!session.status.dataReady) {
                    session.pc.restartIce()
                }
            }
        }
    }

    // B17：网络恢复切换后主动对尚未打通的数据通道触发 ICE 重启
    fun refreshIceIfNeeded() {
        sessions.values.forEach { session ->
            if (!session.status.dataReady) {
                runCatching { session.pc.restartIce() }
            }
        }
    }

    fun syncPeers(activePeerIds: List<String>) {
        val activeSet = activePeerIds.filterNot { callbacks.isPeerBlocked(it) }.toSet()
        sessions.keys.toList().forEach { existingId ->
            if (existingId !in activeSet) {
                removePeer(existingId)
            }
        }
        activeSet.forEach { ensurePeer(it) }
    }

    fun ensurePeer(remotePeerId: String, forceReset: Boolean = false) {
        if (remotePeerId.isBlank() || remotePeerId == selfPeerId) return
        if (callbacks.isPeerBlocked(remotePeerId)) return
        val existing = sessions[remotePeerId]
        if (existing != null) {
            val state = existing.pc.iceConnectionState()
            val dead = state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED
            if (!forceReset && !dead) return
            removePeer(remotePeerId)
        }

        val polite = selfPeerId < remotePeerId
        val rtcConfig = buildRtcConfig(iceServers)

        lateinit var session: PeerSession
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.FAILED -> {
                        runCatching { session.pc.restartIce() }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        session.status = session.status.copy(
                            transport = LinkTransportType.DISCONNECTED,
                            dataReady = false,
                        )
                        callbacks.onLinkStatusChange(session.status)
                    }
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        probeLinkStats(session)
                    }
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val payload = json.encodeToJsonElement(
                    IceCandidatePayload(
                        candidate = candidate.sdp,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                    )
                )
                callbacks.sendSignal(
                    SignalEnvelope(
                        type = "ice-candidate",
                        targetPeerId = remotePeerId,
                        payload = payload,
                    )
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit

            override fun onDataChannel(dataChannel: DataChannel?) {
                if (dataChannel != null) {
                    bindDataChannel(session, dataChannel)
                }
            }

            override fun onRenegotiationNeeded() {
                scope.launch {
                    createAndSendOffer(session)
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        }

        val pc = factory.createPeerConnection(rtcConfig, observer) ?: return
        session = PeerSession(remotePeerId, polite, pc)
        sessions[remotePeerId] = session
        callbacks.onLinkStatusChange(session.status)

        if (!polite) {
            val init = DataChannel.Init().apply { ordered = true }
            pc.createDataChannel("chat", init)?.let { bindDataChannel(session, it) }
            pc.createDataChannel("file", init)?.let { bindDataChannel(session, it) }
        }

        session.statsJob = scope.launch {
            while (isActive) {
                delay(3_000L)
                probeLinkStats(session)
            }
        }
    }

    private suspend fun createAndSendOffer(session: PeerSession) {
        if (session.makingOffer) return
        session.makingOffer = true
        try {
            val sdp = session.pc.createOfferAwait() ?: return
            if (!session.pc.setLocalDescriptionAwait(sdp)) return
            val payload = json.encodeToJsonElement(SdpPayload("offer", sdp.description))
            callbacks.sendSignal(
                SignalEnvelope(
                    type = "offer",
                    targetPeerId = session.peerId,
                    payload = payload,
                )
            )
        } finally {
            session.makingOffer = false
        }
    }

    fun handleSignal(env: SignalEnvelope) {
        val fromId = env.fromPeerId ?: return
        if (fromId == selfPeerId || callbacks.isPeerBlocked(fromId)) return

        ensurePeer(fromId)
        val session = sessions[fromId] ?: return
        val payload = env.payload ?: return

        scope.launch {
            runCatching {
                when (env.type) {
                    "offer", "answer" -> {
                        val desc = json.decodeFromJsonElement(SdpPayload.serializer(), payload)
                        val isOffer = desc.type.equals("offer", ignoreCase = true)
                        val offerCollision = isOffer && (
                            session.makingOffer ||
                                session.pc.signalingState() != PeerConnection.SignalingState.STABLE
                            )
                        session.ignoreOffer = !session.polite && offerCollision
                        if (session.ignoreOffer) return@launch

                        if (offerCollision && session.pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                            session.pc.setLocalDescriptionAwait(
                                SessionDescription(SessionDescription.Type.ROLLBACK, "")
                            )
                        }

                        val sdpType = if (isOffer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                        val ok = session.pc.setRemoteDescriptionAwait(SessionDescription(sdpType, desc.sdp))
                        if (!ok) return@launch

                        synchronized(session.pendingIceCandidates) {
                            val queued = session.pendingIceCandidates.toList()
                            session.pendingIceCandidates.clear()
                            queued.forEach { cand -> runCatching { session.pc.addIceCandidate(cand) } }
                        }

                        if (isOffer) {
                            val answer = session.pc.createAnswerAwait() ?: return@launch
                            if (session.pc.setLocalDescriptionAwait(answer)) {
                                callbacks.sendSignal(
                                    SignalEnvelope(
                                        type = "answer",
                                        targetPeerId = fromId,
                                        payload = json.encodeToJsonElement(SdpPayload("answer", answer.description)),
                                    )
                                )
                            }
                        }
                    }

                    "ice-candidate" -> {
                        val candJson = json.decodeFromJsonElement(IceCandidatePayload.serializer(), payload)
                        val candidate = IceCandidate(candJson.sdpMid ?: "", candJson.sdpMLineIndex, candJson.candidate)
                        if (session.pc.remoteDescription == null) {
                            synchronized(session.pendingIceCandidates) {
                                session.pendingIceCandidates.add(candidate)
                            }
                        } else {
                            runCatching { session.pc.addIceCandidate(candidate) }
                        }
                    }
                }
            }
        }
    }

    fun reconnectPeer(remotePeerId: String) {
        if (callbacks.isPeerBlocked(remotePeerId)) return
        ensurePeer(remotePeerId, forceReset = true)
    }

    fun removePeer(remotePeerId: String) {
        val session = sessions.remove(remotePeerId) ?: return
        session.statsJob?.cancel()
        session.bufferedLowWaiter?.complete(Unit)
        session.outgoing.values.forEach { out ->
            out.cancelled = true
            if (out.info.status == TransferStatus.WAITING_ACCEPT || out.info.status == TransferStatus.TRANSFERRING) {
                out.info = out.info.copy(
                    status = TransferStatus.ERROR,
                    errorMessage = "对方已离开或连接中断",
                    speedBps = 0.0,
                    etaSeconds = 0.0,
                )
                callbacks.onTransferUpdate(out.info)
            }
        }
        session.incomingById.values.forEach { inc ->
            inc.cancelled = true
            runCatching { inc.outputStream?.flush() }
            runCatching { inc.outputStream?.close() }
            inc.outputStream = null
            if (inc.info.status == TransferStatus.OFFERING || inc.info.status == TransferStatus.TRANSFERRING) {
                val hasPartial = !inc.info.isImage && !inc.info.isAudio && inc.info.transferredBytes > 0L
                inc.info = inc.info.copy(
                    status = TransferStatus.ERROR,
                    errorMessage = if (hasPartial) "对方已离开，已保留断点（重发同文件可续传）" else "对方已离开或连接中断",
                    speedBps = 0.0,
                    etaSeconds = 0.0,
                )
                callbacks.onTransferUpdate(inc.info)
            }
        }
        session.receiveExecutor.shutdownNow()
        runCatching { session.chatChannel?.close() }
        runCatching { session.fileChannel?.close() }
        runCatching { session.pc.close() }
        runCatching { session.pc.dispose() }
    }

    fun destroyAll() {
        sessions.keys.toList().forEach { removePeer(it) }
        scope.cancel()
        runCatching { factory.dispose() }
    }

    fun getConnectedPeerCount(): Int = sessions.values.count { it.status.dataReady }

    fun broadcastText(id: String, text: String, timestamp: Long, replyTo: ReplyQuote? = null): Int {
        val obj = buildJsonObject {
            put("kind", "text")
            put("id", id)
            put("text", text)
            put("timestamp", timestamp)
            if (replyTo != null) {
                put("replyTo", json.encodeToJsonElement(replyTo))
            }
        }
        return broadcastChatJson(obj)
    }

    fun broadcastRecall(msgId: String) {
        broadcastChatJson(buildJsonObject {
            put("kind", "recall")
            put("msgId", msgId)
        })
    }

    fun broadcastTyping(isTyping: Boolean, mode: String = "text") {
        broadcastChatJson(buildJsonObject {
            put("kind", "typing")
            put("isTyping", isTyping)
            put("mode", mode)
        })
    }

    fun broadcastReaction(msgId: String, emoji: String) {
        broadcastChatJson(buildJsonObject {
            put("kind", "reaction")
            put("msgId", msgId)
            put("emoji", emoji)
        })
    }

    // 广播 P2P「拍一拍」提示（仅在房间直连成员间瞬时透传，不落服务端历史缓存）
    fun broadcastNudge(
        id: String,
        targetPeerId: String,
        targetName: String,
        fromName: String,
        timestamp: Long,
    ): Int {
        return broadcastChatJson(buildJsonObject {
            put("kind", "nudge")
            put("id", id)
            put("targetPeerId", targetPeerId)
            put("targetName", targetName)
            put("fromName", fromName)
            put("timestamp", timestamp)
        })
    }

    fun broadcastClipboard(text: String, updatedAt: Long = System.currentTimeMillis()): Int {
        return broadcastChatJson(buildJsonObject {
            put("kind", "clip-sync")
            put("text", text)
            put("updatedAt", updatedAt)
        })
    }

    fun broadcastRoomMoved(newCode: String, password: String = ""): Int {
        return broadcastChatJson(buildJsonObject {
            put("kind", "room-moved")
            put("type", "room-moved")
            put("code", newCode)
            put("newCode", newCode)
            if (password.isNotBlank()) put("password", password)
        })
    }

    fun broadcastResumeRequest(resumeKey: String, fileName: String, fileSize: Long, offset: Long = 0L): Int {
        return broadcastChatJson(buildJsonObject {
            put("kind", "resume-request")
            put("type", "resume-request")
            put("resumeKey", resumeKey)
            put("fileName", fileName)
            put("size", fileSize)
            put("fileSize", fileSize)
            put("offset", offset)
        })
    }

    fun sendResumeUnavailable(peerId: String, resumeKey: String, fileName: String, reason: String) {
        val session = sessions[peerId] ?: return
        sendChatJson(session, buildJsonObject {
            put("kind", "resume-unavailable")
            put("type", "resume-unavailable")
            put("resumeKey", resumeKey)
            put("fileName", fileName)
            put("reason", reason)
        })
    }

    fun sendFileToAll(source: OutgoingFileSource): List<FileTransferInfo> {
        val readySessions = sessions.values.filter { it.status.dataReady && !callbacks.isPeerBlocked(it.peerId) }
        if (readySessions.isEmpty()) return emptyList()

        // B4：普通大文件在发 offer 前计算头尾 1MiB 指纹 resumeKey
        val resolvedResumeKey = source.resumeKey ?: if (!source.isImage && !source.isAudio && source.fileSize > 0L) {
            runCatching {
                when {
                    source.bytes != null -> TransferResumeUtils.computeResumeKeyFromBytes(source.fileName, source.bytes)
                    source.uri != null -> MediaDownloadHelper.computeResumeKey(appContext, source.uri, source.fileName, source.fileSize)
                    else -> null
                }
            }.getOrNull()
        } else {
            null
        }
        val enrichedSource = source.copy(resumeKey = resolvedResumeKey)

        val created = mutableListOf<FileTransferInfo>()
        readySessions.forEach { session ->
            val streamId = nextStreamId.getAndIncrement()
            val transferId = "${enrichedSource.transferIdPrefix}_${session.peerId}"
            val info = FileTransferInfo(
                transferId = transferId,
                streamId = streamId,
                fileName = enrichedSource.fileName,
                fileSize = enrichedSource.fileSize,
                mimeType = enrichedSource.mimeType.ifBlank { "application/octet-stream" },
                isImage = enrichedSource.isImage,
                isAudio = enrichedSource.isAudio,
                direction = TransferDirection.SEND,
                targetPeerId = session.peerId,
                status = TransferStatus.WAITING_ACCEPT,
                resumeKey = resolvedResumeKey,
                recipientsTotal = readySessions.size,
            )
            session.outgoing[transferId] = OutgoingTransfer(
                transferId = transferId,
                streamId = streamId,
                source = enrichedSource,
                info = info,
            )
            sendChatJson(
                session,
                buildJsonObject {
                    put("kind", "file-offer")
                    put("msgId", enrichedSource.transferIdPrefix)
                    put("transferId", transferId)
                    put("streamId", streamId)
                    put("fileName", info.fileName)
                    put("fileSize", info.fileSize)
                    put("mimeType", info.mimeType)
                    put("isImage", info.isImage)
                    if (info.isAudio) put("isAudio", true)
                    resolvedResumeKey?.let { put("resumeKey", it) }
                    put("hashScope", "full")
                    enrichedSource.caption?.let { put("caption", it) }
                    put("timestamp", enrichedSource.timestamp)
                }
            )
            created.add(info)
        }
        return created
    }

    private fun transferCacheDir(): File {
        val dir = Companion.transferCacheDir(appContext)
        val expireAt = System.currentTimeMillis() - PART_EXPIRE_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".part") && f.lastModified() < expireAt) {
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    callbacks.onPartExpired(f.name)
                }
            }
        }
        return dir
    }

    private fun resumePartFile(fileName: String, fileSize: Long, resumeKey: String? = null): File {
        return File(transferCacheDir(), Companion.resumePartFileName(fileName, fileSize, resumeKey))
    }

    fun acceptIncomingTransfer(peerId: String, transferId: String, resumeIfAvailable: Boolean = true): Boolean {
        val session = sessions[peerId] ?: return false
        if (!session.status.dataReady) return false
        val inc = session.incomingById[transferId] ?: return false
        if (inc.accepted || inc.cancelled) return false

        val isResumableFile = !inc.info.isImage && !inc.info.isAudio && inc.info.fileSize > 0L
        val tmp = if (isResumableFile) {
            resumePartFile(inc.info.fileName, inc.info.fileSize, inc.info.resumeKey)
        } else {
            File(transferCacheDir(), "${transferId.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.part")
        }

        val existingLen = if (isResumableFile && resumeIfAvailable && tmp.exists()) tmp.length() else 0L
        val resumeOffset = if (existingLen in 1L until inc.info.fileSize) existingLen else 0L
        if (resumeOffset == 0L && tmp.exists()) {
            runCatching { tmp.delete() }
        }

        // B4：断点续传时先将已有 [0, resumeOffset) 字节流式喂入 fullSha，再接续写入剩余字节
        if (resumeOffset > 0L) {
            val preloadOk = runCatching {
                FileInputStream(tmp).use { fis ->
                    val buf = ByteArray(64 * 1024)
                    var remaining = resumeOffset
                    while (remaining > 0L) {
                        val toRead = minOf(remaining, buf.size.toLong()).toInt()
                        val r = fis.read(buf, 0, toRead)
                        if (r <= 0) break
                        inc.fullSha.update(buf, 0, r)
                        remaining -= r
                    }
                    remaining == 0L
                }
            }.getOrDefault(false)
            if (!preloadOk) {
                runCatching { tmp.delete() }
                inc.fullSha.reset()
            }
        }

        val effectiveOffset = if (tmp.exists() && resumeOffset > 0L) resumeOffset else 0L
        inc.tempFile = tmp
        inc.outputStream = BufferedOutputStream(FileOutputStream(tmp, effectiveOffset > 0L), 64 * 1024)
        inc.accepted = true
        inc.lastReportAt = SystemClock.elapsedRealtime()
        inc.lastReportBytes = effectiveOffset
        inc.info = inc.info.copy(
            status = TransferStatus.TRANSFERRING,
            transferredBytes = effectiveOffset,
            resumedFromBytes = effectiveOffset,
        )
        callbacks.onTransferUpdate(inc.info)

        val ok = sendChatJson(
            session,
            buildJsonObject {
                put("kind", "file-accept")
                put("transferId", transferId)
                if (effectiveOffset > 0L) put("offset", effectiveOffset)
            }
        )
        if (!ok) {
            inc.cancelled = true
            runCatching { inc.outputStream?.close() }
            inc.outputStream = null
            inc.info = inc.info.copy(status = TransferStatus.ERROR, errorMessage = "数据通道已断开，无法接收")
            callbacks.onTransferUpdate(inc.info)
            session.incomingById.remove(transferId)
            session.incomingByStream.remove(inc.streamId)
            return false
        }
        return true
    }

    fun rejectIncomingTransfer(peerId: String, transferId: String) {
        val session = sessions[peerId] ?: return
        val inc = session.incomingById.remove(transferId) ?: return
        session.incomingByStream.remove(inc.streamId)
        inc.cancelled = true
        inc.info = inc.info.copy(status = TransferStatus.REJECTED)
        callbacks.onTransferUpdate(inc.info)
        sendChatJson(
            session,
            buildJsonObject {
                put("kind", "file-reject")
                put("transferId", transferId)
            }
        )
    }

    fun cancelTransfer(peerId: String, transferId: String) {
        val session = sessions[peerId] ?: return
        session.outgoing.remove(transferId)?.let { out ->
            out.cancelled = true
            out.info = out.info.copy(status = TransferStatus.CANCELLED)
            callbacks.onTransferUpdate(out.info)
            sendChatJson(session, buildJsonObject {
                put("kind", "file-cancel")
                put("transferId", transferId)
            })
            return
        }
        session.incomingById.remove(transferId)?.let { inc ->
            session.incomingByStream.remove(inc.streamId)
            inc.cancelled = true
            runCatching { inc.outputStream?.close() }
            runCatching { inc.tempFile?.delete() }
            inc.info = inc.info.copy(status = TransferStatus.CANCELLED)
            callbacks.onTransferUpdate(inc.info)
            sendChatJson(session, buildJsonObject {
                put("kind", "file-cancel")
                put("transferId", transferId)
            })
        }
    }

    private fun bindDataChannel(session: PeerSession, ch: DataChannel) {
        when (ch.label()) {
            "chat" -> {
                session.chatChannel = ch
                ch.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit
                    override fun onStateChange() {
                        refreshDataReady(session)
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (!buffer.binary) {
                            val bytes = ByteArray(buffer.data.remaining())
                            buffer.data.get(bytes)
                            handleChatPacket(session, String(bytes, Charsets.UTF_8))
                        }
                    }
                })
                refreshDataReady(session)
            }

            "file" -> {
                session.fileChannel = ch
                ch.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {
                        if (ch.bufferedAmount() <= LOW_WATER_MARK) {
                            session.bufferedLowWaiter?.complete(Unit)
                        }
                    }
                    override fun onStateChange() {
                        if (ch.state() != DataChannel.State.OPEN) {
                            session.bufferedLowWaiter?.complete(Unit)
                        }
                        refreshDataReady(session)
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        if (buffer.binary) {
                            val raw = ByteArray(buffer.data.remaining())
                            buffer.data.get(raw)
                            session.receiveExecutor.execute {
                                handleFileBinaryFrame(session, raw)
                            }
                        }
                    }
                })
                refreshDataReady(session)
            }
        }
    }

    private fun refreshDataReady(session: PeerSession) {
        val ready = session.chatChannel?.state() == DataChannel.State.OPEN &&
            session.fileChannel?.state() == DataChannel.State.OPEN
        session.status = session.status.copy(dataReady = ready)
        if (ready) {
            probeLinkStats(session)
        } else {
            callbacks.onLinkStatusChange(session.status)
        }
    }

    private fun broadcastChatJson(obj: JsonObject): Int {
        var sent = 0
        sessions.values.forEach { session ->
            if (!callbacks.isPeerBlocked(session.peerId) && sendChatJson(session, obj)) sent++
        }
        return sent
    }

    private fun sendChatJson(session: PeerSession, obj: JsonObject): Boolean {
        val ch = session.chatChannel ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        val bytes = obj.toString().toByteArray(Charsets.UTF_8)
        return runCatching {
            ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        }.getOrDefault(false)
    }

    private fun handleChatPacket(session: PeerSession, raw: String) {
        if (callbacks.isPeerBlocked(session.peerId)) return
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        when (obj["kind"]?.jsonPrimitive?.content) {
            "text" -> {
                val id = obj["id"]?.jsonPrimitive?.content ?: return
                val text = FormatUtils.decodeLegacySecret(obj["text"]?.jsonPrimitive?.content)
                val ts = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()
                val replyTo = obj["replyTo"]?.let {
                    runCatching { json.decodeFromJsonElement(ReplyQuote.serializer(), it) }.getOrNull()
                }
                callbacks.onTextMessage(session.peerId, id, text, replyTo, ts)
            }

            "recall" -> {
                val msgId = obj["msgId"]?.jsonPrimitive?.content ?: return
                callbacks.onRecallMessage(session.peerId, msgId)
            }

            "typing" -> {
                val isTyping = obj["isTyping"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val mode = obj["mode"]?.jsonPrimitive?.content?.takeIf { it == "voice" || it == "text" } ?: "text"
                callbacks.onTyping(session.peerId, isTyping, mode)
            }

            "reaction" -> {
                val msgId = obj["msgId"]?.jsonPrimitive?.content ?: return
                val emoji = obj["emoji"]?.jsonPrimitive?.content ?: return
                callbacks.onReaction(session.peerId, msgId, emoji)
            }

            "nudge" -> {
                val id = obj["id"]?.jsonPrimitive?.content ?: return
                val targetPeerId = obj["targetPeerId"]?.jsonPrimitive?.content ?: return
                val targetName = obj["targetName"]?.jsonPrimitive?.content.orEmpty()
                val fromName = obj["fromName"]?.jsonPrimitive?.content.orEmpty()
                val timestamp = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: System.currentTimeMillis()
                callbacks.onNudge(session.peerId, id, targetPeerId, targetName, fromName, timestamp)
            }

            "clip-sync" -> {
                val text = obj["text"]?.jsonPrimitive?.content ?: return
                val updatedAt = obj["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()
                callbacks.onClipboardSync(session.peerId, text, updatedAt)
            }

            "room-moved" -> {
                val newCode = (obj["code"] ?: obj["newCode"])?.jsonPrimitive?.content ?: return
                val password = obj["password"]?.jsonPrimitive?.content.orEmpty()
                callbacks.onRoomMoved(session.peerId, newCode, password)
            }

            "resume-request" -> {
                val resumeKey = obj["resumeKey"]?.jsonPrimitive?.content.orEmpty()
                val fileName = obj["fileName"]?.jsonPrimitive?.content.orEmpty()
                val fileSize = (obj["size"] ?: obj["fileSize"])?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                callbacks.onResumeRequested(session.peerId, resumeKey, fileName, fileSize)
            }

            "resume-unavailable" -> {
                val resumeKey = obj["resumeKey"]?.jsonPrimitive?.content.orEmpty()
                val fileName = obj["fileName"]?.jsonPrimitive?.content.orEmpty()
                val reason = obj["reason"]?.jsonPrimitive?.content ?: "发送方已无法恢复该文件传输"
                callbacks.onResumeUnavailable(session.peerId, resumeKey, fileName, reason)
            }

            "file-offer" -> {
                val transferId = obj["transferId"]?.jsonPrimitive?.content ?: return
                if (callbacks.isPeerBlocked(session.peerId)) {
                    sendChatJson(session, buildJsonObject {
                        put("kind", "file-reject")
                        put("transferId", transferId)
                    })
                    return
                }
                val streamId = obj["streamId"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                val fileName = obj["fileName"]?.jsonPrimitive?.content ?: "unnamed"
                val fileSize = obj["fileSize"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: "application/octet-stream"
                val offeredIsImage = obj["isImage"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val offeredIsAudio = obj["isAudio"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val isScreenshot = obj["isScreenshot"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val caption = obj["caption"]?.jsonPrimitive?.content
                val msgId = obj["msgId"]?.jsonPrimitive?.content ?: "tr_$transferId"
                val ts = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()
                val resumeKey = obj["resumeKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val offerHashScope = obj["hashScope"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

                // B2：接收端根据 MIME 白名单与扩展名严格重新判定图片与音频，杜绝伪造绕过自动接收
                val effectiveIsImage = offeredIsImage && TransferResumeUtils.isTrustedImage(fileName, mimeType)
                val effectiveIsAudio = offeredIsAudio && TransferResumeUtils.isTrustedAudio(fileName, mimeType)

                val resumableBytes = if (!effectiveIsImage && !effectiveIsAudio && fileSize > 0L) {
                    val part = resumePartFile(fileName, fileSize, resumeKey)
                    val len = if (part.exists()) part.length() else 0L
                    if (len in 1L until fileSize) len else 0L
                } else 0L

                val info = FileTransferInfo(
                    transferId = transferId,
                    streamId = streamId,
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    isImage = effectiveIsImage,
                    isAudio = effectiveIsAudio,
                    isScreenshot = effectiveIsImage && isScreenshot,
                    direction = TransferDirection.RECEIVE,
                    targetPeerId = session.peerId,
                    status = TransferStatus.OFFERING,
                    canResumeBytes = resumableBytes,
                    resumeKey = resumeKey,
                )
                val inc = IncomingTransfer(
                    transferId = transferId,
                    streamId = streamId,
                    peerId = session.peerId,
                    info = info,
                    hashScope = offerHashScope,
                )
                session.incomingById[transferId] = inc
                session.incomingByStream[streamId] = inc
                callbacks.onFileOffer(session.peerId, info, msgId, caption, ts)

                if (callbacks.shouldAutoAccept(info)) {
                    acceptIncomingTransfer(session.peerId, transferId, resumeIfAvailable = true)
                }
            }

            "file-done" -> {
                val transferId = obj["transferId"]?.jsonPrimitive?.content ?: return
                val inc = session.incomingById[transferId] ?: return
                inc.hashScope = obj["hashScope"]?.jsonPrimitive?.content
                inc.expectedSha256Full = obj["sha256Full"]?.jsonPrimitive?.content
            }

            "file-accept" -> {
                val transferId = obj["transferId"]?.jsonPrimitive?.content ?: return
                val out = session.outgoing[transferId]
                if (out == null) {
                    sendChatJson(session, buildJsonObject {
                        put("kind", "file-cancel")
                        put("transferId", transferId)
                    })
                    return
                }
                if (!out.started && !out.cancelled) {
                    out.started = true
                    val reqOffset = obj["offset"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                    val startOffset = if (reqOffset in 1L until out.info.fileSize) reqOffset else 0L
                    scope.launch { pumpOutgoingFile(session, out, startOffset) }
                }
            }

            "file-reject" -> {
                val transferId = obj["transferId"]?.jsonPrimitive?.content ?: return
                session.outgoing.remove(transferId)?.let { out ->
                    out.cancelled = true
                    out.info = out.info.copy(status = TransferStatus.REJECTED)
                    callbacks.onTransferUpdate(out.info)
                }
            }

            "file-cancel" -> {
                val transferId = obj["transferId"]?.jsonPrimitive?.content ?: return
                session.outgoing.remove(transferId)?.let { out ->
                    out.cancelled = true
                    out.info = out.info.copy(status = TransferStatus.CANCELLED)
                    callbacks.onTransferUpdate(out.info)
                }
                session.incomingById.remove(transferId)?.let { inc ->
                    session.incomingByStream.remove(inc.streamId)
                    inc.cancelled = true
                    runCatching { inc.outputStream?.close() }
                    runCatching { inc.tempFile?.delete() }
                    inc.info = inc.info.copy(status = TransferStatus.CANCELLED)
                    callbacks.onTransferUpdate(inc.info)
                }
            }

            "file-ack" -> {
                val transferId = obj["transferId"]?.jsonPrimitive?.content ?: return
                val verified = obj["verified"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val sha256 = obj["sha256"]?.jsonPrimitive?.content
                session.outgoing.remove(transferId)?.let { out ->
                    out.info = out.info.copy(
                        status = TransferStatus.COMPLETED,
                        verified = verified,
                        sha256 = sha256,
                        transferredBytes = out.info.fileSize,
                        etaSeconds = 0.0,
                    )
                    callbacks.onTransferUpdate(out.info)
                }
            }
        }
    }

    // B4：流式分片发送，续传时先读取 [0, startOffset) 喂入 SHA-256 保证整文件全量校验一致
    private suspend fun pumpOutgoingFile(session: PeerSession, out: OutgoingTransfer, startOffset: Long = 0L) {
        val ch = session.fileChannel
        if (ch == null || ch.state() != DataChannel.State.OPEN) {
            out.info = out.info.copy(status = TransferStatus.ERROR, errorMessage = "P2P 数据通道未就绪")
            callbacks.onTransferUpdate(out.info)
            return
        }

        val sha = MessageDigest.getInstance("SHA-256")
        var sentBytes = startOffset
        var chunkIndex = 0
        val totalSize = out.source.fileSize
        var lastReportAt = SystemClock.elapsedRealtime()
        var lastReportBytes = startOffset

        out.info = out.info.copy(
            status = TransferStatus.TRANSFERRING,
            transferredBytes = startOffset,
            resumedFromBytes = startOffset,
        )
        callbacks.onTransferUpdate(out.info)

        try {
            val inputStream = when {
                out.source.bytes != null -> out.source.bytes.inputStream()
                out.source.uri != null -> appContext.contentResolver.openInputStream(out.source.uri)
                else -> null
            } ?: throw IllegalStateException("无法读取所选文件")

            inputStream.use { stream ->
                // 若存在断点偏移，完整读取 [0, startOffset) 喂入 MessageDigest，保证全量 SHA-256 校验
                var remainingPrehash = startOffset
                val prehashBuf = ByteArray(64 * 1024)
                while (remainingPrehash > 0L) {
                    val toRead = minOf(remainingPrehash, prehashBuf.size.toLong()).toInt()
                    val read = stream.read(prehashBuf, 0, toRead)
                    if (read <= 0) break
                    sha.update(prehashBuf, 0, read)
                    remainingPrehash -= read.toLong()
                }

                val readBuf = ByteArray(CHUNK_SIZE)
                while (true) {
                    if (out.cancelled) return
                    if (ch.state() != DataChannel.State.OPEN) {
                        throw IllegalStateException("P2P 传输通道已断开")
                    }

                    val readLen = stream.read(readBuf)
                    if (readLen <= 0) break

                    if (ch.bufferedAmount() > HIGH_WATER_MARK) {
                        waitForBufferedLow(session, ch)
                        if (out.cancelled) return
                    }

                    sha.update(readBuf, 0, readLen)
                    val frame = ByteBuffer.allocate(8 + readLen).order(ByteOrder.BIG_ENDIAN)
                    frame.putInt(out.streamId)
                    frame.putInt(chunkIndex)
                    frame.put(readBuf, 0, readLen)
                    frame.flip()

                    if (!ch.send(DataChannel.Buffer(frame, true))) {
                        throw IllegalStateException("数据分片发送失败")
                    }

                    sentBytes += readLen
                    chunkIndex++

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReportAt >= 200L || sentBytes >= totalSize) {
                        val dt = ((now - lastReportAt) / 1000.0).coerceAtLeast(0.05)
                        val speed = (sentBytes - lastReportBytes) / dt
                        val eta = if (speed > 0) ((totalSize - sentBytes).coerceAtLeast(0) / speed) else 0.0
                        out.info = out.info.copy(
                            transferredBytes = sentBytes,
                            speedBps = speed,
                            etaSeconds = eta,
                        )
                        callbacks.onTransferUpdate(out.info)
                        lastReportAt = now
                        lastReportBytes = sentBytes
                    }
                }
            }

            // 先发 file-done 宣告全量哈希与 hashScope，再发二进制 EOF 尾帧
            val digest = sha.digest()
            val fullHex = FormatUtils.bytesToHex(digest)
            sendChatJson(
                session,
                buildJsonObject {
                    put("kind", "file-done")
                    put("transferId", out.transferId)
                    put("sha256Full", fullHex)
                    put("hashScope", "full")
                }
            )

            val eof = ByteBuffer.allocate(8 + 32).order(ByteOrder.BIG_ENDIAN)
            eof.putInt(out.streamId)
            eof.putInt(EOF_CHUNK_INDEX)
            eof.put(digest)
            eof.flip()

            if (ch.bufferedAmount() > HIGH_WATER_MARK) {
                waitForBufferedLow(session, ch)
            }
            if (out.cancelled || ch.state() != DataChannel.State.OPEN) return
            ch.send(DataChannel.Buffer(eof, true))

            out.info = out.info.copy(
                sha256 = fullHex,
                transferredBytes = totalSize,
                etaSeconds = 0.0,
            )
            callbacks.onTransferUpdate(out.info)
        } catch (e: Exception) {
            if (!out.cancelled) {
                out.info = out.info.copy(
                    status = TransferStatus.ERROR,
                    errorMessage = e.message ?: "发送中断",
                )
                callbacks.onTransferUpdate(out.info)
            }
        }
    }

    private suspend fun waitForBufferedLow(session: PeerSession, ch: DataChannel) {
        while (ch.bufferedAmount() > LOW_WATER_MARK && ch.state() == DataChannel.State.OPEN) {
            val waiter = CompletableDeferred<Unit>()
            session.bufferedLowWaiter = waiter
            if (ch.bufferedAmount() <= LOW_WATER_MARK) return
            kotlinx.coroutines.withTimeoutOrNull(80L) { waiter.await() }
        }
    }

    // 串行接收二进制帧并流式落盘 + 全量 SHA-256 校验（向后兼容旧版无 hashScope 协议）
    private fun handleFileBinaryFrame(session: PeerSession, raw: ByteArray) {
        if (raw.size < 8) return
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val streamId = buf.int
        val chunkIndex = buf.int

        val inc = session.incomingByStream[streamId] ?: return
        if (inc.cancelled) return

        if (chunkIndex == EOF_CHUNK_INDEX) {
            val eofHex = if (raw.size >= 40) {
                FormatUtils.bytesToHex(raw.copyOfRange(8, 40))
            } else {
                ""
            }
            val fullHex = FormatUtils.bytesToHex(inc.fullSha.digest())
            val tailHex = FormatUtils.bytesToHex(inc.partialSha.digest())
            val expectedHex = inc.expectedSha256Full?.takeIf { it.isNotBlank() } ?: eofHex
            val verified = when {
                expectedHex.isEmpty() -> false
                inc.hashScope == "full" -> expectedHex.equals(fullHex, ignoreCase = true)
                inc.info.resumedFromBytes <= 0L -> expectedHex.equals(fullHex, ignoreCase = true)
                // 旧版对端未携带 hashScope 且为断点续传时兼容尾段哈希
                else -> expectedHex.equals(fullHex, ignoreCase = true) || expectedHex.equals(tailHex, ignoreCase = true)
            }

            try {
                inc.outputStream?.flush()
                inc.outputStream?.close()
                inc.outputStream = null

                val tmpFile = inc.tempFile ?: throw IllegalStateException("临时文件丢失")
                val savedResult = MediaDownloadHelper.saveReceivedFile(
                    context = appContext,
                    sourceFile = tmpFile,
                    fileName = inc.info.fileName,
                    mimeType = inc.info.mimeType,
                    keepLocalPreviewCopy = inc.info.isImage || inc.info.isAudio,
                    isExplicitAudio = inc.info.isAudio,
                )

                inc.info = inc.info.copy(
                    status = TransferStatus.COMPLETED,
                    transferredBytes = inc.info.fileSize,
                    sha256 = fullHex,
                    verified = verified,
                    etaSeconds = 0.0,
                    localPath = savedResult.previewPath,
                    contentUri = savedResult.contentUri?.toString(),
                    savedDisplayPath = savedResult.displayPath,
                )
                callbacks.onTransferUpdate(inc.info)

                sendChatJson(
                    session,
                    buildJsonObject {
                        put("kind", "file-ack")
                        put("transferId", inc.transferId)
                        put("verified", verified)
                        put("sha256", fullHex)
                    }
                )
            } catch (e: Exception) {
                inc.info = inc.info.copy(
                    status = TransferStatus.ERROR,
                    errorMessage = e.message ?: "保存文件失败",
                )
                callbacks.onTransferUpdate(inc.info)
            } finally {
                session.incomingById.remove(inc.transferId)
                session.incomingByStream.remove(streamId)
            }
            return
        }

        val payloadLen = raw.size - 8
        if (payloadLen <= 0) return

        try {
            inc.fullSha.update(raw, 8, payloadLen)
            inc.partialSha.update(raw, 8, payloadLen)
            inc.outputStream?.write(raw, 8, payloadLen)
        } catch (e: Exception) {
            inc.cancelled = true
            runCatching { inc.outputStream?.close() }
            runCatching { inc.tempFile?.delete() }
            inc.info = inc.info.copy(
                status = TransferStatus.ERROR,
                errorMessage = e.message ?: "写入存储失败",
            )
            callbacks.onTransferUpdate(inc.info)
            session.incomingById.remove(inc.transferId)
            session.incomingByStream.remove(streamId)
            sendChatJson(session, buildJsonObject {
                put("kind", "file-cancel")
                put("transferId", inc.transferId)
            })
            return
        }

        val newBytes = inc.info.transferredBytes + payloadLen
        val now = SystemClock.elapsedRealtime()
        if (now - inc.lastReportAt >= 200L || newBytes >= inc.info.fileSize) {
            val dt = ((now - inc.lastReportAt) / 1000.0).coerceAtLeast(0.05)
            val speed = (newBytes - inc.lastReportBytes) / dt
            val eta = if (speed > 0) ((inc.info.fileSize - newBytes).coerceAtLeast(0) / speed) else 0.0
            inc.info = inc.info.copy(
                transferredBytes = newBytes,
                speedBps = speed,
                etaSeconds = eta,
            )
            callbacks.onTransferUpdate(inc.info)
            inc.lastReportAt = now
            inc.lastReportBytes = newBytes
        } else {
            inc.info = inc.info.copy(transferredBytes = newBytes)
        }
    }

    private fun probeLinkStats(session: PeerSession) {
        runCatching {
            session.pc.getStats { report ->
                val candidates = mutableMapOf<String, Map<String, Any>>()
                var selectedPair: Map<String, Any>? = null

                report.statsMap.values.forEach { stat ->
                    if (stat.type == "local-candidate" || stat.type == "remote-candidate") {
                        candidates[stat.id] = stat.members
                    } else if (stat.type == "candidate-pair") {
                        val nominated = stat.members["nominated"] as? Boolean == true
                        val state = stat.members["state"] as? String
                        val selected = stat.members["selected"] as? Boolean == true
                        if (selected || (state == "succeeded" && nominated)) {
                            selectedPair = stat.members
                        }
                    }
                }

                val pair = selectedPair ?: return@getStats
                val localType = candidates[pair["localCandidateId"]?.toString()]?.get("candidateType")?.toString().orEmpty()
                val remoteType = candidates[pair["remoteCandidateId"]?.toString()]?.get("candidateType")?.toString().orEmpty()

                val transport = when {
                    localType == "relay" || remoteType == "relay" -> LinkTransportType.RELAY_TURN
                    localType == "host" && remoteType == "host" -> LinkTransportType.P2P_LAN
                    else -> LinkTransportType.P2P_WAN
                }
                val rttSec = (pair["currentRoundTripTime"] as? Number)?.toDouble()
                val rttMs = if (rttSec != null && rttSec >= 0.0) (rttSec * 1000.0).toInt() else null

                session.status = session.status.copy(transport = transport, rttMs = rttMs)
                callbacks.onLinkStatusChange(session.status)
            }
        }
    }

    private fun buildRtcConfig(servers: List<PeerConnection.IceServer>): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceCandidatePoolSize = 4
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
    }

    private fun convertIceServers(configs: List<IceServerConfig>): List<PeerConnection.IceServer> {
        val result = mutableListOf<PeerConnection.IceServer>()
        configs.forEach { cfg ->
            val urlList = when (val elem = cfg.urls) {
                is JsonPrimitive -> listOf(elem.content)
                is JsonArray -> elem.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }.filter { it.isNotBlank() }

            if (urlList.isNotEmpty()) {
                val builder = PeerConnection.IceServer.builder(urlList)
                if (!cfg.username.isNullOrBlank()) builder.setUsername(cfg.username)
                if (!cfg.credential.isNullOrBlank()) builder.setPassword(cfg.credential)
                result.add(builder.createIceServer())
            }
        }
        if (result.isEmpty()) {
            result.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        return result
    }

    private suspend fun PeerConnection.createOfferAwait(): SessionDescription? {
        val deferred = CompletableDeferred<SessionDescription?>()
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) { deferred.complete(desc) }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) { deferred.complete(null) }
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
        return deferred.await()
    }

    private suspend fun PeerConnection.createAnswerAwait(): SessionDescription? {
        val deferred = CompletableDeferred<SessionDescription?>()
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) { deferred.complete(desc) }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String?) { deferred.complete(null) }
            override fun onSetFailure(error: String?) = Unit
        }, MediaConstraints())
        return deferred.await()
    }

    private suspend fun PeerConnection.setLocalDescriptionAwait(desc: SessionDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onSetSuccess() { deferred.complete(true) }
            override fun onCreateFailure(p0: String?) = Unit
            override fun onSetFailure(p0: String?) { deferred.complete(false) }
        }, desc)
        return deferred.await()
    }

    private suspend fun PeerConnection.setRemoteDescriptionAwait(desc: SessionDescription): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onSetSuccess() { deferred.complete(true) }
            override fun onCreateFailure(p0: String?) = Unit
            override fun onSetFailure(p0: String?) { deferred.complete(false) }
        }, desc)
        return deferred.await()
    }
}
