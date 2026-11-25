package com.baytale.baychat.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayActionSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayEmptyState
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.SheetAction
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MESSAGE_GROUP_GAP_MS = 120_000L

/**
 * 房间聊天主视图：整合顶部栏、断网/附近邀请提示条、搜索筛选、待发系统分享横幅、消息流、正在输入提示与多模态输入区。
 */
@Composable
fun ChatRoomScreen(
    state: BayChatUiState,
    vm: BayChatViewModel,
) {
    val room = state.activeRoom ?: return
    val colors = BayTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val inputFocusRequester = remember { FocusRequester() }

    // UX-10：全部局部界面状态采用 rememberSaveable 保持，确保旋转屏幕不丢失
    var showSearchBar by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showInviteSheet by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showSettingsSheet by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showQuickSwitchSheet by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showAttachmentPanel by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var voiceMode by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var actionTargetMessageId by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var pendingDeleteMessageId by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var previewImageItemId by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var fullscreenReaderText by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var peerQuickTargetId by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var peerQuickTargetName by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var inputText by rememberSaveable(room.roomCode) { mutableStateOf(vm.draftFor(room.roomCode)) }

    var isVoiceCancelling by remember { mutableStateOf(false) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }

    // UX-3：返回键逐层退回（先关闭附件面板，再关闭搜索栏，避免直接触发退房确认弹窗）
    BackHandler(enabled = showSearchBar && !showAttachmentPanel) {
        vm.setSearchFilter("", "all")
        showSearchBar = false
    }
    BackHandler(enabled = showAttachmentPanel) {
        showAttachmentPanel = false
    }

    fun dismissKeyboardAndPanel() {
        focusManager.clearFocus()
        keyboardController?.hide()
        if (showAttachmentPanel) {
            showAttachmentPanel = false
        }
    }

    LaunchedEffect(room.roomCode) {
        val saved = vm.draftFor(room.roomCode)
        if (inputText != saved) {
            inputText = saved
        }
        vm.onDraftTypingChanged(saved.isNotBlank())
    }

    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1200L)
            highlightedMessageId = null
        }
    }

    LaunchedEffect(state.isRecordingVoice) {
        if (!state.isRecordingVoice) {
            isVoiceCancelling = false
        }
    }

    val imageHeaders = remember(room.roomCode, room.password) { vm.imageHeaders() }
    val inviteUrl = remember(room.roomCode) { vm.buildInviteUrl(room.roomCode) }
    val isRoomMuted = room.roomCode.uppercase() in state.mutedRooms
    val otherRecentRooms = remember(state.recentRooms, room.roomCode) {
        state.recentRooms.filterNot { it.code.equals(room.roomCode, ignoreCase = true) }
    }
    val galleryImages = remember(room.messages) {
        room.messages.filter { it.kind == ChatKind.IMAGE && resolveImageModel(it) != null }
    }

    // 过滤后的可见消息列表（自动屏蔽已拉黑成员）
    val visibleMessages = remember(room.messages, room.searchKeyword, room.filterKind, state.blockedPeerIds) {
        val kw = room.searchKeyword.trim().lowercase()
        room.messages.filter { msg ->
            if (!msg.isSelf && msg.senderId in state.blockedPeerIds) return@filter false
            val kindMatch = when (room.filterKind) {
                "image" -> msg.kind == ChatKind.IMAGE
                "file" -> msg.kind == ChatKind.FILE
                else -> true
            }
            if (!kindMatch) return@filter false
            if (kw.isEmpty()) return@filter true
            listOfNotNull(msg.text, msg.caption, msg.senderName, msg.transfer?.fileName)
                .joinToString(" ")
                .lowercase()
                .contains(kw)
        }
    }

    // 追踪是否停留在底部附近，避免翻看历史消息时被新消息强行拉回
    val isAtBottom by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            if (total == 0) true
            else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }
    var lastSeenMessageCount by remember(room.roomCode) { mutableIntStateOf(visibleMessages.size) }
    var unreadBelowCount by remember(room.roomCode) { mutableIntStateOf(0) }
    var lastScrolledUnreadId by remember(room.roomCode) { mutableStateOf<String?>(null) }

    // UX-1：软键盘弹出且列表原处于底部时，自动滚动到底部防止遮挡最后几条消息
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && isAtBottom && visibleMessages.isNotEmpty()) {
            delay(80L)
            listState.animateScrollToItem(visibleMessages.lastIndex)
        }
    }

    // 首次出现「以下为新消息」标记时自动定位到首条未读位置
    LaunchedEffect(state.firstUnreadMessageId, visibleMessages.size) {
        val targetId = state.firstUnreadMessageId ?: return@LaunchedEffect
        if (targetId == lastScrolledUnreadId) return@LaunchedEffect
        val idx = visibleMessages.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            lastScrolledUnreadId = targetId
            listState.animateScrollToItem(idx)
        }
    }

    LaunchedEffect(visibleMessages.size, room.searchKeyword, room.filterKind) {
        val currentSize = visibleMessages.size
        val isUnfiltered = room.searchKeyword.isBlank() && room.filterKind == "all"
        val hasUnreadAnchor = state.firstUnreadMessageId != null &&
            state.firstUnreadMessageId != lastScrolledUnreadId &&
            visibleMessages.any { it.id == state.firstUnreadMessageId }
        if (currentSize > lastSeenMessageCount && isUnfiltered && !hasUnreadAnchor) {
            val added = currentSize - lastSeenMessageCount
            val lastIsSelf = visibleMessages.lastOrNull()?.isSelf == true
            if (isAtBottom || lastIsSelf) {
                unreadBelowCount = 0
                if (currentSize > 0) {
                    listState.animateScrollToItem(currentSize - 1)
                }
            } else {
                unreadBelowCount += added
            }
        } else if (isAtBottom) {
            unreadBelowCount = 0
        }
        lastSeenMessageCount = currentSize
    }

    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            unreadBelowCount = 0
        }
    }

    // UX-2：滑动消息列表时自动收起软键盘并关闭附件面板
    val listScrollDismissConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    dismissKeyboardAndPanel()
                }
                return Offset.Zero
            }
        }
    }

    val blankSpaceInteraction = remember { MutableInteractionSource() }

    Box(modifier = Modifier.fillMaxSize()) {
        // UX-1：外层统一处理 WindowInsets.ime.union(WindowInsets.navigationBars)，消除键盘与小白条双重垫高
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bg)
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            // 1. 顶部房间导航栏
            ChatTopBar(
                room = room,
                isMuted = isRoomMuted,
                searchActive = showSearchBar,
                hasQuickSwitchTargets = otherRecentRooms.isNotEmpty(),
                onMinimize = { vm.minimizeRoom() },
                onOpenQuickSwitch = { showQuickSwitchSheet = true },
                onToggleSearch = {
                    if (showSearchBar) {
                        vm.setSearchFilter("", "all")
                    }
                    showSearchBar = !showSearchBar
                },
                onOpenInvite = { showInviteSheet = true },
                onOpenSettings = { showSettingsSheet = true },
            )

            // UX-14：房内断网 / 信令重连紧凑提示条
            AnimatedVisibility(
                visible = state.isNetworkOffline || !room.wsConnected,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.warning.copy(alpha = if (colors.isDark) 0.20f else 0.14f))
                        .padding(horizontal = BaySpacing.lg, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Icon(
                        imageVector = if (state.isNetworkOffline) Icons.Rounded.WifiOff else Icons.Rounded.Sync,
                        contentDescription = null,
                        tint = colors.warning,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (state.isNetworkOffline) {
                            "当前网络不可用，请检查网络连接"
                        } else {
                            "与服务器连接断开，正在尝试重连..."
                        },
                        style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // UX-15：同局域网附近收到的房间邀请横幅
            val incomingInvite = state.incomingNearbyInvites.firstOrNull()
            AnimatedVisibility(
                visible = incomingInvite != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                incomingInvite?.let { invite ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.xs)
                            .clip(BayShapes.mdShape)
                            .background(colors.accentSoft)
                            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GroupAdd,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "${invite.fromName} 邀请你加入 #${invite.roomCode}",
                            style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        BayButton(
                            text = "接受",
                            onClick = { vm.respondNearbyInvite(invite, accept = true) },
                            style = BayButtonStyle.Primary,
                            size = BayButtonSize.Small,
                        )
                        BayButton(
                            text = "婉拒",
                            onClick = { vm.respondNearbyInvite(invite, accept = false) },
                            style = BayButtonStyle.Neutral,
                            size = BayButtonSize.Small,
                        )
                    }
                }
            }

            // UX-15：主动发出的附近邀请状态横幅
            val outgoingInvite = state.outgoingNearbyInvite
            AnimatedVisibility(
                visible = outgoingInvite != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                outgoingInvite?.let { out ->
                    val statusText = when (out.status) {
                        "accepted" -> "${out.toName} 已接受房间 #${out.roomCode} 邀请"
                        "declined" -> "${out.toName} 婉拒了房间邀请"
                        "expired" -> "发送给 ${out.toName} 的邀请已超时"
                        else -> "正在等待 ${out.toName} 响应房间 #${out.roomCode} 邀请..."
                    }
                    val bannerBg = when (out.status) {
                        "accepted" -> colors.success.copy(alpha = if (colors.isDark) 0.20f else 0.14f)
                        "declined", "expired" -> colors.fill
                        else -> colors.accentSoft
                    }
                    val bannerAccent = when (out.status) {
                        "accepted" -> colors.success
                        "declined", "expired" -> colors.textSecondary
                        else -> colors.accent
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.xs)
                            .clip(BayShapes.mdShape)
                            .background(bannerBg)
                            .padding(start = BaySpacing.md, end = BaySpacing.xs, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        Text(
                            text = statusText,
                            style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                            color = bannerAccent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                                .bayClickable { vm.dismissOutgoingInviteBanner() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭邀请提示",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // 2. 可展开搜索过滤条
            AnimatedVisibility(
                visible = showSearchBar,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    BayTextField(
                        value = room.searchKeyword,
                        onValueChange = { kw -> vm.setSearchFilter(kw, room.filterKind) },
                        placeholder = "搜索聊天文本、文件名或成员...",
                        leadingIcon = Icons.Rounded.Search,
                        trailing = {
                            BayIconButton(
                                icon = Icons.Rounded.Close,
                                contentDescription = "关闭搜索",
                                onClick = {
                                    vm.setSearchFilter("", "all")
                                    showSearchBar = false
                                },
                                style = BayIconStyle.Plain,
                                size = 28.dp,
                            )
                        },
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BayChip(
                            label = "全部",
                            selected = room.filterKind == "all",
                            onClick = { vm.setSearchFilter(room.searchKeyword, "all") },
                        )
                        BayChip(
                            label = "图片",
                            selected = room.filterKind == "image",
                            leadingIcon = Icons.Rounded.Image,
                            onClick = { vm.setSearchFilter(room.searchKeyword, "image") },
                        )
                        BayChip(
                            label = "文件",
                            selected = room.filterKind == "file",
                            leadingIcon = Icons.Rounded.InsertDriveFile,
                            onClick = { vm.setSearchFilter(room.searchKeyword, "file") },
                        )
                    }
                }
            }

            // 3. UX-34：文件夹打包进度横幅（采用不定态动画进度条，零描边）
            AnimatedVisibility(
                visible = state.packingFolder != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                state.packingFolder?.let { pack ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.xs)
                            .clip(BayShapes.mdShape)
                            .background(colors.surface)
                            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderZip,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "正在打包「${pack.folderName}」· ${pack.packedFiles} 个文件 (${FormatUtils.formatBytes(pack.packedBytes)})",
                                style = BayType.footnote,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IndeterminatePackingProgressBar()
                    }
                }
            }

            // 4. 外部待分享内容横幅
            AnimatedVisibility(
                visible = state.pendingShare.hasContent,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                val summary = buildString {
                    if (state.pendingShare.uris.isNotEmpty()) {
                        append("${state.pendingShare.uris.size} 个附件")
                    }
                    if (!state.pendingShare.sharedText.isNullOrBlank()) {
                        if (isNotEmpty()) append(" + ")
                        append("文本内容")
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BaySpacing.md, vertical = BaySpacing.xs)
                        .clip(BayShapes.mdShape)
                        .background(colors.accentSoft)
                        .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    Text(
                        text = "待发系统分享：$summary",
                        style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BayButton(
                        text = "立即发送",
                        onClick = { vm.sendPendingShareNow() },
                        style = BayButtonStyle.Primary,
                        size = BayButtonSize.Small,
                    )
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                            .bayClickable { vm.clearPendingShare() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清除待发分享",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }

            // 5. 中央消息流 / 空状态 / 悬浮回到底部胶囊（点击消息区空白自动收起键盘与面板）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = blankSpaceInteraction,
                        indication = null,
                        onClick = ::dismissKeyboardAndPanel,
                    ),
            ) {
                when {
                    room.messages.isEmpty() -> {
                        EmptyRoomOverview(
                            roomCode = room.roomCode,
                            inviteUrl = inviteUrl,
                            hasPeers = room.peers.isNotEmpty(),
                            onCopyInvite = {
                                copyTextToClipboard(context, inviteUrl)
                                BayHaptic.confirm(view, state.prefs.hapticEnabled)
                                vm.showToast("已复制邀请链接")
                            },
                            onShareInvite = { shareRoomInvite(context, room.roomCode, inviteUrl) },
                            onOpenQrSheet = { showInviteSheet = true },
                        )
                    }

                    visibleMessages.isEmpty() -> {
                        BayEmptyState(
                            icon = Icons.Rounded.SearchOff,
                            title = "未找到匹配消息",
                            message = "可尝试更换搜索关键词或切换消息类型筛选",
                            actionLabel = "清除筛选",
                            onAction = { vm.setSearchFilter("", "all") },
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(listScrollDismissConnection),
                            contentPadding = PaddingValues(
                                horizontal = BaySpacing.md,
                                vertical = BaySpacing.md,
                            ),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            itemsIndexed(
                                items = visibleMessages,
                                key = { _, item -> item.id },
                            ) { index, item ->
                                val prev = visibleMessages.getOrNull(index - 1)
                                val next = visibleMessages.getOrNull(index + 1)
                                val isFirstInGroup = prev == null ||
                                    prev.kind == ChatKind.SYSTEM ||
                                    prev.senderId != item.senderId ||
                                    (item.timestamp - prev.timestamp) > MESSAGE_GROUP_GAP_MS
                                val isLastInGroup = next == null ||
                                    next.kind == ChatKind.SYSTEM ||
                                    next.senderId != item.senderId ||
                                    (next.timestamp - item.timestamp) > MESSAGE_GROUP_GAP_MS
                                val showTimeDivider = prev == null ||
                                    (item.timestamp - prev.timestamp) >= 5 * 60 * 1000L ||
                                    !isSameCalendarDay(prev.timestamp, item.timestamp)
                                val showUnreadDivider = item.id == state.firstUnreadMessageId

                                // UX-19：局部订阅高频传输进度快照，避免每秒全列表重组
                                val progressSnap = item.transfer?.transferId?.let { state.transferProgress[it] }

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    if (showTimeDivider) {
                                        Text(
                                            text = formatWeChatMessageTime(item.timestamp),
                                            style = BayType.footnote,
                                            color = colors.textSecondary,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = BaySpacing.md, bottom = BaySpacing.xs),
                                        )
                                    }

                                    if (showUnreadDivider) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = BaySpacing.sm),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(0.5.dp)
                                                    .background(colors.separator),
                                            )
                                            Text(
                                                text = "以下为新消息",
                                                style = BayType.caption,
                                                color = colors.textSecondary,
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(0.5.dp)
                                                    .background(colors.separator),
                                            )
                                        }
                                    }

                                    MessageBubble(
                                        item = item,
                                        selfPeerId = room.selfPeerId,
                                        isFirstInGroup = isFirstInGroup,
                                        isLastInGroup = isLastInGroup,
                                        hapticEnabled = state.prefs.hapticEnabled,
                                        imageHeaders = imageHeaders,
                                        voicePlayback = state.voicePlayback,
                                        playedVoiceIds = state.playedVoiceIds,
                                        snapshotTransferredBytes = progressSnap?.transferredBytes,
                                        snapshotTotalBytes = progressSnap?.totalBytes,
                                        snapshotSpeedBps = progressSnap?.speedBps,
                                        snapshotEtaSeconds = progressSnap?.etaSeconds,
                                        isHighlighted = highlightedMessageId == item.id,
                                        onLongPress = {
                                            if (item.kind != ChatKind.SYSTEM) {
                                                actionTargetMessageId = item.id
                                            }
                                        },
                                        onReEditRecall = { recalled ->
                                            voiceMode = false
                                            showAttachmentPanel = false
                                            inputText = recalled
                                            vm.updateDraft(room.roomCode, recalled)
                                            vm.onDraftTypingChanged(recalled.isNotBlank())
                                            scope.launch {
                                                runCatching { inputFocusRequester.requestFocus() }
                                                keyboardController?.show()
                                            }
                                        },
                                        onJumpToReply = { replyId ->
                                            val targetIdx = visibleMessages.indexOfFirst { it.id == replyId }
                                            if (targetIdx >= 0) {
                                                highlightedMessageId = replyId
                                                scope.launch {
                                                    listState.animateScrollToItem(targetIdx)
                                                }
                                            } else {
                                                vm.showToast("原消息已不可见")
                                            }
                                        },
                                        onDoubleClickText = { text ->
                                            fullscreenReaderText = text
                                        },
                                        onPeerClick = { pid, name ->
                                            peerQuickTargetId = pid
                                            peerQuickTargetName = name
                                        },
                                        onRetrySend = { msgId -> vm.retrySend(msgId) },
                                        onReact = { emoji -> vm.reactMessage(item.id, emoji) },
                                        onPreviewImage = { previewImageItemId = item.id },
                                        onAcceptFile = { pid, tid, resume -> vm.acceptFile(pid, tid, resume) },
                                        onRejectFile = { pid, tid -> vm.rejectFile(pid, tid) },
                                        onCancelTransfer = { pid, tid -> vm.cancelTransfer(pid, tid) },
                                        onDownloadCachedFile = { url, name, size, mime ->
                                            vm.downloadCachedAttachment(item.id, url, name, size, mime)
                                        },
                                        onOpenFile = {
                                            item.transfer?.let { tr -> vm.openTransfer(tr) }
                                        },
                                        onShareItem = { vm.shareChatItem(item) },
                                        onRequestResend = { vm.requestResend(item.id) },
                                        onRetryCloudUpload = { vm.retryCloudUpload(item.id) },
                                        onToggleVoice = { vm.toggleVoicePlayback(item) },
                                        onCopiedClip = { vm.showToast("已复制到剪贴板") },
                                    )
                                }
                            }
                        }
                    }
                }

                // 顶部「有人@我」悬浮胶囊
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.unreadMentionMessageIds.isNotEmpty() && visibleMessages.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = BaySpacing.md, end = BaySpacing.lg),
                ) {
                    val targetId = state.unreadMentionMessageIds.firstOrNull()
                    Row(
                        modifier = Modifier
                            .shadow(elevation = 4.dp, shape = BayShapes.fullShape, clip = false)
                            .clip(BayShapes.fullShape)
                            .background(colors.accent)
                            .padding(start = BaySpacing.md, end = BaySpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "有人@我",
                            style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = colors.onAccent,
                            modifier = Modifier
                                .bayClickable {
                                    if (targetId != null) {
                                        val idx = visibleMessages.indexOfFirst { it.id == targetId }
                                        if (idx >= 0) {
                                            highlightedMessageId = targetId
                                            scope.launch {
                                                listState.animateScrollToItem(idx)
                                            }
                                        } else {
                                            vm.showToast("对应消息当前未在列表中")
                                        }
                                        vm.jumpToMentionHandled(targetId)
                                    }
                                }
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                        )
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 36.dp, minHeight = 36.dp)
                                .bayClickable { vm.dismissMentionBanner() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "关闭@提醒",
                                tint = colors.onAccent,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }

                // 未读/回到底部悬浮胶囊（显式调用顶层 AnimatedVisibility 避免与外层 ColumnScope 扩展冲突）
                androidx.compose.animation.AnimatedVisibility(
                    visible = visibleMessages.isNotEmpty() && (!isAtBottom || unreadBelowCount > 0),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = BaySpacing.lg, bottom = BaySpacing.md),
                ) {
                    val label = if (unreadBelowCount > 0) "${unreadBelowCount} 条新消息" else "回到底部"
                    Row(
                        modifier = Modifier
                            .shadow(elevation = 4.dp, shape = BayShapes.fullShape, clip = false)
                            .bayClickable {
                                unreadBelowCount = 0
                                vm.clearFirstUnreadMarker()
                                scope.launch {
                                    if (visibleMessages.isNotEmpty()) {
                                        listState.animateScrollToItem(visibleMessages.lastIndex)
                                    }
                                }
                            }
                            .clip(BayShapes.fullShape)
                            .background(if (unreadBelowCount > 0) colors.accent else colors.surface)
                            .padding(horizontal = BaySpacing.md, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = label,
                            tint = if (unreadBelowCount > 0) colors.onAccent else colors.textPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = label,
                            style = BayType.caption,
                            color = if (unreadBelowCount > 0) colors.onAccent else colors.textPrimary,
                        )
                    }
                }
            }

            // 6. 正在输入提示条
            val typingNames = remember(room.typingPeers, room.peers) {
                room.typingPeers.mapNotNull { room.peers[it]?.name }.joinToString("、")
            }
            AnimatedVisibility(
                visible = typingNames.isNotBlank(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = "$typingNames 正在输入...",
                        style = BayType.caption,
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 7. 底部输入与多模态附件面板
            Composer(
                state = state,
                roomCode = room.roomCode,
                peers = room.peers.values.toList(),
                inputText = inputText,
                onInputTextChange = { inputText = it },
                voiceMode = voiceMode,
                onVoiceModeChange = { voiceMode = it },
                showAttachmentPanel = showAttachmentPanel,
                onShowAttachmentPanelChange = { showAttachmentPanel = it },
                inputFocusRequester = inputFocusRequester,
                replyingTo = room.replyingTo,
                vm = vm,
                onVoiceCancellingChanged = { isVoiceCancelling = it },
            )
        }

        // 8. 微信式录音居中浮层（纯视觉遮罩穿透指针事件，绝不阻断底层按住说话手势）
        VoiceRecordingOverlay(
            visible = state.isRecordingVoice,
            isCancelling = isVoiceCancelling,
            durationMs = state.voiceRecordDurationMs,
            amplitude = state.voiceAmplitude,
        )
    }

    // ------------------------------------------------------------------------
    // 弹窗与交互菜单宿主（按持久化 ID 查找消息，旋转不丢失）
    // ------------------------------------------------------------------------

    val actionTargetMessage = remember(actionTargetMessageId, room.messages) {
        actionTargetMessageId?.let { id -> room.messages.firstOrNull { it.id == id } }
    }
    actionTargetMessage?.let { targetMsg ->
        MessageActionSheet(
            item = targetMsg,
            selfPeerId = room.selfPeerId,
            hapticEnabled = state.prefs.hapticEnabled,
            vm = vm,
            onRequestDelete = { pendingDeleteMessageId = targetMsg.id },
            onDismiss = { actionTargetMessageId = null },
        )
    }

    val pendingDeleteMessage = remember(pendingDeleteMessageId, room.messages) {
        pendingDeleteMessageId?.let { id -> room.messages.firstOrNull { it.id == id } }
    }
    pendingDeleteMessage?.let { deleteTarget ->
        BayDialog(
            title = "删除消息",
            message = "删除后将不会出现在你的消息记录中",
            confirmText = "删除",
            onConfirm = {
                pendingDeleteMessageId = null
                vm.deleteMessageLocally(deleteTarget.id)
            },
            dismissText = "取消",
            onDismiss = { pendingDeleteMessageId = null },
            destructive = true,
        )
    }

    if (showInviteSheet) {
        InviteSheet(
            room = room,
            hapticEnabled = state.prefs.hapticEnabled,
            vm = vm,
            onDismiss = { showInviteSheet = false },
        )
    }

    if (showSettingsSheet) {
        RoomSettingsSheet(
            state = state,
            room = room,
            vm = vm,
            onDismiss = { showSettingsSheet = false },
        )
    }

    val previewImageItem = remember(previewImageItemId, room.messages) {
        previewImageItemId?.let { id -> room.messages.firstOrNull { it.id == id } }
    }
    previewImageItem?.let { imgItem ->
        val imagesToView = if (galleryImages.isNotEmpty()) galleryImages else listOf(imgItem)
        val initialIdx = imagesToView.indexOfFirst { it.id == imgItem.id }.takeIf { it >= 0 } ?: 0
        ImageViewer(
            images = imagesToView,
            initialIndex = initialIdx,
            hapticEnabled = state.prefs.hapticEnabled,
            imageHeaders = imageHeaders,
            onSave = { currentItem -> vm.saveImageToGallery(currentItem) },
            onShare = { currentItem -> vm.shareChatItem(currentItem) },
            onDismiss = { previewImageItemId = null },
        )
    }

    fullscreenReaderText?.let { readerText ->
        TextFullscreenReader(
            text = readerText,
            onDismiss = { fullscreenReaderText = null },
        )
    }

    // 点击对方头像/昵称弹出的私密操作菜单
    val pId = peerQuickTargetId
    val pName = peerQuickTargetName
    if (pId != null && pName != null) {
        BayActionSheet(
            onDismiss = {
                peerQuickTargetId = null
                peerQuickTargetName = null
            },
            title = pName,
            actions = listOf(
                SheetAction(
                    label = "屏蔽此成员",
                    icon = Icons.Rounded.Block,
                    destructive = true,
                    onClick = { vm.blockPeer(pId, pName) },
                ),
                SheetAction(
                    label = "举报违规内容",
                    icon = Icons.Rounded.Flag,
                    onClick = { vm.reportPeer(pId, pName) },
                ),
            ),
        )
    }

    // 点击顶部房间标题弹出的快速切换菜单
    if (showQuickSwitchSheet) {
        val quickActions = buildList {
            add(
                SheetAction(
                    label = "复制当前口令 #${room.roomCode}",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = {
                        copyTextToClipboard(context, room.roomCode)
                        BayHaptic.confirm(view, state.prefs.hapticEnabled)
                        vm.showToast("已复制房间口令 ${room.roomCode}")
                    },
                )
            )
            add(
                SheetAction(
                    label = "随机切换公开房间",
                    icon = Icons.Rounded.Casino,
                    enabled = !state.randomJoining,
                    onClick = { vm.joinRandomPublicRoom(excludeCurrent = true) },
                )
            )
            otherRecentRooms.take(5).forEach { rec ->
                val modeTag = if (rec.mode == RoomMode.FIXED.wireValue) "常驻" else "临时"
                add(
                    SheetAction(
                        label = "#${rec.code} · $modeTag · ${FormatUtils.formatRelativeTime(rec.lastVisitedAt)}",
                        icon = Icons.Rounded.Forum,
                        onClick = { vm.switchToRecentRoom(rec) },
                    )
                )
            }
        }
        BayActionSheet(
            onDismiss = { showQuickSwitchSheet = false },
            title = "切换房间与快捷操作",
            actions = quickActions,
        )
    }
}

