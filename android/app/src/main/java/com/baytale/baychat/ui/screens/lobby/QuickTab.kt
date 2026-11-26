package com.baytale.baychat.ui.screens.lobby

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.text.font.FontFamily
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.lan.LanDirectMessage
import com.baytale.baychat.core.lan.LanDirectPeer
import com.baytale.baychat.core.model.NearbyPeer
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.service.OtaUpdateStatus
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayActionSheet
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayGroup
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayLargeTitleHeader
import com.baytale.baychat.ui.design.BayListRow
import com.baytale.baychat.ui.design.BaySectionHeader
import com.baytale.baychat.ui.design.BaySwitch
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.RowTrailing
import com.baytale.baychat.ui.design.SheetAction
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.screens.update.UpdateBannerCard
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private const val QUICK_PULL_DEBOUNCE_MS = 1500L
private const val QUICK_MIN_REFRESH_MS = 350L
private const val QUICK_REFRESH_TIMEOUT_MS = 8000L

// UX-10：将 RoomOptionsDraft 持久化至 SavedState，防止旋转屏幕或后台重建丢失已填设置
private val RoomOptionsDraftSaver = listSaver<RoomOptionsDraft, Any>(
    save = { d ->
        listOf(
            d.mode.wireValue,
            d.retentionDays,
            d.maxFileSizeMB,
            d.password,
            d.topic,
            d.privatePairOnly,
            d.saveFixedFavorite,
        )
    },
    restore = { list ->
        RoomOptionsDraft(
            mode = RoomMode.fromWire(list[0] as String),
            retentionDays = list[1] as Int,
            maxFileSizeMB = list[2] as Int,
            password = list[3] as String,
            topic = list[4] as String,
            privatePairOnly = list[5] as Boolean,
            saveFixedFavorite = list[6] as Boolean,
        )
    },
)

