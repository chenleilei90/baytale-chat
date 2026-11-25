package com.baytale.baychat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.CachedMessage
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.CreateRoomRequest
import com.baytale.baychat.core.model.FileTransferInfo
import com.baytale.baychat.core.model.HistoryMeta
import com.baytale.baychat.core.model.LinkTransportType
import com.baytale.baychat.core.model.PeerLinkStatus
import com.baytale.baychat.core.model.PeerProfile
import com.baytale.baychat.core.model.ReplyQuote
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.model.RoomSummary
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.model.ServerConfig
import com.baytale.baychat.core.model.SignalEnvelope
import com.baytale.baychat.core.model.TransferDirection
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.model.UserProfile
import com.baytale.baychat.core.network.ApiException
import com.baytale.baychat.core.network.BayChatApi
import com.baytale.baychat.core.network.SignalingCallbacks
import com.baytale.baychat.core.network.SignalingClient
import com.baytale.baychat.core.storage.AppPrefsStore
import com.baytale.baychat.core.storage.AppUserPrefs
import com.baytale.baychat.core.storage.MediaDownloadHelper
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.core.webrtc.MeshCallbacks
import com.baytale.baychat.core.webrtc.OutgoingFileSource
import com.baytale.baychat.core.webrtc.WebRtcMeshManager
import com.baytale.baychat.service.TransferForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ActiveRoomSession(
    val roomCode: String,
    val password: String,
    val roomInfo: RoomSummary? = null,
    val historyMeta: HistoryMeta = HistoryMeta(),
    val wsConnected: Boolean = false,
    val peers: Map<String, PeerProfile> = emptyMap(),
    val linkStatuses: Map<String, PeerLinkStatus> = emptyMap(),
    val typingPeers: Set<String> = emptySet(),
    val messages: List<ChatItem> = emptyList(),
    val replyingTo: ReplyQuote? = null,
    val searchKeyword: String = "",
    val filterKind: String = "all", // all | image | file
)

data class PendingExternalShare(
    val uris: List<Uri> = emptyList(),
    val sharedText: String? = null,
) {
    val hasContent: Boolean get() = uris.isNotEmpty() || !sharedText.isNullOrBlank()
}

data class BayChatUiState(
    val privacyAccepted: Boolean = false,
    val profile: UserProfile,
    val themeMode: AppThemeMode = AppThemeMode.PEACH,
    val prefs: AppUserPrefs = AppUserPrefs(),
    val serverConfig: ServerConfig = ServerConfig(),
    val configLoaded: Boolean = false,
    val savedRooms: List<SavedFixedRoom> = emptyList(),
    val lobbyBusy: Boolean = false,
    val lobbyError: String? = null,
    val pendingJoinCode: String? = null,
    val pendingShare: PendingExternalShare = PendingExternalShare(),
    val activeRoom: ActiveRoomSession? = null,
    val toastMessage: String? = null,
)

