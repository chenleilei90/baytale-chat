package com.baytale.baychat.ui.screens.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.MessageSendStatus
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.VoicePlaybackState
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

/**
 * 统一消息气泡行：兼容系统通知、撤回态、文本气泡、引用回复、图片、文件、语音与剪贴板卡片。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    item: ChatItem,
    selfPeerId: String,
    isFirstInGroup: Boolean,
    isLastInGroup: Boolean,
    hapticEnabled: Boolean,
    imageHeaders: Map<String, String>,
    voicePlayback: VoicePlaybackState,
    playedVoiceIds: Set<String>,
    snapshotTransferredBytes: Long? = null,
    snapshotTotalBytes: Long? = null,
    snapshotSpeedBps: Long? = null,
    snapshotEtaSeconds: Int? = null,
    isHighlighted: Boolean = false,
    onLongPress: () -> Unit,
    onReEditRecall: (String) -> Unit,
    onJumpToReply: (String) -> Unit,
    onDoubleClickText: (String) -> Unit,
    onPeerClick: (peerId: String, peerName: String) -> Unit,
    onRetrySend: (messageId: String) -> Unit,
    onReact: (emoji: String) -> Unit,
    onPreviewImage: () -> Unit,
    onAcceptFile: (peerId: String, transferId: String, resumeIfAvailable: Boolean) -> Unit,
    onRejectFile: (peerId: String, transferId: String) -> Unit,
    onCancelTransfer: (peerId: String, transferId: String) -> Unit,
    onDownloadCachedFile: (url: String, fileName: String, fileSize: Long, mimeType: String) -> Unit,
    onOpenFile: () -> Unit,
    onShareItem: () -> Unit,
    onRequestResend: () -> Unit,
    onRetryCloudUpload: () -> Unit,
    onToggleVoice: () -> Unit,
    onCopiedClip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BayTheme.colors
    val haptic = LocalHapticFeedback.current

    // 系统通知 / 撤回提示气泡（本人撤回的文本消息支持「重新编辑」）
    if (item.kind == ChatKind.SYSTEM) {
        val rawText = item.text.orEmpty()
        val displayText = if (rawText.startsWith("↩ ")) rawText.removePrefix("↩ ").trim() else rawText
        val recalledText = item.recalledText
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = BaySpacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(BayShapes.fullShape)
                    .background(colors.fill)
                    .padding(horizontal = BaySpacing.md, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                Text(
                    text = displayText,
                    style = BayType.caption.copy(fontWeight = FontWeight.Normal),
                    color = colors.textSecondary,
                )
                if (item.isSelfRecall && !recalledText.isNullOrEmpty()) {
                    Text(
                        text = "重新编辑",
                        style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.accent,
                        modifier = Modifier
                            .bayClickable { onReEditRecall(recalledText) }
                            .clip(BayShapes.xsShape)
                            .padding(horizontal = 2.dp),
                    )
                }
            }
        }
        return
    }

    val bubbleShape = RoundedCornerShape(
        topStart = if (!item.isSelf && !isFirstInGroup) 8.dp else 18.dp,
        topEnd = if (item.isSelf && !isFirstInGroup) 8.dp else 18.dp,
        bottomStart = if (!item.isSelf && isLastInGroup) 6.dp else 18.dp,
        bottomEnd = if (item.isSelf && isLastInGroup) 6.dp else 18.dp,
    )

    val topGap = if (isFirstInGroup) BaySpacing.sm else 2.dp
    val rowHighlightColor by animateColorAsState(
        targetValue = if (isHighlighted) colors.accentSoft else Color.Transparent,
        animationSpec = tween(durationMillis = 320),
        label = "messageJumpHighlight",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topGap)
            .clip(BayShapes.mdShape)
            .background(rowHighlightColor)
            .padding(horizontal = 2.dp, vertical = 1.dp),
        horizontalArrangement = if (item.isSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // 他人消息左侧头像列（连贯消息保留对齐空白，点击热区 >= 44.dp）
        if (!item.isSelf) {
            if (isFirstInGroup) {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                        .bayClickable { onPeerClick(item.senderId, item.senderName) },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    BayAvatar(
                        emojiId = item.senderAvatar,
                        size = 34.dp,
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(44.dp))
            }
            Spacer(modifier = Modifier.width(BaySpacing.xs))
        }

        Column(
            horizontalAlignment = if (item.isSelf) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            // 同组首条消息展示发送者昵称与时间
            if (isFirstInGroup) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(
                        start = if (item.isSelf) 0.dp else 4.dp,
                        end = if (item.isSelf) 4.dp else 0.dp,
                        bottom = 3.dp,
                    ),
                ) {
                    if (!item.isSelf) {
                        Text(
                            text = item.senderName,
                            style = BayType.caption,
                            color = colors.textSecondary,
                            modifier = Modifier
                                .bayClickable { onPeerClick(item.senderId, item.senderName) }
                                .clip(BayShapes.xsShape),
                        )
                    }
                    Text(
                        text = FormatUtils.formatClock(item.timestamp),
                        style = BayType.caption.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal,
                        ),
                        color = colors.textSecondary,
                    )
                    if (item.fromCache) {
                        Text(
                            text = "云端",
                            style = BayType.caption.copy(fontWeight = FontWeight.Normal),
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // 消息主体行：自己消息左侧可附带发送中/失败重试图标
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (item.isSelf) {
                    SelfSendStatusIndicator(
                        status = item.sendStatus,
                        onRetry = { onRetrySend(item.id) },
                    )
                }

                val longPressInteraction = remember { MutableInteractionSource() }
                val textBody = item.text.orEmpty()
                Box(
                    modifier = Modifier
                        .widthIn(max = 304.dp)
                        .combinedClickable(
                            interactionSource = longPressInteraction,
                            indication = null,
                            onClick = {},
                            onDoubleClick = if (item.kind == ChatKind.TEXT && textBody.isNotBlank()) {
                                { onDoubleClickText(textBody) }
                            } else {
                                null
                            },
                            onLongClick = {
                                BayHaptic.heavy(haptic, hapticEnabled)
                                onLongPress()
                            },
                        ),
                ) {
                    when (item.kind) {
                        ChatKind.TEXT -> {
                            val basePeerBg = if (colors.isDark) colors.surfaceElevated else colors.surface
                            val textColor = if (item.isSelf) colors.onAccent else colors.textPrimary
                            val bubbleBgModifier = when {
                                item.isSelf -> Modifier.background(colors.accent)
                                item.mentionedMe -> Modifier
                                    .background(basePeerBg)
                                    .background(colors.accentSoft)
                                else -> Modifier.background(basePeerBg)
                            }

                            Column(
                                modifier = Modifier
                                    .clip(bubbleShape)
                                    .then(bubbleBgModifier)
                                    .padding(horizontal = BaySpacing.md + 2.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (!item.isSelf && item.mentionedMe) {
                                    Text(
                                        text = "@我",
                                        style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.accent,
                                    )
                                }

                                item.replyTo?.let { quote ->
                                    val quoteBg = if (item.isSelf) {
                                        colors.onAccent.copy(alpha = 0.16f)
                                    } else {
                                        colors.fill
                                    }
                                    val quoteBar = if (item.isSelf) colors.onAccent else colors.accent
                                    val isRecalledQuote = quote.preview == "该引用内容已撤回"
                                    Row(
                                        modifier = Modifier
                                            .bayClickable { onJumpToReply(quote.id) }
                                            .height(IntrinsicSize.Min)
                                            .clip(BayShapes.xsShape)
                                            .background(quoteBg)
                                            .padding(horizontal = BaySpacing.sm, vertical = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(2.5.dp)
                                                .fillMaxHeight()
                                                .clip(BayShapes.fullShape)
                                                .background(quoteBar),
                                        )
                                        Column {
                                            Text(
                                                text = quote.senderName,
                                                style = BayType.caption,
                                                color = if (item.isSelf) colors.onAccent else colors.accent,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = quote.preview,
                                                style = BayType.caption.copy(fontWeight = FontWeight.Normal),
                                                color = when {
                                                    item.isSelf -> colors.onAccent.copy(
                                                        alpha = if (isRecalledQuote) 0.65f else 0.88f,
                                                    )
                                                    isRecalledQuote -> colors.textSecondary.copy(alpha = 0.72f)
                                                    else -> colors.textSecondary
                                                },
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = textBody,
                                    style = BayType.callout,
                                    color = textColor,
                                )
                            }
                        }

                        ChatKind.IMAGE -> {
                            ImageMessageCard(
                                item = item,
                                imageHeaders = imageHeaders,
                                snapshotTransferredBytes = snapshotTransferredBytes,
                                snapshotTotalBytes = snapshotTotalBytes,
                                onPreviewImage = onPreviewImage,
                                onCancelTransfer = onCancelTransfer,
                                onRequestResend = onRequestResend,
                                onRetryCloudUpload = onRetryCloudUpload,
                            )
                        }

                        ChatKind.FILE -> {
                            val tr = item.transfer
                            if (tr != null) {
                                FileTransferCard(
                                    item = item,
                                    transfer = tr,
                                    snapshotTransferredBytes = snapshotTransferredBytes,
                                    snapshotTotalBytes = snapshotTotalBytes,
                                    snapshotSpeedBps = snapshotSpeedBps,
                                    snapshotEtaSeconds = snapshotEtaSeconds,
                                    onAccept = onAcceptFile,
                                    onReject = onRejectFile,
                                    onCancel = onCancelTransfer,
                                    onDownloadCached = { url ->
                                        onDownloadCachedFile(url, tr.fileName, tr.fileSize, tr.mimeType)
                                    },
                                    onOpen = onOpenFile,
                                    onShare = onShareItem,
                                    onRequestResend = onRequestResend,
                                    onRetryCloudUpload = onRetryCloudUpload,
                                )
                            }
                        }

                        ChatKind.AUDIO -> {
                            VoiceMessageRow(
                                item = item,
                                voicePlayback = voicePlayback,
                                isUnread = !item.isSelf && item.id !in playedVoiceIds,
                                hapticEnabled = hapticEnabled,
                                onTogglePlayback = onToggleVoice,
                                onLongPress = onLongPress,
                            )
                        }

                        ChatKind.CLIP -> {
                            ClipCard(
                                item = item,
                                hapticEnabled = hapticEnabled,
                                onCopied = onCopiedClip,
                            )
                        }

                        else -> {
                            Text(
                                text = item.text.orEmpty(),
                                style = BayType.callout,
                                color = colors.textPrimary,
                            )
                        }
                    }
                }
            }

            // 表情回应统计胶囊
            val activeReactions = item.reactions.filterValues { it.isNotEmpty() }
            if (activeReactions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 5.dp),
                ) {
                    activeReactions.forEach { (emoji, users) ->
                        val reactedByMe = selfPeerId in users
                        val pillBg = if (reactedByMe) {
                            colors.accentSoft
                        } else if (colors.isDark) {
                            colors.surfaceElevated
                        } else {
                            colors.surface
                        }
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                                .bayClickable { onReact(emoji) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(BayShapes.fullShape)
                                    .background(pillBg)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = emoji,
                                    style = BayType.footnote,
                                )
                                Text(
                                    text = users.size.toString(),
                                    style = BayType.caption.copy(fontFamily = FontFamily.Monospace),
                                    color = if (reactedByMe) colors.accent else colors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelfSendStatusIndicator(
    status: MessageSendStatus,
    onRetry: () -> Unit,
) {
    val colors = BayTheme.colors
    when (status) {
        MessageSendStatus.SENT -> Unit
        MessageSendStatus.SENDING -> {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                contentDescription = "发送中",
                tint = colors.textSecondary,
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .size(16.dp),
            )
        }
        MessageSendStatus.FAILED -> {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                    .bayClickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .clip(BayShapes.fullShape)
                        .background(colors.danger.copy(alpha = if (colors.isDark) 0.24f else 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = "发送失败，点击重试",
                            tint = colors.danger,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "重发",
                            style = BayType.caption,
                            color = colors.danger,
                        )
                    }
                }
            }
        }
    }
}
