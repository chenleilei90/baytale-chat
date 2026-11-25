package com.baytale.baychat.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.LinkTransportType
import com.baytale.baychat.core.model.PeerProfile
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.ActiveRoomSession
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayActionSheet
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayGroup
import com.baytale.baychat.ui.design.BayGroupDivider
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayListRow
import com.baytale.baychat.ui.design.BaySwitch
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.RowTrailing
import com.baytale.baychat.ui.design.SheetAction
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

private const val RECALL_TIMEOUT_MS = 5 * 60 * 1000L

private val RETENTION_OPTIONS = listOf(
    0 to "阅后即焚",
    1 to "1 天",
    3 to "3 天",
    7 to "7 天",
)

private val MAX_FILE_OPTIONS = listOf(
    50 to "50MB",
    200 to "200MB",
    500 to "500MB",
    1024 to "1GB",
    2048 to "2GB",
    0 to "不限制",
)

/**
 * 长按消息操作菜单：支持快捷表情回应、引用回复、复制文本、保存图片、分享、撤回与屏蔽举报。
 */
@Composable
fun MessageActionSheet(
    item: ChatItem,
    selfPeerId: String,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val canRecall = item.isSelf &&
        item.kind != ChatKind.FILE &&
        item.kind != ChatKind.SYSTEM &&
        (System.currentTimeMillis() - item.timestamp <= RECALL_TIMEOUT_MS)

    val actions = buildList {
        add(
            SheetAction(
                label = "引用回复",
                icon = Icons.AutoMirrored.Rounded.Reply,
                onClick = { vm.setReplyingTo(item) },
            )
        )
        if (!item.text.isNullOrBlank() && (item.kind == ChatKind.TEXT || item.kind == ChatKind.CLIP)) {
            add(
                SheetAction(
                    label = "复制内容",
                    icon = Icons.Rounded.ContentCopy,
                    onClick = {
                        copyTextToClipboard(context, item.text)
                        vm.showToast("已复制到剪贴板")
                    },
                )
            )
        }
        if (item.kind == ChatKind.IMAGE) {
            add(
                SheetAction(
                    label = "保存图片到相册",
                    icon = Icons.Rounded.Download,
                    onClick = { vm.saveImageToGallery(item) },
                )
            )
        }
        if (item.kind == ChatKind.IMAGE || item.kind == ChatKind.FILE || item.kind == ChatKind.AUDIO) {
            add(
                SheetAction(
                    label = "系统分享",
                    icon = Icons.Rounded.Share,
                    onClick = { vm.shareChatItem(item) },
                )
            )
        }
        if (canRecall) {
            add(
                SheetAction(
                    label = "撤回消息",
                    icon = Icons.Rounded.Undo,
                    destructive = true,
                    onClick = { vm.recallMessage(item.id) },
                )
            )
        }
        if (!item.isSelf && item.kind != ChatKind.SYSTEM) {
            add(
                SheetAction(
                    label = "屏蔽「${item.senderName}」",
                    icon = Icons.Rounded.Block,
                    destructive = true,
                    onClick = { vm.blockPeer(item.senderId, item.senderName) },
                )
            )
            add(
                SheetAction(
                    label = "举报违规内容",
                    icon = Icons.Rounded.Flag,
                    onClick = { vm.reportPeer(item.senderId, item.senderName, item.id) },
                )
            )
        }
    }

    BayActionSheet(
        onDismiss = onDismiss,
        header = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BayShapes.mdShape)
                    .background(colors.surface)
                    .padding(horizontal = BaySpacing.sm, vertical = BaySpacing.sm),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                REACTION_EMOJIS.forEach { emoji ->
                    val reacted = selfPeerId in item.reactions[emoji].orEmpty()
                    Box(
                        modifier = Modifier
                            .bayClickable {
                                vm.reactMessage(item.id, emoji)
                                onDismiss()
                            }
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (reacted) colors.accentSoft else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = emoji,
                            style = BayType.title2,
                        )
                    }
                }
            }
        },
        actions = actions,
    )
}

