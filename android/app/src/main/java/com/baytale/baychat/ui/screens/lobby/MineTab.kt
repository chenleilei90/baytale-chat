package com.baytale.baychat.ui.screens.lobby

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.AppearanceMode
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.service.IncomingNotificationHelper
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayGroup
import com.baytale.baychat.ui.design.BayGroupDivider
import com.baytale.baychat.ui.design.BayLargeTitleHeader
import com.baytale.baychat.ui.design.BayListRow
import com.baytale.baychat.ui.design.BaySegmented
import com.baytale.baychat.ui.design.RowTrailing
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.sheets.AboutDialog
import com.baytale.baychat.ui.sheets.BlockedListSheet
import com.baytale.baychat.ui.sheets.FavoritesSheet
import com.baytale.baychat.ui.sheets.ProfileEditSheet
import com.baytale.baychat.ui.sheets.ServerSheet
import com.baytale.baychat.ui.sheets.StorageSheet
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import com.baytale.baychat.ui.theme.themeOrbColor

private val THEME_PALETTE_MODES = listOf(
    AppThemeMode.GEEK,
    AppThemeMode.AIR,
    AppThemeMode.MINT,
    AppThemeMode.BERRY,
    AppThemeMode.PEACH,
    AppThemeMode.LIME,
)

private val AUTO_ACCEPT_THRESHOLDS_MB = listOf(10, 50, 100)

/**
 * 「我的」设置与偏好页面：采用分组卡片呈现身份名片、外观主题、传输与隐私偏好及服务存储管理。
 */
