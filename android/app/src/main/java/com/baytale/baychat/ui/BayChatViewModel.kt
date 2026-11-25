package com.baytale.baychat.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.core.model.AppReleaseInfo
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.CachedMessage
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.CreateRoomRequest
import com.baytale.baychat.core.model.FileTransferInfo
import com.baytale.baychat.core.model.HistoryMeta
import com.baytale.baychat.core.model.LinkTransportType
import com.baytale.baychat.core.model.PartialFileMeta
import com.baytale.baychat.core.model.PartialResumeItem
import com.baytale.baychat.core.model.PeerLinkStatus
import com.baytale.baychat.core.model.PeerProfile
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.ReplyQuote
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.model.RoomSummary
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.model.ServerConfig
import com.baytale.baychat.core.model.SignalEnvelope
import com.baytale.baychat.core.model.TransferDirection
import com.baytale.baychat.core.model.TransferRecord
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.model.UserProfile
import com.baytale.baychat.core.network.ApiException
import com.baytale.baychat.core.network.AppUpdateManager
import com.baytale.baychat.core.network.BayChatApi
import com.baytale.baychat.core.network.SignalingCallbacks
import com.baytale.baychat.core.network.SignalingClient
import com.baytale.baychat.core.storage.AppPrefsStore
import com.baytale.baychat.core.storage.AppUserPrefs
import com.baytale.baychat.core.storage.MediaDownloadHelper
import com.baytale.baychat.core.util.BaySoundPlayer
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.core.webrtc.MeshCallbacks
import com.baytale.baychat.core.webrtc.OutgoingFileSource
import com.baytale.baychat.core.webrtc.WebRtcMeshManager
import com.baytale.baychat.service.AppUpdateCenter
import com.baytale.baychat.service.AppUpdateService
import com.baytale.baychat.service.OtaUiEvent
import com.baytale.baychat.service.OtaUpdateState
import com.baytale.baychat.service.OtaUpdateStatus
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
import java.util.concurrent.atomic.AtomicBoolean

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
    val lobbyBusy: Boolean = false,
    val lobbyError: String? = null,
    val lobbyNeedPassword: Boolean = false,
    val pendingJoinCode: String? = null,
    val pendingShare: PendingExternalShare = PendingExternalShare(),
    val activeRoom: ActiveRoomSession? = null,
    val roomMinimized: Boolean = false,
    val toastMessage: String? = null,
)

