package com.baytale.baychat.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.AppearanceMode
import com.baytale.baychat.core.model.BlockedPeer
import com.baytale.baychat.core.model.CachedMessage
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.CloudUploadStatus
import com.baytale.baychat.core.model.CreateRoomRequest
import com.baytale.baychat.core.model.FavoriteItem
import com.baytale.baychat.core.model.FileTransferInfo
import com.baytale.baychat.core.model.HistoryMeta
import com.baytale.baychat.core.model.LastActiveRoom
import com.baytale.baychat.core.model.LinkTransportType
import com.baytale.baychat.core.model.MessageSendStatus
import com.baytale.baychat.core.model.NearbyHeartbeatRequest
import com.baytale.baychat.core.model.NearbyInvite
import com.baytale.baychat.core.model.NearbyInviteRequest
import com.baytale.baychat.core.model.NearbyPeer
import com.baytale.baychat.core.model.PartialFileMeta
import com.baytale.baychat.core.model.PartialResumeItem
import com.baytale.baychat.core.model.PeerLinkStatus
import com.baytale.baychat.core.model.PeerProfile
import com.baytale.baychat.core.model.PickupInfo
import com.baytale.baychat.core.model.PickupTransferProgress
import com.baytale.baychat.core.model.PaperPlane
import com.baytale.baychat.core.model.CreatePlaneRequest
import com.baytale.baychat.core.model.PlaneFileMeta
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.ReplyQuote
import com.baytale.baychat.core.model.ReportRequest
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.model.RoomSummary
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.model.SavedPickupRecord
import com.baytale.baychat.core.model.ServerConfig
import com.baytale.baychat.core.model.SignalEnvelope
import com.baytale.baychat.core.model.TransferDirection
import com.baytale.baychat.core.model.TransferRecord
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.model.UserProfile
import com.baytale.baychat.core.network.ApiException
import com.baytale.baychat.core.network.AppUpdateManager
import com.baytale.baychat.core.network.BayChatApi
import com.baytale.baychat.core.network.NearbyApi
import com.baytale.baychat.core.network.PickupApi
import com.baytale.baychat.core.network.SignalingCallbacks
import com.baytale.baychat.core.network.SignalingClient
import com.baytale.baychat.core.storage.AppPrefsStore
import com.baytale.baychat.core.storage.AppUserPrefs
import com.baytale.baychat.core.storage.MediaDownloadHelper
import com.baytale.baychat.core.util.BaySoundPlayer
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.core.util.TransferResumeUtils
import com.baytale.baychat.core.util.classifyPlaneKind
import com.baytale.baychat.core.webrtc.MeshCallbacks
import com.baytale.baychat.core.webrtc.OutgoingFileSource
import com.baytale.baychat.core.webrtc.WebRtcMeshManager
import com.baytale.baychat.service.AppUpdateCenter
import com.baytale.baychat.service.AppUpdateService
import com.baytale.baychat.service.IncomingActionBus
import com.baytale.baychat.service.IncomingActionEvent
import com.baytale.baychat.service.IncomingNotificationHelper
import com.baytale.baychat.service.OtaUiEvent
import com.baytale.baychat.service.OtaUpdateState
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.service.TransferForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// 桌面长按静态快捷方式路由动作
enum class AppShortcutAction {
    QUICK_SEND,
    SCAN,
    PICKUP,
}

// 本地存储分类（用于存储统计与一键清理）
enum class StorageCategory {
    PARTIALS,
    RECEIVED_MEDIA,
    COIL_CACHE,
    UPDATES,
    EXPORTS,
}

data class StorageStats(
    val partialsBytes: Long = 0L,
    val receivedMediaBytes: Long = 0L,
    val coilCacheBytes: Long = 0L,
    val updatesBytes: Long = 0L,
    val exportsBytes: Long = 0L,
) {
    val totalBytes: Long
        get() = partialsBytes + receivedMediaBytes + coilCacheBytes + updatesBytes + exportsBytes
}

// B6：输入未知口令准备创建常驻房前的二次确认载荷
data class PendingCreateConfirm(
    val code: String,
    val password: String = "",
    val retentionDays: Int = 3,
    val maxFileSizeMB: Int = 500,
    val privatePairOnly: Boolean = false,
    val saveFixedFavorite: Boolean = true,
    val allowRandom: Boolean = false,
    val topic: String = "",
)

// B13：已有活跃/挂起房间时尝试进入另一房间的切换确认载荷
data class PendingSwitchRoom(
    val targetCode: String,
    val password: String = "",
    val source: String = "",
    val roomSummary: RoomSummary? = null,
    // UX-15：若是因收到附近邀请触发切换确认，暂存该邀请以便确认切换时再回复服务端
    val pendingNearbyInvite: NearbyInvite? = null,
)

// UX-15：已发出的附近设备直连邀请状态（供房间顶部横幅轮询展示）
data class OutgoingNearbyInvite(
    val inviteId: String,
    val toName: String,
    val roomCode: String,
    val status: String = "waiting", // waiting | accepted | declined | expired
)

// UX-19：高频文件传输实时进度快照（独立于消息列表，防止每 200ms 重建全列表重组）
data class TransferProgressSnapshot(
    val transferredBytes: Long,
    val totalBytes: Long,
    val speedBps: Long,
    val etaSeconds: Int,
)

// 文件夹 ZIP 打包实时进度
data class FolderPackProgress(
    val folderName: String,
    val packedFiles: Int = 0,
    val packedBytes: Long = 0L,
)

// 语音消息内联播放状态
data class VoicePlaybackState(
    val activeItemId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

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
    val ownerToken: String = "",
    val isOwner: Boolean = false,
    val selfPeerId: String = "",
) {
    // B14：严格判断 roomInfo.mode == ephemeral，避免 roomInfo 为空时误判为临时房
    val isEphemeral: Boolean
        get() = roomInfo?.mode == RoomMode.EPHEMERAL.wireValue
}

/**
 * 逐条转发载荷：支持纯文本消息与本地可读图片/文件。
 */
sealed interface ForwardPayload {
    data class Text(val text: String) : ForwardPayload
    data class FileUri(val uri: Uri, val fileName: String, val mime: String) : ForwardPayload
}

data class PendingExternalShare(
    val uris: List<Uri> = emptyList(),
    val sharedText: String? = null,
    val texts: List<String> = emptyList(),
    val autoSendRoomCode: String = "",
) {
    val hasContent: Boolean
        get() = uris.isNotEmpty() || !sharedText.isNullOrBlank() || texts.isNotEmpty()
}

data class BayChatUiState(
    val privacyAccepted: Boolean = false,
    val profile: UserProfile,
    val themeMode: AppThemeMode = AppThemeMode.AIR,
    val prefs: AppUserPrefs = AppUserPrefs(),
    val serverConfig: ServerConfig = ServerConfig(),
    val configLoaded: Boolean = false,
    val savedRooms: List<SavedFixedRoom> = emptyList(),
    val recentRooms: List<RecentRoom> = emptyList(),
    val publicRooms: List<RoomSummary> = emptyList(),
    val publicRoomsLoading: Boolean = false,
    val publicRoomsLastRefreshedAt: Long = 0L,
    val randomJoining: Boolean = false,
    val transferRecords: List<TransferRecord> = emptyList(),
    val partialTransfers: List<PartialResumeItem> = emptyList(),
    val otaState: OtaUpdateState = AppUpdateCenter.state.value,
    val showUpdateDialog: Boolean = false,
    val blockedPeerIds: Set<String> = emptySet(),
    val blockedClients: List<BlockedPeer> = emptyList(),
    val lobbyBusy: Boolean = false,
    val lobbyError: String? = null,
    val lobbyNeedPassword: Boolean = false,
    val pendingJoinCode: String? = null,
    val pendingShare: PendingExternalShare = PendingExternalShare(),
    val activeRoom: ActiveRoomSession? = null,
    val roomMinimized: Boolean = false,
    val toastMessage: String? = null,
    // v1.2.0 新增状态字段
    val lastRecoverableRoom: LastActiveRoom? = null,
    val pendingCreateConfirm: PendingCreateConfirm? = null,
    val pendingSwitchRoom: PendingSwitchRoom? = null,
    val pendingAppAction: AppShortcutAction? = null,
    val unreadCount: Int = 0,
    val isAppForeground: Boolean = true,
    val myPickups: List<SavedPickupRecord> = emptyList(),
    val pickupLookupResult: PickupInfo? = null,
    val pickupBusy: Boolean = false,
    val pickupProgress: PickupTransferProgress? = null,
    val nearbyPeers: List<NearbyPeer> = emptyList(),
    val incomingNearbyInvites: List<NearbyInvite> = emptyList(),
    val packingFolder: FolderPackProgress? = null,
    val isRecordingVoice: Boolean = false,
    val voiceRecordDurationMs: Long = 0L,
    val voiceAmplitude: Float = 0f,
    val voicePlayback: VoicePlaybackState = VoicePlaybackState(),
    val playedVoiceIds: Set<String> = emptySet(),
    val storageStats: StorageStats = StorageStats(),
    // 按房间持久化的输入草稿与消息免打扰开关
    val draftsByRoom: Map<String, String> = emptyMap(),
    val mutedRooms: Set<String> = emptySet(),
    // 「以下为新消息」定位标记与待跳转 @我 消息 ID 列表
    val firstUnreadMessageId: String? = null,
    val unreadMentionMessageIds: List<String> = emptyList(),
    // UX-4/6/7/12/14/15/19 新增交互与状态字段
    val invitingNearbyClientId: String? = null,
    val publicRoomsError: String? = null,
    val isLobbyRefreshing: Boolean = false,
    val isTransferRefreshing: Boolean = false,
    val toastSeq: Long = 0L,
    val isNetworkOffline: Boolean = false,
    val nearbyNetworkType: String = "unknown",
    val outgoingNearbyInvite: OutgoingNearbyInvite? = null,
    val transferProgress: Map<String, TransferProgressSnapshot> = emptyMap(),
    // v1.3.0 本地收藏、最近使用表情及拍一拍头像抖动状态
    val favorites: List<FavoriteItem> = emptyList(),
    val recentEmojis: List<String> = emptyList(),
    val nudgedPeerId: String? = null,
    val nudgeNonce: Long = 0L,
    // v1.4.0 纸飞机公开文件清单列表与状态
    val planes: List<PaperPlane> = emptyList(),
    val planesLoading: Boolean = false,
    val planesError: String? = null,
    val planesLastRefreshedAt: Long = 0L,
    val creatingPlane: Boolean = false,
) {
    // B12：供 Activity 与导航判断当前是否保有活跃或后台挂起的房间会话
    val hasActiveRoom: Boolean
        get() = activeRoom != null
}

