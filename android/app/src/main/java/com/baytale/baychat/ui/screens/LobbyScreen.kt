package com.baytale.baychat.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.PartialResumeItem
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.model.RoomSummary
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.model.TransferRecord
import com.baytale.baychat.core.storage.AppUserPrefs
import com.baytale.baychat.core.storage.MediaDownloadHelper
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.service.OtaUpdateState
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.theme.LocalBayColors
import com.baytale.baychat.ui.theme.themeOrbColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val RETENTION_OPTIONS = listOf(0 to "阅后即焚", 1 to "1天", 2 to "2天", 3 to "3天", 5 to "5天", 7 to "7天")
private val FILE_LIMIT_OPTIONS = listOf(50 to "50MB", 200 to "200MB", 500 to "500MB", 1024 to "1GB", 0 to "不限制")
private val TOPIC_PRESETS = listOf("", "⚡ 互传文件", "☕ 闲聊摸鱼", "🎵 分享资源", "📸 原图交流")

// 发现页排序方式枚举
private enum class DiscoverSortMode(val label: String) {
    HOT("🔥 热门活跃"),
    NEWEST("🕒 刚刚新建"),
    MOST_PEERS("👥 人数最多"),
}

// 发现页主题筛选胶囊（含用于宽松匹配的关键词）
private data class DiscoverTopicFilter(val label: String, val presetValue: String, val keyword: String)

private val DISCOVER_TOPIC_FILTERS = listOf(
    DiscoverTopicFilter("全部", "", ""),
    DiscoverTopicFilter("⚡ 互传文件", "⚡ 互传文件", "互传文件"),
    DiscoverTopicFilter("☕ 闲聊摸鱼", "☕ 闲聊摸鱼", "闲聊摸鱼"),
    DiscoverTopicFilter("🎵 分享资源", "🎵 分享资源", "分享资源"),
    DiscoverTopicFilter("📸 原图交流", "📸 原图交流", "原图交流"),
)

// 传输记录快捷分类枚举
private enum class TransferFilterCategory(val label: String) {
    ALL("全部"),
    IMAGE("图片"),
    VIDEO("视频"),
    FILE("文件"),
}

// 4 套旗舰色球精选（与 4-Tab 网格工整呼应，同时完整保留 BayChatTheme 全套主题定义）
private val FEATURED_THEMES = listOf(
    AppThemeMode.AIR,
    AppThemeMode.PEACH,
    AppThemeMode.MINT,
    AppThemeMode.NEON,
)

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "flv", "wmv", "m4v", "3gp", "ts")

// 根据 MIME 类型或文件名后缀精准归类已收发文件
private fun classifyTransferRecord(rec: TransferRecord): TransferFilterCategory {
    val mime = rec.mimeType.lowercase(Locale.ROOT)
    val ext = rec.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") || ext in IMAGE_EXTENSIONS -> TransferFilterCategory.IMAGE
        mime.startsWith("video/") || ext in VIDEO_EXTENSIONS -> TransferFilterCategory.VIDEO
        else -> TransferFilterCategory.FILE
    }
}

// 底部 Dock 栏标签项定义
private data class DockTabItem(
    val index: Int,
    val label: String,
    val icon: ImageVector,
)

private val DOCK_TABS = listOf(
    DockTabItem(0, "快传", Icons.Rounded.Bolt),
    DockTabItem(1, "发现", Icons.Rounded.Explore),
    DockTabItem(2, "传输", Icons.Rounded.Inventory2),
    DockTabItem(3, "我的", Icons.Rounded.Person),
)