/**
 * 「快传」主页：单入口智能直达台、场景快捷胶囊、同局域网附近设备与最近/收藏房间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickTab(
    state: BayChatUiState,
    vm: BayChatViewModel,
    nav: LobbyNav,
    onOpenPickupHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    var roomInput by rememberSaveable { mutableStateOf("") }
    var optionsDraft by rememberSaveable(stateSaver = RoomOptionsDraftSaver) {
        mutableStateOf(
            RoomOptionsDraft(
                maxFileSizeMB = state.serverConfig.defaultRoomMaxFileMB,
            )
        )
    }
    var showRoomOptions by rememberSaveable { mutableStateOf(false) }

    // UX-7：真实下拉刷新等待状态
    var pullRefreshing by remember { mutableStateOf(false) }
    var lastPullAt by remember { mutableLongStateOf(0L) }
    var pullStartedAt by remember { mutableLongStateOf(0L) }
    val latestLobbyRefreshing by rememberUpdatedState(state.isLobbyRefreshing)
    val latestHapticEnabled by rememberUpdatedState(state.prefs.hapticEnabled)

    // UX-28：常用房间更多操作菜单与编辑弹窗状态
    var actionTargetCode by rememberSaveable { mutableStateOf<String?>(null) }
    var renameDialogCode by rememberSaveable { mutableStateOf<String?>(null) }
    var renameInput by rememberSaveable { mutableStateOf("") }
    var passwordDialogCode by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordInput by rememberSaveable { mutableStateOf("") }

    // 离线局域网 mDNS 直连会话弹窗状态
    var activeLanPeerKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lanChatInput by rememberSaveable { mutableStateOf("") }
    val activeLanPeer = remember(activeLanPeerKey, state.lanDirectPeers) {
        activeLanPeerKey?.let { key -> state.lanDirectPeers.firstOrNull { it.peerKey == key } }
    }
    val lanFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val peer = activeLanPeer
        if (uri != null && peer != null) {
            vm.sendLanDirectFile(peer, uri)
        }
    }

    // 同步深链/外部跳转写入的待加入口令
    LaunchedEffect(state.pendingJoinCode) {
        val code = state.pendingJoinCode
        if (!code.isNullOrBlank() && !roomInput.trim().equals(code, ignoreCase = true)) {
            roomInput = code
            vm.consumePendingJoinCode()
        }
    }

    // UX-7：监听真实刷新完成再收起指示器并触发触感/音效（最小 350ms 防闪烁，8000ms 超时兜底）
    LaunchedEffect(pullRefreshing) {
        if (!pullRefreshing) return@LaunchedEffect
        delay(120L)
        withTimeoutOrNull(QUICK_REFRESH_TIMEOUT_MS) {
            snapshotFlow { latestLobbyRefreshing }.first { !it }
        }
        val elapsed = System.currentTimeMillis() - pullStartedAt
        if (elapsed < QUICK_MIN_REFRESH_MS) {
            delay(QUICK_MIN_REFRESH_MS - elapsed)
        }
        pullRefreshing = false
        BayHaptic.confirm(view, enabled = latestHapticEnabled)
        vm.playRefreshSound()
    }

    val submitRoomAction = {
        BayHaptic.tap(haptic, enabled = state.prefs.hapticEnabled)
        if (roomInput.trim().isNotEmpty()) {
            vm.enterOrCreateRoom(
                rawInput = roomInput,
                mode = optionsDraft.mode,
                password = optionsDraft.password,
                privatePairOnly = optionsDraft.privatePairOnly,
                retentionDays = optionsDraft.retentionDays,
                maxFileSizeMB = optionsDraft.maxFileSizeMB,
                saveFixedFavorite = optionsDraft.saveFixedFavorite,
                allowRandom = false,
                topic = optionsDraft.topic,
            )
        } else {
            val cleanPwd = optionsDraft.password.trim()
            if (cleanPwd.isNotEmpty() && (!cleanPwd.all { it.isDigit() } || cleanPwd.length < 5)) {
                vm.showToast("新建房间密码至少输入 5 位数字")
            } else {
                // 无输入时新建私密房（B1：默认 allowRandom = false）
                vm.createAndEnterRoom(
                    mode = optionsDraft.mode,
                    customCode = "",
                    password = cleanPwd,
                    privatePairOnly = optionsDraft.privatePairOnly,
                    retentionDays = optionsDraft.retentionDays,
                    maxFileSizeMB = optionsDraft.maxFileSizeMB,
                    saveFixedFavorite = optionsDraft.saveFixedFavorite,
                    allowRandom = false,
                    topic = optionsDraft.topic,
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BayLargeTitleHeader(
            title = "快传",
            subtitle = "${state.serverConfig.domain} · P2P 跨端直传",
            actions = {
                BayIconButton(
                    icon = Icons.Rounded.QrCodeScanner,
                    contentDescription = "扫一扫",
                    onClick = { nav.requestQrScan() },
                    style = BayIconStyle.Filled,
                )
                BayIconButton(
                    icon = Icons.Rounded.Inventory2,
                    contentDescription = "取件码",
                    onClick = onOpenPickupHub,
                    style = BayIconStyle.Filled,
                )
            },
        )

        PullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = {
                val now = System.currentTimeMillis()
                if (now - lastPullAt < QUICK_PULL_DEBOUNCE_MS) {
                    pullRefreshing = false
                    vm.showToast("刚刚刷新过，请稍候")
                } else {
                    lastPullAt = now
                    pullStartedAt = now
                    pullRefreshing = true
                    vm.refreshLobbyOverview()
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BaySpacing.lg,
                    end = BaySpacing.lg,
                    top = BaySpacing.xs,
                    bottom = BaySpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
            ) {
                // UX-14：断网状态提示条
                if (state.isNetworkOffline) {
                    item(key = "network_offline_banner") {
                        OfflineNoticeStrip()
                    }
                }

                // 1. 应用自动更新横幅
                val ota = state.otaState
                val isIgnored = ota.release != null && state.prefs.ignoredVersionCode == ota.release.versionCode
                val showUpdateBanner = ota.release != null &&
                    ota.status != OtaUpdateStatus.IDLE &&
                    ota.status != OtaUpdateStatus.CHECKING &&
                    (!isIgnored || ota.status != OtaUpdateStatus.AVAILABLE)

                if (showUpdateBanner) {
                    item(key = "ota_banner") {
                        UpdateBannerCard(
                            ota = ota,
                            onStartOrResume = { vm.startOrResumeAppUpdate(silent = false) },
                            onPause = { vm.pauseAppUpdate() },
                            onInstall = { vm.installReadyAppUpdate() },
                            onIgnore = { vm.ignoreCurrentUpdate() },
                            onOpenDetail = { vm.openUpdateDialog() },
                        )
                    }
                }

                // 2. 外部系统分享待发送提示条
                if (state.pendingShare.hasContent) {
                    item(key = "pending_share") {
                        PendingShareNoticeCard(
                            fileCount = state.pendingShare.uris.size,
                            hasText = !state.pendingShare.sharedText.isNullOrBlank(),
                            hasActiveRoom = state.activeRoom != null,
                            onSendNow = {
                                vm.sendPendingShareNow()
                                nav.restoreRoom()
                            },
                            onClear = { vm.clearPendingShare() },
                        )
                    }
                }

                // 2.5 P2PLink 本机节点与端到端加密状态卡
                item(key = "p2p_node_card") {
                    P2PNodeStatusCard(
                        avatar = state.profile.avatar,
                        name = state.profile.name,
                        clientId = state.profile.clientId,
                        nearbyDiscoverable = state.prefs.nearbyDiscoverable,
                        nearbyCount = state.nearbyPeers.size,
                        onOpenMine = { nav.switchTab(3) },
                    )
                }

                // 3. 单入口核心直达卡片
                item(key = "quick_entry_card") {
                    QuickEntryCard(
                        roomInput = roomInput,
                        onRoomInputChange = { next ->
                            if (next.length <= 160) {
                                roomInput = next
                                vm.onJoinCodeInputChanged(next)
                                vm.clearLobbyError()
                            }
                        },
                        optionsDraft = optionsDraft,
                        onPasswordChange = { pwd ->
                            optionsDraft = optionsDraft.copy(password = pwd.take(40))
                            vm.clearLobbyError()
                        },
                        needPassword = state.lobbyNeedPassword,
                        lobbyError = state.lobbyError,
                        busy = state.lobbyBusy,
                        onPasteClipboard = {
                            val clipText = readClipboardText(context)
                            val validCode = clipText?.let { FormatUtils.extractValidRoomCodeFromClipboard(it) }
                            if (validCode != null) {
                                roomInput = validCode
                                vm.onJoinCodeInputChanged(validCode)
                                vm.clearLobbyError()
                                BayHaptic.tap(haptic, enabled = state.prefs.hapticEnabled)
                                vm.showToast("已填入剪贴板口令")
                            } else {
                                vm.showToast("剪贴板中未识别到有效房间口令或链接")
                            }
                        },
                        onRequestQrScan = { nav.requestQrScan() },
                        onSubmit = submitRoomAction,
                        onOpenOptions = { showRoomOptions = true },
                    )
                }

                // 4. 三个一键场景胶囊（UX-5：忙碌时禁用防连点）
                item(key = "quick_actions") {
                    QuickScenarioRow(
                        lobbyBusy = state.lobbyBusy,
                        randomJoining = state.randomJoining,
                        onCreatePrivate = {
                            BayHaptic.tap(haptic, enabled = state.prefs.hapticEnabled)
                            vm.createAndEnterRoom(
                                mode = RoomMode.EPHEMERAL,
                                customCode = "",
                                password = "",
                                privatePairOnly = false,
                                retentionDays = 1,
                                maxFileSizeMB = state.serverConfig.defaultRoomMaxFileMB,
                                saveFixedFavorite = false,
                                allowRandom = false,
                                topic = "",
                            )
                        },
                        onOpenPickup = {
                            BayHaptic.tap(haptic, enabled = state.prefs.hapticEnabled)
                            onOpenPickupHub()
                        },
                        onRandomJoin = {
                            BayHaptic.tap(haptic, enabled = state.prefs.hapticEnabled)
                            vm.joinRandomPublicRoom(excludeCurrent = false)
                        },
                    )
                }

                // 5. 同局域网附近设备列表（UX-4 防连点、UX-15 移动网络区分提示、UX-33 友好设备名）
                if (state.prefs.nearbyDiscoverable) {
                    item(key = "nearby_peers_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                            BaySectionHeader(title = "附近设备")
                            if (state.nearbyPeers.isEmpty()) {
                                val isCellular = state.nearbyNetworkType.equals("cellular", ignoreCase = true)
                                BayCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.md)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(BayShapes.smShape)
                                                    .background(colors.fill),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Devices,
                                                    contentDescription = null,
                                                    tint = colors.textSecondary,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (isCellular) "当前使用移动网络" else "正在扫描同一局域网设备",
                                                    style = BayType.callout.copy(fontWeight = FontWeight.Medium),
                                                    color = colors.textPrimary,
                                                )
                                                Text(
                                                    text = if (isCellular) {
                                                        "同局域网设备发现需双方连接同一 Wi-Fi（仍可通过口令或取件码传输）"
                                                    } else {
                                                        "同网络下开启 BayChat 的设备将自动显示在此处"
                                                    },
                                                    style = BayType.caption,
                                                    color = colors.textSecondary,
                                                )
                                            }
                                        }
                                        if (isCellular) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.End,
                                            ) {
                                                BayButton(
                                                    text = "手动刷新",
                                                    onClick = {
                                                        vm.startNearbyPolling()
                                                        vm.refreshLobbyOverview()
                                                    },
                                                    style = BayButtonStyle.Secondary,
                                                    size = BayButtonSize.Small,
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                BayGroup {
                                    state.nearbyPeers.forEachIndexed { index, peer ->
                                        NearbyPeerRow(
                                            peer = peer,
                                            isInviting = state.invitingNearbyClientId == peer.clientId,
                                            inviteLocked = state.invitingNearbyClientId != null,
                                            showDivider = index < state.nearbyPeers.lastIndex,
                                            onInvite = { vm.inviteNearby(peer) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5.5 真·离线局域网 mDNS 直连（断外网零服务器可用）
                item(key = "lan_direct_mdns_section") {
                    LanDirectMdnsCard(
                        running = state.lanDirectRunning,
                        localEndpoint = state.lanLocalEndpoint,
                        peers = state.lanDirectPeers,
                        onToggle = { enabled -> vm.toggleLanDirect(enabled) },
                        onOpenPeer = { peer -> activeLanPeerKey = peer.peerKey },
                    )
                }

                // 6. 收藏常驻房间（UX-28：提供更多操作菜单）
                if (state.savedRooms.isNotEmpty()) {
                    item(key = "saved_rooms_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                            BaySectionHeader(title = "常用房间")
                            BayGroup {
                                state.savedRooms.forEachIndexed { index, room ->
                                    SavedFixedRoomRow(
                                        room = room,
                                        showDivider = index < state.savedRooms.lastIndex,
                                        onEnter = { vm.joinRoom(room.code, room.password) },
                                        onCopyLink = { copyInviteLink(context, vm, room.code) },
                                        onMore = { actionTargetCode = room.code },
                                    )
                                }
                            }
                        }
                    }
                }

                // 7. 最近房间
                if (state.recentRooms.isNotEmpty()) {
                    item(key = "recent_rooms_section") {
                        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
                            BaySectionHeader(
                                title = "最近房间",
                                actionLabel = "清空",
                                onAction = { vm.clearRecentRooms() },
                            )
                            BayGroup {
                                val displayed = state.recentRooms.take(6)
                                displayed.forEachIndexed { index, room ->
                                    RecentRoomItemRow(
                                        room = room,
                                        showDivider = index < displayed.lastIndex,
                                        onEnter = { vm.joinRoom(room.code, room.password) },
                                        onCopyLink = { copyInviteLink(context, vm, room.code) },
                                        onRemove = { vm.removeRecentRoom(room.code) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRoomOptions) {
        RoomOptionsSheet(
            draft = optionsDraft,
            maxAttachmentMB = state.serverConfig.maxAttachmentMB,
            maxRoomPeers = state.serverConfig.maxRoomPeers,
            onApply = { updated -> optionsDraft = updated },
            onDismiss = { showRoomOptions = false },
        )
    }

    if (activeLanPeer != null) {
        LanDirectPeerDialog(
            peer = activeLanPeer,
            messages = state.lanDirectMessages.filter { it.peerKey == activeLanPeer.peerKey },
            input = lanChatInput,
            onInputChange = { lanChatInput = it },
            onSendText = {
                val clean = lanChatInput.trim()
                if (clean.isNotEmpty()) {
                    vm.sendLanDirectText(activeLanPeer, clean)
                    lanChatInput = ""
                }
            },
            onPickFile = { lanFileLauncher.launch(arrayOf("*/*")) },
            onDismiss = { activeLanPeerKey = null },
        )
    }

    // UX-28：常用房间操作菜单（设置备注名 / 修改保存密码 / 复制链接 / 移除收藏）
    val actionRoom = actionTargetCode?.let { code ->
        state.savedRooms.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
    if (actionRoom != null) {
        val headerTitle = if (actionRoom.alias.isNotBlank()) {
            "${actionRoom.alias} (#${actionRoom.code})"
        } else {
            "房间 #${actionRoom.code}"
        }
        BayActionSheet(
            onDismiss = { actionTargetCode = null },
            title = headerTitle,
            actions = listOf(
                SheetAction(
                    label = "设置备注名称",
                    icon = Icons.Rounded.Edit,
                    onClick = {
                        renameInput = actionRoom.alias
                        renameDialogCode = actionRoom.code
                    },
                ),
                SheetAction(
                    label = "修改保存密码",
                    icon = Icons.Rounded.Lock,
                    onClick = {
                        passwordInput = actionRoom.password
                        passwordDialogCode = actionRoom.code
                    },
                ),
                SheetAction(
                    label = "复制邀请链接",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = { copyInviteLink(context, vm, actionRoom.code) },
                ),
                SheetAction(
                    label = "移除收藏",
                    icon = Icons.Rounded.DeleteOutline,
                    destructive = true,
                    onClick = { vm.removeSavedRoom(actionRoom.code) },
                ),
            ),
        )
    }

    // UX-28：设置备注名称弹窗
    val renameCode = renameDialogCode
    if (renameCode != null) {
        BayDialog(
            title = "设置备注名称",
            message = "为常用房间 #$renameCode 设置易于识别的名称，留空可清除备注。",
            confirmText = "保存",
            onConfirm = {
                vm.renameSavedRoom(renameCode, renameInput)
                renameDialogCode = null
            },
            dismissText = "取消",
            onDismiss = { renameDialogCode = null },
        ) {
            BayTextField(
                value = renameInput,
                onValueChange = { renameInput = it.take(24) },
                placeholder = "例如：办公室传输室",
                leadingIcon = Icons.Rounded.Edit,
            )
        }
    }

    // UX-28：修改本地保存密码弹窗
    val pwdCode = passwordDialogCode
    if (pwdCode != null) {
        BayDialog(
            title = "修改保存密码",
            message = "更新本地为房间 #$pwdCode 记住的访问密码，留空表示清除本地密码。",
            confirmText = "保存",
            onConfirm = {
                vm.updateSavedPassword(pwdCode, passwordInput)
                passwordDialogCode = null
            },
            dismissText = "取消",
            onDismiss = { passwordDialogCode = null },
        ) {
            BayTextField(
                value = passwordInput,
                onValueChange = { passwordInput = it.take(40) },
                placeholder = "输入访问密码（留空则清除）",
                leadingIcon = Icons.Rounded.Lock,
                isPassword = true,
            )
        }
    }
}

