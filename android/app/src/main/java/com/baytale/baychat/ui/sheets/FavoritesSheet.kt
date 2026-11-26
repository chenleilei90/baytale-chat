package com.baytale.baychat.ui.sheets

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.baytale.baychat.core.model.ChatItem
import com.baytale.baychat.core.model.ChatKind
import com.baytale.baychat.core.model.FavoriteItem
import com.baytale.baychat.core.storage.MediaDownloadHelper
import com.baytale.baychat.core.util.BayHaptic
import com.baytale.baychat.core.util.FormatUtils
import com.baytale.baychat.ui.design.BayActionSheet
import com.baytale.baychat.ui.design.BayBottomSheet
import com.baytale.baychat.ui.design.BayEmptyState
import com.baytale.baychat.ui.design.BayGroupDivider
import com.baytale.baychat.ui.design.BaySegmented
import com.baytale.baychat.ui.design.SheetAction
import com.baytale.baychat.ui.screens.chat.ImageViewer
import com.baytale.baychat.ui.screens.chat.TextFullscreenReader
import com.baytale.baychat.ui.screens.chat.copyTextToClipboard
import com.baytale.baychat.ui.theme.BayShapes
import com.baytale.baychat.ui.theme.BaySpacing
import com.baytale.baychat.ui.theme.BayTheme
import com.baytale.baychat.ui.theme.BayType
import java.io.File

private val FAVORITE_FILTERS = listOf("全部", "文字", "图片", "文件", "语音")