// 全局业务控制器：统一管理大厅、发现广场、传输中心、取件码、附近发现、房间信令、WebRTC Mesh 传输、语音与前台保活
class BayChatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val store = AppPrefsStore(appContext)
    private val api = BayChatApi(serverUrlProvider = { _uiState.value.prefs.serverUrl })
    private val pickupApi = PickupApi(serverUrlProvider = { _uiState.value.prefs.serverUrl }, okHttpClient = api.okHttpClient)
    private val nearbyApi = NearbyApi(serverUrlProvider = { _uiState.value.prefs.serverUrl }, okHttpClient = api.okHttpClient)
    private val updateManager = AppUpdateManager(appContext, api.okHttpClient)
    private val soundPlayer = BaySoundPlayer(appContext)

    private val initialProfile = store.loadProfile()
    private val initialBlocked = store.loadBlockedPeers()
    private val initialLastRoom = loadValidRecoverableRoom()

    private val _uiState = MutableStateFlow(
        BayChatUiState(
            privacyAccepted = store.hasAcceptedPrivacy(),
            profile = initialProfile,
            themeMode = store.loadTheme(),
            prefs = store.loadPrefs(),
            savedRooms = store.loadSavedRooms(),
            recentRooms = store.loadRecentRooms(),
            transferRecords = store.loadTransferRecords(),
            blockedClients = initialBlocked,
            lastRecoverableRoom = initialLastRoom,
            myPickups = store.loadMyPickups(),
            draftsByRoom = store.loadRoomDrafts(),
            mutedRooms = store.loadMutedRooms(),
            favorites = store.loadFavorites(),
            recentEmojis = store.loadRecentEmojis(),
        )
    )
    val uiState: StateFlow<BayChatUiState> = _uiState.asStateFlow()

    private var selfPeerId: String = FormatUtils.generatePeerId()
    private var signaling: SignalingClient? = null
    private var mesh: WebRtcMeshManager? = null
    private var awaitingInitialHistorySync: Boolean = false
    private var hadRoomWsDisconnect: Boolean = false
    private var hasJoinedRoomOnce: Boolean = false
    private var pendingRestoreCaptureChecked: Boolean = false

    // 发送去重与重发源上下文缓存
    private val recordedTransfers = ConcurrentHashMap.newKeySet<String>()
    private val sendCompletedRecipients = ConcurrentHashMap<String, MutableSet<String>>()
    private val sendExpectedTotal = ConcurrentHashMap<String, Int>()
    private val outgoingSourcesByItemId = ConcurrentHashMap<String, OutgoingFileSource>()
    private val activeDownloadIds = ConcurrentHashMap.newKeySet<String>()
    private val typingTimeoutJobs = ConcurrentHashMap<String, Job>()
    private val lastResendActionAt = ConcurrentHashMap<String, Long>()
    private val currentProcessCameraUris = ConcurrentHashMap.newKeySet<String>()

    private var awaitingInstallPermission: Boolean = false
    private var lastOtaStartRequestMs: Long = 0L
    private var dismissedUpdateVersionInSession: Int = 0
    // 记录 peerId -> effectiveClientId 映射，避免成员离线后解除屏蔽仍残留拦截
    private val blockedPeerIdToClientId = ConcurrentHashMap<String, String>()
    private var lastForegroundBannerAt: Long = 0L
    private var lastForegroundBannerPercent: Int = -1
    private var lastForegroundBannerTitle: String = ""
    private var lastToastText: String = ""
    private var lastToastAt: Long = 0L
    private var lastTypingSentAt: Long = 0L
    private var draftIdleTypingJob: Job? = null
    private var nearbyPollingJob: Job? = null
    private var outgoingInvitePollingJob: Job? = null
    private var lastNudgeAt: Long = 0L
    private val seenNudgeIds = LinkedHashSet<String>()

    // 语音录制与播放控制器
    private var mediaRecorder: MediaRecorder? = null
    private var voiceOutputFile: File? = null
    private var voiceRecordStartAt: Long = 0L
    private var voiceRecordTickerJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var voicePlaybackTickerJob: Job? = null
    private val audioManager: AudioManager? by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private var recordAudioFocusRequest: AudioFocusRequest? = null
    private var playbackAudioFocusRequest: AudioFocusRequest? = null

    // 系统网络状态回调（断网恢复秒触发 WS/ICE 重连）
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        cleanupExpiredReceivedMedia()
        refreshTransferCenter()
        refreshStorageStats()
        observeOtaUpdateCenter()
        observeIncomingNotificationActions()
        registerNetworkMonitor()
        if (_uiState.value.privacyAccepted) {
            refreshServerConfig()
            refreshPublicRooms()
            checkForAppUpdate(manual = false, bypassThrottle = true)
            startNearbyPolling()
        }
    }

    private fun loadValidRecoverableRoom(): LastActiveRoom? {
        val last = store.loadLastActiveRoom() ?: return null
        val ageMs = System.currentTimeMillis() - last.at
        if (ageMs in 0L..LAST_ROOM_RECOVER_TTL_MS && last.code.isNotBlank()) {
            return last
        }
        store.clearLastActiveRoom()
        return null
    }

    private fun cleanupExpiredReceivedMedia() {
        viewModelScope.launch(Dispatchers.IO) {
            val previewDir = File(appContext.filesDir, "received_media")
            MediaDownloadHelper.cleanupOldReceivedMedia(previewDir)
        }
    }

    private fun isCurrentlyOnline(cm: ConnectivityManager?): Boolean {
        val network = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkMonitor() {
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            connectivityManager = cm
            _uiState.update {
                it.copy(
                    isNetworkOffline = !isCurrentlyOnline(cm),
                    nearbyNetworkType = detectNearbyNetworkType(),
                )
            }
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasOffline = _uiState.value.isNetworkOffline
                    _uiState.update {
                        it.copy(
                            isNetworkOffline = false,
                            nearbyNetworkType = detectNearbyNetworkType(),
                        )
                    }
                    signaling?.notifyNetworkAvailable()
                    mesh?.refreshIceIfNeeded()
                    if (wasOffline) {
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            showToast("网络已恢复，已重新连接")
                        }
                    }
                }

                override fun onLost(network: Network) {
                    val offlineNow = !isCurrentlyOnline(cm)
                    _uiState.update {
                        it.copy(
                            isNetworkOffline = offlineNow,
                            nearbyNetworkType = detectNearbyNetworkType(),
                        )
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        }
    }

    private fun observeIncomingNotificationActions() {
        viewModelScope.launch {
            IncomingActionBus.events.collect { event ->
                when (event) {
                    is IncomingActionEvent.AcceptFile -> acceptFile(event.peerId, event.transferId, resumeIfAvailable = true)
                    is IncomingActionEvent.RejectFile -> rejectFile(event.peerId, event.transferId)
                }
            }
        }
    }

    private fun observeOtaUpdateCenter() {
        viewModelScope.launch {
            var lastStatus = AppUpdateCenter.state.value.status
            AppUpdateCenter.state.collect { ota ->
                if (ota.status == OtaUpdateStatus.READY_TO_INSTALL && lastStatus != OtaUpdateStatus.READY_TO_INSTALL) {
                    playSoundIfEnabled { playTransferDone() }
                }
                lastStatus = ota.status
                _uiState.update { st ->
                    val forcePopup = ota.isForceUpdate &&
                        ota.release != null &&
                        ota.status != OtaUpdateStatus.IDLE &&
                        ota.status != OtaUpdateStatus.CHECKING
                    st.copy(
                        otaState = ota,
                        showUpdateDialog = if (forcePopup) true else st.showUpdateDialog,
                    )
                }
            }
        }
        viewModelScope.launch {
            AppUpdateCenter.events.collect { event ->
                when (event) {
                    is OtaUiEvent.Toast -> showToast(event.message)
                    is OtaUiEvent.InstallerLaunched -> {
                        awaitingInstallPermission = false
                        showToast("安装包已就绪，请按系统提示完成覆盖升级")
                    }
                    is OtaUiEvent.NeedInstallPermission -> {
                        awaitingInstallPermission = true
                        showToast("请先开启「允许安装未知应用」权限，返回后将自动继续安装")
                    }
                }
            }
        }
    }

    fun acceptPrivacyPolicy() {
        store.setPrivacyAccepted(true)
        _uiState.update { it.copy(privacyAccepted = true) }
        refreshServerConfig()
        refreshPublicRooms()
        checkForAppUpdate(manual = false, bypassThrottle = true)
        startNearbyPolling()
    }

    fun onAppForegrounded() {
        _uiState.update {
            it.copy(
                isAppForeground = true,
                unreadCount = if (it.roomMinimized) it.unreadCount else 0,
            )
        }
        val fgState = _uiState.value
        if (!fgState.roomMinimized && fgState.activeRoom != null) {
            IncomingNotificationHelper.cancelMessageNotification(appContext)
        }
        if (!_uiState.value.privacyAccepted) return
        startNearbyPolling()
        val ota = AppUpdateCenter.state.value
        if (awaitingInstallPermission &&
            ota.status == OtaUpdateStatus.READY_TO_INSTALL &&
            AppUpdateManager.canInstallPackages(appContext)
        ) {
            awaitingInstallPermission = false
            installReadyAppUpdate()
            return
        }
        if (ota.status == OtaUpdateStatus.CHECKING ||
            ota.status == OtaUpdateStatus.DOWNLOADING ||
            ota.status == OtaUpdateStatus.PAUSED ||
            ota.status == OtaUpdateStatus.VERIFYING ||
            ota.status == OtaUpdateStatus.READY_TO_INSTALL ||
            ota.status == OtaUpdateStatus.ERROR
        ) {
            return
        }
        val elapsed = System.currentTimeMillis() - _uiState.value.prefs.lastUpdateCheckAt
        if (elapsed >= FOREGROUND_UPDATE_CHECK_INTERVAL_MS) {
            checkForAppUpdate(manual = false, bypassThrottle = false)
        }
    }

    fun onAppBackgrounded() {
        // 切入后台时立即中止未完成的语音录制，防止麦克风持续占用
        cancelVoiceRecording()
        _uiState.update { it.copy(isAppForeground = false) }
        stopNearbyPolling()
    }

    fun refreshServerConfig() {
        viewModelScope.launch {
            runCatching {
                api.fetchServerConfig(_uiState.value.profile.clientId)
            }.onSuccess { cfg ->
                _uiState.update { it.copy(serverConfig = cfg, configLoaded = true) }
                mesh?.updateIceServers(cfg.iceServers)
            }
        }
    }

    // B22：节点连通性及 RTT 延迟测试
    suspend fun testServer(url: String): Result<Long> {
        return runCatching {
            api.measureServerHealth(url)
        }.onFailure { err ->
            val msg = err.message.orEmpty()
            if (msg.contains("CLEARTEXT", ignoreCase = true)) {
                return Result.failure(
                    IllegalStateException("Android 安全策略默认禁止非本机明文 HTTP，请使用 HTTPS 地址")
                )
            }
        }
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    // UX-12：同一提示文案 3 秒内自动去重防刷屏，并递增 toastSeq 驱动 UI 准确重置自动消失计时
    fun showToast(msg: String) {
        val clean = msg.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        if (clean == lastToastText && now - lastToastAt < TOAST_DEDUP_WINDOW_MS) {
            return
        }
        lastToastText = clean
        lastToastAt = now
        _uiState.update { it.copy(toastMessage = clean, toastSeq = it.toastSeq + 1L) }
    }

    // B21：清除错误文案时不重置 lobbyNeedPassword，避免用户清空密码框时输入框突然消失
    fun clearLobbyError() {
        _uiState.update { it.copy(lobbyError = null) }
    }

    // 当用户主动修改房间口令输入框并偏离 pendingJoinCode 时才清零密码门禁状态
    fun onJoinCodeInputChanged(rawInput: String) {
        val code = FormatUtils.extractRoomCode(rawInput)
        val pending = _uiState.value.pendingJoinCode
        if (pending != null && !code.equals(pending, ignoreCase = true)) {
            _uiState.update { it.copy(lobbyNeedPassword = false, pendingJoinCode = null, lobbyError = null) }
        }
    }

    fun consumePendingJoinCode() {
        _uiState.update { it.copy(pendingJoinCode = null) }
    }

    fun handleAppShortcutAction(action: AppShortcutAction) {
        _uiState.update { it.copy(pendingAppAction = action) }
    }

    fun consumePendingAppAction() {
        _uiState.update { it.copy(pendingAppAction = null) }
    }

    fun markOnboardingDone() {
        store.setOnboardingDone(true)
        _uiState.update { it.copy(prefs = it.prefs.copy(onboardingDone = true)) }
    }

    fun markNotificationAsked() {
        store.setNotificationAsked(true)
        _uiState.update { it.copy(prefs = it.prefs.copy(notificationAsked = true)) }
    }

    fun markCameraPermAsked() {
        store.setCameraPermAsked(true)
        _uiState.update { it.copy(prefs = it.prefs.copy(cameraPermAsked = true)) }
    }

    fun markAudioPermAsked() {
        store.setAudioPermAsked(true)
        _uiState.update { it.copy(prefs = it.prefs.copy(audioPermAsked = true)) }
    }

    fun handleIncomingDeepLink(rawUrl: String) {
        val trimmed = rawUrl.trim()
        // 识别桌面快捷方式自定义路由 baychat://action/*
        when {
            trimmed.equals("baychat://action/quick_send", ignoreCase = true) -> {
                handleAppShortcutAction(AppShortcutAction.QUICK_SEND)
                return
            }
            trimmed.equals("baychat://action/scan", ignoreCase = true) -> {
                handleAppShortcutAction(AppShortcutAction.SCAN)
                return
            }
            trimmed.equals("baychat://action/pickup", ignoreCase = true) -> {
                handleAppShortcutAction(AppShortcutAction.PICKUP)
                return
            }
        }
        val code = FormatUtils.extractRoomCode(trimmed)
        if (code.isNotBlank()) {
            _uiState.update { it.copy(pendingJoinCode = code) }
            if (!_uiState.value.privacyAccepted) return
            val current = _uiState.value.activeRoom
            if (current == null) {
                joinRoom(code, "")
            } else if (!current.roomCode.equals(code, ignoreCase = true)) {
                _uiState.update {
                    it.copy(pendingSwitchRoom = PendingSwitchRoom(targetCode = code, source = "深链邀请"))
                }
            } else {
                restoreRoom()
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
            showToast("已收到系统分享内容，创建或加入房间后即可发送")
        }
    }

    fun clearPendingShare() {
        _uiState.update { it.copy(pendingShare = PendingExternalShare()) }
    }

    fun sendPendingShareNow() {
        val share = _uiState.value.pendingShare
        if (!share.hasContent || _uiState.value.activeRoom == null) return
        clearPendingShare()
        updateRoom { it.copy(replyingTo = null) }
        share.texts.forEach { text ->
            if (text.isNotBlank()) sendTextMessage(text)
        }
        share.sharedText?.let { sendTextMessage(it) }
        if (share.uris.isNotEmpty()) {
            sendUris(share.uris)
        }
    }

    fun updateProfile(name: String, avatar: String) {
        val cleanName = name.trim().take(24).ifBlank { FormatUtils.generateRandomName() }
        val next = _uiState.value.profile.copy(name = cleanName, avatar = avatar)
        store.saveProfile(next)
        _uiState.update { st ->
            st.copy(
                profile = next,
                activeRoom = st.activeRoom?.let { r ->
                    r.copy(
                        messages = r.messages.map { m ->
                            if (m.isSelf) m.copy(senderName = next.name, senderAvatar = next.avatar) else m
                        }
                    )
                }
            )
        }
        signaling?.updateProfile(
            PeerProfile(
                peerId = selfPeerId,
                name = next.name,
                avatar = next.avatar,
                joinedAt = System.currentTimeMillis(),
                clientId = next.clientId,
            )
        )
        showToast("已更新昵称为「${next.name}」")
    }

    fun randomizeProfile() {
        updateProfile(FormatUtils.generateRandomName(), FormatUtils.PRESET_AVATARS.random().first)
    }

    fun selectTheme(mode: AppThemeMode) {
        if (mode == AppThemeMode.NEON) {
            // 兼容旧调用：自动切换为清风极简 + 深色外观
            store.saveTheme(AppThemeMode.AIR)
            setAppearance(AppearanceMode.DARK)
            _uiState.update { it.copy(themeMode = AppThemeMode.AIR) }
            return
        }
        store.saveTheme(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setAppearance(mode: AppearanceMode) {
        store.setAppearance(mode)
        _uiState.update { it.copy(prefs = it.prefs.copy(appearance = mode)) }
    }

    fun updatePrefs(next: AppUserPrefs) {
        val prevUrl = _uiState.value.prefs.serverUrl
        val prevNearby = _uiState.value.prefs.nearbyDiscoverable
        store.savePrefs(next)
        _uiState.update { it.copy(prefs = next) }
        if (prevUrl != next.serverUrl && _uiState.value.privacyAccepted) {
            refreshServerConfig()
            refreshPublicRooms()
        }
        if (prevNearby != next.nearbyDiscoverable && _uiState.value.privacyAccepted) {
            if (next.nearbyDiscoverable) {
                startNearbyPolling()
            } else {
                stopNearbyPolling()
                _uiState.update { it.copy(nearbyPeers = emptyList()) }
            }
        }
    }

    fun removeSavedRoom(code: String) {
        val next = store.removeSavedRoom(code)
        _uiState.update { it.copy(savedRooms = next) }
        showToast("已取消收藏 #$code")
    }

    fun renameSavedRoom(code: String, alias: String) {
        val next = store.renameSavedRoom(code, alias)
        _uiState.update { it.copy(savedRooms = next) }
        showToast(if (alias.isBlank()) "已清除 #$code 备注名" else "已将 #$code 备注为「${alias.trim()}」")
    }

    fun updateSavedPassword(code: String, newPassword: String) {
        val next = store.updateSavedPassword(code, newPassword)
        _uiState.update { it.copy(savedRooms = next) }
        showToast("已更新 #$code 本地保存密码")
    }

    fun removeRecentRoom(code: String) {
        val next = store.removeRecentRoom(code)
        _uiState.update { it.copy(recentRooms = next) }
    }

    fun clearRecentRooms() {
        val next = store.clearRecentRooms()
        _uiState.update { it.copy(recentRooms = next) }
        showToast("已清空最近房间记录")
    }

    private fun recordRecentRoom(
        code: String,
        mode: String,
        hasPassword: Boolean,
        password: String,
        ownerToken: String = "",
    ) {
        val updated = store.upsertRecentRoom(
            RecentRoom(
                code = code,
                mode = mode,
                hasPassword = hasPassword,
                password = password,
                ownerToken = ownerToken,
            )
        )
        _uiState.update { it.copy(recentRooms = updated) }
    }

    private fun resolveOwnerTokenForCode(code: String): String {
        val fromSaved = _uiState.value.savedRooms.firstOrNull { it.code.equals(code, ignoreCase = true) }?.ownerToken
        if (!fromSaved.isNullOrBlank()) return fromSaved
        val fromRecent = _uiState.value.recentRooms.firstOrNull { it.code.equals(code, ignoreCase = true) }?.ownerToken
        if (!fromRecent.isNullOrBlank()) return fromRecent
        return ""
    }

    fun buildInviteUrl(code: String): String = api.buildInviteUrl(code)

    // B1：发现广场专用公开建房入口（allowRandom = true）
    fun createPublicRoom(topic: String = "", retentionDays: Int = 1, maxFileSizeMB: Int = 0) {
        createAndEnterRoom(
            mode = RoomMode.EPHEMERAL,
            customCode = "",
            password = "",
            privatePairOnly = false,
            retentionDays = retentionDays,
            maxFileSizeMB = maxFileSizeMB,
            saveFixedFavorite = false,
            allowRandom = true,
            topic = topic,
        )
    }

    // B1：首页与口令建房默认私密（allowRandom = false）
    fun createAndEnterRoom(
        mode: RoomMode,
        customCode: String,
        password: String,
        privatePairOnly: Boolean,
        retentionDays: Int,
        maxFileSizeMB: Int,
        saveFixedFavorite: Boolean,
        allowRandom: Boolean = false,
        topic: String = "",
    ) {
        if (_uiState.value.lobbyBusy) return
        cancelVoiceRecording()
        _uiState.update { it.copy(lobbyBusy = true, lobbyError = null) }
        viewModelScope.launch {
            try {
                if (_uiState.value.activeRoom != null) {
                    signaling?.leaveRoom()
                    tearDownSession(stopService = false)
                }
                val maxPeers = if (privatePairOnly) 2 else _uiState.value.serverConfig.maxRoomPeers.coerceAtLeast(2)
                val cleanPwd = password.trim()
                val summary = api.createRoom(
                    CreateRoomRequest(
                        mode = mode.wireValue,
                        code = if (mode == RoomMode.FIXED) customCode.trim().takeIf { it.isNotBlank() } else null,
                        password = cleanPwd.takeIf { it.isNotBlank() },
                        allowRandom = if (cleanPwd.isBlank()) allowRandom else false,
                        topic = topic.trim().takeIf { it.isNotBlank() },
                        maxPeers = maxPeers,
                        retentionDays = retentionDays,
                        maxFileSizeMB = maxFileSizeMB,
                    )
                )
                val ownerToken = summary.ownerToken.orEmpty()
                if (mode == RoomMode.FIXED && saveFixedFavorite) {
                    val saved = store.upsertSavedRoom(
                        SavedFixedRoom(
                            code = summary.code,
                            hasPassword = summary.hasPassword,
                            password = cleanPwd,
                            ownerToken = ownerToken,
                        )
                    )
                    _uiState.update { it.copy(savedRooms = saved) }
                }
                recordRecentRoom(summary.code, summary.mode, summary.hasPassword, cleanPwd, ownerToken)
                enterRoomInternal(summary.code, cleanPwd, summary, ownerToken = ownerToken)
            } catch (e: ApiException) {
                val msg = if (e.errorCode == "CODE_RESERVED") {
                    "该口令为系统保留号段，请换一个包含字母的口令"
                } else {
                    e.message
                }
                _uiState.update { it.copy(lobbyError = msg) }
            } catch (e: Exception) {
                _uiState.update { it.copy(lobbyError = e.message ?: "创建房间失败") }
            } finally {
                _uiState.update { it.copy(lobbyBusy = false) }
            }
        }
    }

    // UX-6：公开房间加载失败写入可读错误，成功时清零 publicRoomsError
    private suspend fun fetchPublicRoomsInternal(silent: Boolean) {
        if (!silent) {
            _uiState.update { it.copy(publicRoomsLoading = true, publicRoomsError = null) }
        } else {
            _uiState.update { it.copy(publicRoomsError = null) }
        }
        runCatching { api.fetchPublicRooms() }
            .onSuccess { resp ->
                _uiState.update {
                    it.copy(
                        publicRooms = resp.rooms,
                        publicRoomsLoading = false,
                        publicRoomsError = null,
                        publicRoomsLastRefreshedAt = System.currentTimeMillis(),
                    )
                }
            }
            .onFailure {
                _uiState.update {
                    it.copy(
                        publicRoomsLoading = false,
                        publicRoomsError = "网络异常，公开房间加载失败",
                    )
                }
            }
    }

    fun refreshPublicRooms(silent: Boolean = false) {
        viewModelScope.launch {
            fetchPublicRoomsInternal(silent = silent)
        }
    }

    fun refreshPublicRoomsIfStale(staleMs: Long = DISCOVER_SWR_STALE_MS) {
        val current = _uiState.value
        val elapsed = System.currentTimeMillis() - current.publicRoomsLastRefreshedAt
        if (current.publicRooms.isEmpty() || elapsed >= staleMs) {
            refreshPublicRooms(silent = current.publicRooms.isNotEmpty())
        }
    }

    // 纸飞机公开文件清单加载、放飞、免密/直通加入与举报
    private suspend fun fetchPlanesInternal(silent: Boolean) {
        if (!silent) {
            _uiState.update { it.copy(planesLoading = true, planesError = null) }
        } else {
            _uiState.update { it.copy(planesError = null) }
        }
        runCatching { api.fetchPlanes(24) }
            .onSuccess { resp ->
                _uiState.update {
                    it.copy(
                        planes = resp.planes,
                        planesLoading = false,
                        planesError = null,
                        planesLastRefreshedAt = System.currentTimeMillis(),
                    )
                }
            }
            .onFailure {
                _uiState.update {
                    it.copy(
                        planesLoading = false,
                        planesError = "网络异常，纸飞机列表加载失败",
                    )
                }
            }
    }

    fun refreshPlanes(silent: Boolean = false) {
        viewModelScope.launch {
            fetchPlanesInternal(silent = silent)
        }
    }

    fun refreshPlanesIfStale(staleMs: Long = DISCOVER_SWR_STALE_MS) {
        val current = _uiState.value
        val elapsed = System.currentTimeMillis() - current.planesLastRefreshedAt
        if (current.planes.isEmpty() || elapsed >= staleMs) {
            refreshPlanes(silent = current.planes.isNotEmpty())
        }
    }

    fun planeCandidateFilesForActiveRoom(): List<PlaneFileMeta> {
        val room = _uiState.value.activeRoom ?: return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<PlaneFileMeta>()
        for (i in room.messages.indices.reversed()) {
            val m = room.messages[i]
            if (m.kind != ChatKind.FILE && m.kind != ChatKind.IMAGE && m.kind != ChatKind.AUDIO) continue
            val name = m.transfer?.fileName?.takeIf { it.isNotBlank() }
                ?: m.caption?.takeIf { it.isNotBlank() }
                ?: when (m.kind) {
                    ChatKind.IMAGE -> "图片附件.png"
                    ChatKind.AUDIO -> "语音消息.m4a"
                    else -> ""
                }
            if (name.isBlank() || !seen.add(name)) continue
            val mime = m.transfer?.mimeType ?: when (m.kind) {
                ChatKind.IMAGE -> "image/*"
                ChatKind.AUDIO -> "audio/*"
                else -> ""
            }
            result.add(
                PlaneFileMeta(
                    name = name,
                    size = m.transfer?.fileSize ?: 0L,
                    kind = classifyPlaneKind(name, mime),
                )
            )
            if (result.size >= 10) break
        }
        return result
    }

    fun createPlaneFromActiveRoom(
        message: String,
        files: List<PlaneFileMeta>,
        allowEntry: Boolean,
        onSuccess: () -> Unit,
    ) {
        if (_uiState.value.creatingPlane) return
        val state = _uiState.value
        val room = state.activeRoom ?: return
        val hasPwd = room.password.isNotBlank() || room.roomInfo?.hasPassword == true
        _uiState.update { it.copy(creatingPlane = true) }
        viewModelScope.launch {
            try {
                val created = api.createPlane(
                    CreatePlaneRequest(
                        clientId = state.profile.clientId,
                        roomCode = room.roomCode,
                        senderName = state.profile.name,
                        senderAvatar = state.profile.avatar,
                        message = message.trim(),
                        files = files.take(5),
                        allowEntry = allowEntry,
                        ownerToken = if (allowEntry && hasPwd && room.isOwner) room.ownerToken else null,
                        password = if (allowEntry && hasPwd && room.isOwner) room.password else null,
                    )
                )
                _uiState.update { it.copy(planes = listOf(created) + it.planes.filterNot { p -> p.id == created.id }) }
                showToast("纸飞机已放飞到大厅广场，24 小时内有效")
                onSuccess()
            } catch (err: Exception) {
                showToast(err.message ?: "放飞纸飞机失败")
            } finally {
                _uiState.update { it.copy(creatingPlane = false) }
            }
        }
    }

    fun joinFromPlane(
        plane: PaperPlane,
        onJoined: () -> Unit,
        onPasswordExpired: () -> Unit,
        onFinished: () -> Unit = {},
    ) {
        viewModelScope.launch {
            try {
                val res = api.joinPlane(plane.id)
                onJoined()
                val current = _uiState.value.activeRoom
                if (current != null && current.roomCode.equals(res.roomCode, ignoreCase = true)) {
                    restoreRoom()
                } else if (current != null) {
                    _uiState.update {
                        it.copy(
                            pendingSwitchRoom = PendingSwitchRoom(
                                targetCode = res.roomCode,
                                password = res.password,
                                source = "纸飞机",
                            )
                        )
                    }
                } else {
                    joinRoomInternal(res.roomCode, res.password)
                }
            } catch (e: ApiException) {
                if (e.errorCode == "PLANE_ENTRY_EXPIRED") {
                    _uiState.update { st ->
                        st.copy(
                            planes = st.planes.map { if (it.id == plane.id) it.copy(allowEntry = false) else it },
                            pendingJoinCode = plane.roomCode,
                            lobbyNeedPassword = true,
                            lobbyError = "房主已修改密码，请输入密码进入",
                        )
                    }
                    showToast("房主已修改密码，请输入密码进入")
                    onPasswordExpired()
                } else if (e.errorCode == "PLANE_NOT_FOUND" || e.errorCode == "PLANE_EXPIRED") {
                    _uiState.update { st -> st.copy(planes = st.planes.filterNot { it.id == plane.id }) }
                    onJoined()
                    showToast(e.message)
                } else {
                    showToast(e.message)
                }
            } catch (e: Exception) {
                showToast(e.message ?: "通过纸飞机进入房间失败")
            } finally {
                onFinished()
            }
        }
    }

    fun reportPlane(planeId: String, onDone: (success: Boolean, deleted: Boolean) -> Unit) {
        val clientId = _uiState.value.profile.clientId
        viewModelScope.launch {
            runCatching { api.reportPlane(planeId, clientId) }
                .onSuccess { res ->
                    if (res.deleted) {
                        _uiState.update { st -> st.copy(planes = st.planes.filterNot { it.id == planeId }) }
                        showToast("该纸飞机已被多名用户举报并移除")
                    } else {
                        showToast("已收到举报，系统将在累计达到阈值后自动清理")
                    }
                    onDone(true, res.deleted)
                }
                .onFailure { err ->
                    showToast(err.message ?: "举报提交失败")
                    onDone(false, false)
                }
        }
    }

    // UX-7：真实聚合等待所有刷新子协程彻底完成后才重置 isLobbyRefreshing = false
    fun refreshLobbyOverview() {
        if (_uiState.value.isLobbyRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLobbyRefreshing = true) }
            try {
                val cfgJob = launch {
                    runCatching { api.fetchServerConfig(_uiState.value.profile.clientId) }
                        .onSuccess { cfg ->
                            _uiState.update { it.copy(serverConfig = cfg, configLoaded = true) }
                            mesh?.updateIceServers(cfg.iceServers)
                        }
                }
                val roomsJob = launch {
                    fetchPublicRoomsInternal(silent = _uiState.value.publicRooms.isNotEmpty())
                }
                val statsJob = launch { storageStats() }
                checkForAppUpdate(manual = false, bypassThrottle = false)
                cfgJob.join()
                roomsJob.join()
                statsJob.join()
            } finally {
                _uiState.update { it.copy(isLobbyRefreshing = false) }
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        store.setSoundEnabled(enabled)
        _uiState.update { it.copy(prefs = it.prefs.copy(soundEnabled = enabled)) }
        if (enabled) {
            runCatching { soundPlayer.playRefreshTick() }
        }
    }

    fun setHapticEnabled(enabled: Boolean) {
        store.setHapticEnabled(enabled)
        _uiState.update { it.copy(prefs = it.prefs.copy(hapticEnabled = enabled)) }
    }

    fun playRefreshSound() {
        playSoundIfEnabled { playRefreshTick() }
    }

    private fun playSoundIfEnabled(action: BaySoundPlayer.() -> Unit) {
        if (!_uiState.value.prefs.soundEnabled) return
        runCatching { soundPlayer.action() }
    }

    fun joinRandomPublicRoom(excludeCurrent: Boolean = false) {
        if (_uiState.value.randomJoining || _uiState.value.lobbyBusy) return
        val active = _uiState.value.activeRoom
        val exclude = if (excludeCurrent) active?.roomCode.orEmpty() else ""
        _uiState.update { it.copy(randomJoining = true, lobbyError = null) }
        viewModelScope.launch {
            try {
                val target = api.pickRandomPublicRoom(exclude)
                if (target.hasPassword) {
                    showToast("已跳过加密房间")
                    return@launch
                }
                // B13：从大厅随机进入时若已有挂起房间，先请求用户确认切换
                if (!excludeCurrent && active != null && !active.roomCode.equals(target.code, ignoreCase = true)) {
                    _uiState.update {
                        it.copy(
                            pendingSwitchRoom = PendingSwitchRoom(
                                targetCode = target.code,
                                password = "",
                                source = "随机串门",
                                roomSummary = target,
                            )
                        )
                    }
                    return@launch
                }
                if (excludeCurrent && active != null) {
                    signaling?.leaveRoom()
                    tearDownSession(stopService = false)
                }
                recordRecentRoom(target.code, target.mode, false, "")
                enterRoomInternal(target.code, "", target)
                val topicSuffix = if (target.topic.isNotBlank()) " · ${target.topic}" else ""
                showToast("已进入公开房间 #${target.code}$topicSuffix")
            } catch (e: ApiException) {
                if (e.errorCode == "NO_PUBLIC_ROOM") {
                    val hint = if (excludeCurrent) "暂无其他免密公开房可切换" else e.message
                    showToast(hint)
                } else {
                    showToast(e.message)
                }
                refreshPublicRooms()
            } catch (e: Exception) {
                showToast(e.message ?: "随机匹配失败，请稍后重试")
            } finally {
                _uiState.update { it.copy(randomJoining = false) }
            }
        }
    }

    // B4/B24/UX-7：只扫描 resume_ 前缀或已有元信息的普通文件断点，过滤图片/语音临时碎片，并维护真实下拉刷新状态
    fun refreshTransferCenter() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isTransferRefreshing = true) }
            try {
                val dir = WebRtcMeshManager.transferCacheDir(appContext)
                val metaMap = store.loadPartialMetaMap()
                val partials = dir.listFiles()
                    ?.filter { f ->
                        f.isFile &&
                            f.name.endsWith(".part") &&
                            f.length() > 0L &&
                            (f.name.startsWith("resume_") || metaMap.containsKey(f.name))
                    }
                    ?.map { f ->
                        val meta = metaMap[f.name]
                        PartialResumeItem(
                            partFileName = f.name,
                            fileName = meta?.fileName ?: f.name,
                            savedBytes = f.length(),
                            totalBytes = meta?.fileSize ?: f.length(),
                            roomCode = meta?.roomCode ?: "",
                            peerName = meta?.peerName ?: "",
                            lastModified = f.lastModified(),
                            resumeKey = meta?.resumeKey.orEmpty(),
                        )
                    }
                    ?.sortedByDescending { it.lastModified }
                    .orEmpty()
                val history = store.loadTransferRecords()
                _uiState.update { it.copy(partialTransfers = partials, transferRecords = history) }
                storageStats()
            } finally {
                _uiState.update { it.copy(isTransferRefreshing = false) }
            }
        }
    }

    fun deletePartialTransfer(partFileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val f = File(WebRtcMeshManager.transferCacheDir(appContext), partFileName)
            runCatching { f.delete() }
            store.removePartialMeta(partFileName)
            refreshTransferCenter()
            withContext(Dispatchers.Main) { showToast("已清理该断点缓存") }
        }
    }

    fun clearAllPartialTransfers() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = WebRtcMeshManager.transferCacheDir(appContext)
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".part")) {
                    runCatching { f.delete() }
                    store.removePartialMeta(f.name)
                }
            }
            refreshTransferCenter()
            withContext(Dispatchers.Main) { showToast("已清空全部未完成断点缓存") }
        }
    }

    // G2：向房间内对端广播断点续传请求
    fun requestResumeFromTransferCenter(item: PartialResumeItem) {
        val room = _uiState.value.activeRoom
        if (room == null) {
            showToast("请先进入房间 #${item.roomCode.ifBlank { "对应" }} 后再请求续传")
            return
        }
        val key = item.resumeKey.ifBlank { item.partFileName.removePrefix("resume_").removeSuffix(".part") }
        val sent = mesh?.broadcastResumeRequest(key, item.fileName, item.totalBytes, item.savedBytes) ?: 0
        if (sent > 0) {
            showToast("已向房内在线伙伴请求继续发送「${item.fileName}」")
        } else {
            showToast("暂无已打通的 P2P 伙伴，等待伙伴上线后重试")
        }
    }

    fun removeTransferRecord(id: String) {
        val next = store.removeTransferRecord(id)
        _uiState.update { it.copy(transferRecords = next) }
    }

    fun clearTransferRecords() {
        val next = store.clearTransferRecords()
        _uiState.update { it.copy(transferRecords = next) }
        showToast("已清空传输记录列表（本地已保存文件不受影响）")
    }

    // B7/G3/G12/G13/UX-34：统一打开/分享已接收文件，打开前先校验本地文件存在性与可用应用
    private fun openMediaWithDiagnostics(
        contentUri: String?,
        localPath: String?,
        mimeType: String,
    ): Boolean {
        var result = MediaDownloadHelper.OpenFileResult.FILE_NOT_FOUND
        if (!contentUri.isNullOrBlank()) {
            result = MediaDownloadHelper.openContentUriDetailed(appContext, contentUri, mimeType)
            if (result == MediaDownloadHelper.OpenFileResult.OPENED) return true
        }
        if (!localPath.isNullOrBlank()) {
            val localRes = MediaDownloadHelper.openLocalFileDetailed(appContext, localPath, mimeType)
            if (localRes == MediaDownloadHelper.OpenFileResult.OPENED) return true
            if (localRes == MediaDownloadHelper.OpenFileResult.NO_APP_FOUND || result != MediaDownloadHelper.OpenFileResult.NO_APP_FOUND) {
                result = localRes
            }
        }
        when (result) {
            MediaDownloadHelper.OpenFileResult.NO_APP_FOUND -> showToast("未找到可打开此格式文件的应用")
            MediaDownloadHelper.OpenFileResult.FILE_NOT_FOUND -> showToast("本地文件已被移动或删除")
            MediaDownloadHelper.OpenFileResult.OPENED -> Unit
        }
        return false
    }

    fun openTransfer(record: TransferRecord): Boolean {
        return openMediaWithDiagnostics(record.contentUri, record.localPath, record.mimeType)
    }

    fun openTransfer(info: FileTransferInfo): Boolean {
        return openMediaWithDiagnostics(info.contentUri, info.localPath, info.mimeType)
    }

    fun shareTransfer(record: TransferRecord): Boolean {
        val source = record.contentUri?.takeIf { it.isNotBlank() } ?: record.localPath
        val shared = !source.isNullOrBlank() &&
            MediaDownloadHelper.shareUriOrPath(appContext, source, record.mimeType, "分享 ${record.fileName}")
        if (!shared) showToast("找不到可分享的本地文件")
        return shared
    }

    fun shareChatItem(item: ChatItem): Boolean {
        val source = item.transfer?.contentUri?.takeIf { it.isNotBlank() }
            ?: item.localFilePath?.takeIf { it.isNotBlank() }
            ?: item.transfer?.localPath?.takeIf { it.isNotBlank() }
            ?: item.imageUrl?.takeIf { it.startsWith("content://") || it.startsWith("file://") || it.startsWith("/") }
        val mime = item.transfer?.mimeType ?: if (item.kind == ChatKind.IMAGE) "image/*" else "*/*"
        val shared = !source.isNullOrBlank() &&
            MediaDownloadHelper.shareUriOrPath(appContext, source, mime, "分享内容")
        if (!shared) showToast("该条内容暂无可分享的本地文件")
        return shared
    }

    fun saveImageToGallery(item: ChatItem): Boolean {
        val src = item.localFilePath?.takeIf { it.isNotBlank() }
            ?: item.transfer?.localPath?.takeIf { it.isNotBlank() }
            ?: item.transfer?.contentUri?.takeIf { it.isNotBlank() }
            ?: item.imageUrl?.takeIf { it.isNotBlank() }
        if (src.isNullOrBlank()) {
            showToast("图片尚未就绪")
            return false
        }
        if (src.startsWith("http://") || src.startsWith("https://")) {
            val fileName = item.transfer?.fileName ?: "baychat_${item.timestamp}.jpg"
            val roomPwd = _uiState.value.activeRoom?.password.orEmpty()
            viewModelScope.launch(Dispatchers.IO) {
                val tmp = File(appContext.cacheDir, "gallery_dl_${System.currentTimeMillis()}.tmp")
                val ok = runCatching {
                    api.downloadCachedFileToTemp(src, tmp, password = roomPwd) { _, _ -> }
                    MediaDownloadHelper.saveReceivedFile(
                        context = appContext,
                        sourceFile = tmp,
                        fileName = fileName,
                        mimeType = item.transfer?.mimeType ?: "image/jpeg",
                        keepLocalPreviewCopy = false,
                    )
                }.isSuccess
                if (!ok) runCatching { tmp.delete() }
                withContext(Dispatchers.Main) {
                    showToast(if (ok) "已保存到系统相册 Pictures/BayChat" else "保存图片失败")
                }
            }
            return true
        }
        val saved = MediaDownloadHelper.saveImageToGallery(
            context = appContext,
            sourcePathOrUri = src,
            fileName = item.transfer?.fileName ?: "baychat_${item.timestamp}.jpg",
            mimeType = item.transfer?.mimeType ?: "image/jpeg",
        )
        showToast(if (saved != null) "已保存至 ${saved.displayPath}" else "保存图片失败")
        return saved != null
    }

    fun openDownloadsFolder(): Boolean {
        val ok = MediaDownloadHelper.openDownloadsFolder(appContext)
        if (!ok) showToast("请在系统「文件管理」中查看 Download/BayChat")
        return ok
    }

    // G11：统计与分类清理本地存储占用
    suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        val stats = StorageStats(
            partialsBytes = dirSize(WebRtcMeshManager.transferCacheDir(appContext)),
            receivedMediaBytes = dirSize(File(appContext.filesDir, "received_media")),
            coilCacheBytes = dirSize(File(appContext.cacheDir, "image_cache")),
            updatesBytes = dirSize(AppUpdateManager.updatesDir(appContext)),
            exportsBytes = dirSize(File(appContext.cacheDir, "folder_zips")) +
                dirSize(File(appContext.cacheDir, "cached_downloads")) +
                dirSize(File(appContext.cacheDir, "camera_captures")) +
                dirSize(File(appContext.cacheDir, "voice_rec")),
        )
        _uiState.update { it.copy(storageStats = stats) }
        stats
    }

    fun refreshStorageStats() {
        viewModelScope.launch { storageStats() }
    }

    fun clearStorage(category: StorageCategory) {
        viewModelScope.launch(Dispatchers.IO) {
            when (category) {
                StorageCategory.PARTIALS -> {
                    val dir = WebRtcMeshManager.transferCacheDir(appContext)
                    dir.listFiles()?.forEach { f ->
                        runCatching { f.delete() }
                        store.removePartialMeta(f.name)
                    }
                    refreshTransferCenter()
                }
                StorageCategory.RECEIVED_MEDIA -> {
                    deleteDirContents(File(appContext.filesDir, "received_media"))
                }
                StorageCategory.COIL_CACHE -> {
                    deleteDirContents(File(appContext.cacheDir, "image_cache"))
                }
                StorageCategory.UPDATES -> {
                    deleteDirContents(AppUpdateManager.updatesDir(appContext))
                }
                StorageCategory.EXPORTS -> {
                    deleteDirContents(File(appContext.cacheDir, "folder_zips"))
                    deleteDirContents(File(appContext.cacheDir, "cached_downloads"))
                    deleteDirContents(File(appContext.cacheDir, "camera_captures"))
                    deleteDirContents(File(appContext.cacheDir, "voice_rec"))
                }
            }
            storageStats()
            withContext(Dispatchers.Main) { showToast("已清理所选缓存空间") }
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun deleteDirContents(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // 自动检查应用更新
    fun checkForAppUpdate(manual: Boolean = false, bypassThrottle: Boolean = false) {
        val currentOta = AppUpdateCenter.state.value
        if (currentOta.status == OtaUpdateStatus.CHECKING) return

        if (currentOta.status == OtaUpdateStatus.DOWNLOADING || currentOta.status == OtaUpdateStatus.VERIFYING) {
            if (manual) {
                _uiState.update { it.copy(showUpdateDialog = true) }
                showToast("新版本正在后台下载中...")
            }
            return
        }

        val now = System.currentTimeMillis()
        if (!manual) {
            // 非手动检查时严禁覆盖用户手动暂停或已失败/就绪状态，杜绝前后台切换重新拉起二次下载
            if (currentOta.status == OtaUpdateStatus.PAUSED ||
                currentOta.status == OtaUpdateStatus.READY_TO_INSTALL ||
                currentOta.status == OtaUpdateStatus.ERROR
            ) {
                return
            }
            if (!bypassThrottle && now - _uiState.value.prefs.lastUpdateCheckAt < FOREGROUND_UPDATE_CHECK_INTERVAL_MS) {
                return
            }
        }

        store.setLastUpdateCheckAt(now)
        _uiState.update { it.copy(prefs = it.prefs.copy(lastUpdateCheckAt = now)) }

        val previousOta = currentOta
        AppUpdateCenter.updateState {
            it.copy(status = OtaUpdateStatus.CHECKING, errorMessage = null)
        }

        viewModelScope.launch {
            runCatching { api.fetchLatestRelease() }
                .onSuccess { rel ->
                    if (rel.versionCode > BuildConfig.VERSION_CODE) {
                        handleDiscoveredRelease(rel = rel, manual = manual)
                    } else {
                        AppUpdateCenter.updateState { OtaUpdateState(status = OtaUpdateStatus.IDLE) }
                        if (manual) showToast("当前已是最新版本 v${BuildConfig.VERSION_NAME}")
                    }
                }
                .onFailure {
                    val restored = if (previousOta.release != null &&
                        previousOta.status != OtaUpdateStatus.IDLE &&
                        previousOta.status != OtaUpdateStatus.CHECKING
                    ) {
                        previousOta
                    } else {
                        OtaUpdateState(status = OtaUpdateStatus.IDLE)
                    }
                    AppUpdateCenter.updateState { restored }
                    if (manual) showToast("检查更新失败，请检查网络连接")
                }
        }
    }

    private suspend fun handleDiscoveredRelease(rel: AppReleaseInfo, manual: Boolean) {
        val isForce = rel.forceUpdate || (rel.minSupportedCode > 0 && BuildConfig.VERSION_CODE < rel.minSupportedCode)
        var currentPrefs = _uiState.value.prefs

        if (manual) {
            dismissedUpdateVersionInSession = 0
        }
        if (manual && currentPrefs.ignoredVersionCode == rel.versionCode) {
            store.setIgnoredVersionCode(0)
            currentPrefs = currentPrefs.copy(ignoredVersionCode = 0)
            _uiState.update { it.copy(prefs = currentPrefs) }
        }

        val isIgnored = !manual && !isForce && currentPrefs.ignoredVersionCode == rel.versionCode
        val isSessionDismissed = !manual && !isForce && dismissedUpdateVersionInSession == rel.versionCode

        val inspection = updateManager.inspectLocalRelease(rel)
        val readyApk = inspection.readyApkFile
        if (readyApk != null) {
            val size = readyApk.length()
            AppUpdateCenter.updateState {
                OtaUpdateState(
                    status = OtaUpdateStatus.READY_TO_INSTALL,
                    release = rel,
                    downloadedBytes = size,
                    totalBytes = size,
                    speedBytesPerSec = 0L,
                    errorMessage = null,
                    apkFilePath = readyApk.absolutePath,
                    isSilentBackground = false,
                    isForceUpdate = isForce,
                )
            }
            if (manual || isForce) {
                _uiState.update { it.copy(showUpdateDialog = true) }
                if (manual) showToast("v${rel.versionName} 安装包已就绪，可立即安装")
            }
            return
        }

        AppUpdateCenter.updateState {
            OtaUpdateState(
                status = OtaUpdateStatus.AVAILABLE,
                release = rel,
                downloadedBytes = inspection.partialBytes,
                totalBytes = rel.sizeBytes.coerceAtLeast(inspection.partialBytes),
                speedBytesPerSec = 0L,
                errorMessage = null,
                apkFilePath = null,
                isSilentBackground = false,
                isForceUpdate = isForce,
            )
        }

        if (isIgnored) return

        val canSilentWifiDownload = !manual &&
            currentPrefs.autoDownloadOnWifi &&
            AppUpdateManager.isUnmeteredWifi(appContext)

        if (canSilentWifiDownload) {
            val started = AppUpdateService.startDownload(
                context = appContext,
                release = rel,
                silent = !isForce,
                forceUpdate = isForce,
            )
            if (isForce || (!started && !isSessionDismissed)) {
                _uiState.update { it.copy(showUpdateDialog = true) }
            }
        } else if (!isSessionDismissed) {
            _uiState.update { it.copy(showUpdateDialog = true) }
            if (manual) {
                showToast("发现新版本 v${rel.versionName}")
            }
        }
    }

    fun startOrResumeAppUpdate(silent: Boolean = false) {
        val ota = AppUpdateCenter.state.value
        val release = ota.release ?: return
        if (ota.status == OtaUpdateStatus.READY_TO_INSTALL && !ota.apkFilePath.isNullOrBlank()) {
            installReadyAppUpdate()
            return
        }
        // 正在下载或校验中禁止重复启动；同时对短时间连点做 600ms 幂等防抖，杜绝下发双份服务 Intent
        if (ota.status == OtaUpdateStatus.DOWNLOADING || ota.status == OtaUpdateStatus.VERIFYING) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastOtaStartRequestMs < 600L) {
            return
        }
        lastOtaStartRequestMs = now
        if (_uiState.value.prefs.ignoredVersionCode == release.versionCode) {
            store.setIgnoredVersionCode(0)
            _uiState.update { it.copy(prefs = it.prefs.copy(ignoredVersionCode = 0)) }
        }
        val started = AppUpdateService.startDownload(
            context = appContext,
            release = release,
            silent = silent,
            forceUpdate = ota.isForceUpdate,
        )
        if (!started) {
            lastOtaStartRequestMs = 0L
            showToast("启动后台更新服务失败，请稍后重试")
        }
    }

    fun pauseAppUpdate() {
        AppUpdateService.pauseDownload(appContext)
        showToast("已暂停更新，本地断点已安全保留")
    }

    fun installReadyAppUpdate() {
        val ota = AppUpdateCenter.state.value
        val apkFile = ota.apkFilePath?.let { File(it) }
            ?: AppUpdateManager.updatesDir(appContext)
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(".apk") && it.length() > 0L }
                ?.maxByOrNull { it.lastModified() }
        if (apkFile == null || !apkFile.exists()) {
            if (ota.release != null) {
                showToast("本地安装包已失效，正在重新获取...")
                startOrResumeAppUpdate(silent = false)
            } else {
                checkForAppUpdate(manual = true)
            }
            return
        }
        when (val outcome = AppUpdateManager.launchInstaller(appContext, apkFile)) {
            is AppUpdateManager.UpdateInstallOutcome.InstallerLaunched -> {
                awaitingInstallPermission = false
                showToast("正在拉起系统安装器，请按提示完成覆盖升级")
            }
            is AppUpdateManager.UpdateInstallOutcome.NeedInstallPermission -> {
                awaitingInstallPermission = true
                showToast("请先开启「允许安装未知应用」权限，返回后将自动继续安装")
            }
            is AppUpdateManager.UpdateInstallOutcome.Error -> {
                showToast("更新失败：${outcome.message}")
            }
        }
    }

    fun ignoreCurrentUpdate() {
        val ota = AppUpdateCenter.state.value
        if (ota.isForceUpdate) {
            showToast("当前版本过低，需先升级至最新版本后方可继续使用")
            return
        }
        val release = ota.release ?: return
        store.setIgnoredVersionCode(release.versionCode)
        _uiState.update {
            it.copy(
                prefs = it.prefs.copy(ignoredVersionCode = release.versionCode),
                showUpdateDialog = false,
            )
        }
        showToast("已忽略 v${release.versionName}，手动点「检查更新」仍可随时升级")
    }

    fun openUpdateDialog() {
        if (AppUpdateCenter.state.value.release != null) {
            _uiState.update { it.copy(showUpdateDialog = true) }
        }
    }

    fun dismissUpdateDialog() {
        val ota = AppUpdateCenter.state.value
        if (ota.isForceUpdate) return
        // UX-17：本会话内暂缓自动重复弹窗（保留大厅横幅与「我的」红点提示）
        ota.release?.versionCode?.let { ver ->
            if (ver > 0) dismissedUpdateVersionInSession = ver
        }
        _uiState.update { it.copy(showUpdateDialog = false) }
    }

    fun setAutoDownloadOnWifi(enabled: Boolean) {
        store.setAutoDownloadOnWifi(enabled)
        _uiState.update { it.copy(prefs = it.prefs.copy(autoDownloadOnWifi = enabled)) }
        showToast(
            if (enabled) "已开启 Wi-Fi 自动后台下载更新（移动网络不消耗流量）"
            else "已关闭 Wi-Fi 自动后台下载更新"
        )
    }

    // B3：真实持久化屏蔽（立即断连并拒绝一切消息/文件 offer）
    fun blockPeer(peerId: String, peerName: String) {
        val resolvedClientId = _uiState.value.activeRoom?.peers?.get(peerId)?.clientId
        blockPeer(peerId = peerId, clientId = resolvedClientId, peerName = peerName)
    }

    fun blockPeer(peerId: String, clientId: String?, peerName: String) {
        if (peerId.isBlank() || peerId == selfPeerId) return
        val effectiveClientId = clientId?.takeIf { it.isNotBlank() } ?: "name:${peerName.trim()}"
        blockedPeerIdToClientId[peerId] = effectiveClientId
        val updatedBlocked = store.addBlockedPeer(
            BlockedPeer(
                clientId = effectiveClientId,
                name = peerName.trim().ifBlank { "未知成员" },
                blockedAt = System.currentTimeMillis(),
            )
        )
        mesh?.removePeer(peerId)
        _uiState.update { st ->
            st.copy(
                blockedPeerIds = st.blockedPeerIds + peerId,
                blockedClients = updatedBlocked,
                activeRoom = st.activeRoom?.let { r ->
                    r.copy(
                        peers = r.peers - peerId,
                        linkStatuses = r.linkStatuses - peerId,
                        typingPeers = r.typingPeers - peerId,
                    )
                },
            )
        }
        showToast("已永久屏蔽「$peerName」，并断开其传输连接")
    }

    fun unblockClient(clientId: String) {
        val target = _uiState.value.blockedClients.firstOrNull { it.clientId.equals(clientId, ignoreCase = true) }
        val next = store.removeBlockedPeer(clientId)
        _uiState.update { st ->
            val currentPeers = st.activeRoom?.peers.orEmpty()
            val cleanedPeerIds = st.blockedPeerIds.filterNot { pid ->
                val p = currentPeers[pid]
                val mapped = blockedPeerIdToClientId[pid]
                val matched = mapped.equals(clientId, ignoreCase = true) ||
                    p?.clientId.equals(clientId, ignoreCase = true) ||
                    (target != null && p?.name.equals(target.name, ignoreCase = true))
                if (matched) blockedPeerIdToClientId.remove(pid)
                matched
            }.toSet()
            st.copy(blockedClients = next, blockedPeerIds = cleanedPeerIds)
        }
        showToast("已解除屏蔽")
    }

    private fun isPeerBlockedInternal(peerId: String?, clientId: String? = null, name: String? = null): Boolean {
        val st = _uiState.value
        if (!peerId.isNullOrBlank() && peerId in st.blockedPeerIds) return true
        val resolvedClientId = clientId ?: peerId?.let { st.activeRoom?.peers?.get(it)?.clientId }
        val resolvedName = name ?: peerId?.let { st.activeRoom?.peers?.get(it)?.name }
        if (!resolvedClientId.isNullOrBlank() &&
            st.blockedClients.any { it.clientId.equals(resolvedClientId, ignoreCase = true) }
        ) {
            return true
        }
        if (!resolvedName.isNullOrBlank()) {
            val clean = resolvedName.trim()
            if (st.blockedClients.any { it.clientId == "name:$clean" || it.name.equals(clean, ignoreCase = true) }) {
                return true
            }
        }
        return false
    }

    // G1：举报违规成员并上报服务端 /api/report
    fun reportPeer(peerName: String) {
        reportPeer(peerId = "", peerName = peerName, messageId = null, reason = "违规内容")
    }

    fun reportPeer(peerId: String, peerName: String, messageId: String? = null, reason: String = "违规内容") {
        val roomCode = _uiState.value.activeRoom?.roomCode.orEmpty()
        val targetClientId = _uiState.value.activeRoom?.peers?.get(peerId)?.clientId.orEmpty()
        viewModelScope.launch {
            runCatching {
                api.reportPeer(
                    ReportRequest(
                        roomCode = roomCode,
                        reportedName = peerName,
                        reportedClientId = targetClientId,
                        messageId = messageId,
                        reason = reason,
                        reporterClientId = _uiState.value.profile.clientId,
                    )
                )
            }
            showToast("已提交对「$peerName」的违规举报，建议屏蔽对方或离开房间")
        }
    }

    // B1/B6/B13：单入口直达，存在未建房口令时先弹窗确认，存在活跃房间时先确认切换
    fun enterOrCreateRoom(
        rawInput: String,
        mode: RoomMode,
        password: String,
        privatePairOnly: Boolean,
        retentionDays: Int,
        maxFileSizeMB: Int,
        saveFixedFavorite: Boolean,
        allowRandom: Boolean = false,
        topic: String = "",
    ) {
        val code = FormatUtils.extractRoomCode(rawInput)
        if (code.isBlank()) {
            if (_uiState.value.activeRoom != null) {
                signaling?.leaveRoom()
                tearDownSession(stopService = false)
            }
            createAndEnterRoom(
                mode = mode,
                customCode = "",
                password = password,
                privatePairOnly = privatePairOnly,
                retentionDays = retentionDays,
                maxFileSizeMB = maxFileSizeMB,
                saveFixedFavorite = saveFixedFavorite,
                allowRandom = allowRandom,
                topic = topic,
            )
            return
        }
        val current = _uiState.value.activeRoom
        if (current != null && !current.roomCode.equals(code, ignoreCase = true)) {
            _uiState.update {
                it.copy(
                    pendingSwitchRoom = PendingSwitchRoom(
                        targetCode = code,
                        password = password.trim(),
                        source = "口令直达",
                    )
                )
            }
            return
        }
        if (_uiState.value.lobbyBusy) return
        _uiState.update { it.copy(lobbyBusy = true, lobbyError = null) }

        viewModelScope.launch {
            try {
                val cleanPwd = password.trim()
                val existing = try {
                    api.fetchRoomInfo(code)
                } catch (e: ApiException) {
                    if (e.errorCode == "ROOM_NOT_FOUND") null else throw e
                }

                if (existing != null) {
                    if (existing.hasPassword) {
                        if (cleanPwd.isBlank()) {
                            _uiState.update {
                                it.copy(
                                    lobbyError = "房间 #$code 设有暗号密码，请输入密码后进入",
                                    lobbyNeedPassword = true,
                                    pendingJoinCode = code,
                                )
                            }
                            return@launch
                        }
                        api.verifyRoomPassword(code, cleanPwd)
                    }
                    val existingOwner = resolveOwnerTokenForCode(existing.code)
                    if (existing.mode == RoomMode.FIXED.wireValue) {
                        val saved = store.upsertSavedRoom(
                            SavedFixedRoom(
                                code = existing.code,
                                hasPassword = existing.hasPassword,
                                password = cleanPwd,
                                ownerToken = existingOwner,
                            )
                        )
                        _uiState.update { it.copy(savedRooms = saved) }
                    }
                    recordRecentRoom(existing.code, existing.mode, existing.hasPassword, cleanPwd, existingOwner)
                    enterRoomInternal(existing.code, cleanPwd, existing, ownerToken = existingOwner)
                } else {
                    // B6：房间不存在时绝不直接建房；6 位纯数字禁止误建常驻房
                    if (code.matches(Regex("^\\d{6}$"))) {
                        _uiState.update {
                            it.copy(lobbyError = "临时房间已结束或口令有误")
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                pendingCreateConfirm = PendingCreateConfirm(
                                    code = code,
                                    password = cleanPwd,
                                    retentionDays = retentionDays,
                                    maxFileSizeMB = maxFileSizeMB,
                                    privatePairOnly = privatePairOnly,
                                    saveFixedFavorite = saveFixedFavorite,
                                    allowRandom = allowRandom,
                                    topic = topic,
                                )
                            )
                        }
                    }
                }
            } catch (e: ApiException) {
                val needPwd = e.errorCode == "WRONG_PASSWORD"
                _uiState.update {
                    it.copy(
                        lobbyError = e.message,
                        lobbyNeedPassword = needPwd || it.lobbyNeedPassword,
                        pendingJoinCode = code,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(lobbyError = e.message ?: "无法连接到服务器") }
            } finally {
                _uiState.update { it.copy(lobbyBusy = false) }
            }
        }
    }

    // B6：用户弹窗确认后正式创建常驻房并进入
    fun confirmCreateFixedRoom() {
        val pending = _uiState.value.pendingCreateConfirm ?: return
        _uiState.update { it.copy(pendingCreateConfirm = null) }
        createAndEnterRoom(
            mode = RoomMode.FIXED,
            customCode = pending.code,
            password = pending.password,
            privatePairOnly = pending.privatePairOnly,
            retentionDays = pending.retentionDays,
            maxFileSizeMB = pending.maxFileSizeMB,
            saveFixedFavorite = pending.saveFixedFavorite,
            allowRandom = pending.allowRandom,
            topic = pending.topic,
        )
    }

    fun dismissCreateFixedConfirm() {
        _uiState.update { it.copy(pendingCreateConfirm = null) }
    }

    // B13/UX-15：用户确认切换房间后，先发 leaveRoom 再进新房（若携带待处理的附近邀请，确认后再回复服务端）
    fun confirmSwitchRoom() {
        val pending = _uiState.value.pendingSwitchRoom ?: return
        cancelVoiceRecording()
        _uiState.update { st ->
            val pendingInvite = pending.pendingNearbyInvite
            st.copy(
                pendingSwitchRoom = null,
                incomingNearbyInvites = if (pendingInvite != null) {
                    st.incomingNearbyInvites.filterNot { it.inviteId == pendingInvite.inviteId }
                } else {
                    st.incomingNearbyInvites
                },
            )
        }
        signaling?.leaveRoom()
        tearDownSession(stopService = false)
        _uiState.update {
            it.copy(
                activeRoom = null,
                roomMinimized = false,
                unreadCount = 0,
                playedVoiceIds = emptySet(),
                firstUnreadMessageId = null,
                unreadMentionMessageIds = emptyList(),
                transferProgress = emptyMap(),
            )
        }
        val pendingInvite = pending.pendingNearbyInvite
        if (pendingInvite != null) {
            viewModelScope.launch {
                val resp = runCatching {
                    nearbyApi.respondInvite(pendingInvite.inviteId, _uiState.value.profile.clientId, true)
                }.getOrNull()
                val roomCode = resp?.roomCode?.takeIf { it.isNotBlank() } ?: pending.targetCode
                joinRoomInternal(roomCode, "")
            }
            return
        }
        val summary = pending.roomSummary
        if (summary != null && !summary.hasPassword) {
            recordRecentRoom(summary.code, summary.mode, false, "")
            enterRoomInternal(summary.code, "", summary)
        } else {
            joinRoomInternal(pending.targetCode, pending.password)
        }
    }

    fun dismissSwitchRoom() {
        _uiState.update { it.copy(pendingSwitchRoom = null) }
    }

    // B12/B23：进程冷启动后恢复上次活跃房间
    fun recoverLastRoom() {
        val last = _uiState.value.lastRecoverableRoom ?: return
        _uiState.update { it.copy(lastRecoverableRoom = null) }
        joinRoomInternal(last.code, last.password, overrideOwnerToken = last.ownerToken)
    }

    fun dismissRecoverRoom() {
        store.clearLastActiveRoom()
        _uiState.update { it.copy(lastRecoverableRoom = null) }
    }

    fun joinRoom(rawInput: String, password: String) {
        cancelVoiceRecording()
        val code = FormatUtils.extractRoomCode(rawInput)
        if (code.isBlank()) {
            _uiState.update { it.copy(lobbyError = "请输入房间口令或扫描二维码") }
            return
        }
        val current = _uiState.value.activeRoom
        if (current != null && current.roomCode.equals(code, ignoreCase = true)) {
            restoreRoom()
            return
        }
        if (current != null && !current.roomCode.equals(code, ignoreCase = true)) {
            _uiState.update {
                it.copy(
                    pendingSwitchRoom = PendingSwitchRoom(
                        targetCode = code,
                        password = password.trim(),
                        source = "加入房间",
                    )
                )
            }
            return
        }
        joinRoomInternal(code, password)
    }

    private fun joinRoomInternal(code: String, password: String, overrideOwnerToken: String = "") {
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
                                lobbyNeedPassword = true,
                                pendingJoinCode = code,
                                pendingShare = it.pendingShare.copy(autoSendRoomCode = ""),
                            )
                        }
                        return@launch
                    }
                    api.verifyRoomPassword(code, cleanPwd)
                }
                val ownerToken = overrideOwnerToken.ifBlank { resolveOwnerTokenForCode(info.code) }
                if (info.mode == RoomMode.FIXED.wireValue) {
                    val saved = store.upsertSavedRoom(
                        SavedFixedRoom(
                            code = info.code,
                            hasPassword = info.hasPassword,
                            password = cleanPwd,
                            ownerToken = ownerToken,
                        )
                    )
                    _uiState.update { it.copy(savedRooms = saved) }
                }
                recordRecentRoom(info.code, info.mode, info.hasPassword, cleanPwd, ownerToken)
                enterRoomInternal(info.code, cleanPwd, info, ownerToken = ownerToken)
            } catch (e: ApiException) {
                // B15：房间不存在或已过期时，同时清理最近记录和本地收藏
                if (e.errorCode == "ROOM_NOT_FOUND" || e.errorCode == "ROOM_EXPIRED") {
                    val nextRecent = store.removeRecentRoom(code)
                    val nextSaved = store.removeSavedRoom(code)
                    _uiState.update { it.copy(recentRooms = nextRecent, savedRooms = nextSaved) }
                    showToast("房间 #$code 已销毁或过期，已从记录与收藏移除")
                }
                _uiState.update {
                    it.copy(
                        lobbyError = e.message,
                        lobbyNeedPassword = e.errorCode == "WRONG_PASSWORD" || it.lobbyNeedPassword,
                        pendingJoinCode = code,
                        pendingShare = it.pendingShare.copy(autoSendRoomCode = ""),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        lobbyError = e.message ?: "无法连接到服务器",
                        pendingShare = it.pendingShare.copy(autoSendRoomCode = ""),
                    )
                }
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

    // B19：供 UI Coil 请求历史图片时附加 X-Room-Password 请求头，密码绝不入 URL
    fun imageHeaders(): Map<String, String> {
        val pwd = _uiState.value.activeRoom?.password.orEmpty()
        return if (pwd.isBlank()) emptyMap() else mapOf("X-Room-Password" to pwd)
    }

    private fun enterRoomInternal(
        code: String,
        password: String,
        initialInfo: RoomSummary?,
        ownerToken: String = initialInfo?.ownerToken.orEmpty(),
    ) {
        tearDownSession(stopService = false)
        awaitingInitialHistorySync = true
        hasJoinedRoomOnce = false
        hadRoomWsDisconnect = false
        pendingRestoreCaptureChecked = false
        // B9：由稳定 clientId + roomCode 派生固定房间内 peerId
        selfPeerId = TransferResumeUtils.derivePeerId(_uiState.value.profile.clientId, code)
        recordedTransfers.clear()
        sendCompletedRecipients.clear()
        sendExpectedTotal.clear()

        val resolvedOwnerToken = ownerToken.ifBlank { resolveOwnerTokenForCode(code) }
        val initialIsOwner = initialInfo?.isOwner == true || resolvedOwnerToken.isNotBlank()

        store.saveLastActiveRoom(
            LastActiveRoom(
                code = code,
                password = password,
                ownerToken = resolvedOwnerToken,
                at = System.currentTimeMillis(),
            )
        )

        _uiState.update {
            it.copy(
                lobbyError = null,
                lobbyNeedPassword = false,
                pendingJoinCode = null,
                pendingCreateConfirm = null,
                pendingSwitchRoom = null,
                lastRecoverableRoom = null,
                roomMinimized = false,
                unreadCount = 0,
                playedVoiceIds = emptySet(),
                firstUnreadMessageId = null,
                unreadMentionMessageIds = emptyList(),
                transferProgress = emptyMap(),
                activeRoom = ActiveRoomSession(
                    roomCode = code,
                    password = password,
                    roomInfo = initialInfo,
                    historyMeta = initialInfo?.history ?: HistoryMeta(retentionDays = initialInfo?.retentionDays ?: 1),
                    ownerToken = resolvedOwnerToken,
                    isOwner = initialIsOwner,
                    selfPeerId = selfPeerId,
                ),
            )
        }

        // B11：仅在已建立活跃房间时启动前台保活服务
        TransferForegroundService.updateStatus(
            appContext,
            title = "湾聊房间 #$code",
            text = "正在维持 P2P 直连通道...",
        )

        val selfProfile = PeerProfile(
            peerId = selfPeerId,
            name = _uiState.value.profile.name,
            avatar = _uiState.value.profile.avatar,
            joinedAt = System.currentTimeMillis(),
            clientId = _uiState.value.profile.clientId,
        )

        val newMesh = WebRtcMeshManager(
            appContext = appContext,
            selfPeerId = selfPeerId,
            initialIceServers = _uiState.value.serverConfig.iceServers,
            callbacks = object : MeshCallbacks {
                override fun sendSignal(env: SignalEnvelope) {
                    signaling?.send(env)
                }

                override fun isPeerBlocked(peerId: String): Boolean {
                    return isPeerBlockedInternal(peerId)
                }

                override fun onPartExpired(partFileName: String) {
                    store.removePartialMeta(partFileName)
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
                    if (isPeerBlockedInternal(peerId)) return
                    val sender = _uiState.value.activeRoom?.peers?.get(peerId)
                    val mentioned = isTextMentioningMe(text)
                    val item = ChatItem(
                        id = id,
                        kind = ChatKind.TEXT,
                        senderId = peerId,
                        senderName = sender?.name ?: "伙伴",
                        senderAvatar = sender?.avatar,
                        isSelf = false,
                        timestamp = timestamp,
                        text = text,
                        replyTo = replyTo,
                        mentionedMe = mentioned,
                    )
                    val inserted = appendMessageIfAbsent(item)
                    if (inserted) {
                        val roomCode = _uiState.value.activeRoom?.roomCode.orEmpty()
                        handleIncomingMessageArrived(
                            item = item,
                            fromCache = false,
                            roomCode = roomCode,
                            previewText = text,
                        )
                    }
                }

                override fun onClipboardSync(peerId: String, text: String, updatedAt: Long) {
                    if (isPeerBlockedInternal(peerId) || !_uiState.value.prefs.acceptClipShare) return
                    val sender = _uiState.value.activeRoom?.peers?.get(peerId)
                    val item = ChatItem(
                        id = "clip_${updatedAt}_${peerId.take(6)}",
                        kind = ChatKind.CLIP,
                        senderId = peerId,
                        senderName = sender?.name ?: "伙伴",
                        senderAvatar = sender?.avatar,
                        isSelf = false,
                        timestamp = updatedAt,
                        text = text,
                    )
                    val inserted = appendMessageIfAbsent(item)
                    if (inserted) {
                        val roomCode = _uiState.value.activeRoom?.roomCode.orEmpty()
                        handleIncomingMessageArrived(
                            item = item,
                            fromCache = false,
                            roomCode = roomCode,
                            previewText = "[跨端剪贴板] $text",
                        )
                    }
                }

                override fun onRoomMoved(peerId: String, newCode: String, password: String) {
                    if (isPeerBlockedInternal(peerId)) return
                    viewModelScope.launch {
                        showToast("房间已更换新口令 #$newCode，正在自动跟随...")
                        signaling?.leaveRoom()
                        tearDownSession(stopService = false)
                        _uiState.update {
                            it.copy(
                                activeRoom = null,
                                roomMinimized = false,
                                unreadCount = 0,
                                playedVoiceIds = emptySet(),
                                firstUnreadMessageId = null,
                                unreadMentionMessageIds = emptyList(),
                            )
                        }
                        joinRoomInternal(newCode, password)
                    }
                }

                override fun onResumeRequested(peerId: String, resumeKey: String, fileName: String, fileSize: Long) {
                    if (isPeerBlockedInternal(peerId)) return
                    val matched = outgoingSourcesByItemId.values.firstOrNull { src ->
                        (resumeKey.isNotBlank() && src.resumeKey == resumeKey) ||
                            (src.fileName == fileName && src.fileSize == fileSize)
                    }
                    if (matched == null) {
                        mesh?.sendResumeUnavailable(peerId, resumeKey, fileName, "发送方源文件已关闭或不可用")
                        return
                    }
                    sendFileSources(listOf(matched))
                }

                override fun onResumeUnavailable(peerId: String, resumeKey: String, fileName: String, reason: String) {
                    showToast("「$fileName」无法续传：$reason")
                }

                override fun onRecallMessage(peerId: String, msgId: String) {
                    if (isPeerBlockedInternal(peerId)) return
                    val senderName = _uiState.value.activeRoom?.peers?.get(peerId)?.name
                    applyMessageRecalled(msgId, peerId, senderName)
                }

                override fun onTyping(peerId: String, isTyping: Boolean) {
                    if (isPeerBlockedInternal(peerId)) return
                    typingTimeoutJobs.remove(peerId)?.cancel()
                    updateRoom { r ->
                        val next = if (isTyping) r.typingPeers + peerId else r.typingPeers - peerId
                        r.copy(typingPeers = next)
                    }
                    // B20：收到 typing=true 后启动 6 秒自动超时清除
                    if (isTyping) {
                        typingTimeoutJobs[peerId] = viewModelScope.launch {
                            delay(TYPING_TIMEOUT_MS)
                            updateRoom { r -> r.copy(typingPeers = r.typingPeers - peerId) }
                            typingTimeoutJobs.remove(peerId)
                        }
                    }
                }

                override fun onReaction(peerId: String, msgId: String, emoji: String) {
                    if (isPeerBlockedInternal(peerId)) return
                    toggleReactionLocal(msgId, emoji, peerId)
                }

                override fun onNudge(
                    peerId: String,
                    id: String,
                    targetPeerId: String,
                    targetName: String,
                    fromName: String,
                    timestamp: Long,
                ) {
                    if (isPeerBlockedInternal(peerId)) return
                    handleIncomingNudge(peerId, id, targetPeerId, targetName, fromName, timestamp)
                }

                override fun onFileOffer(
                    peerId: String,
                    info: FileTransferInfo,
                    msgId: String,
                    caption: String?,
                    timestamp: Long,
                ) {
                    if (isPeerBlockedInternal(peerId)) {
                        mesh?.rejectIncomingTransfer(peerId, info.transferId)
                        return
                    }
                    val sender = _uiState.value.activeRoom?.peers?.get(peerId)
                    val kind = when {
                        info.isImage -> ChatKind.IMAGE
                        info.isAudio -> ChatKind.AUDIO
                        else -> ChatKind.FILE
                    }
                    val itemId = if (info.isImage || info.isAudio) msgId else "tr_${info.transferId}"
                    val limitMB = effectiveMaxFileMB()
                    if (limitMB > 0 && info.fileSize > limitMB.toLong() * 1024L * 1024L) {
                        showToast("收到较大附件「${info.fileName}」，请手动确认接收或婉拒")
                    }
                    if (!info.isImage && !info.isAudio && info.fileSize > 0L) {
                        val partName = WebRtcMeshManager.resumePartFileName(info.fileName, info.fileSize, info.resumeKey)
                        store.upsertPartialMeta(
                            PartialFileMeta(
                                partFileName = partName,
                                fileName = info.fileName,
                                fileSize = info.fileSize,
                                roomCode = _uiState.value.activeRoom?.roomCode.orEmpty(),
                                peerName = sender?.name ?: "伙伴",
                                resumeKey = info.resumeKey.orEmpty(),
                            )
                        )
                    }
                    val incomingItem = ChatItem(
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
                    val inserted = appendMessageIfAbsent(incomingItem)
                    if (inserted) {
                        val roomCode = _uiState.value.activeRoom?.roomCode.orEmpty()
                        val autoAccept = shouldAutoAccept(info)
                        if (!autoAccept) {
                            // 例外规则：需要手动确认的文件传输请求在免打扰房间及挂起态均保持提醒与系统通知
                            playSoundIfEnabled { playMessageIn() }
                            recordUnreadArrival(itemId = incomingItem.id, mentionedMe = false)
                            if (!_uiState.value.isAppForeground || _uiState.value.roomMinimized) {
                                IncomingNotificationHelper.showIncomingOfferNotification(
                                    context = appContext,
                                    roomCode = roomCode,
                                    peerId = peerId,
                                    peerName = sender?.name ?: "伙伴",
                                    transferId = info.transferId,
                                    fileName = info.fileName,
                                    fileSize = info.fileSize,
                                )
                            }
                            if (_uiState.value.isAppForeground && _uiState.value.roomMinimized) {
                                showToast("${sender?.name ?: "伙伴"} 发来文件「${info.fileName}」，请点击恢复房间确认接收")
                            }
                        } else {
                            val label = when {
                                info.isImage -> "[图片] ${info.fileName}"
                                info.isAudio -> "[语音消息]"
                                else -> "[文件] ${info.fileName}"
                            }
                            handleIncomingMessageArrived(
                                item = incomingItem,
                                fromCache = false,
                                roomCode = roomCode,
                                previewText = label,
                            )
                        }
                    }
                }

                override fun onTransferUpdate(info: FileTransferInfo) {
                    if (info.status == TransferStatus.TRANSFERRING) {
                        val snapshot = TransferProgressSnapshot(
                            transferredBytes = info.transferredBytes,
                            totalBytes = info.fileSize,
                            speedBps = info.speedBps.toLong(),
                            etaSeconds = info.etaSeconds.toInt().coerceAtLeast(0),
                        )
                        val existingTransfer = _uiState.value.activeRoom?.messages
                            ?.firstOrNull { it.transfer?.transferId == info.transferId }
                            ?.transfer
                        val needsListUpdate = existingTransfer == null ||
                            existingTransfer.status != TransferStatus.TRANSFERRING ||
                            (info.cloudStatus != CloudUploadStatus.NONE && existingTransfer.cloudStatus != info.cloudStatus) ||
                            (info.cachedDownloadUrl != null && existingTransfer.cachedDownloadUrl != info.cachedDownloadUrl) ||
                            (info.localPath != null && existingTransfer.localPath != info.localPath)
                        if (needsListUpdate) {
                            _uiState.update { st ->
                                val room = st.activeRoom
                                val nextRoom = room?.copy(
                                    messages = room.messages.map { item ->
                                        if (item.transfer?.transferId != info.transferId) {
                                            item
                                        } else {
                                            item.copy(
                                                imageUrl = if (info.isImage && !info.localPath.isNullOrBlank()) info.localPath else item.imageUrl,
                                                localFilePath = info.localPath ?: item.localFilePath,
                                                transfer = info.copy(
                                                    targetPeerName = item.transfer.targetPeerName ?: info.targetPeerName,
                                                    cachedDownloadUrl = info.cachedDownloadUrl ?: item.transfer.cachedDownloadUrl,
                                                    cloudStatus = if (info.cloudStatus != CloudUploadStatus.NONE) info.cloudStatus else item.transfer.cloudStatus,
                                                    cloudError = info.cloudError ?: item.transfer.cloudError,
                                                ),
                                            )
                                        }
                                    }
                                )
                                st.copy(
                                    activeRoom = nextRoom,
                                    transferProgress = st.transferProgress + (info.transferId to snapshot),
                                )
                            }
                        } else {
                            // UX-19：高频 200ms 传输进度仅刷新 transferProgress Map，避免全消息列表频繁重组
                            _uiState.update { st ->
                                st.copy(transferProgress = st.transferProgress + (info.transferId to snapshot))
                            }
                        }
                    } else {
                        val isTerminal = info.status == TransferStatus.COMPLETED ||
                            info.status == TransferStatus.ERROR ||
                            info.status == TransferStatus.CANCELLED ||
                            info.status == TransferStatus.REJECTED
                        _uiState.update { st ->
                            val room = st.activeRoom
                            val nextRoom = room?.copy(
                                messages = room.messages.map { item ->
                                    if (item.transfer?.transferId != info.transferId) {
                                        item
                                    } else {
                                        item.copy(
                                            imageUrl = if (info.isImage && !info.localPath.isNullOrBlank()) info.localPath else item.imageUrl,
                                            localFilePath = info.localPath ?: item.localFilePath,
                                            transfer = info.copy(
                                                targetPeerName = item.transfer.targetPeerName ?: info.targetPeerName,
                                                cachedDownloadUrl = info.cachedDownloadUrl ?: item.transfer.cachedDownloadUrl,
                                                cloudStatus = if (info.cloudStatus != CloudUploadStatus.NONE) info.cloudStatus else item.transfer.cloudStatus,
                                                cloudError = info.cloudError ?: item.transfer.cloudError,
                                            ),
                                        )
                                    }
                                }
                            )
                            st.copy(
                                activeRoom = nextRoom,
                                transferProgress = if (isTerminal) st.transferProgress - info.transferId else st.transferProgress,
                            )
                        }
                    }

                    if (info.status == TransferStatus.TRANSFERRING && info.fileSize > 0) {
                        val pct = ((info.transferredBytes * 100) / info.fileSize).toInt().coerceIn(0, 100)
                        val speedText = "${FormatUtils.formatBytes(info.speedBps.toLong())}/s"
                        val dirLabel = if (info.direction == TransferDirection.SEND) "正在发送" else "正在接收"
                        val title = "$dirLabel ${info.fileName}"
                        val now = System.currentTimeMillis()
                        if (_uiState.value.activeRoom != null &&
                            (now - lastForegroundBannerAt >= 1000L || kotlin.math.abs(pct - lastForegroundBannerPercent) >= 2 || lastForegroundBannerTitle != title)
                        ) {
                            lastForegroundBannerAt = now
                            lastForegroundBannerPercent = pct
                            lastForegroundBannerTitle = title
                            TransferForegroundService.updateStatus(
                                appContext,
                                title = title,
                                text = "$pct% · $speedText",
                                progress = pct,
                            )
                        }
                    } else if (info.status == TransferStatus.COMPLETED) {
                        if (info.direction == TransferDirection.RECEIVE) {
                            IncomingNotificationHelper.cancelOfferNotification(appContext, info.transferId)
                        }
                        val roomCode = _uiState.value.activeRoom?.roomCode.orEmpty()
                        val baseKey = if (info.direction == TransferDirection.SEND) {
                            info.transferId.substringBeforeLast('_')
                        } else {
                            info.transferId
                        }
                        // B24：多人发送合并为单条传输记录，统计 recipientsDone / recipientsTotal
                        val recordId = "${baseKey}_${info.direction.name}"
                        var doneCount = 1
                        var totalCount = 1
                        if (info.direction == TransferDirection.SEND) {
                            val set = sendCompletedRecipients.getOrPut(baseKey) { ConcurrentHashMap.newKeySet() }
                            info.targetPeerId?.let { set.add(it) }
                            doneCount = set.size.coerceAtLeast(1)
                            totalCount = maxOf(sendExpectedTotal[baseKey] ?: info.recipientsTotal, doneCount)
                        }

                        val rec = TransferRecord(
                            id = recordId,
                            fileName = info.fileName,
                            fileSize = info.fileSize,
                            mimeType = info.mimeType,
                            direction = if (info.direction == TransferDirection.SEND) "send" else "receive",
                            roomCode = roomCode,
                            peerName = if (totalCount > 1) "房间 $doneCount/$totalCount 位成员" else info.targetPeerName.orEmpty(),
                            savedDisplayPath = info.savedDisplayPath,
                            localPath = info.localPath,
                            contentUri = info.contentUri,
                            verified = info.verified,
                            recipientsDone = doneCount,
                            recipientsTotal = totalCount,
                        )
                        val updatedHistory = store.addTransferRecord(rec)
                        if (info.direction == TransferDirection.RECEIVE && !info.isImage && !info.isAudio) {
                            store.removePartialMeta(
                                WebRtcMeshManager.resumePartFileName(info.fileName, info.fileSize, info.resumeKey)
                            )
                        }
                        _uiState.update { it.copy(transferRecords = updatedHistory) }
                        refreshTransferCenter()

                        if (!info.isImage && !info.isAudio) {
                            if (info.direction == TransferDirection.SEND) {
                                if (recordedTransfers.add(baseKey)) {
                                    playSoundIfEnabled { playTransferDone() }
                                    signaling?.recordActivity("file", info.fileSize)
                                    showToast(
                                        if (info.verified) "${info.fileName} 已直传完成且 SHA-256 校验通过"
                                        else "${info.fileName} 发送完成"
                                    )
                                }
                            } else {
                                if (!isRoomMuted(roomCode)) {
                                    playSoundIfEnabled { playTransferDone() }
                                }
                                val savedText = if (info.verified) {
                                    "已保存至 ${info.savedDisplayPath ?: "下载目录"}（SHA-256 校验通过）"
                                } else {
                                    "已保存至 ${info.savedDisplayPath ?: "下载目录"}"
                                }
                                showToast(savedText)
                                if (!_uiState.value.isAppForeground && roomCode.isNotBlank()) {
                                    IncomingNotificationHelper.showFileTransferResultNotification(
                                        context = appContext,
                                        roomCode = roomCode,
                                        fileName = info.fileName,
                                        success = true,
                                        detailText = savedText,
                                    )
                                }
                            }
                        }
                        refreshForegroundBanner()
                    } else if (info.status == TransferStatus.ERROR || info.status == TransferStatus.CANCELLED || info.status == TransferStatus.REJECTED) {
                        if (info.direction == TransferDirection.RECEIVE) {
                            IncomingNotificationHelper.cancelOfferNotification(appContext, info.transferId)
                        }
                        refreshTransferCenter()
                        refreshForegroundBanner()
                    }
                }

                override fun shouldAutoAccept(info: FileTransferInfo): Boolean {
                    // B2/B3：被屏蔽对端与 allowRandom=true 公开房一律禁止自动接收任何文件/图片
                    val targetPeer = info.targetPeerId
                    if (isPeerBlockedInternal(targetPeer)) return false
                    if (_uiState.value.activeRoom?.roomInfo?.allowRandom == true) return false

                    val limitMB = effectiveMaxFileMB()
                    if (limitMB > 0 && info.fileSize > limitMB.toLong() * 1024L * 1024L) {
                        return false
                    }
                    if (info.isImage) {
                        return TransferResumeUtils.isTrustedImage(info.fileName, info.mimeType) &&
                            info.fileSize <= TransferResumeUtils.IMAGE_AUTO_ACCEPT_MAX_BYTES
                    }
                    if (info.isAudio) {
                        return TransferResumeUtils.isTrustedAudio(info.fileName, info.mimeType) &&
                            info.fileSize <= TransferResumeUtils.AUDIO_AUTO_ACCEPT_MAX_BYTES
                    }
                    val prefs = _uiState.value.prefs
                    val maxAutoBytes = prefs.autoAcceptMaxMB.coerceAtLeast(1).toLong() * 1024L * 1024L
                    return prefs.autoAcceptSmallFiles && info.fileSize <= maxAutoBytes
                }
            }
        )
        mesh = newMesh

        val joinSoundPlayed = AtomicBoolean(false)
        val newSig = SignalingClient(
            wsUrl = api.buildWebSocketUrl(),
            roomCode = code,
            password = password,
            profile = selfProfile,
            okHttpClient = api.okHttpClient,
            ownerToken = resolvedOwnerToken,
            callbacks = object : SignalingCallbacks {
                override fun onConnectionState(connected: Boolean) {
                    if (!connected && hasJoinedRoomOnce) {
                        hadRoomWsDisconnect = true
                    }
                    updateRoom { it.copy(wsConnected = connected) }
                }

                override fun onJoined(env: SignalEnvelope) {
                    val wasReconnected = hasJoinedRoomOnce && hadRoomWsDisconnect
                    hasJoinedRoomOnce = true
                    hadRoomWsDisconnect = false
                    if (wasReconnected) {
                        showToast("网络已恢复，已重新连接")
                    }
                    if (joinSoundPlayed.compareAndSet(false, true)) {
                        playSoundIfEnabled { playRoomJoin() }
                    }
                    val peerMap = env.peers.orEmpty()
                        .filterNot { isPeerBlockedInternal(it.peerId, it.clientId, it.name) }
                        .associateBy { it.peerId }
                    val joinedIsOwner = env.isOwner ?: resolvedOwnerToken.isNotBlank()
                    updateRoom { r ->
                        r.copy(
                            roomInfo = env.room ?: r.roomInfo,
                            historyMeta = env.history ?: env.room?.history ?: r.historyMeta,
                            peers = peerMap,
                            linkStatuses = r.linkStatuses.filterKeys { it in peerMap },
                            typingPeers = r.typingPeers.filter { it in peerMap }.toSet(),
                            isOwner = joinedIsOwner,
                        )
                    }
                    awaitingInitialHistorySync = false
                    // 进房同步历史消息改为单次原子批量合并（不再把历史最早消息误标为未读导致列表滚回顶部）
                    mergeCachedMessagesBatch(env.messages.orEmpty(), fromCache = true)
                    newMesh.syncPeers(peerMap.keys.toList())
                    refreshForegroundBanner()
                    maybeRecoverPendingCameraCapture(code)
                    val autoTarget = _uiState.value.pendingShare.autoSendRoomCode
                    if (autoTarget.isNotBlank() && autoTarget.equals(code, ignoreCase = true)) {
                        viewModelScope.launch {
                            delay(800L)
                            val curRoom = _uiState.value.activeRoom
                            if (curRoom != null &&
                                curRoom.roomCode.equals(code, ignoreCase = true) &&
                                _uiState.value.pendingShare.hasContent
                            ) {
                                sendPendingShareNow()
                            }
                        }
                    }
                }

                override fun onPeerJoined(profile: PeerProfile) {
                    if (isPeerBlockedInternal(profile.peerId, profile.clientId, profile.name)) return
                    val alreadyPresent = _uiState.value.activeRoom?.peers?.containsKey(profile.peerId) == true
                    updateRoom { r -> r.copy(peers = r.peers + (profile.peerId to profile)) }
                    // B8：WebSocket 抖动重连时不强制 reset 已建立且 dataReady 的 P2P 连接
                    newMesh.ensurePeer(profile.peerId, forceReset = false)
                    if (!alreadyPresent) {
                        appendSystemMessage("${profile.name} 加入了房间")
                    }
                    refreshForegroundBanner()
                }

                override fun onPeerUpdated(profile: PeerProfile) {
                    if (isPeerBlockedInternal(profile.peerId, profile.clientId, profile.name)) return
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
                    typingTimeoutJobs.remove(peerId)?.cancel()
                    val leftName = _uiState.value.activeRoom?.peers?.get(peerId)?.name
                    updateRoom { r ->
                        r.copy(
                            peers = r.peers - peerId,
                            linkStatuses = r.linkStatuses - peerId,
                            typingPeers = r.typingPeers - peerId,
                        )
                    }
                    newMesh.removePeer(peerId)
                    if (leftName != null) appendSystemMessage("$leftName 离开了房间")
                    refreshForegroundBanner()
                }

                override fun onSignal(env: SignalEnvelope) {
                    if (isPeerBlockedInternal(env.fromPeerId)) return
                    newMesh.handleSignal(env)
                }

                override fun onMessageCached(env: SignalEnvelope) {
                    env.history?.let { h -> updateRoom { it.copy(historyMeta = h) } }
                    val msg = env.message ?: return
                    if (msg.senderId == selfPeerId) {
                        updateMessageSendStatus(msg.id, MessageSendStatus.SENT)
                    } else if (!isPeerBlockedInternal(msg.senderId, null, msg.senderName)) {
                        mergeCachedMessage(msg, fromCache = false)
                    }
                }

                override fun onQueuedMessageFlushed(messageId: String) {
                    updateMessageSendStatus(messageId, MessageSendStatus.SENT)
                }

                override fun onQueuedMessagesDropped(messageIds: List<String>) {
                    messageIds.forEach { updateMessageSendStatus(it, MessageSendStatus.FAILED) }
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
                    appendSystemMessage("${env.actorName ?: "伙伴"} 将保鲜期设为：$desc")
                }

                override fun onRoomSettingsUpdated(env: SignalEnvelope) {
                    env.maxFileSizeMB?.let { mb ->
                        updateRoom { r -> r.copy(roomInfo = r.roomInfo?.copy(maxFileSizeMB = mb)) }
                        appendSystemMessage("${env.actorName ?: "伙伴"} 将附件上限调为：${FormatUtils.formatLimitMB(mb)}")
                    }
                    env.allowRandom?.let { allow ->
                        updateRoom { r -> r.copy(roomInfo = r.roomInfo?.copy(allowRandom = allow)) }
                        val stateDesc = if (allow) "已开启（允许陌生人随机串门）" else "已关闭（仅凭口令进入）"
                        appendSystemMessage("${env.actorName ?: "伙伴"} 修改随机串门状态：$stateDesc")
                    }
                    env.topic?.let { topic ->
                        updateRoom { r -> r.copy(roomInfo = r.roomInfo?.copy(topic = topic)) }
                    }
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
                                    text = "${env.actorName ?: "伙伴"} 已清空服务器聊天缓存",
                                )
                            )
                        )
                    }
                    _uiState.update {
                        it.copy(
                            firstUnreadMessageId = null,
                            unreadMentionMessageIds = emptyList(),
                        )
                    }
                    showToast("服务器聊天缓存已清空")
                }

                override fun onRoomDestroyed(reason: String) {
                    store.clearLastActiveRoom()
                    outgoingInvitePollingJob?.cancel()
                    outgoingInvitePollingJob = null
                    tearDownSession(stopService = true)
                    _uiState.update {
                        it.copy(
                            activeRoom = null,
                            roomMinimized = false,
                            unreadCount = 0,
                            playedVoiceIds = emptySet(),
                            firstUnreadMessageId = null,
                            unreadMentionMessageIds = emptyList(),
                            outgoingNearbyInvite = null,
                            transferProgress = emptyMap(),
                            lobbyError = reason,
                        )
                    }
                }

                override fun onError(code: String, message: String) {
                    if (code == "RECALL_EXPIRED") {
                        showToast("已超过 2 分钟，无法撤回")
                    } else if (code in setOf("ROOM_NOT_FOUND", "ROOM_EXPIRED", "WRONG_PASSWORD", "ROOM_FULL", "INVALID_JOIN")) {
                        if (code == "ROOM_NOT_FOUND" || code == "ROOM_EXPIRED") {
                            val nextRecent = store.removeRecentRoom(code = _uiState.value.activeRoom?.roomCode.orEmpty())
                            val nextSaved = store.removeSavedRoom(code = _uiState.value.activeRoom?.roomCode.orEmpty())
                            _uiState.update { it.copy(recentRooms = nextRecent, savedRooms = nextSaved) }
                        }
                        store.clearLastActiveRoom()
                        outgoingInvitePollingJob?.cancel()
                        outgoingInvitePollingJob = null
                        tearDownSession(stopService = true)
                        _uiState.update {
                            it.copy(
                                activeRoom = null,
                                roomMinimized = false,
                                unreadCount = 0,
                                playedVoiceIds = emptySet(),
                                firstUnreadMessageId = null,
                                unreadMentionMessageIds = emptyList(),
                                outgoingNearbyInvite = null,
                                transferProgress = emptyMap(),
                                lobbyError = message,
                            )
                        }
                    } else {
                        showToast(message)
                    }
                }
            }
        )
        signaling = newSig
        newSig.connect()

        if (!_uiState.value.configLoaded) {
            refreshServerConfig()
        }
    }

    private fun isRoomMuted(roomCode: String?): Boolean {
        val clean = roomCode?.trim()?.uppercase().orEmpty()
        return clean.isNotEmpty() && clean in _uiState.value.mutedRooms
    }

    // 检测他人文本正文中是否包含 @当前用户昵称（区分空格、常见标点或字符串边界）
    private fun isTextMentioningMe(text: String?): Boolean {
        val myName = _uiState.value.profile.name.trim()
        if (myName.isEmpty() || text.isNullOrBlank()) return false
        val target = "@$myName"
        var searchFrom = 0
        while (searchFrom <= text.length - target.length) {
            val idx = text.indexOf(target, startIndex = searchFrom, ignoreCase = true)
            if (idx < 0) return false
            val afterIdx = idx + target.length
            val endBoundaryOk = if (afterIdx >= text.length) {
                true
            } else {
                val nextCh = text[afterIdx]
                nextCh.isWhitespace() || nextCh in MENTION_BOUNDARY_CHARS
            }
            if (endBoundaryOk) return true
            searchFrom = idx + 1
        }
        return false
    }

    // 记录后台/挂起期间的未读数、首条未读定位标记与待跳转 @我 消息
    private fun recordUnreadArrival(itemId: String, mentionedMe: Boolean) {
        _uiState.update { st ->
            val awayFromRoom = !st.isAppForeground || st.roomMinimized
            val nextUnread = if (awayFromRoom) st.unreadCount + 1 else st.unreadCount
            val nextFirstUnread = if (awayFromRoom && st.firstUnreadMessageId == null) itemId else st.firstUnreadMessageId
            val nextMentions = if (mentionedMe && itemId !in st.unreadMentionMessageIds) {
                st.unreadMentionMessageIds + itemId
            } else {
                st.unreadMentionMessageIds
            }
            st.copy(
                unreadCount = nextUnread,
                firstUnreadMessageId = nextFirstUnread,
                unreadMentionMessageIds = nextMentions,
            )
        }
    }

    // 统一处理实时新到达的普通消息（音效、未读计数、未读定位、@我 标记及后台通知）
    private fun handleIncomingMessageArrived(
        item: ChatItem,
        fromCache: Boolean,
        roomCode: String,
        previewText: String,
    ) {
        if (fromCache || item.isSelf) return
        val muted = isRoomMuted(roomCode)
        if (!muted) {
            playSoundIfEnabled { playMessageIn() }
        }
        recordUnreadArrival(itemId = item.id, mentionedMe = item.mentionedMe)
        val allowNotify = !muted || item.mentionedMe
        if (!_uiState.value.isAppForeground && allowNotify && roomCode.isNotBlank()) {
            val unread = _uiState.value.unreadCount.coerceAtLeast(1)
            IncomingNotificationHelper.showBackgroundMessageNotification(
                context = appContext,
                roomCode = roomCode,
                unreadCount = unread,
                senderName = item.senderName,
                previewText = previewText,
                mentionedMe = item.mentionedMe,
            )
        } else if (_uiState.value.isAppForeground && _uiState.value.roomMinimized && item.mentionedMe && roomCode.isNotBlank()) {
            showToast("${item.senderName} 在 #${roomCode} @了你：${previewText.take(24)}")
        }
    }

    fun refreshForegroundBanner() {
        val room = _uiState.value.activeRoom ?: return
        lastForegroundBannerPercent = -1
        lastForegroundBannerTitle = ""
        lastForegroundBannerAt = System.currentTimeMillis()
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
            title = "湾聊房间 #${room.roomCode}",
            text = statusText,
        )
    }

    // B20：输入框草稿变更节流发送 typing 状态（停止输入 3 秒后自动发 false）
    fun onDraftTypingChanged(hasText: Boolean) {
        if (_uiState.value.activeRoom == null) return
        draftIdleTypingJob?.cancel()
        if (!hasText) {
            lastTypingSentAt = 0L
            mesh?.broadcastTyping(false)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt >= 2000L) {
            lastTypingSentAt = now
            mesh?.broadcastTyping(true)
        }
        draftIdleTypingJob = viewModelScope.launch {
            delay(3000L)
            lastTypingSentAt = 0L
            mesh?.broadcastTyping(false)
        }
    }

    fun sendTextMessage(rawText: String) {
        val clean = rawText.trim()
        val room = _uiState.value.activeRoom ?: return
        if (clean.isEmpty()) return

        onDraftTypingChanged(false)
        val id = "msg_${System.currentTimeMillis()}_${(100000..999999).random()}"
        val ts = System.currentTimeMillis()
        val quote = room.replyingTo
        val profile = _uiState.value.profile
        val p2pSent = mesh?.broadcastText(id, clean, ts, quote) ?: 0

        var initialSendStatus = if (p2pSent > 0) MessageSendStatus.SENT else MessageSendStatus.SENDING
        if (room.historyMeta.retentionDays > 0) {
            val sentImmediate = signaling?.cacheMessage(
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
            ) == true
            if (sentImmediate) initialSendStatus = MessageSendStatus.SENT
        } else {
            if (p2pSent == 0) {
                showToast(
                    if (room.peers.isEmpty()) "当前阅后即焚且暂无他人在线，消息仅在本地展示"
                    else "P2P 通道未就绪，点击可重试发送"
                )
                initialSendStatus = MessageSendStatus.FAILED
            } else {
                initialSendStatus = MessageSendStatus.SENT
            }
        }

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
                sendStatus = initialSendStatus,
            )
        )
        updateRoom { it.copy(replyingTo = null) }
        updateDraft(room.roomCode, "")
        clearFirstUnreadMarker()
        signaling?.recordActivity("message", 0)
    }

    // 跨设备共享剪贴板同步发送（接收端仅建 CLIP 消息卡片，绝不自动写入系统剪贴板）
    fun sendClipboardSync(rawText: String) {
        val clean = rawText.trim()
        if (clean.isEmpty() || _uiState.value.activeRoom == null) return
        val ts = System.currentTimeMillis()
        val profile = _uiState.value.profile
        val sent = mesh?.broadcastClipboard(clean, ts) ?: 0
        appendMessageIfAbsent(
            ChatItem(
                id = "clip_${ts}_self",
                kind = ChatKind.CLIP,
                senderId = selfPeerId,
                senderName = profile.name,
                senderAvatar = profile.avatar,
                isSelf = true,
                timestamp = ts,
                text = clean,
                sendStatus = if (sent > 0) MessageSendStatus.SENT else MessageSendStatus.FAILED,
            )
        )
        if (sent == 0) {
            showToast("暂无已直连伙伴，剪贴板同步需要至少 1 位在线伙伴")
        }
    }

    // UX-5：对同一消息 ID 600ms 内连续触发的重发请求去重防连点
    private fun acquireResendDebounce(id: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastResendActionAt[id]
        if (prev != null && now - prev < RESEND_DEBOUNCE_MS) {
            return false
        }
        lastResendActionAt[id] = now
        return true
    }

    // B17/G4/UX-5：重发失败的文本消息或文件/图片传输
    fun retrySend(messageId: String) {
        if (!acquireResendDebounce(messageId)) return
        val room = _uiState.value.activeRoom ?: return
        val item = room.messages.firstOrNull { it.id == messageId } ?: return
        if (item.kind == ChatKind.TEXT && !item.text.isNullOrBlank()) {
            val p2pSent = mesh?.broadcastText(item.id, item.text, System.currentTimeMillis(), item.replyTo) ?: 0
            var nextStatus = if (p2pSent > 0) MessageSendStatus.SENT else MessageSendStatus.SENDING
            if (room.historyMeta.retentionDays > 0) {
                val ok = signaling?.cacheMessage(
                    CachedMessage(
                        id = item.id,
                        kind = "text",
                        senderId = selfPeerId,
                        senderName = _uiState.value.profile.name,
                        senderAvatar = _uiState.value.profile.avatar,
                        text = item.text,
                        replyToId = item.replyTo?.id,
                        replyToSender = item.replyTo?.senderName,
                        replyToPreview = item.replyTo?.preview,
                        timestamp = item.timestamp,
                    )
                ) == true
                if (ok) nextStatus = MessageSendStatus.SENT
            } else if (p2pSent == 0) {
                nextStatus = MessageSendStatus.FAILED
                showToast("暂无伙伴在线，无法重发阅后即焚消息")
            }
            updateMessageSendStatus(item.id, nextStatus)
            return
        }
        requestResendInternal(messageId)
    }

    fun requestResend(itemId: String) {
        if (!acquireResendDebounce(itemId)) return
        requestResendInternal(itemId)
    }

    private fun requestResendInternal(itemId: String) {
        val room = _uiState.value.activeRoom ?: return
        val item = room.messages.firstOrNull { it.id == itemId } ?: return
        if (item.isSelf) {
            val source = outgoingSourcesByItemId[itemId]
                ?: outgoingSourcesByItemId.values.firstOrNull {
                    item.transfer != null && it.fileName == item.transfer.fileName && it.fileSize == item.transfer.fileSize
                }
            if (source != null) {
                sendFileSources(listOf(source))
                showToast("正在重新发起发送「${source.fileName}」")
                return
            }
            if (item.transfer?.cloudStatus == CloudUploadStatus.FAILED) {
                retryCloudUpload(itemId)
                return
            }
            showToast("源文件句柄已失效，请重新选择文件发送")
        } else {
            val tr = item.transfer ?: return
            val key = tr.resumeKey.orEmpty()
            val sent = mesh?.broadcastResumeRequest(key, tr.fileName, tr.fileSize) ?: 0
            showToast(if (sent > 0) "已请求对方重发「${tr.fileName}」" else "对方当前不在线，无法请求重发")
        }
    }

    private fun updateMessageSendStatus(messageId: String, status: MessageSendStatus) {
        updateRoom { r ->
            r.copy(
                messages = r.messages.map { m ->
                    if (m.id == messageId) m.copy(sendStatus = status) else m
                }
            )
        }
    }

    private fun shouldUploadCloudBackup(connectedPeers: Int, retentionDays: Int): Boolean {
        if (retentionDays <= 0) return false
        if (connectedPeers == 0) return true
        val prefs = _uiState.value.prefs
        return prefs.cloudBackupOnWifi && AppUpdateManager.isUnmeteredWifi(appContext)
    }

    fun sendUris(uris: List<Uri>) {
        val room = _uiState.value.activeRoom ?: return
        if (uris.isEmpty()) return

        // UX-10：若待发送队列中包含已持久化记录的相机拍照 URI，及时消费清理
        val pendingCamera = store.peekPendingCameraCapture(room.roomCode)
        if (pendingCamera != null && uris.any { it.toString() == pendingCamera }) {
            store.consumePendingCameraCapture(room.roomCode)
        }

        viewModelScope.launch {
            val connectedPeers = mesh?.getConnectedPeerCount() ?: 0
            val limitMB = effectiveMaxFileMB()
            val profile = _uiState.value.profile
            var skippedCount = 0
            var lastSkipReason: String? = null

            for (uri in uris) {
                val meta = withContext(Dispatchers.IO) { MediaDownloadHelper.queryUriMeta(appContext, uri) }
                if (limitMB > 0 && meta.fileSize > limitMB.toLong() * 1024L * 1024L) {
                    skippedCount++
                    lastSkipReason = "「${meta.fileName}」超出当前附件上限 ${FormatUtils.formatLimitMB(limitMB)}"
                    continue
                }

                val isImage = meta.mimeType.startsWith("image/", ignoreCase = true) &&
                    meta.fileSize <= TransferResumeUtils.IMAGE_AUTO_ACCEPT_MAX_BYTES

                if (isImage) {
                    sendImageUri(uri, meta.fileName, meta.fileSize, meta.mimeType, room, profile)
                    continue
                }

                val retention = room.historyMeta.retentionDays
                val maxCacheFileBytes = _uiState.value.serverConfig.historyMaxFileBytes
                    .takeIf { it > 0L } ?: (100L * 1024L * 1024L)
                val canCloudCache = retention > 0 && meta.fileSize in 1L..maxCacheFileBytes
                val wantCloudBackup = canCloudCache && shouldUploadCloudBackup(connectedPeers, retention)

                if (connectedPeers == 0 && !canCloudCache) {
                    skippedCount++
                    lastSkipReason = if (retention <= 0) {
                        "当前为阅后即焚模式，大文件直传需等伙伴进房后发送"
                    } else {
                        "超过 ${FormatUtils.formatBytes(maxCacheFileBytes)} 的超大文件仅走 P2P 直传，请等伙伴进房后发送"
                    }
                    continue
                }

                val ts = System.currentTimeMillis()
                val idPrefix = "file_${ts}_${(1000..9999).random()}"
                val resumeKey = withContext(Dispatchers.IO) {
                    runCatching {
                        MediaDownloadHelper.computeResumeKey(appContext, uri, meta.fileName, meta.fileSize)
                    }.getOrNull()
                }
                val source = OutgoingFileSource(
                    uri = uri,
                    fileName = meta.fileName,
                    fileSize = meta.fileSize,
                    mimeType = meta.mimeType,
                    isImage = false,
                    transferIdPrefix = idPrefix,
                    timestamp = ts,
                    resumeKey = resumeKey,
                )

                if (connectedPeers > 0) {
                    val transfers = mesh?.sendFileToAll(source).orEmpty()
                    sendExpectedTotal[idPrefix] = transfers.size.coerceAtLeast(1)

                    transfers.forEach { tr ->
                        val itemId = "tr_${tr.transferId}"
                        outgoingSourcesByItemId[itemId] = source
                        val peerName = tr.targetPeerId?.let { room.peers[it]?.name }
                        appendMessageIfAbsent(
                            ChatItem(
                                id = itemId,
                                kind = ChatKind.FILE,
                                senderId = selfPeerId,
                                senderName = profile.name,
                                senderAvatar = profile.avatar,
                                isSelf = true,
                                timestamp = ts,
                                transfer = tr.copy(
                                    targetPeerName = peerName,
                                    cloudStatus = if (wantCloudBackup) CloudUploadStatus.UPLOADING else CloudUploadStatus.NONE,
                                ),
                            )
                        )
                    }

                    // B16：仅在满足 cloudBackupOnWifi + 非计费 Wi-Fi（或无人在线）时才上传云端，避免无谓双倍流量与重复 fnotice
                    if (wantCloudBackup) {
                        uploadCloudFileAsync(
                            room = room,
                            profile = profile,
                            uri = uri,
                            metaFileName = meta.fileName,
                            metaFileSize = meta.fileSize,
                            metaMime = meta.mimeType,
                            idPrefix = idPrefix,
                            ts = ts,
                            noticeOnlyMode = false,
                        )
                    }
                } else if (canCloudCache) {
                    val noticeId = "fnotice_$idPrefix"
                    outgoingSourcesByItemId[noticeId] = source
                    appendMessageIfAbsent(
                        ChatItem(
                            id = noticeId,
                            kind = ChatKind.FILE,
                            senderId = selfPeerId,
                            senderName = profile.name,
                            senderAvatar = profile.avatar,
                            isSelf = true,
                            timestamp = ts,
                            transfer = FileTransferInfo(
                                transferId = noticeId,
                                streamId = 0,
                                fileName = meta.fileName,
                                fileSize = meta.fileSize,
                                mimeType = meta.mimeType,
                                isImage = false,
                                direction = TransferDirection.SEND,
                                status = TransferStatus.TRANSFERRING,
                                cloudStatus = CloudUploadStatus.UPLOADING,
                            ),
                        )
                    )
                    uploadCloudFileAsync(
                        room = room,
                        profile = profile,
                        uri = uri,
                        metaFileName = meta.fileName,
                        metaFileSize = meta.fileSize,
                        metaMime = meta.mimeType,
                        idPrefix = idPrefix,
                        ts = ts,
                        noticeOnlyMode = true,
                    )
                }
            }

            if (skippedCount == 1 && !lastSkipReason.isNullOrBlank()) {
                showToast(lastSkipReason)
            } else if (skippedCount > 1) {
                showToast("已跳过 $skippedCount 个不支持或超限文件")
            }
        }
    }

    private fun uploadCloudFileAsync(
        room: ActiveRoomSession,
        profile: UserProfile,
        uri: Uri,
        metaFileName: String,
        metaFileSize: Long,
        metaMime: String,
        idPrefix: String,
        ts: Long,
        noticeOnlyMode: Boolean,
    ) {
        val noticeId = "fnotice_$idPrefix"
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.uploadCachedFile(
                    code = room.roomCode,
                    password = room.password,
                    context = appContext,
                    uri = uri,
                    fileName = metaFileName,
                    fileSize = metaFileSize,
                    mimeType = metaMime,
                )
            }.onSuccess { uploaded ->
                val dlUrl = api.buildCachedFileUrl(room.roomCode, uploaded.fileId)
                updateRoom { r ->
                    r.copy(
                        messages = r.messages.map { m ->
                            val matches = if (noticeOnlyMode) m.id == noticeId
                            else m.kind == ChatKind.FILE && m.transfer?.transferId?.startsWith("${idPrefix}_") == true
                            if (matches && m.transfer != null) {
                                m.copy(
                                    mediaId = uploaded.fileId,
                                    transfer = m.transfer.copy(
                                        status = if (noticeOnlyMode) TransferStatus.COMPLETED else m.transfer.status,
                                        transferredBytes = if (noticeOnlyMode) metaFileSize else m.transfer.transferredBytes,
                                        cachedDownloadUrl = dlUrl,
                                        cloudStatus = CloudUploadStatus.DONE,
                                        cloudError = null,
                                    ),
                                )
                            } else m
                        }
                    )
                }
                signaling?.cacheMessage(
                    CachedMessage(
                        id = noticeId,
                        kind = "file_notice",
                        senderId = selfPeerId,
                        senderName = profile.name,
                        senderAvatar = profile.avatar,
                        mediaId = uploaded.fileId,
                        mediaMime = uploaded.mime,
                        fileName = metaFileName,
                        fileSize = metaFileSize,
                        timestamp = ts,
                    )
                )
                if (noticeOnlyMode) {
                    signaling?.recordActivity("file", metaFileSize)
                    val rec = TransferRecord(
                        id = "${noticeId}_SEND",
                        fileName = metaFileName,
                        fileSize = metaFileSize,
                        mimeType = metaMime,
                        direction = "send",
                        roomCode = room.roomCode,
                        savedDisplayPath = "房间云端限时缓存",
                    )
                    _uiState.update { it.copy(transferRecords = store.addTransferRecord(rec)) }
                    playSoundIfEnabled { playTransferDone() }
                    showToast("「$metaFileName」已上传至房间缓存，刷新或换设备随时可下")
                }
            }.onFailure { err ->
                updateRoom { r ->
                    r.copy(
                        messages = r.messages.map { m ->
                            val matches = if (noticeOnlyMode) m.id == noticeId
                            else m.kind == ChatKind.FILE && m.transfer?.transferId?.startsWith("${idPrefix}_") == true
                            if (matches && m.transfer != null) {
                                m.copy(
                                    transfer = m.transfer.copy(
                                        status = if (noticeOnlyMode) TransferStatus.ERROR else m.transfer.status,
                                        errorMessage = if (noticeOnlyMode) (err.message ?: "上传失败") else m.transfer.errorMessage,
                                        cloudStatus = CloudUploadStatus.FAILED,
                                        cloudError = err.message ?: "云端备份失败",
                                    ),
                                )
                            } else m
                        }
                    )
                }
            }
        }
    }

    // B16：重试云端备份上传
    fun retryCloudUpload(itemId: String) {
        val room = _uiState.value.activeRoom ?: return
        val item = room.messages.firstOrNull { it.id == itemId } ?: return
        val source = outgoingSourcesByItemId[itemId] ?: return
        val uri = source.uri ?: return
        val noticeOnly = item.id.startsWith("fnotice_")
        val idPrefix = source.transferIdPrefix
        updateRoom { r ->
            r.copy(
                messages = r.messages.map { m ->
                    if (m.id == itemId && m.transfer != null) {
                        m.copy(
                            transfer = m.transfer.copy(
                                status = if (noticeOnly) TransferStatus.TRANSFERRING else m.transfer.status,
                                cloudStatus = CloudUploadStatus.UPLOADING,
                                cloudError = null,
                                errorMessage = null,
                            )
                        )
                    } else m
                }
            )
        }
        uploadCloudFileAsync(
            room = room,
            profile = _uiState.value.profile,
            uri = uri,
            metaFileName = source.fileName,
            metaFileSize = source.fileSize,
            metaMime = source.mimeType,
            idPrefix = idPrefix,
            ts = source.timestamp,
            noticeOnlyMode = noticeOnly,
        )
    }

    private fun sendFileSources(sources: List<OutgoingFileSource>) {
        val room = _uiState.value.activeRoom ?: return
        val profile = _uiState.value.profile
        sources.forEach { src ->
            val ts = System.currentTimeMillis()
            val prefix = if (src.isImage) "img_${ts}_${(1000..9999).random()}" else "file_${ts}_${(1000..9999).random()}"
            val renewed = src.copy(transferIdPrefix = prefix, timestamp = ts)
            val transfers = mesh?.sendFileToAll(renewed).orEmpty()
            sendExpectedTotal[prefix] = transfers.size.coerceAtLeast(1)
            transfers.forEach { tr ->
                val itemId = "tr_${tr.transferId}"
                outgoingSourcesByItemId[itemId] = renewed
                val peerName = tr.targetPeerId?.let { room.peers[it]?.name }
                appendMessageIfAbsent(
                    ChatItem(
                        id = itemId,
                        kind = when {
                            renewed.isImage -> ChatKind.IMAGE
                            renewed.isAudio -> ChatKind.AUDIO
                            else -> ChatKind.FILE
                        },
                        senderId = selfPeerId,
                        senderName = profile.name,
                        senderAvatar = profile.avatar,
                        isSelf = true,
                        timestamp = ts,
                        transfer = tr.copy(targetPeerName = peerName),
                    )
                )
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
        val isHeic = TransferResumeUtils.isHeicOrHeif(fileName, mimeType)

        // B18：HEIC/HEIF 强制转 JPEG 92%；非原图模式按 EXIF 旋转压缩
        val convertedBytes = withContext(Dispatchers.IO) {
            when {
                isHeic -> MediaDownloadHelper.compressImageToJpeg(appContext, uri, maxEdgePx = 4096, quality = 92)
                !prefs.imageOriginalMode -> MediaDownloadHelper.compressImageToJpeg(appContext, uri)
                else -> null
            }
        }

        val source = if (convertedBytes != null) {
            OutgoingFileSource(
                bytes = convertedBytes,
                fileName = fileName.substringBeforeLast('.') + ".jpg",
                fileSize = convertedBytes.size.toLong(),
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
        val connectedPeers = mesh?.getConnectedPeerCount() ?: 0
        outgoingSourcesByItemId[idPrefix] = source
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

        if (connectedPeers == 0 && room.historyMeta.retentionDays <= 0) {
            showToast("当前阅后即焚且暂无他人在线，图片仅在本地展示")
        }

        if (shouldUploadCloudBackup(connectedPeers, room.historyMeta.retentionDays)) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val maxCache = _uiState.value.serverConfig.historyMaxMediaBytes
                    val uploadBytes = convertedBytes ?: run {
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
                            mimeType = if (convertedBytes != null) "image/jpeg" else mimeType,
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

    // 文件夹打包为 ZIP 后直传发送
    fun sendFolder(treeUri: Uri) {
        if (_uiState.value.activeRoom == null) {
            showToast("请先进入房间后再发送文件夹")
            return
        }
        if (_uiState.value.packingFolder != null) {
            showToast("正在打包另一个文件夹，请稍候")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val zipDir = File(appContext.cacheDir, "folder_zips").apply { mkdirs() }
            var zipFile: File? = null
            try {
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
                val folderName = queryDocumentDisplayName(rootDocUri)
                    ?.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_")
                    ?.ifBlank { "folder" } ?: "folder"

                _uiState.update { it.copy(packingFolder = FolderPackProgress(folderName = folderName)) }
                zipFile = File(zipDir, "${folderName}_${System.currentTimeMillis()}.zip")

                var packedFiles = 0
                var packedBytes = 0L
                ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                    fun packDirectory(parentDocId: String, relativePrefix: String) {
                        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                        val projection = arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                        )
                        appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                            val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            while (cursor.moveToNext()) {
                                val docId = if (idIdx >= 0) cursor.getString(idIdx) else continue
                                val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null) ?: "item"
                                val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else ""
                                val entryPath = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
                                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                    packDirectory(docId, entryPath)
                                } else {
                                    val fileDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                    zos.putNextEntry(ZipEntry(entryPath))
                                    appContext.contentResolver.openInputStream(fileDocUri)?.use { input ->
                                        val buf = ByteArray(32 * 1024)
                                        while (true) {
                                            val r = input.read(buf)
                                            if (r <= 0) break
                                            zos.write(buf, 0, r)
                                            packedBytes += r
                                        }
                                    }
                                    zos.closeEntry()
                                    packedFiles++
                                    _uiState.update {
                                        it.copy(
                                            packingFolder = FolderPackProgress(
                                                folderName = folderName,
                                                packedFiles = packedFiles,
                                                packedBytes = packedBytes,
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    packDirectory(treeDocId, "")
                }

                _uiState.update { it.copy(packingFolder = null) }
                if (packedFiles == 0) {
                    runCatching { zipFile.delete() }
                    withContext(Dispatchers.Main) { showToast("所选文件夹为空") }
                } else {
                    withContext(Dispatchers.Main) {
                        sendUris(listOf(Uri.fromFile(zipFile)))
                        showToast("已打包 $packedFiles 个文件，开始发送 ${zipFile.name}")
                    }
                }
            } catch (e: Exception) {
                runCatching { zipFile?.delete() }
                _uiState.update { it.copy(packingFolder = null) }
                withContext(Dispatchers.Main) { showToast("文件夹打包失败：${e.message}") }
            }
        }
    }

    private fun queryDocumentDisplayName(docUri: Uri): String? {
        return runCatching {
            appContext.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        }.getOrNull()
    }

    // 生成供系统相机 TakePicture / CaptureVideo 写入的临时 FileProvider URI，并持久化记录防低内存杀进程丢失
    fun createCameraUri(isVideo: Boolean = false): Uri {
        val dir = File(appContext.cacheDir, "camera_captures").apply { mkdirs() }
        val ext = if (isVideo) "mp4" else "jpg"
        val prefix = if (isVideo) "VID" else "IMG"
        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.$ext")
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val uriStr = uri.toString()
        currentProcessCameraUris.add(uriStr)
        _uiState.value.activeRoom?.roomCode?.let { code ->
            store.savePendingCameraCapture(code, uriStr)
        }
        return uri
    }

    // UX-10：供 UI 或进程冷启动恢复后消费并清理未发出的相机拍照 URI
    fun consumePendingCameraCapture(roomCode: String): String? {
        return store.consumePendingCameraCapture(roomCode)
    }

    // UX-10：冷启动恢复进原房间时，若发现上一进程未消费且非空的相机拍摄文件，自动恢复发送并清理
    private fun maybeRecoverPendingCameraCapture(roomCode: String) {
        if (pendingRestoreCaptureChecked) return
        pendingRestoreCaptureChecked = true
        val pendingUriStr = store.peekPendingCameraCapture(roomCode) ?: return
        if (pendingUriStr in currentProcessCameraUris) return
        viewModelScope.launch {
            val uri = runCatching { Uri.parse(pendingUriStr) }.getOrNull()
            val nonEmpty = withContext(Dispatchers.IO) {
                uri != null && runCatching {
                    appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                        afd.length > 0L || (afd.parcelFileDescriptor.statSize > 0L)
                    } ?: false
                }.getOrDefault(false)
            }
            store.consumePendingCameraCapture(roomCode)
            if (nonEmpty && uri != null && _uiState.value.activeRoom?.roomCode.equals(roomCode, ignoreCase = true)) {
                sendUris(listOf(uri))
                showToast("已恢复发送上次未完成的相机拍摄")
            }
        }
    }

    // 录音音频焦点变化监听（来电或其它应用抢占焦点时立即停止并释放麦克风）
    private val recordAudioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (_uiState.value.isRecordingVoice) {
                        cancelVoiceRecording()
                        showToast("音频焦点被占用，已自动停止录音")
                    }
                }
            }
        }
    }

    // 放音音频焦点变化监听（来电或其它媒体抢占焦点时安全停止或降低音量）
    private val playbackAudioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    if (_uiState.value.voicePlayback.activeItemId != null || mediaPlayer != null) {
                        stopVoicePlayback()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                runCatching { mediaPlayer?.setVolume(0.25f, 0.25f) }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                runCatching { mediaPlayer?.setVolume(1f, 1f) }
            }
        }
    }

    private fun requestRecordAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(recordAudioFocusListener)
                .build()
            recordAudioFocusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonRecordAudioFocus() {
        val am = audioManager ?: return
        val req = recordAudioFocusRequest ?: return
        recordAudioFocusRequest = null
        runCatching { am.abandonAudioFocusRequest(req) }
    }

    private fun requestPlaybackAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(playbackAudioFocusListener)
                .build()
            playbackAudioFocusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonPlaybackAudioFocus() {
        val am = audioManager ?: return
        val req = playbackAudioFocusRequest ?: return
        playbackAudioFocusRequest = null
        runCatching { am.abandonAudioFocusRequest(req) }
    }

    // G10：语音对讲录制（AAC/m4a，最长 60s）与内联播放进度控制
    fun startVoiceRecording(): Boolean {
        if (_uiState.value.activeRoom == null) {
            showToast("请先进入房间")
            return false
        }
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            showToast("请先授予麦克风权限后再录制语音")
            return false
        }
        stopVoicePlayback()
        if (_uiState.value.isRecordingVoice) return false

        return runCatching {
            val dir = File(appContext.cacheDir, "voice_rec").apply { mkdirs() }
            val outFile = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setMaxDuration(MAX_VOICE_DURATION_MS.toInt())
                setOutputFile(outFile.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        stopVoiceRecording(send = true)
                    }
                }
                prepare()
                requestRecordAudioFocus()
                start()
            }
            mediaRecorder = recorder
            voiceOutputFile = outFile
            voiceRecordStartAt = System.currentTimeMillis()
            _uiState.update { it.copy(isRecordingVoice = true, voiceRecordDurationMs = 0L, voiceAmplitude = 0f) }
            voiceRecordTickerJob?.cancel()
            voiceRecordTickerJob = viewModelScope.launch {
                while (isActive && _uiState.value.isRecordingVoice) {
                    val elapsed = System.currentTimeMillis() - voiceRecordStartAt
                    val rawAmp = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val amp = (rawAmp / 32767f).coerceIn(0f, 1f)
                    val prevAmp = _uiState.value.voiceAmplitude
                    val smooth = (prevAmp * 0.6f + amp * 0.4f).coerceIn(0f, 1f)
                    _uiState.update {
                        it.copy(
                            voiceRecordDurationMs = elapsed,
                            voiceAmplitude = smooth,
                        )
                    }
                    if (elapsed >= MAX_VOICE_DURATION_MS) {
                        stopVoiceRecording(send = true)
                        break
                    }
                    delay(100L)
                }
            }
            true
        }.getOrElse { err ->
            releaseVoiceRecorder()
            showToast("无法启动录音：${err.message}")
            false
        }
    }

    fun stopVoiceRecording(send: Boolean = true) {
        if (!_uiState.value.isRecordingVoice) return
        voiceRecordTickerJob?.cancel()
        voiceRecordTickerJob = null
        val elapsedMs = (System.currentTimeMillis() - voiceRecordStartAt).coerceAtLeast(0L)
        val file = voiceOutputFile
        runCatching { mediaRecorder?.stop() }
        releaseVoiceRecorder()
        _uiState.update { it.copy(isRecordingVoice = false, voiceRecordDurationMs = 0L, voiceAmplitude = 0f) }

        if (!send || file == null || !file.exists() || file.length() == 0L || elapsedMs < MIN_VOICE_DURATION_MS) {
            runCatching { file?.delete() }
            if (send && elapsedMs < MIN_VOICE_DURATION_MS) {
                showToast("说话时间太短")
            }
            return
        }

        val room = _uiState.value.activeRoom ?: run {
            runCatching { file.delete() }
            return
        }
        val durationSec = ((elapsedMs + 500L) / 1000L).coerceIn(1L, 60L)
        val ts = System.currentTimeMillis()
        val idPrefix = "voice_${ts}_${(1000..9999).random()}"
        val source = OutgoingFileSource(
            uri = Uri.fromFile(file),
            fileName = file.name,
            fileSize = file.length(),
            mimeType = "audio/mp4",
            isImage = false,
            isAudio = true,
            caption = "${durationSec}s",
            transferIdPrefix = idPrefix,
            timestamp = ts,
        )
        val transfers = mesh?.sendFileToAll(source).orEmpty()
        outgoingSourcesByItemId[idPrefix] = source
        val profile = _uiState.value.profile
        appendMessageIfAbsent(
            ChatItem(
                id = idPrefix,
                kind = ChatKind.AUDIO,
                senderId = selfPeerId,
                senderName = profile.name,
                senderAvatar = profile.avatar,
                isSelf = true,
                timestamp = ts,
                caption = "${durationSec}s",
                localFilePath = file.absolutePath,
                transfer = transfers.firstOrNull(),
            )
        )
        val connectedPeers = mesh?.getConnectedPeerCount() ?: 0
        if (shouldUploadCloudBackup(connectedPeers, room.historyMeta.retentionDays)) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    val bytes = file.readBytes()
                    if (bytes.size <= _uiState.value.serverConfig.historyMaxMediaBytes) {
                        val uploaded = api.uploadCachedMedia(
                            code = room.roomCode,
                            password = room.password,
                            bytes = bytes,
                            mimeType = "audio/mp4",
                        )
                        signaling?.cacheMessage(
                            CachedMessage(
                                id = idPrefix,
                                kind = "audio",
                                senderId = selfPeerId,
                                senderName = profile.name,
                                senderAvatar = profile.avatar,
                                caption = "${durationSec}s",
                                mediaId = uploaded.mediaId,
                                mediaMime = uploaded.mime,
                                fileName = file.name,
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

    fun cancelVoiceRecording() {
        stopVoiceRecording(send = false)
    }

    private fun releaseVoiceRecorder() {
        runCatching { mediaRecorder?.reset() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        voiceOutputFile = null
        abandonRecordAudioFocus()
        _uiState.update { it.copy(voiceAmplitude = 0f) }
    }

    // 判断语音条目是否具备可直接播放的本地文件或已缓存媒体来源，避免连播时触发空放
    private fun hasPlayableVoiceSource(item: ChatItem): Boolean {
        val localOk = item.localFilePath?.let { File(it).exists() } == true ||
            item.transfer?.localPath?.let { File(it).exists() } == true
        val contentOk = !item.transfer?.contentUri.isNullOrBlank()
        val mediaOk = !item.mediaId.isNullOrBlank() && _uiState.value.activeRoom != null
        return localOk || contentOk || mediaOk
    }

    // 查找当前语音之后下一条未播放的对方语音（用于微信式自动连播）
    private fun findNextUnplayedPeerVoice(current: ChatItem): ChatItem? {
        val messages = _uiState.value.activeRoom?.messages ?: return null
        val played = _uiState.value.playedVoiceIds
        val idx = messages.indexOfFirst { it.id == current.id }
        val candidates = if (idx >= 0) {
            messages.drop(idx + 1)
        } else {
            messages.filter { it.timestamp > current.timestamp }
        }
        return candidates.firstOrNull { m ->
            !m.isSelf &&
                m.kind == ChatKind.AUDIO &&
                m.id !in played &&
                hasPlayableVoiceSource(m)
        }
    }

    fun toggleVoicePlayback(item: ChatItem) {
        val cur = _uiState.value.voicePlayback
        val player = mediaPlayer
        if (cur.activeItemId == item.id && player != null) {
            if (cur.isPlaying) {
                runCatching { player.pause() }
                abandonPlaybackAudioFocus()
                _uiState.update { it.copy(voicePlayback = it.voicePlayback.copy(isPlaying = false)) }
            } else {
                requestPlaybackAudioFocus()
                runCatching { player.start() }
                _uiState.update {
                    it.copy(
                        voicePlayback = it.voicePlayback.copy(isPlaying = true),
                        playedVoiceIds = it.playedVoiceIds + item.id,
                    )
                }
                startVoicePlaybackTicker()
            }
            return
        }

        stopVoicePlayback()
        val room = _uiState.value.activeRoom
        val localSource = item.localFilePath?.takeIf { File(it).exists() }
            ?: item.transfer?.localPath?.takeIf { File(it).exists() }
        val contentUriStr = item.transfer?.contentUri
        val remoteUrl = item.mediaId?.takeIf { it.isNotBlank() }?.let { mid ->
            room?.let { api.buildCachedMediaUrl(it.roomCode, mid) }
        }

        // 标记该条语音为已播放（用于隐藏未读红点与连播跳过）
        _uiState.update { it.copy(playedVoiceIds = it.playedVoiceIds + item.id) }
        requestPlaybackAudioFocus()

        runCatching {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                when {
                    localSource != null -> setDataSource(localSource)
                    !contentUriStr.isNullOrBlank() -> setDataSource(appContext, Uri.parse(contentUriStr))
                    !remoteUrl.isNullOrBlank() -> setDataSource(appContext, Uri.parse(remoteUrl), imageHeaders())
                    else -> throw IllegalStateException("找不到语音源文件")
                }
                setOnPreparedListener { readyPlayer ->
                    readyPlayer.start()
                    _uiState.update {
                        it.copy(
                            voicePlayback = VoicePlaybackState(
                                activeItemId = item.id,
                                isPlaying = true,
                                positionMs = 0L,
                                durationMs = readyPlayer.duration.toLong().coerceAtLeast(0L),
                            )
                        )
                    }
                    startVoicePlaybackTicker()
                }
                setOnCompletionListener {
                    stopVoicePlayback()
                    // 对方发来的语音播完后，自动连续播放后续下一条未播的对方语音
                    if (!item.isSelf && item.kind == ChatKind.AUDIO) {
                        findNextUnplayedPeerVoice(item)?.let { nextItem ->
                            toggleVoicePlayback(nextItem)
                        }
                    }
                }
                setOnErrorListener { _, _, _ ->
                    stopVoicePlayback()
                    showToast("语音文件播放失败")
                    true
                }
                prepareAsync()
            }
            mediaPlayer = mp
            _uiState.update {
                it.copy(voicePlayback = VoicePlaybackState(activeItemId = item.id, isPlaying = false))
            }
        }.onFailure { err ->
            stopVoicePlayback()
            showToast("无法播放语音：${err.message}")
        }
    }

    fun seekVoicePlayback(positionMs: Long) {
        val mp = mediaPlayer ?: return
        runCatching {
            mp.seekTo(positionMs.toInt().coerceAtLeast(0))
            _uiState.update { it.copy(voicePlayback = it.voicePlayback.copy(positionMs = positionMs)) }
        }
    }

    fun stopVoicePlayback() {
        voicePlaybackTickerJob?.cancel()
        voicePlaybackTickerJob = null
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        abandonPlaybackAudioFocus()
        _uiState.update { it.copy(voicePlayback = VoicePlaybackState()) }
    }

    private fun startVoicePlaybackTicker() {
        voicePlaybackTickerJob?.cancel()
        voicePlaybackTickerJob = viewModelScope.launch {
            while (isActive) {
                val mp = mediaPlayer ?: break
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                if (!playing) break
                val pos = runCatching { mp.currentPosition.toLong() }.getOrDefault(0L)
                val dur = runCatching { mp.duration.toLong() }.getOrDefault(0L)
                _uiState.update {
                    it.copy(
                        voicePlayback = it.voicePlayback.copy(
                            isPlaying = true,
                            positionMs = pos.coerceAtLeast(0L),
                            durationMs = dur.coerceAtLeast(0L),
                        )
                    )
                }
                delay(200L)
            }
        }
    }

    // 离线取件码：支持断点上传、查询、断点下载与撤销
    fun createPickup(uri: Uri, expireHours: Int = 24, burnAfterRead: Boolean = false, password: String = "") {
        if (_uiState.value.pickupBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(pickupBusy = true) }
            val meta = withContext(Dispatchers.IO) { MediaDownloadHelper.queryUriMeta(appContext, uri) }
            val startAt = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    pickupProgress = PickupTransferProgress(
                        active = true,
                        fileName = meta.fileName,
                        totalBytes = meta.fileSize,
                        isUpload = true,
                    )
                )
            }
            runCatching {
                pickupApi.uploadPickupFromUri(
                    context = appContext,
                    uri = uri,
                    fileName = meta.fileName,
                    fileSize = meta.fileSize,
                    mimeType = meta.mimeType,
                    expireHours = expireHours,
                    burnAfterRead = burnAfterRead,
                    password = password,
                ) { sent, total, code ->
                    val elapsedSec = ((System.currentTimeMillis() - startAt) / 1000.0).coerceAtLeast(0.1)
                    _uiState.update {
                        it.copy(
                            pickupProgress = PickupTransferProgress(
                                active = true,
                                code = code,
                                fileName = meta.fileName,
                                transferredBytes = sent,
                                totalBytes = total,
                                speedBps = sent / elapsedSec,
                                isUpload = true,
                            )
                        )
                    }
                }
            }.onSuccess { resp ->
                val primary = resp.files.firstOrNull()
                val rec = SavedPickupRecord(
                    code = resp.code,
                    fileName = primary?.name?.ifBlank { meta.fileName } ?: meta.fileName,
                    fileSize = primary?.size?.takeIf { it > 0L } ?: meta.fileSize,
                    mimeType = primary?.mime?.ifBlank { meta.mimeType } ?: meta.mimeType,
                    manageToken = resp.ownerToken,
                    fileId = primary?.fileId.orEmpty(),
                    createdAt = System.currentTimeMillis(),
                    expiresAt = resp.expiresAt,
                    burnAfterRead = burnAfterRead,
                )
                val updated = store.upsertMyPickup(rec)
                _uiState.update { it.copy(myPickups = updated, pickupBusy = false, pickupProgress = null) }
                playSoundIfEnabled { playTransferDone() }
                showToast("已生成取件码 ${resp.code}")
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        pickupBusy = false,
                        pickupProgress = it.pickupProgress?.copy(active = false, errorMessage = err.message),
                    )
                }
                showToast(err.message ?: "生成取件码失败")
            }
        }
    }

    fun lookupPickup(code: String, password: String = "") {
        if (_uiState.value.pickupBusy) return
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) {
            showToast("请输入取件码")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(pickupBusy = true) }
            runCatching { pickupApi.getPickupInfo(cleanCode, password) }
                .onSuccess { info ->
                    _uiState.update { it.copy(pickupLookupResult = info, pickupBusy = false) }
                }
                .onFailure { err ->
                    _uiState.update { it.copy(pickupLookupResult = null, pickupBusy = false) }
                    showToast(err.message ?: "未找到该取件码或密码有误")
                }
        }
    }

    fun clearPickupLookup() {
        _uiState.update { it.copy(pickupLookupResult = null, pickupProgress = null) }
    }

    fun downloadPickup(code: String, password: String = "") {
        val cleanCode = code.trim()
        if (cleanCode.isBlank() || _uiState.value.pickupBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(pickupBusy = true) }
            val info = _uiState.value.pickupLookupResult?.takeIf { it.code.equals(cleanCode, ignoreCase = true) }
                ?: runCatching { pickupApi.getPickupInfo(cleanCode, password) }.getOrNull()
            val targetFiles = info?.files.orEmpty()
            if (targetFiles.isEmpty()) {
                _uiState.update { it.copy(pickupBusy = false) }
                showToast("未找到可下载的取件文件，请检查取件码或密码")
                return@launch
            }
            val tmpDir = File(appContext.cacheDir, "cached_downloads").apply { mkdirs() }
            val startAt = System.currentTimeMillis()
            var lastSavedPath = ""
            runCatching {
                for (f in targetFiles) {
                    val partFile = File(tmpDir, "pickup_${cleanCode}_${f.fileId}.part")
                    _uiState.update {
                        it.copy(
                            pickupProgress = PickupTransferProgress(
                                active = true,
                                code = cleanCode,
                                fileName = f.name,
                                totalBytes = f.size,
                                isUpload = false,
                            )
                        )
                    }
                    val downloaded = pickupApi.downloadPickupFileToTemp(cleanCode, f.fileId, password, partFile, f.size) { done, total ->
                        val elapsedSec = ((System.currentTimeMillis() - startAt) / 1000.0).coerceAtLeast(0.1)
                        _uiState.update {
                            it.copy(
                                pickupProgress = PickupTransferProgress(
                                    active = true,
                                    code = cleanCode,
                                    fileName = f.name,
                                    transferredBytes = done,
                                    totalBytes = total,
                                    speedBps = done / elapsedSec,
                                    isUpload = false,
                                )
                            )
                        }
                    }
                    val saved = withContext(Dispatchers.IO) {
                        MediaDownloadHelper.saveReceivedFile(
                            context = appContext,
                            sourceFile = partFile,
                            fileName = f.name,
                            mimeType = f.mime,
                            keepLocalPreviewCopy = false,
                        )
                    }
                    lastSavedPath = saved.displayPath
                    val rec = TransferRecord(
                        id = "pickup_${cleanCode}_${f.fileId}_${System.currentTimeMillis()}",
                        fileName = saved.actualFileName,
                        fileSize = f.size.takeIf { it > 0L } ?: downloaded,
                        mimeType = f.mime,
                        direction = "receive",
                        roomCode = "取件:$cleanCode",
                        savedDisplayPath = saved.displayPath,
                        localPath = saved.previewPath,
                        contentUri = saved.contentUri?.toString(),
                        verified = true,
                    )
                    val updatedHistory = store.addTransferRecord(rec)
                    _uiState.update { it.copy(transferRecords = updatedHistory) }
                }
                _uiState.update { it.copy(pickupBusy = false, pickupProgress = null) }
                playSoundIfEnabled { playTransferDone() }
                showToast("已取件保存至 $lastSavedPath")
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        pickupBusy = false,
                        pickupProgress = it.pickupProgress?.copy(active = false, errorMessage = err.message),
                    )
                }
                showToast(err.message ?: "取件下载失败")
            }
        }
    }

    fun revokePickup(code: String) {
        val rec = _uiState.value.myPickups.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: return
        viewModelScope.launch {
            runCatching { pickupApi.revokePickup(rec.code, rec.manageToken) }
            val next = store.removeMyPickup(rec.code)
            _uiState.update { it.copy(myPickups = next) }
            showToast("已撤销取件码 ${rec.code}")
        }
    }

    private fun detectNearbyNetworkType(): String {
        val cm = connectivityManager ?: (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "unknown"
        return when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "unknown"
        }
    }

    // UX-9/UX-15：附近设备发现仅由前后台与 nearbyDiscoverable 开关控制，切 Tab 不再停表，心跳同步更新网络类型
    fun startNearbyPolling() {
        val stNow = _uiState.value
        if (!stNow.privacyAccepted || !stNow.isAppForeground || !stNow.prefs.nearbyDiscoverable) return
        if (nearbyPollingJob?.isActive == true) return
        nearbyPollingJob = viewModelScope.launch {
            while (isActive) {
                val st = _uiState.value
                if (!st.isAppForeground || !st.privacyAccepted || !st.prefs.nearbyDiscoverable) break
                val netType = detectNearbyNetworkType()
                if (st.nearbyNetworkType != netType) {
                    _uiState.update { it.copy(nearbyNetworkType = netType) }
                }
                val inLobby = st.activeRoom == null || st.roomMinimized
                val hasActiveInvite = st.incomingNearbyInvites.isNotEmpty() || st.outgoingNearbyInvite != null
                runCatching {
                    val resp = nearbyApi.heartbeat(
                        NearbyHeartbeatRequest(
                            clientId = st.profile.clientId,
                            name = st.profile.name,
                            avatar = st.profile.avatar,
                            device = "android",
                            network = netType,
                        )
                    )
                    _uiState.update {
                        it.copy(
                            nearbyNetworkType = netType,
                            nearbyPeers = resp.devices.filterNot { p ->
                                p.clientId == st.profile.clientId || isPeerBlockedInternal(null, p.clientId, p.name)
                            },
                            incomingNearbyInvites = resp.invites.filterNot { inv ->
                                isPeerBlockedInternal(null, inv.fromClientId, inv.fromName)
                            },
                        )
                    }
                }
                delay(if (inLobby || !hasActiveInvite) NEARBY_LOBBY_HEARTBEAT_MS else NEARBY_ROOM_POLL_MS)
            }
        }
    }

    fun stopNearbyPolling() {
        nearbyPollingJob?.cancel()
        nearbyPollingJob = null
    }

    // UX-4/UX-15：加并发邀请锁防连点，并在发出邀请后开启 3 秒邀请状态轮询
    fun inviteNearby(target: NearbyPeer) {
        if (_uiState.value.invitingNearbyClientId != null) return
        _uiState.update { it.copy(invitingNearbyClientId = target.clientId) }
        viewModelScope.launch {
            try {
                val profile = _uiState.value.profile
                val resp = nearbyApi.sendInvite(
                    NearbyInviteRequest(
                        fromClientId = profile.clientId,
                        toClientId = target.clientId,
                        fromName = profile.name,
                        fromAvatar = profile.avatar,
                    )
                )
                val targetRoom = resp.room
                if (targetRoom != null) {
                    if (_uiState.value.activeRoom != null) {
                        signaling?.leaveRoom()
                        tearDownSession(stopService = false)
                    }
                    recordRecentRoom(targetRoom.code, targetRoom.mode, false, "", resp.ownerToken)
                    enterRoomInternal(targetRoom.code, "", targetRoom, ownerToken = resp.ownerToken)
                }
                val roomCode = targetRoom?.code.orEmpty()
                if (resp.inviteId.isNotBlank()) {
                    _uiState.update {
                        it.copy(
                            outgoingNearbyInvite = OutgoingNearbyInvite(
                                inviteId = resp.inviteId,
                                toName = target.name,
                                roomCode = roomCode,
                                status = "waiting",
                            )
                        )
                    }
                    startOutgoingInvitePolling(resp.inviteId, target.name, roomCode)
                }
                showToast("已向附近设备「${target.name}」发送直连邀请")
            } catch (err: Exception) {
                showToast(err.message ?: "发送附近邀请失败")
            } finally {
                _uiState.update { it.copy(invitingNearbyClientId = null) }
            }
        }
    }

    // UX-15：3 秒轮询发出的附近邀请状态（accepted / declined / expired）
    private fun startOutgoingInvitePolling(inviteId: String, toName: String, roomCode: String) {
        outgoingInvitePollingJob?.cancel()
        outgoingInvitePollingJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                delay(OUTGOING_INVITE_POLL_MS)
                val current = _uiState.value.outgoingNearbyInvite ?: break
                if (current.inviteId != inviteId) break
                val status = runCatching { nearbyApi.getInviteStatus(inviteId) }.getOrNull().orEmpty().lowercase()
                when (status) {
                    "accepted" -> {
                        _uiState.update {
                            it.copy(
                                outgoingNearbyInvite = OutgoingNearbyInvite(
                                    inviteId = inviteId,
                                    toName = toName,
                                    roomCode = roomCode,
                                    status = "accepted",
                                )
                            )
                        }
                        showToast("$toName 已接受邀请，正在加入房间")
                        break
                    }
                    "declined" -> {
                        _uiState.update {
                            it.copy(
                                outgoingNearbyInvite = OutgoingNearbyInvite(
                                    inviteId = inviteId,
                                    toName = toName,
                                    roomCode = roomCode,
                                    status = "declined",
                                )
                            )
                        }
                        showToast("$toName 婉拒了你的附近邀请")
                        break
                    }
                    "expired" -> {
                        _uiState.update {
                            it.copy(
                                outgoingNearbyInvite = OutgoingNearbyInvite(
                                    inviteId = inviteId,
                                    toName = toName,
                                    roomCode = roomCode,
                                    status = "expired",
                                )
                            )
                        }
                        showToast("发给「$toName」的附近邀请已超时")
                        break
                    }
                }
                if (System.currentTimeMillis() - startedAt > OUTGOING_INVITE_MAX_WAIT_MS) {
                    _uiState.update {
                        it.copy(
                            outgoingNearbyInvite = OutgoingNearbyInvite(
                                inviteId = inviteId,
                                toName = toName,
                                roomCode = roomCode,
                                status = "expired",
                            )
                        )
                    }
                    showToast("发给「$toName」的附近邀请已超时")
                    break
                }
            }
            outgoingInvitePollingJob = null
        }
    }

    fun dismissOutgoingInviteBanner() {
        outgoingInvitePollingJob?.cancel()
        outgoingInvitePollingJob = null
        if (_uiState.value.outgoingNearbyInvite != null) {
            _uiState.update { it.copy(outgoingNearbyInvite = null) }
        }
    }

    // UX-15：若当前已在房间且需要弹切换确认框，则先暂存邀请，待确认切换时再回复服务端与移除横幅
    fun respondNearbyInvite(invite: NearbyInvite, accept: Boolean) {
        val current = _uiState.value.activeRoom
        if (accept && current != null && !current.roomCode.equals(invite.roomCode, ignoreCase = true)) {
            _uiState.update {
                it.copy(
                    pendingSwitchRoom = PendingSwitchRoom(
                        targetCode = invite.roomCode,
                        password = "",
                        source = "附近邀请（${invite.fromName}）",
                        pendingNearbyInvite = invite,
                    )
                )
            }
            return
        }
        _uiState.update { st ->
            st.copy(incomingNearbyInvites = st.incomingNearbyInvites.filterNot { it.inviteId == invite.inviteId })
        }
        viewModelScope.launch {
            val resp = runCatching {
                nearbyApi.respondInvite(invite.inviteId, _uiState.value.profile.clientId, accept)
            }.getOrNull()
            if (accept) {
                val roomCode = resp?.roomCode?.takeIf { it.isNotBlank() } ?: invite.roomCode
                joinRoomInternal(roomCode, "")
            }
        }
    }

    fun acceptFile(peerId: String, transferId: String, resumeIfAvailable: Boolean = true) {
        IncomingNotificationHelper.cancelOfferNotification(appContext, transferId)
        val ok = mesh?.acceptIncomingTransfer(peerId, transferId, resumeIfAvailable) == true
        if (!ok) {
            showToast("发送方已离线或传输已失效，请让对方重新发送")
            if (!_uiState.value.isAppForeground) {
                IncomingNotificationHelper.showFileTransferResultNotification(
                    context = appContext,
                    roomCode = _uiState.value.activeRoom?.roomCode.orEmpty(),
                    fileName = "文件接收",
                    success = false,
                    detailText = "发送方已离线或传输通道已断开，请进入房间让对方重发",
                )
            }
        }
    }

    fun rejectFile(peerId: String, transferId: String) {
        IncomingNotificationHelper.cancelOfferNotification(appContext, transferId)
        mesh?.rejectIncomingTransfer(peerId, transferId)
    }

    fun cancelTransfer(peerId: String, transferId: String) {
        mesh?.cancelTransfer(peerId, transferId)
    }

    // B19：防重复并发下载、HTTP Range 断点续传、X-Room-Password 头鉴权、失败标 ERROR
    fun downloadCachedAttachment(
        itemId: String,
        url: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
    ) {
        val room = _uiState.value.activeRoom ?: return
        if (!activeDownloadIds.add(itemId)) {
            showToast("该附件正在下载中，请勿重复点击")
            return
        }
        viewModelScope.launch {
            val startAt = System.currentTimeMillis()
            val tmpDir = File(appContext.cacheDir, "cached_downloads").apply { mkdirs() }
            val safeId = itemId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val tmpFile = File(tmpDir, "dl_${room.roomCode}_$safeId.part")
            val existingOffset = if (tmpFile.exists()) tmpFile.length().coerceAtLeast(0L) else 0L
            val progressKey = _uiState.value.activeRoom?.messages
                ?.firstOrNull { it.id == itemId }
                ?.transfer?.transferId ?: itemId

            _uiState.update { st ->
                val roomCur = st.activeRoom
                val nextRoom = roomCur?.copy(
                    messages = roomCur.messages.map { m ->
                        if (m.id == itemId && m.transfer != null) {
                            m.copy(
                                transfer = m.transfer.copy(
                                    status = TransferStatus.TRANSFERRING,
                                    transferredBytes = existingOffset,
                                    speedBps = 0.0,
                                    errorMessage = null,
                                )
                            )
                        } else m
                    }
                )
                st.copy(
                    activeRoom = nextRoom,
                    transferProgress = st.transferProgress + (
                        progressKey to TransferProgressSnapshot(
                            transferredBytes = existingOffset,
                            totalBytes = fileSize,
                            speedBps = 0L,
                            etaSeconds = 0,
                        )
                    ),
                )
            }

            try {
                api.downloadCachedFileToTemp(
                    url = url,
                    destFile = tmpFile,
                    password = room.password,
                ) { done, total ->
                    val realTotal = if (total > 0L) total else fileSize
                    val elapsedSec = ((System.currentTimeMillis() - startAt) / 1000.0).coerceAtLeast(0.1)
                    val speed = ((done - existingOffset).coerceAtLeast(0L)) / elapsedSec
                    val eta = if (speed > 0 && realTotal > done) (realTotal - done) / speed else 0.0
                    // UX-19：高频进度仅写入 transferProgress，避免触发聊天消息列表全量重组
                    _uiState.update { st ->
                        st.copy(
                            transferProgress = st.transferProgress + (
                                progressKey to TransferProgressSnapshot(
                                    transferredBytes = done,
                                    totalBytes = realTotal,
                                    speedBps = speed.toLong(),
                                    etaSeconds = eta.toInt().coerceAtLeast(0),
                                )
                            )
                        )
                    }
                }
                val saved = withContext(Dispatchers.IO) {
                    MediaDownloadHelper.saveReceivedFile(
                        context = appContext,
                        sourceFile = tmpFile,
                        fileName = fileName,
                        mimeType = mimeType,
                        keepLocalPreviewCopy = false,
                    )
                }
                _uiState.update { st ->
                    val roomCur = st.activeRoom
                    val nextRoom = roomCur?.copy(
                        messages = roomCur.messages.map { m ->
                            if (m.id == itemId && m.transfer != null) {
                                m.copy(
                                    localFilePath = saved.previewPath,
                                    transfer = m.transfer.copy(
                                        status = TransferStatus.COMPLETED,
                                        transferredBytes = m.transfer.fileSize,
                                        etaSeconds = 0.0,
                                        localPath = saved.previewPath,
                                        contentUri = saved.contentUri?.toString(),
                                        savedDisplayPath = saved.displayPath,
                                        errorMessage = null,
                                    )
                                )
                            } else m
                        }
                    )
                    st.copy(
                        activeRoom = nextRoom,
                        transferProgress = st.transferProgress - progressKey,
                    )
                }
                val rec = TransferRecord(
                    id = "${itemId}_CLOUD_DL",
                    fileName = saved.actualFileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    direction = "receive",
                    roomCode = room.roomCode,
                    savedDisplayPath = saved.displayPath,
                    localPath = saved.previewPath,
                    contentUri = saved.contentUri?.toString(),
                )
                _uiState.update { it.copy(transferRecords = store.addTransferRecord(rec)) }
                playSoundIfEnabled { playTransferDone() }
                showToast("已下载历史附件至 ${saved.displayPath}")
            } catch (err: Exception) {
                _uiState.update { st ->
                    val roomCur = st.activeRoom
                    val nextRoom = roomCur?.copy(
                        messages = roomCur.messages.map { m ->
                            if (m.id == itemId && m.transfer != null) {
                                m.copy(
                                    transfer = m.transfer.copy(
                                        status = TransferStatus.ERROR,
                                        errorMessage = err.message ?: "历史附件下载失败",
                                    )
                                )
                            } else m
                        }
                    )
                    st.copy(
                        activeRoom = nextRoom,
                        transferProgress = st.transferProgress - progressKey,
                    )
                }
                showToast(err.message ?: "历史附件下载失败")
            } finally {
                activeDownloadIds.remove(itemId)
            }
        }
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
            ChatKind.TEXT, ChatKind.CLIP -> item.text.orEmpty().take(60)
            ChatKind.IMAGE -> "[图片] ${item.caption ?: item.transfer?.fileName.orEmpty()}"
            ChatKind.AUDIO -> "[语音]"
            ChatKind.FILE -> "[文件] ${item.transfer?.fileName.orEmpty()}"
            ChatKind.SYSTEM -> return
            else -> return
        }.trim()
        updateRoom { it.copy(replyingTo = ReplyQuote(item.id, item.senderName, preview)) }
    }

    fun recallMessage(msgId: String) {
        val room = _uiState.value.activeRoom ?: return
        val target = room.messages.firstOrNull { it.id == msgId } ?: return
        if (!target.isSelf) return
        if (System.currentTimeMillis() - target.timestamp > RECALL_WINDOW_MS) {
            showToast("已超过 2 分钟，无法撤回")
            return
        }
        applyMessageRecalled(msgId, selfPeerId, _uiState.value.profile.name)
        mesh?.broadcastRecall(msgId)
        signaling?.recallMessage(msgId)
        showToast("已撤回消息")
    }

    // 仅在本地移除指定消息（不动服务端与同伴），若正在播放对应语音则停止
    fun deleteMessageLocally(msgId: String) {
        if (_uiState.value.voicePlayback.activeItemId == msgId) {
            stopVoicePlayback()
        }
        _uiState.update { st ->
            val room = st.activeRoom ?: return@update st
            val idx = room.messages.indexOfFirst { it.id == msgId }
            if (idx < 0) return@update st
            val remaining = room.messages.filterNot { it.id == msgId }
            val nextFirstUnread = if (st.firstUnreadMessageId == msgId) {
                remaining.drop(idx).firstOrNull { !it.isSelf && it.kind != ChatKind.SYSTEM }?.id
            } else {
                st.firstUnreadMessageId
            }
            st.copy(
                activeRoom = room.copy(
                    messages = remaining,
                    replyingTo = if (room.replyingTo?.id == msgId) null else room.replyingTo,
                ),
                firstUnreadMessageId = nextFirstUnread,
                unreadMentionMessageIds = st.unreadMentionMessageIds.filterNot { it == msgId },
            )
        }
    }

    // 按房间更新输入草稿（空字符串自动移除 key，最多保留最近 20 个房间）
    fun updateDraft(code: String, text: String) {
        val key = code.trim().uppercase()
        if (key.isEmpty()) return
        val next = store.saveRoomDraft(key, text)
        _uiState.update { it.copy(draftsByRoom = next) }
    }

    // 读取指定房间的已保存输入草稿
    fun draftFor(code: String): String {
        val key = code.trim().uppercase()
        if (key.isEmpty()) return ""
        return _uiState.value.draftsByRoom[key].orEmpty()
    }

    // 按房间设置消息免打扰开关（roomCode 统一大写持久化）
    fun setRoomMuted(code: String, muted: Boolean) {
        val key = code.trim().uppercase()
        if (key.isEmpty()) return
        val next = store.setRoomMuted(key, muted)
        _uiState.update { it.copy(mutedRooms = next) }
        showToast(if (muted) "已开启消息免打扰" else "已关闭消息免打扰")
    }

    // 清除「以下为新消息」未读定位标记
    fun clearFirstUnreadMarker() {
        if (_uiState.value.firstUnreadMessageId == null) return
        _uiState.update { it.copy(firstUnreadMessageId = null) }
    }

    // 忽略当前所有 @我 提醒横幅
    fun dismissMentionBanner() {
        if (_uiState.value.unreadMentionMessageIds.isEmpty()) return
        _uiState.update { it.copy(unreadMentionMessageIds = emptyList()) }
    }

    // 处理完成 @我 跳转后清理对应或全部未读提及记录
    fun jumpToMentionHandled(messageId: String? = null) {
        _uiState.update { st ->
            val next = if (messageId != null) {
                st.unreadMentionMessageIds.filterNot { it == messageId }
            } else {
                emptyList()
            }
            st.copy(unreadMentionMessageIds = next)
        }
    }

    // 仅清理本机内存中的消息流（不触碰服务端缓存，适用于普通成员与房主本地整理）
    fun clearLocalMessages() {
        stopVoicePlayback()
        updateRoom { r ->
            r.copy(
                messages = emptyList(),
                replyingTo = null,
            )
        }
        _uiState.update {
            it.copy(
                firstUnreadMessageId = null,
                unreadMentionMessageIds = emptyList(),
                unreadCount = 0,
            )
        }
        showToast("已清空本机记录")
    }

    // 解析选中消息中的可转发载荷（保持时间顺序，语音/系统/撤回/未下载云端文件跳过）
    fun resolveForwardPayloads(items: List<ChatItem>): Pair<List<ForwardPayload>, Int> {
        val payloads = mutableListOf<ForwardPayload>()
        var skipped = 0
        items.sortedBy { it.timestamp }.forEach { item ->
            when {
                item.isSelfRecall || item.kind == ChatKind.SYSTEM || item.kind == ChatKind.AUDIO -> {
                    skipped++
                }
                item.kind == ChatKind.TEXT || item.kind == ChatKind.CLIP -> {
                    val txt = item.text?.trim().orEmpty()
                    if (txt.isNotEmpty()) {
                        payloads.add(ForwardPayload.Text(txt))
                    } else {
                        skipped++
                    }
                }
                item.kind == ChatKind.IMAGE || item.kind == ChatKind.FILE -> {
                    val src = resolveLocalSourceForItem(item)
                    val uri = src?.let { MediaDownloadHelper.resolveShareableUri(appContext, it) }
                    if (uri != null) {
                        val fileName = item.transfer?.fileName?.takeIf { it.isNotBlank() }
                            ?: "baychat_${item.timestamp}"
                        val mime = item.transfer?.mimeType?.takeIf { it.isNotBlank() }
                            ?: if (item.kind == ChatKind.IMAGE) "image/*" else "*/*"
                        payloads.add(ForwardPayload.FileUri(uri, fileName, mime))
                    } else {
                        skipped++
                    }
                }
                else -> skipped++
            }
        }
        return payloads to skipped
    }

    // 逐条转发至当前房间或切换加入指定历史房间后自动发出
    fun forwardToRoom(targetCode: String, payloads: List<ForwardPayload>) {
        if (payloads.isEmpty()) {
            showToast("所选消息暂不支持转发")
            return
        }
        val cleanCode = targetCode.trim().uppercase()
        if (cleanCode.isEmpty()) return
        val texts = payloads.filterIsInstance<ForwardPayload.Text>().map { it.text }
        val uris = payloads.filterIsInstance<ForwardPayload.FileUri>().map { it.uri }
        val currentCode = _uiState.value.activeRoom?.roomCode

        if (currentCode != null && currentCode.equals(cleanCode, ignoreCase = true)) {
            updateRoom { it.copy(replyingTo = null) }
            texts.forEach { txt ->
                if (txt.isNotBlank()) sendTextMessage(txt)
            }
            if (uris.isNotEmpty()) {
                sendUris(uris)
            }
            showToast("已转发")
            return
        }

        val matchedSaved = _uiState.value.savedRooms.firstOrNull { it.code.equals(cleanCode, ignoreCase = true) }
        val matchedRecent = _uiState.value.recentRooms.firstOrNull { it.code.equals(cleanCode, ignoreCase = true) }
        val pwd = matchedSaved?.password?.takeIf { it.isNotBlank() } ?: matchedRecent?.password.orEmpty()
        val ownerToken = matchedSaved?.ownerToken?.takeIf { it.isNotBlank() } ?: matchedRecent?.ownerToken.orEmpty()

        leaveRoom()
        _uiState.update {
            it.copy(
                pendingShare = PendingExternalShare(
                    uris = uris,
                    texts = texts,
                    autoSendRoomCode = cleanCode,
                )
            )
        }
        joinRoomInternal(cleanCode, pwd, overrideOwnerToken = ownerToken)
    }

    private fun resolveLocalSourceForItem(item: ChatItem): String? =
        item.transfer?.contentUri?.takeIf { it.isNotBlank() }
            ?: item.localFilePath?.takeIf { it.isNotBlank() }
            ?: item.transfer?.localPath?.takeIf { it.isNotBlank() }
            ?: item.imageUrl?.takeIf {
                it.startsWith("content://") || it.startsWith("file://") || it.startsWith("/")
            }

    // 批量或单条添加到本地收藏（文件异步拷贝至 filesDir/favorites，避免缓存清理误删）
    fun addFavorites(items: List<ChatItem>) {
        val roomCode = _uiState.value.activeRoom?.roomCode.orEmpty().uppercase()
        val candidates = items
            .filter { it.kind != ChatKind.SYSTEM && !it.isSelfRecall }
            .sortedBy { it.timestamp }
        if (candidates.isEmpty()) {
            showToast("暂无可收藏内容")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val currentList = _uiState.value.favorites.toMutableList()
            val existingKeys = currentList.mapNotNull { it.sourceKey.takeIf(String::isNotBlank) }.toMutableSet()
            val favDir = File(appContext.filesDir, "favorites").apply { mkdirs() }
            val added = mutableListOf<FavoriteItem>()
            var duplicateCount = 0
            var skippedCount = 0

            for (item in candidates) {
                val sourceKey = "$roomCode:${item.id}"
                if (sourceKey in existingKeys) {
                    duplicateCount++
                    continue
                }
                when (item.kind) {
                    ChatKind.TEXT, ChatKind.CLIP -> {
                        val txt = item.text?.trim().orEmpty()
                        if (txt.isEmpty()) {
                            skippedCount++
                            continue
                        }
                        val fav = FavoriteItem(
                            id = "fav_${System.currentTimeMillis()}_${(1000..9999).random()}",
                            kind = "text",
                            text = txt,
                            senderName = item.senderName,
                            roomCode = roomCode,
                            savedAt = System.currentTimeMillis(),
                            sourceKey = sourceKey,
                        )
                        added.add(fav)
                        existingKeys.add(sourceKey)
                    }
                    ChatKind.IMAGE, ChatKind.FILE, ChatKind.AUDIO -> {
                        val src = resolveLocalSourceForItem(item)
                        val shareUri = src?.let { MediaDownloadHelper.resolveShareableUri(appContext, it) }
                        if (shareUri == null) {
                            skippedCount++
                            continue
                        }
                        val favId = "fav_${System.currentTimeMillis()}_${(1000..9999).random()}"
                        val defaultExt = when (item.kind) {
                            ChatKind.IMAGE -> "jpg"
                            ChatKind.AUDIO -> "m4a"
                            else -> "bin"
                        }
                        val rawName = item.transfer?.fileName?.takeIf { it.isNotBlank() }
                            ?: "baychat_${item.timestamp}.$defaultExt"
                        val safeName = rawName.replace(Regex("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]"), "_")
                        val destFile = File(favDir, "${favId}_$safeName")
                        val copied = runCatching {
                            appContext.contentResolver.openInputStream(shareUri)?.use { input ->
                                FileOutputStream(destFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            destFile.exists() && destFile.length() > 0L
                        }.getOrDefault(false)
                        if (!copied) {
                            runCatching { destFile.delete() }
                            skippedCount++
                            continue
                        }
                        val favKind = when (item.kind) {
                            ChatKind.IMAGE -> "image"
                            ChatKind.AUDIO -> "audio"
                            else -> "file"
                        }
                        val favMime = item.transfer?.mimeType?.takeIf { it.isNotBlank() } ?: when (item.kind) {
                            ChatKind.IMAGE -> "image/jpeg"
                            ChatKind.AUDIO -> "audio/mp4"
                            else -> "application/octet-stream"
                        }
                        val fav = FavoriteItem(
                            id = favId,
                            kind = favKind,
                            filePath = destFile.absolutePath,
                            fileName = rawName,
                            mime = favMime,
                            size = destFile.length(),
                            senderName = item.senderName,
                            roomCode = roomCode,
                            savedAt = System.currentTimeMillis(),
                            sourceKey = sourceKey,
                        )
                        added.add(fav)
                        existingKeys.add(sourceKey)
                    }
                    else -> skippedCount++
                }
            }

            if (added.isNotEmpty()) {
                val merged = (added.reversed() + currentList).take(300)
                store.saveFavorites(merged)
                _uiState.update { it.copy(favorites = merged) }
            }

            withContext(Dispatchers.Main) {
                val msg = when {
                    added.isNotEmpty() && skippedCount > 0 ->
                        "已收藏 ${added.size} 条（${skippedCount} 条无本地文件已跳过）"
                    added.size == 1 -> "已收藏"
                    added.size > 1 -> "已收藏 ${added.size} 条"
                    duplicateCount > 0 -> "内容已在收藏中"
                    else -> "暂无可收藏的本地文件"
                }
                showToast(msg)
            }
        }
    }

    fun removeFavorite(id: String) {
        val current = _uiState.value.favorites
        val target = current.firstOrNull { it.id == id } ?: return
        val next = current.filterNot { it.id == id }
        store.saveFavorites(next)
        _uiState.update { it.copy(favorites = next) }
        target.filePath?.let { path ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { File(path).delete() }
            }
        }
        showToast("已删除收藏")
    }

    fun sendFavorite(fav: FavoriteItem) {
        if (_uiState.value.activeRoom == null) {
            showToast("请先加入房间后再发送")
            return
        }
        if (fav.kind == "text") {
            val txt = fav.text?.trim().orEmpty()
            if (txt.isEmpty()) return
            sendTextMessage(txt)
            showToast("已发送到当前房间")
            return
        }
        val file = fav.filePath?.let { File(it) }?.takeIf { it.exists() }
        if (file == null) {
            showToast("收藏文件已损坏或被移除")
            return
        }
        val uri = runCatching {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        }.getOrNull()
        if (uri == null) {
            showToast("无法读取收藏文件")
            return
        }
        sendUris(listOf(uri))
        showToast("已发送到当前房间")
    }

    fun recordEmojiUsed(emoji: String) {
        if (emoji.isBlank()) return
        val next = (listOf(emoji) + _uiState.value.recentEmojis.filterNot { it == emoji }).take(24)
        store.saveRecentEmojis(next)
        _uiState.update { it.copy(recentEmojis = next) }
    }

    // 微信式「拍一拍」：3 秒节流，仅本地提示 + P2P 广播，不落服务端历史
    fun nudgePeer(targetPeerId: String, targetName: String) {
        val room = _uiState.value.activeRoom ?: return
        val now = System.currentTimeMillis()
        if (now - lastNudgeAt < 3_000L) {
            showToast("操作太频繁")
            return
        }
        lastNudgeAt = now
        val isSelfTarget = targetPeerId == selfPeerId || targetPeerId == room.selfPeerId
        val resolvedTargetName = if (isSelfTarget) _uiState.value.profile.name else targetName
        val nudgeId = "nudge_${now}_${(1000..9999).random()}"
        seenNudgeIds.add(nudgeId)
        val text = FormatUtils.formatNudgeText(
            fromName = _uiState.value.profile.name,
            targetName = resolvedTargetName,
            isSelfSender = true,
            isSelfTarget = isSelfTarget,
        )
        appendMessageIfAbsent(
            ChatItem(
                id = nudgeId,
                kind = ChatKind.SYSTEM,
                senderId = "system",
                senderName = "系统",
                isSelf = false,
                timestamp = now,
                text = text,
            )
        )
        _uiState.update { it.copy(nudgedPeerId = targetPeerId, nudgeNonce = it.nudgeNonce + 1) }
        mesh?.broadcastNudge(
            id = nudgeId,
            targetPeerId = targetPeerId,
            targetName = resolvedTargetName,
            fromName = _uiState.value.profile.name,
            timestamp = now,
        )
    }

    private fun handleIncomingNudge(
        peerId: String,
        id: String,
        targetPeerId: String,
        targetName: String,
        fromName: String,
        timestamp: Long,
    ) {
        if (!seenNudgeIds.add(id)) return
        if (seenNudgeIds.size > 500) {
            val trim = seenNudgeIds.take(100)
            seenNudgeIds.removeAll(trim.toSet())
        }
        val room = _uiState.value.activeRoom ?: return
        val isSelfTarget = targetPeerId == room.selfPeerId || targetPeerId == selfPeerId
        val isSenderTargetSelf = targetPeerId == peerId
        val senderLabel = room.peers[peerId]?.name ?: fromName.ifBlank { "伙伴" }
        val targetLabel = if (isSelfTarget) {
            _uiState.value.profile.name
        } else {
            room.peers[targetPeerId]?.name ?: targetName.ifBlank { "伙伴" }
        }
        val text = FormatUtils.formatNudgeText(
            fromName = senderLabel,
            targetName = targetLabel,
            isSelfSender = false,
            isSelfTarget = isSelfTarget,
            isSenderTargetSelf = isSenderTargetSelf,
        )
        val inserted = appendMessageIfAbsent(
            ChatItem(
                id = id,
                kind = ChatKind.SYSTEM,
                senderId = "system",
                senderName = "系统",
                isSelf = false,
                timestamp = timestamp,
                text = text,
            )
        )
        if (inserted) {
            _uiState.update { it.copy(nudgedPeerId = targetPeerId, nudgeNonce = it.nudgeNonce + 1) }
            if (isSelfTarget) {
                recordUnreadArrival(itemId = id, mentionedMe = false)
                if (!isRoomMuted(room.roomCode)) {
                    playSoundIfEnabled { playMessageIn() }
                    if (!_uiState.value.isAppForeground) {
                        IncomingNotificationHelper.showBackgroundMessageNotification(
                            context = appContext,
                            roomCode = room.roomCode,
                            unreadCount = _uiState.value.unreadCount.coerceAtLeast(1),
                            senderName = senderLabel,
                            previewText = text,
                            mentionedMe = false,
                        )
                    }
                }
            }
        }
    }

    fun reactMessage(msgId: String, emoji: String) {
        toggleReactionLocal(msgId, emoji, selfPeerId)
        mesh?.broadcastReaction(msgId, emoji)
    }

    fun setSearchFilter(keyword: String, filterKind: String) {
        updateRoom { it.copy(searchKeyword = keyword, filterKind = filterKind) }
    }

    // B5：房主权限校验
    private fun requireRoomOwner(): ActiveRoomSession? {
        val room = _uiState.value.activeRoom ?: return null
        if (!room.isOwner) {
            showToast("仅房主可操作")
            return null
        }
        return room
    }

    fun updateRetentionDays(days: Int) {
        if (requireRoomOwner() == null) return
        signaling?.updateRetention(days)
    }

    fun updateRoomMaxFileMB(mb: Int) {
        if (requireRoomOwner() == null) return
        val cap = _uiState.value.serverConfig.maxAttachmentMB
        if (cap > 0 && (mb == 0 || mb > cap)) {
            showToast("服务端限制附件上限最高不超过 ${FormatUtils.formatLimitMB(cap)}")
            return
        }
        signaling?.updateMaxFileSize(mb)
    }

    fun updateAllowRandom(allowRandom: Boolean) {
        val room = requireRoomOwner() ?: return
        if (room.password.isNotBlank() || room.roomInfo?.hasPassword == true) {
            showToast("加密房间禁止开启随机串门")
            return
        }
        signaling?.updateAllowRandom(allowRandom)
    }

    // G7：房主修改常驻房主题或密码
    fun updateRoomProfile(topic: String? = null, newPassword: String? = null) {
        val room = requireRoomOwner() ?: return
        viewModelScope.launch {
            runCatching {
                api.updateRoomProfile(
                    code = room.roomCode,
                    password = room.password,
                    ownerToken = room.ownerToken,
                    topic = topic?.trim(),
                    newPassword = newPassword?.trim(),
                    actorName = _uiState.value.profile.name,
                )
            }.onSuccess { updated ->
                val resolvedPwd = newPassword?.trim() ?: room.password
                updateRoom { r ->
                    r.copy(
                        password = resolvedPwd,
                        roomInfo = updated,
                    )
                }
                if (newPassword != null) {
                    val saved = store.updateSavedPassword(room.roomCode, resolvedPwd)
                    _uiState.update { it.copy(savedRooms = saved) }
                    recordRecentRoom(room.roomCode, updated.mode, resolvedPwd.isNotBlank(), resolvedPwd, room.ownerToken)
                }
                showToast("已更新房间信息")
            }.onFailure { err ->
                showToast(err.message ?: "更新房间信息失败")
            }
        }
    }

    fun clearServerHistory() {
        val room = requireRoomOwner() ?: return
        signaling?.clearHistory()
        if (room.ownerToken.isNotBlank()) {
            viewModelScope.launch {
                runCatching {
                    api.clearRoomHistory(
                        code = room.roomCode,
                        password = room.password,
                        ownerToken = room.ownerToken,
                        actorName = _uiState.value.profile.name,
                    )
                }
            }
        }
    }

    // G13：导出聊天记录并返回可直接打开/分享的 contentUri
    suspend fun exportChatHistory(): Uri? {
        val room = _uiState.value.activeRoom ?: return null
        return withContext(Dispatchers.IO) {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val lines = room.messages.map { m ->
                val ts = fmt.format(Date(m.timestamp))
                when (m.kind) {
                    ChatKind.SYSTEM -> "[$ts] [系统] ${m.text.orEmpty()}"
                    ChatKind.TEXT -> {
                        val quote = m.replyTo?.let { "（引用 @${it.senderName}：${it.preview}）" }.orEmpty()
                        "[$ts] ${m.senderName}$quote: ${m.text.orEmpty()}"
                    }
                    ChatKind.CLIP -> "[$ts] ${m.senderName}: [剪贴板] ${m.text.orEmpty()}"
                    ChatKind.IMAGE -> "[$ts] ${m.senderName}: [图片] ${m.caption ?: m.transfer?.fileName.orEmpty()}"
                    ChatKind.AUDIO -> "[$ts] ${m.senderName}: [语音] ${m.caption.orEmpty()}"
                    ChatKind.FILE -> "[$ts] ${m.senderName}: [文件] ${m.transfer?.fileName.orEmpty()} (${FormatUtils.formatBytes(m.transfer?.fileSize ?: 0)})"
                    else -> "[$ts] ${m.senderName}: ${m.text.orEmpty()}"
                }
            }
            val content = buildString {
                appendLine("# BayChat 房间 #${room.roomCode} 聊天记录")
                appendLine("导出时间：${fmt.format(Date())}")
                appendLine("共 ${lines.size} 条记录")
                appendLine()
                lines.forEach { appendLine(it) }
            }
            val result = runCatching {
                MediaDownloadHelper.saveTextToDownloadsResult(
                    appContext,
                    "baychat-${room.roomCode}-${System.currentTimeMillis()}.txt",
                    content,
                )
            }.getOrNull()
            withContext(Dispatchers.Main) {
                showToast(if (result != null) "已导出至 ${result.displayPath}" else "导出失败")
            }
            result?.contentUri
        }
    }

    fun exportChatLog() {
        viewModelScope.launch {
            exportChatHistory()
        }
    }

    // B14：安全换码并先 P2P 广播 room-moved 让同伴无缝跟随到新口令
    fun rotateEphemeralCode() {
        val room = requireRoomOwner() ?: return
        if (!room.isEphemeral) {
            showToast("仅临时房间支持更换口令")
            return
        }
        viewModelScope.launch {
            runCatching {
                api.createRoom(
                    CreateRoomRequest(
                        mode = RoomMode.EPHEMERAL.wireValue,
                        password = room.password.takeIf { it.isNotBlank() },
                        allowRandom = room.roomInfo?.allowRandom ?: false,
                        topic = room.roomInfo?.topic?.takeIf { it.isNotBlank() },
                        maxPeers = room.roomInfo?.maxPeers ?: _uiState.value.serverConfig.maxRoomPeers,
                        retentionDays = room.historyMeta.retentionDays,
                        maxFileSizeMB = effectiveMaxFileMB(),
                    )
                )
            }.onSuccess { next ->
                mesh?.broadcastRoomMoved(next.code, room.password)
                delay(800L)
                signaling?.destroyRoom()
                removeRecentRoom(room.roomCode)
                val nextOwner = next.ownerToken.orEmpty()
                recordRecentRoom(next.code, next.mode, next.hasPassword, room.password, nextOwner)
                enterRoomInternal(next.code, room.password, next, ownerToken = nextOwner)
                showToast("已切换全新房间口令：${next.code}，伙伴将自动跟随")
            }.onFailure {
                showToast(it.message ?: "换码失败")
            }
        }
    }

    // 收起房间至悬浮球，仅首次收起时给出一次性操作提示（随进随出）
    fun minimizeRoom() {
        cancelVoiceRecording()
        val state = _uiState.value
        if (state.activeRoom != null) {
            val shouldShowHint = !state.prefs.minimizeHintShown
            if (shouldShowHint) {
                store.setMinimizeHintShown(true)
            }
            _uiState.update {
                it.copy(
                    roomMinimized = true,
                    prefs = if (shouldShowHint) it.prefs.copy(minimizeHintShown = true) else it.prefs,
                )
            }
            if (shouldShowHint) {
                showToast("房间已收起，连接与传输保持中。点悬浮球返回，在「聊天信息」里可退出房间")
            }
        }
    }

    fun restoreRoom() {
        if (_uiState.value.activeRoom != null) {
            _uiState.update { it.copy(roomMinimized = false, unreadCount = 0) }
            IncomingNotificationHelper.cancelMessageNotification(appContext)
        }
    }

    fun restoreRoomFromNotification(roomCode: String) {
        val current = _uiState.value.activeRoom
        if (current != null && (roomCode.isBlank() || current.roomCode.equals(roomCode, ignoreCase = true))) {
            restoreRoom()
            return
        }
        val last = _uiState.value.lastRecoverableRoom
        if (last != null && (roomCode.isBlank() || last.code.equals(roomCode, ignoreCase = true))) {
            recoverLastRoom()
            return
        }
        if (roomCode.isNotBlank()) {
            joinRoom(roomCode, "")
        }
    }

    fun switchToRecentRoom(item: RecentRoom) {
        cancelVoiceRecording()
        val current = _uiState.value.activeRoom
        if (current != null && current.roomCode.equals(item.code, ignoreCase = true)) {
            restoreRoom()
            return
        }
        if (current != null && !current.roomCode.equals(item.code, ignoreCase = true)) {
            _uiState.update {
                it.copy(
                    pendingSwitchRoom = PendingSwitchRoom(
                        targetCode = item.code,
                        password = item.password,
                        source = "最近房间",
                    )
                )
            }
            return
        }
        joinRoomInternal(item.code, item.password, overrideOwnerToken = item.ownerToken)
    }

    fun leaveRoom() {
        cancelVoiceRecording()
        val room = _uiState.value.activeRoom
        // B15：最后一人主动离开临时房时清理过期最近记录
        if (room != null && room.isEphemeral && room.peers.isEmpty()) {
            removeRecentRoom(room.roomCode)
        }
        store.clearLastActiveRoom()
        signaling?.leaveRoom()
        outgoingInvitePollingJob?.cancel()
        outgoingInvitePollingJob = null
        tearDownSession(stopService = true)
        _uiState.update {
            it.copy(
                activeRoom = null,
                roomMinimized = false,
                unreadCount = 0,
                playedVoiceIds = emptySet(),
                firstUnreadMessageId = null,
                unreadMentionMessageIds = emptyList(),
                outgoingNearbyInvite = null,
                transferProgress = emptyMap(),
            )
        }
    }

    fun destroyRoom() {
        val room = requireRoomOwner() ?: return
        cancelVoiceRecording()
        val curCode = room.roomCode
        store.clearLastActiveRoom()
        signaling?.destroyRoom()
        outgoingInvitePollingJob?.cancel()
        outgoingInvitePollingJob = null
        if (room.ownerToken.isNotBlank()) {
            viewModelScope.launch {
                runCatching {
                    api.deleteRoom(
                        code = curCode,
                        password = room.password,
                        ownerToken = room.ownerToken,
                        actorName = _uiState.value.profile.name,
                    )
                }
            }
        }
        tearDownSession(stopService = true)
        val nextRecent = store.removeRecentRoom(curCode)
        val nextSaved = store.removeSavedRoom(curCode)
        _uiState.update {
            it.copy(
                activeRoom = null,
                roomMinimized = false,
                unreadCount = 0,
                playedVoiceIds = emptySet(),
                firstUnreadMessageId = null,
                unreadMentionMessageIds = emptyList(),
                outgoingNearbyInvite = null,
                transferProgress = emptyMap(),
                recentRooms = nextRecent,
                savedRooms = nextSaved,
            )
        }
        showToast("房间与服务器缓存已彻底销毁")
    }

    // 将服务端 CachedMessage 转换为本地 ChatItem；若为已存在的实时文件传输补全缓存直链则仅返回 null 并交由外部刷新
    private fun buildChatItemFromCached(
        roomCode: String,
        cm: CachedMessage,
        fromCache: Boolean,
    ): ChatItem? {
        val isSelf = cm.senderId == selfPeerId
        if (!isSelf && isPeerBlockedInternal(cm.senderId, null, cm.senderName)) return null
        return when (cm.kind) {
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
                    imageUrl = api.buildCachedMediaUrl(roomCode, cm.mediaId),
                    mediaId = cm.mediaId,
                    fromCache = fromCache,
                )
            } else null

            "audio" -> if (!cm.mediaId.isNullOrBlank()) {
                ChatItem(
                    id = cm.id,
                    kind = ChatKind.AUDIO,
                    senderId = cm.senderId,
                    senderName = cm.senderName.ifBlank { "伙伴" },
                    senderAvatar = cm.senderAvatar,
                    isSelf = isSelf,
                    timestamp = cm.timestamp,
                    caption = cm.caption,
                    mediaId = cm.mediaId,
                    fromCache = fromCache,
                    transfer = FileTransferInfo(
                        transferId = cm.id,
                        streamId = 0,
                        fileName = cm.fileName ?: "voice_${cm.timestamp}.m4a",
                        fileSize = cm.fileSize ?: 0L,
                        mimeType = cm.mediaMime ?: "audio/mp4",
                        isImage = false,
                        isAudio = true,
                        direction = if (isSelf) TransferDirection.SEND else TransferDirection.RECEIVE,
                        status = TransferStatus.COMPLETED,
                        cachedDownloadUrl = api.buildCachedMediaUrl(roomCode, cm.mediaId),
                        isHistoryRecord = true,
                    ),
                )
            } else null

            "file_notice" -> {
                val cachedUrl = cm.mediaId?.takeIf { it.isNotBlank() }
                    ?.let { api.buildCachedFileUrl(roomCode, it) }
                ChatItem(
                    id = cm.id,
                    kind = ChatKind.FILE,
                    senderId = cm.senderId,
                    senderName = cm.senderName.ifBlank { "伙伴" },
                    senderAvatar = cm.senderAvatar,
                    isSelf = isSelf,
                    timestamp = cm.timestamp,
                    mediaId = cm.mediaId,
                    fromCache = fromCache,
                    transfer = FileTransferInfo(
                        transferId = cm.id,
                        streamId = 0,
                        fileName = cm.fileName?.ifBlank { "未命名附件" } ?: "未命名附件",
                        fileSize = cm.fileSize ?: 0L,
                        mimeType = cm.mediaMime?.ifBlank { "application/octet-stream" } ?: "application/octet-stream",
                        isImage = false,
                        direction = if (isSelf) TransferDirection.SEND else TransferDirection.RECEIVE,
                        status = TransferStatus.COMPLETED,
                        transferredBytes = cm.fileSize ?: 0L,
                        cachedDownloadUrl = cachedUrl,
                        isHistoryRecord = true,
                    ),
                )
            }

            else -> {
                val quote = if (!cm.replyToId.isNullOrBlank() && !cm.replyToPreview.isNullOrBlank()) {
                    ReplyQuote(cm.replyToId, cm.replyToSender ?: "伙伴", cm.replyToPreview)
                } else null
                val decodedText = FormatUtils.decodeLegacySecret(cm.text)
                val mentioned = !isSelf && isTextMentioningMe(decodedText)
                ChatItem(
                    id = cm.id,
                    kind = ChatKind.TEXT,
                    senderId = cm.senderId,
                    senderName = cm.senderName.ifBlank { "伙伴" },
                    senderAvatar = cm.senderAvatar,
                    isSelf = isSelf,
                    timestamp = cm.timestamp,
                    text = decodedText,
                    replyTo = quote,
                    fromCache = fromCache,
                    mentionedMe = mentioned,
                )
            }
        }
    }

    // 进房批量同步服务端历史记录：单次原子去重合并并按时间戳升序排列，保证最新消息始终在列表底部
    private fun mergeCachedMessagesBatch(cachedList: List<CachedMessage>, fromCache: Boolean) {
        if (cachedList.isEmpty()) return
        updateRoom { r ->
            val currentMessages = r.messages.toMutableList()
            val existingIds = currentMessages.mapTo(HashSet()) { it.id }

            cachedList.forEach { cm ->
                if (cm.kind == "file_notice") {
                    val basePrefix = cm.id.removePrefix("fnotice_")
                    val cachedUrl = cm.mediaId?.takeIf { it.isNotBlank() }
                        ?.let { api.buildCachedFileUrl(r.roomCode, it) }
                    val existingLiveIdx = currentMessages.indexOfFirst { m ->
                        m.kind == ChatKind.FILE && m.transfer != null &&
                            (m.transfer.transferId == basePrefix || m.transfer.transferId.startsWith("${basePrefix}_"))
                    }
                    if (existingLiveIdx >= 0) {
                        if (cachedUrl != null) {
                            val live = currentMessages[existingLiveIdx]
                            live.transfer?.let { tr ->
                                currentMessages[existingLiveIdx] = live.copy(
                                    transfer = tr.copy(cachedDownloadUrl = cachedUrl)
                                )
                            }
                        }
                        return@forEach
                    }
                }
                if (cm.id in existingIds) return@forEach
                val built = buildChatItemFromCached(r.roomCode, cm, fromCache) ?: return@forEach
                existingIds.add(built.id)
                currentMessages.add(built)
            }

            r.copy(messages = currentMessages.sortedBy { it.timestamp })
        }
    }

    private fun mergeCachedMessage(cm: CachedMessage, fromCache: Boolean): ChatItem? {
        val room = _uiState.value.activeRoom ?: return null
        if (cm.kind == "file_notice") {
            val cachedUrl = cm.mediaId?.takeIf { it.isNotBlank() }
                ?.let { api.buildCachedFileUrl(room.roomCode, it) }
            val basePrefix = cm.id.removePrefix("fnotice_")
            val existingLive = room.messages.any { m ->
                m.kind == ChatKind.FILE && m.transfer != null &&
                    (m.transfer.transferId == basePrefix || m.transfer.transferId.startsWith("${basePrefix}_"))
            }
            if (existingLive) {
                if (cachedUrl != null) {
                    updateRoom { r ->
                        r.copy(
                            messages = r.messages.map { m ->
                                if (m.kind == ChatKind.FILE && m.transfer != null &&
                                    (m.transfer.transferId == basePrefix || m.transfer.transferId.startsWith("${basePrefix}_"))
                                ) {
                                    m.copy(transfer = m.transfer.copy(cachedDownloadUrl = cachedUrl))
                                } else m
                            }
                        )
                    }
                }
                return null
            }
        }
        val item = buildChatItemFromCached(room.roomCode, cm, fromCache) ?: return null
        val inserted = appendMessageIfAbsent(item)
        if (!inserted) return null
        if (!fromCache && !item.isSelf) {
            val previewText = when (item.kind) {
                ChatKind.IMAGE -> "[图片]"
                ChatKind.AUDIO -> "[语音消息]"
                ChatKind.FILE -> "[文件] ${item.transfer?.fileName ?: "附件"}"
                else -> item.text?.takeIf { it.isNotBlank() } ?: "[新消息]"
            }
            handleIncomingMessageArrived(
                item = item,
                fromCache = false,
                roomCode = room.roomCode,
                previewText = previewText,
            )
        }
        return item
    }

    private fun applyMessageRecalled(msgId: String, actorPeerId: String?, actorName: String?) {
        if (_uiState.value.voicePlayback.activeItemId == msgId) {
            stopVoicePlayback()
        }
        _uiState.update { st ->
            val r = st.activeRoom ?: return@update st
            val targetIdx = r.messages.indexOfFirst { it.id == msgId }
            if (targetIdx < 0) return@update st
            val target = r.messages[targetIdx]
            if (actorPeerId != null && target.senderId != actorPeerId) return@update st
            // 接收端防篡改校验：超过 2 分钟 + 60 秒时钟偏差容忍上限时直接忽略撤回
            if (System.currentTimeMillis() - target.timestamp > RECALL_WINDOW_MS + 60_000L) {
                return@update st
            }
            val isSelfRecall = target.isSelf || (actorPeerId != null && actorPeerId == selfPeerId)
            val recalledText = if (isSelfRecall && target.kind == ChatKind.TEXT) target.text else null
            val senderDisplay = actorName?.takeIf { it.isNotBlank() } ?: target.senderName
            val recallNoticeText = if (isSelfRecall) {
                "你撤回了一条消息"
            } else {
                "\"${senderDisplay}\" 撤回了一条消息"
            }
            val recallItem = ChatItem(
                id = "recalled_$msgId",
                kind = ChatKind.SYSTEM,
                senderId = "system",
                senderName = "系统",
                isSelf = false,
                timestamp = target.timestamp,
                text = recallNoticeText,
                isSelfRecall = isSelfRecall,
                recalledText = recalledText,
            )
            // 在原位替换为撤回系统提示，并同步将所有引用该消息的 replyToPreview 刷新为已撤回提示
            val updatedMessages = r.messages.map { m ->
                when {
                    m.id == msgId -> recallItem
                    m.replyTo?.id == msgId -> m.copy(
                        replyTo = m.replyTo.copy(preview = "该引用内容已撤回")
                    )
                    else -> m
                }
            }
            val nextFirstUnread = if (st.firstUnreadMessageId == msgId) {
                updatedMessages.drop(targetIdx + 1).firstOrNull { !it.isSelf && it.kind != ChatKind.SYSTEM }?.id
            } else {
                st.firstUnreadMessageId
            }
            st.copy(
                activeRoom = r.copy(
                    messages = updatedMessages,
                    replyingTo = if (r.replyingTo?.id == msgId) null else r.replyingTo,
                ),
                firstUnreadMessageId = nextFirstUnread,
                unreadMentionMessageIds = st.unreadMentionMessageIds.filterNot { it == msgId },
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

    private fun appendMessageIfAbsent(item: ChatItem): Boolean {
        var inserted = false
        updateRoom { r ->
            if (r.messages.any { it.id == item.id }) {
                inserted = false
                r
            } else {
                inserted = true
                r.copy(messages = r.messages + item)
            }
        }
        return inserted
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
        draftIdleTypingJob?.cancel()
        draftIdleTypingJob = null
        typingTimeoutJobs.values.forEach { it.cancel() }
        typingTimeoutJobs.clear()
        stopVoicePlayback()
        if (_uiState.value.isRecordingVoice) {
            cancelVoiceRecording()
        }
        signaling?.disconnect()
        signaling = null
        mesh?.destroyAll()
        mesh = null
        IncomingNotificationHelper.cancelAllIncomingNotifications(appContext)
        if (stopService) {
            TransferForegroundService.stop(appContext)
        }
    }

    override fun onCleared() {
        stopNearbyPolling()
        outgoingInvitePollingJob?.cancel()
        outgoingInvitePollingJob = null
        networkCallback?.let { cb ->
            runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        tearDownSession(stopService = true)
        runCatching { soundPlayer.release() }
        super.onCleared()
    }

    companion object {
        // 切回前台自动静默复查更新的最小时间间隔（10 分钟）
        private const val FOREGROUND_UPDATE_CHECK_INTERVAL_MS = 10L * 60L * 1000L
        // 发现页 15 秒 SWR 静默刷新过期阈值
        private const val DISCOVER_SWR_STALE_MS = 15_000L
        // 最近活跃房间断线冷启动恢复窗口（10 分钟）
        private const val LAST_ROOM_RECOVER_TTL_MS = 10L * 60L * 1000L
        // B20：正在输入状态 6 秒自动超时清除
        private const val TYPING_TIMEOUT_MS = 6_000L
        // 最短与最长语音消息录制时长（1 秒 ~ 60 秒）
        private const val MIN_VOICE_DURATION_MS = 1_000L
        private const val MAX_VOICE_DURATION_MS = 60_000L
        // 消息撤回有效时间窗口（2 分钟）
        const val RECALL_WINDOW_MS = 120_000L
        // @提及 后缀允许的常见分隔标点符号
        private val MENTION_BOUNDARY_CHARS = setOf(',', '.', ':', ';', '!', '?', '，', '。', '：', '；', '！', '？', ')', '）', ']', '】')
        // 同一文案 Toast 去重时间窗口（3 秒）
        private const val TOAST_DEDUP_WINDOW_MS = 3_000L
        // 重发/续传动作去重防连点窗口（600 毫秒）
        private const val RESEND_DEBOUNCE_MS = 600L
        // 附近设备心跳与房内邀请轮询间隔
        private const val NEARBY_LOBBY_HEARTBEAT_MS = 5_000L
        private const val NEARBY_ROOM_POLL_MS = 3_000L
        private const val OUTGOING_INVITE_POLL_MS = 3_000L
        private const val OUTGOING_INVITE_MAX_WAIT_MS = 70_000L
    }
}