// ============================================================================
// 湾聊 v1.1.4 旗舰级 4-Tab 主界面（快传 / 发现 / 传输 / 我的）
// 搭配 56dp 精致低矮底栏 CompactDockBar、120Hz Crossfade 与全页原生 PullToRefreshBox
// ============================================================================
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    state: BayChatUiState,
    onEnterOrCreateRoom: (
        rawInput: String,
        mode: RoomMode,
        password: String,
        privatePairOnly: Boolean,
        retentionDays: Int,
        maxFileSizeMB: Int,
        saveFixedFavorite: Boolean,
        allowRandom: Boolean,
        topic: String,
    ) -> Unit,
    onCreateRoom: (
        mode: RoomMode,
        customCode: String,
        password: String,
        privatePairOnly: Boolean,
        retentionDays: Int,
        maxFileSizeMB: Int,
        saveFixedFavorite: Boolean,
        allowRandom: Boolean,
        topic: String,
    ) -> Unit,
    onJoinRoom: (rawInput: String, password: String) -> Unit,
    onRandomJoinRoom: () -> Unit,
    onRefreshLobby: () -> Unit,
    onRefreshPublicRooms: () -> Unit,
    onRefreshPublicRoomsIfStale: () -> Unit,
    onRequestQrScan: () -> Unit,
    onRemoveSavedRoom: (String) -> Unit,
    onRemoveRecentRoom: (String) -> Unit,
    onClearRecentRooms: () -> Unit,
    onUpdateProfile: (String, String) -> Unit,
    onSelectTheme: (AppThemeMode) -> Unit,
    onUpdatePrefs: (AppUserPrefs) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleHaptic: (Boolean) -> Unit,
    onPlayRefreshSound: () -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onClearPendingShare: () -> Unit,
    onCopyInviteUrl: (String) -> Unit,
    onRefreshTransfers: () -> Unit,
    onDeletePartialTransfer: (String) -> Unit,
    onClearAllPartialTransfers: () -> Unit,
    onRemoveTransferRecord: (String) -> Unit,
    onClearTransferRecords: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    onStartOrResumeAppUpdate: () -> Unit,
    onPauseAppUpdate: () -> Unit,
    onInstallReadyAppUpdate: () -> Unit,
    onIgnoreAppUpdate: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
    onToggleAutoDownloadOnWifi: (Boolean) -> Unit,
    onToast: (String) -> Unit,
    onClearError: () -> Unit,
    onConsumePendingJoin: () -> Unit,
) {
    val colors = LocalBayColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    var navTab by remember { mutableIntStateOf(0) } // 0=快传, 1=发现, 2=传输, 3=我的
    var roomInput by remember { mutableStateOf("") } // 单入口统一口令/基地号/邀请链接输入框
    var roomPassword by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) } // 高级参数默认折叠，首屏极简清爽
    var roomMode by remember { mutableStateOf(RoomMode.EPHEMERAL) }
    var allowRandom by remember { mutableStateOf(true) } // 默认打开，点击可关闭；设密码自动关闭
    var roomTopic by remember { mutableStateOf("") }
    var privatePairOnly by remember { mutableStateOf(false) }
    var retentionDays by remember { mutableIntStateOf(1) }
    var maxFileSizeMB by remember(state.serverConfig.defaultRoomMaxFileMB) {
        mutableIntStateOf(state.serverConfig.defaultRoomMaxFileMB)
    }
    var saveFavorite by remember { mutableStateOf(true) }

    // 各 Tab 独立的下拉刷新状态与 1.5s 防抖时间戳
    var lobbyRefreshing by remember { mutableStateOf(false) }
    var lastLobbyPullAt by remember { mutableLongStateOf(0L) }
    var discoverRefreshing by remember { mutableStateOf(false) }
    var lastDiscoverPullAt by remember { mutableLongStateOf(0L) }
    var transferRefreshing by remember { mutableStateOf(false) }
    var lastTransferPullAt by remember { mutableLongStateOf(0L) }

    // 计算「我的」Tab 更新提醒徽章颜色（就绪显示绿点，发现新版本/更新中显示红点）
    val ota = state.otaState
    val isIgnoredUpdate = ota.release != null &&
        !ota.isForceUpdate &&
        ota.release.versionCode == state.prefs.ignoredVersionCode
    val mineBadgeColor: Color? = when {
        ota.release == null -> null
        ota.status == OtaUpdateStatus.READY_TO_INSTALL -> colors.emerald
        ota.status == OtaUpdateStatus.IDLE || ota.status == OtaUpdateStatus.CHECKING -> null
        isIgnoredUpdate && ota.status == OtaUpdateStatus.AVAILABLE -> null
        else -> colors.danger
    }

    LaunchedEffect(state.pendingJoinCode) {
        val code = state.pendingJoinCode
        if (!code.isNullOrBlank()) {
            navTab = 0
            roomInput = code
            onConsumePendingJoin()
        }
    }

    // 切入 Tab 1 采用 15 秒 SWR 静默刷新；切入 Tab 2 扫描最新本地断点与历史记录
    LaunchedEffect(navTab) {
        if (navTab == 1) onRefreshPublicRoomsIfStale()
        if (navTab == 2) onRefreshTransfers()
    }

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            CompactDockBar(
                selectedTab = navTab,
                mineBadgeColor = mineBadgeColor,
                hapticEnabled = state.prefs.hapticEnabled,
                onSelectTab = { target -> navTab = target },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .padding(bottom = innerPadding.calculateBottomPadding()),
        ) {
            // 顶部瘦身品牌栏：左「湾聊 BayChat + 节点副标」，右「扫码图标 + 轻量小头像直达我的」
            TopBrandHeader(
                domain = state.serverConfig.domain,
                avatarEmoji = FormatUtils.presetEmoji(state.profile.avatar),
                mineBadgeColor = mineBadgeColor,
                isMineSelected = navTab == 3,
                hapticEnabled = state.prefs.hapticEnabled,
                onRequestQrScan = onRequestQrScan,
                onClickAvatar = { navTab = 3 },
            )

            // 120Hz 轻量平滑 4-Tab 页面切换
            Crossfade(
                targetState = navTab,
                animationSpec = tween(durationMillis = 160),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "lobbyTabCrossfade",
            ) { tab ->
                when (tab) {
                    0 -> {
                        // ================= Tab 0：⚡ 快传（守住传输初心 + 原生下拉刷新） =================
                        PullToRefreshBox(
                            isRefreshing = lobbyRefreshing,
                            onRefresh = {
                                val now = System.currentTimeMillis()
                                if (now - lastLobbyPullAt >= PULL_DEBOUNCE_MS) {
                                    lastLobbyPullAt = now
                                    lobbyRefreshing = true
                                    onRefreshLobby()
                                    scope.launch {
                                        delay(550L)
                                        lobbyRefreshing = false
                                        BayHaptic.confirm(view, state.prefs.hapticEnabled)
                                        onPlayRefreshSound()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // 顶部企业级 OTA 实时状态横幅
                                val showTopUpdateBanner = ota.release != null &&
                                    ota.status != OtaUpdateStatus.IDLE &&
                                    ota.status != OtaUpdateStatus.CHECKING &&
                                    (!isIgnoredUpdate || ota.status != OtaUpdateStatus.AVAILABLE)

                                if (showTopUpdateBanner) {
                                    UpdateBannerCard(
                                        ota = ota,
                                        onStartOrResume = onStartOrResumeAppUpdate,
                                        onPause = onPauseAppUpdate,
                                        onInstall = onInstallReadyAppUpdate,
                                        onIgnore = onIgnoreAppUpdate,
                                        onOpenDetail = onOpenUpdateDialog,
                                    )
                                }

                                // 外部「分享到湾聊」待发提示条
                                if (state.pendingShare.hasContent) {
                                    Card(
                                        shape = RoundedCornerShape(18.dp),
                                        colors = CardDefaults.cardColors(containerColor = colors.accentSoft),
                                        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f)),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("已收到系统分享内容", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = colors.accent)
                                                val desc = buildString {
                                                    if (state.pendingShare.uris.isNotEmpty()) append("${state.pendingShare.uris.size} 个文件/图片")
                                                    if (!state.pendingShare.sharedText.isNullOrBlank()) {
                                                        if (isNotEmpty()) append(" + ")
                                                        append("一段文本")
                                                    }
                                                    append("，进房后即可一键发送")
                                                }
                                                Text(desc, fontSize = 12.sp, color = colors.textSub)
                                            }
                                            IconButton(onClick = onClearPendingShare) {
                                                Icon(Icons.Rounded.Close, contentDescription = "取消分享", tint = colors.textSub)
                                            }
                                        }
                                    }
                                }

                                // 单入口智能直达台（留空秒开 / 口令有就进无就建 / 折叠高级设置）
                                val hasInputCode = roomInput.trim().isNotEmpty()
                                val hasPasswordInput = roomPassword.isNotBlank()
                                val summaryLabel = buildString {
                                    append(if (roomMode == RoomMode.FIXED || hasInputCode) "常驻" else "临时")
                                    append("·")
                                    append(if (retentionDays == 0) "即焚" else "${retentionDays}天")
                                    append("·")
                                    append(if (maxFileSizeMB <= 0) "不限" else FormatUtils.formatLimitMB(maxFileSizeMB))
                                }

                                Card(
                                    shape = RoundedCornerShape(22.dp),
                                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                                    border = BorderStroke(1.dp, colors.border),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(15.dp),
                                        verticalArrangement = Arrangement.spacedBy(11.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = "⚡ 快速传输直达台",
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 15.sp,
                                                color = colors.textMain,
                                            )
                                            Text(
                                                text = if (hasInputCode) "有就进 · 无就建" else "留空秒开新房",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = colors.accent,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(99.dp))
                                                    .background(colors.accentSoft)
                                                    .padding(horizontal = 9.dp, vertical = 3.dp),
                                            )
                                        }

                                        // 统一超级输入框：口令 / 基地号 / 邀请链接 + 内嵌清空/粘贴/扫码
                                        OutlinedTextField(
                                            value = roomInput,
                                            onValueChange = {
                                                if (it.length <= 160) {
                                                    roomInput = it
                                                    onClearError()
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            label = { Text("输入口令 / 基地号 / 邀请链接（可留空）", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                            placeholder = { Text("留空一键秒开，或输 bay-8888 直达") },
                                            singleLine = true,
                                            shape = RoundedCornerShape(14.dp),
                                            trailingIcon = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (roomInput.isNotEmpty()) {
                                                        IconButton(
                                                            onClick = {
                                                                roomInput = ""
                                                                onClearError()
                                                            },
                                                            modifier = Modifier.size(34.dp),
                                                        ) {
                                                            Icon(Icons.Rounded.Close, contentDescription = "清空", tint = colors.textMuted, modifier = Modifier.size(18.dp))
                                                        }
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                            val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                                            if (!text.isNullOrBlank()) {
                                                                roomInput = text.trim()
                                                                onClearError()
                                                                BayHaptic.tap(view, state.prefs.hapticEnabled)
                                                            } else {
                                                                onToast("剪贴板暂无内容")
                                                            }
                                                        },
                                                        modifier = Modifier.size(36.dp),
                                                    ) {
                                                        Icon(Icons.Rounded.ContentPaste, contentDescription = "粘贴", tint = colors.textSub, modifier = Modifier.size(19.dp))
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            BayHaptic.tap(view, state.prefs.hapticEnabled)
                                                            onRequestQrScan()
                                                        },
                                                        modifier = Modifier.size(38.dp),
                                                    ) {
                                                        Icon(Icons.Rounded.QrCodeScanner, contentDescription = "扫码秒连", tint = colors.accent, modifier = Modifier.size(21.dp))
                                                    }
                                                }
                                            },
                                        )

                                        // 仅当目标房间需要密码（或未展开高级设置但已填密码）时自动滑出密码框
                                        AnimatedVisibility(
                                            visible = state.lobbyNeedPassword || (!showAdvanced && roomPassword.isNotBlank()),
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut(),
                                        ) {
                                            OutlinedTextField(
                                                value = roomPassword,
                                                onValueChange = {
                                                    roomPassword = it
                                                    onClearError()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                                label = { Text("房间暗号密码") },
                                                placeholder = { Text("输入该房间的访问密码") },
                                                leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = colors.accent) },
                                                visualTransformation = PasswordVisualTransformation(),
                                                singleLine = true,
                                                shape = RoundedCornerShape(14.dp),
                                            )
                                        }

                                        if (!state.lobbyError.isNullOrBlank()) {
                                            ErrorBanner(state.lobbyError)
                                        }

                                        // 主按钮：留空秒开快传 / 填口令智能有就进无就建
                                        Button(
                                            onClick = {
                                                BayHaptic.tap(view, state.prefs.hapticEnabled)
                                                onEnterOrCreateRoom(
                                                    roomInput,
                                                    roomMode,
                                                    roomPassword,
                                                    privatePairOnly,
                                                    retentionDays,
                                                    maxFileSizeMB,
                                                    saveFavorite,
                                                    !hasPasswordInput && allowRandom,
                                                    roomTopic,
                                                )
                                            },
                                            enabled = !state.lobbyBusy,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(50.dp),
                                            shape = RoundedCornerShape(15.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                        ) {
                                            if (state.lobbyBusy) {
                                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                            } else {
                                                Text(
                                                    text = when {
                                                        hasInputCode -> "🚀 直达进入（无则自动建房）"
                                                        roomMode == RoomMode.FIXED -> "📌 秒开常驻秘密基地"
                                                        else -> "⚡ 秒开快传房间"
                                                    },
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                            }
                                        }

                                        // 一行紧凑控制条：允许陌生人随机串门快捷胶囊 + 折叠式高级设置入口
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            val randomActive = !hasPasswordInput && allowRandom
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(11.dp))
                                                    .background(if (randomActive) colors.accentSoft else colors.surfaceSubtle)
                                                    .border(
                                                        width = 1.dp,
                                                        color = if (randomActive) colors.accent.copy(alpha = 0.35f) else colors.border,
                                                        shape = RoundedCornerShape(11.dp),
                                                    )
                                                    .clickable(enabled = !hasPasswordInput) {
                                                        allowRandom = !allowRandom
                                                        BayHaptic.tap(view, state.prefs.hapticEnabled)
                                                        onToast(if (allowRandom) "🌐 已开启陌生人随机串门" else "🔒 已关闭随机串门，仅凭口令进入")
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                            ) {
                                                Icon(
                                                    imageVector = if (randomActive) Icons.Rounded.Public else Icons.Rounded.Lock,
                                                    contentDescription = null,
                                                    tint = if (randomActive) colors.accent else colors.textMuted,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Text(
                                                    text = when {
                                                        hasPasswordInput -> "暗号保护"
                                                        allowRandom -> "允许串门 ✓"
                                                        else -> "仅限口令"
                                                    },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (randomActive) colors.accent else colors.textSub,
                                                    maxLines = 1,
                                                )
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(11.dp))
                                                    .background(if (showAdvanced) colors.accentSoft else Color.Transparent)
                                                    .clickable {
                                                        showAdvanced = !showAdvanced
                                                        BayHaptic.tap(view, state.prefs.hapticEnabled)
                                                    }
                                                    .padding(horizontal = 9.dp, vertical = 6.dp),
                                            ) {
                                                Icon(
                                                    Icons.Rounded.Tune,
                                                    contentDescription = null,
                                                    tint = if (showAdvanced) colors.accent else colors.textSub,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                                Text(
                                                    text = "高级设置",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (showAdvanced) colors.accent else colors.textSub,
                                                )
                                                if (!showAdvanced) {
                                                    Text(
                                                        text = "($summaryLabel)",
                                                        fontSize = 11.sp,
                                                        color = colors.textMuted,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                Icon(
                                                    imageVector = if (showAdvanced) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                    contentDescription = null,
                                                    tint = if (showAdvanced) colors.accent else colors.textMuted,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }

                                        // 折叠区：低频参数按需展开（模式/密码/氛围标签/保鲜期/附件上限/人数/收藏）
                                        AnimatedVisibility(
                                            visible = showAdvanced,
                                            enter = expandVertically() + fadeIn(),
                                            exit = shrinkVertically() + fadeOut(),
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp),
                                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                HorizontalDivider(color = colors.border)

                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    ModeSelectCard(
                                                        modifier = Modifier.weight(1f),
                                                        selected = roomMode == RoomMode.EPHEMERAL,
                                                        title = "闪传临时房",
                                                        subtitle = "6位口令 · 聊完销毁",
                                                        icon = { Icon(Icons.Rounded.LocalFireDepartment, null, tint = Color.White, modifier = Modifier.size(16.dp)) },
                                                        badgeColors = listOf(colors.accent, colors.accentSecondary),
                                                        onClick = {
                                                            roomMode = RoomMode.EPHEMERAL
                                                            if (retentionDays == 3) retentionDays = 1
                                                        },
                                                    )
                                                    ModeSelectCard(
                                                        modifier = Modifier.weight(1f),
                                                        selected = roomMode == RoomMode.FIXED,
                                                        title = "常驻秘密基地",
                                                        subtitle = "固定房号 · 随时重连",
                                                        icon = { Icon(Icons.Rounded.PushPin, null, tint = Color.White, modifier = Modifier.size(16.dp)) },
                                                        badgeColors = listOf(Color(0xFF6366F1), Color(0xFF06B6D4)),
                                                        onClick = {
                                                            roomMode = RoomMode.FIXED
                                                            if (retentionDays == 1) retentionDays = 3
                                                        },
                                                    )
                                                }

                                                OutlinedTextField(
                                                    value = roomPassword,
                                                    onValueChange = { roomPassword = it },
                                                    modifier = Modifier.fillMaxWidth(),
                                                    label = { Text("访问暗号密码（可选）") },
                                                    placeholder = { Text("留空免密；设暗号自动关闭随机串门") },
                                                    leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = colors.textMuted) },
                                                    visualTransformation = PasswordVisualTransformation(),
                                                    singleLine = true,
                                                    shape = RoundedCornerShape(14.dp),
                                                )

                                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                    Text("房间氛围标签（公开广场展示）", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        TOPIC_PRESETS.forEach { preset ->
                                                            val label = preset.ifBlank { "默认无标签" }
                                                            FilterChip(
                                                                selected = roomTopic == preset,
                                                                onClick = { roomTopic = preset },
                                                                label = { Text(label, fontSize = 12.sp) },
                                                                colors = FilterChipDefaults.filterChipColors(
                                                                    selectedContainerColor = colors.accent,
                                                                    selectedLabelColor = Color.White,
                                                                ),
                                                            )
                                                        }
                                                    }
                                                }

                                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                    ) {
                                                        Text("消息与文件云端保鲜期", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                                                        Text(
                                                            text = if (retentionDays == 0) "服务器零留存" else "活跃起保留 ${retentionDays} 天",
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = colors.accent,
                                                        )
                                                    }
                                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        RETENTION_OPTIONS.forEach { (days, label) ->
                                                            FilterChip(
                                                                selected = retentionDays == days,
                                                                onClick = { retentionDays = days },
                                                                label = { Text(label, fontSize = 12.sp) },
                                                                colors = FilterChipDefaults.filterChipColors(
                                                                    selectedContainerColor = if (days == 0) colors.danger else colors.accent,
                                                                    selectedLabelColor = Color.White,
                                                                ),
                                                            )
                                                        }
                                                    }
                                                }

                                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                    ) {
                                                        Text("单附件大小上限", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                                                        Text(
                                                            text = FormatUtils.formatLimitMB(maxFileSizeMB),
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = colors.accent,
                                                        )
                                                    }
                                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        val cap = state.serverConfig.maxAttachmentMB
                                                        FILE_LIMIT_OPTIONS.filter { cap <= 0 || (it.first in 1..cap) }.forEach { (mb, label) ->
                                                            FilterChip(
                                                                selected = maxFileSizeMB == mb,
                                                                onClick = { maxFileSizeMB = mb },
                                                                label = { Text(label, fontSize = 12.sp) },
                                                                colors = FilterChipDefaults.filterChipColors(
                                                                    selectedContainerColor = colors.accent,
                                                                    selectedLabelColor = Color.White,
                                                                ),
                                                            )
                                                        }
                                                    }
                                                }

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    FilterChip(
                                                        selected = privatePairOnly,
                                                        onClick = { privatePairOnly = true },
                                                        label = { Text("1v1 双人", fontSize = 12.sp) },
                                                        colors = FilterChipDefaults.filterChipColors(
                                                            selectedContainerColor = colors.accent,
                                                            selectedLabelColor = Color.White,
                                                        ),
                                                    )
                                                    FilterChip(
                                                        selected = !privatePairOnly,
                                                        onClick = { privatePairOnly = false },
                                                        label = { Text("小队 (${state.serverConfig.maxRoomPeers}人)", fontSize = 12.sp) },
                                                        colors = FilterChipDefaults.filterChipColors(
                                                            selectedContainerColor = colors.accent,
                                                            selectedLabelColor = Color.White,
                                                        ),
                                                    )
                                                }

                                                if (roomMode == RoomMode.FIXED || hasInputCode) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                    ) {
                                                        Text("自动收藏到「我的秘密基地」", fontSize = 12.5.sp, color = colors.textSub)
                                                        Switch(
                                                            checked = saveFavorite,
                                                            onCheckedChange = { saveFavorite = it },
                                                            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 🎲 随机串门快捷紧凑条
                                Card(
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                                    border = BorderStroke(1.2.dp, colors.accent.copy(alpha = 0.35f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !state.randomJoining) {
                                            BayHaptic.tap(view, state.prefs.hapticEnabled)
                                            onRandomJoinRoom()
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(colors.accent.copy(alpha = 0.08f), colors.accentSecondary.copy(alpha = 0.08f))
                                                )
                                            )
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(11.dp),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(40.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(Icons.Rounded.Casino, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
                                            }
                                            Column {
                                                Text(
                                                    text = "随机串门 · 空降免密房",
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 14.sp,
                                                    color = colors.textMain,
                                                    maxLines = 1,
                                                )
                                                Text(
                                                    text = if (state.publicRooms.isNotEmpty()) {
                                                        "只进无密码公开房 · 当前 ${state.publicRooms.size} 间活跃"
                                                    } else {
                                                        "自动避开加密房 · 点击试试手气"
                                                    },
                                                    fontSize = 11.5.sp,
                                                    color = colors.textSub,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                BayHaptic.tap(view, state.prefs.hapticEnabled)
                                                onRandomJoinRoom()
                                            },
                                            enabled = !state.randomJoining,
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            modifier = Modifier.height(34.dp),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                        ) {
                                            if (state.randomJoining) {
                                                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                                            } else {
                                                Text("试试手气", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                            }
                                        }
                                    }
                                }

                                // 🕒 最近房间
                                if (state.recentRooms.isNotEmpty()) {
                                    Card(
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                                        border = BorderStroke(1.dp, colors.border),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(9.dp),
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text("🕒 最近房间", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = colors.textMain)
                                                TextButton(
                                                    onClick = onClearRecentRooms,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                    modifier = Modifier.height(28.dp),
                                                ) {
                                                    Text("清空", fontSize = 12.sp, color = colors.textMuted)
                                                }
                                            }
                                            state.recentRooms.take(6).forEach { item ->
                                                RecentRoomRow(
                                                    room = item,
                                                    onEnter = { onJoinRoom(item.code, item.password) },
                                                    onCopy = { onCopyInviteUrl(item.code) },
                                                    onRemove = { onRemoveRecentRoom(item.code) },
                                                )
                                            }
                                        }
                                    }
                                }

                                // 📌 我的秘密基地
                                if (state.savedRooms.isNotEmpty()) {
                                    Card(
                                        shape = RoundedCornerShape(20.dp),
                                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                                        border = BorderStroke(1.dp, colors.border),
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(9.dp),
                                        ) {
                                            Text("📌 我的秘密基地", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = colors.textMain)
                                            state.savedRooms.forEach { item ->
                                                SavedRoomRow(
                                                    room = item,
                                                    onEnter = { onJoinRoom(item.code, item.password) },
                                                    onCopy = { onCopyInviteUrl(item.code) },
                                                    onRemove = { onRemoveSavedRoom(item.code) },
                                                )
                                            }
                                        }
                                    }
                                }

                                // 底部柔和留白，确保最后一张卡片绝不紧贴 56dp Dock 栏
                                Spacer(Modifier.height(14.dp))
                            }
                        }
                    }

                    1 -> {
                        // ================= Tab 1：🧭 发现（15s SWR + 下拉强制刷新 + 排序与主题筛选） =================
                        PullToRefreshBox(
                            isRefreshing = discoverRefreshing,
                            onRefresh = {
                                val now = System.currentTimeMillis()
                                if (now - lastDiscoverPullAt >= PULL_DEBOUNCE_MS) {
                                    lastDiscoverPullAt = now
                                    discoverRefreshing = true
                                    onRefreshPublicRooms()
                                    scope.launch {
                                        delay(550L)
                                        discoverRefreshing = false
                                        BayHaptic.confirm(view, state.prefs.hapticEnabled)
                                        onPlayRefreshSound()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            DiscoverTabContent(
                                publicRooms = state.publicRooms,
                                loading = state.publicRoomsLoading,
                                randomJoining = state.randomJoining,
                                hapticEnabled = state.prefs.hapticEnabled,
                                onRandomJoin = onRandomJoinRoom,
                                onRefresh = {
                                    BayHaptic.tap(view, state.prefs.hapticEnabled)
                                    onRefreshPublicRooms()
                                },
                                onJoinPublicRoom = { code -> onJoinRoom(code, "") },
                                onQuickCreatePublicRoom = { topicPreset ->
                                    onCreateRoom(
                                        RoomMode.EPHEMERAL,
                                        "",
                                        "",
                                        false,
                                        1,
                                        state.serverConfig.defaultRoomMaxFileMB,
                                        false,
                                        true,
                                        topicPreset.ifBlank { "☕ 闲聊摸鱼" },
                                    )
                                },
                            )
                        }
                    }

                    2 -> {
                        // ================= Tab 2：📦 传输（下拉刷新 + 断点续传 + 历史分类筛选） =================
                        PullToRefreshBox(
                            isRefreshing = transferRefreshing,
                            onRefresh = {
                                val now = System.currentTimeMillis()
                                if (now - lastTransferPullAt >= PULL_DEBOUNCE_MS) {
                                    lastTransferPullAt = now
                                    transferRefreshing = true
                                    onRefreshTransfers()
                                    scope.launch {
                                        delay(480L)
                                        transferRefreshing = false
                                        BayHaptic.confirm(view, state.prefs.hapticEnabled)
                                        onPlayRefreshSound()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            TransferCenterTabContent(
                                partials = state.partialTransfers,
                                records = state.transferRecords,
                                hapticEnabled = state.prefs.hapticEnabled,
                                onRefresh = {
                                    BayHaptic.tap(view, state.prefs.hapticEnabled)
                                    onRefreshTransfers()
                                },
                                onDeletePartial = onDeletePartialTransfer,
                                onClearAllPartials = onClearAllPartialTransfers,
                                onOpenRecord = { rec ->
                                    val path = rec.localPath
                                    val opened = !path.isNullOrBlank() && MediaDownloadHelper.openLocalFile(context, path, rec.mimeType)
                                    if (!opened) {
                                        onToast("文件保存在系统「${rec.savedDisplayPath ?: "Download/BayChat"}」中")
                                    }
                                },
                                onRemoveRecord = onRemoveTransferRecord,
                                onClearRecords = onClearTransferRecords,
                            )
                        }
                    }

                    3 -> {
                        // ================= Tab 3：👤 我的（资料/皮肤/音效触感/OTA 更新中心/传输默认/系统节点） =================
                        MineTabContent(
                            state = state,
                            onUpdateProfile = onUpdateProfile,
                            onSelectTheme = onSelectTheme,
                            onToggleSound = onToggleSound,
                            onToggleHaptic = onToggleHaptic,
                            onUpdatePrefs = onUpdatePrefs,
                            onUpdateServerUrl = onUpdateServerUrl,
                            onClearAllPartialTransfers = onClearAllPartialTransfers,
                            onCheckUpdate = onCheckAppUpdate,
                            onStartOrResumeUpdate = onStartOrResumeAppUpdate,
                            onPauseUpdate = onPauseAppUpdate,
                            onInstallReadyUpdate = onInstallReadyAppUpdate,
                            onOpenUpdateDialog = onOpenUpdateDialog,
                            onToggleAutoDownloadOnWifi = onToggleAutoDownloadOnWifi,
                            onToast = onToast,
                        )
                    }
                }
            }
        }
    }
}

// 顶部瘦身品牌栏：清爽品牌 + 节点副标，右侧仅保留「扫码」与「小头像（直达我的）」
@Composable
private fun TopBrandHeader(
    domain: String,
    avatarEmoji: String,
    mineBadgeColor: Color?,
    isMineSelected: Boolean,
    hapticEnabled: Boolean,
    onRequestQrScan: () -> Unit,
    onClickAvatar: () -> Unit,
) {
    val colors = LocalBayColors.current
    val view = LocalView.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(37.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "湾聊",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = colors.textMain,
                        maxLines = 1,
                    )
                    Text(
                        text = "BayChat",
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.accentSoft)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        maxLines = 1,
                    )
                }
                Text(
                    text = "$domain · P2P 跨端直传",
                    fontSize = 11.sp,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 快捷扫码图标按钮
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, colors.border, CircleShape)
                    .clickable {
                        BayHaptic.tap(view, hapticEnabled)
                        onRequestQrScan()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCodeScanner,
                    contentDescription = "扫码进房",
                    tint = colors.accent,
                    modifier = Modifier.size(19.dp),
                )
            }

            // 轻量快捷小头像胶囊：点击直接切换至 Tab 3「我的」
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable {
                        BayHaptic.tap(view, hapticEnabled)
                        onClickAvatar()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isMineSelected) colors.accentSoft else colors.surface)
                        .border(
                            width = if (isMineSelected) 1.5.dp else 1.dp,
                            color = if (isMineSelected) colors.accent else colors.border,
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(avatarEmoji, fontSize = 16.sp)
                }
                if (mineBadgeColor != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(mineBadgeColor)
                            .border(1.5.dp, colors.background, CircleShape),
                    )
                }
            }
        }
    }
}

