package com.baytale.baychat.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.ui.ActiveRoomSession
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 房间顶部导航栏：最小化挂起入口、房间标题与实时状态、搜索/二维码邀请/设置入口。
 */
@Composable
fun ChatTopBar(
    room: ActiveRoomSession,
    isMuted: Boolean,
    searchActive: Boolean,
    hasQuickSwitchTargets: Boolean,
    onMinimize: () -> Unit,
    onOpenQuickSwitch: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenSettings: () -> Unit,
    onQuickSendOriginalMedia: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val linkBadge = rememberLinkStatusBadge(room)
    val topic = room.roomInfo?.topic?.trim().orEmpty()
    val hasTopic = topic.isNotEmpty()
    val isPasswordProtected = room.password.isNotBlank() || room.roomInfo?.hasPassword == true
    val peerCount = 1 + room.peers.size
    val retentionLabel = formatRetentionBadge(room.historyMeta.retentionDays)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.chatBg),
    ) {
        // 顶部 P2PLink 守护服务运行与局域网保活状态条（实时联动当前信令状态，重连前夕柔和展示）
        val statusBarDotColor = if (room.wsConnected) colors.success else colors.accent
        val statusBarText = if (room.wsConnected) {
            "BayChat 守护服务运行中 · 局域网直连就绪"
        } else {
            "BayChat 守护服务运行中 · 正在同步消息"
        }
        val statusBarBadge = if (room.wsConnected) "已保活" else "收取中"
        val statusBarBadgeColor = if (room.wsConnected) colors.success else colors.accent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.accentSoft)
                .padding(horizontal = BaySpacing.md, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusBarDotColor),
                )
                Text(
                    text = statusBarText,
                    style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .clip(BayShapes.fullShape)
                    .background(colors.surface)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    text = statusBarBadge,
                    style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                    color = statusBarBadgeColor,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BaySpacing.md, vertical = BaySpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 最小化到悬浮球按钮，保持连接不断开
            BayIconButton(
                icon = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "最小化到悬浮球",
                onClick = onMinimize,
                style = BayIconStyle.Filled,
                size = 36.dp,
            )

            Spacer(modifier = Modifier.width(BaySpacing.sm))

            // 房间标题、密码标识与连接状态摘要（点击可弹出快速切换菜单或复制口令）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .bayClickable(onClick = onOpenQuickSwitch)
                    .clip(BayShapes.smShape)
                    .padding(horizontal = BaySpacing.xs, vertical = 2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    Text(
                        text = if (hasTopic) "$topic ($peerCount)" else "#${room.roomCode} ($peerCount)",
                        style = BayType.headline,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (hasTopic) {
                        Box(
                            modifier = Modifier
                                .clip(BayShapes.xsShape)
                                .background(colors.fill)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "#${room.roomCode}",
                                style = BayType.caption,
                                color = colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                    if (isPasswordProtected) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "已加密",
                            tint = colors.accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (isMuted) {
                        Icon(
                            imageVector = Icons.Rounded.NotificationsOff,
                            contentDescription = "消息免打扰",
                            tint = colors.textTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    if (hasQuickSwitchTargets) {
                        Icon(
                            imageVector = Icons.Rounded.UnfoldMore,
                            contentDescription = "切换房间",
                            tint = colors.textTertiary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(linkBadge.color),
                    )
                    Text(
                        text = "${peerCount}人 · ${linkBadge.label} · $retentionLabel",
                        style = BayType.caption.copy(fontWeight = FontWeight.Normal),
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(BaySpacing.xs))

            // 右侧工具动作组
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 一键发送相册 4K 原片快捷入口（对齐 P2PLink 原型）
                Row(
                    modifier = Modifier
                        .clip(BayShapes.fullShape)
                        .background(colors.accentSoft)
                        .bayClickable(onClick = onQuickSendOriginalMedia)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = "发送相册 4K 原片",
                        tint = colors.accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "原片",
                        style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.accent,
                    )
                }
                BayIconButton(
                    icon = Icons.Rounded.Search,
                    contentDescription = "搜索消息",
                    onClick = onToggleSearch,
                    style = if (searchActive) BayIconStyle.Accent else BayIconStyle.Plain,
                    size = 34.dp,
                )
                BayIconButton(
                    icon = Icons.Rounded.QrCode2,
                    contentDescription = "邀请码",
                    onClick = onOpenInvite,
                    style = BayIconStyle.Accent,
                    size = 34.dp,
                )
                BayIconButton(
                    icon = Icons.Rounded.MoreHoriz,
                    contentDescription = "聊天信息",
                    onClick = onOpenSettings,
                    style = BayIconStyle.Plain,
                    size = 34.dp,
                )
            }
        }

        // 发丝级底部分隔线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.separator),
        )
    }
}
