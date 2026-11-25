package com.baytale.baychat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.baytale.baychat.service.AppUpdateService
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.PortraitCaptureActivity
import com.baytale.baychat.ui.components.FloatingRoomOrb
import com.baytale.baychat.ui.screens.ChatRoomScreen
import com.baytale.baychat.ui.screens.LobbyScreen
import com.baytale.baychat.ui.screens.PrivacyConsentDialog
import com.baytale.baychat.ui.screens.UpdateAvailableDialog
import com.baytale.baychat.ui.theme.BayChatTheme
import com.baytale.baychat.ui.theme.LocalBayColors
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// 湾聊（BayChat）主 Activity：处理沉浸式深浅状态栏自适应、深链进房、系统分享、原生扫码、磁吸悬浮圆球与隐私合规门禁
class MainActivity : ComponentActivity() {
    private val viewModel: BayChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 默认开启浅色背景深色图标状态栏，配合 BayChatTheme 动态切换，彻底避免澎湃 OS 状态栏白字隐身
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        // B10：仅冷启动 (savedInstanceState == null) 时解析启动 Intent，并在解析后立即清空，防止旋转/重建重放
        if (savedInstanceState == null) {
            parseIncomingIntent(intent)
            setIntent(Intent())
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            var showLeaveConfirm by remember { mutableStateOf(false) }

            val notificationPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                viewModel.markNotificationAsked()
                if (!granted) {
                    viewModel.showToast("已跳过通知权限，如需后台来件提醒可在系统设置中开启")
                }
            }

            val qrScanLauncher = rememberLauncherForActivityResult(
                contract = ScanContract()
            ) { result ->
                val scanned = result.contents
                if (!scanned.isNullOrBlank()) {
                    viewModel.joinRoom(scanned, "")
                }
            }

            fun launchQrScanner() {
                val opts = ScanOptions().apply {
                    setCaptureActivity(PortraitCaptureActivity::class.java)
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("对准电脑或好友手机上的湾聊房间二维码")
                    setBeepEnabled(true)
                    setOrientationLocked(true)
                    setBarcodeImageEnabled(false)
                }
                qrScanLauncher.launch(opts)
            }

