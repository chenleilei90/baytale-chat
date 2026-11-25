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
    fun onTyping(peerId: String, isTyping: Boolean)
    fun onReaction(peerId: String, msgId: String, emoji: String)
    fun onFileOffer(peerId: String, info: FileTransferInfo, msgId: String, caption: String?, timestamp: Long)
    fun onTransferUpdate(info: FileTransferInfo)
    fun shouldAutoAccept(info: FileTransferInfo): Boolean
}

data class OutgoingFileSource(
    val uri: Uri? = null,
    val bytes: ByteArray? = null,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val isImage: Boolean,
    val caption: String? = null,
    val transferIdPrefix: String,
    val timestamp: Long,
)

// 核心原生 WebRTC Mesh P2P 引擎：与 Web 端完全兼容 Perfect Negotiation + chat/file 双 DataChannel + 48KB 背压流式传输 + SHA-256 校验
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

        private val factoryInitialized = AtomicBoolean(false)

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
        val sha: MessageDigest = MessageDigest.getInstance("SHA-256"),
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

    fun syncPeers(activePeerIds: List<String>) {
        val activeSet = activePeerIds.toSet()
        sessions.keys.toList().forEach { existingId ->
            if (existingId !in activeSet) {
                removePeer(existingId)
            }
        }
        activePeerIds.forEach { ensurePeer(it) }
    }

    fun ensurePeer(remotePeerId: String, forceReset: Boolean = false) {
        if (remotePeerId.isBlank() || remotePeerId == selfPeerId) return
        val existing = sessions[remotePeerId]
        if (existing != null) {
            val state = existing.pc.iceConnectionState()
            val dead = state == PeerConnection.IceConnectionState.FAILED ||
                state == PeerConnection.IceConnectionState.CLOSED
            if (!forceReset && !dead) return
            removePeer(remotePeerId)
        }

        // 与 Web 端完全一致的字典序礼貌端规则，消除双端 Offer 竞态冲突
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
        if (fromId == selfPeerId) return

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

                        // 若礼貌端处于 have-local-offer 碰撞状态，先回滚本地提议再接受远端 Offer
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
        ensurePeer(remotePeerId, forceReset = true)
    }

    fun removePeer(remotePeerId: String) {
        val session = sessions.remove(remotePeerId) ?: return
        session.statsJob?.cancel()
        session.bufferedLowWaiter?.complete(Unit)
        session.outgoing.values.forEach { it.cancelled = true }
        session.incomingById.values.forEach { inc ->
            inc.cancelled = true
            runCatching { inc.outputStream?.close() }
            runCatching { inc.tempFile?.delete() }
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

    fun broadcastTyping(isTyping: Boolean) {
        broadcastChatJson(buildJsonObject {
            put("kind", "typing")
            put("isTyping", isTyping)
        })
    }

    fun broadcastReaction(msgId: String, emoji: String) {
        broadcastChatJson(buildJsonObject {
            put("kind", "reaction")
            put("msgId", msgId)
            put("emoji", emoji)
        })
    }

    fun sendFileToAll(source: OutgoingFileSource): List<FileTransferInfo> {
        val created = mutableListOf<FileTransferInfo>()
        sessions.values.forEach { session ->
            if (!session.status.dataReady) return@forEach
            val streamId = nextStreamId.getAndIncrement()
            val transferId = "${source.transferIdPrefix}_${session.peerId}"
            val info = FileTransferInfo(
                transferId = transferId,
                streamId = streamId,
                fileName = source.fileName,
                fileSize = source.fileSize,
                mimeType = source.mimeType.ifBlank { "application/octet-stream" },
                isImage = source.isImage,
                direction = TransferDirection.SEND,
                targetPeerId = session.peerId,
                status = TransferStatus.WAITING_ACCEPT,
            )
            session.outgoing[transferId] = OutgoingTransfer(
                transferId = transferId,
                streamId = streamId,
                source = source,
                info = info,
            )
            sendChatJson(
                session,
                buildJsonObject {
                    put("kind", "file-offer")
                    put("msgId", source.transferIdPrefix)
                    put("transferId", transferId)
                    put("streamId", streamId)
                    put("fileName", info.fileName)
                    put("fileSize", info.fileSize)
                    put("mimeType", info.mimeType)
                    put("isImage", info.isImage)
                    source.caption?.let { put("caption", it) }
                    put("timestamp", source.timestamp)
                }
            )
            created.add(info)
        }
        return created
    }

    fun acceptIncomingTransfer(peerId: String, transferId: String) {
        val session = sessions[peerId] ?: return
        val inc = session.incomingById[transferId] ?: return
        if (inc.accepted || inc.cancelled) return

        val transferDir = File(appContext.cacheDir, "baychat_transfers").apply { mkdirs() }
        val tmp = File(transferDir, "${transferId.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.part")
        inc.tempFile = tmp
        inc.outputStream = BufferedOutputStream(FileOutputStream(tmp), 64 * 1024)
        inc.accepted = true
        inc.lastReportAt = SystemClock.elapsedRealtime()
        inc.lastReportBytes = 0L
        inc.info = inc.info.copy(status = TransferStatus.TRANSFERRING)
        callbacks.onTransferUpdate(inc.info)

        sendChatJson(
            session,
            buildJsonObject {
                put("kind", "file-accept")
                put("transferId", transferId)
            }
        )
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
            if (sendChatJson(session, obj)) sent++
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
                callbacks.onTyping(session.peerId, isTyping)
            }

            "reaction" -> {
                val msgId = obj["msgId"]?.jsonPrimitive?.content ?: return
                val emoji = obj["emoji"]?.jsonPrimitive?.content ?: return
                callbacks.onReaction(session.peerId, msgId, emoji)
            }

            "file-offer" -> {
                val transferId = obj["transferId"]?.jsonPrimitive?.content ?: return
                val streamId = obj["streamId"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                val fileName = obj["fileName"]?.jsonPrimitive?.content ?: "unnamed"
                val fileSize = obj["fileSize"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: "application/octet-stream"
                val isImage = obj["isImage"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val isAudio = obj["isAudio"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val isScreenshot = obj["isScreenshot"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val caption = obj["caption"]?.jsonPrimitive?.content
                val msgId = obj["msgId"]?.jsonPrimitive?.content ?: "tr_$transferId"
                val ts = obj["timestamp"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()

                val info = FileTransferInfo(
                    transferId = transferId,
                    streamId = streamId,
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    isImage = isImage,
                    isAudio = isAudio,
                    isScreenshot = isScreenshot,
                    direction = TransferDirection.RECEIVE,
                    targetPeerId = session.peerId,
                    status = TransferStatus.OFFERING,
                )
                val inc = IncomingTransfer(transferId, streamId, session.peerId, info)
                session.incomingById[transferId] = inc
                session.incomingByStream[streamId] = inc
                callbacks.onFileOffer(session.peerId, info, msgId, caption, ts)

                if (callbacks.shouldAutoAccept(info)) {
                    acceptIncomingTransfer(session.peerId, transferId)
                }
            }

            "file-accept" -> {
                val transferId = obj["transferId"]?.jsonPrimitive?.content ?: return
                val out = session.outgoing[transferId] ?: return
                if (!out.started && !out.cancelled) {
                    out.started = true
                    scope.launch { pumpOutgoingFile(session, out) }
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

    // 流式分片发送（支持任意 GB 级大文件，仅占用 ~48KB 读取缓冲 + 背压水位流控）
    private suspend fun pumpOutgoingFile(session: PeerSession, out: OutgoingTransfer) {
        val ch = session.fileChannel
        if (ch == null || ch.state() != DataChannel.State.OPEN) {
            out.info = out.info.copy(status = TransferStatus.ERROR, errorMessage = "P2P 数据通道未就绪")
            callbacks.onTransferUpdate(out.info)
            return
        }

        val sha = MessageDigest.getInstance("SHA-256")
        out.info = out.info.copy(status = TransferStatus.TRANSFERRING)
        callbacks.onTransferUpdate(out.info)

        var sentBytes = 0L
        var chunkIndex = 0
        val totalSize = out.source.fileSize
        var lastReportAt = SystemClock.elapsedRealtime()
        var lastReportBytes = 0L

        try {
            val inputStream = when {
                out.source.bytes != null -> out.source.bytes.inputStream()
                out.source.uri != null -> appContext.contentResolver.openInputStream(out.source.uri)
                else -> null
            } ?: throw IllegalStateException("无法读取所选文件")

            inputStream.use { stream ->
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

            // 发送 EOF 尾帧：[streamId 4B][0xFFFFFFFF 4B][32B raw SHA-256]
            val digest = sha.digest()
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
                sha256 = FormatUtils.bytesToHex(digest),
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
            // 最多每 80ms 兜底检查一次，避免极端机型漏触发回调
            kotlinx.coroutines.withTimeoutOrNull(80L) { waiter.await() }
        }
    }

    // 串行接收二进制帧并流式落盘 + 增量 SHA-256 校验
    private fun handleFileBinaryFrame(session: PeerSession, raw: ByteArray) {
        if (raw.size < 8) return
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        val streamId = buf.int
        val chunkIndex = buf.int

        val inc = session.incomingByStream[streamId] ?: return
        if (inc.cancelled) return

        // EOF 尾帧：校验 SHA-256 并保存到系统 Downloads/BayChat/
        if (chunkIndex == EOF_CHUNK_INDEX) {
            val expectedHex = if (raw.size >= 40) {
                FormatUtils.bytesToHex(raw.copyOfRange(8, 40))
            } else {
                ""
            }
            val actualHex = FormatUtils.bytesToHex(inc.sha.digest())
            val verified = expectedHex.isNotEmpty() && expectedHex == actualHex

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
                )

                inc.info = inc.info.copy(
                    status = TransferStatus.COMPLETED,
                    transferredBytes = inc.info.fileSize,
                    sha256 = actualHex,
                    verified = verified,
                    etaSeconds = 0.0,
                    localPath = savedResult.previewFilePath,
                    savedDisplayPath = savedResult.displayPath,
                )
                callbacks.onTransferUpdate(inc.info)

                sendChatJson(
                    session,
                    buildJsonObject {
                        put("kind", "file-ack")
                        put("transferId", inc.transferId)
                        put("verified", verified)
                        put("sha256", actualHex)
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
            inc.sha.update(raw, 8, payloadLen)
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

    // 探测实时传输路径（局域网 P2P / 公网 P2P / TURN 中继）与 RTT 延迟
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
