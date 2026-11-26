package com.baytale.baychat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.baytale.baychat.service.AppUpdateService
import com.baytale.baychat.service.IncomingNotificationHelper
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.service.TransferForegroundService
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
            var permissionGuide by remember { mutableStateOf<Pair<String, String>?>(null) }

            // UX-16：悬浮球归一化停靠位置，跨旋转与回房重挂稳定保持
            var orbDockedRight by rememberSaveable { mutableStateOf(true) }
            var orbVerticalFraction by rememberSaveable { mutableFloatStateOf(0.86f) }

            fun openAppDetailsSettings() {
                val settingsIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(settingsIntent) }
            }

            // UX-8：通知权限授权后立即补发前台保活通知；被永久拒绝时给出用途说明并跳转系统设置
            val notificationPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                viewModel.markNotificationAsked()
                if (granted) {
                    viewModel.refreshForegroundBanner()
                } else {
                    val permanentlyDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !ActivityCompat.shouldShowRequestPermissionRationale(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                    if (permanentlyDenied) {
                        permissionGuide = "需要通知权限" to "通知权限用于显示后台文件传输保活进度与新消息提醒，请前往系统设置开启。"
                    } else {
                        viewModel.showToast("已跳过通知权限，后续可在「我的 -> 消息与后台通知」中开启")
                    }
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

            fun startQrScanIntent() {
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

            // UX-8：扫一扫前检查与申请 CAMERA 权限，永久拒绝时弹窗引导系统设置
            val cameraPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    startQrScanIntent()
                } else {
                    val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                        this@MainActivity,
                        Manifest.permission.CAMERA,
                    )
                    if (permanentlyDenied) {
                        permissionGuide = "需要相机权限" to "扫一扫需要使用相机识别房间二维码，请前往系统设置开启相机权限。"
                    } else {
                        viewModel.showToast("未授予相机权限，无法使用扫一扫")
                    }
                }
            }

            fun launchQrScanner() {
                val granted = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    startQrScanIntent()
                } else {
                    cameraPermLauncher.launch(Manifest.permission.CAMERA)
                }
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

            // 进房后按需拉起一次系统「允许后台无限制运行」白名单弹窗（错开通知权限弹窗避免冲突）
            LaunchedEffect(uiState.activeRoom?.roomCode, uiState.prefs.batteryOptAsked, uiState.prefs.notificationAsked) {
                val roomReady = uiState.activeRoom != null
                val notifSettled = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || uiState.prefs.notificationAsked
                if (roomReady && !uiState.prefs.batteryOptAsked && notifSettled) {
                    delay(500L)
                    val pm = getSystemService(POWER_SERVICE) as? PowerManager
                    val ignoring = pm?.isIgnoringBatteryOptimizations(packageName) == true
                    viewModel.markBatteryOptAsked()
                    if (!ignoring) {
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:$packageName"),
                                )
                            )
                        }
                    }
                }
            }

            // UX-9：协调附近设备轮询（仅在已同意隐私且处于前台时保持轮询，退后台由 ViewModel 直接监听 AppForegroundTracker 停止）
            LaunchedEffect(uiState.privacyAccepted, uiState.isAppForeground, uiState.prefs.nearbyDiscoverable) {
                if (uiState.privacyAccepted && uiState.isAppForeground && uiState.prefs.nearbyDiscoverable) {
                    viewModel.startNearbyPolling()
                }
            }

            // 桌面快捷方式扫码动作路由
            LaunchedEffect(uiState.pendingAppAction, uiState.privacyAccepted) {
                if (uiState.privacyAccepted && uiState.pendingAppAction == AppShortcutAction.SCAN) {
                    viewModel.consumePendingAppAction()
                    launchQrScanner()
                }
            }

            // UX-12：悬浮胶囊 Toast 自动定时消失（收敛至 2000ms，并用 toastSeq 支持同文案重计时）
            LaunchedEffect(uiState.toastMessage, uiState.toastSeq) {
                if (!uiState.toastMessage.isNullOrBlank()) {
                    delay(2000L)
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

                // 房间内按返回键直接收起至悬浮球，随进随出不弹窗打扰
                BackHandler(enabled = uiState.activeRoom != null && !uiState.roomMinimized) {
                    viewModel.minimizeRoom()
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
                                    isMuted = activeRoom.roomCode.uppercase() in uiState.mutedRooms,
                                    transferProgress = uiState.transferProgress,
                                    hapticEnabled = uiState.prefs.hapticEnabled,
                                    dockedRight = orbDockedRight,
                                    verticalFraction = orbVerticalFraction,
                                    onPositionChange = { right, fraction ->
                                        orbDockedRight = right
                                        orbVerticalFraction = fraction
                                    },
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

                    // UX-17：应用更新详情与断点续传弹窗（非强制升级不打断新手引导）
                    val canShowUpdateModal = uiState.privacyAccepted &&
                        (uiState.prefs.onboardingDone || uiState.otaState.isForceUpdate) &&
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

                    // UX-8：权限被永久拒绝后的系统设置引导弹窗
                    val guide = permissionGuide
                    if (guide != null) {
                        BayDialog(
                            title = guide.first,
                            message = guide.second,
                            confirmText = "去设置开启",
                            onConfirm = {
                                permissionGuide = null
                                openAppDetailsSettings()
                            },
                            dismissText = "暂不",
                            onDismiss = { permissionGuide = null },
                        )
                    }

                    // 安全离开确认弹窗：仅用于悬浮球主动点击退出，房间内按返回键已直接收起
                    if (showLeaveConfirm && uiState.activeRoom != null) {
                        val roomCode = uiState.activeRoom?.roomCode.orEmpty()
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
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parseIncomingIntent(intent)
        setIntent(Intent())
    }

    private fun parseIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            // UX-18：通知点击直达挂起房间或冷启动自动恢复房间
            IncomingNotificationHelper.ACTION_RESTORE_ACTIVE_ROOM,
            TransferForegroundService.ACTION_RESTORE_ACTIVE_ROOM -> {
                val roomCode = intent.getStringExtra(IncomingNotificationHelper.EXTRA_ROOM_CODE).orEmpty()
                viewModel.restoreRoomFromNotification(roomCode)
            }

            AppUpdateService.ACTION_OPEN_OTA_DIALOG -> {
                viewModel.openUpdateDialog()
            }

            AppUpdateService.ACTION_RESUME_OTA_DOWNLOAD -> {
                val release = AppUpdateService.extractReleaseFromIntent(intent)
                if (release != null && com.baytale.baychat.service.AppUpdateCenter.state.value.release == null) {
                    com.baytale.baychat.service.AppUpdateCenter.updateState {
                        it.copy(status = com.baytale.baychat.service.OtaUpdateStatus.PAUSED, release = release)
                    }
                }
                viewModel.startOrResumeAppUpdate(silent = false)
            }

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
