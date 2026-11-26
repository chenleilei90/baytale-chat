package com.baytale.baychat.ui.screens.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
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
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    isAvatarShaking: Boolean = false,
    nudgeShakeNonce: Long = 0L,
    onLongPress: (Rect) -> Unit,
    onReEditRecall: (String) -> Unit,
    onJumpToReply: (String) -> Unit,
    onDoubleClickText: (String) -> Unit,
    onPeerClick: (peerId: String, peerName: String) -> Unit,
    onAvatarDoubleTap: (peerId: String, peerName: String) -> Unit = { _, _ -> },
    onAvatarLongPress: (peerId: String, peerName: String) -> Unit = { _, _ -> },
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
    voiceTranscriptText: String? = null,
    isVoiceTranscribing: Boolean = false,
    onCollapseVoiceTranscript: () -> Unit = {},
    onCopyVoiceTranscript: (String) -> Unit = {},
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

    // 新版微信同款 6dp 微圆角气泡
    val bubbleShape = RoundedCornerShape(6.dp)

    val topGap = 10.dp
    val selectable = !item.isSelfRecall
    val rowHighlightColor by animateColorAsState(
        targetValue = when {
            selectionMode && isSelected -> colors.accentSoft.copy(alpha = 0.7f)
            isHighlighted -> colors.accentSoft
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 320),
        label = "messageJumpHighlight",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topGap)
            .clip(BayShapes.mdShape)
            .background(rowHighlightColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp, vertical = 1.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp, end = BaySpacing.xs)
                        .size(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selectable) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(colors.accent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "已选择",
                                    tint = colors.onAccent,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(colors.fillStrong),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(colors.bg),
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = if (item.isSelf) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                // 他人消息左侧头像列（每条均与微信一致展示 40dp 方形微圆角头像；支持单击操作、双击拍一拍、长按@提及）
                if (!item.isSelf) {
                    MessageSenderAvatar(
                        avatarId = item.senderAvatar,
                        isShaking = isAvatarShaking,
                        shakeNonce = nudgeShakeNonce,
                        enabled = !selectionMode,
                        onClick = { onPeerClick(item.senderId, item.senderName) },
                        onDoubleClick = { onAvatarDoubleTap(item.senderId, item.senderName) },
                        onLongClick = {
                            BayHaptic.tap(haptic, hapticEnabled)
                            onAvatarLongPress(item.senderId, item.senderName)
                        },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

        Column(
            horizontalAlignment = if (item.isSelf) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            // 仅他人群组消息展示昵称，对齐气泡左边缘；自己消息绝不额外渲染顶部标签以确保头像与气泡上沿严格齐平
            if (!item.isSelf && isFirstInGroup) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(
                        start = 6.dp,
                        bottom = 3.dp,
                    ),
                ) {
                    Text(
                        text = item.senderName,
                        style = BayType.caption,
                        color = colors.textSecondary,
                        modifier = Modifier
                            .bayClickable { onPeerClick(item.senderId, item.senderName) }
                            .clip(BayShapes.xsShape),
                    )
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
                var bubbleBounds by remember { mutableStateOf(Rect.Zero) }
                val textBody = item.text.orEmpty()
                Box(
                    modifier = Modifier
                        .widthIn(max = 304.dp)
                        .onGloballyPositioned { coords -> bubbleBounds = coords.boundsInWindow() }
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
                                onLongPress(bubbleBounds)
                            },
                        ),
                ) {
                    when (item.kind) {
                        ChatKind.TEXT -> {
                            val bubbleFillColor = when {
                                item.isSelf -> colors.wechatSelfBubble
                                item.mentionedMe -> colors.wechatPeerBubble
                                else -> colors.wechatPeerBubble
                            }
                            val textColor = if (item.isSelf) colors.wechatSelfText else colors.textPrimary
                            val bubbleBgModifier = when {
                                item.isSelf -> Modifier.background(colors.wechatSelfBubble)
                                item.mentionedMe -> Modifier
                                    .background(colors.wechatPeerBubble)
                                    .background(colors.accentSoft)
                                else -> Modifier.background(colors.wechatPeerBubble)
                            }

                            Row(verticalAlignment = Alignment.Top) {
                                if (!item.isSelf) {
                                    Canvas(
                                        modifier = Modifier
                                            .padding(top = 15.dp)
                                            .size(width = 6.dp, height = 10.dp),
                                    ) {
                                        val path = Path().apply {
                                            moveTo(size.width, 0f)
                                            lineTo(0f, size.height / 2f)
                                            lineTo(size.width, size.height)
                                            close()
                                        }
                                        drawPath(path = path, color = bubbleFillColor)
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .heightIn(min = 40.dp)
                                        .clip(bubbleShape)
                                        .then(bubbleBgModifier)
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                if (!item.isSelf && item.mentionedMe) {
                                    Text(
                                        text = "@我",
                                        style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.accent,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }

                                item.replyTo?.let { quote ->
                                    val quoteBg = if (item.isSelf) {
                                        colors.wechatSelfText.copy(alpha = 0.08f)
                                    } else {
                                        colors.fill
                                    }
                                    val quoteBar = if (item.isSelf) colors.wechatSelfText.copy(alpha = 0.45f) else colors.accent
                                    val isRecalledQuote = quote.preview == "该引用内容已撤回"
                                    Row(
                                        modifier = Modifier
                                            .padding(bottom = 6.dp)
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
                                                color = if (item.isSelf) colors.wechatSelfText else colors.accent,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = quote.preview,
                                                style = BayType.caption.copy(fontWeight = FontWeight.Normal),
                                                color = when {
                                                    item.isSelf -> colors.wechatSelfText.copy(
                                                        alpha = if (isRecalledQuote) 0.55f else 0.72f,
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

                                val linkColor = colors.accent
                                val linkedText = remember(textBody, linkColor) {
                                    buildClickableUrlText(textBody, linkColor)
                                }
                                Text(
                                    text = linkedText,
                                    style = BayType.body.copy(
                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                    ),
                                    color = textColor,
                                )
                                }

                                if (item.isSelf) {
                                    Canvas(
                                        modifier = Modifier
                                            .padding(top = 15.dp)
                                            .size(width = 6.dp, height = 10.dp),
                                    ) {
                                        val path = Path().apply {
                                            moveTo(0f, 0f)
                                            lineTo(size.width, size.height / 2f)
                                            lineTo(0f, size.height)
                                            close()
                                        }
                                        drawPath(path = path, color = bubbleFillColor)
                                    }
                                }
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
                                transcriptText = voiceTranscriptText,
                                isTranscribing = isVoiceTranscribing,
                                onCollapseTranscript = onCollapseVoiceTranscript,
                                onCopyTranscript = onCopyVoiceTranscript,
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

                // 自己消息右侧头像列（每条均展示 40dp 头像，上沿与气泡齐平，支持双击拍自己）
                if (item.isSelf) {
                    Spacer(modifier = Modifier.width(6.dp))
                    MessageSenderAvatar(
                        avatarId = item.senderAvatar,
                        isShaking = isAvatarShaking,
                        shakeNonce = nudgeShakeNonce,
                        enabled = !selectionMode,
                        onClick = {},
                        onDoubleClick = { onAvatarDoubleTap(item.senderId, item.senderName) },
                        onLongClick = null,
                    )
                }
            }
        }

        // 多选模式下全行透明拦截层：停用内部所有点击/双击/长按/链接跳转，点击整行即切换勾选
        if (selectionMode) {
            val selectInteraction = remember(item.id) { MutableInteractionSource() }
            Spacer(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = selectInteraction,
                        indication = null,
                        enabled = selectable,
                        onClick = onToggleSelect,
                    ),
            )
        }
    }
}

private val CHAT_URL_REGEX = Regex(
    "https?://[^\\s\\u3000-\\u303F\\u4e00-\\u9fa5，。！？、；：（）【】《》\"'<>]+",
    RegexOption.IGNORE_CASE,
)

private fun buildClickableUrlText(text: String, linkColor: Color): AnnotatedString {
    val matches = CHAT_URL_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        val linkStyle = TextLinkStyles(
            style = SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )
        )
        matches.forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            if (start > cursor) {
                append(text.substring(cursor, start))
            }
            val url = text.substring(start, end)
            withLink(LinkAnnotation.Url(url = url, styles = linkStyle)) {
                append(url)
            }
            cursor = end
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageSenderAvatar(
    avatarId: String?,
    isShaking: Boolean,
    shakeNonce: Long,
    enabled: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val shakeDeg = remember { Animatable(0f) }
    LaunchedEffect(shakeNonce, isShaking) {
        if (isShaking && shakeNonce > 0L) {
            shakeDeg.snapTo(0f)
            val frames = listOf(-12f, 12f, -8f, 8f, 0f)
            frames.forEach { target ->
                shakeDeg.animateTo(target, animationSpec = tween(durationMillis = 115))
            }
        }
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
                onDoubleClick = onDoubleClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BayAvatar(
            emojiId = avatarId,
            size = 40.dp,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.graphicsLayer {
                rotationZ = shakeDeg.value
            },
        )
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
