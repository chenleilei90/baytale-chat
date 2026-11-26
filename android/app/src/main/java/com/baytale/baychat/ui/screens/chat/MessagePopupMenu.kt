package com.baytale.baychat.ui.screens.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlin.math.roundToInt

/**
 * 微信风格长按浮动菜单支持的动作枚举。
 */
enum class MessageMenuAction {
    COPY,
    FORWARD,
    FAVORITE,
    DELETE,
    MULTI_SELECT,
    REPLY,
    RECALL,
    SAVE_IMAGE,
    SHARE,
    TRANSCRIBE,
    REPORT,
}

private data class PopupGridAction(
    val action: MessageMenuAction,
    val label: String,
    val icon: ImageVector,
)

/**
 * 微信式长按浮动气泡菜单：
 * - 深灰浮层底色（[BayTheme.colors.popupSurface]），零描边、12dp 圆角、带指向锚点的小三角；
 * - 顶部一排快捷表情回应，下方按每行 5 格排列图标与文案；
 * - 优先放在目标气泡上方，上方空间不足时自动翻转到下方并约束屏幕边距。
 */
@Composable
fun MessagePopupMenu(
    item: ChatItem,
    anchor: Rect,
    selfPeerId: String,
    hapticEnabled: Boolean,
    vm: BayChatViewModel,
    onAction: (MessageMenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val view = LocalView.current
    val density = LocalDensity.current

    LaunchedEffect(item.id) {
        BayHaptic.heavy(view, hapticEnabled)
    }

    val canRecall = item.isSelf &&
        item.kind != ChatKind.FILE &&
        item.kind != ChatKind.SYSTEM &&
        (System.currentTimeMillis() - item.timestamp <= BayChatViewModel.RECALL_WINDOW_MS)

    val gridActions = remember(item.id, item.kind, item.isSelf, item.isSelfRecall, item.text, canRecall) {
        buildList {
            if (!item.text.isNullOrBlank() && (item.kind == ChatKind.TEXT || item.kind == ChatKind.CLIP)) {
                add(PopupGridAction(MessageMenuAction.COPY, "复制", Icons.Rounded.ContentCopy))
            }
            if (!item.isSelfRecall &&
                (item.kind == ChatKind.TEXT || item.kind == ChatKind.CLIP || item.kind == ChatKind.IMAGE || item.kind == ChatKind.FILE)
            ) {
                add(PopupGridAction(MessageMenuAction.FORWARD, "转发", Icons.AutoMirrored.Rounded.Forward))
            }
            if (item.kind == ChatKind.TEXT ||
                item.kind == ChatKind.CLIP ||
                item.kind == ChatKind.IMAGE ||
                item.kind == ChatKind.FILE ||
                item.kind == ChatKind.AUDIO
            ) {
                add(PopupGridAction(MessageMenuAction.FAVORITE, "收藏", Icons.Rounded.BookmarkBorder))
            }
            if (item.kind != ChatKind.SYSTEM) {
                add(PopupGridAction(MessageMenuAction.DELETE, "删除", Icons.Rounded.DeleteOutline))
            }
            if (item.kind != ChatKind.SYSTEM && !item.isSelfRecall) {
                add(PopupGridAction(MessageMenuAction.MULTI_SELECT, "多选", Icons.Rounded.Checklist))
            }
            if (item.kind != ChatKind.SYSTEM) {
                add(PopupGridAction(MessageMenuAction.REPLY, "引用", Icons.AutoMirrored.Rounded.Reply))
            }
            if (canRecall) {
                add(PopupGridAction(MessageMenuAction.RECALL, "撤回", Icons.Rounded.Undo))
            }
            if (item.kind == ChatKind.IMAGE) {
                add(PopupGridAction(MessageMenuAction.SAVE_IMAGE, "保存", Icons.Rounded.Download))
            }
            if (item.kind == ChatKind.IMAGE || item.kind == ChatKind.FILE || item.kind == ChatKind.AUDIO) {
                add(PopupGridAction(MessageMenuAction.SHARE, "分享", Icons.Rounded.Share))
            }
            if (item.kind == ChatKind.AUDIO) {
                add(PopupGridAction(MessageMenuAction.TRANSCRIBE, "转文字", Icons.Rounded.Translate))
            }
            if (!item.isSelf && item.kind != ChatKind.SYSTEM) {
                add(PopupGridAction(MessageMenuAction.REPORT, "举报", Icons.Rounded.Flag))
            }
        }
    }

    var placeAbove by remember(item.id) { mutableStateOf(true) }
    var arrowCenterXInPopupPx by remember(item.id) { mutableFloatStateOf(-1f) }
    val animProgress = remember(item.id) { Animatable(0f) }
    LaunchedEffect(item.id) {
        animProgress.animateTo(1f, animationSpec = tween(durationMillis = 150))
    }

    val marginPx = with(density) { 12.dp.roundToPx() }
    val gapPx = with(density) { 8.dp.roundToPx() }
    val topSafePx = with(density) { 48.dp.roundToPx() }
    val arrowWidth = 14.dp
    val arrowHeight = 7.dp
    val arrowEdgePaddingPx = with(density) { 20.dp.toPx() }

    val positionProvider = remember(anchor, marginPx, gapPx, topSafePx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - marginPx - popupContentSize.width).coerceAtLeast(marginPx)
                val maxY = (windowSize.height - marginPx - popupContentSize.height).coerceAtLeast(topSafePx)
                if (anchor.isEmpty) {
                    placeAbove = true
                    arrowCenterXInPopupPx = popupContentSize.width / 2f
                    return IntOffset(
                        x = ((windowSize.width - popupContentSize.width) / 2).coerceIn(marginPx, maxX),
                        y = (windowSize.height / 3).coerceIn(topSafePx, maxY),
                    )
                }

                val targetCenterX = ((anchor.left + anchor.right) / 2f).roundToInt()
                val resolvedX = (targetCenterX - popupContentSize.width / 2).coerceIn(marginPx, maxX)
                arrowCenterXInPopupPx = (targetCenterX - resolvedX).toFloat()

                val spaceAbove = anchor.top.roundToInt() - topSafePx
                val showAbove = spaceAbove >= popupContentSize.height + gapPx
                placeAbove = showAbove
                val rawY = if (showAbove) {
                    anchor.top.roundToInt() - popupContentSize.height - gapPx
                } else {
                    anchor.bottom.roundToInt() + gapPx
                }
                val resolvedY = rawY.coerceIn(topSafePx, maxY)
                return IntOffset(x = resolvedX, y = resolvedY)
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val scale = 0.9f + 0.1f * animProgress.value
        val pivotY = if (placeAbove) 1f else 0f
        Column(
            modifier = Modifier
                .widthIn(min = 240.dp, max = 312.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = animProgress.value
                    transformOrigin = TransformOrigin(0.5f, pivotY)
                },
        ) {
            if (!placeAbove) {
                PopupArrowTriangle(
                    pointUp = true,
                    color = colors.popupSurface,
                    arrowCenterXPx = arrowCenterXInPopupPx,
                    edgePaddingPx = arrowEdgePaddingPx,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(arrowHeight),
                    arrowWidthDp = arrowWidth,
                )
            }

            Column(
                modifier = Modifier
                    .clip(BayShapes.smShape)
                    .background(colors.popupSurface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(horizontal = BaySpacing.sm, vertical = BaySpacing.sm),
            ) {
                // 快捷表情回应横条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = BaySpacing.xs),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    REACTION_EMOJIS.forEach { emoji ->
                        val reacted = selfPeerId in item.reactions[emoji].orEmpty()
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (reacted) colors.accentSoft else colors.popupSurface)
                                .bayClickable {
                                    vm.reactMessage(item.id, emoji)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emoji,
                                style = BayType.title2,
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = BaySpacing.xs)
                        .height(0.5.dp)
                        .background(colors.popupContent.copy(alpha = 0.16f)),
                )

                Spacer(modifier = Modifier.height(BaySpacing.xs))

                // 功能网格：每行最多 5 格
                val rows = gridActions.chunked(5)
                rows.forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowItems.forEach { entry ->
                            Column(
                                modifier = Modifier
                                    .width(58.dp)
                                    .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
                                    .clip(BayShapes.xsShape)
                                    .bayClickable { onAction(entry.action) }
                                    .padding(vertical = 6.dp, horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(
                                    imageVector = entry.icon,
                                    contentDescription = entry.label,
                                    tint = colors.popupContent,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = entry.label,
                                    style = BayType.caption,
                                    color = colors.popupContent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            if (placeAbove) {
                PopupArrowTriangle(
                    pointUp = false,
                    color = colors.popupSurface,
                    arrowCenterXPx = arrowCenterXInPopupPx,
                    edgePaddingPx = arrowEdgePaddingPx,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(arrowHeight),
                    arrowWidthDp = arrowWidth,
                )
            }
        }
    }
}

@Composable
private fun PopupArrowTriangle(
    pointUp: Boolean,
    color: androidx.compose.ui.graphics.Color,
    arrowCenterXPx: Float,
    edgePaddingPx: Float,
    arrowWidthDp: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val halfW = arrowWidthDp.toPx() / 2f
        val minCenter = (edgePaddingPx + halfW).coerceAtMost(size.width / 2f)
        val maxCenter = (size.width - edgePaddingPx - halfW).coerceAtLeast(size.width / 2f)
        val cx = if (arrowCenterXPx >= 0f) {
            arrowCenterXPx.coerceIn(minCenter, maxCenter)
        } else {
            size.width / 2f
        }
        val path = Path().apply {
            if (pointUp) {
                moveTo(cx, 0f)
                lineTo(cx - halfW, size.height)
                lineTo(cx + halfW, size.height)
            } else {
                moveTo(cx - halfW, 0f)
                lineTo(cx + halfW, 0f)
                lineTo(cx, size.height)
            }
            close()
        }
        drawPath(path = path, color = color)
    }
}