/**
 * 文件夹打包不定态动画进度条（纯色滑动胶囊，零描边）。
 */
@Composable
private fun IndeterminatePackingProgressBar(
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val transition = rememberInfiniteTransition(label = "packingProgress")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "packingProgressOffset",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(BayShapes.fullShape)
            .background(colors.fill),
    ) {
        val barWidth = maxWidth * 0.36f
        val offsetX = (maxWidth + barWidth) * progress - barWidth
        Box(
            modifier = Modifier
                .offset(x = offsetX)
                .width(barWidth)
                .fillMaxHeight()
                .clip(BayShapes.fullShape)
                .background(colors.accent),
        )
    }
}

@Composable
private fun EmptyRoomOverview(
    roomCode: String,
    inviteUrl: String,
    hasPeers: Boolean,
    onCopyInvite: () -> Unit,
    onShareInvite: () -> Unit,
    onOpenQrSheet: () -> Unit,
) {
    val colors = BayTheme.colors
    val qrBitmap = remember(inviteUrl) { FormatUtils.generateQrBitmap(inviteUrl, 520) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(BaySpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (qrBitmap != null) {
            Box(
                modifier = Modifier
                    .bayClickable(onClick = onOpenQrSheet)
                    .clip(BayShapes.lgShape)
                    .background(Color.White)
                    .padding(BaySpacing.md),
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "房间二维码",
                    modifier = Modifier.size(168.dp),
                )
            }
            Spacer(modifier = Modifier.height(BaySpacing.lg))
        }

        Text(
            text = roomCode,
            style = BayType.codeLarge,
            color = colors.accent,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(BaySpacing.sm))

        Text(
            text = if (hasPeers) {
                "成员已就绪，现在可以发送消息、图片、语音或文件"
            } else {
                "在另一台设备扫码或输入口令即可加入当前房间并传输文件"
            },
            style = BayType.subhead,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(BaySpacing.lg))

        Row(
            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BayButton(
                text = "复制邀请链接",
                onClick = onCopyInvite,
                style = BayButtonStyle.Primary,
                size = BayButtonSize.Medium,
                leadingIcon = Icons.Rounded.ContentCopy,
            )
            BayButton(
                text = "分享给好友",
                onClick = onShareInvite,
                style = BayButtonStyle.Neutral,
                size = BayButtonSize.Medium,
                leadingIcon = Icons.Rounded.Share,
            )
        }
    }
}