// 全局业务控制器：统一管理大厅、房间信令、WebRTC Mesh 传输、系统分享队列与前台保活通知
class BayChatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val store = AppPrefsStore(appContext)
    private val api = BayChatApi(serverUrlProvider = { _uiState.value.prefs.serverUrl })

    private val _uiState = MutableStateFlow(
        BayChatUiState(
            privacyAccepted = store.hasAcceptedPrivacy(),
            profile = store.loadProfile(),
            themeMode = store.loadTheme(),
            prefs = store.loadPrefs(),
            savedRooms = store.loadSavedRooms(),
        )
    )
    val uiState: StateFlow<BayChatUiState> = _uiState.asStateFlow()

    private var selfPeerId: String = FormatUtils.generatePeerId()
    private var signaling: SignalingClient? = null
    private var mesh: WebRtcMeshManager? = null
    private val recordedTransfers = mutableSetOf<String>()

    init {
        if (_uiState.value.privacyAccepted) {
            refreshServerConfig()
        }
    }

    fun acceptPrivacyPolicy() {
        store.setPrivacyAccepted(true)
        _uiState.update { it.copy(privacyAccepted = true) }
        refreshServerConfig()
    }

    fun refreshServerConfig() {
        viewModelScope.launch {
            runCatching {
                api.fetchServerConfig(_uiState.value.profile.clientId)
            }.onSuccess { cfg ->
                _uiState.update { it.copy(serverConfig = cfg, configLoaded = true) }
                mesh?.updateIceServers(cfg.iceServers)
            }.onFailure {
                // 离线或弱网时保持默认配置，不阻塞界面
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun showToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg) }
    }

    fun clearLobbyError() {
        _uiState.update { it.copy(lobbyError = null) }
    }

    fun consumePendingJoinCode() {
        _uiState.update { it.copy(pendingJoinCode = null) }
    }

    fun handleIncomingDeepLink(rawUrl: String) {
        val code = FormatUtils.extractRoomCode(rawUrl)
        if (code.isNotBlank()) {
            _uiState.update { it.copy(pendingJoinCode = code) }
            if (_uiState.value.privacyAccepted && _uiState.value.activeRoom == null) {
                joinRoom(code, "")
            }
        }
    }

    fun handleExternalShare(uris: List<Uri>, text: String?) {
        val share = PendingExternalShare(uris = uris, sharedText = text?.takeIf { it.isNotBlank() })
        if (!share.hasContent) return
        _uiState.update { it.copy(pendingShare = share) }
        if (_uiState.value.activeRoom != null) {
            showToast("已收到系统分享内容，可直接在房间内一键发出")
        } else {
            showToast("已收到系统分享内容，创建或加入房间后即可秒传")
        }
    }

    fun clearPendingShare() {
        _uiState.update { it.copy(pendingShare = PendingExternalShare()) }
    }

    fun sendPendingShareNow() {
        val share = _uiState.value.pendingShare
        if (!share.hasContent || _uiState.value.activeRoom == null) return
        clearPendingShare()
        share.sharedText?.let { sendTextMessage(it) }
        if (share.uris.isNotEmpty()) {
            sendUris(share.uris)
        }
    }

    fun updateProfile(name: String, avatar: String) {
        val cleanName = name.trim().take(24).ifBlank { FormatUtils.generateRandomName() }
        val next = _uiState.value.profile.copy(name = cleanName, avatar = avatar)
        store.saveProfile(next)
        _uiState.update { it.copy(profile = next) }
        signaling?.updateProfile(
            PeerProfile(
                peerId = selfPeerId,
                name = next.name,
                avatar = next.avatar,
                joinedAt = System.currentTimeMillis(),
            )
        )
        showToast("资料已更新 ✨")
    }

    fun randomizeProfile() {
        updateProfile(FormatUtils.generateRandomName(), FormatUtils.PRESET_AVATARS.random().first)
    }

    fun selectTheme(mode: AppThemeMode) {
        store.saveTheme(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun updatePrefs(next: AppUserPrefs) {
        val prevUrl = _uiState.value.prefs.serverUrl
        store.savePrefs(next)
        _uiState.update { it.copy(prefs = next) }
        if (prevUrl != next.serverUrl && _uiState.value.privacyAccepted) {
            refreshServerConfig()
        }
    }

    fun removeSavedRoom(code: String) {
        val next = store.removeSavedRoom(code)
        _uiState.update { it.copy(savedRooms = next) }
        showToast("已取消收藏 #$code")
    }

    fun buildInviteUrl(code: String): String = api.buildInviteUrl(code)

    // 创建房间并自动进入
    fun createAndEnterRoom(
        mode: RoomMode,
        customCode: String,
        password: String,
        privatePairOnly: Boolean,
        retentionDays: Int,
        maxFileSizeMB: Int,
        saveFixedFavorite: Boolean,
    ) {
        if (_uiState.value.lobbyBusy) return
        _uiState.update { it.copy(lobbyBusy = true, lobbyError = null) }
        viewModelScope.launch {
            try {
                val maxPeers = if (privatePairOnly) 2 else _uiState.value.serverConfig.maxRoomPeers.coerceAtLeast(2)
                val summary = api.createRoom(
                    CreateRoomRequest(
                        mode = mode.wireValue,
                        code = if (mode == RoomMode.FIXED) customCode.trim().takeIf { it.isNotBlank() } else null,
                        password = password.trim().takeIf { it.isNotBlank() },
                        maxPeers = maxPeers,
                        retentionDays = retentionDays,
                        maxFileSizeMB = maxFileSizeMB,
                    )
                )
                if (mode == RoomMode.FIXED && saveFixedFavorite) {
                    val saved = store.upsertSavedRoom(
                        SavedFixedRoom(
                            code = summary.code,
                            hasPassword = summary.hasPassword,
                            password = password.trim(),
                        )
                    )
                    _uiState.update { it.copy(savedRooms = saved) }
                }
                enterRoomInternal(summary.code, password.trim(), summary)
            } catch (e: Exception) {
                _uiState.update { it.copy(lobbyError = e.message ?: "创建房间失败") }
            } finally {
                _uiState.update { it.copy(lobbyBusy = false) }
            }
        }
    }

    // 通过房间码/邀请链接/扫码结果加入房间
    fun joinRoom(rawInput: String, password: String) {
        val code = FormatUtils.extractRoomCode(rawInput)
        if (code.isBlank()) {
            _uiState.update { it.copy(lobbyError = "请输入房间口令或扫描二维码") }
            return
        }
        if (_uiState.value.lobbyBusy) return
        _uiState.update { it.copy(lobbyBusy = true, lobbyError = null) }

        viewModelScope.launch {
            try {
                val info = api.fetchRoomInfo(code)
                val cleanPwd = password.trim()
                if (info.hasPassword) {
                    if (cleanPwd.isBlank()) {
                        _uiState.update {
                            it.copy(
                                lobbyError = "房间 #$code 设有暗号密码，请输入密码后进入",
                                pendingJoinCode = code,
                            )
                        }
                        return@launch
                    }
                    api.verifyRoomPassword(code, cleanPwd)
                }
                if (info.mode == RoomMode.FIXED.wireValue) {
                    val saved = store.upsertSavedRoom(
                        SavedFixedRoom(code = info.code, hasPassword = info.hasPassword, password = cleanPwd)
                    )
                    _uiState.update { it.copy(savedRooms = saved) }
                }
                enterRoomInternal(info.code, cleanPwd, info)
            } catch (e: ApiException) {
                _uiState.update { it.copy(lobbyError = e.message, pendingJoinCode = code) }
            } catch (e: Exception) {
                _uiState.update { it.copy(lobbyError = e.message ?: "无法连接到服务器") }
            } finally {
                _uiState.update { it.copy(lobbyBusy = false) }
            }
        }
    }

    fun effectiveMaxFileMB(): Int {
        val roomMB = _uiState.value.activeRoom?.roomInfo?.maxFileSizeMB
            ?: _uiState.value.serverConfig.defaultRoomMaxFileMB
        val capMB = _uiState.value.serverConfig.maxAttachmentMB
        return if (capMB > 0 && (roomMB <= 0 || roomMB > capMB)) capMB else roomMB
    }

    private fun enterRoomInternal(code: String, password: String, initialInfo: RoomSummary?) {
        tearDownSession(stopService = false)
        selfPeerId = FormatUtils.generatePeerId()
        recordedTransfers.clear()

        _uiState.update {
            it.copy(
                lobbyError = null,
                pendingJoinCode = null,
                activeRoom = ActiveRoomSession(
                    roomCode = code,
                    password = password,
                    roomInfo = initialInfo,
                    historyMeta = initialInfo?.history ?: HistoryMeta(retentionDays = initialInfo?.retentionDays ?: 1),
                ),
            )
        }

        TransferForegroundService.updateStatus(
            appContext,
            title = "BayChat 房间 #$code",
            text = "正在维持 P2P 闪传通道...",
        )

        val selfProfile = PeerProfile(
            peerId = selfPeerId,
            name = _uiState.value.profile.name,
            avatar = _uiState.value.profile.avatar,
            joinedAt = System.currentTimeMillis(),
        )

        val newMesh = WebRtcMeshManager(
            appContext = appContext,
            selfPeerId = selfPeerId,
            initialIceServers = _uiState.value.serverConfig.iceServers,
            callbacks = object : MeshCallbacks {
                override fun sendSignal(env: SignalEnvelope) {
                    signaling?.send(env)
                }

                override fun onLinkStatusChange(status: PeerLinkStatus) {
                    updateRoom { r ->
                        r.copy(linkStatuses = r.linkStatuses + (status.peerId to status))
                    }
                    refreshForegroundBanner()
                }

                override fun onTextMessage(
                    peerId: String,
                    id: String,
                    text: String,
                    replyTo: ReplyQuote?,
                    timestamp: Long,
                ) {
                    val sender = _uiState.value.activeRoom?.peers?.get(peerId)
                    appendMessageIfAbsent(
                        ChatItem(
                            id = id,
                            kind = ChatKind.TEXT,
                            senderId = peerId,
                            senderName = sender?.name ?: "伙伴",
                            senderAvatar = sender?.avatar,
                            isSelf = false,
                            timestamp = timestamp,
                            text = text,
                            replyTo = replyTo,
                        )
                    )
                }

                override fun onRecallMessage(peerId: String, msgId: String) {
                    val senderName = _uiState.value.activeRoom?.peers?.get(peerId)?.name
                    applyMessageRecalled(msgId, peerId, senderName)
                }

                override fun onTyping(peerId: String, isTyping: Boolean) {
                    updateRoom { r ->
                        val next = if (isTyping) r.typingPeers + peerId else r.typingPeers - peerId
                        r.copy(typingPeers = next)
                    }
                }

                override fun onReaction(peerId: String, msgId: String, emoji: String) {
                    toggleReactionLocal(msgId, emoji, peerId)
                }

                override fun onFileOffer(
                    peerId: String,
                    info: FileTransferInfo,
                    msgId: String,
                    caption: String?,
                    timestamp: Long,
                ) {
                    val sender = _uiState.value.activeRoom?.peers?.get(peerId)
                    val kind = when {
                        info.isImage -> ChatKind.IMAGE
                        info.isAudio -> ChatKind.AUDIO
                        else -> ChatKind.FILE
                    }
                    val itemId = if (info.isImage || info.isAudio) msgId else "tr_${info.transferId}"
                    val limitMB = effectiveMaxFileMB()
                    if (limitMB > 0 && info.fileSize > limitMB.toLong() * 1024L * 1024L) {
                        showToast("⚠️ 收到超大附件「${info.fileName}」，请手动确认接收或婉拒")
                    }
                    appendMessageIfAbsent(
                        ChatItem(
                            id = itemId,
                            kind = kind,
                            senderId = peerId,
                            senderName = sender?.name ?: "伙伴",
                            senderAvatar = sender?.avatar,
                            isSelf = false,
                            timestamp = timestamp,
                            caption = caption,
                            transfer = info.copy(targetPeerName = sender?.name),
                        )
                    )
                }

                override fun onTransferUpdate(info: FileTransferInfo) {
                    updateRoom { r ->
                        r.copy(
                            messages = r.messages.map { item ->
                                if (item.transfer?.transferId != info.transferId) {
                                    item
                                } else {
                                    item.copy(
                                        imageUrl = if (info.isImage && !info.localPath.isNullOrBlank()) info.localPath else item.imageUrl,
                                        localFilePath = info.localPath ?: item.localFilePath,
                                        transfer = info.copy(
                                            targetPeerName = item.transfer.targetPeerName ?: info.targetPeerName
                                        ),
                                    )
                                }
                            }
                        )
                    }

                    if (info.status == TransferStatus.TRANSFERRING && info.fileSize > 0) {
                        val pct = ((info.transferredBytes * 100) / info.fileSize).toInt().coerceIn(0, 100)
                        val speedText = "${FormatUtils.formatBytes(info.speedBps.toLong())}/s"
                        val dirLabel = if (info.direction == TransferDirection.SEND) "正在发送" else "正在接收"
                        TransferForegroundService.updateStatus(
                            appContext,
                            title = "$dirLabel ${info.fileName}",
                            text = "$pct% · $speedText",
                            progress = pct,
                        )
                    } else if (info.status == TransferStatus.COMPLETED) {
                        if (!info.isImage && !info.isAudio) {
                            if (info.direction == TransferDirection.SEND) {
                                val baseKey = info.transferId.substringBeforeLast('_')
                                if (recordedTransfers.add(baseKey)) {
                                    signaling?.recordActivity("file", info.fileSize)
                                }
                                showToast(
                                    if (info.verified) "✅ ${info.fileName} 已直传完成且 SHA-256 校验通过"
                                    else "📦 ${info.fileName} 发送完成"
                                )
                            } else {
                                showToast(
                                    if (info.verified) "✅ 已保存至 ${info.savedDisplayPath ?: "下载目录"}（SHA-256 校验通过）"
                                    else "📦 已保存至 ${info.savedDisplayPath ?: "下载目录"}"
                                )
                            }
                        }
                        refreshForegroundBanner()
                    } else if (info.status == TransferStatus.ERROR || info.status == TransferStatus.CANCELLED) {
                        refreshForegroundBanner()
                    }
                }

                override fun shouldAutoAccept(info: FileTransferInfo): Boolean {
                    val limitMB = effectiveMaxFileMB()
                    if (limitMB > 0 && info.fileSize > limitMB.toLong() * 1024L * 1024L) {
                        return false
                    }
                    if (info.isImage || info.isAudio) return true
                    val prefs = _uiState.value.prefs
                    val maxAutoBytes = prefs.autoAcceptMaxMB.coerceAtLeast(1).toLong() * 1024L * 1024L
                    return prefs.autoAcceptSmallFiles && info.fileSize <= maxAutoBytes
                }
            }
        )
        mesh = newMesh

        val newSig = SignalingClient(
            wsUrl = api.buildWebSocketUrl(),
            roomCode = code,
            password = password,
            profile = selfProfile,
            okHttpClient = api.okHttpClient,
            callbacks = object : SignalingCallbacks {
                override fun onConnectionState(connected: Boolean) {
                    updateRoom { it.copy(wsConnected = connected) }
                }

                override fun onJoined(env: SignalEnvelope) {
                    val peerMap = env.peers.orEmpty().associateBy { it.peerId }
                    updateRoom { r ->
                        r.copy(
                            roomInfo = env.room ?: r.roomInfo,
                            historyMeta = env.history ?: env.room?.history ?: r.historyMeta,
                            peers = peerMap,
                            linkStatuses = r.linkStatuses.filterKeys { it in peerMap },
                            typingPeers = r.typingPeers.filter { it in peerMap }.toSet(),
                        )
                    }
                    env.messages.orEmpty().forEach { mergeCachedMessage(it, fromCache = true) }
                    newMesh.syncPeers(peerMap.keys.toList())
                    refreshForegroundBanner()
                }

                override fun onPeerJoined(profile: PeerProfile) {
                    updateRoom { r -> r.copy(peers = r.peers + (profile.peerId to profile)) }
                    newMesh.ensurePeer(profile.peerId, forceReset = true)
                    appendSystemMessage("✨ ${profile.name} 加入了房间")
                    refreshForegroundBanner()
                }

                override fun onPeerUpdated(profile: PeerProfile) {
                    updateRoom { r ->
                        r.copy(
                            peers = r.peers + (profile.peerId to profile),
                            messages = r.messages.map { m ->
                                if (m.senderId == profile.peerId) {
                                    m.copy(senderName = profile.name, senderAvatar = profile.avatar)
                                } else m
                            }
                        )
                    }
                }

                override fun onPeerLeft(peerId: String) {
                    val leftName = _uiState.value.activeRoom?.peers?.get(peerId)?.name
                    updateRoom { r ->
                        r.copy(
                            peers = r.peers - peerId,
                            linkStatuses = r.linkStatuses - peerId,
                            typingPeers = r.typingPeers - peerId,
                        )
                    }
                    newMesh.removePeer(peerId)
                    if (leftName != null) appendSystemMessage("👋 $leftName 离开了房间")
                    refreshForegroundBanner()
                }

                override fun onSignal(env: SignalEnvelope) {
                    newMesh.handleSignal(env)
                }

                override fun onMessageCached(env: SignalEnvelope) {
                    env.history?.let { h -> updateRoom { it.copy(historyMeta = h) } }
                    val msg = env.message ?: return
                    if (msg.senderId != selfPeerId) {
                        mergeCachedMessage(msg, fromCache = false)
                    }
                }

                override fun onMessageRecalled(env: SignalEnvelope) {
                    env.history?.let { h -> updateRoom { it.copy(historyMeta = h) } }
                    env.messageId?.let { id -> applyMessageRecalled(id, env.fromPeerId, env.actorName) }
                }

                override fun onRetentionUpdated(env: SignalEnvelope) {
                    env.history?.let { h -> updateRoom { it.copy(historyMeta = h) } }
                    val days = env.retentionDays ?: return
                    updateRoom { r ->
                        r.copy(roomInfo = r.roomInfo?.copy(retentionDays = days))
                    }
                    val desc = if (days == 0) "阅后即焚（零缓存）" else "最后聊天后保留 ${days} 天"
                    appendSystemMessage("⏱ ${env.actorName ?: "伙伴"} 将保鲜期设为：$desc")
                }

                override fun onRoomSettingsUpdated(env: SignalEnvelope) {
                    val mb = env.maxFileSizeMB ?: return
                    updateRoom { r -> r.copy(roomInfo = r.roomInfo?.copy(maxFileSizeMB = mb)) }
                    appendSystemMessage("📦 ${env.actorName ?: "伙伴"} 将附件上限调为：${FormatUtils.formatLimitMB(mb)}")
                }

                override fun onRoomStatsUpdated(env: SignalEnvelope) {
                    val stats = env.stats ?: return
                    updateRoom { r -> r.copy(roomInfo = r.roomInfo?.copy(stats = stats)) }
                }

                override fun onHistoryCleared(env: SignalEnvelope) {
                    updateRoom { r ->
                        r.copy(
                            historyMeta = env.history ?: r.historyMeta.copy(messageCount = 0),
                            replyingTo = null,
                            messages = listOf(
                                ChatItem(
                                    id = "cleared_${System.currentTimeMillis()}",
                                    kind = ChatKind.SYSTEM,
                                    senderId = "system",
                                    senderName = "系统",
                                    isSelf = false,
                                    timestamp = System.currentTimeMillis(),
                                    text = "🧹 ${env.actorName ?: "伙伴"} 已清空服务器聊天缓存",
                                )
                            )
                        )
                    }
                    showToast("服务器聊天缓存已清空")
                }

                override fun onRoomDestroyed(reason: String) {
                    tearDownSession(stopService = true)
                    _uiState.update { it.copy(activeRoom = null, lobbyError = reason) }
                }

                override fun onError(code: String, message: String) {
                    if (code in setOf("ROOM_NOT_FOUND", "ROOM_EXPIRED", "WRONG_PASSWORD", "ROOM_FULL", "INVALID_JOIN")) {
                        tearDownSession(stopService = true)
                        _uiState.update { it.copy(activeRoom = null, lobbyError = message) }
                    } else {
                        showToast(message)
                    }
                }
            }
        )
        signaling = newSig
        newSig.connect()

        // 确保拉取到最新 TURN 凭证
        if (!_uiState.value.configLoaded) {
            refreshServerConfig()
        }
    }

    private fun refreshForegroundBanner() {
        val room = _uiState.value.activeRoom ?: return
        val readyCount = room.linkStatuses.values.count { it.dataReady }
        val statusText = when {
            room.peers.isEmpty() -> "等待伙伴扫码加入 · 口令 ${room.roomCode}"
            readyCount == 0 -> "正在打洞建立 P2P 直连..."
            room.linkStatuses.values.any { it.dataReady && it.transport == LinkTransportType.RELAY_TURN } ->
                "已连线 $readyCount 位伙伴（TURN 中转）"
            else -> "已 P2P 直连 $readyCount 位伙伴"
        }
        TransferForegroundService.updateStatus(
            appContext,
            title = "BayChat 房间 #${room.roomCode}",
            text = statusText,
        )
    }

    fun sendTextMessage(rawText: String) {
        val clean = rawText.trim()
        val room = _uiState.value.activeRoom ?: return
        if (clean.isEmpty()) return

        val id = "msg_${System.currentTimeMillis()}_${(100000..999999).random()}"
        val ts = System.currentTimeMillis()
        val quote = room.replyingTo
        val profile = _uiState.value.profile
        val p2pSent = mesh?.broadcastText(id, clean, ts, quote) ?: 0

        appendMessageIfAbsent(
            ChatItem(
                id = id,
                kind = ChatKind.TEXT,
                senderId = selfPeerId,
                senderName = profile.name,
                senderAvatar = profile.avatar,
                isSelf = true,
                timestamp = ts,
                text = clean,
                replyTo = quote,
            )
        )
        updateRoom { it.copy(replyingTo = null) }

        if (room.historyMeta.retentionDays > 0) {
            signaling?.cacheMessage(
                CachedMessage(
                    id = id,
                    kind = "text",
                    senderId = selfPeerId,
                    senderName = profile.name,
                    senderAvatar = profile.avatar,
                    text = clean,
                    replyToId = quote?.id,
                    replyToSender = quote?.senderName,
                    replyToPreview = quote?.preview,
                    timestamp = ts,
                )
            )
        } else if (p2pSent == 0 && room.peers.isEmpty()) {
            showToast("当前阅后即焚且暂无他人在线，消息仅在本地展示")
        }
        signaling?.recordActivity("message", 0)
    }

    fun sendUris(uris: List<Uri>) {
        val room = _uiState.value.activeRoom ?: return
        if (uris.isEmpty()) return

        viewModelScope.launch {
            val connectedPeers = mesh?.getConnectedPeerCount() ?: 0
            val limitMB = effectiveMaxFileMB()
            val profile = _uiState.value.profile

            for (uri in uris) {
                val meta = withContext(Dispatchers.IO) { MediaDownloadHelper.queryUriMeta(appContext, uri) }
                if (limitMB > 0 && meta.fileSize > limitMB.toLong() * 1024L * 1024L) {
                    showToast("⚠️「${meta.fileName}」超出当前附件上限 ${FormatUtils.formatLimitMB(limitMB)}")
                    continue
                }

                val isImage = meta.mimeType.startsWith("image/", ignoreCase = true) &&
                    meta.fileSize <= 25L * 1024L * 1024L

                if (isImage) {
                    sendImageUri(uri, meta.fileName, meta.fileSize, meta.mimeType, room, profile)
                    continue
                }

                if (connectedPeers == 0) {
                    showToast("文件 P2P 直传需等伙伴进房建立通道后发送")
                    continue
                }

                val ts = System.currentTimeMillis()
                val idPrefix = "file_${ts}_${(1000..9999).random()}"
                val transfers = mesh?.sendFileToAll(
                    OutgoingFileSource(
                        uri = uri,
                        fileName = meta.fileName,
                        fileSize = meta.fileSize,
                        mimeType = meta.mimeType,
                        isImage = false,
                        transferIdPrefix = idPrefix,
                        timestamp = ts,
                    )
                ).orEmpty()

                transfers.forEach { tr ->
                    val peerName = tr.targetPeerId?.let { room.peers[it]?.name }
                    appendMessageIfAbsent(
                        ChatItem(
                            id = "tr_${tr.transferId}",
                            kind = ChatKind.FILE,
                            senderId = selfPeerId,
                            senderName = profile.name,
                            senderAvatar = profile.avatar,
                            isSelf = true,
                            timestamp = ts,
                            transfer = tr.copy(targetPeerName = peerName),
                        )
                    )
                }

                if (room.historyMeta.retentionDays > 0) {
                    signaling?.cacheMessage(
                        CachedMessage(
                            id = "fnotice_$idPrefix",
                            kind = "file_notice",
                            senderId = selfPeerId,
                            senderName = profile.name,
                            senderAvatar = profile.avatar,
                            fileName = meta.fileName,
                            fileSize = meta.fileSize,
                            timestamp = ts,
                        )
                    )
                }
            }
        }
    }

    private suspend fun sendImageUri(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        room: ActiveRoomSession,
        profile: UserProfile,
    ) {
        val prefs = _uiState.value.prefs
        val ts = System.currentTimeMillis()
        val idPrefix = "img_${ts}_${(1000..9999).random()}"

        // 省流模式压缩，原图模式直发
        val compressedBytes = if (!prefs.imageOriginalMode) {
            withContext(Dispatchers.IO) { MediaDownloadHelper.compressImageToJpeg(appContext, uri) }
        } else null

        val source = if (compressedBytes != null) {
            OutgoingFileSource(
                bytes = compressedBytes,
                fileName = fileName.substringBeforeLast('.') + ".jpg",
                fileSize = compressedBytes.size.toLong(),
                mimeType = "image/jpeg",
                isImage = true,
                transferIdPrefix = idPrefix,
                timestamp = ts,
            )
        } else {
            OutgoingFileSource(
                uri = uri,
                fileName = fileName,
                fileSize = fileSize,
                mimeType = mimeType,
                isImage = true,
                transferIdPrefix = idPrefix,
                timestamp = ts,
            )
        }

        val transfers = mesh?.sendFileToAll(source).orEmpty()
        appendMessageIfAbsent(
            ChatItem(
                id = idPrefix,
                kind = ChatKind.IMAGE,
                senderId = selfPeerId,
                senderName = profile.name,
                senderAvatar = profile.avatar,
                isSelf = true,
                timestamp = ts,
                imageUrl = uri.toString(),
                transfer = transfers.firstOrNull(),
            )
        )

        // 若开启了服务器保鲜缓存，后台异步上传缩略/原图供新进房伙伴查看
        if (room.historyMeta.retentionDays > 0) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val maxCache = _uiState.value.serverConfig.historyMaxMediaBytes
                    val uploadBytes = compressedBytes ?: run {
                        if (fileSize in 1..maxCache) {
                            appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        } else {
                            MediaDownloadHelper.compressImageToJpeg(appContext, uri, 1600, 78)
                        }
                    } ?: return@launch
                    if (uploadBytes.size <= maxCache) {
                        val uploaded = api.uploadCachedMedia(
                            code = room.roomCode,
                            password = room.password,
                            bytes = uploadBytes,
                            mimeType = if (compressedBytes != null) "image/jpeg" else mimeType,
                        )
                        signaling?.cacheMessage(
                            CachedMessage(
                                id = idPrefix,
                                kind = "image",
                                senderId = selfPeerId,
                                senderName = profile.name,
                                senderAvatar = profile.avatar,
                                mediaId = uploaded.mediaId,
                                mediaMime = uploaded.mime,
                                fileName = source.fileName,
                                fileSize = uploaded.size,
                                timestamp = ts,
                            )
                        )
                    }
                }
            }
        }
        signaling?.recordActivity("message", 0)
    }

    fun acceptFile(peerId: String, transferId: String) {
        mesh?.acceptIncomingTransfer(peerId, transferId)
    }

    fun rejectFile(peerId: String, transferId: String) {
        mesh?.rejectIncomingTransfer(peerId, transferId)
    }

    fun cancelTransfer(peerId: String, transferId: String) {
        mesh?.cancelTransfer(peerId, transferId)
    }

    fun reconnectPeer(peerId: String) {
        mesh?.reconnectPeer(peerId)
        showToast("正在重新尝试 P2P 打洞直连...")
    }

    fun setReplyingTo(item: ChatItem?) {
        if (item == null) {
            updateRoom { it.copy(replyingTo = null) }
            return
        }
        val preview = when (item.kind) {
            ChatKind.TEXT -> item.text.orEmpty().take(60)
            ChatKind.IMAGE -> "🖼️ [图片] ${item.caption ?: item.transfer?.fileName.orEmpty()}"
            ChatKind.AUDIO -> "🎙️ [语音]"
            ChatKind.FILE -> "📦 [文件] ${item.transfer?.fileName.orEmpty()}"
            ChatKind.SYSTEM -> return
        }.trim()
        updateRoom { it.copy(replyingTo = ReplyQuote(item.id, item.senderName, preview)) }
    }

    fun recallMessage(msgId: String) {
        applyMessageRecalled(msgId, selfPeerId, _uiState.value.profile.name)
        mesh?.broadcastRecall(msgId)
        signaling?.recallMessage(msgId)
        showToast("已撤回消息")
    }

    fun reactMessage(msgId: String, emoji: String) {
        toggleReactionLocal(msgId, emoji, selfPeerId)
        mesh?.broadcastReaction(msgId, emoji)
    }

    fun setSearchFilter(keyword: String, filterKind: String) {
        updateRoom { it.copy(searchKeyword = keyword, filterKind = filterKind) }
    }

    fun updateRetentionDays(days: Int) {
        signaling?.updateRetention(days)
    }

    fun updateRoomMaxFileMB(mb: Int) {
        val cap = _uiState.value.serverConfig.maxAttachmentMB
        if (cap > 0 && (mb == 0 || mb > cap)) {
            showToast("服务端限制附件上限最高不超过 ${FormatUtils.formatLimitMB(cap)}")
            return
        }
        signaling?.updateMaxFileSize(mb)
    }

    fun clearServerHistory() {
        signaling?.clearHistory()
    }

    fun exportChatLog() {
        val room = _uiState.value.activeRoom ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val lines = room.messages.map { m ->
                val ts = fmt.format(Date(m.timestamp))
                when (m.kind) {
                    ChatKind.SYSTEM -> "[$ts] [系统] ${m.text.orEmpty()}"
                    ChatKind.TEXT -> {
                        val quote = m.replyTo?.let { "（引用 @${it.senderName}：${it.preview}）" }.orEmpty()
                        "[$ts] ${m.senderName}$quote: ${m.text.orEmpty()}"
                    }
                    ChatKind.IMAGE -> "[$ts] ${m.senderName}: [图片] ${m.caption ?: m.transfer?.fileName.orEmpty()}"
                    ChatKind.AUDIO -> "[$ts] ${m.senderName}: [语音] ${m.caption.orEmpty()}"
                    ChatKind.FILE -> "[$ts] ${m.senderName}: [文件] ${m.transfer?.fileName.orEmpty()} (${FormatUtils.formatBytes(m.transfer?.fileSize ?: 0)})"
                }
            }
            val content = buildString {
                appendLine("# BayChat 房间 #${room.roomCode} 聊天记录")
                appendLine("导出时间：${fmt.format(Date())}")
                appendLine("共 ${lines.size} 条记录")
                appendLine()
                lines.forEach { appendLine(it) }
            }
            val savedPath = runCatching {
                MediaDownloadHelper.saveTextToDownloads(appContext, "baychat-${room.roomCode}-${System.currentTimeMillis()}.txt", content)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                showToast(if (savedPath != null) "已导出至 $savedPath" else "导出失败")
            }
        }
    }

    fun rotateEphemeralCode() {
        val room = _uiState.value.activeRoom ?: return
        viewModelScope.launch {
            runCatching {
                api.createRoom(
                    CreateRoomRequest(
                        mode = RoomMode.EPHEMERAL.wireValue,
                        password = room.password.takeIf { it.isNotBlank() },
                        maxPeers = room.roomInfo?.maxPeers ?: _uiState.value.serverConfig.maxRoomPeers,
                        retentionDays = room.historyMeta.retentionDays,
                        maxFileSizeMB = effectiveMaxFileMB(),
                    )
                )
            }.onSuccess { next ->
                signaling?.destroyRoom()
                enterRoomInternal(next.code, room.password, next)
                showToast("已切换全新房间口令：${next.code}")
            }.onFailure {
                showToast(it.message ?: "换码失败")
            }
        }
    }

    fun leaveRoom() {
        signaling?.leaveRoom()
        tearDownSession(stopService = true)
        _uiState.update { it.copy(activeRoom = null) }
    }

    fun destroyRoom() {
        signaling?.destroyRoom()
        tearDownSession(stopService = true)
        _uiState.update { it.copy(activeRoom = null) }
        showToast("房间与服务器缓存已彻底销毁")
    }

    private fun mergeCachedMessage(cm: CachedMessage, fromCache: Boolean) {
        val room = _uiState.value.activeRoom ?: return
        val isSelf = cm.senderId == selfPeerId
        val item = when (cm.kind) {
            "image" -> if (!cm.mediaId.isNullOrBlank()) {
                ChatItem(
                    id = cm.id,
                    kind = ChatKind.IMAGE,
                    senderId = cm.senderId,
                    senderName = cm.senderName.ifBlank { "伙伴" },
                    senderAvatar = cm.senderAvatar,
                    isSelf = isSelf,
                    timestamp = cm.timestamp,
                    caption = cm.caption,
                    imageUrl = api.buildCachedMediaUrl(room.roomCode, cm.mediaId, room.password),
                    mediaId = cm.mediaId,
                    fromCache = fromCache,
                )
            } else return

            "file_notice" -> ChatItem(
                id = cm.id,
                kind = ChatKind.SYSTEM,
                senderId = cm.senderId,
                senderName = cm.senderName,
                isSelf = false,
                timestamp = cm.timestamp,
                text = "📦 ${cm.senderName} 闪传过文件「${cm.fileName ?: "文件"}」（${FormatUtils.formatBytes(cm.fileSize ?: 0L)}）",
                fromCache = fromCache,
            )

            else -> {
                val quote = if (!cm.replyToId.isNullOrBlank() && !cm.replyToPreview.isNullOrBlank()) {
                    ReplyQuote(cm.replyToId, cm.replyToSender ?: "伙伴", cm.replyToPreview)
                } else null
                ChatItem(
                    id = cm.id,
                    kind = ChatKind.TEXT,
                    senderId = cm.senderId,
                    senderName = cm.senderName.ifBlank { "伙伴" },
                    senderAvatar = cm.senderAvatar,
                    isSelf = isSelf,
                    timestamp = cm.timestamp,
                    text = FormatUtils.decodeLegacySecret(cm.text),
                    replyTo = quote,
                    fromCache = fromCache,
                )
            }
        }
        appendMessageIfAbsent(item)
    }

    private fun applyMessageRecalled(msgId: String, actorPeerId: String?, actorName: String?) {
        updateRoom { r ->
            val target = r.messages.firstOrNull { it.id == msgId } ?: return@updateRoom r
            if (actorPeerId != null && target.senderId != actorPeerId) return@updateRoom r
            val filtered = r.messages.filterNot { it.id == msgId }
            r.copy(
                messages = filtered + ChatItem(
                    id = "recalled_$msgId",
                    kind = ChatKind.SYSTEM,
                    senderId = "system",
                    senderName = "系统",
                    isSelf = false,
                    timestamp = System.currentTimeMillis(),
                    text = "↩ ${actorName ?: target.senderName} 撤回了一条消息",
                ),
                replyingTo = if (r.replyingTo?.id == msgId) null else r.replyingTo,
            )
        }
    }

    private fun toggleReactionLocal(msgId: String, emoji: String, peerId: String) {
        updateRoom { r ->
            r.copy(
                messages = r.messages.map { m ->
                    if (m.id != msgId) m else {
                        val map = m.reactions.toMutableMap()
                        val list = map[emoji].orEmpty()
                        map[emoji] = if (peerId in list) list - peerId else list + peerId
                        m.copy(reactions = map.filterValues { it.isNotEmpty() })
                    }
                }
            )
        }
    }

    private fun appendMessageIfAbsent(item: ChatItem) {
        updateRoom { r ->
            if (r.messages.any { it.id == item.id }) r
            else r.copy(messages = r.messages + item)
        }
    }

    private fun appendSystemMessage(text: String) {
        appendMessageIfAbsent(
            ChatItem(
                id = "sys_${System.currentTimeMillis()}_${(1000..9999).random()}",
                kind = ChatKind.SYSTEM,
                senderId = "system",
                senderName = "系统",
                isSelf = false,
                timestamp = System.currentTimeMillis(),
                text = text,
            )
        )
    }

    private fun updateRoom(transform: (ActiveRoomSession) -> ActiveRoomSession) {
        _uiState.update { st ->
            val cur = st.activeRoom ?: return@update st
            st.copy(activeRoom = transform(cur))
        }
    }

    private fun tearDownSession(stopService: Boolean) {
        signaling?.disconnect()
        signaling = null
        mesh?.destroyAll()
        mesh = null
        if (stopService) {
            TransferForegroundService.stop(appContext)
        }
    }

    override fun onCleared() {
        tearDownSession(stopService = true)
        super.onCleared()
    }
}