/**
 * 本地「我的收藏」抽屉页：支持分类筛选、点击查看/打开、长按发送/复制/删除及从聊天输入面板选择发送。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FavoritesSheet(
    favorites: List<FavoriteItem>,
    hasActiveRoom: Boolean,
    hapticEnabled: Boolean,
    pickerMode: Boolean = false,
    onSendFavorite: ((FavoriteItem) -> Unit)? = null,
    onDeleteFavorite: (String) -> Unit,
    onShowToast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = BayTheme.colors
    val context = LocalContext.current
    val view = LocalView.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var actionTarget by remember { mutableStateOf<FavoriteItem?>(null) }
    var readerText by remember { mutableStateOf<String?>(null) }
    var previewImage by remember { mutableStateOf<FavoriteItem?>(null) }

    val filtered = remember(favorites, selectedTab) {
        when (selectedTab) {
            1 -> favorites.filter { it.kind == "text" }
            2 -> favorites.filter { it.kind == "image" }
            3 -> favorites.filter { it.kind == "file" }
            4 -> favorites.filter { it.kind == "audio" }
            else -> favorites
        }
    }

    BayBottomSheet(
        onDismiss = onDismiss,
        title = if (pickerMode) "从收藏发送" else "我的收藏",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f),
            verticalArrangement = Arrangement.spacedBy(BaySpacing.md),
        ) {
            BaySegmented(
                options = FAVORITE_FILTERS,
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    BayEmptyState(
                        icon = Icons.Rounded.BookmarkBorder,
                        title = "暂无收藏内容",
                        message = "在聊天中长按消息并点击「收藏」，即可保存在本机随时查看与转发",
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(BayShapes.mdShape)
                        .background(if (colors.isDark) colors.surface else colors.bg),
                    contentPadding = PaddingValues(vertical = BaySpacing.xs),
                ) {
                    itemsIndexed(
                        items = filtered,
                        key = { _, item -> item.id },
                    ) { index, fav ->
                        val interaction = remember(fav.id) { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    interactionSource = interaction,
                                    indication = null,
                                    onClick = {
                                        if (pickerMode && onSendFavorite != null) {
                                            onSendFavorite(fav)
                                            onDismiss()
                                        } else {
                                            when (fav.kind) {
                                                "text" -> {
                                                    fav.text?.takeIf { it.isNotBlank() }?.let { readerText = it }
                                                }
                                                "image" -> {
                                                    val path = fav.filePath
                                                    if (path != null && File(path).exists()) {
                                                        previewImage = fav
                                                    } else {
                                                        onShowToast("收藏图片文件已不存在")
                                                    }
                                                }
                                                else -> {
                                                    val path = fav.filePath
                                                    if (path.isNullOrBlank()) {
                                                        onShowToast("收藏文件已不存在")
                                                    } else {
                                                        when (MediaDownloadHelper.openLocalFileDetailed(context, path, fav.mime ?: "*/*")) {
                                                            MediaDownloadHelper.OpenFileResult.OPENED -> Unit
                                                            MediaDownloadHelper.OpenFileResult.FILE_NOT_FOUND ->
                                                                onShowToast("收藏文件已被移除")
                                                            MediaDownloadHelper.OpenFileResult.NO_APP_FOUND ->
                                                                onShowToast("暂无可打开此类型文件的应用")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        BayHaptic.heavy(view, hapticEnabled)
                                        actionTarget = fav
                                    },
                                )
                                .padding(horizontal = BaySpacing.lg, vertical = BaySpacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(BaySpacing.md),
                        ) {
                            FavoriteThumb(fav = fav)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = when (fav.kind) {
                                        "text" -> fav.text.orEmpty()
                                        "image" -> fav.fileName?.takeIf { it.isNotBlank() } ?: "图片"
                                        else -> fav.fileName?.takeIf { it.isNotBlank() } ?: "文件"
                                    },
                                    style = BayType.callout.copy(fontWeight = FontWeight.Medium),
                                    color = colors.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val metaLine = buildString {
                                    if (fav.kind != "text" && fav.size > 0L) {
                                        append(FormatUtils.formatBytes(fav.size))
                                        append(" · ")
                                    }
                                    append("来自 ")
                                    append(fav.senderName.ifBlank { "伙伴" })
                                    if (fav.roomCode.isNotBlank()) {
                                        append(" · #")
                                        append(fav.roomCode)
                                    }
                                    append(" · ")
                                    append(FormatUtils.formatRelativeTime(fav.savedAt))
                                }
                                Text(
                                    text = metaLine,
                                    style = BayType.caption,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (index < filtered.lastIndex) {
                            BayGroupDivider(hasLeadingIcon = true)
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { fav ->
        val actions = buildList {
            if (hasActiveRoom && onSendFavorite != null) {
                add(
                    SheetAction(
                        label = "发送到当前房间",
                        icon = Icons.AutoMirrored.Rounded.Send,
                        onClick = {
                            onSendFavorite(fav)
                            onDismiss()
                        },
                    )
                )
            }
            if (fav.kind == "text" && !fav.text.isNullOrBlank()) {
                add(
                    SheetAction(
                        label = "复制内容",
                        icon = Icons.Rounded.ContentCopy,
                        onClick = {
                            copyTextToClipboard(context, fav.text)
                            BayHaptic.confirm(view, hapticEnabled)
                            onShowToast("已复制到剪贴板")
                        },
                    )
                )
            }
            add(
                SheetAction(
                    label = "删除收藏",
                    icon = Icons.Rounded.DeleteOutline,
                    destructive = true,
                    onClick = { onDeleteFavorite(fav.id) },
                )
            )
        }
        BayActionSheet(
            onDismiss = { actionTarget = null },
            actions = actions,
        )
    }

    readerText?.let { txt ->
        TextFullscreenReader(
            text = txt,
            onDismiss = { readerText = null },
        )
    }

    previewImage?.let { fav ->
        val tempItem = remember(fav.id) {
            ChatItem(
                id = fav.id,
                kind = ChatKind.IMAGE,
                senderId = "",
                senderName = fav.senderName,
                isSelf = false,
                timestamp = fav.savedAt,
                localFilePath = fav.filePath,
            )
        }
        ImageViewer(
            images = listOf(tempItem),
            initialIndex = 0,
            hapticEnabled = hapticEnabled,
            imageHeaders = emptyMap(),
            onSave = { item ->
                val src = item.localFilePath ?: return@ImageViewer false
                val saved = MediaDownloadHelper.saveImageToGallery(
                    context = context,
                    sourcePathOrUri = src,
                    fileName = fav.fileName ?: "baychat_${fav.savedAt}.jpg",
                    mimeType = fav.mime ?: "image/jpeg",
                )
                onShowToast(if (saved != null) "已保存至 ${saved.displayPath}" else "保存图片失败")
                saved != null
            },
            onShare = { item ->
                val src = item.localFilePath ?: return@ImageViewer
                val ok = MediaDownloadHelper.shareUriOrPath(context, src, fav.mime ?: "image/*", "分享图片")
                if (!ok) onShowToast("无法分享该图片")
            },
            onDismiss = { previewImage = null },
        )
    }
}

@Composable
private fun FavoriteThumb(fav: FavoriteItem) {
    val colors = BayTheme.colors
    if (fav.kind == "image" && !fav.filePath.isNullOrBlank()) {
        AsyncImage(
            model = File(fav.filePath),
            contentDescription = fav.fileName ?: "收藏图片",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(BayShapes.xsShape)
                .background(colors.fill),
        )
        return
    }
    val (icon, tint) = when (fav.kind) {
        "image" -> Icons.Rounded.Image to colors.fileTypeColors.image
        "audio" -> Icons.Rounded.Mic to colors.fileTypeColors.audio
        "file" -> Icons.Rounded.InsertDriveFile to colors.fileTypeColors.doc
        else -> Icons.Rounded.Description to colors.accent
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(BayShapes.xsShape)
            .background(tint.copy(alpha = if (colors.isDark) 0.22f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}
