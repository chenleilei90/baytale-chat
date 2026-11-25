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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.baytale.baychat.core.model.AppThemeMode
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.theme.LocalBayColors
import com.baytale.baychat.ui.theme.themeOrbColor

private val RETENTION_OPTIONS = listOf(0 to "阅后即焚", 1 to "1天", 2 to "2天", 3 to "3天", 5 to "5天", 7 to "7天")
private val FILE_LIMIT_OPTIONS = listOf(50 to "50MB", 200 to "200MB", 500 to "500MB", 1024 to "1GB", 0 to "不限制")

// 原生大厅主界面：建房 / 扫码或口令加入 / 我的常驻秘密基地 / 接收外部系统分享
@OptIn(ExperimentalLayoutApi::class)
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
    ) -> Unit,
    onJoinRoom: (rawInput: String, password: String) -> Unit,
    onRequestQrScan: () -> Unit,
    onRemoveSavedRoom: (String) -> Unit,
    onRemoveRecentRoom: (String) -> Unit,
    onClearRecentRooms: () -> Unit,
    onUpdateProfile: (String, String) -> Unit,
    onSelectTheme: (AppThemeMode) -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onClearPendingShare: () -> Unit,
    onCopyInviteUrl: (String) -> Unit,
    onClearError: () -> Unit,
    onConsumePendingJoin: () -> Unit,
) {
    val colors = LocalBayColors.current
    val context = LocalContext.current

    var activeTab by remember { mutableIntStateOf(0) } // 0=建房, 1=加入
    var roomMode by remember { mutableStateOf(RoomMode.EPHEMERAL) }
    var customCode by remember { mutableStateOf("") }
    var createPassword by remember { mutableStateOf("") }
    var privatePairOnly by remember { mutableStateOf(false) }
    var retentionDays by remember { mutableIntStateOf(1) }
    var maxFileSizeMB by remember(state.serverConfig.defaultRoomMaxFileMB) {
        mutableIntStateOf(state.serverConfig.defaultRoomMaxFileMB)
    }
    var saveFavorite by remember { mutableStateOf(true) }

    var joinInput by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }

    var showSettingsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.pendingJoinCode) {
        val code = state.pendingJoinCode
        if (!code.isNullOrBlank()) {
            activeTab = 1
            joinInput = code
            onConsumePendingJoin()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 顶部胶囊导航
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                }
                Column {
                    Text("BayChat", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = colors.textMain)
                    Text(
                        text = "${state.serverConfig.domain} · P2P 跨端直传",
                        fontSize = 11.5.sp,
                        color = colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // 快捷扫码入口
                IconButton(onClick = onRequestQrScan) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = "扫码进房", tint = colors.accent)
                }
                IconButton(onClick = { showSettingsDialog = true }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "个人与服务器设置", tint = colors.textSub)
                }
            }
        }

        // 若通过系统「分享到 BayChat」带入了文件/文本，展示待发横幅
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
                        Text(
                            text = "已收到系统分享内容",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = colors.accent,
                        )
                        val desc = buildString {
                            if (state.pendingShare.uris.isNotEmpty()) append("${state.pendingShare.uris.size} 个文件/图片")
                            if (!state.pendingShare.sharedText.isNullOrBlank()) {
                                if (isNotEmpty()) append(" + ")
                                append("一段文本")
                            }
                            append("，创建或加入房间即可直接发送")
                        }
                        Text(desc, fontSize = 12.sp, color = colors.textSub)
                    }
                    IconButton(onClick = onClearPendingShare) {
                        Icon(Icons.Rounded.Close, contentDescription = "取消分享", tint = colors.textSub)
                    }
                }
            }
        }

        // 主卡片：创建房间 / 加入房间
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 分段 Tab 切换
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceSubtle)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf("✨ 创建新房间", "🚀 加入房间").forEachIndexed { idx, label ->
                        val selected = activeTab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (selected) Brush.linearGradient(listOf(colors.accent, colors.accentSecondary))
                                    else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                                )
                                .clickable {
                                    activeTab = idx
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
                            )
                        }
                    }
                }

                if (activeTab == 0) {
                    // 双模式糖果卡
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ModeSelectCard(
                            modifier = Modifier.weight(1f),
                            selected = roomMode == RoomMode.EPHEMERAL,
                            title = "闪传盲盒房",
                            subtitle = "6位口令 · 聊完销毁",
                            icon = { Icon(Icons.Rounded.LocalFireDepartment, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                            badgeColors = listOf(Color(0xFFFF5487), Color(0xFFFF965A)),
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
                            icon = { Icon(Icons.Rounded.PushPin, null, tint = Color.White, modifier = Modifier.size(18.dp)) },
                            badgeColors = listOf(Color(0xFF8B5CF6), Color(0xFF06B6D4)),
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

                    // 房间容量 + 密码
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = privatePairOnly,
                            onClick = { privatePairOnly = true },
                            label = { Text("1v1 私密") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.accent,
                                selectedLabelColor = Color.White,
                            ),
                        )
                        FilterChip(
                            selected = !privatePairOnly,
                            onClick = { privatePairOnly = false },
                            label = { Text("小队(${state.serverConfig.maxRoomPeers}人)") },
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
                        placeholder = { Text(if (roomMode == RoomMode.FIXED) "建议设个暗号" else "留空免密秒进") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, null, tint = colors.textMuted) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                    )

                    if (roomMode == RoomMode.FIXED) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("收藏到下方「我的秘密基地」", fontSize = 13.sp, color = colors.textSub)
                            Switch(checked = saveFavorite, onCheckedChange = { saveFavorite = it })
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
                            )
                        },
                        enabled = !state.lobbyBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
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
                            Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    // 加入房间 Tab
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
                            shape = RoundedCornerShape(16.dp),
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
                            shape = RoundedCornerShape(16.dp),
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

        // 最近访问房间（自动记录临时闪传房与固定房，支持一键重进与清空）
        if (state.recentRooms.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("🕒 最近房间", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)
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

        // 我的常驻秘密基地
        if (state.savedRooms.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = colors.surface),
                border = BorderStroke(1.dp, colors.border),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("📌 我的秘密基地", fontWeight = FontWeight.ExtraBold, fontSize = 14.5.sp, color = colors.textMain)
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

        // 我的资料轻卡片
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surface),
            border = BorderStroke(1.dp, colors.border),
            modifier = Modifier.clickable { showSettingsDialog = true },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(FormatUtils.presetEmoji(state.profile.avatar), fontSize = 20.sp)
                    }
                    Column {
                        Text(state.profile.name, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = colors.textMain)
                        Text("免注册 · 与网页端 chat.baytale.com 互通", fontSize = 11.5.sp, color = colors.textMuted)
                    }
                }
                TextButton(onClick = { showSettingsDialog = true }) {
                    Text("改昵称", color = colors.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        // 若有后台挂起房间浮窗，底部额外留白防止遮挡资料卡
        if (state.activeRoom != null && state.roomMinimized) {
            Spacer(Modifier.height(78.dp))
        }
    }

    if (showSettingsDialog) {
        ProfileAndServerDialog(
            state = state,
            onDismiss = { showSettingsDialog = false },
            onSaveProfile = onUpdateProfile,
            onSelectTheme = onSelectTheme,
            onSaveServerUrl = onUpdateServerUrl,
        )
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
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) colors.accentSoft else colors.surfaceSubtle)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.accent else colors.border,
                shape = RoundedCornerShape(18.dp),
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(badgeColors)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = colors.textMain)
        Text(subtitle, fontSize = 11.5.sp, color = colors.textSub)
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
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceSubtle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("#${room.code}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = colors.textMain)
            if (room.hasPassword) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(13.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Button(
                onClick = onEnter,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Text("进入", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "复制链接", tint = colors.textSub, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "移除", tint = colors.textMuted, modifier = Modifier.size(16.dp))
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
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceSubtle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("#${room.code}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.textMain)
                if (room.hasPassword) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(12.dp))
                }
            }
            Text(
                text = (if (room.mode == RoomMode.FIXED.wireValue) "📌 常驻" else "🔥 闪传") +
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
                shape = RoundedCornerShape(10.dp),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileAndServerDialog(
    state: BayChatUiState,
    onDismiss: () -> Unit,
    onSaveProfile: (String, String) -> Unit,
    onSelectTheme: (AppThemeMode) -> Unit,
    onSaveServerUrl: (String) -> Unit,
) {
    val colors = LocalBayColors.current
    var name by remember(state.profile.name) { mutableStateOf(state.profile.name) }
    var avatar by remember(state.profile.avatar) { mutableStateOf(state.profile.avatar) }
    var serverUrl by remember(state.prefs.serverUrl) { mutableStateOf(state.prefs.serverUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = colors.surface,
        title = { Text("修改昵称、头像与设置", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 24) name = it },
                    label = { Text("显示昵称 (${name.trim().length}/24)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { name = FormatUtils.generateRandomName() }) {
                            Icon(Icons.Rounded.Casino, contentDescription = "随机生成昵称", tint = colors.accent)
                        }
                    },
                )
                Text("选择头像", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatUtils.PRESET_AVATARS.forEach { (id, emoji) ->
                        val picked = avatar == id
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (picked) colors.accentSoft else colors.surfaceSubtle)
                                .border(if (picked) 2.dp else 1.dp, if (picked) colors.accent else colors.border, CircleShape)
                                .clickable { avatar = id },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 19.sp)
                        }
                    }
                }
                Text("界面主题色", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = colors.textSub)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppThemeMode.entries.forEach { mode ->
                        val selected = state.themeMode == mode
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(themeOrbColor(mode))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) colors.textMain else Color.White,
                                    shape = CircleShape,
                                )
                                .clickable { onSelectTheme(mode) },
                        )
                    }
                }
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("服务器地址（支持自建节点）") },
                    placeholder = { Text("https://chat.baytale.com") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSaveProfile(name, avatar)
                    if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
                        onSaveServerUrl(serverUrl)
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