@Composable
private fun OfflineNoticeStrip() {
    val colors = BayTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BayShapes.mdShape)
            .background(colors.danger.copy(alpha = if (colors.isDark) 0.22f else 0.10f))
            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
    ) {
        Icon(
            imageVector = Icons.Rounded.WifiOff,
            contentDescription = null,
            tint = colors.danger,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "当前网络已断开，恢复后将自动重连",
            style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.danger,
        )
    }
}

@Composable
private fun PendingShareNoticeCard(
    fileCount: Int,
    hasText: Boolean,
    hasActiveRoom: Boolean,
    onSendNow: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = BayTheme.colors
    val desc = buildString {
        if (fileCount > 0) append("$fileCount 个文件/图片")
        if (hasText) {
            if (isNotEmpty()) append(" + ")
            append("一段文本")
        }
        append(if (hasActiveRoom) " · 可直接发送至当前房间" else " · 进入房间后发送")
    }

    BayCard(modifier = Modifier.fillMaxWidth(), padding = BaySpacing.md) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(BayShapes.smShape)
                        .background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Send,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "已接收外部待发内容",
                        style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                    Text(
                        text = desc,
                        style = BayType.caption,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
            ) {
                if (hasActiveRoom) {
                    BayButton(
                        text = "发送",
                        onClick = onSendNow,
                        style = BayButtonStyle.Primary,
                        size = BayButtonSize.Small,
                    )
                }
                BayIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = "清除待发内容",
                    onClick = onClear,
                    style = BayIconStyle.Plain,
                    size = 32.dp,
                )
            }
        }
    }
}