// ============================================================================
// 56dp 精致低矮自定义底部 Dock 栏（对标微信/飞书/小红书紧凑高级手感）
// ============================================================================
@Composable
private fun CompactDockBar(
    selectedTab: Int,
    mineBadgeColor: Color?,
    hapticEnabled: Boolean,
    onSelectTab: (Int) -> Unit,
) {
    val colors = LocalBayColors.current
    val view = LocalView.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.border)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(56.dp)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DOCK_TABS.forEach { tab ->
                val selected = selectedTab == tab.index
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.12f else 1f,
                    animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
                    label = "dockScale_${tab.index}",
                )
                val pillScale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.78f,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 520f),
                    label = "dockPillScale_${tab.index}",
                )
                val interactionSource = remember { MutableInteractionSource() }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) {
                            if (!selected) {
                                BayHaptic.tap(view, hapticEnabled)
                                onSelectTab(tab.index)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.size(width = 50.dp, height = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        // 选中态紧凑浅色胶囊背板（50dp x 28dp）
                        if (selected) {
                            Box(
                                modifier = Modifier
                                    .size(width = 50.dp, height = 28.dp)
                                    .graphicsLayer {
                                        scaleX = pillScale
                                        scaleY = pillScale
                                    }
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(colors.accentSoft),
                            )
                        }
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) colors.accent else colors.textMuted,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                        )
                        // Tab 3「我的」更新提醒红点 / 就绪绿点
                        if (tab.index == 3 && mineBadgeColor != null) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 2.dp, end = 9.dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(mineBadgeColor)
                                    .border(1.4.dp, colors.surface, CircleShape),
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = tab.label,
                        fontSize = 11.sp,
                        lineHeight = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) colors.accent else colors.textMuted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ============================================================================
