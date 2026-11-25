package com.baytale.baychat.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayActionSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayEmptyState
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayProgressBar
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.SheetAction
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlinx.coroutines.launch

private const val MESSAGE_GROUP_GAP_MS = 120_000L

/**
 * 房间聊天主视图：整合顶部栏、可展开搜索筛选、待发系统分享横幅、消息流、正在输入提示与多模态输入区。
 */
@Composable
fun ChatRoomScreen(
    state: BayChatUiState,
    vm: BayChatViewModel,
) {
    val room = state.activeRoom ?: return
    val colors = BayTheme.colors
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showSearchBar by remember { mutableStateOf(false) }
    var showInviteSheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showQuickSwitchSheet by remember { mutableStateOf(false) }
    var actionTargetMessage by remember { mutableStateOf<ChatItem?>(null) }
    var previewImageItem by remember { mutableStateOf<ChatItem?>(null) }
    var peerQuickTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    val imageHeaders = remember(room.roomCode, room.password) { vm.imageHeaders() }
    val inviteUrl = remember(room.roomCode) { vm.buildInviteUrl(room.roomCode) }
    val otherRecentRooms = remember(state.recentRooms, room.roomCode) {
        state.recentRooms.filterNot { it.code.equals(room.roomCode, ignoreCase = true) }
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

    LaunchedEffect(visibleMessages.size, room.searchKeyword, room.filterKind) {
        val currentSize = visibleMessages.size
        val isUnfiltered = room.searchKeyword.isBlank() && room.filterKind == "all"
        if (currentSize > lastSeenMessageCount && isUnfiltered) {
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bg)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // 1. 顶部房间导航栏
        ChatTopBar(
            room = room,
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

        // 3. 文件夹打包进度横幅
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
                    BayProgressBar(fraction = 0.6f)
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
                BayIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = "清除待发分享",
                    onClick = { vm.clearPendingShare() },
                    style = BayIconStyle.Plain,
                    size = 28.dp,
                )
            }
        }

        // 5. 中央消息流 / 空状态 / 悬浮回到底部胶囊
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                room.messages.isEmpty() -> {
                    EmptyRoomOverview(
                        roomCode = room.roomCode,
                        inviteUrl = inviteUrl,
                        hasPeers = room.peers.isNotEmpty(),
                        onCopyInvite = {
                            copyTextToClipboard(context, inviteUrl)
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
                        modifier = Modifier.fillMaxSize(),
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

                            MessageBubble(
                                item = item,
                                selfPeerId = room.selfPeerId,
                                isFirstInGroup = isFirstInGroup,
                                isLastInGroup = isLastInGroup,
                                hapticEnabled = state.prefs.hapticEnabled,
                                imageHeaders = imageHeaders,
                                voicePlayback = state.voicePlayback,
                                onLongPress = {
                                    if (item.kind != ChatKind.SYSTEM) {
                                        actionTargetMessage = item
                                    }
                                },
                                onPeerClick = { pid, name ->
                                    peerQuickTarget = pid to name
                                },
                                onRetrySend = { msgId -> vm.retrySend(msgId) },
                                onReact = { emoji -> vm.reactMessage(item.id, emoji) },
                                onPreviewImage = { previewImageItem = item },
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
                                onSeekVoice = { posMs -> vm.seekVoicePlayback(posMs) },
                                onCopiedClip = { vm.showToast("已复制到剪贴板") },
                            )
                        }
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
            replyingTo = room.replyingTo,
            vm = vm,
        )
    }

    // ------------------------------------------------------------------------
    // 弹窗与交互菜单宿主
    // ------------------------------------------------------------------------

    actionTargetMessage?.let { targetMsg ->
        MessageActionSheet(
            item = targetMsg,
            selfPeerId = room.selfPeerId,
            vm = vm,
            onDismiss = { actionTargetMessage = null },
        )
    }

    if (showInviteSheet) {
        InviteSheet(
            room = room,
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

    previewImageItem?.let { imgItem ->
        ImageViewer(
            item = imgItem,
            imageHeaders = imageHeaders,
            onSave = { vm.saveImageToGallery(imgItem) },
            onShare = { vm.shareChatItem(imgItem) },
            onDismiss = { previewImageItem = null },
        )
    }

    // 点击对方头像/昵称弹出的私密操作菜单
    peerQuickTarget?.let { (peerId, peerName) ->
        BayActionSheet(
            onDismiss = { peerQuickTarget = null },
            title = peerName,
            actions = listOf(
                SheetAction(
                    label = "屏蔽此成员",
                    icon = Icons.Rounded.Block,
                    destructive = true,
                    onClick = { vm.blockPeer(peerId, peerName) },
                ),
                SheetAction(
                    label = "举报违规内容",
                    icon = Icons.Rounded.Flag,
                    onClick = { vm.reportPeer(peerId, peerName) },
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
