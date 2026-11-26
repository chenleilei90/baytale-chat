package com.baytale.baychat.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.ForwardPayload
import com.baytale.baychat.ui.design.BayActionSheet
import com.baytale.baychat.ui.design.BayBottomSheet
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
import com.baytale.baychat.ui.sheets.FavoritesSheet
import com.baytale.baychat.ui.sheets.PlaneComposerSheet
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
    // 按 roomCode 独立创建 LazyListState，杜绝同槽位切房或重进残留旧滚动位置
    val listState = remember(room.roomCode) {
        LazyListState(firstVisibleItemIndex = (room.messages.lastIndex).coerceAtLeast(0))
    }
    val scope = rememberCoroutineScope()
    val inputFocusRequester = remember { FocusRequester() }

    // UX-10：全部局部界面状态采用 rememberSaveable 保持，确保旋转屏幕不丢失
    var showSearchBar by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showInviteSheet by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showRoomInfo by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showQuickSwitchSheet by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showAttachmentPanel by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showEmojiPanel by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showFavoritesPicker by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var showPlaneComposer by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var voiceMode by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var actionTargetMessageId by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var actionTargetAnchorRect by remember { mutableStateOf(Rect.Zero) }
    var pendingDeleteMessageId by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var selectionMode by rememberSaveable(room.roomCode) { mutableStateOf(false) }
    var selectedIds by remember(room.roomCode) { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var showLeaveRoomConfirm by remember { mutableStateOf(false) }
    var forwardPayloadItems by remember { mutableStateOf<List<ChatItem>?>(null) }
    var previewImageItemId by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var fullscreenReaderText by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var peerQuickTargetId by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var peerQuickTargetName by rememberSaveable(room.roomCode) { mutableStateOf<String?>(null) }
    var inputText by rememberSaveable(room.roomCode) { mutableStateOf(vm.draftFor(room.roomCode)) }

    var voiceGestureZone by remember { mutableStateOf(VoiceGestureZone.SEND) }
    var highlightedMessageId by remember { mutableStateOf<String?>(null) }

    // UX-3：返回键逐层退回（先退出多选/表情面板/附件面板，再关闭搜索栏，避免直接触发退房确认弹窗）
    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedIds = emptySet()
    }
    BackHandler(enabled = showEmojiPanel && !selectionMode) {
        showEmojiPanel = false
    }
    BackHandler(enabled = showSearchBar && !showAttachmentPanel && !showEmojiPanel && !selectionMode) {
        vm.setSearchFilter("", "all")
        showSearchBar = false
    }
    BackHandler(enabled = showAttachmentPanel && !selectionMode) {
        showAttachmentPanel = false
    }

    fun dismissKeyboardAndPanel() {
        focusManager.clearFocus()
        keyboardController?.hide()
        if (showAttachmentPanel) {
            showAttachmentPanel = false
        }
        if (showEmojiPanel) {
            showEmojiPanel = false
        }
    }

    LaunchedEffect(room.roomCode) {
        selectionMode = false
        selectedIds = emptySet()
        showEmojiPanel = false
        showRoomInfo = false
        forwardPayloadItems = null
        val saved = vm.draftFor(room.roomCode)
        if (inputText != saved) {
            inputText = saved
        }
        vm.onDraftTypingChanged(saved.isNotBlank())
    }

    LaunchedEffect(state.nudgeNonce) {
        if (state.nudgeNonce > 0L && state.nudgedPeerId == room.selfPeerId) {
            BayHaptic.heavy(view, state.prefs.hapticEnabled)
        }
    }

    LaunchedEffect(highlightedMessageId) {
        if (highlightedMessageId != null) {
            delay(1200L)
            highlightedMessageId = null
        }
    }

    LaunchedEffect(state.isRecordingVoice) {
        if (!state.isRecordingVoice) {
            voiceGestureZone = VoiceGestureZone.SEND
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
    var lastSeenMessageCount by remember(room.roomCode) { mutableIntStateOf(0) }
    var hasInitialScrolled by remember(room.roomCode) { mutableStateOf(false) }
    var lastHandledSyncNonce by remember(room.roomCode) { mutableIntStateOf(-1) }
    var unreadBelowCount by remember(room.roomCode) { mutableIntStateOf(0) }
    var lastScrolledUnreadId by remember(room.roomCode) { mutableStateOf<String?>(null) }

    // 精准贴底：先滚动至末项，再在下一帧按末项实际高度补齐像素偏移，确保最后一条消息完整贴底可见
    suspend fun snapToLatestBottom() {
        val lastIdx = visibleMessages.lastIndex
        if (lastIdx < 0) return
        listState.scrollToItem(lastIdx)
        withFrameNanos { }
        val info = listState.layoutInfo
        val lastItem = info.visibleItemsInfo.lastOrNull { it.index == lastIdx }
        if (lastItem != null) {
            val overflow = (lastItem.offset + lastItem.size) - info.viewportEndOffset
            if (overflow > 0) {
                listState.scrollBy(overflow.toFloat())
            }
        }
    }

    // UX-1：软键盘弹出且列表原处于底部时，自动滚动到底部防止遮挡最后几条消息
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && isAtBottom && visibleMessages.isNotEmpty()) {
            delay(80L)
            listState.animateScrollToItem(visibleMessages.lastIndex)
        }
    }

    // 首次出现「以下为新消息」标记时自动定位到首条未读位置（仅挂起期间产生真实未读时触发）
    LaunchedEffect(state.firstUnreadMessageId, visibleMessages.size) {
        val targetId = state.firstUnreadMessageId ?: return@LaunchedEffect
        if (targetId == lastScrolledUnreadId) return@LaunchedEffect
        val idx = visibleMessages.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            hasInitialScrolled = true
            lastHandledSyncNonce = room.historySyncNonce
            lastSeenMessageCount = visibleMessages.size
            lastScrolledUnreadId = targetId
            listState.animateScrollToItem(idx)
        }
    }

    // 进房首次历史消息同步完成及从挂起恢复时，精准定位至最新消息底部；后续实时新消息按是否在底部智能跟随
    LaunchedEffect(room.roomCode, room.historySyncNonce, visibleMessages.size, room.searchKeyword, room.filterKind) {
        val currentSize = visibleMessages.size
        val isUnfiltered = room.searchKeyword.isBlank() && room.filterKind == "all"
        if (currentSize == 0) {
            lastSeenMessageCount = 0
            return@LaunchedEffect
        }
        val needSyncScroll = (!hasInitialScrolled || room.historySyncNonce != lastHandledSyncNonce) && isUnfiltered
        if (needSyncScroll) {
            val unreadId = state.firstUnreadMessageId
            val unreadIdx = if (unreadId != null) visibleMessages.indexOfFirst { it.id == unreadId } else -1
            hasInitialScrolled = true
            lastHandledSyncNonce = room.historySyncNonce
            lastSeenMessageCount = currentSize
            unreadBelowCount = 0
            if (unreadIdx >= 0 && unreadId != lastScrolledUnreadId) {
                lastScrolledUnreadId = unreadId
                listState.scrollToItem(unreadIdx)
            } else {
                snapToLatestBottom()
                delay(160L)
                snapToLatestBottom()
            }
            return@LaunchedEffect
        }

        val hasUnreadAnchor = state.firstUnreadMessageId != null &&
            state.firstUnreadMessageId != lastScrolledUnreadId &&
            visibleMessages.any { it.id == state.firstUnreadMessageId }
        if (currentSize > lastSeenMessageCount && isUnfiltered && !hasUnreadAnchor) {
            val added = currentSize - lastSeenMessageCount
            val lastIsSelf = visibleMessages.lastOrNull()?.isSelf == true
            if (isAtBottom || lastIsSelf) {
                unreadBelowCount = 0
                snapToLatestBottom()
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
            if (state.firstUnreadMessageId != null) {
                delay(1500L)
                vm.clearFirstUnreadMarker()
            }
        }
    }

    // UX-2：滑动消息列表时自动收起软键盘并关闭附件/表情面板（多选模式屏蔽收起手势）
    val listScrollDismissConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!selectionMode && source == NestedScrollSource.UserInput && available.y != 0f) {
                    dismissKeyboardAndPanel()
                }
                return Offset.Zero
            }
        }
    }

    val jumpAndHighlightMessage: (String) -> Unit = { targetId ->
        val idx = visibleMessages.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            highlightedMessageId = targetId
            scope.launch {
                listState.animateScrollToItem(idx)
            }
        } else {
            vm.showToast("原消息已不可见")
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
            // 1. 顶部房间导航栏（多选模式下切换为取消与已选择数量）
            if (selectionMode) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "取消",
                            style = BayType.callout.copy(fontWeight = FontWeight.Medium),
                            color = colors.accent,
                            modifier = Modifier
                                .defaultMinSize(minWidth = 44.dp, minHeight = 40.dp)
                                .bayClickable {
                                    selectionMode = false
                                    selectedIds = emptySet()
                                }
                                .padding(horizontal = BaySpacing.xs, vertical = BaySpacing.sm),
                        )
                        Text(
                            text = "已选择 ${selectedIds.size} 条",
                            style = BayType.headline,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(44.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(colors.separator),
                    )
                }
            } else {
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
                    onOpenSettings = {
                        dismissKeyboardAndPanel()
                        showRoomInfo = true
                    },
                )
            }

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
                    val textCount = state.pendingShare.texts.size + if (!state.pendingShare.sharedText.isNullOrBlank()) 1 else 0
                    if (textCount > 0) {
                        if (isNotEmpty()) append(" + ")
                        append("${textCount} 条文本")
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
                                        selectionMode = selectionMode,
                                        isSelected = item.id in selectedIds,
                                        onToggleSelect = {
                                            selectedIds = if (item.id in selectedIds) {
                                                selectedIds - item.id
                                            } else {
                                                selectedIds + item.id
                                            }
                                        },
                                        isAvatarShaking = state.nudgedPeerId == item.senderId,
                                        nudgeShakeNonce = state.nudgeNonce,
                                        onLongPress = { anchor ->
                                            if (!selectionMode && item.kind != ChatKind.SYSTEM) {
                                                actionTargetAnchorRect = anchor
                                                actionTargetMessageId = item.id
                                            }
                                        },
                                        onReEditRecall = { recalled ->
                                            voiceMode = false
                                            showAttachmentPanel = false
                                            showEmojiPanel = false
                                            inputText = recalled
                                            vm.updateDraft(room.roomCode, recalled)
                                            vm.onDraftTypingChanged(recalled.isNotBlank())
                                            scope.launch {
                                                runCatching { inputFocusRequester.requestFocus() }
                                                keyboardController?.show()
                                            }
                                        },
                                        onJumpToReply = { replyId ->
                                            if (!selectionMode) {
                                                jumpAndHighlightMessage(replyId)
                                            }
                                        },
                                        onDoubleClickText = { text ->
                                            if (!selectionMode) {
                                                fullscreenReaderText = text
                                            }
                                        },
                                        onPeerClick = { pid, name ->
                                            if (!selectionMode) {
                                                peerQuickTargetId = pid
                                                peerQuickTargetName = name
                                            }
                                        },
                                        onAvatarDoubleTap = { pid, name ->
                                            if (!selectionMode) {
                                                vm.nudgePeer(pid, name)
                                            }
                                        },
                                        onAvatarLongPress = { _, name ->
                                            if (!selectionMode) {
                                                voiceMode = false
                                                showAttachmentPanel = false
                                                showEmojiPanel = false
                                                val nextText = if (inputText.isNotEmpty() && !inputText.endsWith(" ")) {
                                                    "$inputText @$name "
                                                } else {
                                                    "$inputText@$name "
                                                }
                                                inputText = nextText
                                                vm.updateDraft(room.roomCode, nextText)
                                                vm.onDraftTypingChanged(true)
                                                scope.launch {
                                                    runCatching { inputFocusRequester.requestFocus() }
                                                    keyboardController?.show()
                                                }
                                            }
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

            // 6. 多人输入与语音说话状态提示条（精准区分多人及「正在输入」/「正在说话」）
            val typingSummary = remember(room.typingPeers, room.peers) {
                val voiceNames = mutableListOf<String>()
                val textNames = mutableListOf<String>()
                room.typingPeers.forEach { (pid, mode) ->
                    val name = room.peers[pid]?.name?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (mode == "voice") voiceNames.add(name) else textNames.add(name)
                }
                fun formatNames(names: List<String>): String = when (names.size) {
                    0 -> ""
                    1 -> names[0]
                    2 -> "${names[0]}、${names[1]}"
                    else -> "${names[0]}、${names[1]} 等 ${names.size} 人"
                }
                val hasVoice = voiceNames.isNotEmpty()
                val text = when {
                    voiceNames.isNotEmpty() && textNames.isNotEmpty() ->
                        "${formatNames(voiceNames)} 正在说话，${formatNames(textNames)} 正在输入..."
                    voiceNames.isNotEmpty() -> "${formatNames(voiceNames)} 正在说话..."
                    textNames.isNotEmpty() -> "${formatNames(textNames)} 正在输入..."
                    else -> ""
                }
                text to hasVoice
            }
            AnimatedVisibility(
                visible = typingSummary.first.isNotBlank(),
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
                        imageVector = if (typingSummary.second) Icons.Rounded.Mic else Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = typingSummary.first,
                        style = BayType.caption,
                        color = colors.accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 7. 底部输入与多模态附件/表情面板，或多选模式下的批量操作栏
            if (selectionMode) {
                val hasSelection = selectedIds.isNotEmpty()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(colors.separator),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val actionTint = if (hasSelection) colors.textPrimary else colors.textTertiary
                        val dangerTint = if (hasSelection) colors.danger else colors.textTertiary
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .bayClickable(enabled = hasSelection) {
                                    val chosen = room.messages.filter { it.id in selectedIds }
                                    val (payloads, _) = vm.resolveForwardPayloads(chosen)
                                    if (payloads.isEmpty()) {
                                        vm.showToast("所选消息暂不支持转发")
                                    } else {
                                        forwardPayloadItems = chosen
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Forward,
                                contentDescription = "转发",
                                tint = actionTint,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(text = "转发", style = BayType.caption, color = actionTint)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .bayClickable(enabled = hasSelection) {
                                    val chosen = room.messages.filter { it.id in selectedIds }
                                    vm.addFavorites(chosen)
                                    selectionMode = false
                                    selectedIds = emptySet()
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BookmarkBorder,
                                contentDescription = "收藏",
                                tint = actionTint,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(text = "收藏", style = BayType.caption, color = actionTint)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .bayClickable(enabled = hasSelection) {
                                    showBatchDeleteConfirm = true
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "删除",
                                tint = dangerTint,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(text = "删除", style = BayType.caption, color = dangerTint)
                        }
                    }
                }
            } else {
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
                    showEmojiPanel = showEmojiPanel,
                    onShowEmojiPanelChange = { showEmojiPanel = it },
                    onOpenFavoritesPicker = { showFavoritesPicker = true },
                    onOpenPlaneComposer = { showPlaneComposer = true },
                    inputFocusRequester = inputFocusRequester,
                    replyingTo = room.replyingTo,
                    vm = vm,
                    onVoiceZoneChanged = { voiceGestureZone = it },
                )
            }
        }

        // 8. 新版微信式录音与转文字全屏浮层（纯视觉遮罩穿透指针事件，绝不阻断底层手势）
        VoiceRecordingOverlay(
            visible = state.isRecordingVoice,
            zone = voiceGestureZone,
            durationMs = state.voiceRecordDurationMs,
            amplitude = state.voiceAmplitude,
            transcribePreview = state.voiceTranscribeText,
            isSpeechRecognizing = state.isSpeechRecognizing,
        )

        // 8b. 语音转文字确认与编辑面板（支持发送文字、发送原语音或再次语音听写）
        if (state.showVoiceTranscribePanel) {
            VoiceTranscribeConfirmSheet(
                text = state.voiceTranscribeText,
                hasDraftAudio = !state.voiceDraftAudioPath.isNullOrBlank(),
                isRecognizing = state.isSpeechRecognizing,
                onTextChange = { vm.updateVoiceTranscribeText(it) },
                onToggleDictation = {
                    if (state.isSpeechRecognizing) vm.stopSpeechDictationInPanel() else vm.startSpeechDictationInPanel()
                },
                onSendAudio = { vm.sendVoiceDraftAudio() },
                onSendText = { vm.sendVoiceTranscribeText() },
                onDismiss = { vm.dismissVoiceTranscribePanel() },
            )
        }

        // 9. 微信式全屏「聊天信息」房间页（从右侧滑入覆盖当前聊天视图）
        RoomInfoScreen(
            visible = showRoomInfo,
            state = state,
            room = room,
            vm = vm,
            onOpenInvite = {
                showRoomInfo = false
                showInviteSheet = true
            },
            onOpenPlaneComposer = { showPlaneComposer = true },
            onJumpToMessage = { msgId -> jumpAndHighlightMessage(msgId) },
            onRequestLeaveRoom = { showLeaveRoomConfirm = true },
            onDismiss = { showRoomInfo = false },
        )
    }

    // ------------------------------------------------------------------------
    // 弹窗与交互菜单宿主（按持久化 ID 查找消息，旋转不丢失）
    // ------------------------------------------------------------------------

    val actionTargetMessage = remember(actionTargetMessageId, room.messages) {
        actionTargetMessageId?.let { id -> room.messages.firstOrNull { it.id == id } }
    }
    actionTargetMessage?.let { targetMsg ->
        MessagePopupMenu(
            item = targetMsg,
            anchor = actionTargetAnchorRect,
            selfPeerId = room.selfPeerId,
            hapticEnabled = state.prefs.hapticEnabled,
            vm = vm,
            onAction = { action ->
                when (action) {
                    MessageMenuAction.COPY -> {
                        copyTextToClipboard(context, targetMsg.text.orEmpty())
                        BayHaptic.confirm(view, state.prefs.hapticEnabled)
                        vm.showToast("已复制到剪贴板")
                    }
                    MessageMenuAction.FORWARD -> {
                        val (payloads, _) = vm.resolveForwardPayloads(listOf(targetMsg))
                        if (payloads.isEmpty()) {
                            vm.showToast("该消息暂无可转发的本地文件")
                        } else {
                            forwardPayloadItems = listOf(targetMsg)
                        }
                    }
                    MessageMenuAction.FAVORITE -> {
                        vm.addFavorites(listOf(targetMsg))
                    }
                    MessageMenuAction.DELETE -> {
                        pendingDeleteMessageId = targetMsg.id
                    }
                    MessageMenuAction.MULTI_SELECT -> {
                        dismissKeyboardAndPanel()
                        selectionMode = true
                        selectedIds = setOf(targetMsg.id)
                    }
                    MessageMenuAction.REPLY -> {
                        vm.setReplyingTo(targetMsg)
                    }
                    MessageMenuAction.RECALL -> {
                        vm.recallMessage(targetMsg.id)
                    }
                    MessageMenuAction.SAVE_IMAGE -> {
                        if (vm.saveImageToGallery(targetMsg)) {
                            BayHaptic.confirm(view, state.prefs.hapticEnabled)
                        }
                    }
                    MessageMenuAction.SHARE -> {
                        vm.shareChatItem(targetMsg)
                    }
                    MessageMenuAction.REPORT -> {
                        vm.reportPeer(targetMsg.senderId, targetMsg.senderName, targetMsg.id)
                    }
                }
                actionTargetMessageId = null
            },
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

    if (showBatchDeleteConfirm && selectedIds.isNotEmpty()) {
        val deleteCount = selectedIds.size
        BayDialog(
            title = "删除 $deleteCount 条消息？",
            message = "删除后将不会出现在你的本机消息记录中",
            confirmText = "删除",
            onConfirm = {
                val idsToDelete = selectedIds.toList()
                showBatchDeleteConfirm = false
                selectionMode = false
                selectedIds = emptySet()
                idsToDelete.forEach { vm.deleteMessageLocally(it) }
            },
            dismissText = "取消",
            onDismiss = { showBatchDeleteConfirm = false },
            destructive = true,
        )
    }

    forwardPayloadItems?.let { sourceItems ->
        val (payloads, skippedCount) = remember(sourceItems, room.messages) {
            vm.resolveForwardPayloads(sourceItems)
        }
        val payloadSummary = remember(payloads) {
            if (payloads.size == 1) {
                when (val only = payloads.first()) {
                    is ForwardPayload.Text -> only.text.take(60)
                    is ForwardPayload.FileUri -> {
                        if (only.mime.startsWith("image/")) "[图片]" else "[文件] ${only.fileName}"
                    }
                }
            } else {
                val preview = payloads.firstOrNull()?.let { first ->
                    when (first) {
                        is ForwardPayload.Text -> first.text.take(40)
                        is ForwardPayload.FileUri -> if (first.mime.startsWith("image/")) "[图片]" else "[文件] ${first.fileName}"
                    }
                }.orEmpty()
                if (preview.isNotEmpty()) "共 ${payloads.size} 条：$preview" else "共 ${payloads.size} 条内容"
            }
        }
        ForwardTargetSheet(
            currentRoomCode = room.roomCode,
            currentRoomTopic = room.roomInfo?.topic.orEmpty(),
            savedRooms = state.savedRooms,
            recentRooms = state.recentRooms,
            payloadSummary = payloadSummary,
            skippedCount = skippedCount,
            onConfirmTarget = { targetCode ->
                forwardPayloadItems = null
                selectionMode = false
                selectedIds = emptySet()
                vm.forwardToRoom(targetCode, payloads)
            },
            onDismiss = { forwardPayloadItems = null },
        )
    }

    if (showFavoritesPicker) {
        FavoritesSheet(
            favorites = state.favorites,
            hasActiveRoom = true,
            hapticEnabled = state.prefs.hapticEnabled,
            pickerMode = true,
            onSendFavorite = { fav -> vm.sendFavorite(fav) },
            onDeleteFavorite = { vm.removeFavorite(it) },
            onShowToast = { vm.showToast(it) },
            onDismiss = { showFavoritesPicker = false },
        )
    }

    if (showLeaveRoomConfirm) {
        BayDialog(
            title = "确认退出房间 #${room.roomCode}？",
            message = "退出后将断开当前房间的直连通道。",
            confirmText = "退出房间",
            onConfirm = {
                showLeaveRoomConfirm = false
                vm.leaveRoom()
            },
            dismissText = "取消",
            onDismiss = { showLeaveRoomConfirm = false },
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

    if (showPlaneComposer) {
        val candidateFiles = remember(room.messages.size, room.roomCode) {
            vm.planeCandidateFilesForActiveRoom()
        }
        PlaneComposerSheet(
            roomCode = room.roomCode,
            hasPassword = room.password.isNotBlank() || room.roomInfo?.hasPassword == true,
            isOwner = room.isOwner,
            submitting = state.creatingPlane,
            candidateFiles = candidateFiles,
            onConfirm = { msg, files, allowEntry ->
                vm.createPlaneFromActiveRoom(
                    message = msg,
                    files = files,
                    allowEntry = allowEntry,
                    onSuccess = { showPlaneComposer = false },
                )
            },
            onDismiss = { if (!state.creatingPlane) showPlaneComposer = false },
            onToast = { vm.showToast(it) },
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
 * 新版微信同款全屏按住说话浮层：
 * 居中呈现声波气泡 / 实时转文字预览，底部双圆形按钮区分「左取消 / 右转文字」，最底端呈宽弧形发送区。
 */
@Composable
private fun VoiceRecordingOverlay(
    visible: Boolean,
    zone: VoiceGestureZone,
    durationMs: Long,
    amplitude: Float,
    transcribePreview: String,
    isSpeechRecognizing: Boolean,
) {
    val colors = BayTheme.colors
    val isCountingDown = durationMs >= 50_000L
    val remSec = ((60_000L - durationMs + 999L) / 1000L).coerceIn(1L, 10L)
    val isCancel = zone == VoiceGestureZone.CANCEL
    val isConvert = zone == VoiceGestureZone.CONVERT_TEXT

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(140)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .navigationBarsPadding(),
        ) {
            // 中部状态气泡：松开发送显示声波，左滑变红，右滑「文 转文字」实时预览识别文案
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 84.dp)
                    .padding(horizontal = BaySpacing.xl),
            ) {
                val bubbleBg = when {
                    isCancel -> colors.danger
                    isConvert -> colors.surfaceElevated
                    else -> colors.accent
                }
                val bubbleTextColor = if (isConvert) colors.textPrimary else colors.onAccent

                Column(
                    modifier = Modifier
                        .width(if (isConvert) 264.dp else 210.dp)
                        .clip(BayShapes.lgShape)
                        .background(bubbleBg)
                        .padding(horizontal = BaySpacing.xl, vertical = BaySpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    when {
                        isCancel -> {
                            Icon(
                                imageVector = Icons.Rounded.Undo,
                                contentDescription = "松开取消发送",
                                tint = bubbleTextColor,
                                modifier = Modifier.size(30.dp),
                            )
                            Text(
                                text = "松开手指，取消发送",
                                style = BayType.subhead.copy(fontWeight = FontWeight.SemiBold),
                                color = bubbleTextColor,
                            )
                        }

                        isConvert -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = if (isSpeechRecognizing) "正在识别说话内容..." else "松开转为文字并编辑",
                                    style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                    color = colors.accent,
                                )
                            }
                            Text(
                                text = transcribePreview.ifBlank {
                                    if (isSpeechRecognizing) "请直接说出你想发送的文字..." else "松手进入语音转文字确认面板"
                                },
                                style = BayType.body,
                                color = if (transcribePreview.isNotBlank()) colors.textPrimary else colors.textSecondary,
                                textAlign = TextAlign.Start,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        else -> {
                            Row(
                                modifier = Modifier.height(34.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                val normAmp = amplitude.coerceIn(0f, 1f)
                                RECORD_METER_FACTORS.forEachIndexed { idx, factor ->
                                    val targetHeight by animateDpAsState(
                                        targetValue = 6.dp + 26.dp * maxOf(0.16f, normAmp) * factor,
                                        animationSpec = tween(durationMillis = 90),
                                        label = "voiceMeterBar_$idx",
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(targetHeight)
                                            .clip(BayShapes.fullShape)
                                            .background(colors.onAccent),
                                    )
                                }
                            }
                            Text(
                                text = if (isCountingDown) {
                                    "${formatDurationMs(durationMs)} · 还可以说 $remSec 秒"
                                } else {
                                    formatDurationMs(durationMs)
                                },
                                style = BayType.footnote.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                color = colors.onAccent,
                            )
                        }
                    }
                }
            }

            // 底部交互分区指示：左圆形「取消」+ 右圆形「文 转文字」+ 最底端发送提示条
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = BaySpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 42.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧：取消区域
                    val cancelScale by animateFloatAsState(
                        targetValue = if (isCancel) 1.18f else 1.0f,
                        animationSpec = tween(120),
                        label = "cancelScale",
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .scale(cancelScale)
                                .clip(CircleShape)
                                .background(if (isCancel) colors.danger else colors.surfaceElevated.copy(alpha = 0.90f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "取消",
                                tint = if (isCancel) Color.White else colors.textPrimary,
                                modifier = Modifier.size(26.dp),
                            )
                        }
                        Text(
                            text = if (isCancel) "松开取消" else "取消",
                            style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                            color = if (isCancel) colors.danger else Color.White.copy(alpha = 0.85f),
                        )
                    }

                    // 右侧：转文字区域
                    val convertScale by animateFloatAsState(
                        targetValue = if (isConvert) 1.18f else 1.0f,
                        animationSpec = tween(120),
                        label = "convertScale",
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .scale(convertScale)
                                .clip(CircleShape)
                                .background(if (isConvert) colors.accent else colors.surfaceElevated.copy(alpha = 0.90f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "文",
                                style = BayType.headline.copy(fontWeight = FontWeight.Bold),
                                color = if (isConvert) colors.onAccent else colors.textPrimary,
                            )
                        }
                        Text(
                            text = if (isConvert) "松开转文字" else "转文字",
                            style = BayType.caption.copy(fontWeight = FontWeight.Medium),
                            color = if (isConvert) colors.accent else Color.White.copy(alpha = 0.85f),
                        )
                    }
                }

                // 底部发送托盘指示条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BaySpacing.lg)
                        .height(54.dp)
                        .clip(BayShapes.lgShape)
                        .background(
                            if (zone == VoiceGestureZone.SEND) colors.surfaceElevated else colors.surfaceElevated.copy(alpha = 0.55f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when (zone) {
                            VoiceGestureZone.SEND -> "松开 发送 · 上滑左取消 / 右转文字"
                            VoiceGestureZone.CANCEL -> "松开取消发送"
                            VoiceGestureZone.CONVERT_TEXT -> "松开进入文字发送确认"
                        },
                        style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                        color = if (zone == VoiceGestureZone.SEND) colors.textPrimary else colors.textSecondary,
                    )
                }
            }
        }
    }
}

/**
 * 语音转文字确认抽屉：支持语音听写、手动修改及选择「发送文字」或「发送原语音」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceTranscribeConfirmSheet(
    text: String,
    hasDraftAudio: Boolean,
    isRecognizing: Boolean,
    onTextChange: (String) -> Unit,
    onToggleDictation: () -> Unit,
    onSendAudio: () -> Unit,
    onSendText: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    BayBottomSheet(
        onDismiss = onDismiss,
        title = "语音转文字",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            BayTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = if (isRecognizing) "正在识别语音..." else "可点击下方「语音听写」识别内容，或直接修改后发送",
                singleLine = false,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BayButton(
                    text = if (isRecognizing) "正在聆听，点此完成" else "点击语音听写",
                    onClick = onToggleDictation,
                    style = if (isRecognizing) BayButtonStyle.Primary else BayButtonStyle.Secondary,
                    size = BayButtonSize.Small,
                    leadingIcon = Icons.Rounded.Mic,
                )
                if (hasDraftAudio) {
                    Text(
                        text = "已保留刚刚录制的原语音",
                        style = BayType.caption,
                        color = colors.textSecondary,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayButton(
                    text = "取消",
                    onClick = onDismiss,
                    style = BayButtonStyle.Neutral,
                    size = BayButtonSize.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (hasDraftAudio) {
                    BayButton(
                        text = "发原语音",
                        onClick = onSendAudio,
                        style = BayButtonStyle.Secondary,
                        size = BayButtonSize.Medium,
                        modifier = Modifier.weight(1f),
                    )
                }
                BayButton(
                    text = "发送文字",
                    onClick = onSendText,
                    style = BayButtonStyle.Primary,
                    size = BayButtonSize.Medium,
                    enabled = text.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
