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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.PortraitCaptureActivity
import com.baytale.baychat.ui.screens.ChatRoomScreen
import com.baytale.baychat.ui.screens.LobbyScreen
import com.baytale.baychat.ui.screens.PrivacyConsentDialog
import com.baytale.baychat.ui.theme.BayChatTheme
import com.baytale.baychat.ui.theme.LocalBayColors
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// BayChat 主 Activity：处理沉浸式界面、深链进房、系统「分享到 BayChat」、原生扫码与隐私合规门禁
class MainActivity : ComponentActivity() {
    private val viewModel: BayChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        parseIncomingIntent(intent)

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            var showLeaveConfirm by remember { mutableStateOf(false) }

            // Android 13+ 仅在进房时请求通知权限，用于前台保活显示传输进度
            val notificationPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { /* 拒绝也不阻断应用使用 */ }

            // 竖屏原生二维码扫描
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
                    setPrompt("对准电脑或好友手机上的 BayChat 房间二维码")
                    setBeepEnabled(true)
                    setOrientationLocked(true)
                    setBarcodeImageEnabled(false)
                }
                qrScanLauncher.launch(opts)
            }

            // 首次进入房间时请求通知权限（仅 Android 13+ 且尚未授权时）
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

            // 统一全局轻提示展示
            LaunchedEffect(uiState.toastMessage) {
                val msg = uiState.toastMessage
                if (!msg.isNullOrBlank()) {
                    snackbarHostState.showSnackbar(msg)
                    viewModel.clearToast()
                }
            }

            BayChatTheme(themeMode = uiState.themeMode) {
                val colors = LocalBayColors.current

                // 在房间内按系统返回键时温和确认，避免大文件传输中途误退
                BackHandler(enabled = uiState.activeRoom != null) {
                    showLeaveConfirm = true
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    val activeRoom = uiState.activeRoom
                    if (activeRoom == null) {
                        LobbyScreen(
                            state = uiState,
                            onCreateRoom = { mode, customCode, pwd, pairOnly, days, mb, saveFav ->
                                viewModel.createAndEnterRoom(mode, customCode, pwd, pairOnly, days, mb, saveFav)
                            },
                            onJoinRoom = { raw, pwd -> viewModel.joinRoom(raw, pwd) },
                            onRequestQrScan = { launchQrScanner() },
                            onRemoveSavedRoom = { viewModel.removeSavedRoom(it) },
                            onUpdateProfile = { name, avatar -> viewModel.updateProfile(name, avatar) },
                            onRandomizeProfile = { viewModel.randomizeProfile() },
                            onSelectTheme = { viewModel.selectTheme(it) },
                            onUpdateServerUrl = { url -> viewModel.updatePrefs(uiState.prefs.copy(serverUrl = url)) },
                            onClearPendingShare = { viewModel.clearPendingShare() },
                            onCopyInviteUrl = { code ->
                                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("BayChat", viewModel.buildInviteUrl(code)))
                                viewModel.showToast("已复制 #$code 邀请链接")
                            },
                            onClearError = { viewModel.clearLobbyError() },
                            onConsumePendingJoin = { viewModel.consumePendingJoinCode() },
                        )
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
                            onAcceptFile = { pid, tid -> viewModel.acceptFile(pid, tid) },
                            onRejectFile = { pid, tid -> viewModel.rejectFile(pid, tid) },
                            onCancelTransfer = { pid, tid -> viewModel.cancelTransfer(pid, tid) },
                            onReconnectPeer = { pid -> viewModel.reconnectPeer(pid) },
                            onReplyMessage = { item -> viewModel.setReplyingTo(item) },
                            onRecallMessage = { id -> viewModel.recallMessage(id) },
                            onReactMessage = { id, emoji -> viewModel.reactMessage(id, emoji) },
                            onSearchFilterChange = { kw, kind -> viewModel.setSearchFilter(kw, kind) },
                            onUpdateRetention = { days -> viewModel.updateRetentionDays(days) },
                            onUpdateMaxFileMB = { mb -> viewModel.updateRoomMaxFileMB(mb) },
                            onUpdatePrefs = { next -> viewModel.updatePrefs(next) },
                            onSelectTheme = { mode -> viewModel.selectTheme(mode) },
                            onExportLog = { viewModel.exportChatLog() },
                            onClearHistory = { viewModel.clearServerHistory() },
                            onRotateCode = { viewModel.rotateEphemeralCode() },
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
                            .padding(top = 60.dp, start = 16.dp, end = 16.dp),
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
                            title = { Text("离开当前房间？") },
                            text = { Text("离开后会断开当前 P2P 直连并停止进行中的文件传输。") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showLeaveConfirm = false
                                    viewModel.leaveRoom()
                                }) { Text("确认离开", color = colors.danger) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showLeaveConfirm = false }) { Text("继续留在房间") }
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

    // 解析系统「分享到 BayChat」与深链唤起
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
