package com.baytale.baychat.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.PeerProfile
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.ActiveRoomSession
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayActionSheet
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayEmptyState
import com.baytale.baychat.ui.design.BayGroup
import com.baytale.baychat.ui.design.BayGroupDivider
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayListRow
import com.baytale.baychat.ui.design.BaySegmented
import com.baytale.baychat.ui.design.BaySwitch
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.RowTrailing
import com.baytale.baychat.ui.design.SheetAction
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

private val URL_EXTRACT_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val HISTORY_SEARCH_TABS = listOf("全部", "图片", "文件", "链接")

private sealed interface MemberGridCell {
    data class Self(val peerId: String, val name: String, val avatar: String, val isOwner: Boolean) : MemberGridCell
    data class Peer(val profile: PeerProfile) : MemberGridCell
    data object InviteMore : MemberGridCell
}

/**
 * 微信式全屏「聊天信息」房间页（替代原 RoomSettingsSheet）：
 * 涵盖成员宫格（支持双击头像拍一拍）、房间信息与主题密码编辑、查找聊天记录、消息免打扰、
 * 房主权限配置（保鲜期/附件上限/随机串门）、导出与最小化、清空聊天记录及退出/解散房间。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun RoomInfoScreen(
    visible: Boolean,
    state: BayChatUiState,
    room: ActiveRoomSession,
    vm: BayChatViewModel,
    onOpenInvite: () -> Unit,
    onJumpToMessage: (String) -> Unit,
    onRequestLeaveRoom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val focusManager = LocalFocusManager.current
    val isPasswordProtected = room.password.isNotBlank() || room.roomInfo?.hasPassword == true
    val effectiveMaxMB = vm.effectiveMaxFileMB()
    val modeLabel = if (room.roomInfo?.mode == RoomMode.FIXED.wireValue) "常驻房间" else "临时房间"
    val totalMembers = 1 + room.peers.size
    val isMuted = room.roomCode.uppercase() in state.mutedRooms

    var showProfileEditor by remember(room.roomCode) { mutableStateOf(false) }
    var draftTopic by remember(room.roomInfo?.topic) { mutableStateOf(room.roomInfo?.topic.orEmpty()) }
    var draftPassword by remember(room.password) { mutableStateOf(room.password) }
    var showHistorySearch by remember(room.roomCode) { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDestroyConfirm by remember { mutableStateOf(false) }
    var peerActionTarget by remember { mutableStateOf<PeerProfile?>(null) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        BackHandler(enabled = visible && !showHistorySearch) {
            onDismiss()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bg)
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                // 顶栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface)
                        .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    BayIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回聊天",
                        onClick = onDismiss,
                        style = BayIconStyle.Filled,
                        size = 36.dp,
                    )
                    Text(
                        text = "聊天信息 ($totalMembers)",
                        style = BayType.headline,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.separator),
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
                ) {
                    // 1. 微信式 5 列成员宫格
                    val cells = remember(room.selfPeerId, state.profile, room.isOwner, room.peers) {
                        buildList {
                            add(MemberGridCell.Self(room.selfPeerId, state.profile.name, state.profile.avatar, room.isOwner))
                            room.peers.values.forEach { add(MemberGridCell.Peer(it)) }
                            add(MemberGridCell.InviteMore)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(BayShapes.mdShape)
                            .background(colors.surface)
                            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.md),
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
                    ) {
                        cells.chunked(5).forEach { rowCells ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                            ) {
                                rowCells.forEach { cell ->
                                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                                        when (cell) {
                                            is MemberGridCell.Self -> {
                                                val selfInteraction = remember { MutableInteractionSource() }
                                                Column(
                                                    modifier = Modifier
                                                        .clip(BayShapes.smShape)
                                                        .combinedClickable(
                                                            interactionSource = selfInteraction,
                                                            indication = null,
                                                            onClick = {},
                                                            onDoubleClick = { vm.nudgePeer(cell.peerId, cell.name) },
                                                        )
                                                        .padding(vertical = 2.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    BayAvatar(emojiId = cell.avatar, size = 48.dp)
                                                    Text(
                                                        text = cell.name,
                                                        style = BayType.caption,
                                                        color = colors.textSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        textAlign = TextAlign.Center,
                                                    )
                                                    if (cell.isOwner) {
                                                        Box(
                                                            modifier = Modifier
                                                                .clip(BayShapes.fullShape)
                                                                .background(colors.accentSoft)
                                                                .padding(horizontal = 6.dp, vertical = 1.dp),
                                                        ) {
                                                            Text(
                                                                text = "房主",
                                                                style = BayType.caption,
                                                                color = colors.accent,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            is MemberGridCell.Peer -> {
                                                val peerInteraction = remember(cell.profile.peerId) { MutableInteractionSource() }
                                                Column(
                                                    modifier = Modifier
                                                        .clip(BayShapes.smShape)
                                                        .combinedClickable(
                                                            interactionSource = peerInteraction,
                                                            indication = null,
                                                            onClick = { peerActionTarget = cell.profile },
                                                            onDoubleClick = {
                                                                vm.nudgePeer(cell.profile.peerId, cell.profile.name)
                                                            },
                                                        )
                                                        .padding(vertical = 2.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    BayAvatar(emojiId = cell.profile.avatar, size = 48.dp)
                                                    Text(
                                                        text = cell.profile.name,
                                                        style = BayType.caption,
                                                        color = colors.textSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        textAlign = TextAlign.Center,
                                                    )
                                                }
                                            }
                                            is MemberGridCell.InviteMore -> {
                                                Column(
                                                    modifier = Modifier
                                                        .clip(BayShapes.smShape)
                                                        .bayClickable(onClick = onOpenInvite)
                                                        .padding(vertical = 2.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(48.dp)
                                                            .clip(BayShapes.mdShape)
                                                            .background(colors.fill),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Rounded.Add,
                                                            contentDescription = "邀请成员",
                                                            tint = colors.textSecondary,
                                                            modifier = Modifier.size(22.dp),
                                                        )
                                                    }
                                                    Text(
                                                        text = "邀请",
                                                        style = BayType.caption,
                                                        color = colors.textSecondary,
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                repeat(5 - rowCells.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    // 2. 房间基本信息与主题/密码编辑
                    BayGroup(header = "房间信息") {
                        BayListRow(
                            icon = Icons.Rounded.QrCode2,
                            iconTint = colors.accent,
                            title = "房间口令",
                            value = "#${room.roomCode}",
                            trailing = RowTrailing.Chevron,
                            showDivider = true,
                            onClick = onOpenInvite,
                        )
                        BayListRow(
                            icon = Icons.Rounded.Edit,
                            title = "房间主题",
                            value = room.roomInfo?.topic?.takeIf { it.isNotBlank() } ?: "未设置",
                            trailing = if (room.isOwner) RowTrailing.Chevron else RowTrailing.None,
                            showDivider = true,
                            onClick = if (room.isOwner) {
                                { showProfileEditor = !showProfileEditor }
                            } else {
                                null
                            },
                        )
                        BayListRow(
                            icon = Icons.Rounded.Forum,
                            title = "房间类型",
                            value = modeLabel,
                            showDivider = true,
                        )
                        BayListRow(
                            icon = Icons.Rounded.Lock,
                            iconTint = if (isPasswordProtected) colors.accent else colors.textSecondary,
                            title = "访问密码",
                            value = if (isPasswordProtected) "已加密" else "免密",
                            trailing = if (room.isOwner) RowTrailing.Chevron else RowTrailing.None,
                            showDivider = room.isOwner && showProfileEditor,
                            onClick = if (room.isOwner) {
                                { showProfileEditor = !showProfileEditor }
                            } else {
                                null
                            },
                        )
                        if (room.isOwner && showProfileEditor) {
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
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(
                                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                    ),
                                )
                                BayTextField(
                                    value = draftPassword,
                                    onValueChange = { draftPassword = it },
                                    placeholder = "输入新密码（留空则取消密码）",
                                    isPassword = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = {
                                            focusManager.clearFocus()
                                            vm.updateRoomProfile(
                                                topic = draftTopic,
                                                newPassword = draftPassword,
                                            )
                                            showProfileEditor = false
                                        },
                                    ),
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

                    // 3. 查找聊天记录、导出与悬浮挂起
                    BayGroup {
                        BayListRow(
                            icon = Icons.Rounded.Search,
                            title = "查找聊天记录",
                            trailing = RowTrailing.Chevron,
                            showDivider = true,
                            onClick = { showHistorySearch = true },
                        )
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
                            onClick = {
                                onDismiss()
                                vm.minimizeRoom()
                            },
                        )
                    }

                    // 4. 消息免打扰
                    BayGroup {
                        BayListRow(
                            icon = Icons.Rounded.NotificationsOff,
                            title = "消息免打扰",
                            subtitle = "开启后新消息不响铃与弹窗，仍接收文件确认提醒",
                            trailing = RowTrailing.Switch(
                                checked = isMuted,
                                onChange = { vm.setRoomMuted(room.roomCode, it) },
                            ),
                        )
                    }

                    // 5. 房间隐私与传输限制（房主可调，成员只读）
                    BayGroup(header = if (room.isOwner) "房主权限与传输限制" else "传输限制") {
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

                    // 6. 清空聊天记录
                    BayGroup {
                        BayListRow(
                            icon = Icons.Rounded.DeleteOutline,
                            title = "清空聊天记录",
                            subtitle = if (room.isOwner) "删除服务器缓存与当前记录" else "仅清空本机记录",
                            destructive = true,
                            onClick = { showClearConfirm = true },
                        )
                    }

                    // 7. 底部微信式危险操作按钮（退出房间 / 房主解散房间）
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 50.dp)
                                .clip(BayShapes.mdShape)
                                .background(colors.surface)
                                .bayClickable {
                                    onDismiss()
                                    onRequestLeaveRoom()
                                }
                                .padding(vertical = BaySpacing.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "退出房间",
                                style = BayType.headline,
                                color = colors.danger,
                            )
                        }

                        if (room.isOwner) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 50.dp)
                                    .clip(BayShapes.mdShape)
                                    .background(colors.danger.copy(alpha = if (colors.isDark) 0.20f else 0.12f))
                                    .bayClickable { showDestroyConfirm = true }
                                    .padding(vertical = BaySpacing.md),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "解散房间",
                                    style = BayType.headline,
                                    color = colors.danger,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(BaySpacing.lg))
                }
            }

            // 7b. 全屏查找聊天记录二级页
            ChatHistorySearchPage(
                visible = showHistorySearch,
                state = state,
                room = room,
                onSelectMessage = { msgId ->
                    showHistorySearch = false
                    onDismiss()
                    onJumpToMessage(msgId)
                },
                onDismiss = { showHistorySearch = false },
            )
        }
    }

    // 成员操作菜单（重连 / 拍一拍 / 屏蔽 / 举报）
    peerActionTarget?.let { target ->
        BayActionSheet(
            onDismiss = { peerActionTarget = null },
            title = target.name,
            actions = listOf(
                SheetAction(
                    label = "拍一拍",
                    icon = Icons.Rounded.TouchApp,
                    onClick = { vm.nudgePeer(target.peerId, target.name) },
                ),
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
        if (room.isOwner) {
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
        } else {
            BayDialog(
                title = "清空聊天记录？",
                message = "仅清空本机记录，不影响其他成员与服务器缓存。",
                confirmText = "确认清空",
                onConfirm = {
                    showClearConfirm = false
                    onDismiss()
                    vm.clearLocalMessages()
                },
                dismissText = "取消",
                onDismiss = { showClearConfirm = false },
                destructive = true,
            )
        }
    }

    if (showDestroyConfirm) {
        BayDialog(
            title = "确认解散房间？",
            message = "解散后将断开全部成员连接、作废当前口令并清除服务器缓存，该操作不可恢复。",
            confirmText = "确认解散",
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
 * 微信式查找聊天记录二级全屏页：支持 全部 / 图片 / 文件 / 链接 分类检索与关键词高亮定位跳转。
 */