@Composable
private fun QuickEntryCard(
    roomInput: String,
    onRoomInputChange: (String) -> Unit,
    optionsDraft: RoomOptionsDraft,
    onPasswordChange: (String) -> Unit,
    needPassword: Boolean,
    lobbyError: String?,
    busy: Boolean,
    onPasteClipboard: () -> Unit,
    onRequestQrScan: () -> Unit,
    onSubmit: () -> Unit,
    onOpenOptions: () -> Unit,
) {
    val colors = BayTheme.colors
    val hasInput = roomInput.trim().isNotEmpty()

    BayCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "进入或新建房间",
                    style = BayType.headline,
                    color = colors.textPrimary,
                )
                Text(
                    text = if (hasInput) "存在即进 · 不存在可新建" else "留空新建私密房",
                    style = BayType.caption,
                    color = colors.accent,
                    modifier = Modifier
                        .clip(BayShapes.fullShape)
                        .background(colors.accentSoft)
                        .padding(horizontal = BaySpacing.sm, vertical = BaySpacing.xs),
                )
            }

            // 单入口输入栏（含清除与扫码）
            BayTextField(
                value = roomInput,
                onValueChange = onRoomInputChange,
                placeholder = "输入房间口令、名称或粘贴链接",
                large = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                    ) {
                        if (roomInput.isNotEmpty()) {
                            BayIconButton(
                                icon = Icons.Rounded.Close,
                                contentDescription = "清空输入",
                                onClick = { onRoomInputChange("") },
                                style = BayIconStyle.Plain,
                                size = 30.dp,
                            )
                        }
                        BayIconButton(
                            icon = Icons.Rounded.QrCodeScanner,
                            contentDescription = "扫码进入",
                            onClick = onRequestQrScan,
                            style = BayIconStyle.Accent,
                            size = 32.dp,
                        )
                    }
                },
            )

            // 剪贴板口令快贴胶囊
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BayChip(
                    label = "粘贴剪贴板口令",
                    selected = false,
                    leadingIcon = Icons.Rounded.ContentPaste,
                    onClick = onPasteClipboard,
                )
            }

            // 需要密码或已预设密码时滑出密码输入
            AnimatedVisibility(
                visible = needPassword || optionsDraft.password.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                BayTextField(
                    value = optionsDraft.password,
                    onValueChange = onPasswordChange,
                    placeholder = "输入房间访问密码",
                    leadingIcon = Icons.Rounded.Lock,
                    isPassword = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                )
            }

            if (!lobbyError.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BayShapes.smShape)
                        .background(colors.danger.copy(alpha = if (colors.isDark) 0.20f else 0.10f))
                        .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                ) {
                    Text(
                        text = lobbyError,
                        style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                        color = colors.danger,
                    )
                }
            }

            BayButton(
                text = if (hasInput) "加入房间" else "快速建房",
                onClick = onSubmit,
                style = BayButtonStyle.Primary,
                size = BayButtonSize.Large,
                loading = busy,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )

            // 折叠入口：打开房间高级选项 Sheet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bayClickable(onClick = onOpenOptions)
                    .clip(BayShapes.smShape)
                    .background(colors.fill)
                    .padding(horizontal = BaySpacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "房间高级选项",
                        style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    Text(
                        text = optionsDraft.summaryLabel(),
                        style = BayType.caption,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickScenarioRow(
    lobbyBusy: Boolean,
    randomJoining: Boolean,
    onCreatePrivate: () -> Unit,
    onOpenPickup: () -> Unit,
    onRandomJoin: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
    ) {
        ScenarioTile(
            icon = Icons.Rounded.Lock,
            label = "新建私密房",
            enabled = !lobbyBusy,
            onClick = onCreatePrivate,
            modifier = Modifier.weight(1f),
        )
        ScenarioTile(
            icon = Icons.Rounded.Inventory2,
            label = "取件码",
            onClick = onOpenPickup,
            modifier = Modifier.weight(1f),
        )
        ScenarioTile(
            icon = Icons.Rounded.Explore,
            label = if (randomJoining) "匹配中" else "随机串门",
            enabled = !randomJoining && !lobbyBusy,
            onClick = onRandomJoin,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ScenarioTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = BayTheme.colors
    Column(
        modifier = modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .bayClickable(enabled = enabled, onClick = onClick)
            .clip(BayShapes.mdShape)
            .background(colors.surface)
            .padding(vertical = BaySpacing.md, horizontal = BaySpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(BayShapes.smShape)
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = colors.accent,
                modifier = Modifier.size(19.dp),
            )
        }
        Text(
            text = label,
            style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NearbyPeerRow(
    peer: NearbyPeer,
    isInviting: Boolean,
    inviteLocked: Boolean,
    showDivider: Boolean,
    onInvite: () -> Unit,
) {
    val colors = BayTheme.colors
    val (deviceIcon, deviceLabel) = remember(peer.deviceType) {
        mapNearbyDeviceMeta(peer.deviceType)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bayClickable(enabled = !inviteLocked, onClick = onInvite)
                .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                modifier = Modifier.weight(1f),
            ) {
                BayAvatar(emojiId = peer.avatar, size = 38.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peer.name,
                        style = BayType.body,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = deviceIcon,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = "$deviceLabel · 同网络在线",
                            style = BayType.footnote,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(BaySpacing.sm))
            BayButton(
                text = if (isInviting) "邀请中" else "邀请连接",
                onClick = onInvite,
                style = BayButtonStyle.Secondary,
                size = BayButtonSize.Small,
                loading = isInviting,
                enabled = !inviteLocked,
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 66.dp)
                    .height(0.5.dp)
                    .background(colors.separator),
            )
        }
    }
}

private fun mapNearbyDeviceMeta(rawType: String): Pair<ImageVector, String> {
    val normalized = rawType.trim().lowercase(Locale.ROOT)
    return when {
        normalized in setOf("android", "ios", "mobile", "phone") ->
            Icons.Rounded.Smartphone to "手机端"
        normalized in setOf("web", "browser", "desktop", "pc", "mac", "windows", "linux") ->
            Icons.Rounded.Computer to "桌面浏览器"
        else ->
            Icons.Rounded.Devices to "附近设备"
    }
}

@Composable
private fun SavedFixedRoomRow(
    room: SavedFixedRoom,
    showDivider: Boolean,
    onEnter: () -> Unit,
    onCopyLink: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = BayTheme.colors
    val title = if (room.alias.isNotBlank()) "${room.alias} (#${room.code})" else "#${room.code}"
    val subtitle = buildString {
        append("常驻房间")
        if (room.hasPassword) append(" · 需密码")
        append(" · ${FormatUtils.formatRelativeTime(room.lastVisitedAt)}")
    }

    BayListRow(
        title = title,
        subtitle = subtitle,
        trailing = RowTrailing.Custom {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
            ) {
                if (room.hasPassword) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "已设密码",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                BayIconButton(
                    icon = Icons.Rounded.ContentCopy,
                    contentDescription = "复制邀请链接",
                    onClick = onCopyLink,
                    style = BayIconStyle.Plain,
                    size = 32.dp,
                )
                BayIconButton(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = "更多房间操作",
                    onClick = onMore,
                    style = BayIconStyle.Plain,
                    size = 32.dp,
                )
            }
        },
        showDivider = showDivider,
        onClick = onEnter,
    )
}

@Composable
private fun RecentRoomItemRow(
    room: RecentRoom,
    showDivider: Boolean,
    onEnter: () -> Unit,
    onCopyLink: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = BayTheme.colors
    val modeDesc = if (room.mode == RoomMode.FIXED.wireValue) "常驻房间" else "临时房间"
    val subtitle = buildString {
        append(modeDesc)
        if (room.hasPassword) append(" · 需密码")
        append(" · ${FormatUtils.formatRelativeTime(room.lastVisitedAt)}")
    }

    BayListRow(
        title = "#${room.code}",
        subtitle = subtitle,
        trailing = RowTrailing.Custom {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
            ) {
                if (room.hasPassword) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "已设密码",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                BayIconButton(
                    icon = Icons.Rounded.ContentCopy,
                    contentDescription = "复制邀请链接",
                    onClick = onCopyLink,
                    style = BayIconStyle.Plain,
                    size = 32.dp,
                )
                BayIconButton(
                    icon = Icons.Rounded.DeleteOutline,
                    contentDescription = "移除记录",
                    onClick = onRemove,
                    style = BayIconStyle.Plain,
                    size = 32.dp,
                )
            }
        },
        showDivider = showDivider,
        onClick = onEnter,
    )
}

private fun readClipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
}

