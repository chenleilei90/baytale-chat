package com.baytale.baychat.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SystemUpdate
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.baytale.baychat.BuildConfig
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.PartialResumeItem
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.model.RoomSummary
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.model.TransferRecord
import com.baytale.baychat.core.storage.MediaDownloadHelper
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.theme.LocalBayColors
import com.baytale.baychat.ui.theme.themeOrbColor

private val RETENTION_OPTIONS = listOf(0 to "阅后即焚", 1 to "1天", 2 to "2天", 3 to "3天", 5 to "5天", 7 to "7天")
private val FILE_LIMIT_OPTIONS = listOf(50 to "50MB", 200 to "200MB", 500 to "500MB", 1024 to "1GB", 0 to "不限制")
private val TOPIC_PRESETS = listOf("", "⚡ 互传文件", "☕ 闲聊摸鱼", "🎵 分享资源", "📸 原图交流")

// 湾聊 v1.1.0 方案 B「清风极简」三 Tab 主界面：房间（建房/加入/随机串门/最近与基地）| 发现（公开广场）| 传输中心（断点续传与历史+自动更新）
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LobbyScreen(
    state: BayChatUiState,
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
    onRefreshPublicRooms: () -> Unit,
    onRequestQrScan: () -> Unit,
    onRemoveSavedRoom: (String) -> Unit,
    onRemoveRecentRoom: (String) -> Unit,
    onClearRecentRooms: () -> Unit,
    onUpdateProfile: (String, String) -> Unit,
    onSelectTheme: (AppThemeMode) -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onClearPendingShare: () -> Unit,
    onCopyInviteUrl: (String) -> Unit,
    onRefreshTransfers: () -> Unit,
    onDeletePartialTransfer: (String) -> Unit,
    onClearAllPartialTransfers: () -> Unit,
    onRemoveTransferRecord: (String) -> Unit,
    onClearTransferRecords: () -> Unit,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onToast: (String) -> Unit,
    onClearError: () -> Unit,
    onConsumePendingJoin: () -> Unit,
) {
    val colors = LocalBayColors.current
    val context = LocalContext.current

    var navTab by remember { mutableIntStateOf(0) } // 0=房间, 1=发现, 2=传输
    var roomActionTab by remember { mutableIntStateOf(0) } // 0=创建房间, 1=加入房间
    var roomMode by remember { mutableStateOf(RoomMode.EPHEMERAL) }
    var customCode by remember { mutableStateOf("") }
    var createPassword by remember { mutableStateOf("") }
    var allowRandom by remember { mutableStateOf(true) } // 默认打开，点击可关闭；设密码自动关闭
    var roomTopic by remember { mutableStateOf("") }
    var privatePairOnly by remember { mutableStateOf(false) }
    var retentionDays by remember { mutableIntStateOf(1) }
    var maxFileSizeMB by remember(state.serverConfig.defaultRoomMaxFileMB) {
        mutableIntStateOf(state.serverConfig.defaultRoomMaxFileMB)
    }
    var saveFavorite by remember { mutableStateOf(true) }

    var joinInput by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }
    var showProfileSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.pendingJoinCode) {
        val code = state.pendingJoinCode
        if (!code.isNullOrBlank()) {
            navTab = 0
            roomActionTab = 1
            joinInput = code
            onConsumePendingJoin()
        }
    }

    LaunchedEffect(navTab) {
        if (navTab == 1) onRefreshPublicRooms()
        if (navTab == 2) onRefreshTransfers()
    }

    Scaffold(
        containerColor = colors.background,
        bottomBar = {
            NavigationBar(
                containerColor = colors.surface,
                tonalElevation = 0.dp,
                modifier = Modifier.border(width = 0.5.dp, color = colors.border),
            ) {
                NavigationBarItem(
                    selected = navTab == 0,
                    onClick = { navTab = 0 },
                    icon = { Icon(Icons.Rounded.Bolt, contentDescription = "房间") },
                    label = { Text("房间", fontSize = 11.5.sp, fontWeight = if (navTab == 0) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        indicatorColor = colors.accentSoft,
                        unselectedIconColor = colors.textMuted,
                        unselectedTextColor = colors.textMuted,
                    ),
                )
                NavigationBarItem(
                    selected = navTab == 1,
                    onClick = { navTab = 1 },
                    icon = { Icon(Icons.Rounded.Explore, contentDescription = "发现") },
                    label = { Text("发现", fontSize = 11.5.sp, fontWeight = if (navTab == 1) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        indicatorColor = colors.accentSoft,
                        unselectedIconColor = colors.textMuted,
                        unselectedTextColor = colors.textMuted,
                    ),
                )
                NavigationBarItem(
                    selected = navTab == 2,
                    onClick = { navTab = 2 },
                    icon = { Icon(Icons.Rounded.Inventory2, contentDescription = "传输") },
                    label = { Text("传输", fontSize = 11.5.sp, fontWeight = if (navTab == 2) FontWeight.Bold else FontWeight.Medium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = colors.accent,
                        selectedTextColor = colors.accent,
                        indicatorColor = colors.accentSoft,
                        unselectedIconColor = colors.textMuted,
                        unselectedTextColor = colors.textMuted,
                    ),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .padding(bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            // 顶部清爽品牌栏 + 右上角「单行头像昵称胶囊」（彻底解决旧版改昵称竖排挤压问题）
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "湾聊",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 19.sp,
                                color = colors.textMain,
                                maxLines = 1,
                            )
                            Text(
                                text = "BayChat",
                                fontSize = 11.sp,
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
                            text = "${state.serverConfig.domain} · P2P 跨端直传",
                            fontSize = 11.sp,
                            color = colors.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // 右上角单行头像昵称胶囊：点击弹出底部抽屉改名/换头像/换主题/检查更新
                Row(
                    modifier = Modifier
                        .widthIn(max = 152.dp)
                        .clip(RoundedCornerShape(99.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(99.dp))
                        .clickable { showProfileSheet = true }
                        .padding(start = 5.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(FormatUtils.presetEmoji(state.profile.avatar), fontSize = 14.sp)
                    }
                    Text(
                        text = state.profile.name,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textMain,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "修改昵称与设置",
                        tint = colors.accent,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }

            // 若有新版本可用，顶部展示轻巧直连更新提示条
            state.availableUpdate?.let { rel ->
                UpdateBannerCard(
                    versionName = rel.versionName,
                    notes = rel.releaseNotes,
                    downloading = state.updateDownloading,
                    progress = state.updateProgress,
                    onInstall = onDownloadAppUpdate,
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

            when (navTab) {
                0 -> {
                    // ================= Tab 0：房间主页 =================
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        border = BorderStroke(1.dp, colors.border),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(15.dp),
                            verticalArrangement = Arrangement.spacedBy(13.dp),
                        ) {
                            // 创建 / 加入 分段 Tab
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(colors.surfaceSubtle)
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf("✨ 创建新房间", "🚀 加入房间").forEachIndexed { idx, label ->
                                    val selected = roomActionTab == idx
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                if (selected) Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))
                                                else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                                            )
                                            .clickable {
                                                roomActionTab = idx
                                                onClearError()
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = label,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.5.sp,
                                            color = if (selected) Color.White else colors.textSub,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }

                            if (roomActionTab == 0) {
                                // 双模式卡片
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ModeSelectCard(
                                        modifier = Modifier.weight(1f),
                                        selected = roomMode == RoomMode.EPHEMERAL,
                                        title = "闪传临时房",
                                        subtitle = "6位口令 · 聊完销毁",
                                        icon = { Icon(Icons.Rounded.LocalFireDepartment, null, tint = Color.White, modifier = Modifier.size(17.dp)) },
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
                                        subtitle = "自定义号 · 随时重连",
                                        icon = { Icon(Icons.Rounded.PushPin, null, tint = Color.White, modifier = Modifier.size(17.dp)) },
                                        badgeColors = listOf(Color(0xFF6366F1), Color(0xFF06B6D4)),
                                        onClick = {
                                            roomMode = RoomMode.FIXED
                                            if (retentionDays == 1) retentionDays = 3
                                        },
                                    )
                                }

                                if (roomMode == RoomMode.FIXED) {
                                    OutlinedTextField(
                                        value = customCode,
                                        onValueChange = { if (it.length <= 24) customCode = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("自定义基地房间号（留空随机）") },
                                        placeholder = { Text("如 my-desk、work-888") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(14.dp),
                                        trailingIcon = {
                                            IconButton(onClick = { customCode = "bay-${(1000..9999).random()}" }) {
                                                Icon(Icons.Rounded.Casino, contentDescription = "随机生成", tint = colors.accent)
                                            }
                                        },
                                    )
                                }

                                // 房间主题标签（可选，公开房展示在发现广场）
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("房间氛围标签（可选）", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
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

                                // 保鲜期选择
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("服务器消息保鲜期", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                                        Text(
                                            text = if (retentionDays == 0) "服务器零留存" else "最后聊天起保留 ${retentionDays} 天",
                                            fontSize = 12.sp,
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

                                // 单附件大小上限
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("单附件大小上限", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                                        Text(
                                            text = FormatUtils.formatLimitMB(maxFileSizeMB),
                                            fontSize = 12.sp,
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

                                // 房间容量
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    FilterChip(
                                        selected = privatePairOnly,
                                        onClick = { privatePairOnly = true },
                                        label = { Text("1v1 双人") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = colors.accent,
                                            selectedLabelColor = Color.White,
                                        ),
                                    )
                                    FilterChip(
                                        selected = !privatePairOnly,
                                        onClick = { privatePairOnly = false },
                                        label = { Text("小队 (${state.serverConfig.maxRoomPeers}人)") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = colors.accent,
                                            selectedLabelColor = Color.White,
                                        ),
                                    )
                                }

                                OutlinedTextField(
                                    value = createPassword,
                                    onValueChange = { createPassword = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("访问暗号密码（可选）") },
                                    placeholder = { Text(if (roomMode == RoomMode.FIXED) "留空免密，设暗号则仅凭密码进" else "留空免密秒进") },
                                    leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = colors.textMuted) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                )

                                // 允许陌生人随机串门开关（默认打开；点击关闭；若设置了密码自动锁定关闭）
                                val hasPasswordInput = createPassword.isNotBlank()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(colors.surfaceSubtle)
                                        .clickable(enabled = !hasPasswordInput) { allowRandom = !allowRandom }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(
                                            if (hasPasswordInput) Icons.Rounded.Lock else Icons.Rounded.Public,
                                            contentDescription = null,
                                            tint = if (!hasPasswordInput && allowRandom) colors.accent else colors.textMuted,
                                            modifier = Modifier.size(19.dp),
                                        )
                                        Column {
                                            Text(
                                                text = "允许陌生人随机串门",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = colors.textMain,
                                            )
                                            Text(
                                                text = if (hasPasswordInput) {
                                                    "已设暗号密码，自动禁止随机进入"
                                                } else if (allowRandom) {
                                                    "默认开启 · 传私密文件时可点击关闭"
                                                } else {
                                                    "已关闭 · 仅限知道口令的伙伴进入"
                                                },
                                                fontSize = 11.sp,
                                                color = colors.textMuted,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Switch(
                                        checked = !hasPasswordInput && allowRandom,
                                        enabled = !hasPasswordInput,
                                        onCheckedChange = { allowRandom = it },
                                        colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                                    )
                                }

                                if (roomMode == RoomMode.FIXED) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text("收藏到「我的秘密基地」", fontSize = 13.sp, color = colors.textSub)
                                        Switch(
                                            checked = saveFavorite,
                                            onCheckedChange = { saveFavorite = it },
                                            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent),
                                        )
                                    }
                                }

                                if (!state.lobbyError.isNullOrBlank()) {
                                    ErrorBanner(state.lobbyError)
                                }

                                Button(
                                    onClick = {
                                        onCreateRoom(
                                            roomMode,
                                            customCode,
                                            createPassword,
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
                                            text = if (roomMode == RoomMode.FIXED) "开启常驻秘密基地" else "生成闪传口令并出发",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            } else {
                                // 加入房间 Tab（统一扫码入口，无重复图标）
                                OutlinedTextField(
                                    value = joinInput,
                                    onValueChange = {
                                        joinInput = it
                                        onClearError()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("6位口令 / 基地号 / 分享链接") },
                                    placeholder = { Text("如 849205 或粘贴链接") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                            if (!text.isNullOrBlank()) {
                                                joinInput = text.trim()
                                                onClearError()
                                            }
                                        }) {
                                            Icon(Icons.Rounded.ContentPaste, contentDescription = "粘贴", tint = colors.accent)
                                        }
                                    },
                                )

                                OutlinedTextField(
                                    value = joinPassword,
                                    onValueChange = { joinPassword = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("房间密码（无密码留空）") },
                                    leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = colors.textMuted) },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                )

                                if (!state.lobbyError.isNullOrBlank()) {
                                    ErrorBanner(state.lobbyError)
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = onRequestQrScan,
                                        modifier = Modifier.height(50.dp),
                                        shape = RoundedCornerShape(15.dp),
                                        border = BorderStroke(1.2.dp, colors.accent),
                                    ) {
                                        Icon(Icons.Rounded.QrCodeScanner, null, tint = colors.accent)
                                        Spacer(Modifier.width(6.dp))
                                        Text("扫码", color = colors.accent, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = { onJoinRoom(joinInput, joinPassword) },
                                        enabled = !state.lobbyBusy && joinInput.isNotBlank(),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(50.dp),
                                        shape = RoundedCornerShape(15.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                                    ) {
                                        if (state.lobbyBusy) {
                                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                        } else {
                                            Text("立即直连进入", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 🎲 随机串门快捷盲盒卡（严格只进免密码且允许偶遇的房间）
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.surface),
                        border = BorderStroke(1.2.dp, colors.accent.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.randomJoining) { onRandomJoinRoom() },
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
                                onClick = onRandomJoinRoom,
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

                    // 🕒 最近访问房间
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
                }

                1 -> {
                    // ================= Tab 1：发现（随机串门 & 免密公开房广场） =================
                    DiscoverTabContent(
                        publicRooms = state.publicRooms,
                        loading = state.publicRoomsLoading,
                        randomJoining = state.randomJoining,
                        onRandomJoin = onRandomJoinRoom,
                        onRefresh = onRefreshPublicRooms,
                        onJoinPublicRoom = { code -> onJoinRoom(code, "") },
                        onQuickCreatePublicRoom = {
                            onCreateRoom(
                                RoomMode.EPHEMERAL,
                                "",
                                "",
                                false,
                                1,
                                state.serverConfig.defaultRoomMaxFileMB,
                                false,
                                true,
                                "☕ 闲聊摸鱼",
                            )
                        },
                    )
                }

                2 -> {
                    // ================= Tab 2：传输中心（断点续传 + 历史文件 + 应用内更新） =================
                    TransferCenterTabContent(
                        partials = state.partialTransfers,
                        records = state.transferRecords,
                        availableUpdate = state.availableUpdate,
                        updateChecking = state.updateChecking,
                        updateDownloading = state.updateDownloading,
                        updateProgress = state.updateProgress,
                        onRefresh = onRefreshTransfers,
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
                        onCheckUpdate = onCheckAppUpdate,
                        onDownloadUpdate = onDownloadAppUpdate,
                    )
                }
            }

            // 若存在最小化挂起浮窗，底部预留足够空间防遮挡
            if (state.activeRoom != null && state.roomMinimized) {
                Spacer(Modifier.height(82.dp))
            }
        }
    }

    // 单手底部滑出抽屉：修改昵称、头像、主题色、直连节点与检查更新
    if (showProfileSheet) {
        ProfileAndSettingsSheet(
            state = state,
            onDismiss = { showProfileSheet = false },
            onSaveProfile = onUpdateProfile,
            onSelectTheme = onSelectTheme,
            onSaveServerUrl = onUpdateServerUrl,
            onCheckUpdate = onCheckAppUpdate,
            onDownloadUpdate = onDownloadAppUpdate,
        )
    }
}

@Composable
private fun DiscoverTabContent(
    publicRooms: List<RoomSummary>,
    loading: Boolean,
    randomJoining: Boolean,
    onRandomJoin: () -> Unit,
    onRefresh: () -> Unit,
    onJoinPublicRoom: (String) -> Unit,
    onQuickCreatePublicRoom: () -> Unit,
) {
    val colors = LocalBayColors.current

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
                onClick = onRandomJoin,
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

    // 公开房实时列表
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
                Text("🌐 正在开放的免密房", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)
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

            if (publicRooms.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceSubtle)
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("当前还没有在线的免密公开房", fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = colors.textMain)
                    Text("建一间免密公开房，手机或网页端的小伙伴都能随机偶遇进来聊", fontSize = 12.sp, color = colors.textMuted)
                    Button(
                        onClick = onQuickCreatePublicRoom,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    ) {
                        Text("✨ 开一间免密公开房", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            } else {
                publicRooms.forEach { room ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surfaceSubtle)
                            .clickable { onJoinPublicRoom(room.code) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = room.topic.ifBlank { "房间 #${room.code}" },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = colors.textMain,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "#${room.code} · ${room.peerCount}/${room.maxPeers} 人在线 · ${if (room.mode == RoomMode.FIXED.wireValue) "常驻" else "闪传"}",
                                fontSize = 11.5.sp,
                                color = colors.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { onJoinPublicRoom(room.code) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        ) {
                            Text("串门", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferCenterTabContent(
    partials: List<PartialResumeItem>,
    records: List<TransferRecord>,
    availableUpdate: com.baytale.baychat.core.model.AppReleaseInfo?,
    updateChecking: Boolean,
    updateDownloading: Boolean,
    updateProgress: Float,
    onRefresh: () -> Unit,
    onDeletePartial: (String) -> Unit,
    onClearAllPartials: () -> Unit,
    onOpenRecord: (TransferRecord) -> Unit,
    onRemoveRecord: (String) -> Unit,
    onClearRecords: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
) {
    val colors = LocalBayColors.current
    val totalPartialBytes = partials.sumOf { it.savedBytes }

    // 待续传（断点保留区）
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
                    Text("⏸ 待续传断点缓存", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = colors.textMain)
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
                                    text = "已收 ${FormatUtils.formatBytes(item.savedBytes)} / ${FormatUtils.formatBytes(item.totalBytes)}" +
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

    // 最近已完成文件记录
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("✅ 最近传输记录", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = colors.textMain)
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

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceSubtle)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("还没有收发记录，进房互传文件后会自动汇总在这里", fontSize = 12.sp, color = colors.textMuted)
                }
            } else {
                records.take(30).forEach { rec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surfaceSubtle)
                            .clickable { onOpenRecord(rec) }
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    text = if (rec.direction == "send") "↑" else "↓",
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (rec.direction == "send") colors.accent else colors.emerald,
                                    fontSize = 13.sp,
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

    // 应用内自动检查更新卡（告别每次手动拷 APK）
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text("湾聊 v${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = colors.textMain)
                        Text(
                            text = if (availableUpdate != null) "发现新版 v${availableUpdate.versionName}，支持应用内直连升级" else "应用内直连服务器检查与覆盖升级",
                            fontSize = 11.5.sp,
                            color = if (availableUpdate != null) colors.accent else colors.textMuted,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (availableUpdate != null) {
                    Button(
                        onClick = onDownloadUpdate,
                        enabled = !updateDownloading,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                    ) {
                        Text(if (updateDownloading) "${(updateProgress * 100).toInt()}%" else "立即更新", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        enabled = !updateChecking,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(34.dp),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(if (updateChecking) "检查中..." else "检查更新", fontSize = 12.sp)
                    }
                }
            }
            if (updateDownloading) {
                LinearProgressIndicator(
                    progress = { updateProgress.coerceIn(0.02f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = colors.accent,
                    trackColor = colors.accentSoft,
                )
            }
        }
    }
}

@Composable
private fun UpdateBannerCard(
    versionName: String,
    notes: String,
    downloading: Boolean,
    progress: Float,
    onInstall: () -> Unit,
) {
    val colors = LocalBayColors.current
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.accentSoft),
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("🚀 发现新版本 v$versionName", fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp, color = colors.accent)
                    if (notes.isNotBlank()) {
                        Text(notes, fontSize = 11.5.sp, color = colors.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onInstall,
                    enabled = !downloading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                ) {
                    Text(if (downloading) "${(progress * 100).toInt()}%" else "立即升级", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (downloading) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0.02f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(99.dp)),
                    color = colors.accent,
                    trackColor = colors.surface,
                )
            }
        }
    }
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

// 单手底部滑出抽屉：改昵称、选头像、换主题、服务器地址与检查更新
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfileAndSettingsSheet(
    state: BayChatUiState,
    onDismiss: () -> Unit,
    onSaveProfile: (String, String) -> Unit,
    onSelectTheme: (AppThemeMode) -> Unit,
    onSaveServerUrl: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
) {
    val colors = LocalBayColors.current
    var name by remember(state.profile.name) { mutableStateOf(state.profile.name) }
    var avatar by remember(state.profile.avatar) { mutableStateOf(state.profile.avatar) }
    var serverUrl by remember(state.prefs.serverUrl) { mutableStateOf(state.prefs.serverUrl) }
    var showServerField by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 实时预览头
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceSubtle)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(colors.accentSoft)
                        .border(1.5.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(FormatUtils.presetEmoji(avatar), fontSize = 25.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name.trim().ifBlank { "匿名伙伴" },
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        color = colors.textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("免注册身份 · 修改后进房实时同步", fontSize = 11.5.sp, color = colors.textMuted)
                }
            }

            // 昵称输入 + 随机骰子
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
                label = { Text("显示昵称 (${name.trim().length}/24)") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(
                        onClick = { name = FormatUtils.generateRandomName() },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Casino, contentDescription = "随机昵称", tint = colors.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("随机", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.accent)
                    }
                },
            )

            // 萌趣头像选择
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择头像", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatUtils.PRESET_AVATARS.forEach { (id, emoji) ->
                        val picked = avatar == id
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (picked) colors.accentSoft else colors.surfaceSubtle)
                                .border(if (picked) 2.dp else 1.dp, if (picked) colors.accent else colors.border, RoundedCornerShape(13.dp))
                                .clickable { avatar = id },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 20.sp)
                        }
                    }
                }
            }

            // 界面风格切换
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceSubtle)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("界面主题", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
                    Text(state.themeMode.label, fontSize = 11.sp, color = colors.textMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        val selected = state.themeMode == mode
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(themeOrbColor(mode))
                                .border(
                                    width = if (selected) 2.5.dp else 1.dp,
                                    color = if (selected) colors.textMain else Color.White,
                                    shape = CircleShape,
                                )
                                .clickable { onSelectTheme(mode) },
                        )
                    }
                }
            }

            // 应用内版本更新检查条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceSubtle)
                    .clickable {
                        if (state.availableUpdate != null) onDownloadUpdate() else onCheckUpdate()
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (state.availableUpdate != null) Icons.Rounded.SystemUpdate else Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = if (state.availableUpdate != null) colors.accent else colors.emerald,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("版本与自动更新", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.textMain)
                }
                Text(
                    text = when {
                        state.updateDownloading -> "下载中 ${(state.updateProgress * 100).toInt()}%"
                        state.availableUpdate != null -> "升级 v${state.availableUpdate.versionName} ›"
                        state.updateChecking -> "检查中..."
                        else -> "v${BuildConfig.VERSION_NAME} · 点此检查"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.availableUpdate != null) colors.accent else colors.textMuted,
                )
            }

            // 自建服务器地址（默认折叠保持清爽）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceSubtle)
                    .clickable { showServerField = !showServerField }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("直连节点", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colors.textSub)
                Text(
                    text = serverUrl.removePrefix("https://").removePrefix("http://") + if (showServerField) " ⌄" else " ›",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (showServerField) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("自定义服务器地址") },
                    placeholder = { Text("https://chat.baytale.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(color = colors.border)

            Button(
                onClick = {
                    onSaveProfile(name, avatar)
                    if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                        onSaveServerUrl(serverUrl)
                    }
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Text("保存修改", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}