            // B24：仅在尚未询问过通知权限时按需引导一次，避免每次切换房间重复弹窗
            LaunchedEffect(uiState.activeRoom?.roomCode, uiState.otaState.status, uiState.prefs.notificationAsked) {
                val needNotify = uiState.activeRoom != null ||
                    (uiState.otaState.status == OtaUpdateStatus.DOWNLOADING && !uiState.otaState.isSilentBackground)
                if (needNotify && !uiState.prefs.notificationAsked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.markNotificationAsked()
                    }
                }
            }

            // Phase 1 安全适配：桌面快捷方式触发扫码时直接呼起原生扫码器（QUICK_SEND / PICKUP 留待 Phase 2 UI 路由）
            LaunchedEffect(uiState.pendingAppAction, uiState.privacyAccepted) {
                if (uiState.privacyAccepted && uiState.pendingAppAction == com.baytale.baychat.ui.AppShortcutAction.SCAN) {
                    viewModel.consumePendingAppAction()
                    launchQrScanner()
                }
            }

            LaunchedEffect(uiState.toastMessage) {
                val msg = uiState.toastMessage
                if (!msg.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(msg)
                    viewModel.clearToast()
                }
            }

            BayChatTheme(accent = uiState.themeMode, appearance = uiState.prefs.appearance) {
                val colors = LocalBayColors.current

                BackHandler(enabled = uiState.activeRoom != null && !uiState.roomMinimized) {
                    showLeaveConfirm = true
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    val activeRoom = uiState.activeRoom
                    if (activeRoom == null || uiState.roomMinimized) {
                        LobbyScreen(
                            state = uiState,
                            onEnterOrCreateRoom = { rawInput, mode, pwd, pairOnly, days, mb, saveFav, allowRandom, topic ->
                                viewModel.enterOrCreateRoom(
                                    rawInput = rawInput,
                                    mode = mode,
                                    password = pwd,
                                    privatePairOnly = pairOnly,
                                    retentionDays = days,
                                    maxFileSizeMB = mb,
                                    saveFixedFavorite = saveFav,
                                    allowRandom = allowRandom,
                                    topic = topic,
                                )
                            },
                            onCreateRoom = { mode, customCode, pwd, pairOnly, days, mb, saveFav, allowRandom, topic ->
                                viewModel.createAndEnterRoom(
                                    mode = mode,
                                    customCode = customCode,
                                    password = pwd,
                                    privatePairOnly = pairOnly,
                                    retentionDays = days,
                                    maxFileSizeMB = mb,
                                    saveFixedFavorite = saveFav,
                                    allowRandom = allowRandom,
                                    topic = topic,
                                )
                            },
                            onJoinRoom = { raw, pwd -> viewModel.joinRoom(raw, pwd) },
                            onRandomJoinRoom = { viewModel.joinRandomPublicRoom(excludeCurrent = false) },
                            onRefreshLobby = { viewModel.refreshLobbyOverview() },
                            onRefreshPublicRooms = { viewModel.refreshPublicRooms(silent = false) },
                            onRefreshPublicRoomsIfStale = { viewModel.refreshPublicRoomsIfStale() },
                            onRequestQrScan = { launchQrScanner() },
                            onRemoveSavedRoom = { viewModel.removeSavedRoom(it) },
                            onRemoveRecentRoom = { viewModel.removeRecentRoom(it) },
                            onClearRecentRooms = { viewModel.clearRecentRooms() },
                            onUpdateProfile = { name, avatar -> viewModel.updateProfile(name, avatar) },
                            onSelectTheme = { viewModel.selectTheme(it) },
                            onUpdatePrefs = { next -> viewModel.updatePrefs(next) },
                            onToggleSound = { enabled -> viewModel.setSoundEnabled(enabled) },
                            onToggleHaptic = { enabled -> viewModel.setHapticEnabled(enabled) },
                            onPlayRefreshSound = { viewModel.playRefreshSound() },
                            onUpdateServerUrl = { url -> viewModel.updatePrefs(uiState.prefs.copy(serverUrl = url)) },
                            onClearPendingShare = { viewModel.clearPendingShare() },
                            onCopyInviteUrl = { code ->
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("湾聊 BayChat", viewModel.buildInviteUrl(code)))
                                viewModel.showToast("已复制 #$code 邀请链接")
                            },
                            onRefreshTransfers = { viewModel.refreshTransferCenter() },
                            onDeletePartialTransfer = { viewModel.deletePartialTransfer(it) },
                            onClearAllPartialTransfers = { viewModel.clearAllPartialTransfers() },
                            onRemoveTransferRecord = { viewModel.removeTransferRecord(it) },
                            onClearTransferRecords = { viewModel.clearTransferRecords() },
                            onCheckAppUpdate = { viewModel.checkForAppUpdate(manual = true) },
                            onStartOrResumeAppUpdate = { viewModel.startOrResumeAppUpdate(silent = false) },
                            onPauseAppUpdate = { viewModel.pauseAppUpdate() },
                            onInstallReadyAppUpdate = { viewModel.installReadyAppUpdate() },
                            onIgnoreAppUpdate = { viewModel.ignoreCurrentUpdate() },
                            onOpenUpdateDialog = { viewModel.openUpdateDialog() },
                            onToggleAutoDownloadOnWifi = { enabled -> viewModel.setAutoDownloadOnWifi(enabled) },
                            onToast = { viewModel.showToast(it) },
                            onClearError = { viewModel.clearLobbyError() },
                            onConsumePendingJoin = { viewModel.consumePendingJoinCode() },
                        )

                        // 边缘可拖拽磁吸悬浮进度圆球：切回大厅浏览时保持 P2P 在线与大文件后台直传，不遮挡列表正文
                        if (activeRoom != null && uiState.roomMinimized) {
                            FloatingRoomOrb(
                                room = activeRoom,
                                hapticEnabled = uiState.prefs.hapticEnabled,
                                onRestoreRoom = { viewModel.restoreRoom() },
                                onRequestLeaveRoom = { showLeaveConfirm = true },
                            )
                        }
                    } else {
                        ChatRoomScreen(
                            state = uiState,
                            room = activeRoom,
                            inviteUrl = viewModel.buildInviteUrl(activeRoom.roomCode),
                            effectiveMaxFileMB = viewModel.effectiveMaxFileMB(),
                            onSendText = { viewModel.sendTextMessage(it) },
                            onSendUris = { viewModel.sendUris(it) },
                            onSendPendingShare = { viewModel.sendPendingShareNow() },
                            onClearPendingShare = { viewModel.clearPendingShare() },
                            onAcceptFile = { pid, tid, resume -> viewModel.acceptFile(pid, tid, resume) },
                            onRejectFile = { pid, tid -> viewModel.rejectFile(pid, tid) },
                            onCancelTransfer = { pid, tid -> viewModel.cancelTransfer(pid, tid) },
                            onDownloadCachedFile = { itemId, url, name, size, mime ->
                                viewModel.downloadCachedAttachment(itemId, url, name, size, mime)
                            },
                            onReconnectPeer = { pid -> viewModel.reconnectPeer(pid) },
                            onReplyMessage = { item -> viewModel.setReplyingTo(item) },
                            onRecallMessage = { id -> viewModel.recallMessage(id) },
                            onReactMessage = { id, emoji -> viewModel.reactMessage(id, emoji) },
                            onSearchFilterChange = { kw, kind -> viewModel.setSearchFilter(kw, kind) },
                            onUpdateRetention = { days -> viewModel.updateRetentionDays(days) },
                            onUpdateMaxFileMB = { mb -> viewModel.updateRoomMaxFileMB(mb) },
                            onUpdateAllowRandom = { allow -> viewModel.updateAllowRandom(allow) },
                            onUpdatePrefs = { next -> viewModel.updatePrefs(next) },
                            onSelectTheme = { mode -> viewModel.selectTheme(mode) },
                            onExportLog = { viewModel.exportChatLog() },
                            onClearHistory = { viewModel.clearServerHistory() },
                            onRotateCode = { viewModel.rotateEphemeralCode() },
                            onRandomSwitchRoom = { viewModel.joinRandomPublicRoom(excludeCurrent = true) },
                            onBlockPeer = { pid, name -> viewModel.blockPeer(pid, name) },
                            onReportPeer = { name -> viewModel.reportPeer(name) },
                            onSwitchRecentRoom = { rec -> viewModel.switchToRecentRoom(rec) },
                            onMinimizeRoom = { viewModel.minimizeRoom() },
                            onLeaveRoom = { viewModel.leaveRoom() },
                            onDestroyRoom = { viewModel.destroyRoom() },
                            onToast = { viewModel.showToast(it) },
                        )
                    }

                    // 顶部轻提示条
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 58.dp, start = 16.dp, end = 16.dp),
                    ) { data ->
                        Snackbar(
                            snackbarData = data,
                            shape = RoundedCornerShape(16.dp),
                            containerColor = colors.accent,
                            contentColor = Color.White,
                        )
                    }

                    // 首次启动合规隐私同意弹窗
                    if (!uiState.privacyAccepted) {
                        PrivacyConsentDialog(
                            onAccept = { viewModel.acceptPrivacyPolicy() },
                            onDecline = { finish() },
                        )
                    }

                    // 企业级 OTA 更新详情/断点续传进度/一键安装弹窗
                    val canShowUpdateModal = uiState.privacyAccepted &&
                        uiState.showUpdateDialog &&
                        uiState.otaState.release != null &&
                        (uiState.activeRoom == null || uiState.roomMinimized || uiState.otaState.isForceUpdate)
                    if (canShowUpdateModal) {
                        UpdateAvailableDialog(
                            ota = uiState.otaState,
                            onStartOrResume = { viewModel.startOrResumeAppUpdate(silent = false) },
                            onPause = { viewModel.pauseAppUpdate() },
                            onInstall = { viewModel.installReadyAppUpdate() },
                            onIgnore = { viewModel.ignoreCurrentUpdate() },
                            onDismiss = { viewModel.dismissUpdateDialog() },
                        )
                    }

                    // 安全离开确认弹窗：智能适配「房间内按返回」与「大厅悬浮球拖入退出/长按」两种场景
                    if (showLeaveConfirm && uiState.activeRoom != null) {
                        val isMinimized = uiState.roomMinimized
                        val roomCode = uiState.activeRoom?.roomCode.orEmpty()
                        AlertDialog(
                            onDismissRequest = { showLeaveConfirm = false },
                            containerColor = colors.surface,
                            shape = RoundedCornerShape(22.dp),
                            title = {
                                Text(
                                    text = if (isMinimized) "确认退出后台房间 #$roomCode？" else "要收起房间还是彻底退出？",
                                    fontWeight = FontWeight.Bold,
                                    color = colors.textMain,
                                )
                            },
                            text = {
                                Text(
                                    text = if (isMinimized) {
                                        "退出后将立即断开当前 P2P 连接并终止后台进行中的文件传输。若仍需保持传输，可选择继续挂起。"
                                    } else {
                                        "选择「最小化挂起」可退回大厅同时保持 P2P 连接与大文件后台传输；选择「彻底断开」将结束连接。"
                                    },
                                    color = colors.textSub,
                                )
                            },
                            confirmButton = {
                                if (isMinimized) {
                                    Button(
                                        onClick = {
                                            showLeaveConfirm = false
                                            viewModel.leaveRoom()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                                    ) { Text("彻底断开退出") }
                                } else {
                                    Button(
                                        onClick = {
                                            showLeaveConfirm = false
                                            viewModel.minimizeRoom()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    ) { Text("最小化挂起") }
                                }
                            },
                            dismissButton = {
                                if (isMinimized) {
                                    TextButton(onClick = { showLeaveConfirm = false }) {
                                        Text("继续后台挂起", color = colors.accent, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    TextButton(onClick = {
                                        showLeaveConfirm = false
                                        viewModel.leaveRoom()
                                    }) { Text("彻底断开离开", color = colors.danger) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 切回前台时执行 10 分钟节流复查，并在「允许安装未知应用」授权返回后自动续拉系统安装器
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            viewModel.onAppBackgrounded()
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseIncomingIntent(intent)
        // B10：解析完毕后清空 Intent，防止配置变更或重建时重放深链/分享
        setIntent(Intent())
    }

    private fun parseIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            AppUpdateService.ACTION_OPEN_OTA_INSTALL -> {
                viewModel.installReadyAppUpdate()
            }

            Intent.ACTION_VIEW -> {
                intent.dataString?.let { viewModel.handleIncomingDeepLink(it) }
            }

            Intent.ACTION_SEND -> {
                val streamUri = getParcelableExtraCompat(intent, Intent.EXTRA_STREAM)
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                viewModel.handleExternalShare(
                    uris = listOfNotNull(streamUri),
                    text = sharedText,
                )
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = getParcelableArrayListCompat(intent, Intent.EXTRA_STREAM)
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                viewModel.handleExternalShare(uris = uris, text = sharedText)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getParcelableExtraCompat(intent: Intent, key: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java)
        } else {
            intent.getParcelableExtra(key)
        }
    }

    @Suppress("DEPRECATION")
    private fun getParcelableArrayListCompat(intent: Intent, key: String): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(key).orEmpty()
        }
    }
}
