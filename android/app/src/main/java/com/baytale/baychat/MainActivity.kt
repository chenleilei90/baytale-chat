package com.baytale.baychat

import android.Manifest
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.baytale.baychat.service.AppUpdateService
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.ui.AppShortcutAction
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.PortraitCaptureActivity
import com.baytale.baychat.ui.components.FloatingRoomOrb
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayToastHost
import com.baytale.baychat.ui.screens.OnboardingScreen
import com.baytale.baychat.ui.screens.PrivacyConsentDialog
import com.baytale.baychat.ui.screens.chat.ChatRoomScreen
import com.baytale.baychat.ui.screens.lobby.LobbyScreen
import com.baytale.baychat.ui.screens.update.UpdateAvailableDialog
import com.baytale.baychat.ui.theme.BayChatTheme
import com.baytale.baychat.ui.theme.BayTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

/**
 * 湾聊（BayChat）主 Activity：负责沉浸式深浅系统栏同步、深链进房、系统分享、扫码、悬浮球与房间路由。
 */
class MainActivity : ComponentActivity() {
    private val viewModel: BayChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // B10：仅冷启动时解析启动 Intent，并在解析后立即清空，防止配置变更重放
        if (savedInstanceState == null) {
            parseIncomingIntent(intent)
            setIntent(Intent())
        }

        setContent {
            val uiState by viewModel.uiState.collectAsState()
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

            // B24：仅在尚未询问过通知权限时按需引导一次
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

            // 桌面快捷方式扫码动作路由
            LaunchedEffect(uiState.pendingAppAction, uiState.privacyAccepted) {
                if (uiState.privacyAccepted && uiState.pendingAppAction == AppShortcutAction.SCAN) {
                    viewModel.consumePendingAppAction()
                    launchQrScanner()
                }
            }

            // 悬浮胶囊 Toast 自动定时消失
            LaunchedEffect(uiState.toastMessage) {
                if (!uiState.toastMessage.isNullOrBlank()) {
                    delay(2400L)
                    viewModel.clearToast()
                }
            }

            BayChatTheme(accent = uiState.themeMode, appearance = uiState.prefs.appearance) {
                val isDark = BayTheme.colors.isDark

                // 按当前主题明暗实时同步系统状态栏与导航栏图标风格
                LaunchedEffect(isDark) {
                    val transparent = android.graphics.Color.TRANSPARENT
                    val style = if (isDark) {
                        SystemBarStyle.dark(transparent)
                    } else {
                        SystemBarStyle.light(transparent, transparent)
                    }
                    enableEdgeToEdge(
                        statusBarStyle = style,
                        navigationBarStyle = style,
                    )
                }

                BackHandler(enabled = uiState.activeRoom != null && !uiState.roomMinimized) {
                    showLeaveConfirm = true
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    val activeRoom = uiState.activeRoom
                    when {
                        uiState.privacyAccepted && !uiState.prefs.onboardingDone -> {
                            OnboardingScreen(
                                onComplete = { viewModel.markOnboardingDone() },
                            )
                        }
                        activeRoom == null || uiState.roomMinimized -> {
                            LobbyScreen(
                                state = uiState,
                                vm = viewModel,
                                onRequestQrScan = { launchQrScanner() },
                                onMoveTaskToBack = { moveTaskToBack(true) },
                            )

                            if (activeRoom != null && uiState.roomMinimized) {
                                FloatingRoomOrb(
                                    room = activeRoom,
                                    unreadCount = uiState.unreadCount,
                                    hapticEnabled = uiState.prefs.hapticEnabled,
                                    onRestoreRoom = { viewModel.restoreRoom() },
                                    onRequestLeaveRoom = { showLeaveConfirm = true },
                                )
                            }
                        }
                        else -> {
                            ChatRoomScreen(
                                state = uiState,
                                vm = viewModel,
                            )
                        }
                    }

                    // 统一悬浮胶囊轻提示宿主
                    BayToastHost(message = uiState.toastMessage)

                    // 首次启动合规隐私同意弹窗
                    if (!uiState.privacyAccepted) {
                        PrivacyConsentDialog(
                            onAccept = { viewModel.acceptPrivacyPolicy() },
                            onDecline = { finish() },
                        )
                    }

                    // 应用更新详情与断点续传弹窗
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

                    // 安全离开确认弹窗：适配「房间内按返回」与「大厅悬浮球退出」两种场景
                    if (showLeaveConfirm && uiState.activeRoom != null) {
                        val isMinimized = uiState.roomMinimized
                        val roomCode = uiState.activeRoom?.roomCode.orEmpty()
                        if (isMinimized) {
                            BayDialog(
                                title = "确认退出后台房间 #$roomCode？",
                                message = "退出后将立即断开当前 P2P 连接并终止后台进行中的文件传输。",
                                confirmText = "彻底断开退出",
                                onConfirm = {
                                    showLeaveConfirm = false
                                    viewModel.leaveRoom()
                                },
                                dismissText = "继续挂起",
                                onDismiss = { showLeaveConfirm = false },
                                destructive = true,
                            )
                        } else {
                            BayDialog(
                                title = "收起或离开房间 #$roomCode？",
                                message = "「最小化挂起」可返回大厅并保持 P2P 连接与后台传输；「彻底断开离开」将结束当前连接。",
                                confirmText = "最小化挂起",
                                onConfirm = {
                                    showLeaveConfirm = false
                                    viewModel.minimizeRoom()
                                },
                                dismissText = "彻底断开离开",
                                onDismiss = {
                                    showLeaveConfirm = false
                                    viewModel.leaveRoom()
                                },
                            ) {
                                BayButton(
                                    text = "取消，继续留在房间",
                                    onClick = { showLeaveConfirm = false },
                                    style = BayButtonStyle.Plain,
                                    size = BayButtonSize.Small,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
