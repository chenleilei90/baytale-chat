package com.baytale.baychat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.FileTransferInfo
import com.baytale.baychat.core.model.LinkTransportType
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.model.TransferDirection
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.storage.AppUserPrefs
import com.baytale.baychat.core.storage.MediaDownloadHelper
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.ActiveRoomSession
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.theme.LocalBayColors
import com.baytale.baychat.ui.theme.themeOrbColor
import java.io.File

private val QUICK_EMOJIS = listOf("👍", "❤️", "😂", "🔥", "🎉", "🙏", "✨", "🚀", "😎", "🥰", "👀", "👌")
private val REACTION_BAR_EMOJIS = listOf("👍", "❤️", "😂", "🔥", "🎉", "👀")
private val ROOM_RETENTION_STEPS = listOf(0 to "阅后即焚", 1 to "1天", 2 to "2天", 3 to "3天", 5 to "5天", 7 to "7天")
private val ROOM_LIMIT_STEPS = listOf(50 to "50MB", 200 to "200MB", 500 to "500MB", 1024 to "1GB", 2048 to "2GB", 0 to "不限制")

// 原生极简聊天与 P2P 文件传输主界面
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatRoomScreen(
    state: BayChatUiState,
    room: ActiveRoomSession,
    inviteUrl: String,
    effectiveMaxFileMB: Int,
    onSendText: (String) -> Unit,
    onSendUris: (List<Uri>) -> Unit,
    onSendPendingShare: () -> Unit,
    onClearPendingShare: () -> Unit,
    onAcceptFile: (peerId: String, transferId: String, resumeIfAvailable: Boolean) -> Unit,
    onRejectFile: (peerId: String, transferId: String) -> Unit,
    onCancelTransfer: (peerId: String, transferId: String) -> Unit,
    onReconnectPeer: (peerId: String) -> Unit,
    onReplyMessage: (ChatItem?) -> Unit,
    onRecallMessage: (String) -> Unit,
    onReactMessage: (String, String) -> Unit,
    onSearchFilterChange: (String, String) -> Unit,
    onUpdateRetention: (Int) -> Unit,
    onUpdateMaxFileMB: (Int) -> Unit,
    onUpdatePrefs: (AppUserPrefs) -> Unit,
    onSelectTheme: (AppThemeMode) -> Unit,
    onExportLog: () -> Unit,
    onClearHistory: () -> Unit,
    onRotateCode: () -> Unit,
    onSwitchRecentRoom: (RecentRoom) -> Unit,
    onMinimizeRoom: () -> Unit,
    onLeaveRoom: () -> Unit,
    onDestroyRoom: () -> Unit,
    onToast: (String) -> Unit,
) {
    val colors = LocalBayColors.current
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showEmojiBar by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showDestroyConfirm by remember { mutableStateOf(false) }
    var selectedActionMessage by remember { mutableStateOf<ChatItem?>(null) }
    var previewImageSource by remember { mutableStateOf<Any?>(null) }

    // Android 原生多文件选择器（走 Storage Access Framework，零额外存储权限）
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onSendUris(uris)
    }

    // Android 官方 Photo Picker 多图选择器（Google Play 隐私推荐，零存储权限）
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) onSendUris(uris)
    }

    // 过滤后的可见消息列表
    val visibleMessages = remember(room.messages, room.searchKeyword, room.filterKind) {
        val kw = room.searchKeyword.trim().lowercase()
        room.messages.filter { m ->
            val kindOk = when (room.filterKind) {
                "image" -> m.kind == ChatKind.IMAGE
                "file" -> m.kind == ChatKind.FILE
                else -> true
            }
            if (!kindOk) return@filter false
            if (kw.isEmpty()) return@filter true
            listOfNotNull(m.text, m.caption, m.senderName, m.transfer?.fileName)
                .joinToString(" ")
                .lowercase()
                .contains(kw)
        }
    }

    // 有新消息自动滚动到底部
    LaunchedEffect(visibleMessages.size) {
        if (visibleMessages.isNotEmpty() && room.searchKeyword.isBlank() && room.filterKind == "all") {
            listState.animateScrollToItem(visibleMessages.lastIndex)
        }
    }

    // 汇总当前连接状态（局域网直连 / 公网直连 / TURN 中转 / 等待加入）
    val linkBadge = remember(room.peers, room.linkStatuses) {
        if (room.peers.isEmpty()) {
            Triple(colors.accent, "等待伙伴扫码加入", false)
        } else {
            val ready = room.peers.keys.mapNotNull { room.linkStatuses[it] }.filter { it.dataReady }
            if (ready.isEmpty()) {
                Triple(colors.amber, "正在打洞直连...", false)
            } else {
                val rtt = ready.firstNotNullOfOrNull { it.rttMs }?.let { " · ${it}ms" }.orEmpty()
                when {
                    ready.any { it.transport == LinkTransportType.RELAY_TURN } ->
                        Triple(colors.amber, "服务器中转$rtt", true)
                    ready.all { it.transport == LinkTransportType.P2P_LAN } ->
                        Triple(colors.emerald, "局域网直连$rtt", true)
                    else -> Triple(colors.emerald, "跨公网 P2P 直连$rtt", true)
                }
            }
        }
    }

    val qrBitmap = remember(inviteUrl) { FormatUtils.generateQrBitmap(inviteUrl, 520) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        // 顶部精简操作栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                // 左上角默认最小化挂起（不断开传输），彻底退出可在右上角设置内选择
                IconButton(onClick = onMinimizeRoom, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "挂起到后台返回大厅", tint = colors.textMain)
                }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            copyToClipboard(context, room.roomCode)
                            onToast("已复制房间口令 ${room.roomCode}")
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            text = "#${room.roomCode}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = colors.textMain,
                            maxLines = 1,
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(linkBadge.first),
                        )
                    }
                    Text(
                        text = "${linkBadge.second} (${room.peers.size + 1}人)",
                        fontSize = 11.sp,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onMinimizeRoom, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = "最小化挂起房间", tint = colors.textSub, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { showSearch = !showSearch }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = "查找消息",
                        tint = if (showSearch) colors.accent else colors.textSub,
                    )
                }
                IconButton(onClick = { showInviteDialog = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = "邀请二维码", tint = colors.accent)
                }
                IconButton(onClick = { showSettingsSheet = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Tune, contentDescription = "房间设置", tint = colors.textSub)
                }
            }
        }

        // 若从其他 App 分享内容进来，展示一键发送横幅
        if (state.pendingShare.hasContent) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.accentSoft)
                    .border(1.dp, colors.accent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "待发系统分享：${state.pendingShare.uris.size} 附件" +
                        if (!state.pendingShare.sharedText.isNullOrBlank()) " + 文本" else "",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onSendPendingShare,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    Text("立即发送", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onClearPendingShare, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.textSub, modifier = Modifier.size(16.dp))
                }
            }
        }

        // 搜索与分类筛选条
        if (showSearch) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = room.searchKeyword,
                    onValueChange = { onSearchFilterChange(it, room.filterKind) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索聊天文字、文件名或发送人...", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        IconButton(onClick = {
                            onSearchFilterChange("", "all")
                            showSearch = false
                        }) {
                            Icon(Icons.Rounded.Close, contentDescription = "关闭搜索")
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "全部", "image" to "🖼️ 图片", "file" to "📦 文件").forEach { (id, label) ->
                        FilterChip(
                            selected = room.filterKind == id,
                            onClick = { onSearchFilterChange(room.searchKeyword, id) },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accent,
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }
        }

        // 中央消息流或雷达二维码空态
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (room.messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(22.dp))
                                .background(Color.White)
                                .border(2.dp, colors.border, RoundedCornerShape(22.dp))
                                .clickable { showInviteDialog = true }
                                .padding(12.dp),
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "房间二维码",
                                modifier = Modifier.size(168.dp),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    Text(
                        text = room.roomCode,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 30.sp,
                        letterSpacing = 4.sp,
                        color = colors.accent,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (room.peers.isEmpty()) {
                            "电脑打开 chat.baytale.com 或好友扫码即可建立 P2P 满速直连"
                        } else {
                            "已连上伙伴，发消息、选照片或传大文件吧"
                        },
                        fontSize = 13.sp,
                        color = colors.textSub,
                        textAlign = TextAlign.Center,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                copyToClipboard(context, inviteUrl)
                                onToast("已复制直连邀请链接")
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("复制链接", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { shareInviteLink(context, room.roomCode, inviteUrl) },
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, colors.border),
                        ) {
                            Icon(Icons.Rounded.Share, null, tint = colors.textMain, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("发给好友", color = colors.textMain, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else if (visibleMessages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到符合筛选条件的消息", color = colors.textMuted, fontSize = 13.5.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleMessages, key = { it.id }) { item ->
                        ChatMessageRow(
                            item = item,
                            onLongPress = {
                                if (item.kind != ChatKind.SYSTEM) selectedActionMessage = item
                            },
                            onPreviewImage = { src -> previewImageSource = src },
                            onReact = { emoji -> onReactMessage(item.id, emoji) },
                            onAcceptFile = onAcceptFile,
                            onRejectFile = onRejectFile,
                            onCancelTransfer = onCancelTransfer,
                            onOpenLocalFile = { path, mime ->
                                if (!MediaDownloadHelper.openLocalFile(context, path, mime)) {
                                    onToast("文件已保存在系统「下载/BayChat」目录中")
                                }
                            },
                        )
                    }
                }
            }
        }

        // 正在输入提示
        if (room.typingPeers.isNotEmpty()) {
            val names = room.typingPeers.mapNotNull { room.peers[it]?.name }.joinToString("、")
            if (names.isNotBlank()) {
                Text(
                    text = "💬 $names 正在输入中...",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
                )
            }
        }

        // 底部精简输入坞
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 引用回复条
            room.replyingTo?.let { quote ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accentSoft)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Reply, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                        Text(
                            text = "回复 ${quote.senderName}：${quote.preview}",
                            fontSize = 12.sp,
                            color = colors.textSub,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { onReplyMessage(null) }, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "取消引用", modifier = Modifier.size(14.dp))
                    }
                }
            }

            // 表情快捷面板
            if (showEmojiBar) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceSubtle)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    QUICK_EMOJIS.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 22.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    inputText += emoji
                                    showEmojiBar = false
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp, max = 120.dp),
                placeholder = { Text("输入消息，或点下方发图片与任意大文件...", fontSize = 13.5.sp, color = colors.textMuted) },
                shape = RoundedCornerShape(14.dp),
                maxLines = 4,
            )

            // 底部单行清爽工具栏：文件 | 图片 | 表情  +  发送
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolChipButton(
                        icon = Icons.Rounded.AttachFile,
                        label = "文件",
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                    )
                    ToolChipButton(
                        icon = Icons.Rounded.Image,
                        label = "图片",
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                    )
                    IconButton(
                        onClick = { showEmojiBar = !showEmojiBar },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Mood,
                            contentDescription = "表情",
                            tint = if (showEmojiBar) colors.accent else colors.textSub,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Button(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendText(inputText)
                            inputText = ""
                            showEmojiBar = false
                        }
                    },
                    enabled = inputText.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    Text("发送", fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Rounded.Send, null, modifier = Modifier.size(14.dp))
                }
            }
        }
    }

    // 长按消息快捷动作菜单（表情回应 / 复制 / 引用 / 撤回）
    selectedActionMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { selectedActionMessage = null },
            shape = RoundedCornerShape(22.dp),
            containerColor = colors.surface,
            title = { Text("消息操作", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        REACTION_BAR_EMOJIS.forEach { em ->
                            Text(
                                text = em,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        onReactMessage(msg.id, em)
                                        selectedActionMessage = null
                                    }
                                    .padding(6.dp),
                            )
                        }
                    }
                    HorizontalDivider(color = colors.border)
                    if (msg.kind == ChatKind.TEXT && !msg.text.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                copyToClipboard(context, msg.text)
                                onToast("已复制文字")
                                selectedActionMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("复制文字内容", color = colors.textMain)
                        }
                    }
                    TextButton(
                        onClick = {
                            onReplyMessage(msg)
                            selectedActionMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("引用回复此消息", color = colors.textMain)
                    }
                    if (msg.isSelf && msg.kind != ChatKind.FILE) {
                        TextButton(
                            onClick = {
                                onRecallMessage(msg.id)
                                selectedActionMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("撤回此消息（同步删服务器缓存）", color = colors.danger, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedActionMessage = null }) { Text("关闭") }
            },
        )
    }

    // 邀请二维码弹窗
    if (showInviteDialog) {
        val isEphemeral = room.roomInfo?.mode != RoomMode.FIXED.wireValue
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = colors.surface,
            title = { Text("分享房间邀请", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (qrBitmap != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .background(Color.White)
                                .padding(10.dp),
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "邀请二维码",
                                modifier = Modifier.size(190.dp),
                            )
                        }
                    }
                    Text(
                        text = room.roomCode,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        fontSize = 26.sp,
                        letterSpacing = 3.sp,
                        color = colors.accent,
                    )
                    Text(
                        text = "好友用手机扫码或电脑打开 chat.baytale.com 输入口令即可直连",
                        fontSize = 12.sp,
                        color = colors.textMuted,
                        textAlign = TextAlign.Center,
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, room.roomCode)
                                onToast("已复制口令")
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("复制口令", fontSize = 12.5.sp) }
                        Button(
                            onClick = { shareInviteLink(context, room.roomCode, inviteUrl) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        ) { Text("分享链接", fontSize = 12.5.sp) }
                    }
                    if (isEphemeral) {
                        TextButton(onClick = {
                            showInviteDialog = false
                            onRotateCode()
                        }) {
                            Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("作废当前码并换一个新口令", fontSize = 12.5.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false }) { Text("完成") }
            },
        )
    }

    // 房间设置与成员 BottomSheet
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("房间成员 (${room.peers.size + 1}/${room.roomInfo?.maxPeers ?: 6})", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = colors.textMain)

                // 自己
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceSubtle)
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(FormatUtils.presetEmoji(state.profile.avatar), fontSize = 22.sp)
                    Column {
                        Text("${state.profile.name}（我）", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.textMain)
                        Text(if (room.wsConnected) "● 信令在线" else "● 重连中", fontSize = 11.5.sp, color = colors.emerald)
                    }
                }

                // 远端成员
                room.peers.values.forEach { peer ->
                    val st = room.linkStatuses[peer.peerId]
                    val ready = st?.dataReady == true
                    val sub = when {
                        !ready -> "正在打洞握手..."
                        st?.transport == LinkTransportType.RELAY_TURN -> "TURN 中转${st.rttMs?.let { " ${it}ms" }.orEmpty()}"
                        st?.transport == LinkTransportType.P2P_LAN -> "局域网 P2P${st.rttMs?.let { " ${it}ms" }.orEmpty()}"
                        else -> "公网 P2P 直连${st?.rttMs?.let { " ${it}ms" }.orEmpty()}"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surfaceSubtle)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(FormatUtils.presetEmoji(peer.avatar), fontSize = 22.sp)
                            Column {
                                Text(peer.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.textMain)
                                Text(sub, fontSize = 11.5.sp, color = if (ready) colors.emerald else colors.amber)
                            }
                        }
                        if (!ready) {
                            IconButton(onClick = { onReconnectPeer(peer.peerId) }) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "重建 P2P 连接", tint = colors.accent)
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.border)

                // 保鲜期设置
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val expiryHint = room.historyMeta.expiresAt?.let { " · ${FormatUtils.formatExpiry(it)}" }.orEmpty()
                    Text(
                        text = "⏱ 服务器缓存保鲜期（当前 ${room.historyMeta.messageCount} 条$expiryHint）",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = colors.textMain,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ROOM_RETENTION_STEPS.forEach { (days, label) ->
                            FilterChip(
                                selected = room.historyMeta.retentionDays == days,
                                onClick = { onUpdateRetention(days) },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = if (days == 0) colors.danger else colors.accent,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }
                }

                // 单附件上限
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "📦 单附件上限（当前 ${FormatUtils.formatLimitMB(effectiveMaxFileMB)}）",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = colors.textMain,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val cap = state.serverConfig.maxAttachmentMB
                        ROOM_LIMIT_STEPS.filter { cap <= 0 || (it.first in 1..cap) }.forEach { (mb, label) ->
                            FilterChip(
                                selected = effectiveMaxFileMB == mb,
                                onClick = { onUpdateMaxFileMB(mb) },
                                label = { Text(label, fontSize = 12.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.accent,
                                    selectedLabelColor = Color.White,
                                ),
                            )
                        }
                    }
                }

                // 传输偏好开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("自动接收 ≤${state.prefs.autoAcceptMaxMB}MB 小文件", fontSize = 13.5.sp, color = colors.textMain)
                    Switch(
                        checked = state.prefs.autoAcceptSmallFiles,
                        onCheckedChange = { onUpdatePrefs(state.prefs.copy(autoAcceptSmallFiles = it)) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("图片原图无损直发（关=省流压缩）", fontSize = 13.5.sp, color = colors.textMain)
                    Switch(
                        checked = state.prefs.imageOriginalMode,
                        onCheckedChange = { onUpdatePrefs(state.prefs.copy(imageOriginalMode = it)) },
                    )
                }

                // 主题色切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("主题色", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppThemeMode.entries.forEach { mode ->
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(themeOrbColor(mode))
                                    .border(
                                        width = if (state.themeMode == mode) 2.5.dp else 1.dp,
                                        color = if (state.themeMode == mode) colors.textMain else Color.White,
                                        shape = CircleShape,
                                    )
                                    .clickable { onSelectTheme(mode) },
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.border)

                // 最近房间快捷切换
                val otherRecents = state.recentRooms.filterNot { it.code.equals(room.roomCode, ignoreCase = true) }
                if (otherRecents.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("🕒 切换最近房间", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.textMain)
                        otherRecents.take(4).forEach { rec ->
                            OutlinedButton(
                                onClick = {
                                    showSettingsSheet = false
                                    onSwitchRecentRoom(rec)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("#${rec.code}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = colors.textMain)
                                    Text(
                                        text = (if (rec.mode == RoomMode.FIXED.wireValue) "常驻" else "闪传") +
                                            " · ${FormatUtils.formatRelativeTime(rec.lastVisitedAt)}",
                                        fontSize = 11.5.sp,
                                        color = colors.textMuted,
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.border)
                }

                // 管理操作按钮组
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showSettingsSheet = false
                            onExportLog()
                        },
                        enabled = room.messages.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出记录", fontSize = 12.5.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            showSettingsSheet = false
                            showClearConfirm = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.4f)),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, null, tint = colors.danger, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("清空缓存", color = colors.danger, fontSize = 12.5.sp)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showSettingsSheet = false
                            onLeaveRoom()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("退出并断开", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = colors.textMain)
                    }
                    Button(
                        onClick = {
                            showSettingsSheet = false
                            showDestroyConfirm = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                    ) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (room.roomInfo?.mode == RoomMode.FIXED.wireValue) "删除基地" else "销毁房间",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }

    // 全屏图片查看器
    previewImageSource?.let { imgSrc ->
        Dialog(
            onDismissRequest = { previewImageSource = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .clickable { previewImageSource = null },
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = imgSrc,
                    contentDescription = "图片大图预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                )
                IconButton(
                    onClick = { previewImageSource = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(12.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭预览", tint = Color.White)
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空服务器聊天记录？", fontWeight = FontWeight.Bold) },
            text = { Text("将立即从服务器物理抹除本房间全部缓存消息，并同步清空所有在线伙伴画面。") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                ) { Text("确认清空") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("取消") } },
        )
    }

    if (showDestroyConfirm) {
        AlertDialog(
            onDismissRequest = { showDestroyConfirm = false },
            title = { Text("确认销毁此房间？", fontWeight = FontWeight.Bold) },
            text = { Text("确认后将断开全体连接、作废房间口令，并物理清空服务器缓存。") },
            confirmButton = {
                Button(
                    onClick = {
                        showDestroyConfirm = false
                        onDestroyRoom()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                ) { Text("确认销毁") }
            },
            dismissButton = { TextButton(onClick = { showDestroyConfirm = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ToolChipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalBayColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceSubtle)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = colors.accent, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textMain)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ChatMessageRow(
    item: ChatItem,
    onLongPress: () -> Unit,
    onPreviewImage: (Any) -> Unit,
    onReact: (String) -> Unit,
    onAcceptFile: (String, String, Boolean) -> Unit,
    onRejectFile: (String, String) -> Unit,
    onCancelTransfer: (String, String) -> Unit,
    onOpenLocalFile: (String, String) -> Unit,
) {
    val colors = LocalBayColors.current

    if (item.kind == ChatKind.SYSTEM) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = item.text.orEmpty(),
                fontSize = 11.5.sp,
                color = colors.textSub,
                modifier = Modifier
                    .clip(RoundedCornerShape(99.dp))
                    .background(colors.surfaceSubtle)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        return
    }

    val align = if (item.isSelf) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Text(
                text = if (item.isSelf) "${item.senderName}（我）" else item.senderName,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textSub,
            )
            Text(
                text = FormatUtils.formatClock(item.timestamp),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = colors.textMuted,
            )
            if (item.fromCache) {
                Text("云端缓存", fontSize = 10.5.sp, color = colors.textMuted)
            }
        }

        Box(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress,
                ),
        ) {
            when (item.kind) {
                ChatKind.TEXT -> {
                    val shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (item.isSelf) 18.dp else 5.dp,
                        bottomEnd = if (item.isSelf) 5.dp else 18.dp,
                    )
                    Column(
                        modifier = Modifier
                            .clip(shape)
                            .then(
                                if (item.isSelf) {
                                    Modifier.background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary)))
                                } else {
                                    Modifier
                                        .background(colors.peerBubbleBg)
                                        .border(1.dp, colors.border, shape)
                                }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        item.replyTo?.let { q ->
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (item.isSelf) Color.White.copy(alpha = 0.18f) else colors.surfaceSubtle)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "@${q.senderName}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.isSelf) Color.White else colors.accent,
                                )
                                Text(
                                    text = q.preview,
                                    fontSize = 11.5.sp,
                                    color = if (item.isSelf) Color.White.copy(alpha = 0.9f) else colors.textSub,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Text(
                            text = item.text.orEmpty(),
                            fontSize = 14.5.sp,
                            lineHeight = 20.sp,
                            color = if (item.isSelf) colors.selfBubbleText else colors.textMain,
                        )
                    }
                }

                ChatKind.IMAGE -> {
                    val imgModel: Any? = when {
                        !item.localFilePath.isNullOrBlank() -> File(item.localFilePath)
                        !item.imageUrl.isNullOrBlank() -> {
                            if (item.imageUrl.startsWith("/")) File(item.imageUrl) else item.imageUrl
                        }
                        else -> null
                    }
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        border = BorderStroke(1.dp, colors.border),
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            if (imgModel != null) {
                                AsyncImage(
                                    model = imgModel,
                                    contentDescription = item.caption ?: "图片",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .widthIn(min = 160.dp, max = 260.dp)
                                        .heightIn(min = 110.dp, max = 250.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .clickable { onPreviewImage(imgModel) },
                                )
                            } else {
                                val tr = item.transfer
                                val pct = if (tr != null && tr.fileSize > 0) {
                                    (tr.transferredBytes.toFloat() / tr.fileSize.toFloat()).coerceIn(0f, 1f)
                                } else 0f
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("正在接收图片 ${(pct * 100).toInt()}%", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                                }
                            }
                            item.transfer?.savedDisplayPath?.let { saved ->
                                Text(
                                    text = "已存：$saved",
                                    fontSize = 10.5.sp,
                                    color = colors.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                                )
                            }
                        }
                    }
                }

                ChatKind.FILE, ChatKind.AUDIO -> {
                    item.transfer?.let { tr ->
                        FileTransferCard(
                            transfer = tr,
                            onAccept = { resume -> tr.targetPeerId?.let { pid -> onAcceptFile(pid, tr.transferId, resume) } },
                            onReject = { tr.targetPeerId?.let { pid -> onRejectFile(pid, tr.transferId) } },
                            onCancel = { tr.targetPeerId?.let { pid -> onCancelTransfer(pid, tr.transferId) } },
                            onOpen = {
                                val path = tr.localPath
                                if (!path.isNullOrBlank()) onOpenLocalFile(path, tr.mimeType)
                            },
                        )
                    }
                }

                ChatKind.SYSTEM -> Unit
            }
        }

        // 贴纸回应列表
        val reactions = item.reactions.filterValues { it.isNotEmpty() }
        if (reactions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                reactions.forEach { (emoji, users) ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(99.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(99.dp))
                            .clickable { onReact(emoji) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(emoji, fontSize = 12.5.sp)
                        Text(users.size.toString(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = colors.textSub)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileTransferCard(
    transfer: FileTransferInfo,
    onAccept: (resumeIfAvailable: Boolean) -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = LocalBayColors.current
    val rawProgress = if (transfer.fileSize > 0) {
        (transfer.transferredBytes.toFloat() / transfer.fileSize.toFloat()).coerceIn(0f, 1f)
    } else 1f
    val barProgress = if (transfer.status == TransferStatus.TRANSFERRING) maxOf(0.02f, rawProgress) else rawProgress
    val pctNum = rawProgress * 100f
    val pctLabel = if (pctNum > 0f && pctNum < 10f) String.format(java.util.Locale.US, "%.1f", pctNum) else pctNum.toInt().toString()
    val ext = transfer.fileName.substringAfterLast('.', "").take(4).uppercase()
    val hasResume = transfer.canResumeBytes > 0L

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.border),
        modifier = Modifier.width(288.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (ext.isNotBlank()) {
                        Text(ext, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                    } else {
                        Icon(Icons.Rounded.InsertDriveFile, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.fileName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        color = colors.textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${FormatUtils.formatBytes(transfer.fileSize)} · " +
                            if (transfer.direction == TransferDirection.SEND) {
                                transfer.targetPeerName?.let { "发给 $it" } ?: "P2P 直发"
                            } else "P2P 接收",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = colors.textMuted,
                    )
                }
            }

            if (transfer.status == TransferStatus.TRANSFERRING || transfer.status == TransferStatus.WAITING_ACCEPT) {
                LinearProgressIndicator(
                    progress = { barProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = colors.accent,
                    trackColor = colors.accentSoft,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = when {
                            transfer.status == TransferStatus.WAITING_ACCEPT -> "等待对方确认..."
                            transfer.transferredBytes == 0L -> "已确认，等待数据流..."
                            else -> "$pctLabel% · ${FormatUtils.formatBytes(transfer.transferredBytes)}/${FormatUtils.formatBytes(transfer.fileSize)} · ${FormatUtils.formatBytes(transfer.speedBps.toLong())}/s"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = colors.textSub,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (transfer.status == TransferStatus.TRANSFERRING && transfer.speedBps > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = FormatUtils.formatEta(transfer.etaSeconds),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = colors.textMuted,
                        )
                    }
                }
                if (transfer.resumedFromBytes > 0L) {
                    Text(
                        text = "⚡ 已从 ${FormatUtils.formatBytes(transfer.resumedFromBytes)} 断点自动续传",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (transfer.status) {
                    TransferStatus.OFFERING -> {
                        if (hasResume) {
                            Button(
                                onClick = { onAccept(true) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            ) {
                                Text("断点续传 (${FormatUtils.formatBytes(transfer.canResumeBytes)})", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { onAccept(false) },
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("重下", fontSize = 11.5.sp) }
                        } else {
                            Button(
                                onClick = { onAccept(true) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            ) {
                                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("接收", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        OutlinedButton(
                            onClick = onReject,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(10.dp),
                        ) { Text("婉拒", fontSize = 12.sp) }
                    }

                    TransferStatus.TRANSFERRING, TransferStatus.WAITING_ACCEPT -> {
                        OutlinedButton(
                            onClick = onCancel,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.5f)),
                        ) { Text("取消", fontSize = 12.sp, color = colors.danger) }
                    }

                    TransferStatus.COMPLETED -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = colors.emerald, modifier = Modifier.size(15.dp))
                            Text(
                                text = if (transfer.verified) "SHA-256 校验通过" else "传输完成",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.emerald,
                            )
                        }
                        if (transfer.direction == TransferDirection.RECEIVE && !transfer.localPath.isNullOrBlank()) {
                            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Icon(Icons.Rounded.FileOpen, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("打开", fontSize = 12.sp)
                            }
                        }
                    }

                    TransferStatus.REJECTED -> Text("已婉拒", fontSize = 12.sp, color = colors.textMuted)
                    TransferStatus.CANCELLED -> Text("已取消", fontSize = 12.sp, color = colors.textMuted)
                    TransferStatus.ERROR -> Text(
                        transfer.errorMessage ?: "传输异常",
                        fontSize = 12.sp,
                        color = colors.danger,
                    )
                }
            }

            if (transfer.status == TransferStatus.COMPLETED && !transfer.savedDisplayPath.isNullOrBlank()) {
                Text(
                    text = "已保存至 ${transfer.savedDisplayPath}",
                    fontSize = 10.5.sp,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("BayChat", text))
}

private fun shareInviteLink(context: Context, code: String, url: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "快来加入 BayChat 闪传房间 #$code，跨公网 P2P 满速传文件：$url")
    }
    context.startActivity(Intent.createChooser(sendIntent, "分享房间邀请"))
}
