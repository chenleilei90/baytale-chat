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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.PortraitCaptureActivity
import com.baytale.baychat.ui.screens.ChatRoomScreen
import com.baytale.baychat.ui.screens.LobbyScreen
import com.baytale.baychat.ui.screens.PrivacyConsentDialog
import com.baytale.baychat.ui.theme.BayChatTheme
import com.baytale.baychat.ui.theme.LocalBayColors
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// 湾聊（BayChat）主 Activity：处理沉浸式深浅状态栏自适应、深链进房、系统分享、原生扫码与隐私合规门禁
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
        parseIncomingIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            var showLeaveConfirm by remember { mutableStateOf(false) }

            val notificationPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { /* 拒绝也不阻断应用使用 */ }

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

            LaunchedEffect(uiState.activeRoom?.roomCode) {
                if (uiState.activeRoom != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            LaunchedEffect(uiState.toastMessage) {
                val msg = uiState.toastMessage
                if (!msg.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(msg)
                    viewModel.clearToast()
                }
            }

            BayChatTheme(themeMode = uiState.themeMode) {
                val colors = LocalBayColors.current

                BackHandler(enabled = uiState.activeRoom != null && !uiState.roomMinimized) {
                    showLeaveConfirm = true
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    val activeRoom = uiState.activeRoom
                    if (activeRoom == null || uiState.roomMinimized) {
                        LobbyScreen(
                            state = uiState,
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
                            onRefreshPublicRooms = { viewModel.refreshPublicRooms() },
                            onRequestQrScan = { launchQrScanner() },
                            onRemoveSavedRoom = { viewModel.removeSavedRoom(it) },
                            onRemoveRecentRoom = { viewModel.removeRecentRoom(it) },
                            onClearRecentRooms = { viewModel.clearRecentRooms() },
                            onUpdateProfile = { name, avatar -> viewModel.updateProfile(name, avatar) },
                            onSelectTheme = { viewModel.selectTheme(it) },
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
                            onDownloadAppUpdate = { viewModel.downloadAndInstallUpdate() },
                            onToast = { viewModel.showToast(it) },
                            onClearError = { viewModel.clearLobbyError() },
                            onConsumePendingJoin = { viewModel.consumePendingJoinCode() },
                        )

                        // 最小化后台挂起浮窗胶囊：位于底部三 Tab 导航栏上方，切出来逛发现广场/传输中心时传输不断线
                        if (activeRoom != null && uiState.roomMinimized) {
                            val activeTransfer = activeRoom.messages.lastOrNull {
                                it.transfer?.status == TransferStatus.TRANSFERRING
                            }?.transfer
                            val progressFraction = if (activeTransfer != null && activeTransfer.fileSize > 0) {
                                (activeTransfer.transferredBytes.toFloat() / activeTransfer.fileSize.toFloat()).coerceIn(0.02f, 1f)
                            } else null
                            val statusLine = when {
                                activeTransfer != null && activeTransfer.fileSize > 0 -> {
                                    val pct = ((activeTransfer.transferredBytes * 100.0) / activeTransfer.fileSize).coerceIn(0.0, 100.0)
                                    String.format(java.util.Locale.US, "后台直传 %.1f%% · %s", pct, activeTransfer.fileName)
                                }
                                activeRoom.peers.isNotEmpty() -> "后台在线 · ${activeRoom.peers.size + 1} 人已连线"
                                else -> "后台挂起等待伙伴加入..."
                            }

                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.surface),
                                border = BorderStroke(1.5.dp, colors.accent.copy(alpha = 0.55f)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .padding(start = 14.dp, end = 14.dp, bottom = 86.dp)
                                    .fillMaxWidth()
                                    .clickable { viewModel.restoreRoom() },
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(9.dp)
                                                .clip(CircleShape)
                                                .background(colors.emerald),
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "房间 #${activeRoom.roomCode} 后台运行中",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 13.sp,
                                                color = colors.textMain,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = statusLine,
                                                fontSize = 11.5.sp,
                                                color = colors.textSub,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Button(
                                            onClick = { viewModel.restoreRoom() },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            modifier = Modifier.height(30.dp),
                                            shape = RoundedCornerShape(9.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                        ) {
                                            Text("回房", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        IconButton(
                                            onClick = { viewModel.leaveRoom() },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(Icons.Rounded.Close, contentDescription = "断开房间", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    if (progressFraction != null) {
                                        LinearProgressIndicator(
                                            progress = { progressFraction },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(99.dp)),
                                            color = colors.accent,
                                            trackColor = colors.accentSoft,
                                        )
                                    }
                                }
                            }
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

                    if (showLeaveConfirm) {
                        AlertDialog(
                            onDismissRequest = { showLeaveConfirm = false },
                            title = { Text("要收起房间还是彻底退出？", fontWeight = FontWeight.Bold) },
                            text = { Text("选择「最小化挂起」可退回大厅同时保持 P2P 连接与大文件后台传输；选择「彻底断开」将结束连接。") },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showLeaveConfirm = false
                                        viewModel.minimizeRoom()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                ) { Text("最小化挂起") }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showLeaveConfirm = false
                                    viewModel.leaveRoom()
                                }) { Text("彻底断开离开", color = colors.danger) }
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIncomingIntent(intent)
    }

    private fun parseIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
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