@Composable
private fun ChatHistorySearchPage(
    visible: Boolean,
    state: BayChatUiState,
    room: ActiveRoomSession,
    onSelectMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    var query by remember(room.roomCode, visible) { mutableStateOf("") }
    var selectedTab by remember(room.roomCode, visible) { mutableIntStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        BackHandler(enabled = visible) {
            onDismiss()
        }

        val visibleSource = remember(room.messages, state.blockedPeerIds) {
            room.messages.filter { msg ->
                msg.kind != ChatKind.SYSTEM &&
                    !msg.isSelfRecall &&
                    (msg.isSelf || msg.senderId !in state.blockedPeerIds)
            }
        }
        val imageHeaders = remember(room.roomCode, room.password) {
            if (room.password.isBlank()) emptyMap() else mapOf("X-Room-Password" to room.password)
        }
        val kw = query.trim()

        val matchedItems = remember(visibleSource, selectedTab, kw) {
            when (selectedTab) {
                1 -> visibleSource.filter { msg ->
                    msg.kind == ChatKind.IMAGE &&
                        resolveImageModel(msg) != null &&
                        (kw.isEmpty() || msg.senderName.contains(kw, ignoreCase = true) ||
                            msg.transfer?.fileName?.contains(kw, ignoreCase = true) == true)
                }
                2 -> visibleSource.filter { msg ->
                    msg.kind == ChatKind.FILE &&
                        (kw.isEmpty() || msg.senderName.contains(kw, ignoreCase = true) ||
                            msg.transfer?.fileName?.contains(kw, ignoreCase = true) == true)
                }
                3 -> visibleSource.filter { msg ->
                    (msg.kind == ChatKind.TEXT || msg.kind == ChatKind.CLIP) &&
                        !msg.text.isNullOrBlank() &&
                        URL_EXTRACT_REGEX.containsMatchIn(msg.text) &&
                        (kw.isEmpty() || msg.text.contains(kw, ignoreCase = true) ||
                            msg.senderName.contains(kw, ignoreCase = true))
                }
                else -> visibleSource.filter { msg ->
                    (msg.kind == ChatKind.TEXT || msg.kind == ChatKind.CLIP) &&
                        !msg.text.isNullOrBlank() &&
                        (kw.isEmpty() || msg.text.contains(kw, ignoreCase = true) ||
                            msg.senderName.contains(kw, ignoreCase = true))
                }
            }.asReversed()
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayIconButton(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回聊天信息",
                    onClick = onDismiss,
                    style = BayIconStyle.Filled,
                    size = 36.dp,
                )
                BayTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索聊天记录",
                    leadingIcon = Icons.Rounded.Search,
                    trailing = if (query.isNotEmpty()) {
                        {
                            BayIconButton(
                                icon = Icons.Rounded.Close,
                                contentDescription = "清除关键词",
                                onClick = { query = "" },
                                style = BayIconStyle.Plain,
                                size = 28.dp,
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocusRequester),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.sm),
            ) {
                BaySegmented(
                    options = HISTORY_SEARCH_TABS,
                    selected = selectedTab,
                    onSelect = { selectedTab = it },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.separator),
            )

            if (matchedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    BayEmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = "暂无匹配记录",
                        message = "可尝试更换关键词或切换分类标签",
                    )
                }
            } else if (selectedTab == 1) {
                // 图片 4 列网格
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(BaySpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    items(matchedItems, key = { it.id }) { imgItem ->
                        val model = remember(imgItem.id, imgItem.localFilePath, imgItem.imageUrl) {
                            resolveImageModel(imgItem)
                        }
                        val context = LocalContext.current
                        val request = remember(model, imageHeaders) {
                            model?.let { buildAuthedImageRequest(context, it, imageHeaders) }
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = "图片消息",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(BayShapes.xsShape)
                                .background(colors.fill)
                                .bayClickable { onSelectMessage(imgItem.id) },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(BaySpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                ) {
                    itemsIndexed(matchedItems, key = { _, item -> item.id }) { _, item ->
                        HistorySearchRow(
                            item = item,
                            keyword = kw,
                            isLinkTab = selectedTab == 3,
                            onClick = { onSelectMessage(item.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySearchRow(
    item: ChatItem,
    keyword: String,
    isLinkTab: Boolean,
    onClick: () -> Unit,
) {
    val colors = BayTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BayShapes.mdShape)
            .background(colors.surface)
            .bayClickable(onClick = onClick)
            .padding(horizontal = BaySpacing.md, vertical = BaySpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
    ) {
        if (item.kind == ChatKind.FILE) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(BayShapes.xsShape)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.InsertDriveFile,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else if (isLinkTab) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(BayShapes.xsShape)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            BayAvatar(emojiId = item.senderAvatar, size = 40.dp)
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.senderName,
                    style = BayType.footnote.copy(fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(BaySpacing.sm))
                Text(
                    text = formatWeChatMessageTime(item.timestamp),
                    style = BayType.caption,
                    color = colors.textTertiary,
                )
            }

            if (item.kind == ChatKind.FILE) {
                val tr = item.transfer
                Text(
                    text = tr?.fileName ?: "文件",
                    style = BayType.callout,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (tr != null && tr.fileSize > 0L) {
                    Text(
                        text = FormatUtils.formatBytes(tr.fileSize),
                        style = BayType.caption,
                        color = colors.textSecondary,
                    )
                }
            } else {
                val body = item.text.orEmpty()
                val highlighted = remember(body, keyword, colors.accent) {
                    buildKeywordAnnotated(body, keyword, colors.accent)
                }
                Text(
                    text = highlighted,
                    style = BayType.callout,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun buildKeywordAnnotated(
    text: String,
    keyword: String,
    highlightColor: androidx.compose.ui.graphics.Color,
) = buildAnnotatedString {
    if (keyword.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }
    var cursor = 0
    while (cursor < text.length) {
        val idx = text.indexOf(keyword, startIndex = cursor, ignoreCase = true)
        if (idx < 0) {
            append(text.substring(cursor))
            break
        }
        if (idx > cursor) {
            append(text.substring(cursor, idx))
        }
        withStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold)) {
            append(text.substring(idx, idx + keyword.length))
        }
        cursor = idx + keyword.length
    }
}
