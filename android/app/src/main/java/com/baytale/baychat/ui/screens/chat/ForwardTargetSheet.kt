package com.baytale.baychat.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.RecentRoom
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.model.SavedFixedRoom
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayGroupDivider
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

private data class ForwardTargetEntry(
    val code: String,
    val title: String,
    val subtitle: String,
    val isCurrent: Boolean,
    val hasPassword: Boolean,
)

/**
 * 微信风格转发目标房间选择抽屉：置顶「当前房间」，其下按口令去重列出已收藏常驻房与最近房间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardTargetSheet(
    currentRoomCode: String,
    currentRoomTopic: String,
    savedRooms: List<SavedFixedRoom>,
    recentRooms: List<RecentRoom>,
    payloadSummary: String,
    skippedCount: Int,
    onConfirmTarget: (roomCode: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    var pendingConfirmTarget by remember { mutableStateOf<ForwardTargetEntry?>(null) }

    val targets = remember(currentRoomCode, currentRoomTopic, savedRooms, recentRooms) {
        val currentUpper = currentRoomCode.trim().uppercase()
        val list = mutableListOf<ForwardTargetEntry>()
        list.add(
            ForwardTargetEntry(
                code = currentUpper,
                title = "当前房间 #$currentUpper",
                subtitle = currentRoomTopic.ifBlank { "直接发送到当前会话" },
                isCurrent = true,
                hasPassword = false,
            )
        )
        val seen = linkedSetOf(currentUpper)
        savedRooms.forEach { saved ->
            val code = saved.code.trim().uppercase()
            if (code.isNotEmpty() && seen.add(code)) {
                list.add(
                    ForwardTargetEntry(
                        code = code,
                        title = saved.alias.takeIf { it.isNotBlank() } ?: "#$code",
                        subtitle = if (saved.alias.isNotBlank()) "#$code · 常驻房间" else "常驻房间",
                        isCurrent = false,
                        hasPassword = saved.hasPassword,
                    )
                )
            }
        }
        recentRooms.forEach { recent ->
            val code = recent.code.trim().uppercase()
            if (code.isNotEmpty() && seen.add(code)) {
                val modeText = if (recent.mode == RoomMode.FIXED.wireValue) "常驻房间" else "临时房间"
                list.add(
                    ForwardTargetEntry(
                        code = code,
                        title = "#$code",
                        subtitle = "$modeText · ${FormatUtils.formatRelativeTime(recent.lastVisitedAt)}",
                        isCurrent = false,
                        hasPassword = recent.hasPassword,
                    )
                )
            }
        }
        list.toList()
    }

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "选择一个房间",
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(BayShapes.mdShape)
                .background(if (colors.isDark) colors.surface else colors.bg),
            contentPadding = PaddingValues(vertical = BaySpacing.xs),
        ) {
            itemsIndexed(
                items = targets,
                key = { _, item -> item.code },
            ) { index, entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .bayClickable { pendingConfirmTarget = entry }
                        .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(BayShapes.smShape)
                            .background(if (entry.isCurrent) colors.accentSoft else colors.fill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Forum,
                            contentDescription = null,
                            tint = if (entry.isCurrent) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                        ) {
                            Text(
                                text = entry.title,
                                style = BayType.callout.copy(fontWeight = FontWeight.Medium),
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.hasPassword) {
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = "已加密",
                                    tint = colors.textTertiary,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                        Text(
                            text = entry.subtitle,
                            style = BayType.footnote,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (index < targets.lastIndex) {
                    BayGroupDivider(hasLeadingIcon = true)
                }
            }
        }
        Spacer(modifier = Modifier.size(BaySpacing.sm))
    }

    pendingConfirmTarget?.let { target ->
        val confirmBody = buildString {
            append(payloadSummary)
            if (!target.isCurrent) {
                append("\n将离开当前房间并进入 #${target.code} 发送")
            }
            if (skippedCount > 0) {
                append("\n${skippedCount} 条暂不支持转发（语音或未下载文件）")
            }
        }
        BayDialog(
            title = "发送给 #${target.code}",
            message = confirmBody,
            confirmText = "发送",
            onConfirm = {
                val chosenCode = target.code
                pendingConfirmTarget = null
                onConfirmTarget(chosenCode)
            },
            dismissText = "取消",
            onDismiss = { pendingConfirmTarget = null },
        )
    }
}
