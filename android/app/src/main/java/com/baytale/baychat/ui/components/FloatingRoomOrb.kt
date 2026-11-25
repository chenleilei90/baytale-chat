package com.baytale.baychat.ui.components

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.ui.ActiveRoomSession
import com.baytale.baychat.ui.TransferProgressSnapshot
import com.baytale.baychat.ui.design.BayBadge
import com.baytale.baychat.ui.design.BayRingProgress
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 边缘可拖拽磁吸悬浮进度球：
 * 1. 56dp 纯填充圆形浮球，通过 (dockedRight, verticalFraction) 跨旋转与回房重挂稳定保持停靠位置；
 * 2. Animatable + Lambda offset 实现高帧率跟手拖拽与物理弹簧磁吸贴边；
 * 3. 传输态优先订阅高频 [transferProgress] 快照并展示 [BayRingProgress]，在线态在未开启减少动画时展示呼吸光点；
 * 4. 右上角通过 [BayBadge] 展示未读数（免打扰时仅显示红点）或房内成员数；
 * 5. 单击立即回房；拖拽浮现底部胶囊退出热区，拖入松手或长按均触发退出确认。
 */
@Composable
fun FloatingRoomOrb(
    room: ActiveRoomSession,
    hapticEnabled: Boolean,
    onRestoreRoom: () -> Unit,
    onRequestLeaveRoom: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
    isMuted: Boolean = false,
    transferProgress: Map<String, TransferProgressSnapshot> = emptyMap(),
    dockedRight: Boolean? = null,
    verticalFraction: Float? = null,
    onPositionChange: ((dockedRight: Boolean, verticalFraction: Float) -> Unit)? = null,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    // UX-31：检测系统无障碍「移除动画」(ANIMATOR_DURATION_SCALE == 0f)
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }

    // UX-19：优先读取高频 transferProgress 快照，缺省时回退至消息中的 TransferInfo
    val activeTransfer = room.messages.lastOrNull {
        it.transfer?.status == TransferStatus.TRANSFERRING
    }?.transfer
    val progressSnapshot = activeTransfer?.let { transferProgress[it.transferId] }
    val transferredBytes = progressSnapshot?.transferredBytes ?: activeTransfer?.transferredBytes ?: 0L
    val isTransferring = activeTransfer != null && activeTransfer.fileSize > 0L
    val rawProgress = if (isTransferring && activeTransfer != null) {
        (transferredBytes.toFloat() / activeTransfer.fileSize.toFloat()).coerceIn(0.02f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 180),
        label = "floatingOrbProgress",
    )
    val progressPct = (rawProgress * 100f).roundToInt().coerceIn(0, 100)
    val totalOnlineCount = room.peers.size + 1

    val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
    val navBarBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()

    val outerSizeDp = 64.dp
    val orbSizeDp = 56.dp
    val outerSizePx = with(density) { outerSizeDp.toPx() }
    val auraPadPx = with(density) { 4.dp.toPx() }
    val edgeMarginPx = with(density) { 10.dp.toPx() }
    val topSafeExtraPx = with(density) { 14.dp.toPx() }
    val dockReservePx = with(density) { 64.dp.toPx() }
    val dropZoneOffsetFromBottomPx = with(density) { 88.dp.toPx() }
    val dropZoneHitHalfW = with(density) { 108.dp.toPx() }
    val dropZoneHitHalfH = with(density) { 48.dp.toPx() }

    // UX-16：归一化停靠位置，既支持内部 rememberSaveable 也支持外层持久化回传
    var savedDockedRight by rememberSaveable { mutableStateOf(dockedRight ?: true) }
    var savedVerticalFraction by rememberSaveable { mutableFloatStateOf(verticalFraction ?: 0.86f) }
    val resolvedDockedRight = dockedRight ?: savedDockedRight
    val resolvedVerticalFraction = (verticalFraction ?: savedVerticalFraction).coerceIn(0f, 1f)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerHeightPx = constraints.maxHeight.toFloat()

        val minX = (edgeMarginPx - auraPadPx).coerceAtLeast(0f)
        val maxX = (containerWidthPx - outerSizePx - edgeMarginPx + auraPadPx).coerceAtLeast(minX)
        val minY = (statusBarTopPx + topSafeExtraPx - auraPadPx).coerceAtLeast(0f)
        val maxY = (containerHeightPx - navBarBottomPx - dockReservePx - outerSizePx + auraPadPx).coerceAtLeast(minY)
        val dragMaxY = (containerHeightPx - navBarBottomPx - outerSizePx - with(density) { 6.dp.toPx() }).coerceAtLeast(minY)

        val defaultX = if (resolvedDockedRight) maxX else minX
        val defaultY = (minY + (maxY - minY) * resolvedVerticalFraction).coerceIn(minY, maxY)

        val offsetX = remember { Animatable(defaultX) }
        val offsetY = remember { Animatable(defaultY) }
        var isDragging by remember { mutableStateOf(false) }
        var inDropZone by remember { mutableStateOf(false) }

        // 屏幕旋转、分屏或窗口尺寸变化时按归一化比例重新钳位，拖拽态也实时防越界
        LaunchedEffect(containerWidthPx, containerHeightPx, minX, maxX, minY, maxY, resolvedDockedRight, resolvedVerticalFraction) {
            if (isDragging) {
                offsetX.snapTo(offsetX.value.coerceIn(minX, maxX))
                offsetY.snapTo(offsetY.value.coerceIn(minY, dragMaxY))
            } else {
                val targetX = if (resolvedDockedRight) maxX else minX
                val targetY = (minY + (maxY - minY) * resolvedVerticalFraction).coerceIn(minY, maxY)
                offsetX.snapTo(targetX)
                offsetY.snapTo(targetY)
            }
        }

        val latestMinX by rememberUpdatedState(minX)
        val latestMaxX by rememberUpdatedState(maxX)
        val latestMinY by rememberUpdatedState(minY)
        val latestMaxY by rememberUpdatedState(maxY)
        val latestDragMaxY by rememberUpdatedState(dragMaxY)
        val latestContainerWidthPx by rememberUpdatedState(containerWidthPx)
        val latestDropZoneCenterX by rememberUpdatedState(containerWidthPx / 2f)
        val latestDropZoneCenterY by rememberUpdatedState(
            containerHeightPx - navBarBottomPx - dropZoneOffsetFromBottomPx
        )
        val latestHapticEnabled by rememberUpdatedState(hapticEnabled)
        val latestOnRestore by rememberUpdatedState(onRestoreRoom)
        val latestOnRequestLeave by rememberUpdatedState(onRequestLeaveRoom)
        val latestOnPositionChange by rememberUpdatedState(onPositionChange)

        val orbScale by animateFloatAsState(
            targetValue = if (isDragging && !reduceMotion) 1.08f else 1f,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f),
            label = "orbDragScale",
        )
        val dropZoneScale by animateFloatAsState(
            targetValue = if (inDropZone && !reduceMotion) 1.08f else 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            label = "dropZoneScale",
        )

        // 拖拽时底部浮现的纯填充胶囊退出热区
        AnimatedVisibility(
            visible = isDragging,
            enter = fadeIn(tween(150)) + slideInVertically(tween(180)) { it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(160)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 68.dp),
        ) {
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = dropZoneScale
                        scaleY = dropZoneScale
                    }
                    .shadow(
                        elevation = if (inDropZone) 12.dp else 6.dp,
                        shape = BayShapes.fullShape,
                        clip = false,
                    )
                    .clip(BayShapes.fullShape)
                    .background(
                        if (inDropZone) colors.danger else colors.danger.copy(alpha = 0.88f)
                    )
                    .padding(horizontal = BaySpacing.xl, vertical = BaySpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "退出房间",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (inDropZone) "松手退出房间" else "拖到此处退出房间",
                    style = BayType.footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                )
            }
        }

        // UX-30：为 56dp 悬浮进度球本体补充无障碍语义描述
        val orbSemanticsDesc = if (isTransferring) {
            "正在后台传输，点击回到房间"
        } else {
            "后台房间 #${room.roomCode}，点击回到房间"
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .size(outerSizeDp)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = orbSemanticsDesc
                }
                .graphicsLayer {
                    scaleX = orbScale
                    scaleY = orbScale
                }
                .pointerInput(Unit) {
                    val touchSlop = viewConfiguration.touchSlop
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        var dragStarted = false
                        var longPressFired = false
                        var accumulatedX = 0f
                        var accumulatedY = 0f
                        var dragX = offsetX.value
                        var dragY = offsetY.value

                        scope.launch {
                            offsetX.stop()
                            offsetY.stop()
                        }

                        val longPressJob = scope.launch {
                            delay(longPressTimeout)
                            if (!dragStarted) {
                                longPressFired = true
                                BayHaptic.heavy(view, latestHapticEnabled)
                                latestOnRequestLeave()
                            }
                        }

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val delta = change.positionChange()
                                if (!dragStarted) {
                                    accumulatedX += delta.x
                                    accumulatedY += delta.y
                                    if (hypot(accumulatedX, accumulatedY) > touchSlop) {
                                        dragStarted = true
                                        longPressJob.cancel()
                                        isDragging = true
                                        dragX = offsetX.value
                                        dragY = offsetY.value
                                    }
                                }
                                if (dragStarted && !longPressFired) {
                                    change.consume()
                                    dragX = (dragX + delta.x).coerceIn(latestMinX, latestMaxX)
                                    dragY = (dragY + delta.y).coerceIn(latestMinY, latestDragMaxY)
                                    val snapX = dragX
                                    val snapY = dragY
                                    scope.launch {
                                        offsetX.snapTo(snapX)
                                        offsetY.snapTo(snapY)
                                    }

                                    val centerX = snapX + outerSizePx / 2f
                                    val centerY = snapY + outerSizePx / 2f
                                    val entered = abs(centerX - latestDropZoneCenterX) <= dropZoneHitHalfW &&
                                        abs(centerY - latestDropZoneCenterY) <= dropZoneHitHalfH
                                    if (entered && !inDropZone) {
                                        BayHaptic.heavy(view, latestHapticEnabled)
                                    }
                                    inDropZone = entered
                                }
                            }
                        } finally {
                            longPressJob.cancel()
                        }

                        if (dragStarted) {
                            val droppedIntoExit = inDropZone
                            isDragging = false
                            inDropZone = false

                            val centerX = dragX + outerSizePx / 2f
                            val snapRight = centerX >= (latestContainerWidthPx / 2f)
                            val targetX = if (snapRight) latestMaxX else latestMinX
                            val targetY = dragY.coerceIn(latestMinY, latestMaxY)
                            val yRange = latestMaxY - latestMinY
                            val nextFraction = if (yRange > 0f) {
                                ((targetY - latestMinY) / yRange).coerceIn(0f, 1f)
                            } else {
                                0.86f
                            }
                            savedDockedRight = snapRight
                            savedVerticalFraction = nextFraction
                            latestOnPositionChange?.invoke(snapRight, nextFraction)

                            scope.launch {
                                if (reduceMotion) {
                                    offsetX.snapTo(targetX)
                                    offsetY.snapTo(targetY)
                                } else {
                                    launch {
                                        offsetX.animateTo(
                                            targetValue = targetX,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                        )
                                    }
                                    launch {
                                        offsetY.animateTo(
                                            targetValue = targetY,
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                        )
                                    }
                                }
                            }
                            if (droppedIntoExit) {
                                latestOnRequestLeave()
                            }
                        } else if (!longPressFired) {
                            BayHaptic.tap(view, latestHapticEnabled)
                            latestOnRestore()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // UX-31：关闭系统动画缩放时不启动无限呼吸脉冲
            if (!isTransferring && !reduceMotion) {
                val infiniteTransition = rememberInfiniteTransition(label = "orbPulseTransition")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 0.96f,
                    targetValue = 1.24f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1650, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "orbPulseScale",
                )
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.42f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1650, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "orbPulseAlpha",
                )
                Box(
                    modifier = Modifier
                        .size(orbSizeDp)
                        .graphicsLayer {
                            scaleX = pulseScale
                            scaleY = pulseScale
                            alpha = pulseAlpha
                        }
                        .clip(CircleShape)
                        .background(colors.success.copy(alpha = 0.50f)),
                )
            }

            // 56dp 纯填充圆形主体（零描边，仅保留柔和浮层投影）
            Box(
                modifier = Modifier
                    .size(orbSizeDp)
                    .shadow(
                        elevation = if (isDragging) 12.dp else 8.dp,
                        shape = CircleShape,
                        clip = false,
                    )
                    .clip(CircleShape)
                    .background(colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (isTransferring) {
                    BayRingProgress(
                        fraction = animatedProgress,
                        size = 48.dp,
                        strokeWidth = 3.5.dp,
                        trackColor = colors.accentSoft,
                        progressColor = colors.accent,
                    )
                    Text(
                        text = "$progressPct%",
                        style = BayType.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.accent,
                        maxLines = 1,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (room.peers.isNotEmpty()) Icons.Rounded.ChatBubble else Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = colors.onAccent,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }

            // 右上角徽章：免打扰且有未读时仅展示红点，未免打扰有未读时展示计数，否则展示成员数
            when {
                unreadCount > 0 && isMuted -> {
                    BayBadge(
                        dotOnly = true,
                        color = colors.danger,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 2.dp),
                    )
                }
                unreadCount > 0 -> {
                    BayBadge(
                        count = unreadCount,
                        color = colors.danger,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 1.dp, end = 1.dp),
                    )
                }
                else -> {
                    BayBadge(
                        count = totalOnlineCount,
                        color = if (room.peers.isNotEmpty()) colors.success else colors.warning,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 1.dp, end = 1.dp),
                    )
                }
            }
        }
    }
}
