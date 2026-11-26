package com.baytale.baychat.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.LinkTransportType
import com.baytale.baychat.core.model.PeerProfile
import com.baytale.baychat.core.model.RoomMode
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.ActiveRoomSession
import com.baytale.baychat.ui.BayChatUiState
import com.baytale.baychat.ui.BayChatViewModel
import com.baytale.baychat.ui.design.BayActionSheet
import com.baytale.baychat.ui.design.BayAvatar
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayButton
import com.baytale.baychat.ui.design.BayButtonSize
import com.baytale.baychat.ui.design.BayButtonStyle
import com.baytale.baychat.ui.design.BayCard
import com.baytale.baychat.ui.design.BayChip
import com.baytale.baychat.ui.design.BayDialog
import com.baytale.baychat.ui.design.BayGroup
import com.baytale.baychat.ui.design.BayGroupDivider
import com.baytale.baychat.ui.design.BayIconButton
import com.baytale.baychat.ui.design.BayIconStyle
import com.baytale.baychat.ui.design.BayListRow
import com.baytale.baychat.ui.design.BaySwitch
import com.baytale.baychat.ui.design.BayTextField
import com.baytale.baychat.ui.design.RowTrailing
import com.baytale.baychat.ui.design.SheetAction
import com.baytale.baychat.ui.design.bayClickable
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType

internal val RETENTION_OPTIONS = listOf(
    0 to "阅后即焚",
    1 to "1 天",
    3 to "3 天",
    7 to "7 天",
)

internal val MAX_FILE_OPTIONS = listOf(
    50 to "50MB",
    200 to "200MB",
    500 to "500MB",
    1024 to "1GB",
    2048 to "2GB",
    0 to "不限制",
)

/**
 * 房间邀请码与二维码抽屉弹窗：支持大号口令复制、ZXing 二维码、分享链接及临时房换码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteSheet(
    room: ActiveRoomSession,
    hapticEnabled: Boolean,
    vm: BayChatViewModel,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val view = LocalView.current
    val inviteUrl = remember(room.roomCode) { vm.buildInviteUrl(room.roomCode) }
    val qrBitmap = remember(inviteUrl) { FormatUtils.generateQrBitmap(inviteUrl, 560) }

    BayBottomSheet(
        onDismiss = onDismiss,
        title = "邀请加入房间",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BaySpacing.lg),
        ) {
            BayCard(
                onClick = {
                    copyTextToClipboard(context, room.roomCode)
                    BayHaptic.confirm(view, hapticEnabled)
                    vm.showToast("已复制房间口令 ${room.roomCode}")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    Text(
                        text = "房间口令（点击复制）",
                        style = BayType.footnote,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = room.roomCode,
                        style = BayType.codeLarge,
                        color = colors.accent,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            if (qrBitmap != null) {
                // 白色安全静区仅用于保证深色与浅色主题下二维码均可稳定识别
                Box(
                    modifier = Modifier
                        .clip(BayShapes.lgShape)
                        .background(Color.White)
                        .padding(BaySpacing.md),
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "房间邀请二维码",
                        modifier = Modifier.size(196.dp),
                    )
                }
            }

            Text(
                text = "扫码或在浏览器打开 $inviteUrl 即可进入当前房间",
                style = BayType.footnote,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BaySpacing.sm),
            ) {
                BayButton(
                    text = "复制口令",
                    onClick = {
                        copyTextToClipboard(context, room.roomCode)
                        BayHaptic.confirm(view, hapticEnabled)
                        vm.showToast("已复制房间口令")
                    },
                    style = BayButtonStyle.Neutral,
                    size = BayButtonSize.Medium,
                    leadingIcon = Icons.Rounded.ContentCopy,
                    modifier = Modifier.weight(1f),
                )
                BayButton(
                    text = "复制链接",
                    onClick = {
                        copyTextToClipboard(context, inviteUrl)
                        BayHaptic.confirm(view, hapticEnabled)
                        vm.showToast("已复制邀请链接")
                    },
                    style = BayButtonStyle.Secondary,
                    size = BayButtonSize.Medium,
                    modifier = Modifier.weight(1f),
                )
            }

            BayButton(
                text = "分享邀请给好友",
                onClick = { shareRoomInvite(context, room.roomCode, inviteUrl) },
                style = BayButtonStyle.Primary,
                size = BayButtonSize.Large,
                leadingIcon = Icons.Rounded.Share,
                modifier = Modifier.fillMaxWidth(),
            )

            // 临时房轮换新口令（仅房主可用，防止旧口令泄露）
            if (room.isEphemeral && room.isOwner) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BaySpacing.xs),
                ) {
                    BayButton(
                        text = "轮换新口令",
                        onClick = {
                            onDismiss()
                            vm.rotateEphemeralCode()
                        },
                        style = BayButtonStyle.Secondary,
                        size = BayButtonSize.Medium,
                        leadingIcon = Icons.Rounded.Refresh,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "生成新口令并作废旧口令，当前在线成员将自动跟随切换",
                        style = BayType.caption,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 全屏多图左右滑动浏览器（唯一允许使用 Color.Black 纯黑底色的组件）：
 * 支持 HorizontalPager 左右切换、双指缩放、双击放大还原及当前页保存/分享原图。
 */