private fun copyInviteLink(context: Context, vm: BayChatViewModel, code: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("BayChat", vm.buildInviteUrl(code)))
    vm.showToast("已复制 #$code 邀请链接")
}

/**
 * 与 Web 端左侧 P2PLink 节点卡视觉对齐的本机节点与端到端加密状态卡。
 */
@Composable
private fun P2PNodeStatusCard(
    avatar: String,
    name: String,
    clientId: String,
    nearbyDiscoverable: Boolean,
    nearbyCount: Int,
    onOpenMine: () -> Unit,
) {
    val colors = BayTheme.colors
    BayCard(
        onClick = onOpenMine,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.success),
                    )
                    Text(
                        text = "我的节点 (Android 端)",
                        style = BayType.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textSecondary,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(BayShapes.fullShape)
                        .background(colors.accentSoft)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = "AES-256-GCM · DTLS 1.3",
                        style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                        color = colors.accent,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
            ) {
                BayAvatar(emojiId = avatar, size = 38.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = BayType.callout.copy(fontWeight = FontWeight.Bold),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "ID: ${clientId.take(12)}...",
                        style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(BayShapes.fullShape)
                        .background(colors.fill)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Wifi,
                        contentDescription = null,
                        tint = if (nearbyDiscoverable) colors.accent else colors.textTertiary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = if (nearbyDiscoverable) "局域网 $nearbyCount 台" else "仅口令",
                        style = BayType.caption,
                        color = if (nearbyDiscoverable) colors.textPrimary else colors.textTertiary,
                    )
                }
            }
        }
    }
}