// Tab 1「🧭 发现」：随机串门 Hero 卡 + 紧凑排序胶囊 + 横向主题筛选胶囊 + 实时公开房
// ============================================================================
@Composable
private fun DiscoverTabContent(
    publicRooms: List<RoomSummary>,
    loading: Boolean,
    randomJoining: Boolean,
    hapticEnabled: Boolean,
    onRandomJoin: () -> Unit,
    onRefresh: () -> Unit,
    onJoinPublicRoom: (String) -> Unit,
    onQuickCreatePublicRoom: (topicPreset: String) -> Unit,
) {
    val colors = LocalBayColors.current
    val view = LocalView.current

    var sortMode by remember { mutableStateOf(DiscoverSortMode.HOT) }
    var selectedTopicIdx by remember { mutableIntStateOf(0) }
    val activeTopicFilter = DISCOVER_TOPIC_FILTERS.getOrElse(selectedTopicIdx) { DISCOVER_TOPIC_FILTERS.first() }

    // 实时筛选与智能排序（热门活跃：在线未满员优先 -> 有主题标签加权 -> 最近活跃时间倒序）
    val displayRooms = remember(publicRooms, sortMode, selectedTopicIdx) {
        val filtered = if (activeTopicFilter.keyword.isBlank()) {
            publicRooms
        } else {
            publicRooms.filter { room ->
                room.topic == activeTopicFilter.presetValue ||
                    room.topic.contains(activeTopicFilter.keyword, ignoreCase = true)
            }
        }
        when (sortMode) {
            DiscoverSortMode.HOT -> filtered.sortedWith(
                compareByDescending<RoomSummary> { it.peerCount in 1 until it.maxPeers }
                    .thenByDescending { it.peerCount < it.maxPeers }
                    .thenByDescending { it.topic.isNotBlank() }
                    .thenByDescending { maxOf(it.lastActiveAt, it.createdAt) }
                    .thenByDescending { it.peerCount }
            )
            DiscoverSortMode.NEWEST -> filtered.sortedByDescending { it.createdAt }
            DiscoverSortMode.MOST_PEERS -> filtered.sortedWith(
                compareByDescending<RoomSummary> { it.peerCount }
                    .thenByDescending { maxOf(it.lastActiveAt, it.createdAt) }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 渐变蓝盲盒随机进房 Hero 卡
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary)))
                .padding(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🔓 仅限免密公开房",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Text(
                        text = "${publicRooms.size} 间开放中",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "🎲 随机进入一间房",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                    )
                    Text(
                        text = "自动避开加密房与满员房，进房后不投缘随时点「换一间」",
                        fontSize = 12.5.sp,
                        color = Color.White.copy(alpha = 0.9f),
                        lineHeight = 18.sp,
                    )
                }
                Button(
                    onClick = {
                        BayHaptic.tap(view, hapticEnabled)
                        onRandomJoin()
                    },
                    enabled = !randomJoining,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = colors.accent),
                ) {
                    if (randomJoining) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = colors.accent, strokeWidth = 2.dp)
                    } else {
                        Text("⚡ 立即随机空降", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp)
                    }
                }
            }
        }

        // 公开房实时广场卡（内置 1 行排序胶囊 + 横向主题筛选胶囊）
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🌐 正在开放的免密房", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)
                        if (displayRooms.isNotEmpty()) {
                            Text(
                                text = "${displayRooms.size}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(colors.accentSoft)
                                    .padding(horizontal = 7.dp, vertical = 1.dp),
                            )
                        }
                    }
                    TextButton(
                        onClick = onRefresh,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = colors.accent)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = colors.accent, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("刷新", fontSize = 12.sp, color = colors.accent, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 1 行紧凑排序胶囊：🔥 热门活跃 / 🕒 刚刚新建 / 👥 人数最多
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    DiscoverSortMode.entries.forEach { mode ->
                        val picked = sortMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (picked) colors.accent else colors.surfaceSubtle)
                                .border(
                                    width = 1.dp,
                                    color = if (picked) colors.accent else colors.border,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    if (sortMode != mode) {
                                        sortMode = mode
                                        BayHaptic.tap(view, hapticEnabled)
                                    }
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = mode.label,
                                fontSize = 11.5.sp,
                                fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium,
                                color = if (picked) Color.White else colors.textSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // 横向滚动主题筛选胶囊：全部 / ⚡ 互传文件 / ☕ 闲聊摸鱼 / 🎵 分享资源 / 📸 原图交流
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    DISCOVER_TOPIC_FILTERS.forEachIndexed { idx, filter ->
                        val picked = selectedTopicIdx == idx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(99.dp))
                                .background(if (picked) colors.accentSoft else colors.surfaceSubtle)
                                .border(
                                    width = 1.dp,
                                    color = if (picked) colors.accent.copy(alpha = 0.5f) else colors.border,
                                    shape = RoundedCornerShape(99.dp),
                                )
                                .clickable {
                                    if (selectedTopicIdx != idx) {
                                        selectedTopicIdx = idx
                                        BayHaptic.tap(view, hapticEnabled)
                                    }
                                }
                                .padding(horizontal = 11.dp, vertical = 5.dp),
                        ) {
                            Text(
                                text = filter.label,
                                fontSize = 11.5.sp,
                                fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium,
                                color = if (picked) colors.accent else colors.textSub,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // 筛选排序后的房间列表与贴心空态引导
                if (displayRooms.isEmpty()) {
                    val isFilteredEmpty = publicRooms.isNotEmpty() && selectedTopicIdx != 0
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surfaceSubtle)
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Text(
                            text = if (isFilteredEmpty) "「${activeTopicFilter.label}」下暂无公开房" else "当前还没有在线的免密公开房",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = colors.textMain,
                        )
                        Text(
                            text = if (isFilteredEmpty) {
                                "你可以先看看全部公开房，或直接开一间该主题房等待有缘人串门"
                            } else {
                                "建一间免密公开房，手机或网页端的小伙伴都能随机偶遇进来聊"
                            },
                            fontSize = 12.sp,
                            color = colors.textMuted,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isFilteredEmpty) {
                                OutlinedButton(
                                    onClick = {
                                        selectedTopicIdx = 0
                                        BayHaptic.tap(view, hapticEnabled)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(36.dp),
                                ) {
                                    Text("查看全部", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                                }
                            }
                            Button(
                                onClick = {
                                    BayHaptic.tap(view, hapticEnabled)
                                    onQuickCreatePublicRoom(activeTopicFilter.presetValue)
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier.height(36.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            ) {
                                Text("✨ 开一间公开房", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            }
                        }
                    }
                } else {
                    displayRooms.forEach { room ->
                        val isFull = room.peerCount >= room.maxPeers
                        val activeTs = maxOf(room.lastActiveAt, room.createdAt)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surfaceSubtle)
                                .clickable(enabled = !isFull) {
                                    BayHaptic.tap(view, hapticEnabled)
                                    onJoinPublicRoom(room.code)
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (isFull) colors.textMuted else if (room.peerCount > 0) colors.emerald else colors.amber),
                                    )
                                    Text(
                                        text = room.topic.ifBlank { "房间 #${room.code}" },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp,
                                        color = colors.textMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val timePart = if (activeTs > 0L) " · ${FormatUtils.formatRelativeTime(activeTs)}" else ""
                                Text(
                                    text = "#${room.code} · ${room.peerCount}/${room.maxPeers} 人 · ${if (room.mode == RoomMode.FIXED.wireValue) "常驻" else "闪传"}$timePart",
                                    fontSize = 11.5.sp,
                                    color = colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    BayHaptic.tap(view, hapticEnabled)
                                    onJoinPublicRoom(room.code)
                                },
                                enabled = !isFull,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            ) {
                                Text(if (isFull) "已满" else "串门", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
    }
}

// ============================================================================
// Tab 2「📦 传输」：未完成 .part 断点续传管理 + 已收发文件快捷分类历史
// ============================================================================
@Composable
private fun TransferCenterTabContent(
    partials: List<PartialResumeItem>,
    records: List<TransferRecord>,
    hapticEnabled: Boolean,
    onRefresh: () -> Unit,
    onDeletePartial: (String) -> Unit,
    onClearAllPartials: () -> Unit,
    onOpenRecord: (TransferRecord) -> Unit,
    onRemoveRecord: (String) -> Unit,
    onClearRecords: () -> Unit,
) {
    val colors = LocalBayColors.current
    val view = LocalView.current
    val totalPartialBytes = partials.sumOf { it.savedBytes }

    var selectedCategory by remember { mutableStateOf(TransferFilterCategory.ALL) }
    val categoryCounts = remember(records) {
        val img = records.count { classifyTransferRecord(it) == TransferFilterCategory.IMAGE }
        val vid = records.count { classifyTransferRecord(it) == TransferFilterCategory.VIDEO }
        val doc = records.size - img - vid
        mapOf(
            TransferFilterCategory.ALL to records.size,
            TransferFilterCategory.IMAGE to img,
            TransferFilterCategory.VIDEO to vid,
            TransferFilterCategory.FILE to doc,
        )
    }
    val filteredRecords = remember(records, selectedCategory) {
        if (selectedCategory == TransferFilterCategory.ALL) {
            records
        } else {
            records.filter { classifyTransferRecord(it) == selectedCategory }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 待续传（.part 断点保留管理卡）
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("⏸ 待续传断点缓存", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)
                        Text(
                            text = if (partials.isEmpty()) "无未完成的大文件碎片" else "${partials.size} 个任务 · 占用 ${FormatUtils.formatBytes(totalPartialBytes)}",
                            fontSize = 11.5.sp,
                            color = colors.textMuted,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = colors.accent, modifier = Modifier.size(17.dp))
                        }
                        if (partials.isNotEmpty()) {
                            TextButton(
                                onClick = onClearAllPartials,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text("全部清理", fontSize = 12.sp, color = colors.danger)
                            }
                        }
                    }
                }

                if (partials.isNotEmpty()) {
                    partials.forEach { item ->
                        val ratio = if (item.totalBytes > 0L) {
                            (item.savedBytes.toFloat() / item.totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        val pct = (ratio * 100f).toInt().coerceIn(0, 100)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surfaceSubtle)
                                .padding(11.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.fileName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = colors.textMain,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "$pct% · ${FormatUtils.formatBytes(item.savedBytes)} / ${FormatUtils.formatBytes(item.totalBytes)}" +
                                            if (item.roomCode.isNotBlank()) " · #${item.roomCode}" else "",
                                        fontSize = 11.sp,
                                        color = colors.textMuted,
                                    )
                                }
                                IconButton(onClick = { onDeletePartial(item.partFileName) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "删除断点", tint = colors.danger, modifier = Modifier.size(17.dp))
                                }
                            }
                            LinearProgressIndicator(
                                progress = { ratio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(99.dp)),
                                color = colors.amber,
                                trackColor = colors.surface,
                            )
                            Text("重新收到同文件时将自动从断点位置接着传", fontSize = 10.5.sp, color = colors.textMuted)
                        }
                    }
                }
            }
        }

        // 已收发文件历史记录卡（顶部带 全部 / 图片 / 视频 / 文件 快捷分类胶囊）
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("✅ 已收发文件记录", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)
                        Text("接收文件存放在系统 Download/BayChat", fontSize = 11.sp, color = colors.textMuted)
                    }
                    if (records.isNotEmpty()) {
                        TextButton(
                            onClick = onClearRecords,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text("清空", fontSize = 12.sp, color = colors.textMuted)
                        }
                    }
                }

                // 快捷分类筛选胶囊：全部 / 图片 / 视频 / 文件
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    TransferFilterCategory.entries.forEach { cat ->
                        val picked = selectedCategory == cat
                        val count = categoryCounts[cat] ?: 0
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (picked) colors.accent else colors.surfaceSubtle)
                                .border(
                                    width = 1.dp,
                                    color = if (picked) colors.accent else colors.border,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable {
                                    if (selectedCategory != cat) {
                                        selectedCategory = cat
                                        BayHaptic.tap(view, hapticEnabled)
                                    }
                                }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (count > 0 && cat != TransferFilterCategory.ALL) "${cat.label} $count" else cat.label,
                                fontSize = 11.5.sp,
                                fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium,
                                color = if (picked) Color.White else colors.textSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (filteredRecords.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surfaceSubtle)
                            .padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (records.isEmpty()) {
                                "还没有收发记录，进房互传文件后会自动汇总在这里"
                            } else {
                                "「${selectedCategory.label}」分类下暂无传输记录"
                            },
                            fontSize = 12.sp,
                            color = colors.textMuted,
                        )
                        if (records.isNotEmpty() && selectedCategory != TransferFilterCategory.ALL) {
                            OutlinedButton(
                                onClick = {
                                    selectedCategory = TransferFilterCategory.ALL
                                    BayHaptic.tap(view, hapticEnabled)
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text("查看全部记录", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                            }
                        }
                    }
                } else {
                    filteredRecords.take(40).forEach { rec ->
                        val cat = classifyTransferRecord(rec)
                        val typeIcon = when (cat) {
                            TransferFilterCategory.IMAGE -> Icons.Rounded.Image
                            TransferFilterCategory.VIDEO -> Icons.Rounded.Videocam
                            else -> Icons.Rounded.InsertDriveFile
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surfaceSubtle)
                                .clickable {
                                    BayHaptic.tap(view, hapticEnabled)
                                    onOpenRecord(rec)
                                }
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (rec.direction == "send") colors.accentSoft else colors.emerald.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = typeIcon,
                                        contentDescription = null,
                                        tint = if (rec.direction == "send") colors.accent else colors.emerald,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = if (rec.direction == "send") "↑" else "↓",
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (rec.direction == "send") colors.accent else colors.emerald,
                                            fontSize = 12.5.sp,
                                        )
                                        Text(
                                            text = rec.fileName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = colors.textMain,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        text = "${FormatUtils.formatBytes(rec.fileSize)} · #${rec.roomCode} · ${FormatUtils.formatRelativeTime(rec.timestamp)}" +
                                            if (rec.verified) " · 已校验" else "",
                                        fontSize = 11.sp,
                                        color = colors.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (rec.direction == "receive") {
                                    IconButton(onClick = { onOpenRecord(rec) }, modifier = Modifier.size(30.dp)) {
                                        Icon(Icons.Rounded.FileOpen, contentDescription = "打开", tint = colors.accent, modifier = Modifier.size(16.dp))
                                    }
                                }
                                IconButton(onClick = { onRemoveRecord(rec.id) }, modifier = Modifier.size(30.dp)) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "移除记录", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
    }
}