@Composable
fun ImageViewer(
    images: List<ChatItem>,
    initialIndex: Int,
    hapticEnabled: Boolean,
    imageHeaders: Map<String, String>,
    onSave: (ChatItem) -> Boolean,
    onShare: (ChatItem) -> Unit,
    onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    val view = LocalView.current
    val safeInitial = initialIndex.coerceIn(0, images.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { images.size })
    var showOverlay by remember { mutableStateOf(true) }
    var currentPageScale by remember { mutableFloatStateOf(1f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = currentPageScale <= 1.05f,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                val pageItem = images[pageIndex]
                val isCurrentPage = pagerState.currentPage == pageIndex
                ZoomableGalleryPage(
                    item = pageItem,
                    isCurrentPage = isCurrentPage,
                    imageHeaders = imageHeaders,
                    onTap = onDismiss,
                    onActiveScaleChanged = { activeScale ->
                        if (isCurrentPage) {
                            currentPageScale = activeScale
                        }
                    },
                )
            }

            // 顶部页码指示与关闭按钮
            AnimatedVisibility(
                visible = showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.lg),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(BayShapes.fullShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = BaySpacing.md, vertical = 5.dp),
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${images.size}",
                            style = BayType.footnote.copy(fontFamily = FontFamily.Monospace),
                            color = Color.White,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .bayClickable(onClick = onDismiss)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭图片查看器",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // 底部保存与分享按钮栏（始终作用于当前页图片）
            AnimatedVisibility(
                visible = showOverlay,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = BaySpacing.xl, vertical = BaySpacing.xl),
            ) {
                val activeItem = images[pagerState.currentPage.coerceIn(0, images.lastIndex)]
                Row(
                    modifier = Modifier
                        .clip(BayShapes.fullShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(BaySpacing.xl),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .bayClickable {
                                if (onSave(activeItem)) {
                                    BayHaptic.confirm(view, hapticEnabled)
                                }
                            }
                            .clip(BayShapes.fullShape)
                            .padding(horizontal = BaySpacing.sm, vertical = BaySpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "保存原图",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "保存原图",
                            style = BayType.footnote,
                            color = Color.White,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .bayClickable { onShare(activeItem) }
                            .clip(BayShapes.fullShape)
                            .padding(horizontal = BaySpacing.sm, vertical = BaySpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = "分享图片",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "分享",
                            style = BayType.footnote,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableGalleryPage(
    item: ChatItem,
    isCurrentPage: Boolean,
    imageHeaders: Map<String, String>,
    onTap: () -> Unit,
    onActiveScaleChanged: (Float) -> Unit,
) {
    val context = LocalContext.current
    val model = remember(item.localFilePath, item.imageUrl, item.transfer?.localPath, item.transfer?.contentUri) {
        resolveImageModel(item)
    }
    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offset by remember(item.id) { mutableStateOf(Offset.Zero) }
    var retryNonce by remember(item.id) { mutableIntStateOf(0) }

    // 切走页面时自动还原缩放态，并向父级同步当前页缩放比例
    LaunchedEffect(isCurrentPage, scale) {
        if (!isCurrentPage && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
        } else if (isCurrentPage) {
            onActiveScaleChanged(scale)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.4f
                        }
                        onActiveScaleChanged(scale)
                    },
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (pointerCount >= 2) {
                            val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
                            scale = nextScale
                            offset = if (nextScale <= 1.02f) Offset.Zero else offset + panChange
                            onActiveScaleChanged(nextScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (scale > 1.05f && pointerCount == 1) {
                            offset += panChange
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            val request = remember(model, imageHeaders, retryNonce) {
                buildAuthedImageRequest(context, model, imageHeaders, retryNonce)
            }
            SubcomposeAsyncImage(
                model = request,
                contentDescription = item.caption ?: "图片预览",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                },
                error = {
                    Column(
                        modifier = Modifier
                            .bayClickable { retryNonce++ }
                            .clip(BayShapes.mdShape)
                            .background(Color.White.copy(alpha = 0.12f))
                            .padding(horizontal = BaySpacing.xl, vertical = BaySpacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(BaySpacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BrokenImage,
                            contentDescription = "图片加载失败",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                        Text(
                            text = "加载失败，点击重试",
                            style = BayType.footnote,
                            color = Color.White,
                        )
                    }
                },
                success = {
                    SubcomposeAsyncImageContent(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                },
            )
        }
    }
}

/**
 * 双击文本气泡后的全屏阅读弹窗：纯色表面承托，支持长按选择复制部分文字、点击空白处或返回键关闭。
 */
@Composable
fun TextFullscreenReader(
    text: String,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val blankInteraction = remember { MutableInteractionSource() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true),
    ) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
                .clickable(
                    interactionSource = blankInteraction,
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            // 可滚动居中文本阅读区（支持部分选中复制）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = BaySpacing.xxl, vertical = 64.dp),
                contentAlignment = Alignment.Center,
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = BayType.title2,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Start,
                    )
                }
            }

            BayIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = "关闭全屏阅读",
                onClick = onDismiss,
                style = BayIconStyle.Filled,
                size = 36.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(BaySpacing.lg),
            )
        }
    }
}