@Composable
fun MineTab(
    state: BayChatUiState,
    vm: BayChatViewModel,
    nav: LobbyNav,
) {
    val colors = BayTheme.colors

    var showProfileSheet by remember { mutableStateOf(false) }
    var showFavoritesSheet by remember { mutableStateOf(false) }
    var showBlockedSheet by remember { mutableStateOf(false) }
    var showStorageSheet by remember { mutableStateOf(false) }
    var showServerSheet by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshStorageStats()
    }

    val appearanceOptions = listOf("跟随系统", "浅色", "深色")
    val selectedAppearanceIdx = when (state.prefs.appearance) {
        AppearanceMode.SYSTEM -> 0
        AppearanceMode.LIGHT -> 1
        AppearanceMode.DARK -> 2
    }

    val otaStatusLabel = when (state.otaState.status) {
        OtaUpdateStatus.CHECKING -> "检查中"
        OtaUpdateStatus.AVAILABLE -> "发现新版"
        OtaUpdateStatus.DOWNLOADING -> "下载 ${state.otaState.progressPercent}%"
        OtaUpdateStatus.PAUSED -> "已暂停 ${state.otaState.progressPercent}%"
        OtaUpdateStatus.VERIFYING -> "校验中"
        OtaUpdateStatus.READY_TO_INSTALL -> "待安装"
        OtaUpdateStatus.ERROR -> "更新异常"
        OtaUpdateStatus.IDLE -> "v${BuildConfig.VERSION_NAME}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        BayLargeTitleHeader(title = "我的")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = BaySpacing.lg,
                end = BaySpacing.lg,
                top = BaySpacing.xs,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
        ) {
            // 顶部个人名片卡片
            item(key = "profile_card") {
                BayCard(
                    onClick = { showProfileSheet = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                    ) {
                        BayAvatar(
                            emojiId = state.profile.avatar,
                            size = 56.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                        ) {
                            Text(
                                text = state.profile.name,
                                style = BayType.title2,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "免注册本机身份 · 点击编辑头像与昵称",
                                style = BayType.footnote,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = "编辑资料",
                            tint = colors.textTertiary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 分组零：我的收藏
            item(key = "favorites_group") {
                BayGroup {
                    BayListRow(
                        icon = Icons.Rounded.BookmarkBorder,
                        iconTint = colors.accent,
                        title = "我的收藏",
                        value = "${state.favorites.size} 条",
                        trailing = RowTrailing.Chevron,
                        onClick = { showFavoritesSheet = true },
                    )
                }
            }

            // 分组一：外观与主题
            item(key = "appearance_group") {
                BayGroup(header = "外观与主题") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.md),
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "外观模式",
                                style = BayType.body,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = appearanceOptions[selectedAppearanceIdx],
                                style = BayType.footnote,
                                color = colors.textSecondary,
                            )
                        }
                        BaySegmented(
                            options = appearanceOptions,
                            selected = selectedAppearanceIdx,
                            onSelect = { idx ->
                                val mode = when (idx) {
                                    1 -> AppearanceMode.LIGHT
                                    2 -> AppearanceMode.DARK
                                    else -> AppearanceMode.SYSTEM
                                }
                                vm.setAppearance(mode)
                            },
                        )
                    }

                    BayGroupDivider()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.md),
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "主题强调色",
                                style = BayType.body,
                                color = colors.textPrimary,
                            )
                            Text(
                                text = state.themeMode.label,
                                style = BayType.footnote,
                                color = colors.accent,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            THEME_PALETTE_MODES.forEach { mode ->
                                val selected = state.themeMode == mode
                                val orbColor = themeOrbColor(mode, isDark = colors.isDark)

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .bayClickable(haptic = state.prefs.hapticEnabled) {
                                            vm.selectTheme(mode)
                                        }
                                        .clip(BayShapes.smShape)
                                        .background(if (selected) colors.accentSoft else colors.surface)
                                        .padding(vertical = BaySpacing.sm),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    // 选中态通过同心圆底托与白色对勾表达，保持零描边设计原则
                                    Box(
                                        modifier = Modifier
                                            .size(34.dp)
                                            .clip(CircleShape)
                                            .background(if (selected) orbColor.copy(alpha = 0.25f) else colors.fill),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(orbColor),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (selected) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Check,
                                                    contentDescription = "已选中",
                                                    tint = colors.onAccent,
                                                    modifier = Modifier.size(15.dp),
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = mode.label,
                                        style = BayType.caption.copy(
                                            fontSize = 11.sp,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        ),
                                        color = if (selected) colors.accent else colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 分组二：传输与隐私偏好
            item(key = "prefs_group") {
                BayGroup(header = "传输与隐私偏好") {
                    BayListRow(
                        icon = Icons.Rounded.Download,
                        iconTint = colors.accent,
                        title = "小文件自动接收",
                        subtitle = "开启后收到阈值内的文件将免确认直接保存",
                        trailing = RowTrailing.Switch(
                            checked = state.prefs.autoAcceptSmallFiles,
                            onChange = { checked ->
                                vm.updatePrefs(state.prefs.copy(autoAcceptSmallFiles = checked))
                            },
                        ),
                        showDivider = true,
                    )

                    // 自动接收阈值选择行
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.md),
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "自动接收大小上限",
                                style = BayType.subhead,
                                color = if (state.prefs.autoAcceptSmallFiles) colors.textPrimary else colors.textTertiary,
                            )
                            Text(
                                text = "${state.prefs.autoAcceptMaxMB} MB",
                                style = BayType.footnote,
                                color = colors.textSecondary,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = if (state.prefs.autoAcceptSmallFiles) 1f else 0.45f },
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            AUTO_ACCEPT_THRESHOLDS_MB.forEach { limitMb ->
                                BayChip(
                                    label = "${limitMb}MB",
                                    selected = state.prefs.autoAcceptMaxMB == limitMb,
                                    onClick = {
                                        if (state.prefs.autoAcceptSmallFiles) {
                                            vm.updatePrefs(state.prefs.copy(autoAcceptMaxMB = limitMb))
                                        }
                                    },
                                )
                            }
                        }
                    }

                    BayGroupDivider()

                    // 发送图片默认画质
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.md),
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(BayShapes.xsShape)
                                    .background(colors.fileTypeColors.image),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Image,
                                    contentDescription = null,
                                    tint = colors.onAccent,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "发送图片默认画质",
                                    style = BayType.body,
                                    color = colors.textPrimary,
                                )
                                Text(
                                    text = "原图直传保留原始细节，轻巧压缩更节省流量",
                                    style = BayType.footnote,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                        BaySegmented(
                            options = listOf("原图", "轻巧压缩"),
                            selected = if (state.prefs.imageOriginalMode) 0 else 1,
                            onSelect = { idx ->
                                vm.updatePrefs(state.prefs.copy(imageOriginalMode = idx == 0))
                            },
                        )
                    }

                    BayGroupDivider(hasLeadingIcon = true)

                    BayListRow(
                        icon = Icons.Rounded.CloudUpload,
                        iconTint = colors.fileTypeColors.doc,
                        title = "Wi-Fi 下自动备份缓存",
                        subtitle = "无线局域网环境下自动同步附件备份，便于离线成员回看",
                        trailing = RowTrailing.Switch(
                            checked = state.prefs.cloudBackupOnWifi,
                            onChange = { checked ->
                                vm.updatePrefs(state.prefs.copy(cloudBackupOnWifi = checked))
                            },
                        ),
                        showDivider = true,
                    )

                    BayListRow(
                        icon = Icons.Rounded.Wifi,
                        iconTint = colors.success,
                        title = "Wi-Fi 下自动下载更新",
                        subtitle = "仅在非计费无线局域网环境下静默拉取新版安装包",
                        trailing = RowTrailing.Switch(
                            checked = state.prefs.autoDownloadOnWifi,
                            onChange = { checked ->
                                vm.setAutoDownloadOnWifi(checked)
                            },
                        ),
                        showDivider = true,
                    )

                    BayListRow(
                        icon = Icons.Rounded.Radar,
                        iconTint = colors.accent,
                        title = "允许附近同 Wi-Fi 设备发现我",
                        subtitle = "在局域网或同一出口下向附近伙伴展示在线名片",
                        trailing = RowTrailing.Switch(
                            checked = state.prefs.nearbyDiscoverable,
                            onChange = { checked ->
                                vm.updatePrefs(state.prefs.copy(nearbyDiscoverable = checked))
                            },
                        ),
                        showDivider = true,
                    )

                    BayListRow(
                        icon = Icons.Rounded.ContentPaste,
                        iconTint = colors.accent,
                        title = "切回前台自动同步剪贴板",
                        subtitle = "处于聊天房间时切回前台自动检测新剪贴板并推送给伙伴",
                        trailing = RowTrailing.Switch(
                            checked = state.prefs.autoClipboardSync,
                            onChange = { checked ->
                                vm.setAutoClipboardSync(checked)
                            },
                        ),
                        showDivider = true,
                    )

                    BayListRow(
                        icon = Icons.Rounded.VolumeUp,
                        iconTint = colors.warning,
                        title = "提示音",
                        subtitle = "进房、新消息与传输完成时播放轻柔提示音",
                        trailing = RowTrailing.Switch(
                            checked = state.prefs.soundEnabled,
                            onChange = { checked ->
                                vm.setSoundEnabled(checked)
                            },
                        ),
                        showDivider = true,
                    )

                    BayListRow(
                        icon = Icons.Rounded.Vibration,
                        iconTint = colors.fileTypeColors.video,
                        title = "触感反馈",
                        subtitle = "切页、下拉刷新与按钮按压时提供细腻震动回馈",
                        trailing = RowTrailing.Switch(
                            checked = state.prefs.hapticEnabled,
                            onChange = { checked ->
                                vm.setHapticEnabled(checked)
                            },
                        ),
                        showDivider = true,
                    )

                    val context = LocalContext.current
                    val lifecycleOwner = LocalLifecycleOwner.current
                    fun queryIgnoringBatteryOpt(): Boolean {
                        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                    }
                    var notificationsEnabled by remember {
                        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
                    }
                    var ignoringBatteryOpt by remember {
                        mutableStateOf(queryIgnoringBatteryOpt())
                    }
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                                ignoringBatteryOpt = queryIgnoringBatteryOpt()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }
                    fun openAppNotificationSettings(openChannelDetail: Boolean = false) {
                        val channelIntent = if (openChannelDetail) {
                            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                putExtra(Settings.EXTRA_CHANNEL_ID, IncomingNotificationHelper.CHANNEL_ID)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        } else null
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        val fallback = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (channelIntent != null && runCatching { context.startActivity(channelIntent) }.isSuccess) {
                            return
                        }
                        runCatching { context.startActivity(intent) }.onFailure {
                            runCatching { context.startActivity(fallback) }
                        }
                    }
                    fun openBatteryOptimizationSettings() {
                        val detailsIntent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (ignoringBatteryOpt) {
                            runCatching { context.startActivity(detailsIntent) }
                            return
                        }
                        val requestIntent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (runCatching { context.startActivity(requestIntent) }.isSuccess) {
                            return
                        }
                        val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(listIntent) }.onFailure {
                            runCatching { context.startActivity(detailsIntent) }
                        }
                    }
                    val notifyLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                        if (granted || notificationsEnabled) {
                            vm.refreshForegroundBanner()
                            vm.showToast("消息与后台通知已开启")
                        } else {
                            openAppNotificationSettings(openChannelDetail = false)
                        }
                    }
                    BayListRow(
                        icon = Icons.Rounded.NotificationsActive,
                        iconTint = if (notificationsEnabled) colors.success else colors.danger,
                        title = "消息与后台通知",
                        subtitle = if (notificationsEnabled) {
                            "点击前往「新消息与文件提醒」渠道，可开启锁屏与顶部悬浮横幅弹窗"
                        } else {
                            "当前系统未开启通知权限，后台消息与来件将无法弹出提醒"
                        },
                        value = if (notificationsEnabled) "横幅设置" else "去开启",
                        trailing = RowTrailing.Chevron,
                        showDivider = true,
                        onClick = {
                            if (!notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                openAppNotificationSettings(openChannelDetail = notificationsEnabled)
                            }
                        },
                    )

                    BayListRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        iconTint = if (ignoringBatteryOpt) colors.success else colors.warning,
                        title = "后台运行权限",
                        subtitle = if (ignoringBatteryOpt) {
                            "已允许后台无限制运行，切换应用或锁屏后可稳定保持房间在线与收发消息"
                        } else {
                            "系统当前可能清理后台连接，点击设为「无限制」以常驻后台收消息"
                        },
                        value = if (ignoringBatteryOpt) "已无限制" else "去开启",
                        trailing = RowTrailing.Chevron,
                        onClick = {
                            openBatteryOptimizationSettings()
                        },
                    )
                }
            }

            // 分组三：服务与存储
            item(key = "service_group") {
                BayGroup(header = "服务与存储") {
                    BayListRow(
                        icon = Icons.Rounded.Block,
                        iconTint = colors.danger,
                        title = "屏蔽名单管理",
                        value = if (state.blockedClients.isEmpty()) "无" else "${state.blockedClients.size} 人",
                        trailing = RowTrailing.Chevron,
                        showDivider = true,
                        onClick = { showBlockedSheet = true },
                    )

                    BayListRow(
                        icon = Icons.Rounded.Storage,
                        iconTint = colors.warning,
                        title = "存储空间管理",
                        value = FormatUtils.formatBytes(state.storageStats.totalBytes),
                        trailing = RowTrailing.Chevron,
                        showDivider = true,
                        onClick = { showStorageSheet = true },
                    )

                    BayListRow(
                        icon = Icons.Rounded.Dns,
                        iconTint = colors.accent,
                        title = "服务器地址与测速",
                        value = state.prefs.serverUrl
                            .removePrefix("https://")
                            .removePrefix("http://"),
                        trailing = RowTrailing.Chevron,
                        showDivider = true,
                        onClick = { showServerSheet = true },
                    )

                    BayListRow(
                        icon = Icons.Rounded.SystemUpdate,
                        iconTint = colors.success,
                        title = "检查应用更新",
                        subtitle = "当前版本 v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        value = otaStatusLabel,
                        trailing = RowTrailing.Chevron,
                        showDivider = true,
                        onClick = {
                            when (state.otaState.status) {
                                OtaUpdateStatus.READY_TO_INSTALL -> vm.installReadyAppUpdate()
                                OtaUpdateStatus.AVAILABLE,
                                OtaUpdateStatus.DOWNLOADING,
                                OtaUpdateStatus.PAUSED,
                                OtaUpdateStatus.VERIFYING,
                                OtaUpdateStatus.ERROR,
                                -> vm.openUpdateDialog()
                                else -> vm.checkForAppUpdate(manual = true)
                            }
                        },
                    )

                    BayListRow(
                        icon = Icons.Rounded.Info,
                        iconTint = colors.textSecondary,
                        title = "关于湾聊 BayChat",
                        subtitle = "端到端直传机制与隐私保护说明",
                        trailing = RowTrailing.Chevron,
                        onClick = { showAboutDialog = true },
                    )
                }
            }
        }
    }

    if (showFavoritesSheet) {
        FavoritesSheet(
            favorites = state.favorites,
            hasActiveRoom = state.hasActiveRoom,
            hapticEnabled = state.prefs.hapticEnabled,
            pickerMode = false,
            onSendFavorite = if (state.hasActiveRoom) { fav -> vm.sendFavorite(fav) } else null,
            onDeleteFavorite = { vm.removeFavorite(it) },
            onShowToast = { vm.showToast(it) },
            onDismiss = { showFavoritesSheet = false },
        )
    }

    if (showProfileSheet) {
        ProfileEditSheet(
            state = state,
            vm = vm,
            onDismiss = { showProfileSheet = false },
        )
    }

    if (showBlockedSheet) {
        BlockedListSheet(
            state = state,
            vm = vm,
            onDismiss = { showBlockedSheet = false },
        )
    }

    if (showStorageSheet) {
        StorageSheet(
            state = state,
            vm = vm,
            onDismiss = { showStorageSheet = false },
        )
    }

    if (showServerSheet) {
        ServerSheet(
            state = state,
            vm = vm,
            onDismiss = { showServerSheet = false },
        )
    }

    if (showAboutDialog) {
        AboutDialog(
            state = state,
            vm = vm,
            onDismiss = { showAboutDialog = false },
        )
    }
}