// ============================================================================
// Tab 3「👤 我的」：个人身份卡 / 主题皮肤 / 提示音与触感 / OTA 更新中心 / 传输默认 / 系统节点
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MineTabContent(
    state: BayChatUiState,
    onUpdateProfile: (String, String) -> Unit,
    onSelectTheme: (AppThemeMode) -> Unit,
    onToggleSound: (Boolean) -> Unit,
    onToggleHaptic: (Boolean) -> Unit,
    onUpdatePrefs: (AppUserPrefs) -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onClearAllPartialTransfers: () -> Unit,
    onCheckUpdate: () -> Unit,
    onStartOrResumeUpdate: () -> Unit,
    onPauseUpdate: () -> Unit,
    onInstallReadyUpdate: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
    onToggleAutoDownloadOnWifi: (Boolean) -> Unit,
    onToast: (String) -> Unit,
) {
    val colors = LocalBayColors.current
    val view = LocalView.current

    var editExpanded by remember { mutableStateOf(false) }
    var draftName by remember(state.profile.name) { mutableStateOf(state.profile.name) }
    var draftAvatar by remember(state.profile.avatar) { mutableStateOf(state.profile.avatar) }
    var showServerEditor by remember { mutableStateOf(false) }
    var draftServerUrl by remember(state.prefs.serverUrl) { mutableStateOf(state.prefs.serverUrl) }
    var showPrivacyDialog by remember { mutableStateOf(false) }

    val totalPartialBytes = state.partialTransfers.sumOf { it.savedBytes }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. 个人身份卡片（支持折叠/展开编辑昵称、随机骰子与萌趣头像网格，实时同步）
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(15.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editExpanded = !editExpanded
                            BayHaptic.tap(view, state.prefs.hapticEnabled)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .background(Brush.linearGradient(listOf(colors.accentSoft, colors.accentSecondary.copy(alpha = 0.18f))))
                                .border(1.5.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(17.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(FormatUtils.presetEmoji(if (editExpanded) draftAvatar else state.profile.avatar), fontSize = 27.sp)
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = (if (editExpanded) draftName.trim().ifBlank { "匿名伙伴" } else state.profile.name),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.5.sp,
                                color = colors.textMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "免注册本机身份 · 改完进房实时同步",
                                fontSize = 11.5.sp,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(if (editExpanded) colors.accentSoft else colors.surfaceSubtle)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            imageVector = if (editExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.Edit,
                            contentDescription = "编辑资料",
                            tint = colors.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = if (editExpanded) "收起" else "编辑",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.accent,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = editExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HorizontalDivider(color = colors.border)

                        // 昵称输入 + 随机骰子
                        OutlinedTextField(
                            value = draftName,
                            onValueChange = { if (it.length <= 24) draftName = it },
                            label = { Text("显示昵称 (${draftName.trim().length}/24)") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                TextButton(
                                    onClick = {
                                        draftName = FormatUtils.generateRandomName()
                                        BayHaptic.tap(view, state.prefs.hapticEnabled)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                ) {
                                    Icon(Icons.Rounded.Casino, contentDescription = "随机昵称", tint = colors.accent, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("随机", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                                }
                            },
                        )

                        // 萌趣头像网格
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("选择头像", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FormatUtils.PRESET_AVATARS.forEach { (id, emoji) ->
                                    val picked = draftAvatar == id
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(13.dp))
                                            .background(if (picked) colors.accentSoft else colors.surfaceSubtle)
                                            .border(
                                                width = if (picked) 2.dp else 1.dp,
                                                color = if (picked) colors.accent else colors.border,
                                                shape = RoundedCornerShape(13.dp),
                                            )
                                            .clickable {
                                                draftAvatar = id
                                                BayHaptic.tap(view, state.prefs.hapticEnabled)
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(emoji, fontSize = 20.sp)
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                onUpdateProfile(draftName, draftAvatar)
                                BayHaptic.confirm(view, state.prefs.hapticEnabled)
                                editExpanded = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        ) {
                            Text("保存身份资料", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }

        // 2. 🎨 主题皮肤（4 套精选色球一键切换 + 当前风格名）
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Icon(Icons.Rounded.Palette, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Text("🎨 主题皮肤", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)
                    }
                    Text(
                        text = state.themeMode.label,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(colors.accentSoft)
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FEATURED_THEMES.forEach { mode ->
                        val picked = state.themeMode == mode
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (picked) colors.accentSoft else colors.surfaceSubtle)
                                .border(
                                    width = if (picked) 1.5.dp else 1.dp,
                                    color = if (picked) colors.accent else colors.border,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .clickable {
                                    BayHaptic.tap(view, state.prefs.hapticEnabled)
                                    onSelectTheme(mode)
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(themeOrbColor(mode))
                                    .border(
                                        width = if (picked) 2.dp else 1.dp,
                                        color = if (picked) colors.textMain else Color.White.copy(alpha = 0.7f),
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (picked) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                }
                            }
                            Text(
                                text = mode.label,
                                fontSize = 11.sp,
                                fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium,
                                color = if (picked) colors.accent else colors.textSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 3. 🔊 交互体验（清脆提示音开关 + 细腻触感震动开关）
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("🔊 交互体验", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)

                MineSwitchRow(
                    icon = if (state.prefs.soundEnabled) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                    title = "清脆提示音",
                    subtitle = "进房、新消息、传输与更新就绪时播放（静音模式自动静默）",
                    checked = state.prefs.soundEnabled,
                    onCheckedChange = { enabled ->
                        BayHaptic.tap(view, state.prefs.hapticEnabled)
                        onToggleSound(enabled)
                    },
                )
                HorizontalDivider(color = colors.border)
                MineSwitchRow(
                    icon = Icons.Rounded.Vibration,
                    title = "细腻触感震动",
                    subtitle = "切页、下拉刷新与悬浮球磁吸操作时提供轻柔震感",
                    checked = state.prefs.hapticEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) BayHaptic.confirm(view, true)
                        onToggleHaptic(enabled)
                    },
                )
            }
        }

        // 4. 🚀 企业级自动更新中心（完整接入 OtaUpdateState + Wi-Fi 自动后台下载开关）
        OtaUpdateCenterCard(
            otaState = state.otaState,
            autoDownloadOnWifi = state.prefs.autoDownloadOnWifi,
            onCheckUpdate = onCheckUpdate,
            onStartOrResumeUpdate = onStartOrResumeUpdate,
            onPauseUpdate = onPauseUpdate,
            onInstallReadyUpdate = onInstallReadyUpdate,
            onOpenUpdateDialog = onOpenUpdateDialog,
            onToggleAutoDownloadOnWifi = { enabled ->
                BayHaptic.tap(view, state.prefs.hapticEnabled)
                onToggleAutoDownloadOnWifi(enabled)
            },
        )

        // 5. ⚡ 传输默认设置（自动接收 ≤50MB 小文件 + 发送图片默认原图）
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("⚡ 传输默认设置", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)

                MineSwitchRow(
                    icon = Icons.Rounded.Download,
                    title = "自动接收 ≤${state.prefs.autoAcceptMaxMB}MB 小文件",
                    subtitle = "收到伙伴发来的小文件时免手动确认，直接极速落盘",
                    checked = state.prefs.autoAcceptSmallFiles,
                    onCheckedChange = { enabled ->
                        BayHaptic.tap(view, state.prefs.hapticEnabled)
                        onUpdatePrefs(state.prefs.copy(autoAcceptSmallFiles = enabled))
                    },
                )
                HorizontalDivider(color = colors.border)
                MineSwitchRow(
                    icon = Icons.Rounded.Image,
                    title = "发送图片默认原图",
                    subtitle = "保留原始画质直传；关闭后将自动压缩省流发送",
                    checked = state.prefs.imageOriginalMode,
                    onCheckedChange = { enabled ->
                        BayHaptic.tap(view, state.prefs.hapticEnabled)
                        onUpdatePrefs(state.prefs.copy(imageOriginalMode = enabled))
                    },
                )
            }
        }

        // 6. 🛠️ 系统与节点（自建服务器地址可折叠修改 / 一键清理断点 / 版本与隐私合规说明）
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("🛠️ 系统与节点", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)

                // 自建服务器节点（可折叠修改）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceSubtle)
                        .clickable {
                            showServerEditor = !showServerEditor
                            BayHaptic.tap(view, state.prefs.hapticEnabled)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Dns, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                        Text("自建服务器节点", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = state.prefs.serverUrl.removePrefix("https://").removePrefix("http://"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = if (showServerEditor) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            tint = colors.textMuted,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showServerEditor,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draftServerUrl,
                            onValueChange = { draftServerUrl = it },
                            label = { Text("服务器地址（https://...）") },
                            placeholder = { Text(BuildConfig.DEFAULT_SERVER_URL) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    draftServerUrl = BuildConfig.DEFAULT_SERVER_URL
                                    onUpdateServerUrl(BuildConfig.DEFAULT_SERVER_URL)
                                    onToast("已恢复默认官方节点")
                                    showServerEditor = false
                                },
                                modifier = Modifier.weight(1f).height(38.dp),
                                shape = RoundedCornerShape(11.dp),
                            ) {
                                Text("恢复默认", fontSize = 12.5.sp, color = colors.textSub)
                            }
                            Button(
                                onClick = {
                                    val clean = draftServerUrl.trim().trimEnd('/')
                                    if (clean.startsWith("http://") || clean.startsWith("https://")) {
                                        onUpdateServerUrl(clean)
                                        BayHaptic.confirm(view, state.prefs.hapticEnabled)
                                        onToast("已切换直连节点为 $clean")
                                        showServerEditor = false
                                    } else {
                                        onToast("请输入以 http:// 或 https:// 开头的有效地址")
                                    }
                                },
                                modifier = Modifier.weight(1f).height(38.dp),
                                shape = RoundedCornerShape(11.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            ) {
                                Text("保存节点", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 一键清理未完成断点
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceSubtle)
                        .clickable(enabled = state.partialTransfers.isNotEmpty()) {
                            BayHaptic.confirm(view, state.prefs.hapticEnabled)
                            onClearAllPartialTransfers()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Storage, contentDescription = null, tint = colors.amber, modifier = Modifier.size(18.dp))
                        Column {
                            Text("一键清理未完成断点", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
                            Text(
                                text = if (state.partialTransfers.isEmpty()) {
                                    "暂无残留 .part 传输碎片"
                                } else {
                                    "${state.partialTransfers.size} 个未完成碎片 · 占用 ${FormatUtils.formatBytes(totalPartialBytes)}"
                                },
                                fontSize = 11.sp,
                                color = colors.textMuted,
                            )
                        }
                    }
                    Text(
                        text = if (state.partialTransfers.isEmpty()) "很干净" else "立即清理",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (state.partialTransfers.isEmpty()) colors.emerald else colors.danger,
                    )
                }

                // 当前版本与隐私合规说明
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceSubtle)
                        .clickable {
                            BayHaptic.tap(view, state.prefs.hapticEnabled)
                            showPrivacyDialog = true
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = colors.emerald, modifier = Modifier.size(18.dp))
                        Column {
                            Text("隐私合规与版本信息", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
                            Text("湾聊 BayChat v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})", fontSize = 11.sp, color = colors.textMuted)
                        }
                    }
                    Text("查看说明 ›", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.accent)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            containerColor = colors.surface,
            shape = RoundedCornerShape(22.dp),
            title = {
                Text("隐私合规与安全承诺", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = colors.textMain)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "当前版本：湾聊 BayChat v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.accent,
                    )
                    Text(
                        text = "• 免注册极简隐私：不收集手机号、通讯录、定位与广告标识符，身份资料仅存本机。\n" +
                            "• 端到端加密传输：文件与消息优先通过 WebRTC DTLS-SRTP P2P 直连发送，支持全文件 SHA-256 完整性校验。\n" +
                            "• 数据自主可控：支持「阅后即焚（服务器零留存）」或 1~7 天保鲜期，房内随时一键清空或销毁。\n" +
                            "• 零权限滥用：仅在扫码时请求相机权限，仅在后台直传/更新时使用前台通知保活。",
                        fontSize = 12.5.sp,
                        color = colors.textSub,
                        lineHeight = 19.sp,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPrivacyDialog = false },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    Text("我知道了", fontWeight = FontWeight.Bold)
                }
            },
        )
    }
}

@Composable
private fun MineSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalBayColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (checked) colors.accentSoft else colors.surfaceSubtle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) colors.accent else colors.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column {
                Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
                Text(subtitle, fontSize = 11.sp, color = colors.textMuted, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
        )
    }
}

// 企业级 OTA 自动更新中心卡片（置于 Tab 3「我的」，支持全状态机控制 + Wi-Fi 自动后台下载开关）
@Composable
private fun OtaUpdateCenterCard(
    otaState: OtaUpdateState,
    autoDownloadOnWifi: Boolean,
    onCheckUpdate: () -> Unit,
    onStartOrResumeUpdate: () -> Unit,
    onPauseUpdate: () -> Unit,
    onInstallReadyUpdate: () -> Unit,
    onOpenUpdateDialog: () -> Unit,
    onToggleAutoDownloadOnWifi: (Boolean) -> Unit,
) {
    val colors = LocalBayColors.current
    val release = otaState.release
    val isReady = otaState.status == OtaUpdateStatus.READY_TO_INSTALL && release != null
    val isError = otaState.status == OtaUpdateStatus.ERROR && release != null

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isReady) colors.emerald.copy(alpha = 0.08f) else colors.surface,
        ),
        border = BorderStroke(
            width = if (isReady) 1.3.dp else 1.dp,
            color = when {
                isReady -> colors.emerald.copy(alpha = 0.55f)
                isError -> colors.danger.copy(alpha = 0.45f)
                else -> colors.border
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = release != null) { onOpenUpdateDialog() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(
                                when {
                                    isReady -> colors.emerald.copy(alpha = 0.16f)
                                    isError -> colors.danger.copy(alpha = 0.14f)
                                    else -> colors.accentSoft
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                isReady -> Icons.Rounded.VerifiedUser
                                isError -> Icons.Rounded.Warning
                                else -> Icons.Rounded.SystemUpdate
                            },
                            contentDescription = null,
                            tint = when {
                                isReady -> colors.emerald
                                isError -> colors.danger
                                else -> colors.accent
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column {
                        Text(
                            text = when {
                                isReady && release != null -> "🚀 v${release.versionName} 已就绪"
                                release != null -> "🚀 自动更新 · v${BuildConfig.VERSION_NAME} → v${release.versionName}"
                                else -> "🚀 自动更新中心 · v${BuildConfig.VERSION_NAME}"
                            },
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = if (isReady) colors.emerald else colors.textMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subText = when (otaState.status) {
                            OtaUpdateStatus.CHECKING -> "正在检查最新版本..."
                            OtaUpdateStatus.AVAILABLE -> if (otaState.hasPartialBreakpoint) {
                                "已保留断点 ${otaState.progressPercent}% (${FormatUtils.formatBytes(otaState.downloadedBytes)})"
                            } else {
                                "发现新版 · 支持断点续传与后台下载"
                            }
                            OtaUpdateStatus.DOWNLOADING -> buildString {
                                append("${otaState.progressPercent}% · ${FormatUtils.formatBytes(otaState.downloadedBytes)}")
                                if (otaState.totalBytes > 0L) append("/${FormatUtils.formatBytes(otaState.totalBytes)}")
                                if (otaState.speedBytesPerSec > 0L) append(" · ${FormatUtils.formatBytes(otaState.speedBytesPerSec)}/s")
                            }
                            OtaUpdateStatus.PAUSED -> "已暂停 · 保留断点 ${otaState.progressPercent}% (${FormatUtils.formatBytes(otaState.downloadedBytes)})"
                            OtaUpdateStatus.VERIFYING -> "正在校验 SHA-256 完整性..."
                            OtaUpdateStatus.READY_TO_INSTALL -> "已完成 SHA-256 校验 · 0 秒一键安装"
                            OtaUpdateStatus.ERROR -> otaState.errorMessage ?: "下载中断，已保留断点"
                            OtaUpdateStatus.IDLE -> "应用内直连断点续传升级 · 点击立即检查"
                        }
                        Text(
                            text = subText,
                            fontSize = 11.5.sp,
                            color = when (otaState.status) {
                                OtaUpdateStatus.READY_TO_INSTALL -> colors.emerald
                                OtaUpdateStatus.ERROR -> colors.danger
                                OtaUpdateStatus.PAUSED -> colors.amber
                                OtaUpdateStatus.AVAILABLE, OtaUpdateStatus.DOWNLOADING, OtaUpdateStatus.VERIFYING -> colors.accent
                                else -> colors.textMuted
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                when (otaState.status) {
                    OtaUpdateStatus.IDLE, OtaUpdateStatus.CHECKING -> {
                        OutlinedButton(
                            onClick = onCheckUpdate,
                            enabled = otaState.status != OtaUpdateStatus.CHECKING,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(if (otaState.status == OtaUpdateStatus.CHECKING) "检查中..." else "检查更新", fontSize = 12.sp)
                        }
                    }

                    OtaUpdateStatus.AVAILABLE -> {
                        Button(
                            onClick = onStartOrResumeUpdate,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        ) {
                            Text(
                                text = if (otaState.hasPartialBreakpoint) "继续 (${otaState.progressPercent}%)" else "后台下载",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    OtaUpdateStatus.DOWNLOADING -> {
                        OutlinedButton(
                            onClick = onPauseUpdate,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.accent)
                            Spacer(Modifier.width(3.dp))
                            Text("暂停", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                        }
                    }

                    OtaUpdateStatus.PAUSED -> {
                        Button(
                            onClick = onStartOrResumeUpdate,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.amber),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("继续下载", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OtaUpdateStatus.VERIFYING -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
                    }

                    OtaUpdateStatus.READY_TO_INSTALL -> {
                        Button(
                            onClick = onInstallReadyUpdate,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.emerald),
                        ) {
                            Text("一键安装", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OtaUpdateStatus.ERROR -> {
                        Button(
                            onClick = onStartOrResumeUpdate,
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                        ) {
                            Text("断点重试", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 动态进度条（下载中 / 暂停 / 校验中）
            when (otaState.status) {
                OtaUpdateStatus.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { otaState.progressFraction.coerceIn(0.02f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(99.dp)),
                        color = colors.accent,
                        trackColor = colors.accentSoft,
                    )
                    Text(
                        text = if (otaState.attempt > 1) {
                            "断点续传第 ${otaState.attempt} 次重连中 · 支持切后台继续下载"
                        } else {
                            "支持切后台继续下载，完成后通知栏 0 秒一键安装"
                        },
                        fontSize = 10.5.sp,
                        color = colors.textMuted,
                    )
                }

                OtaUpdateStatus.PAUSED -> {
                    LinearProgressIndicator(
                        progress = { otaState.progressFraction.coerceIn(0.02f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(99.dp)),
                        color = colors.amber,
                        trackColor = colors.surfaceSubtle,
                    )
                }

                OtaUpdateStatus.VERIFYING -> {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(99.dp)),
                        color = colors.accent,
                        trackColor = colors.accentSoft,
                    )
                }

                else -> Unit
            }

            HorizontalDivider(color = colors.border)

            // Wi-Fi 自动后台下载更新开关（移动网络绝不偷跑流量）
            MineSwitchRow(
                icon = Icons.Rounded.Wifi,
                title = "Wi-Fi 自动后台下载更新",
                subtitle = "仅未计费 Wi-Fi 静默预下载，移动网络绝不偷跑流量",
                checked = autoDownloadOnWifi,
                onCheckedChange = onToggleAutoDownloadOnWifi,
            )
        }
    }
}

// 首页顶部企业级 OTA 实时状态横幅：全状态机覆盖 AVAILABLE / DOWNLOADING / PAUSED / VERIFYING / READY_TO_INSTALL / ERROR
@Composable
private fun UpdateBannerCard(
    ota: OtaUpdateState,
    onStartOrResume: () -> Unit,
    onPause: () -> Unit,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
    onOpenDetail: () -> Unit,
) {
    val colors = LocalBayColors.current
    val release = ota.release ?: return

    val containerBg = when (ota.status) {
        OtaUpdateStatus.READY_TO_INSTALL -> colors.emerald.copy(alpha = 0.14f)
        OtaUpdateStatus.PAUSED -> colors.amber.copy(alpha = 0.12f)
        OtaUpdateStatus.ERROR -> colors.danger.copy(alpha = 0.11f)
        else -> colors.accentSoft
    }
    val borderTint = when (ota.status) {
        OtaUpdateStatus.READY_TO_INSTALL -> colors.emerald.copy(alpha = 0.55f)
        OtaUpdateStatus.PAUSED -> colors.amber.copy(alpha = 0.45f)
        OtaUpdateStatus.ERROR -> colors.danger.copy(alpha = 0.45f)
        else -> colors.accent.copy(alpha = 0.4f)
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = BorderStroke(1.2.dp, borderTint),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetail() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (ota.status) {
                OtaUpdateStatus.AVAILABLE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🚀 发现新版本 v${release.versionName}" + if (ota.isForceUpdate) " · 必要更新" else "",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.5.sp,
                                color = colors.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val desc = when {
                                ota.hasPartialBreakpoint -> "已保留断点 ${ota.progressPercent}% (${FormatUtils.formatBytes(ota.downloadedBytes)} / ${FormatUtils.formatBytes(ota.totalBytes)})"
                                release.releaseNotes.isNotBlank() -> release.releaseNotes
                                release.sizeBytes > 0L -> "安装包大小 ${FormatUtils.formatBytes(release.sizeBytes)} · 支持后台断点下载"
                                else -> "支持后台静默下载与 SHA-256 校验"
                            }
                            Text(desc, fontSize = 11.5.sp, color = colors.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Button(
                                onClick = onStartOrResume,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            ) {
                                Text(
                                    text = if (ota.hasPartialBreakpoint) "继续下载 (${ota.progressPercent}%)" else "立即后台下载",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                            if (!ota.isForceUpdate) {
                                Text(
                                    text = "忽略此版本",
                                    fontSize = 11.sp,
                                    color = colors.textMuted,
                                    modifier = Modifier
                                        .clickable { onIgnore() }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }

                OtaUpdateStatus.DOWNLOADING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val speedLabel = if (ota.speedBytesPerSec > 0L) {
                                " · ${FormatUtils.formatBytes(ota.speedBytesPerSec)}/s"
                            } else ""
                            Text(
                                text = "⬇️ 正在更新 v${release.versionName} · ${ota.progressPercent}%$speedLabel",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = colors.accent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sizeLine = buildString {
                                append(FormatUtils.formatBytes(ota.downloadedBytes))
                                if (ota.totalBytes > 0L) append(" / ${FormatUtils.formatBytes(ota.totalBytes)}")
                                append(" · 支持切后台继续下载")
                            }
                            Text(sizeLine, fontSize = 11.sp, color = colors.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onPause,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.5f)),
                        ) {
                            Icon(Icons.Rounded.Pause, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("暂停", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { ota.progressFraction.coerceIn(0.02f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(99.dp)),
                        color = colors.accent,
                        trackColor = colors.surface,
                    )
                }

                OtaUpdateStatus.PAUSED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⏸ v${release.versionName} 下载已暂停 · ${ota.progressPercent}%",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = colors.amber,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "已安全保留断点 ${FormatUtils.formatBytes(ota.downloadedBytes)}" +
                                    if (ota.totalBytes > 0L) " / ${FormatUtils.formatBytes(ota.totalBytes)}" else "",
                                fontSize = 11.5.sp,
                                color = colors.textSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onStartOrResume,
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.amber),
                        ) {
                            Text("继续断点下载", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { ota.progressFraction.coerceIn(0.02f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(99.dp)),
                        color = colors.amber,
                        trackColor = colors.surface,
                    )
                }

                OtaUpdateStatus.VERIFYING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🛡️ 正在校验 v${release.versionName} 安装包",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = colors.accent,
                            )
                            Text("正在校验 SHA-256 完整性...", fontSize = 11.5.sp, color = colors.textSub)
                        }
                    }
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(99.dp)),
                        color = colors.accent,
                        trackColor = colors.surface,
                    )
                }

                OtaUpdateStatus.READY_TO_INSTALL -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🎉 v${release.versionName} 已就绪 · 0 秒一键安装",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.5.sp,
                                color = colors.emerald,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "已完成全文件 SHA-256 安全校验，点击立即覆盖升级",
                                fontSize = 11.5.sp,
                                color = colors.textSub,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onInstall,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.emerald),
                        ) {
                            Text("一键安装", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }

                OtaUpdateStatus.ERROR -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚠️ v${release.versionName} 更新中断",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                color = colors.danger,
                            )
                            val errText = buildString {
                                append(ota.errorMessage ?: "网络连接中断")
                                if (ota.hasPartialBreakpoint) {
                                    append("（已保留 ${ota.progressPercent}% 断点）")
                                }
                            }
                            Text(errText, fontSize = 11.5.sp, color = colors.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onStartOrResume,
                            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                        ) {
                            Text("断点续传重试", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}

// 首页企业级 OTA 更新弹窗（支持版本亮点、断点续传实时进度、网速、暂停/继续、忽略此版本与强制升级保护）
@Composable
fun UpdateAvailableDialog(
    ota: OtaUpdateState,
    onStartOrResume: () -> Unit,
    onPause: () -> Unit,
    onInstall: () -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalBayColors.current
    val release = ota.release ?: return

    AlertDialog(
        onDismissRequest = {
            if (!ota.isForceUpdate) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !ota.isForceUpdate,
            dismissOnClickOutside = !ota.isForceUpdate,
        ),
        containerColor = colors.surface,
        shape = RoundedCornerShape(22.dp),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (ota.status == OtaUpdateStatus.READY_TO_INSTALL) {
                                colors.emerald.copy(alpha = 0.16f)
                            } else {
                                colors.accentSoft
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (ota.status == OtaUpdateStatus.READY_TO_INSTALL) {
                            Icons.Rounded.VerifiedUser
                        } else {
                            Icons.Rounded.Download
                        },
                        contentDescription = null,
                        tint = if (ota.status == OtaUpdateStatus.READY_TO_INSTALL) colors.emerald else colors.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "发现新版本 v${release.versionName}",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = colors.textMain,
                        )
                        if (ota.isForceUpdate) {
                            Text(
                                text = "必要升级",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.danger)
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    val metaLine = buildString {
                        append("当前 v${BuildConfig.VERSION_NAME}")
                        if (release.sizeBytes > 0L) append(" · 包体 ${FormatUtils.formatBytes(release.sizeBytes)}")
                    }
                    Text(metaLine, fontSize = 11.5.sp, color = colors.textMuted)
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceSubtle)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("更新内容", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                    Text(
                        text = release.releaseNotes.trim().ifBlank { "• 性能优化与连接稳定性提升\n• 企业级后台断点续传更新支持" },
                        fontSize = 12.5.sp,
                        color = colors.textMain,
                        lineHeight = 19.sp,
                    )
                }

                when (ota.status) {
                    OtaUpdateStatus.AVAILABLE -> {
                        if (ota.hasPartialBreakpoint) {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    text = "已检测到本地断点 ${ota.progressPercent}%（${FormatUtils.formatBytes(ota.downloadedBytes)} / ${FormatUtils.formatBytes(ota.totalBytes)}），点击立即续传",
                                    fontSize = 12.sp,
                                    color = colors.accent,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                LinearProgressIndicator(
                                    progress = { ota.progressFraction.coerceIn(0.02f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(99.dp)),
                                    color = colors.accent,
                                    trackColor = colors.accentSoft,
                                )
                            }
                        } else {
                            Text(
                                text = "支持断点续传、主备源自动容灾、切后台保活与全文件 SHA-256 校验。",
                                fontSize = 11.5.sp,
                                color = colors.textMuted,
                            )
                        }
                    }

                    OtaUpdateStatus.DOWNLOADING -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "后台高速下载中 · ${ota.progressPercent}%",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.accent,
                                )
                                if (ota.speedBytesPerSec > 0L) {
                                    Text(
                                        text = "${FormatUtils.formatBytes(ota.speedBytesPerSec)}/s",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.textSub,
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { ota.progressFraction.coerceIn(0.02f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(99.dp)),
                                color = colors.accent,
                                trackColor = colors.accentSoft,
                            )
                            Text(
                                text = "${FormatUtils.formatBytes(ota.downloadedBytes)} / ${FormatUtils.formatBytes(ota.totalBytes)} · 支持切后台继续下载",
                                fontSize = 11.5.sp,
                                color = colors.textMuted,
                            )
                        }
                    }

                    OtaUpdateStatus.PAUSED -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "⏸ 下载已暂停 · 已保留断点 ${ota.progressPercent}%",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.amber,
                            )
                            LinearProgressIndicator(
                                progress = { ota.progressFraction.coerceIn(0.02f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(99.dp)),
                                color = colors.amber,
                                trackColor = colors.surfaceSubtle,
                            )
                            Text(
                                text = "已保存 ${FormatUtils.formatBytes(ota.downloadedBytes)}，点击下方按钮从断点无缝续传",
                                fontSize = 11.5.sp,
                                color = colors.textMuted,
                            )
                        }
                    }

                    OtaUpdateStatus.VERIFYING -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "🛡️ 正在校验 SHA-256 完整性...",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.accent,
                            )
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(99.dp)),
                                color = colors.accent,
                                trackColor = colors.accentSoft,
                            )
                        }
                    }

                    OtaUpdateStatus.READY_TO_INSTALL -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.emerald.copy(alpha = 0.14f))
                                .padding(10.dp),
                        ) {
                            Text(
                                text = "🎉 v${release.versionName} 已就绪 · 已通过 SHA-256 校验，点击 0 秒一键安装",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.emerald,
                            )
                        }
                    }

                    OtaUpdateStatus.ERROR -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.danger.copy(alpha = 0.11f))
                                .padding(10.dp),
                        ) {
                            val errDetail = buildString {
                                append(ota.errorMessage ?: "下载意外中断")
                                if (ota.hasPartialBreakpoint) {
                                    append("\n已安全保留 ${ota.progressPercent}% 断点，重试无需重头下载。")
                                }
                            }
                            Text(errDetail, fontSize = 12.sp, color = colors.danger, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (ota.status) {
                OtaUpdateStatus.AVAILABLE -> {
                    Button(
                        onClick = onStartOrResume,
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    ) {
                        Text(
                            text = if (ota.hasPartialBreakpoint) "继续下载 (${ota.progressPercent}%)" else "立即后台下载",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                OtaUpdateStatus.DOWNLOADING -> {
                    if (!ota.isForceUpdate) {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(11.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        ) {
                            Text("切后台继续下", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onPause,
                            shape = RoundedCornerShape(11.dp),
                        ) {
                            Text("暂停下载", fontWeight = FontWeight.Bold, color = colors.accent)
                        }
                    }
                }

                OtaUpdateStatus.PAUSED -> {
                    Button(
                        onClick = onStartOrResume,
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.amber),
                    ) {
                        Text("继续断点下载", fontWeight = FontWeight.Bold)
                    }
                }

                OtaUpdateStatus.VERIFYING -> {
                    Button(
                        onClick = {},
                        enabled = false,
                        shape = RoundedCornerShape(11.dp),
                    ) {
                        Text("校验中...")
                    }
                }

                OtaUpdateStatus.READY_TO_INSTALL -> {
                    Button(
                        onClick = onInstall,
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.emerald),
                    ) {
                        Text("0 秒一键安装", fontWeight = FontWeight.Bold)
                    }
                }

                OtaUpdateStatus.ERROR -> {
                    Button(
                        onClick = onStartOrResume,
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                    ) {
                        Text("断点续传重试", fontWeight = FontWeight.Bold)
                    }
                }

                else -> Unit
            }
        },
        dismissButton = {
            when (ota.status) {
                OtaUpdateStatus.AVAILABLE -> {
                    if (!ota.isForceUpdate) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onIgnore) {
                                Text("忽略此版本", color = colors.textMuted)
                            }
                            TextButton(onClick = onDismiss) {
                                Text("稍后再说", color = colors.textSub)
                            }
                        }
                    }
                }

                OtaUpdateStatus.DOWNLOADING -> {
                    if (!ota.isForceUpdate) {
                        TextButton(onClick = onPause) {
                            Text("暂停", color = colors.textSub)
                        }
                    }
                }

                OtaUpdateStatus.PAUSED, OtaUpdateStatus.READY_TO_INSTALL, OtaUpdateStatus.ERROR -> {
                    if (!ota.isForceUpdate) {
                        TextButton(onClick = onDismiss) {
                            Text("稍后再说", color = colors.textSub)
                        }
                    }
                }

                else -> Unit
            }
        },
    )
}

@Composable
private fun ModeSelectCard(
    modifier: Modifier,
    selected: Boolean,
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    badgeColors: List<Color>,
    onClick: () -> Unit,
) {
    val colors = LocalBayColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.accentSoft else colors.surfaceSubtle)
            .border(
                width = if (selected) 1.8.dp else 1.dp,
                color = if (selected) colors.accent else colors.border,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(badgeColors)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp, color = colors.textMain, maxLines = 1)
        Text(subtitle, fontSize = 11.sp, color = colors.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SavedRoomRow(
    room: SavedFixedRoom,
    onEnter: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalBayColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surfaceSubtle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text("#${room.code}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (room.hasPassword) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(13.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Button(
                onClick = onEnter,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Text("进入", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "复制链接", tint = colors.textSub, modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "移除", tint = colors.textMuted, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun RecentRoomRow(
    room: RecentRoom,
    onEnter: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalBayColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surfaceSubtle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("#${room.code}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.textMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (room.hasPassword) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(12.dp))
                }
            }
            Text(
                text = (if (room.mode == RoomMode.FIXED.wireValue) "📌 常驻" else "⚡ 闪传") +
                    " · ${FormatUtils.formatRelativeTime(room.lastVisitedAt)}",
                fontSize = 11.sp,
                color = colors.textMuted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Button(
                onClick = onEnter,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(30.dp),
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Text("重进", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "复制链接", tint = colors.textSub, modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "移除", tint = colors.textMuted, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun ErrorBanner(text: String) {
    val colors = LocalBayColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.danger.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(text, color = colors.danger, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

// 下拉刷新防抖阈值（1.5 秒）
private const val PULL_DEBOUNCE_MS = 1500L