private val RECORD_METER_FACTORS = listOf(0.36f, 0.54f, 0.74f, 0.90f, 1.0f, 0.90f, 0.74f, 0.54f, 0.36f)

/**
 * 录音中全屏居中浮层（纯视觉遮罩，不拦截任何指针事件以确保底部按住说话手势持续追踪）。
 */
@Composable
private fun VoiceRecordingOverlay(
    visible: Boolean,
    isCancelling: Boolean,
    durationMs: Long,
    amplitude: Float,
) {
    val colors = BayTheme.colors
    val isCountingDown = durationMs >= 50_000L
    val remSec = ((60_000L - durationMs + 999L) / 1000L).coerceIn(1L, 10L)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(140)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.40f)),
            contentAlignment = Alignment.Center,
        ) {
            val cardBg = if (isCancelling) colors.danger else colors.surfaceElevated
            val titleColor = if (isCancelling) Color.White else colors.textPrimary
            val hintColor = when {
                isCancelling -> Color.White.copy(alpha = 0.92f)
                isCountingDown -> colors.warning
                else -> colors.textSecondary
            }

            Column(
                modifier = Modifier
                    .width(224.dp)
                    .clip(BayShapes.lgShape)
                    .background(cardBg)
                    .padding(horizontal = BaySpacing.xl, vertical = BaySpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
            ) {
                if (isCancelling) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(BayShapes.mdShape)
                            .background(Color.White.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Undo,
                            contentDescription = "取消发送",
                            tint = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                } else {
                    // 9 根居中对称动态音量柱
                    Row(
                        modifier = Modifier.height(40.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        val normAmp = amplitude.coerceIn(0f, 1f)
                        RECORD_METER_FACTORS.forEachIndexed { idx, factor ->
                            val targetHeight by animateDpAsState(
                                targetValue = 6.dp + 32.dp * maxOf(0.14f, normAmp) * factor,
                                animationSpec = tween(durationMillis = 90),
                                label = "voiceMeterBar_$idx",
                            )
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(targetHeight)
                                    .clip(BayShapes.fullShape)
                                    .background(colors.accent),
                            )
                        }
                    }
                }

                Text(
                    text = formatDurationMs(durationMs),
                    style = BayType.title2.copy(fontFamily = FontFamily.Monospace),
                    color = titleColor,
                )

                Text(
                    text = when {
                        isCancelling -> "松开手指，取消发送"
                        isCountingDown -> "还可以说 ${remSec} 秒"
                        else -> "松开发送，上滑取消"
                    },
                    style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                    color = hintColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}