/**
 * 房间邀请码与二维码抽屉弹窗：支持大号口令复制、ZXing 二维码、分享链接及临时房换码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteSheet(
    room: ActiveRoomSession,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val inviteUrl = remember(room.roomCode) { vm.buildInviteUrl(room.roomCode) }
    val qrBitmap = remember(inviteUrl) { FormatUtils.generateQrBitmap(inviteUrl, 560) }

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "邀请加入房间",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
        ) {
            BayCard(
                onClick = {
                    copyTextToClipboard(context, room.roomCode)
                    vm.showToast("已复制房间口令 ${room.roomCode}")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    Text(
                        text = "房间口令（点击复制）",
                        style = BayType.footnote,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = room.roomCode,
                        style = BayType.codeLarge,
                        color = colors.accent,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (qrBitmap != null) {
                // 白色安全静区仅用于保证深色与浅色主题下二维码均可稳定识别
                Box(
                    modifier = Modifier
                        .clip(BayShapes.lgShape)
                        .background(Color.White)
                        .padding(BaySpacing.md),
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "房间邀请二维码",
                        modifier = Modifier.size(196.dp),
                    )
                }
            }

            Text(
                text = "扫码或在浏览器打开 $inviteUrl 即可进入当前房间",
                style = BayType.footnote,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayButton(
                    text = "复制口令",
                    onClick = {
                        copyTextToClipboard(context, room.roomCode)
                        vm.showToast("已复制房间口令")
                    },
                    style = BayButtonStyle.Neutral,
                    size = BayButtonSize.Medium,
                    leadingIcon = Icons.Rounded.ContentCopy,
                    modifier = Modifier.weight(1f),
                )
                BayButton(
                    text = "复制链接",
                    onClick = {
                        copyTextToClipboard(context, inviteUrl)
                        vm.showToast("已复制邀请链接")
                    },
                    style = BayButtonStyle.Secondary,
                    size = BayButtonSize.Medium,
                    modifier = Modifier.weight(1f),
                )
            }

            BayButton(
                text = "分享邀请给好友",
                onClick = { shareRoomInvite(context, room.roomCode, inviteUrl) },
                style = BayButtonStyle.Primary,
                size = BayButtonSize.Large,
                leadingIcon = Icons.Rounded.Share,
                modifier = Modifier.fillMaxWidth(),
            )

            // 临时房轮换新口令（仅房主可用，防止旧口令泄露）
            if (room.isEphemeral && room.isOwner) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    BayButton(
                        text = "轮换新口令",
                        onClick = {
                            onDismiss()
                            vm.rotateEphemeralCode()
                        },
                        style = BayButtonStyle.Secondary,
                        size = BayButtonSize.Medium,
                        leadingIcon = Icons.Rounded.Refresh,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "生成新口令并作废旧口令，当前在线成员将自动跟随切换",
                        style = BayType.caption,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 房间设置抽屉：主题/密码编辑、B1 随机串门隐私开关、保鲜期/附件上限、成员管理与房主专属销毁控制。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoomSettingsSheet(
    state: BayChatUiState,
    room: ActiveRoomSession,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val isPasswordProtected = room.password.isNotBlank() || room.roomInfo?.hasPassword == true
    val effectiveMaxMB = vm.effectiveMaxFileMB()
    val modeLabel = if (room.roomInfo?.mode == RoomMode.FIXED.wireValue) "常驻房间" else "临时房间"

    var showProfileEditor by remember { mutableStateOf(false) }
    var draftTopic by remember(room.roomInfo?.topic) { mutableStateOf(room.roomInfo?.topic.orEmpty()) }
    var draftPassword by remember(room.password) { mutableStateOf(room.password) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDestroyConfirm by remember { mutableStateOf(false) }
    var peerActionTarget by remember { mutableStateOf<PeerProfile?>(null) }

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "房间设置",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
        ) {
            // 1. 房间概览与房主资料修改
            BayGroup(header = "房间信息") {
                BayListRow(
                    icon = Icons.Rounded.Lock,
                    iconTint = if (isPasswordProtected) colors.accent else colors.textSecondary,
                    title = "#${room.roomCode}",
                    subtitle = buildString {
                        append(modeLabel)
                        val topic = room.roomInfo?.topic?.trim().orEmpty()
                        if (topic.isNotEmpty()) append(" · $topic")
                    },
                    value = if (isPasswordProtected) "已加密" else "免密",
                    showDivider = room.isOwner,
                )

                if (room.isOwner) {
                    BayListRow(
                        icon = Icons.Rounded.Edit,
                        title = "修改房间主题与密码",
                        subtitle = if (showProfileEditor) "修改后将即时生效并同步成员" else "设置主题名称或调整访问密码",
                        trailing = RowTrailing.Chevron,
                        onClick = { showProfileEditor = !showProfileEditor },
                    )
                    if (showProfileEditor) {
                        BayGroupDivider(hasLeadingIcon = false)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(BaySpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            BayTextField(
                                value = draftTopic,
                                onValueChange = { draftTopic = it },
                                placeholder = "输入房间主题（可选）",
                            )
                            BayTextField(
                                value = draftPassword,
                                onValueChange = { draftPassword = it },
                                placeholder = "输入新密码（留空则取消密码）",
                                isPassword = true,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                            ) {
                                BayButton(
                                    text = "取消",
                                    onClick = { showProfileEditor = false },
                                    style = BayButtonStyle.Neutral,
                                    size = BayButtonSize.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                                BayButton(
                                    text = "保存修改",
                                    onClick = {
                                        vm.updateRoomProfile(
                                            topic = draftTopic,
                                            newPassword = draftPassword,
                                        )
                                        showProfileEditor = false
                                    },
                                    style = BayButtonStyle.Primary,
                                    size = BayButtonSize.Medium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            // 2. B1 隐私开关、保鲜期与附件上限
            BayGroup(header = "隐私与传输限制") {
                BayListRow(
                    icon = Icons.Rounded.Public,
                    title = "允许陌生人随机串门",
                    subtitle = when {
                        isPasswordProtected -> "当前房间已设密码，需先取消密码后才可开启"
                        !room.isOwner -> "仅房主可修改此开关"
                        else -> "关闭后仅可通过口令或邀请链接加入"
                    },
                    trailing = RowTrailing.Custom {
                        BaySwitch(
                            checked = !isPasswordProtected && (room.roomInfo?.allowRandom ?: false),
                            onCheckedChange = { checked -> vm.updateAllowRandom(checked) },
                            enabled = !isPasswordProtected && room.isOwner,
                        )
                    },
                    showDivider = true,
                )

                if (room.isOwner) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.md),
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        val expiryHint = room.historyMeta.expiresAt?.let { " · ${FormatUtils.formatExpiry(it)}" }.orEmpty()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "记录保鲜期（${room.historyMeta.messageCount} 条$expiryHint）",
                                style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                                color = colors.textPrimary,
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            RETENTION_OPTIONS.forEach { (days, label) ->
                                BayChip(
                                    label = label,
                                    selected = room.historyMeta.retentionDays == days,
                                    onClick = { vm.updateRetentionDays(days) },
                                )
                            }
                        }
                    }

                    BayGroupDivider(hasLeadingIcon = false)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.md),
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderZip,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "单附件上限（当前 ${FormatUtils.formatLimitMB(effectiveMaxMB)}）",
                                style = BayType.subhead.copy(fontWeight = FontWeight.Medium),
                                color = colors.textPrimary,
                            )
                        }
                        val cap = state.serverConfig.maxAttachmentMB
                        val availableLimits = MAX_FILE_OPTIONS.filter { (mb, _) ->
                            cap <= 0 || (mb in 1..cap)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                            verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                        ) {
                            availableLimits.forEach { (mb, label) ->
                                BayChip(
                                    label = label,
                                    selected = effectiveMaxMB == mb,
                                    onClick = { vm.updateRoomMaxFileMB(mb) },
                                )
                            }
                        }
                    }
                } else {
                    BayListRow(
                        icon = Icons.Rounded.Schedule,
                        title = "记录保鲜期",
                        value = formatRetentionBadge(room.historyMeta.retentionDays),
                        showDivider = true,
                    )
                    BayListRow(
                        icon = Icons.Rounded.FolderZip,
                        title = "单附件上限",
                        value = FormatUtils.formatLimitMB(effectiveMaxMB),
                    )
                }
            }

            // 3. 在线成员列表
            val maxPeers = room.roomInfo?.maxPeers ?: 6
            BayGroup(header = "在线成员 (${1 + room.peers.size}/$maxPeers)") {
                BayListRow(
                    title = "${state.profile.name}（我）",
                    subtitle = if (room.wsConnected) "信令在线" else "重连中",
                    trailing = RowTrailing.Custom {
                        BayAvatar(emojiId = state.profile.avatar, size = 32.dp)
                    },
                    showDivider = room.peers.isNotEmpty(),
                )

                val peerList = room.peers.values.toList()
                peerList.forEachIndexed { index, peer ->
                    val status = room.linkStatuses[peer.peerId]
                    val ready = status?.dataReady == true
                    val rtt = status?.rttMs?.let { " · ${it}ms" }.orEmpty()
                    val linkLabel = when {
                        !ready -> "正在建立连接"
                        status?.transport == LinkTransportType.RELAY_TURN -> "中转连接$rtt"
                        status?.transport == LinkTransportType.P2P_LAN -> "局域网直连$rtt"
                        else -> "P2P 直连$rtt"
                    }

                    BayListRow(
                        title = peer.name,
                        subtitle = linkLabel,
                        trailing = RowTrailing.Custom {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                            ) {
                                BayAvatar(emojiId = peer.avatar, size = 30.dp)
                                if (!ready) {
                                    BayIconButton(
                                        icon = Icons.Rounded.Refresh,
                                        contentDescription = "重新连接",
                                        onClick = { vm.reconnectPeer(peer.peerId) },
                                        style = BayIconStyle.Accent,
                                        size = 32.dp,
                                    )
                                }
                                BayIconButton(
                                    icon = Icons.Rounded.MoreHoriz,
                                    contentDescription = "成员操作",
                                    onClick = { peerActionTarget = peer },
                                    style = BayIconStyle.Plain,
                                    size = 32.dp,
                                )
                            }
                        },
                        showDivider = index < peerList.lastIndex,
                        onClick = { peerActionTarget = peer },
                    )
                }
            }

            // 4. 聊天记录与挂起工具
            BayGroup(header = "记录与会话") {
                BayListRow(
                    icon = Icons.Rounded.Download,
                    title = "导出聊天记录",
                    subtitle = if (room.messages.isEmpty()) "暂无可导出消息" else "保存为文本文件至下载目录",
                    trailing = RowTrailing.Chevron,
                    showDivider = true,
                    onClick = if (room.messages.isNotEmpty()) {
                        {
                            onDismiss()
                            vm.exportChatLog()
                        }
                    } else {
                        null
                    },
                )
                BayListRow(
                    icon = Icons.Rounded.PictureInPictureAlt,
                    title = "最小化到悬浮球",
                    subtitle = "保持连接与文件传输同时返回大厅",
                    trailing = RowTrailing.Chevron,
                    showDivider = room.isOwner,
                    onClick = {
                        onDismiss()
                        vm.minimizeRoom()
                    },
                )
                if (room.isOwner) {
                    BayListRow(
                        icon = Icons.Rounded.DeleteOutline,
                        title = "清空服务器历史记录",
                        destructive = true,
                        onClick = { showClearConfirm = true },
                    )
                }
            }

            // 5. 离开房间与房主专属销毁房间
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayButton(
                    text = "离开房间",
                    onClick = {
                        onDismiss()
                        vm.leaveRoom()
                    },
                    style = BayButtonStyle.Neutral,
                    size = BayButtonSize.Large,
                    leadingIcon = Icons.AutoMirrored.Rounded.ExitToApp,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 严格权限保护：仅 room.isOwner == true 时渲染销毁房间按钮
                if (room.isOwner) {
                    BayButton(
                        text = "销毁房间",
                        onClick = { showDestroyConfirm = true },
                        style = BayButtonStyle.DestructiveSoft,
                        size = BayButtonSize.Large,
                        leadingIcon = Icons.Rounded.DeleteForever,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    // 成员操作菜单（重连 / 屏蔽 / 举报）
    peerActionTarget?.let { target ->
        BayActionSheet(
            onDismiss = { peerActionTarget = null },
            title = target.name,
            actions = listOf(
                SheetAction(
                    label = "重建直连",
                    icon = Icons.Rounded.Refresh,
                    onClick = { vm.reconnectPeer(target.peerId) },
                ),
                SheetAction(
                    label = "屏蔽此成员",
                    icon = Icons.Rounded.Block,
                    destructive = true,
                    onClick = { vm.blockPeer(target.peerId, target.clientId, target.name) },
                ),
                SheetAction(
                    label = "举报违规内容",
                    icon = Icons.Rounded.Flag,
                    onClick = { vm.reportPeer(target.peerId, target.name) },
                ),
            ),
        )
    }

    if (showClearConfirm) {
        BayDialog(
            title = "清空服务器历史记录？",
            message = "将立即删除服务器缓存的本房间消息记录，并同步通知当前在线成员。",
            confirmText = "确认清空",
            onConfirm = {
                showClearConfirm = false
                onDismiss()
                vm.clearServerHistory()
            },
            dismissText = "取消",
            onDismiss = { showClearConfirm = false },
            destructive = true,
        )
    }

    if (showDestroyConfirm) {
        BayDialog(
            title = "确认销毁房间？",
            message = "销毁后将断开全部成员连接、作废当前口令并清除服务器缓存，该操作不可恢复。",
            confirmText = "确认销毁",
            onConfirm = {
                showDestroyConfirm = false
                onDismiss()
                vm.destroyRoom()
            },
            dismissText = "取消",
            onDismiss = { showDestroyConfirm = false },
            destructive = true,
        )
    }
}

/**
 * 全屏图片查看器（唯一允许使用 Color.Black 纯黑底色的组件）：支持双指缩放、双击放大与保存/分享原图。
 */
