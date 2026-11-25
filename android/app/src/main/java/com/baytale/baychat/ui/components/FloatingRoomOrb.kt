package com.baytale.baychat.ui.components

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.baytale.baychat.core.model.TransferStatus
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.ui.ActiveRoomSession
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
 * 1. 56dp 纯填充圆形浮球，默认贴靠右侧偏下并自动避让顶部状态栏与底部导航栏；
 * 2. Animatable + Lambda offset 实现高帧率跟手拖拽与物理弹簧磁吸贴边；
 * 3. 传输态展示 [BayRingProgress] 环形进度；在线态展示柔和呼吸光点；右上角通过 [BayBadge] 展示未读数或成员数；
 * 4. 单击立即回房；拖拽浮现底部胶囊退出热区，拖入松手或长按均触发退出确认。
 */
@Composable
fun FloatingRoomOrb(
    room: ActiveRoomSession,
    hapticEnabled: Boolean,
    onRestoreRoom: () -> Unit,
    onRequestLeaveRoom: () -> Unit,
    modifier: Modifier = Modifier,
    unreadCount: Int = 0,
) {
    val colors = BayTheme.colors
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val activeTransfer = room.messages.lastOrNull {
        it.transfer?.status == TransferStatus.TRANSFERRING
    }?.transfer
    val isTransferring = activeTransfer != null && activeTransfer.fileSize > 0L
    val rawProgress = if (isTransferring && activeTransfer != null) {
        (activeTransfer.transferredBytes.toFloat() / activeTransfer.fileSize.toFloat()).coerceIn(0.02f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 180),
        label = "floatingOrbProgress",
    )
    val progressPct = (rawProgress * 100f).roundToInt().coerceIn(0, 100)
    val totalOnlineCount = room.peers.size + 1

    // 后台在线柔和呼吸光环
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
    val initialBottomLiftPx = with(density) { 26.dp.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerHeightPx = constraints.maxHeight.toFloat()

        val minX = (edgeMarginPx - auraPadPx).coerceAtLeast(0f)
        val maxX = (containerWidthPx - outerSizePx - edgeMarginPx + auraPadPx).coerceAtLeast(minX)
        val minY = (statusBarTopPx + topSafeExtraPx - auraPadPx).coerceAtLeast(0f)
        val maxY = (containerHeightPx - navBarBottomPx - dockReservePx - outerSizePx + auraPadPx).coerceAtLeast(minY)
        val dragMaxY = (containerHeightPx - navBarBottomPx - outerSizePx - with(density) { 6.dp.toPx() }).coerceAtLeast(minY)

        val defaultX = maxX
        val defaultY = (maxY - initialBottomLiftPx).coerceIn(minY, maxY)

        val offsetX = remember { Animatable(defaultX) }
        val offsetY = remember { Animatable(defaultY) }
        var hasInitialized by remember { mutableStateOf(false) }
        var dockedRight by remember { mutableStateOf(true) }
        var isDragging by remember { mutableStateOf(false) }
        var inDropZone by remember { mutableStateOf(false) }

        // 屏幕旋转或窗口尺寸变化时自动防越界并重新磁吸贴边
        LaunchedEffect(containerWidthPx, containerHeightPx, minX, maxX, minY, maxY) {
            if (!hasInitialized) {
                offsetX.snapTo(defaultX)
                offsetY.snapTo(defaultY)
                hasInitialized = true
            } else if (!isDragging) {
                val targetX = if (dockedRight) maxX else minX
                val clampedY = offsetY.value.coerceIn(minY, maxY)
                offsetX.snapTo(targetX)
                offsetY.snapTo(clampedY)
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

        val orbScale by animateFloatAsState(
            targetValue = if (isDragging) 1.08f else 1f,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f),
            label = "orbDragScale",
        )
        val dropZoneScale by animateFloatAsState(
            targetValue = if (inDropZone) 1.08f else 1f,
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

        // 56dp 磁吸可拖拽悬浮进度球本体
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .size(outerSizeDp)
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
                            dockedRight = snapRight
                            val targetX = if (snapRight) latestMaxX else latestMinX
                            val targetY = dragY.coerceIn(latestMinY, latestMaxY)

                            scope.launch {
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
            // 在线空闲态柔和呼吸光环
            if (!isTransferring) {
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
                            contentDescription = "返回房间 #${room.roomCode}",
                            tint = colors.onAccent,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
            }

            // 右上角徽章：优先展示后台未读数，否则展示房内成员数
            val badgeCount = if (unreadCount > 0) unreadCount else totalOnlineCount
            val badgeColor = when {
                unreadCount > 0 -> colors.danger
                room.peers.isNotEmpty() -> colors.success
                else -> colors.warning
            }
            BayBadge(
                count = badgeCount,
                color = badgeColor,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp),
            )
        }
    }
}