/**
 * 真·离线局域网 mDNS（_baychat._tcp.）发现与直连入口卡片（无需互联网与中心服务器）。
 */
@Composable
private fun LanDirectMdnsCard(
    running: Boolean,
    localEndpoint: String,
    peers: List<LanDirectPeer>,
    onToggle: (Boolean) -> Unit,
    onOpenPeer: (LanDirectPeer) -> Unit,
) {
    val colors = BayTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
        BaySectionHeader(title = "离线局域网直连 (mDNS)")
        BayCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.md)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(BayShapes.smShape)
                            .background(if (running) colors.accentSoft else colors.fill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Radar,
                            contentDescription = null,
                            tint = if (running) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (running) "mDNS 离线广播运行中" else "断外网局域网直连",
                            style = BayType.callout.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                        )
                        Text(
                            text = if (running && localEndpoint.isNotBlank()) {
                                "_baychat._tcp. · $localEndpoint"
                            } else {
                                "开启后断外网也可直连同 Wi-Fi 安卓端互传"
                            },
                            style = BayType.caption.copy(fontFamily = if (running) FontFamily.Monospace else FontFamily.Default),
                            color = if (running) colors.accent else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BaySwitch(
                        checked = running,
                        onCheckedChange = onToggle,
                    )
                }

                if (running) {
                    if (peers.isEmpty()) {
                        Text(
                            text = "正在扫描同 Wi-Fi 下开启离线直连的设备...",
                            style = BayType.caption,
                            color = colors.textTertiary,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.xs)) {
                            peers.forEach { peer ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(BayShapes.smShape)
                                        .background(colors.fill)
                                        .bayClickable { onOpenPeer(peer) }
                                        .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(colors.success),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = peer.displayName,
                                            style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                                            color = colors.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "${peer.host}:${peer.port}",
                                            style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                                            color = colors.textTertiary,
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .clip(BayShapes.fullShape)
                                            .background(colors.accentSoft)
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = "离线直传",
                                            style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                            color = colors.accent,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 离线局域网（无中心服务器 TCP Socket）点对点即时文本/文件收发弹窗。
 */
@Composable
private fun LanDirectPeerDialog(
    peer: LanDirectPeer,
    messages: List<LanDirectMessage>,
    input: String,
    onInputChange: (String) -> Unit,
    onSendText: () -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    BayDialog(
        title = "局域网直连 · ${peer.displayName}",
        confirmText = "关闭",
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(BaySpacing.sm)) {
            Text(
                text = "直连端点 ${peer.host}:${peer.port}（无中心服务器）",
                style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                color = colors.accent,
            )

            if (messages.isEmpty()) {
                Text(
                    text = "可直接在下方发送文字，或选择任意文件高速直传至对方手机",
                    style = BayType.footnote,
                    color = colors.textSecondary,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    messages.takeLast(5).forEach { msg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BayShapes.xsShape)
                                .background(if (msg.isSelf) colors.accentSoft else colors.fill)
                                .padding(horizontal = BaySpacing.sm, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (msg.isFile) {
                                Icon(
                                    imageVector = Icons.Rounded.InsertDriveFile,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                            Text(
                                text = if (msg.isFile) {
                                    "${if (msg.isSelf) "我发出" else "收到"} ${msg.fileName} (${FormatUtils.formatBytes(msg.fileSize)})${if (msg.verified) " · SHA-256✓" else ""}"
                                } else {
                                    "${if (msg.isSelf) "我" else msg.senderName}: ${msg.text}"
                                },
                                style = BayType.caption,
                                color = colors.textPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            BayTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = "输入局域网直连消息...",
                singleLine = true,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayButton(
                    text = "发文件",
                    onClick = onPickFile,
                    style = BayButtonStyle.Secondary,
                    size = BayButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
                BayButton(
                    text = "发文字",
                    onClick = onSendText,
                    style = BayButtonStyle.Primary,
                    size = BayButtonSize.Small,
                    enabled = input.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