@Composable
fun ImageViewer(
    item: ChatItem,
    imageHeaders: Map<String, String>,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val model = remember(item.localFilePath, item.imageUrl, item.transfer?.localPath, item.transfer?.contentUri) {
        resolveImageModel(item)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showOverlay by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showOverlay = !showOverlay },
                        onDoubleTap = {
                            if (scale > 1.1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.4f
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = nextScale
                        offset = if (nextScale <= 1.01f) {
                            Offset.Zero
                        } else {
                            offset + pan
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (model != null) {
                val request = remember(model, imageHeaders) {
                    buildAuthedImageRequest(context, model, imageHeaders)
                }
                AsyncImage(
                    model = request,
                    contentDescription = item.caption ?: "图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                )
            }

            // 顶部关闭按钮
            AnimatedVisibility(
                visible = showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(BaySpacing.lg),
            ) {
                Box(
                    modifier = Modifier
                        .bayClickable(onClick = onDismiss)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "关闭图片查看器",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // 底部保存与分享按钮栏
            AnimatedVisibility(
                visible = showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = BaySpacing.xl, vertical = BaySpacing.xl),
            ) {
                Row(
                    modifier = Modifier
                        .clip(BayShapes.fullShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .bayClickable(onClick = onSave)
                            .clip(BayShapes.fullShape)
                            .padding(horizontal = BaySpacing.sm, vertical = BaySpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "保存原图",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "保存原图",
                            style = BayType.footnote,
                            color = Color.White,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .bayClickable(onClick = onShare)
                            .clip(BayShapes.fullShape)
                            .padding(horizontal = BaySpacing.sm, vertical = BaySpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = "分享图片",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "分享",
                            style = BayType.footnote,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}