// 全局业务控制器：统一管理大厅、发现广场、传输中心、房间信令、WebRTC Mesh 传输、自动更新与前台保活通知
class BayChatViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val store = AppPrefsStore(appContext)
    private val api = BayChatApi(serverUrlProvider = { _uiState.value.prefs.serverUrl })
    private val updateManager = AppUpdateManager(appContext, api.okHttpClient)
    private val soundPlayer = BaySoundPlayer(appContext)

    private val _uiState = MutableStateFlow(
        BayChatUiState(
            privacyAccepted = store.hasAcceptedPrivacy(),
            profile = store.loadProfile(),
            themeMode = store.loadTheme(),
            prefs = store.loadPrefs(),
            savedRooms = store.loadSavedRooms(),
            recentRooms = store.loadRecentRooms(),
            transferRecords = store.loadTransferRecords(),
        )
    )
    val uiState: StateFlow<BayChatUiState> = _uiState.asStateFlow()

    private var selfPeerId: String = FormatUtils.generatePeerId()
    private var signaling: SignalingClient? = null
    private var mesh: WebRtcMeshManager? = null
    private val recordedTransfers = mutableSetOf<String>()
    private var awaitingInstallPermission: Boolean = false

    init {
        refreshTransferCenter()
        observeOtaUpdateCenter()
        if (_uiState.value.privacyAccepted) {
            refreshServerConfig()
            refreshPublicRooms()
            // 冷启动自动静默检查一次更新（Wi-Fi 自动后台预下载，移动网络仅温和弹窗）
            checkForAppUpdate(manual = false, bypassThrottle = true)
        }
    }

    private fun observeOtaUpdateCenter() {
        viewModelScope.launch {
            var lastStatus = AppUpdateCenter.state.value.status
            AppUpdateCenter.state.collect { ota ->
                if (ota.status == OtaUpdateStatus.READY_TO_INSTALL && lastStatus != OtaUpdateStatus.READY_TO_INSTALL) {
                    // OTA 更新包下载并校验就绪时播放清脆三和弦提示
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
    }

    // 切回前台回调：若刚授权完「安装未知应用」则自动续拉系统安装器；否则间隔 ≥ 10 分钟才静默复查更新
    fun onAppForegrounded() {
        if (!_uiState.value.privacyAccepted) return
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
            ota.status == OtaUpdateStatus.VERIFYING ||
            ota.status == OtaUpdateStatus.READY_TO_INSTALL
        ) {
            return
        }
        val elapsed = System.currentTimeMillis() - _uiState.value.prefs.lastUpdateCheckAt
        if (elapsed >= FOREGROUND_UPDATE_CHECK_INTERVAL_MS) {
            checkForAppUpdate(manual = false, bypassThrottle = false)
        }
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
        _uiState.update { it.copy(lobbyError = null, lobbyNeedPassword = false) }
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
            )
        )
        showToast("已更新昵称为「${next.name}」")
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

    fun removeRecentRoom(code: String) {
        val next = store.removeRecentRoom(code)
        _uiState.update { it.copy(recentRooms = next) }
    }

    fun clearRecentRooms() {
        val next = store.clearRecentRooms()
        _uiState.update { it.copy(recentRooms = next) }
        showToast("已清空最近房间记录")
    }

    private fun recordRecentRoom(code: String, mode: String, hasPassword: Boolean, password: String) {
        val updated = store.upsertRecentRoom(
            RecentRoom(
                code = code,
                mode = mode,
                hasPassword = hasPassword,
                password = password,
            )
        )
        _uiState.update { it.copy(recentRooms = updated) }
    }

    fun buildInviteUrl(code: String): String = api.buildInviteUrl(code)

    // 创建房间并自动进入（支持 allowRandom 允许陌生人随机串门 & 可选主题标签）
    fun createAndEnterRoom(
        mode: RoomMode,
        customCode: String,
        password: String,
        privatePairOnly: Boolean,
        retentionDays: Int,
        maxFileSizeMB: Int,
        saveFixedFavorite: Boolean,
        allowRandom: Boolean = true,
        topic: String = "",
    ) {
        if (_uiState.value.lobbyBusy) return
        _uiState.update { it.copy(lobbyBusy = true, lobbyError = null) }
        viewModelScope.launch {
            try {
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
                if (mode == RoomMode.FIXED && saveFixedFavorite) {
                    val saved = store.upsertSavedRoom(
                        SavedFixedRoom(
                            code = summary.code,
                            hasPassword = summary.hasPassword,
                            password = cleanPwd,
                        )
                    )
                    _uiState.update { it.copy(savedRooms = saved) }
                }
                recordRecentRoom(summary.code, summary.mode, summary.hasPassword, cleanPwd)
                enterRoomInternal(summary.code, cleanPwd, summary)
            } catch (e: Exception) {
                _uiState.update { it.copy(lobbyError = e.message ?: "创建房间失败") }
            } finally {
                _uiState.update { it.copy(lobbyBusy = false) }
            }
        }
    }

    // 刷新发现广场中的免密公开房间列表（silent=true 时后台静默更新不闪烁，用于 15 秒 SWR 策略）
    fun refreshPublicRooms(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(publicRoomsLoading = true) }
            }
            runCatching { api.fetchPublicRooms() }
                .onSuccess { resp ->
                    _uiState.update {
                        it.copy(
                            publicRooms = resp.rooms,
                            publicRoomsLoading = false,
                            publicRoomsLastRefreshedAt = System.currentTimeMillis(),
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(publicRoomsLoading = false) }
                }
        }
    }

    // 15 秒 SWR 静默刷新：有缓存立即展示不闪烁，距上次刷新超过 15 秒才在后台自动静默拉取
    fun refreshPublicRoomsIfStale(staleMs: Long = DISCOVER_SWR_STALE_MS) {
        val current = _uiState.value
        val elapsed = System.currentTimeMillis() - current.publicRoomsLastRefreshedAt
        if (current.publicRooms.isEmpty() || elapsed >= staleMs) {
            refreshPublicRooms(silent = current.publicRooms.isNotEmpty())
        }
    }

    // 下拉刷新「快传」首页：同步拉取服务器配置、活跃公开房数量与节流版 OTA 检查
    fun refreshLobbyOverview() {
        refreshServerConfig()
        refreshPublicRooms(silent = _uiState.value.publicRooms.isNotEmpty())
        checkForAppUpdate(manual = false, bypassThrottle = false)
    }

    // 开关清脆提示音（开启时试播一声轻柔提示音便于即时感知）
    fun setSoundEnabled(enabled: Boolean) {
        store.setSoundEnabled(enabled)
        _uiState.update { it.copy(prefs = it.prefs.copy(soundEnabled = enabled)) }
        if (enabled) {
            runCatching { soundPlayer.playRefreshTick() }
        }
    }

    // 开关细腻触感震动反馈
    fun setHapticEnabled(enabled: Boolean) {
        store.setHapticEnabled(enabled)
        _uiState.update { it.copy(prefs = it.prefs.copy(hapticEnabled = enabled)) }
    }

    // 供页面下拉刷新完成时安全触发极轻柔提示音
    fun playRefreshSound() {
        playSoundIfEnabled { playRefreshTick() }
    }

    private fun playSoundIfEnabled(action: BaySoundPlayer.() -> Unit) {
        if (!_uiState.value.prefs.soundEnabled) return
        runCatching { soundPlayer.action() }
    }

    // 随机进入一间免密公开房间（支持 excludeCurrent=true 实现房内「🎲 换一间」）
    fun joinRandomPublicRoom(excludeCurrent: Boolean = false) {
        if (_uiState.value.randomJoining || _uiState.value.lobbyBusy) return
        val exclude = if (excludeCurrent) _uiState.value.activeRoom?.roomCode.orEmpty() else ""
        _uiState.update { it.copy(randomJoining = true, lobbyError = null) }
        viewModelScope.launch {
            try {
                val target = api.pickRandomPublicRoom(exclude)
                if (target.hasPassword) {
                    showToast("已跳过加密房间")
                    return@launch
                }
                if (excludeCurrent && _uiState.value.activeRoom != null) {
                    signaling?.leaveRoom()
                    tearDownSession(stopService = false)
                }
                recordRecentRoom(target.code, target.mode, false, "")
                enterRoomInternal(target.code, "", target)
                val topicSuffix = if (target.topic.isNotBlank()) " · ${target.topic}" else ""
                showToast("🎲 已空降免密房间 #${target.code}$topicSuffix")
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

    // 扫描本地断点缓存 .part 与历史记录，驱动「传输中心」Tab
    fun refreshTransferCenter() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = WebRtcMeshManager.transferCacheDir(appContext)
            val metaMap = store.loadPartialMetaMap()
            val partials = dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".part") && it.length() > 0L }
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
                    )
                }
                ?.sortedByDescending { it.lastModified }
                .orEmpty()
            val history = store.loadTransferRecords()
            _uiState.update { it.copy(partialTransfers = partials, transferRecords = history) }
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

    fun removeTransferRecord(id: String) {
        val next = store.removeTransferRecord(id)
        _uiState.update { it.copy(transferRecords = next) }
    }

    fun clearTransferRecords() {
        val next = store.clearTransferRecords()
        _uiState.update { it.copy(transferRecords = next) }
        showToast("已清空传输记录列表（本地已保存文件不受影响）")
    }

    // 企业级 OTA 自动检查更新（对接 chat.baytale.com/apk/version.json，支持强制升级、本地秒命中、Wi-Fi 静默预下载与移动网络防偷跑）
    fun checkForAppUpdate(manual: Boolean = false, bypassThrottle: Boolean = false) {
        val currentOta = AppUpdateCenter.state.value
        if (currentOta.status == OtaUpdateStatus.CHECKING) return

        // 若正在下载或校验中，不打断现有任务；手动检查时展开进度弹窗供用户查看
        if (currentOta.status == OtaUpdateStatus.DOWNLOADING || currentOta.status == OtaUpdateStatus.VERIFYING) {
            if (manual) {
                _uiState.update { it.copy(showUpdateDialog = true) }
                showToast("新版本正在后台高速更新中...")
            }
            return
        }

        val now = System.currentTimeMillis()
        if (!manual && !bypassThrottle) {
            if (currentOta.status == OtaUpdateStatus.READY_TO_INSTALL) return
            if (now - _uiState.value.prefs.lastUpdateCheckAt < FOREGROUND_UPDATE_CHECK_INTERVAL_MS) return
        }

        // 记录本次检查时间戳，用于切回前台 10 分钟节流
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
                    // 网络失败时恢复之前已探测到的可用/暂停/就绪状态，不丢进度
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

        // 手动点击「检查更新」时自动解除对该版本的忽略标记，允许用户主动升级
        if (manual && currentPrefs.ignoredVersionCode == rel.versionCode) {
            store.setIgnoredVersionCode(0)
            currentPrefs = currentPrefs.copy(ignoredVersionCode = 0)
            _uiState.update { it.copy(prefs = currentPrefs) }
        }

        val isIgnored = !manual && !isForce && currentPrefs.ignoredVersionCode == rel.versionCode

        // 扫描本地 filesDir/updates/（同时自动垃圾回收非目标版本旧文件）
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
                if (manual) showToast("🎉 v${rel.versionName} 已在本地就绪，0 秒一键安装")
            }
            return
        }

        // 本地尚未完整就绪：写入 AVAILABLE（若存在 .part 断点则立即带上已下进度）
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

        if (isIgnored) {
            // 用户已忽略此版本：不自动弹窗，也不静默预下载
            return
        }

        // 未计费 Wi-Fi 且开启了自动后台预下载：自动拉起前台服务静默断点下载；移动网络绝不偷跑流量
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
            if (isForce || !started) {
                _uiState.update { it.copy(showUpdateDialog = true) }
            }
        } else {
            // 移动网络 / 关闭了 Wi-Fi 自动下载 / 用户手动检查：展示优雅更新提示弹窗由用户确认
            _uiState.update { it.copy(showUpdateDialog = true) }
            if (manual) {
                showToast("🚀 发现新版本 v${rel.versionName}")
            }
        }
    }

    // 启动或从断点恢复后台下载（若本地已校验就绪则直接一键安装）
    fun startOrResumeAppUpdate(silent: Boolean = false) {
        val ota = AppUpdateCenter.state.value
        val release = ota.release ?: return
        if (ota.status == OtaUpdateStatus.READY_TO_INSTALL && !ota.apkFilePath.isNullOrBlank()) {
            installReadyAppUpdate()
            return
        }
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
            showToast("启动后台更新服务失败，请稍后重试")
        }
    }

    // 暂停当前更新下载并完整保留 .part 断点文件
    fun pauseAppUpdate() {
        AppUpdateService.pauseDownload(appContext)
        showToast("已暂停更新，本地断点已安全保留")
    }

    // 一键拉起系统包安装器（通过 FileProvider 覆盖升级；兼容进程被回收后从完成通知冷启动安装）
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

    // 忽略当前版本：不再自动弹窗或静默下载，但手动「检查更新」仍可发现并升级
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
        if (AppUpdateCenter.state.value.isForceUpdate) return
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

    // 房内安全护栏：本地屏蔽该成员消息 / 记录举报反馈（满足应用商店 UGC 合规要求）
    fun blockPeer(peerId: String, peerName: String) {
        if (peerId.isBlank() || peerId == selfPeerId) return
        _uiState.update { it.copy(blockedPeerIds = it.blockedPeerIds + peerId) }
        showToast("已屏蔽「$peerName」，不再展示其后续消息")
    }

    fun reportPeer(peerName: String) {
        showToast("已提交对「$peerName」的违规反馈，建议屏蔽对方或点击「换一间」离开")
    }

    // 单入口智能直达台：留空秒开新房；输入口令/链接时「已存在直接加入，不存在则自动以此号创建并进入」
    fun enterOrCreateRoom(
        rawInput: String,
        mode: RoomMode,
        password: String,
        privatePairOnly: Boolean,
        retentionDays: Int,
        maxFileSizeMB: Int,
        saveFixedFavorite: Boolean,
        allowRandom: Boolean = true,
        topic: String = "",
    ) {
        val code = FormatUtils.extractRoomCode(rawInput)
        if (code.isBlank()) {
            // 留空直接秒开房间（按当前高级设置，默认临时闪传房）
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
                    // 房间已存在：直接验证并进入
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
                    if (existing.mode == RoomMode.FIXED.wireValue) {
                        val saved = store.upsertSavedRoom(
                            SavedFixedRoom(code = existing.code, hasPassword = existing.hasPassword, password = cleanPwd)
                        )
                        _uiState.update { it.copy(savedRooms = saved) }
                    }
                    recordRecentRoom(existing.code, existing.mode, existing.hasPassword, cleanPwd)
                    enterRoomInternal(existing.code, cleanPwd, existing)
                } else {
                    // 房间不存在：自动以此口令创建常驻房间并直接进入
                    val maxPeers = if (privatePairOnly) 2 else _uiState.value.serverConfig.maxRoomPeers.coerceAtLeast(2)
                    val summary = api.createRoom(
                        CreateRoomRequest(
                            mode = RoomMode.FIXED.wireValue,
                            code = code,
                            password = cleanPwd.takeIf { it.isNotBlank() },
                            allowRandom = if (cleanPwd.isBlank()) allowRandom else false,
                            topic = topic.trim().takeIf { it.isNotBlank() },
                            maxPeers = maxPeers,
                            retentionDays = retentionDays,
                            maxFileSizeMB = maxFileSizeMB,
                        )
                    )
                    if (saveFixedFavorite) {
                        val saved = store.upsertSavedRoom(
                            SavedFixedRoom(
                                code = summary.code,
                                hasPassword = summary.hasPassword,
                                password = cleanPwd,
                            )
                        )
                        _uiState.update { it.copy(savedRooms = saved) }
                    }
                    recordRecentRoom(summary.code, summary.mode, summary.hasPassword, cleanPwd)
                    enterRoomInternal(summary.code, cleanPwd, summary)
                    showToast("✨ 未找到 #$code，已为你自动创建专属房间并进入")
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
                                lobbyNeedPassword = true,
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
                recordRecentRoom(info.code, info.mode, info.hasPassword, cleanPwd)
                enterRoomInternal(info.code, cleanPwd, info)
            } catch (e: ApiException) {
                if (e.errorCode == "ROOM_NOT_FOUND" || e.errorCode == "ROOM_EXPIRED") {
                    removeRecentRoom(code)
                    showToast("房间 #$code 已销毁或过期，已从最近记录移除")
                }
                _uiState.update {
                    it.copy(
                        lobbyError = e.message,
                        lobbyNeedPassword = e.errorCode == "WRONG_PASSWORD" || it.lobbyNeedPassword,
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
                lobbyNeedPassword = false,
                pendingJoinCode = null,
                roomMinimized = false,
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
            title = "湾聊房间 #$code",
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
                    val inserted = appendMessageIfAbsent(
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
                    if (inserted && peerId !in _uiState.value.blockedPeerIds) {
                        playSoundIfEnabled { playMessageIn() }
                    }
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
                    // 提前登记大文件断点续传元数据，中断后可在「传输中心」清晰看到文件名和已接收进度
                    if (!info.isImage && !info.isAudio && info.fileSize > 0L) {
                        val partName = WebRtcMeshManager.resumePartFileName(info.fileName, info.fileSize)
                        store.upsertPartialMeta(
                            PartialFileMeta(
                                partFileName = partName,
                                fileName = info.fileName,
                                fileSize = info.fileSize,
                                roomCode = _uiState.value.activeRoom?.roomCode.orEmpty(),
                                peerName = sender?.name ?: "伙伴",
                            )
                        )
                    }
                    val inserted = appendMessageIfAbsent(
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
                    if (inserted && peerId !in _uiState.value.blockedPeerIds) {
                        playSoundIfEnabled { playMessageIn() }
                    }
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
                        val roomCode = _uiState.value.activeRoom?.roomCode.orEmpty()
                        val rec = TransferRecord(
                            id = "${info.transferId}_${info.direction.name}",
                            fileName = info.fileName,
                            fileSize = info.fileSize,
                            mimeType = info.mimeType,
                            direction = if (info.direction == TransferDirection.SEND) "send" else "receive",
                            roomCode = roomCode,
                            peerName = info.targetPeerName.orEmpty(),
                            savedDisplayPath = info.savedDisplayPath,
                            localPath = info.localPath,
                            verified = info.verified,
                        )
                        val updatedHistory = store.addTransferRecord(rec)
                        if (info.direction == TransferDirection.RECEIVE && !info.isImage && !info.isAudio) {
                            store.removePartialMeta(WebRtcMeshManager.resumePartFileName(info.fileName, info.fileSize))
                        }
                        _uiState.update { it.copy(transferRecords = updatedHistory) }
                        refreshTransferCenter()

                        if (!info.isImage && !info.isAudio) {
                            playSoundIfEnabled { playTransferDone() }
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
                        refreshTransferCenter()
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

        // 避免弱网 WebSocket 自动重连时重复播报进房提示音（使用原子标记保证线程安全）
        val joinSoundPlayed = AtomicBoolean(false)
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
                    if (joinSoundPlayed.compareAndSet(false, true)) {
                        playSoundIfEnabled { playRoomJoin() }
                    }
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
                    env.maxFileSizeMB?.let { mb ->
                        updateRoom { r -> r.copy(roomInfo = r.roomInfo?.copy(maxFileSizeMB = mb)) }
                        appendSystemMessage("📦 ${env.actorName ?: "伙伴"} 将附件上限调为：${FormatUtils.formatLimitMB(mb)}")
                    }
                    env.allowRandom?.let { allow ->
                        updateRoom { r -> r.copy(roomInfo = r.roomInfo?.copy(allowRandom = allow)) }
                        val stateDesc = if (allow) "已开启（允许陌生人随机串门）" else "已关闭（仅凭口令进入）"
                        appendSystemMessage("🌐 ${env.actorName ?: "伙伴"} 修改随机串门状态：$stateDesc")
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
            title = "湾聊房间 #${room.roomCode}",
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

                val retention = room.historyMeta.retentionDays
                val maxCacheFileBytes = _uiState.value.serverConfig.historyMaxFileBytes
                    .takeIf { it > 0L } ?: (100L * 1024L * 1024L)
                val canCloudCache = retention > 0 && meta.fileSize in 1L..maxCacheFileBytes

                if (connectedPeers == 0 && !canCloudCache) {
                    showToast(
                        if (retention <= 0) "当前为阅后即焚模式，大文件直传需等伙伴进房后发送"
                        else "超过 ${FormatUtils.formatBytes(maxCacheFileBytes)} 的超大文件仅走 P2P 直传，请等伙伴进房后发送"
                    )
                    continue
                }

                val ts = System.currentTimeMillis()
                val idPrefix = "file_${ts}_${(1000..9999).random()}"

                if (connectedPeers > 0) {
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

                    if (canCloudCache) {
                        viewModelScope.launch(Dispatchers.IO) {
                            val uploaded = runCatching {
                                api.uploadCachedFile(
                                    code = room.roomCode,
                                    password = room.password,
                                    context = appContext,
                                    uri = uri,
                                    fileName = meta.fileName,
                                    fileSize = meta.fileSize,
                                    mimeType = meta.mimeType,
                                )
                            }.getOrNull()
                            if (uploaded != null) {
                                val dlUrl = api.buildCachedFileUrl(room.roomCode, uploaded.fileId, room.password)
                                updateRoom { r ->
                                    r.copy(
                                        messages = r.messages.map { m ->
                                            if (m.kind == ChatKind.FILE && m.transfer?.transferId?.startsWith("${idPrefix}_") == true) {
                                                m.copy(transfer = m.transfer.copy(cachedDownloadUrl = dlUrl))
                                            } else m
                                        }
                                    )
                                }
                                signaling?.cacheMessage(
                                    CachedMessage(
                                        id = "fnotice_$idPrefix",
                                        kind = "file_notice",
                                        senderId = selfPeerId,
                                        senderName = profile.name,
                                        senderAvatar = profile.avatar,
                                        mediaId = uploaded.fileId,
                                        mediaMime = uploaded.mime,
                                        fileName = meta.fileName,
                                        fileSize = meta.fileSize,
                                        timestamp = ts,
                                    )
                                )
                            } else {
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
                    } else if (retention > 0) {
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
                } else if (canCloudCache) {
                    // 无人在线时（如固定秘密基地跨端留存），≤100MB 附件直接上传到房间服务器限时缓存
                    val noticeId = "fnotice_$idPrefix"
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
                            ),
                        )
                    )
                    runCatching {
                        api.uploadCachedFile(
                            code = room.roomCode,
                            password = room.password,
                            context = appContext,
                            uri = uri,
                            fileName = meta.fileName,
                            fileSize = meta.fileSize,
                            mimeType = meta.mimeType,
                        )
                    }.onSuccess { uploaded ->
                        val dlUrl = api.buildCachedFileUrl(room.roomCode, uploaded.fileId, room.password)
                        updateRoom { r ->
                            r.copy(
                                messages = r.messages.map { m ->
                                    if (m.id == noticeId && m.transfer != null) {
                                        m.copy(
                                            mediaId = uploaded.fileId,
                                            transfer = m.transfer.copy(
                                                status = TransferStatus.COMPLETED,
                                                transferredBytes = meta.fileSize,
                                                cachedDownloadUrl = dlUrl,
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
                                fileName = meta.fileName,
                                fileSize = meta.fileSize,
                                timestamp = ts,
                            )
                        )
                        signaling?.recordActivity("file", meta.fileSize)
                        val rec = TransferRecord(
                            id = "${noticeId}_SEND",
                            fileName = meta.fileName,
                            fileSize = meta.fileSize,
                            mimeType = meta.mimeType,
                            direction = "send",
                            roomCode = room.roomCode,
                            savedDisplayPath = "房间云端限时缓存",
                        )
                        _uiState.update { it.copy(transferRecords = store.addTransferRecord(rec)) }
                        playSoundIfEnabled { playTransferDone() }
                        showToast("☁️「${meta.fileName}」已上传至房间缓存，刷新或换设备随时可下")
                    }.onFailure { err ->
                        updateRoom { r ->
                            r.copy(
                                messages = r.messages.map { m ->
                                    if (m.id == noticeId && m.transfer != null) {
                                        m.copy(
                                            transfer = m.transfer.copy(
                                                status = TransferStatus.ERROR,
                                                errorMessage = err.message ?: "上传失败",
                                            )
                                        )
                                    } else m
                                }
                            )
                        }
                    }
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

    fun acceptFile(peerId: String, transferId: String, resumeIfAvailable: Boolean = true) {
        val ok = mesh?.acceptIncomingTransfer(peerId, transferId, resumeIfAvailable) == true
        if (!ok) {
            showToast("⚠️ 发送方已离线或传输已失效，请让对方重新发送")
        }
    }

    fun rejectFile(peerId: String, transferId: String) {
        mesh?.rejectIncomingTransfer(peerId, transferId)
    }

    fun cancelTransfer(peerId: String, transferId: String) {
        mesh?.cancelTransfer(peerId, transferId)
    }

    // 从服务器限时缓存流式下载历史附件到本地 Download/BayChat/ 并实时刷新进度
    fun downloadCachedAttachment(
        itemId: String,
        url: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
    ) {
        val room = _uiState.value.activeRoom ?: return
        viewModelScope.launch {
            val startAt = System.currentTimeMillis()
            updateRoom { r ->
                r.copy(
                    messages = r.messages.map { m ->
                        if (m.id == itemId && m.transfer != null) {
                            m.copy(
                                transfer = m.transfer.copy(
                                    status = TransferStatus.TRANSFERRING,
                                    transferredBytes = 0L,
                                    speedBps = 0.0,
                                    errorMessage = null,
                                )
                            )
                        } else m
                    }
                )
            }
            val tmpDir = File(appContext.cacheDir, "cached_downloads").apply { mkdirs() }
            val tmpFile = File(tmpDir, "dl_${System.currentTimeMillis()}.tmp")
            runCatching {
                api.downloadCachedFileToTemp(url, tmpFile) { done, total ->
                    val realTotal = if (total > 0L) total else fileSize
                    val elapsedSec = ((System.currentTimeMillis() - startAt) / 1000.0).coerceAtLeast(0.1)
                    val speed = done / elapsedSec
                    val eta = if (speed > 0 && realTotal > done) (realTotal - done) / speed else 0.0
                    updateRoom { r ->
                        r.copy(
                            messages = r.messages.map { m ->
                                if (m.id == itemId && m.transfer != null) {
                                    m.copy(
                                        transfer = m.transfer.copy(
                                            status = TransferStatus.TRANSFERRING,
                                            fileSize = if (realTotal > 0L) realTotal else m.transfer.fileSize,
                                            transferredBytes = done,
                                            speedBps = speed,
                                            etaSeconds = eta,
                                        )
                                    )
                                } else m
                            }
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
                updateRoom { r ->
                    r.copy(
                        messages = r.messages.map { m ->
                            if (m.id == itemId && m.transfer != null) {
                                m.copy(
                                    localFilePath = saved.previewFilePath,
                                    transfer = m.transfer.copy(
                                        status = TransferStatus.COMPLETED,
                                        transferredBytes = m.transfer.fileSize,
                                        etaSeconds = 0.0,
                                        localPath = saved.previewFilePath,
                                        savedDisplayPath = saved.displayPath,
                                    )
                                )
                            } else m
                        }
                    )
                }
                val rec = TransferRecord(
                    id = "${itemId}_CLOUD_DL",
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = mimeType,
                    direction = "receive",
                    roomCode = room.roomCode,
                    savedDisplayPath = saved.displayPath,
                    localPath = saved.previewFilePath,
                )
                _uiState.update { it.copy(transferRecords = store.addTransferRecord(rec)) }
                playSoundIfEnabled { playTransferDone() }
                showToast("✅ 已下载历史附件至 ${saved.displayPath}")
            }.onFailure { err ->
                runCatching { tmpFile.delete() }
                updateRoom { r ->
                    r.copy(
                        messages = r.messages.map { m ->
                            if (m.id == itemId && m.transfer != null) {
                                m.copy(
                                    transfer = m.transfer.copy(
                                        status = TransferStatus.COMPLETED,
                                        errorMessage = err.message,
                                    )
                                )
                            } else m
                        }
                    )
                }
                showToast(err.message ?: "历史附件下载失败")
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

    fun updateAllowRandom(allowRandom: Boolean) {
        val room = _uiState.value.activeRoom ?: return
        if (room.password.isNotBlank() || room.roomInfo?.hasPassword == true) {
            showToast("加密房间禁止开启随机串门")
            return
        }
        signaling?.updateAllowRandom(allowRandom)
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
                removeRecentRoom(room.roomCode)
                recordRecentRoom(next.code, next.mode, next.hasPassword, room.password)
                enterRoomInternal(next.code, room.password, next)
                showToast("已切换全新房间口令：${next.code}")
            }.onFailure {
                showToast(it.message ?: "换码失败")
            }
        }
    }

    fun minimizeRoom() {
        if (_uiState.value.activeRoom != null) {
            _uiState.update { it.copy(roomMinimized = true) }
            showToast("房间已在后台保持连接与传输")
        }
    }

    fun restoreRoom() {
        if (_uiState.value.activeRoom != null) {
            _uiState.update { it.copy(roomMinimized = false) }
        }
    }

    fun switchToRecentRoom(item: RecentRoom) {
        signaling?.leaveRoom()
        tearDownSession(stopService = false)
        _uiState.update { it.copy(activeRoom = null, roomMinimized = false) }
        joinRoom(item.code, item.password)
    }

    fun leaveRoom() {
        signaling?.leaveRoom()
        tearDownSession(stopService = true)
        _uiState.update { it.copy(activeRoom = null, roomMinimized = false) }
    }

    fun destroyRoom() {
        val curCode = _uiState.value.activeRoom?.roomCode
        signaling?.destroyRoom()
        tearDownSession(stopService = true)
        if (!curCode.isNullOrBlank()) removeRecentRoom(curCode)
        _uiState.update { it.copy(activeRoom = null, roomMinimized = false) }
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

            "file_notice" -> {
                val cachedUrl = cm.mediaId?.takeIf { it.isNotBlank() }
                    ?.let { api.buildCachedFileUrl(room.roomCode, it, room.password) }
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
                    return
                }
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
        val inserted = appendMessageIfAbsent(item)
        if (inserted && !fromCache && !isSelf && cm.senderId !in _uiState.value.blockedPeerIds) {
            playSoundIfEnabled { playMessageIn() }
        }
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
        runCatching { soundPlayer.release() }
        super.onCleared()
    }

    companion object {
        // 切回前台自动静默复查更新的最小时间间隔（10 分钟）
        private const val FOREGROUND_UPDATE_CHECK_INTERVAL_MS = 10L * 60L * 1000L
        // 发现页 15 秒 SWR 静默刷新过期阈值
        private const val DISCOVER_SWR_STALE_MS = 15_000L
    }
}
